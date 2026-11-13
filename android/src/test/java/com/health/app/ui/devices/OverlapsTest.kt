package com.health.app.ui.devices

import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.DaySummary
import com.health.api.Device
import com.health.api.DeviceChoices
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.MetricChoice
import com.health.api.SleepSource
import com.health.api.SleepSummary
import com.health.api.SourceValue
import com.health.api.Units
import com.health.app.devices.colmi.Answer
import com.health.app.devices.colmi.ReadingStatus
import com.health.app.devices.colmi.RingReading
import com.health.app.ui.components.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OverlapsTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val ring = SourceValue("ring", "Colmi R02", DeviceKind.COLMI_RING, 46.0, 0.7, readings = 12)
    private val watch = SourceValue("watch", "Pebble Time 2", DeviceKind.PEBBLE, 52.0, 0.3, readings = 4, note = "only part of the night")

    private fun combined(metric: Metric, vararg sources: SourceValue, chosen: String? = null) =
        CombinedValue(metric, 48.0, sources.toList(), "The average of both, weighted to the ring at night.", chosen)

    private fun night(vararg sources: SleepSource) = SleepSummary(
        start = 0, end = 1, asleepMinutes = 400, inBedMinutes = 420, efficiency = 95, deepMinutes = 0, remMinutes = 0, lightMinutes = 0,
        awakeMinutes = 0, staged = true, combined = CombinedSleep(sources.toList(), "Bedtime from both; stages from the ring."),
    )

    @Test
    fun `readings that more than one device measured are listed, sleep last`() {
        val today = DaySummary(
            "2026-11-12",
            combined = listOf(combined(Metric.HRV, ring, watch), combined(Metric.STEPS, watch, ring), combined(Metric.SPO2, ring)),
            sleepSummary = night(SleepSource("ring", "Colmi R02", 0, 1, 400, true), SleepSource("watch", "Pebble Time 2", 0, 1, 390, false)),
        )
        val overlaps = Overlaps.of(listOf(today), DeviceChoices(listOf(MetricChoice(Metric.HRV, "ring"))), emptyList())
        assertEquals(listOf(Metric.HRV, Metric.STEPS, null), overlaps.map { it.metric })
        assertEquals("Only Colmi R02", overlaps[0].current)
        assertEquals("Combined", overlaps[1].current)
        assertEquals(listOf("Pebble Time 2", "Colmi R02"), overlaps[1].devices.map { it.name })
        assertEquals("Sleep", overlaps[2].label)
        assertEquals("sleep", overlaps[2].reading)
        assertEquals("heart rate variability", overlaps[0].reading)
    }

    @Test
    fun `a choice stays listed after the devices stop overlapping, so it can be undone`() {
        val known = listOf(Device("watch", DeviceKind.PEBBLE, "pebble", "Pebble Time 2", createdAt = 0))
        val overlaps = Overlaps.of(emptyList(), DeviceChoices(listOf(MetricChoice(Metric.STEPS, "watch")), sleepDeviceId = "gone"), known)
        assertEquals(2, overlaps.size)
        assertEquals("Only Pebble Time 2", overlaps[0].current)
        assertEquals("A device you chose", overlaps[1].devices.single().name)
        assertTrue(Overlaps.of(emptyList(), DeviceChoices(), known).isEmpty())
    }

    @Test
    fun `devices seen on either day are put together`() {
        val yesterday = DaySummary("2026-11-11", combined = listOf(combined(Metric.HRV, ring, watch)))
        val today = DaySummary("2026-11-12", combined = listOf(combined(Metric.HRV, ring, SourceValue("phone", "Phone", null, 50.0, 0.1))))
        val hrv = Overlaps.of(listOf(today, yesterday), DeviceChoices(), emptyList()).single()
        assertEquals(listOf("ring", "phone", "watch"), hrv.devices.map { it.id })
        assertNull(hrv.chosen)
    }

    @Test
    fun `the sources sheet says what each device counted for`() {
        assertEquals("Counts for 70% · 12 readings", Sources.caption(ring, null))
        assertEquals("Counts for 30% · 4 readings · only part of the night", Sources.caption(watch, null))
        assertEquals("The one you chose · 12 readings", Sources.caption(ring, "ring"))
        assertEquals("Left out · 4 readings · only part of the night", Sources.caption(watch, "ring"))
        assertEquals("Use only Colmi R02 for heart rate variability", Sources.useOnly("Colmi R02", Sources.reading(Metric.HRV)))
        assertEquals("Heart rate variability now comes from Colmi R02 only", Sources.chosen(Sources.reading(Metric.HRV), "Colmi R02"))
        assertEquals("Sleep combines your devices again", Sources.chosen("sleep", null))
        assertEquals(2, Sources.deviceCount(listOf(combined(Metric.HRV, ring, watch), combined(Metric.SPO2, watch, ring))))
        assertEquals("From 2 devices", Sources.line(2))
    }

    @Test
    fun `values read as the cards show them`() {
        assertEquals("48 ms", Sources.value(Metric.HRV, 47.6, Units.METRIC))
        assertEquals("97%", Sources.value(Metric.SPO2, 97.0, Units.METRIC))
        assertEquals("7,214 steps", Sources.value(Metric.STEPS, 7214.0, Units.METRIC))
        assertEquals("34.5 °C", Sources.value(Metric.SKIN_TEMPERATURE, 34.5, Units.METRIC))
        assertEquals("94.1 °F", Sources.value(Metric.SKIN_TEMPERATURE, 34.5, Units.IMPERIAL))
        assertEquals("52 bpm", Sources.value(Metric.RESTING_HEART_RATE, 51.6, Units.METRIC))
    }

    @Test
    fun `charts in Trends speak for their own readings`() {
        assertEquals(setOf(Metric.HRV), Sources.forChart("hrv"))
        assertNull(Sources.forChart("mood"))
        assertNull(Sources.chart("hrv", listOf(combined(Metric.STEPS, ring, watch)), null, Units.METRIC, java.time.ZoneOffset.UTC))
        assertTrue(Sources.chart("steps", listOf(combined(Metric.STEPS, ring, watch)), null, Units.METRIC, java.time.ZoneOffset.UTC) != null)
    }

    @Test
    fun `each ring reading says how it's doing`() {
        val now = 10_000_000L
        assertEquals("Latest 5 min ago" to RingReadingText.Tone.COMING_IN, RingReadingText.of(RingReading.HRV, ReadingStatus(latest = now - 300_000), now))
        assertEquals("Night ended 3 h ago", RingReadingText.of(RingReading.SLEEP, ReadingStatus(latest = now - 3 * 3_600_000), now).first)
        assertEquals("Not on this ring", RingReadingText.of(RingReading.SKIN_TEMPERATURE, ReadingStatus(offered = false), now).first)
        assertEquals("This ring doesn't measure it", RingReadingText.of(RingReading.STRESS, ReadingStatus(answer = Answer.REFUSED), now).first)
        assertEquals("No answer from the ring yet", RingReadingText.of(RingReading.STRESS, ReadingStatus(answer = Answer.SILENT), now).first)
        assertEquals("Waiting for its first reading", RingReadingText.of(RingReading.SPO2, ReadingStatus(answer = Answer.COMPLETE), now).first)
        assertEquals("At the next sync", RingReadingText.of(RingReading.HEART_RATE, null, now).first)
    }
}
