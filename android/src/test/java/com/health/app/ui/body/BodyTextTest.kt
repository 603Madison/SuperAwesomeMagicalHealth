package com.health.app.ui.body

import com.health.api.CompareKey
import com.health.api.ComparedMetric
import com.health.api.DeviceComparison
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.RecentDevice
import com.health.api.RecentPoint
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.api.SleepSession
import com.health.api.TrendDay
import com.health.api.Units
import com.health.app.ui.recent.Recent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** What Body's cards say: against the person's usual, where the readings came from, the tiles two to a row, and Compare devices' line. */
class BodyTextTest {
    init {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `the usual is the middle of the days before today, with three or more`() {
        val days = listOf(48.0, 50.0, 49.0, 60.0).mapIndexed { i, v -> TrendDay("2026-11-1$i", hrv = v) } + TrendDay("2026-11-17", hrv = 30.0)
        assertEquals(49.5, BodyText.usual(days, "2026-11-17") { it.hrv }!!, 0.01)
        assertNull(BodyText.usual(days.take(2), "2026-11-17") { it.hrv })
        assertEquals("22% below your usual 49 ms", BodyText.againstUsual(38.0, 49.0, " ms"))
        assertEquals("about your usual 49", BodyText.againstUsual(48.0, 49.0))
        assertEquals("10% above your usual 50", BodyText.againstUsual(55.0, 50.0))
        assertTrue(BodyText.wellBelow(38.0, 49.0))
        assertFalse(BodyText.wellBelow(46.0, 49.0))
        assertEquals("warmer than usual", BodyText.warmth(0.4))
        assertEquals("about your usual", BodyText.warmth(0.1))
        assertEquals("cooler than usual", BodyText.warmth(-0.5))
    }

    @Test
    fun `the heart card says whose readings they are`() {
        val ring = RecentDevice("r", "Colmi R02", DeviceKind.COLMI_RING)
        val strap = RecentDevice("s", "Polar H10", DeviceKind.HEART_RATE_STRAP)
        assertEquals("Colmi R02 and Polar H10 together", BodyText.sources(RecentReadings(0, 1, devices = listOf(ring, strap))))
        assertEquals("Polar H10", BodyText.sources(RecentReadings(0, 1, device = "s", devices = listOf(ring, strap))))
        assertNull(BodyText.sources(RecentReadings(0, 1, devices = listOf(ring))))
    }

    @Test
    fun `small tiles that follow one another share a row, in the person's order`() {
        assertEquals(
            listOf(listOf(Recent.SLEEP), listOf(Recent.HEART), listOf(Recent.SPO2, Recent.HRV), listOf(Recent.STRESS, Recent.BREATHING), listOf(Recent.TEMPERATURE), listOf(Recent.STEPS)),
            BodyText.rows(Recent.SECTIONS.map { it.id }),
        )
        // A big card between two tiles leaves each on a row of its own.
        assertEquals(listOf(listOf(Recent.HRV), listOf(Recent.STEPS), listOf(Recent.SPO2)), BodyText.rows(listOf(Recent.HRV, Recent.STEPS, Recent.SPO2)))
    }

    @Test
    fun `the night at the top is the longest, and its stages are counted`() {
        val nap = SleepSession(start = 1_000_000, end = 1_000_000 + 30 * 60_000)
        val night = SleepSession(start = 5_000_000, end = 5_000_000 + 7 * 3_600_000)
        assertEquals(night, BodyText.mainNight(RecentReadings(0, 1, sleep = listOf(nap, night))))
        val tiles = BodyText.stageTiles(Recent.totals(listOf(night)))
        assertEquals(listOf("Asleep" to "7h"), tiles)
    }

    @Test
    fun `a reading's figures read plainly`() {
        val zone = ZoneId.of("UTC")
        val spo2 = RecentSeries(Metric.SPO2, listOf(RecentPoint(0, 97.0), RecentPoint(3_600_000, 92.0), RecentPoint(7_200_000, 96.0)), min = 92.0, max = 97.0, average = 95.0)
        assertEquals("95%", BodyText.average(spo2, Units.METRIC))
        assertEquals("lowest 92% at ${com.health.app.ui.Format.timeOfDay(3_600_000L, zone)}", BodyText.lowestAt(spo2, Units.METRIC, zone))
        assertEquals("lowest 92 · highest 97", BodyText.range(spo2, Units.METRIC))
        assertEquals("14.1 /min", BodyText.breaths(RecentSeries(Metric.RESPIRATORY_RATE, listOf(RecentPoint(0, 14.1)), average = 14.1)))
    }

    @Test
    fun `compare devices says how one read against the other, heart rate first`() {
        val ring = RecentDevice("r", "Colmi R02", DeviceKind.COLMI_RING)
        val strap = RecentDevice("s", "Polar H10", DeviceKind.HEART_RATE_STRAP)
        val c = DeviceComparison(
            14, "2026-11-03", "2026-11-17", a = strap, b = ring,
            metrics = listOf(
                ComparedMetric(CompareKey.ASLEEP, 9, 420.0, 432.0, 12.0, 14.0, -5.0, 30.0),
                ComparedMetric(CompareKey.NIGHT_HEART_RATE, 9, 55.0, 58.0, 3.0, 3.5, 1.0, 6.0),
            ),
        )
        assertEquals("The ring read 3 bpm higher than the strap", BodyText.compareLine(c, Units.METRIC))
        assertNull(BodyText.compareLine(c.copy(metrics = emptyList()), Units.METRIC))
        assertNull(BodyText.compareLine(null, Units.METRIC))
    }
}
