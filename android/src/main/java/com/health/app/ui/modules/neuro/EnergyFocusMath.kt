package com.health.app.ui.modules.neuro

import com.health.api.EnergyCheck
import com.health.api.EnergyFocus
import com.health.api.EnergyFocusSettings
import com.health.api.Medication
import com.health.api.TimeBlock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Energy and focus (Autism & ADHD): quick check-ins, energy 1 (running on empty) to 5 (plenty) and
 * focus 1 (all over the place) to 5 (locked in), and the person's own patterns from them, worked
 * out by the server (GET /api/neuro/energy-focus). Here is what the app adds, in plain Kotlin so
 * it's tested without a phone: the words, where things go on today's strip, and the settings'
 * small sums. Patterns are associations in the person's own check-ins, never causes.
 */

object EnergyFocusMath {
    /** How far back patterns look (the server's NeuroService.PATTERN_DAYS). */
    const val PATTERN_DAYS = 42

    /** Patterns start to show from this many check-ins over this many days (the server's EnergyPatterns.MIN_CHECKS and MIN_DAYS). */
    const val MIN_CHECKS = 12
    const val MIN_DAYS = 5

    /** At most this many reminder times (as the server keeps). */
    const val MAX_REMINDERS = 6

    const val ENERGY_LOW = "Running on empty"
    const val ENERGY_HIGH = "Plenty"
    const val FOCUS_LOW = "All over the place"
    const val FOCUS_HIGH = "Locked in"

    /** Under every set of patterns. */
    const val CAPTION = "Patterns in your own check-ins, not proof of cause."

    // ---- Today's strip: 6 AM to midnight ----

    const val STRIP_FROM = 6
    const val STRIP_TO = 24

    /** The strip's time labels, at their places along it. */
    val STRIP_LABELS: List<Pair<Float, String>> = listOf(0f to "6 AM", 1f / 3 to "Noon", 2f / 3 to "6 PM", 1f to "Midnight")

    /** Where [at] falls along [date]'s strip, 0 (6 AM) to 1 (midnight); null before 6 AM or on another day. */
    fun stripX(at: Long, date: LocalDate, zone: ZoneId): Float? {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        if (t.toLocalDate() != date) return null
        val hours = t.hour + t.minute / 60f + t.second / 3600f
        if (hours < STRIP_FROM) return null
        return ((hours - STRIP_FROM) / (STRIP_TO - STRIP_FROM)).coerceIn(0f, 1f)
    }

    /** How high a 1–5 rating sits on the strip: 0 at the top (5) to 1 at the bottom (1). */
    fun stripY(value: Int): Float = (5 - value.coerceIn(1, 5)) / 4f

    enum class MarkKind { ENERGY, FOCUS, DOSE }

    /** One mark on the strip: a check-in's energy or focus at its height, or a dose (the full height). */
    data class Mark(val kind: MarkKind, val x: Float, val y: Float?, val at: Long, val value: Int? = null)

    /** Today's marks, in time order: each check-in's energy and focus, and each dose of the ADHD medication. */
    fun marks(checks: List<EnergyCheck>, doses: List<Long>, date: LocalDate, zone: ZoneId): List<Mark> = buildList {
        doses.sorted().forEach { at -> stripX(at, date, zone)?.let { add(Mark(MarkKind.DOSE, it, null, at)) } }
        checks.sortedBy { it.at }.forEach { c ->
            val x = stripX(c.at, date, zone) ?: return@forEach
            c.energy?.let { add(Mark(MarkKind.ENERGY, x, stripY(it), c.at, it.coerceIn(1, 5))) }
            c.focus?.let { add(Mark(MarkKind.FOCUS, x, stripY(it), c.at, it.coerceIn(1, 5))) }
        }
    }

    /** Today's check-ins from the small hours, before the strip begins (said in words instead). */
    fun beforeStrip(checks: List<EnergyCheck>, date: LocalDate, zone: ZoneId): Int =
        checks.count { c -> Instant.ofEpochMilli(c.at).atZone(zone).let { it.toLocalDate() == date && it.hour < STRIP_FROM } }

    // ---- Words ----

    /** "Energy 4 · focus 2", or just the one given; null with neither. */
    fun ratings(c: EnergyCheck): String? =
        listOfNotNull(c.energy?.let { "energy $it" }, c.focus?.let { "focus $it" }).joinToString(" · ").ifEmpty { null }?.replaceFirstChar(Char::uppercase)

    /** "Energy 4 · focus 2 at 9:45 AM": a check-in in a line, as Care says it. */
    fun checkLine(c: EnergyCheck, clock: (Long) -> String): String? = ratings(c)?.let { "$it at ${clock(c.at)}" }

    /** What else was going on, in the person's words: "stressed, in a hyperfocus · a note". */
    fun detail(c: EnergyCheck): String? = listOfNotNull(
        c.tags.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase(Locale.ROOT) }.ifEmpty { null },
        c.note?.trim()?.ifEmpty { null },
    ).joinToString(" · ").ifEmpty { null }?.replaceFirstChar(Char::uppercase)

    /** A check-in for a screen reader: "9:45 AM: energy 4 of 5, focus 2 of 5, stressed". */
    fun spoken(c: EnergyCheck, clock: (Long) -> String): String = "${clock(c.at)}: " + listOfNotNull(
        c.energy?.let { "energy $it of 5" },
        c.focus?.let { "focus $it of 5" },
        c.tags.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase(Locale.ROOT) }.ifEmpty { null },
    ).joinToString(", ")

    /** What a rating's circle says to a screen reader: "Energy 1 of 5, running on empty". */
    fun optionSpoken(label: String, n: Int, low: String, high: String): String = "$label $n of 5" + when (n) {
        1 -> ", ${low.lowercase(Locale.ROOT)}"
        5 -> ", ${high.lowercase(Locale.ROOT)}"
        else -> ""
    }

    /** Whether a check-in can be saved: energy or focus, at least. */
    fun canSave(energy: Int?, focus: Int?): Boolean = energy != null || focus != null

    // ---- Patterns ----

    /** How far toward patterns, 0–1: check-ins and days count half each, so every check-in moves it. */
    fun progress(checks: Int, days: Int): Float =
        (checks.coerceIn(0, MIN_CHECKS).toFloat() / MIN_CHECKS + days.coerceIn(0, MIN_DAYS).toFloat() / MIN_DAYS) / 2f

    /** "5 check-ins over 3 days so far", or how to begin. */
    fun soFar(checks: Int, days: Int): String = when {
        checks <= 0 -> "Check in a few times a day, whenever you think of it."
        else -> "${count(checks, "check-in")} over ${count(days.coerceAtLeast(1), "day")} so far"
    }

    /** "From 34 check-ins over 12 days." */
    fun basedOn(checks: Int, days: Int): String = "From ${count(checks, "check-in")} over ${count(days.coerceAtLeast(1), "day")}."

    /** The summary's lines to show above the charts: each factor has its own title and sentence below, so theirs aren't repeated. */
    fun summaryLines(ef: EnergyFocus): List<String> = ef.summary.filterNot { line -> ef.factors.any { it.sentence == line } }

    /** "9 AM – noon", "3 – 6 PM", "midnight – 6 AM": hours of the day, as the server says them. */
    fun range(from: Int, to: Int): String {
        fun clock(h: Int) = when (h % 24) {
            0 -> "midnight"
            12 -> "noon"
            in 1..11 -> "${h % 24} AM"
            else -> "${h % 24 - 12} PM"
        }
        val a = clock(from)
        val b = clock(to)
        val samePart = from in 13..23 && to in 13..23 || from in 1..11 && to in 1..11
        return if (samePart) "${a.substringBefore(' ')} – $b" else "$a – $b"
    }

    /** A block of the day, as a chart row's label: "9 AM – noon", "Midnight – 6 AM". */
    fun dayBlock(b: TimeBlock): String = range(b.from, b.to).replaceFirstChar(Char::uppercase)

    /** Hours since a dose, as a chart row's label: "Up to 1 hour", "1–3 hours". */
    fun doseBlock(b: TimeBlock): String = if (b.from == 0 && b.to == 1) "Up to 1 hour" else "${b.from}–${b.to} hours"

    /** "Since your Ritalin". */
    fun sinceTitle(medication: String?): String = "Since your ${medication ?: "ADHD medication"}"

    /** A chart row for a screen reader: "9 AM – noon: energy 3.6, focus 4.1, from 8 check-ins". */
    fun blockSpoken(label: String, b: TimeBlock): String = "$label: " + listOfNotNull(
        b.energy?.let { "energy ${one(it)}" },
        b.focus?.let { "focus ${one(it)}" },
        "from ${count(b.count, "check-in")}",
    ).joinToString(", ")

    /** Where a 1–5 average sits along a chart row, 0–1. */
    fun scaleX(value: Double): Float = ((value.coerceIn(1.0, 5.0) - 1.0) / 4.0).toFloat()

    // ---- Settings ----

    /** The medications to choose the ADHD one from: those in use, and the chosen one even if it's stopped, by name. */
    fun choices(meds: List<Medication>, chosenId: String?): List<Medication> =
        meds.filter { it.id != null && (it.active || it.id == chosenId) }.sortedBy { it.name.lowercase(Locale.ROOT) }

    /**
     * Which of [choices] is the ADHD medication now: the chosen one, none ("None of these", or
     * nothing found), or the one found by its name (as the web's medicationChoice).
     */
    fun selected(choices: List<Medication>, ef: EnergyFocus): String? {
        ef.settings.medicationId?.takeIf { id -> choices.any { it.id == id } }?.let { return it }
        if (ef.settings.noMedication) return null
        return ef.medication?.let { name -> choices.firstOrNull { it.name == name }?.id }
    }

    /** The settings with [id] as the ADHD medication, or "None of these" (null): then no name counts either. */
    fun choose(settings: EnergyFocusSettings, id: String?): EnergyFocusSettings = settings.copy(medicationId = id, noMedication = id == null)

    /** What the settings say about the ADHD medication. */
    fun medicationLine(ef: EnergyFocus): String = when {
        ef.medication == null -> if (ef.settings.noMedication) "None of these" else "None chosen"
        ef.settings.medicationId != null -> ef.medication!!
        else -> "${ef.medication}, found by its name"
    }

    /** [times] with [time] added, kept as the server keeps them: "HH:mm", in order, no repeats, at most six. */
    fun withReminder(times: List<String>, time: LocalTime): List<String> =
        (times + "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)).distinct().sorted().take(MAX_REMINDERS)

    fun withoutReminder(times: List<String>, time: String): List<String> = times.filter { it != time }

    /** "10:00" as the phone says times: "10:00 AM". */
    fun reminderText(time: String): String =
        try {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.parse(time))
        } catch (e: java.time.format.DateTimeParseException) {
            time
        }

    private fun count(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    private fun one(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
