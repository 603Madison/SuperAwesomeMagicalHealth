package com.health.app.ui.recent

import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.Point
import com.health.api.RecentPoint
import com.health.api.RecentSeries
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.api.Units
import com.health.app.ui.components.ChartScale
import com.health.app.ui.components.TimeAxis
import com.health.app.ui.components.TimeLabels
import com.health.app.ui.components.timeRuns
import com.health.app.ui.recent.RecentFixtures.at
import com.health.app.ui.recent.RecentFixtures.now
import com.health.app.ui.recent.RecentFixtures.today
import com.health.app.ui.recent.RecentFixtures.zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class RecentLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    /** Times read the same whichever space the JDK puts before AM and PM. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    private val yesterday = today.minusDays(1)
    private val f = RecentFixtures

    // ---- The time axis ----

    @Test
    fun `ticks every six hours on the clock, labelled the app's way`() {
        val axis = TimeAxis.of(f.from, f.now, zone, locale = Locale.US)
        assertEquals(listOf("6 PM", "Midnight", "6 AM", "Noon"), axis.ticks.map { it.label })
        assertEquals(listOf(at(yesterday, 18), at(today, 0), at(today, 6), at(today, 12)), axis.ticks.map { it.at })
        // 2:20 PM to 2:20 PM: 6 PM is 3 h 40 m in, a little past the sixth of the way.
        assertEquals(220f / 1440f, axis.fraction(at(yesterday, 18)), 1e-4f)
        assertEquals(0f, axis.fraction(f.from), 0f)
        assertEquals(1f, axis.fraction(f.now), 0f)
    }

    @Test
    fun `a window starting on the hour marks its start`() {
        val from = at(yesterday, 18)
        val axis = TimeAxis.of(from, from + 24 * 3_600_000L, zone, locale = Locale.US)
        assertEquals(listOf("6 PM", "Midnight", "6 AM", "Noon", "6 PM"), axis.ticks.map { it.label })
    }

    @Test
    fun `the clock going back or forward never doubles or loses a tick`() {
        // The night the clocks went back in New York (1 Nov 2026): 25 hours from 6 PM to 6 PM.
        val back = LocalTicks(java.time.LocalDate.of(2026, 10, 31))
        assertEquals(listOf("6 PM", "Midnight", "6 AM", "Noon", "6 PM"), back.labels)
        assertEquals(listOf(6L, 7L, 6L, 6L), back.gapsInHours)
        // And when they went forward (8 Mar 2026): 23 hours.
        val forward = LocalTicks(java.time.LocalDate.of(2026, 3, 7))
        assertEquals(listOf("6 PM", "Midnight", "6 AM", "Noon", "6 PM"), forward.labels)
        assertEquals(listOf(6L, 5L, 6L, 6L), forward.gapsInHours)
    }

    private class LocalTicks(day: java.time.LocalDate) {
        val zone: ZoneId = ZoneId.of("America/New_York")
        val axis = TimeAxis.of(day.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(), day.plusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(), zone, locale = Locale.US)
        val labels = axis.ticks.map { it.label }
        val gapsInHours = axis.ticks.zipWithNext { a, b -> (b.at - a.at) / 3_600_000L }
    }

    @Test
    fun `hours read as the person's clock does`() {
        assertEquals("Midnight", TimeLabels.hour(at(today, 0), zone, Locale.US))
        assertEquals("Noon", TimeLabels.hour(at(today, 12), zone, Locale.US))
        assertEquals("6 AM", TimeLabels.hour(at(today, 6), zone, Locale.US))
        assertEquals("6 PM", TimeLabels.hour(at(today, 18), zone, Locale.US))
        assertEquals("6:30 PM", TimeLabels.hour(at(today, 18, 30), zone, Locale.US))
        // A 24-hour clock.
        assertEquals("18:00", TimeLabels.hour(at(today, 18), zone, Locale.UK))
        assertEquals("Midnight", TimeLabels.hour(at(today, 0), zone, Locale.UK))
        assertTrue(TimeLabels.twelveHour(Locale.US))
        assertFalse(TimeLabels.twelveHour(Locale.UK))
        // In the person's time zone: noon in New York is 5 PM in London.
        assertEquals("5 PM", TimeLabels.hour(at(today, 12), ZoneId.of("Europe/London"), Locale.US))
    }

    // ---- The scale ----

    @Test
    fun `scales land on round numbers`() {
        assertEquals(ChartScale(50.0, 120.0, 10.0), ChartScale.nice(52.0, 118.0))
        assertEquals(ChartScale(90.0, 100.0, 2.0), ChartScale.nice(94.0, 98.0, bottom = 90.0, top = 100.0))
        assertEquals(ChartScale(0.0, 100.0, 20.0), ChartScale.nice(21.0, 61.0, bottom = 0.0, top = 100.0))
        assertEquals(ChartScale(0.0, 2500.0, 500.0), ChartScale.nice(0.0, 2140.0, bottom = 0.0))
        assertEquals(ChartScale(33.0, 35.5, 0.5), ChartScale.nice(33.1, 35.4))
        assertEquals("33.0", ChartScale(33.0, 35.5, 0.5).label(33.0))
        assertEquals("120", ChartScale(50.0, 120.0, 10.0).label(120.0))
        // One reading has room around it; readings past a fixed end still fit.
        val one = ChartScale.nice(64.0, 64.0)
        assertTrue(one.low < 64 && one.high > 64)
        assertEquals(86.0, ChartScale.nice(86.0, 99.0, bottom = 90.0, top = 100.0).low, 0.0)
        assertEquals(0.5, ChartScale(0.0, 100.0, 20.0).fraction(50.0).toDouble(), 1e-6)
    }

    @Test
    fun `a line breaks where readings stop`() {
        val m = 60_000L
        val runs = timeRuns(listOf(Point(0, 1.0), Point(7 * m, 2.0), Point(14 * m, 3.0), Point(80 * m, 4.0), Point(86 * m, 5.0)), 45 * m)
        assertEquals(listOf(3, 2), runs.map { it.size })
        // Her heart rate: one break, the hour the ring was charging.
        assertEquals(2, timeRuns(f.heart.map { Point(it.at, it.value) }, 45 * m).size)
    }

    // ---- Sleep ----

    @Test
    fun `the night reads as when, how long and each stage`() {
        assertEquals(17, f.night.stages.size)
        assertEquals("11:08 PM – 6:30 AM", Recent.sessionLine(f.night, zone, withSource = false).plain())
        assertEquals("11:08 PM – 6:30 AM · COLMI R02_C301", Recent.sessionLine(f.night, zone, withSource = true).plain())
        val totals = Recent.totals(listOf(f.night))
        assertEquals(Recent.SleepTotals(asleep = 427, deep = 85, rem = 106, light = 236, awake = 15, unknown = 0), totals)
        assertTrue(totals.staged)
        assertEquals("Sleep stages: deep 1h 25m, REM 1h 46m, light 3h 56m, awake 15m", Recent.sleepDescription(totals))
    }

    @Test
    fun `names the device only when the nights could come from more than one`() {
        assertTrue(Recent.namesDevices(f.all))
        assertFalse(Recent.namesDevices(f.ringAlone))
        assertFalse(Recent.namesDevices(f.all.copy(devices = listOf(f.ring))))
    }

    @Test
    fun `a night without stages is asleep throughout`() {
        val nap = SleepSession(at(today, 13), at(today, 13, 40))
        assertEquals(listOf(StageSpan(SleepStage.ASLEEP, nap.start, nap.end)), Recent.stages(nap))
        val totals = Recent.totals(listOf(nap))
        assertEquals(40, totals.asleep)
        assertFalse(totals.staged)
        assertEquals("Asleep 40m", Recent.sleepDescription(totals))
    }

    @Test
    fun `the same night from two devices is drawn and added once`() {
        val phoneNight = SleepSession(at(yesterday, 23, 0), at(today, 6, 40), deviceId = f.PHONE, source = "Health Connect")
        val nap = SleepSession(at(today, 13), at(today, 13, 30), deviceId = f.PHONE)
        assertEquals(listOf(f.night, nap), Recent.nights(listOf(phoneNight, f.night, nap)))
        assertEquals(427 + 30, Recent.totals(Recent.nights(listOf(phoneNight, f.night, nap))).asleep)
    }

    @Test
    fun `stages and shading are cut at the window's edges`() {
        val early = SleepSession(f.from - 3_600_000L, f.from + 3_600_000L, listOf(StageSpan(SleepStage.LIGHT, f.from - 3_600_000L, f.from + 3_600_000L)))
        assertEquals(listOf(StageSpan(SleepStage.LIGHT, f.from, f.from + 3_600_000L)), Recent.stagesIn(listOf(early), f.from, f.now))
        assertEquals(listOf(f.night.start to f.night.end), Recent.shade(f.all))
        assertEquals(listOf(f.from to f.from + 3_600_000L), Recent.shade(f.all.copy(sleep = listOf(early))))
    }

    // ---- Readings ----

    @Test
    fun `a kind of reading this app doesn't know is left out`() {
        val series = Recent.series(f.all)
        assertEquals(setOf(Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS, Metric.STEPS), series.keys)
        assertEquals(listOf("sleep", "heart", "spo2", "hrv", "stress", "steps"), Recent.sections(f.all).map { it.id })
        // Only the unknown kind: nothing to show.
        val onlyNew = f.empty.copy(series = listOf(RecentSeries(null, listOf(RecentPoint(now, 3.0)))))
        assertFalse(Recent.hasData(onlyNew))
        assertEquals(emptyList<String>(), Recent.sections(onlyNew).map { it.id })
        // A kind with no points isn't a section.
        assertFalse(Recent.hasData(f.empty.copy(series = listOf(RecentSeries(Metric.HRV)))))
    }

    @Test
    fun `sleep shows whenever anything was recorded, the rest with readings`() {
        val dayOnly = f.all.copy(sleep = emptyList())
        assertEquals("sleep", Recent.sections(dayOnly).first().id)
        assertTrue(Recent.hasData(f.empty.copy(sleep = listOf(f.night))))
        assertEquals(listOf("sleep"), Recent.sections(f.empty.copy(sleep = listOf(f.night))).map { it.id })
        assertEquals(listOf("sleep", "heart", "spo2", "hrv", "stress", "breathing", "temperature", "steps"), Recent.sections(f.withTemperature).map { it.id })
        assertEquals(Recent.SECTIONS.map { it.id }, listOf("sleep", "heart", "spo2", "hrv", "stress", "breathing", "temperature", "steps"))
        assertFalse(Recent.hasData(f.empty))
    }

    @Test
    fun `figures under each chart, with units`() {
        val hr = RecentSeries(Metric.HEART_RATE, listOf(RecentPoint(1, 52.0), RecentPoint(2, 118.0)), min = 52.0, max = 118.0, average = 71.4, latest = RecentPoint(at(today, 14, 5), 64.0))
        assertEquals(listOf(Recent.Stat("Lowest", "52 bpm"), Recent.Stat("Highest", "118 bpm"), Recent.Stat("Average", "71 bpm")), Recent.stats(hr, Units.METRIC))
        assertEquals("64 bpm" to "latest at 2:05 PM", Recent.latest(hr, Units.METRIC, zone)?.let { it.first to it.second.plain() })
        assertEquals("Heart rate from 52 to 118 bpm, average 71", Recent.description("Heart rate", hr, Units.METRIC))

        val spo2 = f.series(Metric.SPO2, f.spo2)
        assertEquals(listOf("Lowest" to "94%", "Average" to "96%"), Recent.stats(spo2, Units.METRIC).map { it.label to it.value })
        assertEquals("97%", Recent.latest(spo2, Units.METRIC, zone)?.first)

        val hrv = f.series(Metric.HRV, f.hrv)
        assertEquals(listOf("Average" to "43 ms"), Recent.stats(hrv, Units.METRIC).map { it.label to it.value })
        assertEquals("31 ms", Recent.latest(hrv, Units.METRIC, zone)?.first)

        val stress = f.series(Metric.STRESS, f.stress)
        assertEquals(listOf("Average" to "42", "Highest" to "61"), Recent.stats(stress, Units.METRIC).map { it.label to it.value })

        val breathing = f.series(Metric.RESPIRATORY_RATE, f.breathing)
        assertEquals("15.5 per min", Recent.latest(breathing, Units.METRIC, zone)?.first)

        val steps = f.series(Metric.STEPS, f.steps, counts = true)
        assertEquals(emptyList<Recent.Stat>(), Recent.stats(steps, Units.METRIC))
        assertEquals("Steps by the hour, 9,260 in all", Recent.description("Steps", steps, Units.METRIC))
    }

    @Test
    fun `temperatures in fahrenheit for imperial units`() {
        assertEquals(98.6, Recent.temperature(37.0, Units.IMPERIAL), 1e-9)
        assertEquals(37.0, Recent.temperature(37.0, Units.METRIC), 1e-9)
        // A change has no offset: 0.5 °C warmer is 0.9 °F warmer.
        assertEquals(0.9, Recent.temperatureChange(0.5, Units.IMPERIAL), 1e-9)
        assertEquals("33.4 °C", Recent.value(Metric.SKIN_TEMPERATURE, 33.4, Units.METRIC))
        assertEquals("92.1 °F", Recent.value(Metric.SKIN_TEMPERATURE, 33.4, Units.IMPERIAL))
        assertEquals("+0.3 °C", Recent.value(Metric.TEMPERATURE_DEVIATION, 0.3, Units.METRIC))
        assertEquals("+0.5 °F", Recent.value(Metric.TEMPERATURE_DEVIATION, 0.3, Units.IMPERIAL))
        assertEquals("−0.2 °C", Recent.value(Metric.TEMPERATURE_DEVIATION, -0.2, Units.METRIC))
        val skin = RecentSeries(Metric.SKIN_TEMPERATURE, listOf(RecentPoint(1, 33.0), RecentPoint(2, 35.0)), min = 33.0, max = 35.0, average = 34.0)
        assertEquals(listOf(91.4, 95.0), Recent.points(skin, Units.IMPERIAL).map { (it.v * 10).let(Math::round) / 10.0 })
        assertEquals("Skin temperature from 91.4 to 95.0 °F, average 93.2", Recent.description("Skin temperature", skin, Units.IMPERIAL))
        assertEquals(listOf("Lowest" to "91.4 °F", "Highest" to "95.0 °F", "Average" to "93.2 °F"), Recent.stats(skin, Units.IMPERIAL).map { it.label to it.value })
        // Heart rate is the same either way.
        assertEquals("64 bpm", Recent.value(Metric.HEART_RATE, 64.0, Units.IMPERIAL))
    }

    @Test
    fun `scales suit each reading`() {
        val spo2 = Recent.scale(Metric.SPO2, f.spo2.map { Point(it.at, it.value) })
        assertEquals(90.0 to 100.0, spo2.low to spo2.high)
        val stress = Recent.scale(Metric.STRESS, f.stress.map { Point(it.at, it.value) })
        assertEquals(0.0 to 100.0, stress.low to stress.high)
        val steps = Recent.scale(Metric.STEPS, f.steps.map { Point(it.at, it.value) })
        assertEquals(0.0, steps.low, 0.0)
        val change = Recent.scale(Metric.TEMPERATURE_DEVIATION, listOf(Point(1, 0.3)))
        assertTrue(change.low < 0 && change.high > 0.3)
        assertEquals("−0.6", ChartScale(-0.6, 0.6, 0.2).label(-0.6))
        assertEquals("0", ChartScale(-0.0, 10.0, 2.0).label(-0.0))
        // Breathing 13–15.5 a minute spans at least 6, so a breath either way doesn't fill the chart.
        val breathing = Recent.scale(Metric.RESPIRATORY_RATE, f.breathing.map { Point(it.at, it.value) })
        assertTrue(breathing.high - breathing.low >= 6)
        assertTrue(breathing.low <= 13 && breathing.high >= 15.5)
        // Heart rate that moves more than the least span keeps its own range.
        assertEquals(52.0 to 118.0, Recent.widened(52.0, 118.0, 30.0))
        assertEquals(50.0 to 80.0, Recent.widened(60.0, 70.0, 30.0))
        // Skin temperature spans at least 2 °C, or 3.6 °F.
        assertEquals(3.6, Recent.span(Metric.SKIN_TEMPERATURE, Units.IMPERIAL), 1e-9)
    }

    @Test
    fun `a single reading has its headline and no figures under it`() {
        val one = f.series(Metric.TEMPERATURE_DEVIATION, f.deviation)
        assertEquals(emptyList<Recent.Stat>(), Recent.stats(one, Units.IMPERIAL))
        assertEquals("+0.5 °F", Recent.latest(one, Units.IMPERIAL, zone)?.first)
    }

    // ---- Devices ----

    @Test
    fun `device chips only when there's more than one`() {
        assertEquals(
            listOf(Recent.Chip(null, "All devices"), Recent.Chip(f.RING, "COLMI R02_C301"), Recent.Chip(f.PHONE, "Health Connect")),
            Recent.chips(f.all, chosen = null),
        )
        // The ring chosen: the same chips, the ring's picked on the page.
        assertEquals(3, Recent.chips(f.ringAlone, chosen = f.RING).size)
        assertEquals(emptyList<Recent.Chip>(), Recent.chips(f.all.copy(devices = listOf(f.ring)), chosen = null))
        assertEquals(emptyList<Recent.Chip>(), Recent.chips(f.all.copy(devices = listOf(f.ring)), chosen = f.RING))
        // A device chosen with nothing in the window still shows, by its name, so all of them is a tap away.
        val pebble = Device("dev-pebble", DeviceKind.PEBBLE, "pebble", "Pebble Time 2", createdAt = 0)
        assertEquals(
            listOf(Recent.Chip(null, "All devices"), Recent.Chip(f.RING, "COLMI R02_C301"), Recent.Chip("dev-pebble", "Pebble Time 2")),
            Recent.chips(f.all.copy(devices = listOf(f.ring), device = "dev-pebble"), chosen = "dev-pebble", known = listOf(pebble)),
        )
    }

    @Test
    fun `devices that record the day open it from Devices`() {
        assertTrue(Recent.records(DeviceKind.COLMI_RING))
        assertTrue(Recent.records(DeviceKind.HEALTH_CONNECT))
        assertTrue(Recent.records(DeviceKind.PEBBLE))
        assertTrue(Recent.records(DeviceKind.HEART_RATE_STRAP))
        assertFalse(Recent.records(DeviceKind.BP_MONITOR))
        assertFalse(Recent.records(DeviceKind.GLUCOSE_METER))
        assertFalse(Recent.records(DeviceKind.MANUAL))
    }

    @Test
    fun `last synced is the chosen device's, or the latest of any`() {
        val known = listOf(
            Device(f.RING, DeviceKind.COLMI_RING, "aa", "COLMI R02_C301", lastSyncAt = now - 3 * 3_600_000L, createdAt = 0),
            Device(f.PHONE, DeviceKind.HEALTH_CONNECT, "hc", "Health Connect", lastSyncAt = now - 5 * 60_000L, createdAt = 0),
        )
        assertEquals(now - 5 * 60_000L, Recent.lastSync(f.empty, known))
        assertEquals(now - 3 * 3_600_000L, Recent.lastSync(f.empty.copy(device = f.RING), known))
        assertNull(Recent.lastSync(f.empty, emptyList()))
        assertEquals("Last synced 3 h ago", Recent.lastSyncLine(now - 3 * 3_600_000L, now))
        assertEquals("Not synced yet", Recent.lastSyncLine(null, now))
    }
}
