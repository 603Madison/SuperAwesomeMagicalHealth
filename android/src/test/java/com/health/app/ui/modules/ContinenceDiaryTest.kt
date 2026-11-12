package com.health.app.ui.modules

import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.LeakSize
import com.health.app.ui.modules.continence.ContinenceDiary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ContinenceDiaryTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.parse("2026-11-12")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `three days of entries, then each day's totals`() {
        val entries = listOf(
            ContinenceEntry(at("2026-11-12T07:10"), ContinenceKind.URINATION, volumeMl = 350, urgency = 2),
            ContinenceEntry(at("2026-11-11T03:20"), ContinenceKind.URINATION, urgency = 1, night = true),
            ContinenceEntry(at("2026-11-11T14:05"), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough", "laugh"), note = "at the gym, after a run"),
            ContinenceEntry(at("2026-11-10T09:00"), ContinenceKind.BOWEL, bristol = 4),
            ContinenceEntry(at("2026-11-10T21:00"), ContinenceKind.PELVIC_FLOOR, reps = 10, holdSeconds = 5),
            // Four days ago: not in a three-day diary.
            ContinenceEntry(at("2026-11-09T09:00"), ContinenceKind.LEAK, leak = LeakSize.LARGE),
        )
        val days = listOf(ContinenceDay("2026-11-11", voids = 6, nightVoids = 1, leaks = 1, fluidMl = 1500))

        val text = ContinenceDiary.export(entries, days, zone, today)
        val expected = """
            Bladder and bowel diary, 10 Nov 2026 to 12 Nov 2026

            Date,Time,What,Volume (ml),Urgency (0-3),Leak,Triggers,Stool type (Bristol),Notes
            2026-11-10,09:00,Bowel movement,,,,,4,
            2026-11-10,21:00,Pelvic floor exercise,,,,,,10 reps of 5s
            2026-11-11,03:20,Toilet trip,,1,,,,at night
            2026-11-11,14:05,Leak,,,Small,cough; laugh,,"at the gym, after a run"
            2026-11-12,07:10,Toilet trip,350,2,,,,

            Date,Toilet trips,At night,Leaks,Bowel movements,Pad changes,Fluid drunk (ml)
            2026-11-10,0,0,0,1,0,
            2026-11-11,6,1,1,0,0,1500
            2026-11-12,1,0,0,0,0,
        """.trimIndent() + "\n"
        assertEquals(expected, text)
        assertFalse(text.contains("Large"))
    }

    @Test
    fun `an empty diary says so`() {
        val text = ContinenceDiary.export(emptyList(), emptyList(), zone, today)
        assertEquals("(nothing logged)", text.lines()[3])
    }

    @Test
    fun `fields with commas, quotes or line breaks are quoted`() {
        assertEquals("plain", ContinenceDiary.csv("plain"))
        assertEquals("\"a, b\"", ContinenceDiary.csv("a, b"))
        assertEquals("\"it said \"\"now\"\"\"", ContinenceDiary.csv("it said \"now\""))
        assertEquals("\"two lines\"", ContinenceDiary.csv("two\nlines"))
    }
}
