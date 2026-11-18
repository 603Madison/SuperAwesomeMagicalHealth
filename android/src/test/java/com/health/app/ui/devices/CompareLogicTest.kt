package com.health.app.ui.devices

import com.health.api.ComparedMetric
import com.health.api.CompareKey
import com.health.api.Device
import com.health.api.DeviceComparison
import com.health.api.DeviceKind
import com.health.api.RecentDevice
import com.health.api.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class CompareLogicTest {
    private val f = CompareFixtures
    private val us = Locale.US
    private val ringAndStrap = Compare.names(f.strap, f.ring)
    private val ringAndWatch = Compare.names(f.watch, f.ring)

    init {
        Locale.setDefault(Locale.US)
    }

    /** Times read the same whichever space the JDK puts before AM and PM. */
    private fun String.plain() = replace("\u202F", " ")

    private fun metric(key: CompareKey, count: Int, a: Double, b: Double, difference: Double, apart: Double, lowest: Double, highest: Double) =
        ComparedMetric(key, count, a, b, difference, apart, lowest, highest)

    @Test
    fun `devices are called ring, strap and watch where that tells them apart, else by name`() {
        assertEquals(Compare.Names("strap", "ring", short = true), ringAndStrap)
        assertEquals("The ring", ringAndStrap.subject(ringAndStrap.b))
        assertEquals("the strap", ringAndStrap.within(ringAndStrap.a))
        assertEquals("Strap", ringAndStrap.labelA)
        // Two rings: their names.
        val other = RecentDevice("r2", "COLMI R10_77AA", DeviceKind.COLMI_RING)
        assertEquals(Compare.Names("COLMI R10_77AA", "COLMI R02_C301", short = false), Compare.names(other, f.ring))
        assertEquals("COLMI R02_C301", Compare.names(other, f.ring).subject("COLMI R02_C301"))
        // What Gadgetbridge and Health Connect pass on is told by its name, as the server does.
        assertEquals("strap", Compare.noun(DeviceKind.GADGETBRIDGE, "Polar H10"))
        assertEquals("strap", Compare.noun(DeviceKind.HEALTH_CONNECT, "Wahoo TICKR"))
        assertEquals("watch", Compare.noun(DeviceKind.GADGETBRIDGE, "Bangle.js 2"))
        assertEquals("ring", Compare.noun(DeviceKind.HEALTH_CONNECT, "Oura"))
        assertEquals("strap", Compare.noun(DeviceKind.HEART_RATE_STRAP, "Anything"))
        assertNull(Compare.noun(DeviceKind.HEALTH_CONNECT, "Health Connect"))
        // Health Connect's own count of the phone's steps.
        assertEquals("phone", Compare.noun(DeviceKind.HEALTH_CONNECT, "Phone"))
        assertNull(Compare.noun(null, "Something new"))
    }

    @Test
    fun `a summary reads in plain words`() {
        val heart = metric(CompareKey.NIGHT_HEART_RATE, 9, 55.8, 58.9, 3.1, 4.2, -1.0, 7.0)
        assertEquals("The ring read 3 bpm higher than the strap · 4 apart on average · from −1 to +7 · 9 nights", Compare.sentence(heart, ringAndStrap, locale = us))
        // HRV also as a share of the strap's average.
        val hrv = metric(CompareKey.HRV, 4, 52.3, 46.1, -6.2, 7.0, -12.4, 1.8)
        assertEquals("The ring read 6 ms lower than the strap (12% lower) · 7 apart on average · from −12 to +2 · 4 nights", Compare.sentence(hrv, ringAndStrap, locale = us))
        // Next to nothing apart.
        val same = metric(CompareKey.LOWEST_HEART_RATE, 5, 50.0, 50.2, 0.2, 1.4, -1.0, 2.0)
        assertEquals("The ring and the strap read the same on average · 1 apart on average · from −1 to +2 · 5 nights", Compare.sentence(same, ringAndStrap, locale = us))
        // Nothing apart at all: no "0 apart", no "from 0 to 0".
        val level = metric(CompareKey.ASLEEP, 3, 428.0, 428.0, 0.0, 0.2, -0.3, 0.3)
        assertEquals("The ring and the watch counted the same on average · 3 nights", Compare.sentence(level, ringAndWatch, locale = us))
        // Too few nights: it says it's early, not how far apart they usually are.
        val once = metric(CompareKey.NIGHT_HEART_RATE, 1, 56.0, 58.0, 2.0, 2.0, 2.0, 2.0)
        assertEquals("The ring read 2 bpm higher than the strap · only 1 night so far: a few more will show what's usual", Compare.sentence(once, ringAndStrap, locale = us))
        val twice = metric(CompareKey.NIGHT_HEART_RATE, 2, 56.0, 58.0, 2.0, 3.0, 1.0, 3.0)
        assertEquals("The ring read 2 bpm higher than the strap · only 2 nights so far: a few more will show what's usual", Compare.sentence(twice, ringAndStrap, locale = us))
    }

    @Test
    fun `sleep, times, steps and temperature each say it their own way`() {
        assertEquals(
            "The ring counted 24 min more sleep than the watch · 26 min apart on average · from −10 min to +35 min · 3 nights",
            Compare.sentence(metric(CompareKey.ASLEEP, 3, 402.0, 426.0, 24.0, 26.0, -10.0, 35.0), ringAndWatch, locale = us),
        )
        assertEquals(
            "The ring counted 1 h 5 min less deep sleep than the watch",
            Compare.headline(metric(CompareKey.DEEP, 3, 100.0, 35.0, -65.0, 65.0, -70.0, -60.0), ringAndWatch, locale = us),
        )
        assertEquals("The ring counted 6 min more time awake than the watch", Compare.headline(metric(CompareKey.AWAKE, 3, 9.0, 15.0, 6.0, 6.0, 4.0, 8.0), ringAndWatch, locale = us))
        assertEquals("The ring said you fell asleep 12 min later than the watch did", Compare.headline(metric(CompareKey.BEDTIME, 3, 320.0, 332.0, 12.0, 12.0, 8.0, 15.0), ringAndWatch, locale = us))
        assertEquals("The ring said you woke 8 min earlier than the watch did", Compare.headline(metric(CompareKey.WAKE, 3, 760.0, 752.0, -8.0, 8.0, -9.0, -6.0), ringAndWatch, locale = us))
        assertEquals("The ring and the watch agreed on the time, on average", Compare.headline(metric(CompareKey.WAKE, 3, 760.0, 760.2, 0.2, 2.0, -2.0, 2.0), ringAndWatch, locale = us))
        // Steps: as a share, over only the hours both were counting.
        val steps = metric(CompareKey.STEPS, 3, 9114.0, 8510.0, -604.0, 604.0, -900.0, -300.0).copy(pairs = 31)
        assertEquals("In the 31 hours both were counting, the ring counted 7% fewer steps than the watch · 3 days", Compare.sentence(steps, ringAndWatch, locale = us))
        assertEquals("In the one hour both were counting, the ring counted 7% fewer steps than the watch", Compare.headline(steps.copy(pairs = 1), ringAndWatch, locale = us))
        assertEquals("The ring counted 7% fewer steps than the watch", Compare.headline(steps.copy(pairs = 0), ringAndWatch, locale = us))
        assertEquals("In the 31 hours both were counting, the ring and the watch counted about the same steps", Compare.headline(steps.copy(b = 9115.0, difference = 1.0), ringAndWatch, locale = us))
        // The ring against the phone, on the first day.
        val phone = Compare.names(RecentDevice("r", "COLMI R02_C301", DeviceKind.COLMI_RING), RecentDevice("p", "Phone", DeviceKind.HEALTH_CONNECT))
        assertEquals(
            "In the 5 hours both were counting, the phone counted 45% fewer steps than the ring · only 1 day so far: a few more will show what's usual",
            Compare.sentence(metric(CompareKey.STEPS, 1, 1054.0, 576.0, -478.0, 478.0, -478.0, -478.0).copy(pairs = 5), phone, locale = us),
        )
        val skin = metric(CompareKey.SKIN_TEMPERATURE, 3, 33.2, 33.6, 0.4, 0.4, 0.3, 0.5)
        assertEquals("The ring read 0.4 °C warmer than the watch", Compare.headline(skin, ringAndWatch, locale = us))
        // A °F difference is 9/5 as big.
        assertEquals("The ring read 0.7 °F warmer than the watch", Compare.headline(skin, ringAndWatch, Units.IMPERIAL, us))
        assertEquals("The ring read 0.6 fewer breaths a minute than the watch", Compare.headline(metric(CompareKey.RESPIRATORY_RATE, 3, 14.8, 14.2, -0.6, 0.6, -0.8, -0.4), ringAndWatch, locale = us))
        assertEquals("The ring read 1.1% higher than the watch", Compare.headline(metric(CompareKey.SPO2, 3, 95.8, 96.9, 1.1, 1.1, 0.8, 1.4), ringAndWatch, locale = us))
    }

    @Test
    fun `values are shown by their unit`() {
        assertEquals("7 h 12 min", Compare.value(CompareKey.ASLEEP, 432.0, locale = us))
        assertEquals("45 min", Compare.value(CompareKey.DEEP, 45.2, locale = us))
        assertEquals("1 h", Compare.value(CompareKey.REM, 60.0, locale = us))
        assertEquals("0 min", Compare.value(CompareKey.AWAKE, 0.0, locale = us))
        // When: minutes after 6 pm the evening before, as the clock showed it that night.
        assertEquals("11:30 PM", Compare.value(CompareKey.BEDTIME, 330.0, date = LocalDate.of(2026, 11, 12), zone = f.zone, locale = us).plain())
        assertEquals("7:05 AM", Compare.value(CompareKey.WAKE, 785.0, date = LocalDate.of(2026, 11, 12), zone = f.zone, locale = us).plain())
        // The night the clocks went back (1 November 2026 in New York): twelve hours after 6 pm is 5 am.
        assertEquals("5:00 AM", Compare.value(CompareKey.WAKE, 720.0, date = LocalDate.of(2026, 11, 1), zone = f.zone, locale = us).plain())
        // An average over nights has no date: the clock's time alone.
        assertEquals("11:30 PM", Compare.value(CompareKey.BEDTIME, 330.0, locale = us).plain())
        assertEquals("12:15 AM", Compare.value(CompareKey.BEDTIME, 375.0, locale = us).plain())
        assertEquals("8,214", Compare.value(CompareKey.STEPS, 8214.0, locale = us))
        assertEquals("56 bpm", Compare.value(CompareKey.NIGHT_HEART_RATE, 56.4, locale = us))
        assertEquals("48 ms", Compare.value(CompareKey.HRV, 47.6, locale = us))
        assertEquals("96.4%", Compare.value(CompareKey.SPO2, 96.4, locale = us))
        assertEquals("97%", Compare.value(CompareKey.SPO2, 97.0, locale = us))
        assertEquals("14.2 per min", Compare.value(CompareKey.RESPIRATORY_RATE, 14.2, locale = us))
        assertEquals("33.6 °C", Compare.value(CompareKey.SKIN_TEMPERATURE, 33.64, locale = us))
        assertEquals("92.5 °F", Compare.value(CompareKey.SKIN_TEMPERATURE, 33.6, Units.IMPERIAL, locale = us))
        // Differences with their sign.
        assertEquals("+7", Compare.signed(CompareKey.NIGHT_HEART_RATE, 6.8, locale = us))
        assertEquals("−1", Compare.signed(CompareKey.NIGHT_HEART_RATE, -0.6, locale = us))
        assertEquals("0", Compare.signed(CompareKey.NIGHT_HEART_RATE, 0.3, locale = us))
        assertEquals("+1 h 5 min", Compare.signed(CompareKey.ASLEEP, 65.0, locale = us))
        assertTrue(Compare.none(CompareKey.SPO2, 0.04))
        assertFalse(Compare.none(CompareKey.SPO2, 0.06))
    }

    @Test
    fun `sections are what both measured, then the nights`() {
        assertEquals(listOf("heart", "lowest-heart", "hrv", "day-heart", "nights"), Compare.sections(f.ringVsStrap).map { it.id })
        assertEquals(
            listOf("asleep", "deep", "light", "rem", "awake", "bedtime", "wake", "heart", "lowest-heart", "hrv", "spo2", "steps", "nights"),
            Compare.sections(f.ringVsWatch).map { it.id },
        )
        assertEquals(CompareKey.HRV, Compare.metric(f.ringVsStrap, "hrv")?.key)
        assertNull(Compare.metric(f.ringVsStrap, Compare.NIGHTS))
        assertEquals(emptyList<String>(), Compare.sections(f.neverTogether).map { it.id })
    }

    @Test
    fun `the fixtures sum up as the server would`() {
        val heart = Compare.metric(f.ringVsStrap, "heart")!!
        assertEquals(4, heart.count)
        assertEquals(55.8, heart.a, 0.001)
        assertEquals(58.9, heart.b, 0.001)
        assertEquals(3.1, heart.difference, 0.001)
        assertEquals(3.4, heart.apart, 0.001)
        assertEquals(-0.6, heart.lowest, 0.001)
        assertEquals(6.8, heart.highest, 0.001)
        assertEquals("The ring read 3 bpm higher than the strap · 3 apart on average · from −1 to +7 · 4 nights", Compare.sentence(heart, ringAndStrap, locale = us))
        assertEquals(2, Compare.metric(f.ringVsStrap, "day-heart")!!.count)
        assertEquals("2 days", Compare.count(CompareKey.DAY_HEART_RATE, 2))
    }

    @Test
    fun `a strap's nights - none of its own, said once, and its empty column left out`() {
        assertTrue(Compare.noNights(f.ringVsStrap, ofA = true))
        assertFalse(Compare.noNights(f.ringVsStrap, ofA = false))
        assertFalse(Compare.noNights(f.ringVsWatch, ofA = true))
        assertEquals("The strap doesn't record sleep here, so its readings are set against the ring's nights.", Compare.noNightsLine(ringAndStrap, ofA = true))
        val both = Compare.measuredByBoth(f.ringVsStrap)
        assertEquals(setOf(CompareKey.NIGHT_HEART_RATE, CompareKey.LOWEST_HEART_RATE, CompareKey.HRV, CompareKey.DAY_HEART_RATE), both)
        val last = f.ringVsStrap.nights.first()
        assertEquals(
            listOf(
                Compare.Row(CompareKey.NIGHT_HEART_RATE, "Heart rate overnight", "55 bpm", "58 bpm"),
                Compare.Row(CompareKey.LOWEST_HEART_RATE, "Lowest heart rate overnight", "48 bpm", "50 bpm"),
                Compare.Row(CompareKey.HRV, "HRV overnight", "54 ms", "47 ms"),
                Compare.Row(CompareKey.DAY_HEART_RATE, "Heart rate in the day", "73 bpm", "76 bpm"),
            ),
            Compare.rows(last, both, zone = f.zone, locale = us),
        )
    }

    @Test
    fun `a night with both - its span covers both, and its table has when each fell asleep`() {
        val night = f.ringVsWatch.nights.first()
        val (from, to) = Compare.span(night)!!
        assertEquals(minOf(night.a!!.start, night.b!!.start), from)
        assertEquals(maxOf(night.a!!.end, night.b!!.end), to)
        val rows = Compare.rows(night, Compare.measuredByBoth(f.ringVsWatch), zone = f.zone, locale = us)
        assertEquals(CompareKey.ASLEEP, rows.first().key)
        val fell = rows.first { it.key == CompareKey.BEDTIME }
        assertEquals("11:23 PM", fell.a.plain())
        assertEquals("11:08 PM", fell.b.plain())
        assertEquals("Last night", Compare.nightTitle(f.today, f.today, us))
        assertEquals("Sunday night, 8 Nov", Compare.nightTitle(LocalDate.of(2026, 11, 9), f.today, us))
        assertEquals("11:08 PM – 6:30 AM", Compare.sessionLine(f.ringVsStrap.nights.first().b!!, f.zone).plain())
    }

    @Test
    fun `nothing to compare says why, kindly`() {
        assertNull(Compare.empty(f.ringVsStrap))
        assertEquals(Compare.Empty.ONE_DEVICE, Compare.empty(f.oneDevice))
        assertEquals(Compare.Empty.NOTHING_TOGETHER, Compare.empty(f.neverTogether))
        assertEquals(Compare.Empty.NO_DEVICES, Compare.empty(DeviceComparison(14, "2026-10-30", "2026-11-12")))
        assertEquals("Wear both on the same night to compare them, then sync.", Compare.emptyText(Compare.Empty.NOTHING_TOGETHER, 14).second)
        assertTrue(Compare.emptyText(Compare.Empty.ONE_DEVICE, 30).second.contains("last 30 nights"))
    }

    @Test
    fun `Devices offers it with two devices that record readings`() {
        fun device(id: String, kind: DeviceKind) = Device(id, kind, id, id, createdAt = 0)
        assertTrue(Compare.offered(listOf(device("r", DeviceKind.COLMI_RING), device("s", DeviceKind.HEART_RATE_STRAP))))
        assertFalse(Compare.offered(listOf(device("r", DeviceKind.COLMI_RING), device("m", DeviceKind.MANUAL))))
        assertFalse(Compare.offered(listOf(device("r", DeviceKind.COLMI_RING), device("b", DeviceKind.BP_MONITOR))))
        assertFalse(Compare.offered(emptyList()))
        // The two chosen are always choosable, even with nothing in these nights.
        val quiet = f.ringVsStrap.copy(devices = listOf(f.ring))
        assertEquals(listOf(f.ring, f.strap), Compare.choices(quiet))
    }

    @Test
    fun `the page knows when what it shows isn't what was just chosen`() {
        val shown = CompareState(a = f.STRAP, b = f.RING, days = 14, comparison = f.ringVsStrap, loading = false)
        assertFalse(shown.changing)
        assertTrue(shown.copy(days = 30).changing)
        assertTrue(shown.copy(a = f.RING, b = f.STRAP).changing)
        // Before anything is chosen the server picks: nothing to wait for.
        assertFalse(shown.copy(a = null, b = null).changing)
        assertFalse(shown.copy(days = 30, error = "Can't reach the server").changing)
        assertFalse(CompareState().changing)
    }
}
