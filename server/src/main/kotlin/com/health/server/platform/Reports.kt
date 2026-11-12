package com.health.server.platform

import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.Units
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.util.Days
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A PDF for a doctor: the period's numbers, feature by feature, in plain words, tables and charts. */
class ReportService(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.report")

    /** The sections asked for, or (by default) the overview and every feature that's on. */
    fun sectionsFor(profile: Profile, asked: Set<ReportSectionKey>?): List<ReportSectionKey> =
        ReportSectionKey.entries.filter { key ->
            if (asked != null) key in asked && (key.module == null || key.module in profile.modules)
            else key.module == null || key.module in profile.modules
        }

    suspend fun sections(userId: String, profile: Profile, from: LocalDate, to: LocalDate, keys: List<ReportSectionKey>): List<ReportSection> {
        val hooks = services.hooks.filter { it.module == null || it.module in profile.modules }
        val made = hooks.mapNotNull { hook ->
            runCatching { hook.report(userId, profile, from, to) }.onFailure { log.warn("A report section failed: {}", it.message, it) }.getOrNull()
        }
        // Two features can share a section (food and drinks; the cycle and life stages): they go together.
        return keys.mapNotNull { key ->
            made.filter { it.key == key }.takeIf { it.isNotEmpty() }?.let { parts ->
                ReportSection(key, parts.flatMap { it.summary }, parts.flatMap { it.tables }, parts.flatMap { it.charts })
            }
        }
    }

    suspend fun pdf(userId: String, from: LocalDate, to: LocalDate, asked: Set<ReportSectionKey>?): ByteArray {
        if (from.isAfter(to)) throw ApiError.badRequest("The start comes after the end")
        if (ChronoUnit.DAYS.between(from, to) > 730) throw ApiError.badRequest("A report covers two years at most")
        val profile = services.profiles.get(userId)
        val user = services.users.byId(userId) ?: throw ApiError.notFound()
        val zone = Days.zoneOf(profile.timezone)
        val sections = sections(userId, profile, from, to, sectionsFor(profile, asked))
        return render(user.displayName, profile.units, from, to, sections, zone, services.clock())
    }

    companion object {
        private val TEXT = Color(0x11, 0x18, 0x27)
        private val MUTED = Color(0x4b, 0x55, 0x65)
        private val LINE = Color(0xdd, 0xe2, 0xea)
        private val ZEBRA = Color(0xf4, 0xf6, 0xfb)
        private val COVER = Color(0xec, 0xe6, 0xff)
        private val COVER_2 = Color(0xdc, 0xf7, 0xec)
        private val BAND = Color(0xe3, 0xf6, 0xee)
        /** The web app's chart colours in the light Aurora theme (--c1, --c2, --c3). */
        val SERIES = listOf(Color(0x6a, 0x3f, 0xd6), Color(0x00, 0x80, 0x5e), Color(0xc2, 0x41, 0x0c))

        fun render(name: String, units: Units, from: LocalDate, to: LocalDate, sections: List<ReportSection>, zone: ZoneId, now: Long): ByteArray {
            PDDocument().use { doc ->
                // Not under /fonts: the Android app's tests have the server on their classpath, and Robolectric reads /fonts as Android's own.
                val regular = loadFont(doc, "/report-fonts/Lexend-Regular.ttf") ?: PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val bold = loadFont(doc, "/report-fonts/Lexend-SemiBold.ttf") ?: PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
                val size = if (units == Units.IMPERIAL) PDRectangle.LETTER else PDRectangle.A4
                val pdf = Layout(doc, size, regular, bold)
                pdf.cover(name, from, to, now, zone)
                if (sections.isEmpty()) pdf.paragraph("Nothing to show for this period.", regular, 11f, MUTED)
                sections.forEach { pdf.section(it, zone) }
                pdf.finish()
                pdf.footers("Made with SuperAwesomeMagicalHealth · $name · ${Say.range(from, to)}")
                return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
        }

        private fun loadFont(doc: PDDocument, path: String): PDFont? =
            runCatching { ReportService::class.java.getResourceAsStream(path)?.use { PDType0Font.load(doc, it) } }.getOrNull()
    }

    private class Layout(val doc: PDDocument, val size: PDRectangle, val regular: PDFont, val bold: PDFont) {
        val margin = 48f
        val width = size.width - 2 * margin
        private var page: PDPage? = null
        private var cs: PDPageContentStream? = null
        var y = 0f
        private val glyphs = HashMap<Pair<PDFont, Char>, Boolean>()

        private val out: PDPageContentStream get() = cs ?: newPage().let { cs!! }

        fun newPage() {
            cs?.close()
            val p = PDPage(size)
            doc.addPage(p)
            page = p
            cs = PDPageContentStream(doc, p)
            y = size.height - margin
        }

        fun finish() { cs?.close(); cs = null }

        fun ensure(height: Float) { if (cs == null || y - height < margin + 28) newPage() }

        /** Only characters the font can draw (Lexend covers Latin; anything else becomes "?"). */
        fun clean(text: String, font: PDFont): String = text.map { c ->
            when {
                c == '\n' || c == '\t' -> ' '
                glyphs.getOrPut(font to c) { runCatching { font.encode(c.toString()); true }.getOrDefault(false) } -> c
                else -> '?'
            }
        }.joinToString("")

        fun widthOf(text: String, font: PDFont, pt: Float) = font.getStringWidth(clean(text, font)) / 1000f * pt

        fun text(text: String, font: PDFont, pt: Float, x: Float, atY: Float, color: Color = TEXT) {
            val s = out
            s.beginText()
            s.setFont(font, pt)
            s.setNonStrokingColor(color)
            s.newLineAtOffset(x, atY)
            s.showText(clean(text, font))
            s.endText()
        }

        fun fill(x: Float, atY: Float, w: Float, h: Float, color: Color) {
            out.setNonStrokingColor(color)
            out.addRect(x, atY, w, h)
            out.fill()
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, lineWidth: Float = 0.6f) {
            out.setStrokingColor(color)
            out.setLineWidth(lineWidth)
            out.moveTo(x1, y1)
            out.lineTo(x2, y2)
            out.stroke()
        }

        fun wrap(text: String, font: PDFont, pt: Float, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            var current = ""
            text.split(' ').forEach { word ->
                val next = if (current.isEmpty()) word else "$current $word"
                if (widthOf(next, font, pt) <= maxWidth || current.isEmpty()) current = next
                else { lines += current; current = word }
            }
            if (current.isNotEmpty()) lines += current
            return lines
        }

        fun paragraph(text: String, font: PDFont, pt: Float, color: Color = TEXT, indent: Float = 0f) {
            wrap(text, font, pt, width - indent).forEach { l ->
                ensure(pt * 1.45f)
                y -= pt * 1.45f
                text(l, font, pt, margin + indent, y, color)
            }
        }

        fun cover(name: String, from: LocalDate, to: LocalDate, now: Long, zone: ZoneId) {
            newPage()
            val top = size.height - margin
            fill(margin, top - 118, width, 118f, COVER)
            fill(margin + width * 0.62f, top - 118, width * 0.38f, 118f, COVER_2)
            text("Health report", bold, 24f, margin + 22, top - 44)
            text(name, regular, 14f, margin + 22, top - 68)
            text(Say.range(from, to), regular, 11f, margin + 22, top - 90, MUTED)
            val made = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            text("Made ${Say.date(made)}", regular, 9f, margin + 22, top - 106, MUTED)
            y = top - 138
            paragraph(
                "Recorded by the person's devices and entered by them in SuperAwesomeMagicalHealth. Scores compare each day with the " +
                    "person's own recent days; they're a guide, not a diagnosis.",
                regular, 9.5f, MUTED,
            )
        }

        fun section(section: ReportSection, zone: ZoneId) {
            ensure(60f)
            y -= 26f
            text(section.key.label, bold, 16f, margin, y)
            y -= 7f
            line(margin, y, margin + width, y, SERIES[0], 1.2f)
            y -= 2f
            section.summary.forEach { paragraph(it, regular, 10.5f) }
            section.charts.filter { c -> c.series.any { it.second.isNotEmpty() } }.forEach { chart(it, zone) }
            section.tables.forEach { table(it) }
        }

        fun table(table: ReportTable) {
            if (table.rows.isEmpty()) return
            val pt = 9f
            val rowHeight = 15f
            val columns = table.header.size
            val wanted = (0 until columns).map { c ->
                max(widthOf(table.header[c], bold, pt), table.rows.maxOf { widthOf(it.getOrElse(c) { "" }, regular, pt) }) + 12f
            }
            val total = wanted.sum()
            val widths = if (total <= width) wanted.map { it * width / total } else {
                // Shrink the widest columns first, keeping each at least a little room.
                val scale = width / total
                wanted.map { max(40f, it * scale) }.let { ws -> ws.map { it * width / ws.sum() } }
            }
            fun header() {
                ensure(rowHeight * 2 + 18f)
                y -= 18f
                text(table.title, bold, 10.5f, margin, y)
                y -= rowHeight
                var x = margin
                table.header.forEachIndexed { i, h -> text(fit(h, bold, pt, widths[i] - 8f), bold, pt, x + 4f, y + 4f, MUTED); x += widths[i] }
                line(margin, y, margin + width, y, LINE)
            }
            header()
            table.rows.forEachIndexed { index, row ->
                if (y - rowHeight < margin + 28) { newPage(); header() }
                y -= rowHeight
                if (index % 2 == 1) fill(margin, y, width, rowHeight, ZEBRA)
                var x = margin
                row.forEachIndexed { i, cell -> if (i < columns) { text(fit(cell, regular, pt, widths[i] - 8f), regular, pt, x + 4f, y + 4f); x += widths[i] } }
            }
            y -= 4f
        }

        private fun fit(text: String, font: PDFont, pt: Float, maxWidth: Float): String {
            if (widthOf(text, font, pt) <= maxWidth) return text
            var cut = text
            while (cut.isNotEmpty() && widthOf("$cut…", font, pt) > maxWidth) cut = cut.dropLast(1)
            return "$cut…"
        }

        fun chart(chart: ReportChart, zone: ZoneId) {
            val height = 140f
            ensure(height + 46f)
            y -= 18f
            text(chart.title + if (chart.unit.isNotBlank()) " (${chart.unit})" else "", bold, 10.5f, margin, y)
            val series = chart.series.filter { it.second.isNotEmpty() }
            val all = series.flatMap { it.second }
            var lo = all.minOf { it.v }
            var hi = all.maxOf { it.v }
            chart.band?.let { lo = min(lo, it.start); hi = max(hi, it.endInclusive) }
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val step = niceStep((hi - lo) / 4)
            lo = floor(lo / step) * step
            hi = ceil(hi / step) * step
            val t0 = all.minOf { it.t }
            val t1 = max(all.maxOf { it.t }, t0 + 86_400_000L)
            val left = margin + 36f
            val right = margin + width - 6f
            val bottom = y - height - 6f
            val top = y - 12f
            fun px(t: Long) = left + (right - left) * ((t - t0).toFloat() / (t1 - t0).toFloat())
            fun py(v: Double) = bottom + (top - bottom) * ((v - lo) / (hi - lo)).toFloat()
            chart.band?.let { band -> fill(left, py(band.start), right - left, py(band.endInclusive) - py(band.start), BAND) }
            var tick = lo
            while (tick <= hi + step / 2) {
                val ty = py(tick)
                line(left, ty, right, ty, LINE, 0.5f)
                val label = if (step >= 1) "%.0f".format(tick) else "%.1f".format(tick)
                text(label, regular, 7.5f, left - 6f - widthOf(label, regular, 7.5f), ty - 2.5f, MUTED)
                tick += step
            }
            val firstDay = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate()
            val lastDay = Instant.ofEpochMilli(t1).atZone(zone).toLocalDate()
            text(Say.shortDate(firstDay), regular, 7.5f, left, bottom - 11f, MUTED)
            val end = Say.shortDate(lastDay)
            text(end, regular, 7.5f, right - widthOf(end, regular, 7.5f), bottom - 11f, MUTED)
            series.forEachIndexed { i, (_, points) ->
                val color = SERIES[i % SERIES.size]
                val sorted = points.sortedBy { it.t }
                out.setStrokingColor(color)
                out.setLineWidth(1.4f)
                sorted.forEachIndexed { j, p -> if (j == 0) out.moveTo(px(p.t), py(p.v)) else out.lineTo(px(p.t), py(p.v)) }
                if (sorted.size > 1) out.stroke()
                if (sorted.size <= 45) sorted.forEach { p -> fill(px(p.t) - 1.6f, py(p.v) - 1.6f, 3.2f, 3.2f, color) }
            }
            y = bottom - 18f
            if (series.size > 1 || chart.band != null) {
                y -= 12f
                var x = left
                series.forEachIndexed { i, (label, _) ->
                    fill(x, y - 1f, 8f, 8f, SERIES[i % SERIES.size])
                    text(label, regular, 8f, x + 12f, y, MUTED)
                    x += widthOf(label, regular, 8f) + 28f
                }
                if (chart.band != null) {
                    fill(x, y - 1f, 8f, 8f, BAND)
                    text("Target range", regular, 8f, x + 12f, y, MUTED)
                }
                y -= 8f
            }
        }

        private fun niceStep(raw: Double): Double {
            if (raw <= 0) return 1.0
            val magnitude = 10.0.pow(floor(log10(raw)))
            val residual = raw / magnitude
            val nice = when {
                residual <= 1 -> 1.0
                residual <= 2 -> 2.0
                residual <= 2.5 -> 2.5
                residual <= 5 -> 5.0
                else -> 10.0
            }
            return nice * magnitude
        }

        fun footers(label: String) {
            val count = doc.numberOfPages
            doc.pages.forEachIndexed { index, p ->
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
                    s.setStrokingColor(LINE)
                    s.setLineWidth(0.5f)
                    s.moveTo(margin, margin + 6f)
                    s.lineTo(margin + width, margin + 6f)
                    s.stroke()
                    s.beginText()
                    s.setFont(regular, 7.5f)
                    s.setNonStrokingColor(MUTED)
                    s.newLineAtOffset(margin, margin - 6f)
                    s.showText(clean(label, regular))
                    s.endText()
                    val pageText = "Page ${index + 1} of $count"
                    s.beginText()
                    s.setFont(regular, 7.5f)
                    s.newLineAtOffset(margin + width - widthOf(pageText, regular, 7.5f), margin - 6f)
                    s.showText(pageText)
                    s.endText()
                }
            }
        }
    }
}
