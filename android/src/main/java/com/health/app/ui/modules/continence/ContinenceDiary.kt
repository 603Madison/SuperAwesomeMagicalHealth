package com.health.app.ui.modules.continence

import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A bladder and bowel diary to share with a doctor, nurse or physio: the last few days as a table
 * (time, what, volume, urgency, leak, triggers), then each day's totals and the fluid drunk. Plain
 * text in CSV form, so it pastes into an email or opens in a spreadsheet.
 */
object ContinenceDiary {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val TITLE_DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

    fun export(entries: List<ContinenceEntry>, days: List<ContinenceDay>, zone: ZoneId, today: LocalDate, dayCount: Int = 3): String {
        val first = today.minusDays(dayCount - 1L)
        val dates = (0 until dayCount).map { first.plusDays(it.toLong()) }
        fun dateOf(e: ContinenceEntry) = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
        val shown = entries.filter { dateOf(it) in dates }.sortedBy { it.at }
        return buildString {
            appendLine("Bladder and bowel diary, ${TITLE_DAY.format(first)} to ${TITLE_DAY.format(today)}")
            appendLine()
            appendLine("Date,Time,What,Volume (ml),Urgency (0-3),Leak,Triggers,Stool type (Bristol),Notes")
            shown.forEach { e ->
                val time = Instant.ofEpochMilli(e.at).atZone(zone)
                val notes = listOfNotNull(
                    "at night".takeIf { e.night },
                    "pain".takeIf { e.pain },
                    "blood".takeIf { e.blood },
                    e.reps?.let { "$it reps" + (e.holdSeconds?.let { s -> " of ${s}s" } ?: "") },
                    e.note,
                ).joinToString("; ")
                appendLine(
                    listOf(
                        time.toLocalDate().toString(), TIME.format(time), e.kind.label, e.volumeMl?.toString().orEmpty(),
                        e.urgency?.toString().orEmpty(), e.leak?.label.orEmpty(), e.triggers.joinToString("; "),
                        e.bristol?.toString().orEmpty(), notes,
                    ).joinToString(",") { csv(it) },
                )
            }
            if (shown.isEmpty()) appendLine("(nothing logged)")
            appendLine()
            appendLine("Date,Toilet trips,At night,Leaks,Bowel movements,Pad changes,Fluid drunk (ml)")
            dates.forEach { date ->
                val logged = shown.filter { dateOf(it) == date }
                val day = days.firstOrNull { it.date == date.toString() }
                val voids = day?.voids ?: logged.count { it.kind == ContinenceKind.URINATION }
                val night = day?.nightVoids ?: logged.count { it.kind == ContinenceKind.URINATION && it.night }
                val leaks = day?.leaks ?: logged.count { it.kind == ContinenceKind.LEAK }
                val bowel = day?.bowel ?: logged.count { it.kind == ContinenceKind.BOWEL }
                val pads = day?.pads ?: logged.count { it.kind == ContinenceKind.PAD_CHANGE }
                val fluid = day?.fluidMl?.takeIf { it > 0 }?.toString().orEmpty()
                appendLine(listOf(date.toString(), "$voids", "$night", "$leaks", "$bowel", "$pads", fluid).joinToString(","))
            }
        }.trimEnd() + "\n"
    }

    /** A CSV field: quoted when it holds a comma, a quote or a line break. */
    fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"" else value
}
