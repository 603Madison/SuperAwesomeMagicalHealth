package com.health.app.ui.modules.meds

import com.health.api.MedsHistoryDay
import com.health.app.ui.modules.meds.MedsCalendar.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** The medication history's calendar, as the web's (web/test/medsHistory.test.ts). */
class MedsCalendarTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun day(date: String, scheduled: Int = 2, taken: Int = 2, skipped: Int = 0, missed: Int = 0, open: Int = 0, extra: Int = 0) =
        MedsHistoryDay(date, scheduled, taken, skipped, missed, open, extra)

    @Test
    fun `a month's weeks, Monday first`() {
        val weeks = MedsCalendar.weeks(YearMonth.of(2026, 11))
        // 1 November 2026 is a Sunday: six blanks first.
        assertEquals(listOf<LocalDate?>(null, null, null, null, null, null, LocalDate.of(2026, 11, 1)), weeks.first())
        assertEquals(LocalDate.of(2026, 11, 2), weeks[1][0])
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(30, weeks.flatten().count { it != null })
        assertEquals("November 2026", MedsCalendar.title(YearMonth.of(2026, 11), Locale.US))
        assertEquals("Today", MedsCalendar.dayTitle(today, today, Locale.US))
        assertEquals("Yesterday", MedsCalendar.dayTitle(today.minusDays(1), today, Locale.US))
        assertEquals("Fri 13 Nov", MedsCalendar.dayTitle(LocalDate.of(2026, 11, 13), today, Locale.US))
    }

    @Test
    fun `each day says what happened, with missed first`() {
        assertEquals(Mark.TAKEN, MedsCalendar.mark(day("2026-11-10"), today))
        assertEquals(Mark.MISSED, MedsCalendar.mark(day("2026-11-11", taken = 1, missed = 1), today))
        assertEquals(Mark.MISSED, MedsCalendar.mark(day("2026-11-12", taken = 0, skipped = 1, missed = 1), today))
        assertEquals(Mark.SKIPPED, MedsCalendar.mark(day("2026-11-13", taken = 1, skipped = 1), today))
        assertEquals(Mark.OPEN, MedsCalendar.mark(day("2026-11-18", taken = 1, open = 1), today))
        assertEquals(Mark.EXTRA, MedsCalendar.mark(day("2026-11-14", scheduled = 0, taken = 0, extra = 1), today))
        assertEquals(Mark.NONE, MedsCalendar.mark(day("2026-11-15", scheduled = 0, taken = 0), today))
        assertEquals(Mark.FUTURE, MedsCalendar.mark(day("2026-11-19", taken = 0), today))
        assertEquals(Mark.NONE, MedsCalendar.mark(null, today))
    }

    @Test
    fun `each day in words, and the month in a line`() {
        assertEquals("2 of 2 taken", MedsCalendar.text(day("2026-11-10"), today))
        assertEquals("0 of 2 taken · 1 skipped · 1 missed · 1 as needed", MedsCalendar.text(day("2026-11-12", taken = 0, skipped = 1, missed = 1, extra = 1), today))
        assertEquals("1 of 2 taken · 1 still to take", MedsCalendar.text(day("2026-11-18", taken = 1, open = 1), today))
        assertEquals("Nothing scheduled", MedsCalendar.text(day("2026-11-15", scheduled = 0, taken = 0), today))
        assertEquals("2 scheduled", MedsCalendar.text(day("2026-11-20", taken = 0), today))
        val month = listOf(
            day("2026-11-16"), day("2026-11-17", taken = 1, missed = 1), day("2026-11-18", taken = 1, open = 1),
            day("2026-11-19", taken = 0), day("2026-11-13", taken = 1, skipped = 1),
        )
        assertEquals("5 of 7 taken · 1 skipped · 1 missed", MedsCalendar.summary(month, today))
        assertNull(MedsCalendar.summary(listOf(day("2026-11-19", taken = 0)), today))
    }
}
