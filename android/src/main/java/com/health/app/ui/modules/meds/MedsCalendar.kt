package com.health.app.ui.modules.meds

import com.health.api.MedsHistoryDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The medication history's calendar (GET /api/meds/history, the History section on Medications),
 * worked out without drawing anything so it can be tested; as the web's lib/medsHistory.ts.
 */
object MedsCalendar {
    /**
     * What a day shows: all taken; any missed (it's over and one was never marked); some skipped
     * (none missed); today with some still to take; only doses taken as needed; nothing; or a day
     * still to come.
     */
    enum class Mark { TAKEN, MISSED, SKIPPED, OPEN, EXTRA, NONE, FUTURE }

    /** The key under the calendar. */
    val KEY = listOf(Mark.TAKEN to "All taken", Mark.MISSED to "Missed one", Mark.SKIPPED to "Skipped", Mark.OPEN to "Still to take")

    /** The weekdays' initials over the columns, Monday first as the schedules count them. */
    val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun mark(d: MedsHistoryDay?, today: LocalDate): Mark {
        if (d == null) return Mark.NONE
        if (LocalDate.parse(d.date).isAfter(today)) return Mark.FUTURE
        if (d.scheduled == 0) return if (d.extra > 0) Mark.EXTRA else Mark.NONE
        if (d.missed > 0) return Mark.MISSED
        if (d.open > 0) return Mark.OPEN
        return if (d.taken >= d.scheduled) Mark.TAKEN else Mark.SKIPPED
    }

    /** A day in words, for its cell's description: "2 of 2 taken", "1 of 2 taken · 1 missed", "1 as needed". */
    fun text(d: MedsHistoryDay?, today: LocalDate): String {
        if (d == null || (d.scheduled == 0 && d.extra == 0)) return "Nothing scheduled"
        if (LocalDate.parse(d.date).isAfter(today)) return "${d.scheduled} scheduled"
        return listOfNotNull(
            "${d.taken} of ${d.scheduled} taken".takeIf { d.scheduled > 0 },
            "${d.skipped} skipped".takeIf { d.skipped > 0 },
            "${d.missed} missed".takeIf { d.missed > 0 },
            "${d.open} still to take".takeIf { d.open > 0 },
            "${d.extra} as needed".takeIf { d.extra > 0 },
        ).joinToString(" · ")
    }

    /** The month in a line, over the doses whose time has come (today's still to take aside): "26 of 28 taken · 1 missed". */
    fun summary(days: List<MedsHistoryDay>, today: LocalDate): String? {
        val done = days.filter { !LocalDate.parse(it.date).isAfter(today) }
        val taken = done.sumOf { it.taken }
        val skipped = done.sumOf { it.skipped }
        val missed = done.sumOf { it.missed }
        val total = taken + skipped + missed
        if (total == 0) return null
        return listOfNotNull("$taken of $total taken", "$skipped skipped".takeIf { skipped > 0 }, "$missed missed".takeIf { missed > 0 }).joinToString(" · ")
    }

    /** The month's weeks, Monday first: each day, or null outside the month. */
    fun weeks(month: YearMonth): List<List<LocalDate?>> {
        val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells = MutableList<LocalDate?>(lead) { null }
        (1..month.lengthOfMonth()).forEach { cells += month.atDay(it) }
        while (cells.size % 7 != 0) cells += null
        return cells.chunked(7)
    }

    /** "November 2026". */
    fun title(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)

    /** A day picked: "Today", "Yesterday", or "Tue 17 Nov". */
    fun dayTitle(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)
    }
}
