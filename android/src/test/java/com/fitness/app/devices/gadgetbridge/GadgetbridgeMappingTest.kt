package com.fitness.app.devices.gadgetbridge

import com.fitness.api.DeviceKind
import com.fitness.api.Metric
import com.fitness.api.SleepStage
import com.fitness.api.WorkoutType
import com.fitness.app.devices.gadgetbridge.GadgetbridgeMapping.ActivityRow
import com.fitness.app.devices.gadgetbridge.GadgetbridgeMapping.Overlay
import com.fitness.app.devices.gadgetbridge.GadgetbridgeMapping.StageRow
import com.fitness.app.devices.gadgetbridge.GadgetbridgeMapping.TableKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetbridgeMappingTest {
    private val minute = 60_000L
    private val t0 = 1_762_905_600_000L // a quarter hour on the dot

    @Test
    fun `tables are recognised by their names`() {
        assertEquals(TableKind.ACTIVITY, GadgetbridgeMapping.classify("PEBBLE_HEALTH_ACTIVITY_SAMPLE"))
        assertEquals(TableKind.HEART_RATE, GadgetbridgeMapping.classify("COLMI_HEART_RATE_SAMPLE"))
        assertNull(GadgetbridgeMapping.classify("HUAWEI_RESTING_HEART_RATE_SAMPLE"))
        assertEquals(TableKind.SLEEP_STAGE, GadgetbridgeMapping.classify("GARMIN_SLEEP_STAGE_SAMPLE"))
        assertEquals(TableKind.PEBBLE_OVERLAY, GadgetbridgeMapping.classify("PEBBLE_HEALTH_ACTIVITY_OVERLAY"))
        assertEquals(TableKind.HRV, GadgetbridgeMapping.classify("GARMIN_HRV_SUMMARY_SAMPLE"))
        assertNull(GadgetbridgeMapping.classify("DEVICE"))
        assertEquals("COLMI", GadgetbridgeMapping.prefix("COLMI_SPO2_SAMPLE"))
        assertEquals("PEBBLE_HEALTH", GadgetbridgeMapping.prefix("PEBBLE_HEALTH_ACTIVITY_SAMPLE"))
    }

    @Test
    fun `seconds and milliseconds both become milliseconds`() {
        assertEquals(1_762_905_600_000L, GadgetbridgeMapping.toMillis(1_762_905_600L))
        assertEquals(1_762_905_600_123L, GadgetbridgeMapping.toMillis(1_762_905_600_123L))
    }

    @Test
    fun `minutes of activity are summed into quarter hours`() {
        val rows = (0 until 20).map { ActivityRow(t0 + it * minute, steps = 10, heartRate = if (it < 10) 60 + it else 255, distanceM = 7.0) }
        val samples = GadgetbridgeMapping.activity(rows)
        val steps = samples.filter { it.metric == Metric.STEPS }
        assertEquals(listOf(150.0, 50.0), steps.map { it.value })
        assertEquals(t0, steps[0].start)
        assertEquals(t0 + 15 * minute, steps[0].end)
        val hr = samples.filter { it.metric == Metric.HEART_RATE }
        // 255 means "no reading"; the first ten minutes average per five.
        assertEquals(listOf(62.0, 67.0), hr.map { it.value })
        assertEquals(2, samples.count { it.metric == Metric.DISTANCE })
    }

    @Test
    fun `values that can't be right are dropped`() {
        val samples = GadgetbridgeMapping.values(Metric.SPO2, listOf(t0 to 97.0, t0 + minute to 0.0, t0 + 2 * minute to 120.0))
        assertEquals(listOf(97.0), samples.map { it.value })
    }

    @Test
    fun `calories in small calories are turned into kilocalories`() {
        assertEquals(2.5, GadgetbridgeMapping.kilocalories("CALORIES", 2500.0), 1e-9)
        assertEquals(3.0, GadgetbridgeMapping.kilocalories("ACTIVE_CALORIES", 3.0), 1e-9)
    }

    @Test
    fun `Colmi sleep stages with durations become a night`() {
        val rows = listOf(
            StageRow(t0, 2, 60),
            StageRow(t0 + 60 * minute, 3, 45),
            StageRow(t0 + 105 * minute, 4, 30),
            StageRow(t0 + 135 * minute, 5, 5),
        )
        val night = GadgetbridgeMapping.sleep("COLMI", rows).single()
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM, SleepStage.AWAKE), night.stages.map { it.stage })
        assertEquals(t0, night.start)
        assertEquals(t0 + 140 * minute, night.end)
    }

    @Test
    fun `Garmin stages last until the next, and long gaps split nights`() {
        val rows = listOf(
            StageRow(t0, 2), StageRow(t0 + 30 * minute, 3), StageRow(t0 + 60 * minute, 1),
            // A nap the next afternoon, far away.
            StageRow(t0 + 14 * 60 * minute, 2), StageRow(t0 + 14 * 60 * minute + 40 * minute, 1),
        )
        val nights = GadgetbridgeMapping.sleep("GARMIN", rows)
        assertEquals(2, nights.size)
        assertEquals(t0 + 30 * minute, nights[0].stages[0].end)
        assertEquals(SleepStage.DEEP, nights[0].stages[1].stage)
    }

    @Test
    fun `a few minutes asleep isn't a night`() {
        assertTrue(GadgetbridgeMapping.sleep("COLMI", listOf(StageRow(t0, 2, 10))).isEmpty())
    }

    @Test
    fun `Pebble overlays give nights with deep sleep, and runs`() {
        val overlays = listOf(
            Overlay(t0, t0 + 480 * minute, 1),
            Overlay(t0 + 60 * minute, t0 + 120 * minute, 2),
            Overlay(t0 + 240 * minute, t0 + 270 * minute, 2),
            Overlay(t0 + 600 * minute, t0 + 640 * minute, 6),
            Overlay(t0 + 700 * minute, t0 + 705 * minute, 5),
        )
        val (nights, workouts) = GadgetbridgeMapping.pebble(overlays)
        val night = nights.single()
        assertEquals(
            listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.LIGHT),
            night.stages.map { it.stage },
        )
        assertEquals(t0 + 480 * minute, night.stages.last().end)
        // A five-minute walk isn't a workout; the run is.
        assertEquals(listOf(WorkoutType.RUN), workouts.map { it.type })
    }

    @Test
    fun `devices are told apart by their tables and names`() {
        assertEquals(DeviceKind.PEBBLE, GadgetbridgeMapping.kind(listOf("PEBBLE_HEALTH"), "Pebble Time 2 1A2B", null))
        assertEquals(DeviceKind.COLMI_RING, GadgetbridgeMapping.kind(listOf("COLMI"), "R02_ABCD", null))
        assertEquals(DeviceKind.GARMIN, GadgetbridgeMapping.kind(emptyList(), "Forerunner 265", null))
        assertEquals(DeviceKind.GADGETBRIDGE, GadgetbridgeMapping.kind(listOf("HUAMI_EXTENDED"), "Amazfit Bip", null))
    }
}
