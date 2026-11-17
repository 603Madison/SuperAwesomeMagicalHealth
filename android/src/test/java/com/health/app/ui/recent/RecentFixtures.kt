package com.health.app.ui.recent

import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.RecentDevice
import com.health.api.RecentPoint
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The last 24 hours shaped like hers: a COLMI R02 on her finger (heart rate every 5–10 minutes, a
 * few HRV, blood oxygen and stress readings, and the night with its stages, 11:08 PM – 6:30 AM in
 * 17 spans) and her phone's steps through Health Connect. "Now" is 2:20 PM in New York.
 */
object RecentFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val today: LocalDate = LocalDate.of(2026, 11, 12)
    fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val yesterday = today.minusDays(1)

    val now = at(today, 14, 20)
    val from = now - 24 * 3_600_000L
    private const val MINUTE = 60_000L

    const val RING = "dev-ring"
    const val PHONE = "dev-phone"
    val ring = RecentDevice(RING, "COLMI R02_C301", DeviceKind.COLMI_RING, lastSyncAt = now - 18 * MINUTE)
    val phone = RecentDevice(PHONE, "Health Connect", DeviceKind.HEALTH_CONNECT, lastSyncAt = now - 42 * MINUTE)

    /** Last night: 17 spans of light, deep, REM and a few minutes awake. */
    val night: SleepSession by lazy {
        val start = at(yesterday, 23, 8)
        val pattern = listOf(
            SleepStage.LIGHT to 32, SleepStage.DEEP to 38, SleepStage.LIGHT to 26, SleepStage.REM to 18, SleepStage.LIGHT to 34,
            SleepStage.DEEP to 30, SleepStage.AWAKE to 4, SleepStage.LIGHT to 28, SleepStage.REM to 26, SleepStage.LIGHT to 36,
            SleepStage.DEEP to 17, SleepStage.LIGHT to 30, SleepStage.REM to 32, SleepStage.AWAKE to 3, SleepStage.LIGHT to 50,
            SleepStage.REM to 30, SleepStage.AWAKE to 8,
        )
        var t = start
        val stages = pattern.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * MINUTE).also { t += minutes * MINUTE } }
        SleepSession(start, t, stages, id = "night", deviceId = RING, source = ring.name)
    }

    /** A gentle wobble that's the same every run. */
    private fun wobble(t: Long, period: Double, size: Double) = sin(t / MINUTE / period * 2 * PI) * size

    private fun asleep(t: Long) = t in night.start until night.end

    /** Heart rate every 6–9 minutes, off the finger an hour in the evening to charge; a walk at 5:30 PM. */
    val heart: List<RecentPoint> by lazy {
        val charging = at(yesterday, 19, 5) until at(yesterday, 20, 10)
        val walk = at(yesterday, 17, 20) until at(yesterday, 17, 55)
        val out = ArrayList<RecentPoint>()
        var t = from + 3 * MINUTE
        var i = 0
        while (t <= now - 4 * MINUTE) {
            if (t !in charging) {
                val stage = night.stages.firstOrNull { t >= it.start && t < it.end }?.stage
                val base = when {
                    t in walk -> 104.0 + wobble(t, 9.0, 12.0)
                    stage == SleepStage.DEEP -> 53.0
                    stage == SleepStage.REM -> 61.0
                    stage == SleepStage.AWAKE -> 66.0
                    asleep(t) -> 57.0
                    else -> 74.0 + wobble(t, 97.0, 7.0)
                }
                out += RecentPoint(t, (base + wobble(t, 23.0, 2.5)).roundToInt().toDouble())
            }
            t += (6 + (i++ % 4)) * MINUTE
        }
        out
    }

    /** Blood oxygen now and then through the night, and once in the afternoon. */
    val spo2 = listOf(
        RecentPoint(at(yesterday, 16, 2), 98.0), RecentPoint(at(yesterday, 23, 40), 97.0), RecentPoint(at(today, 0, 45), 96.0),
        RecentPoint(at(today, 1, 50), 94.0), RecentPoint(at(today, 2, 55), 95.0), RecentPoint(at(today, 4, 0), 97.0),
        RecentPoint(at(today, 5, 5), 96.0), RecentPoint(at(today, 6, 10), 98.0), RecentPoint(at(today, 11, 30), 97.0),
    )

    /** HRV a few times overnight and twice in the day. */
    val hrv = listOf(
        RecentPoint(at(yesterday, 15, 30), 34.0), RecentPoint(at(today, 0, 15), 46.0), RecentPoint(at(today, 1, 30), 58.0),
        RecentPoint(at(today, 3, 0), 51.0), RecentPoint(at(today, 4, 30), 44.0), RecentPoint(at(today, 6, 0), 39.0),
        RecentPoint(at(today, 10, 15), 31.0),
    )

    /** Stress in the waking hours. */
    val stress = listOf(
        RecentPoint(at(yesterday, 14, 45), 38.0), RecentPoint(at(yesterday, 15, 45), 52.0), RecentPoint(at(yesterday, 16, 45), 61.0),
        RecentPoint(at(yesterday, 18, 15), 44.0), RecentPoint(at(yesterday, 21, 0), 27.0), RecentPoint(at(yesterday, 22, 15), 21.0),
        RecentPoint(at(today, 7, 30), 33.0), RecentPoint(at(today, 9, 0), 47.0), RecentPoint(at(today, 10, 30), 58.0),
        RecentPoint(at(today, 12, 0), 42.0), RecentPoint(at(today, 13, 30), 36.0),
    )

    /** The phone's steps an hour at a time from the window's start: nothing overnight, a walk at 5:30 PM. */
    val steps: List<RecentPoint> by lazy {
        val byHour = listOf(
            420, 610, 380, 2140, 520, 160, 240, 310, 90, 0, 0, 0, 0, 0, 0, 0, 0, 180, 620, 840, 450, 1210, 760, 330,
        )
        byHour.mapIndexedNotNull { h, n -> if (n == 0) null else RecentPoint(from + h * 3_600_000L, n.toDouble()) }
    }

    /** A sensor this ring doesn't have, for the imperial page: skin temperature overnight, and its change from her usual. */
    val skin = (0..14).map { i ->
        val t = night.start + i * 30 * MINUTE
        RecentPoint(t, (33.6 + i * 0.09 + wobble(t, 70.0, 0.15)).let { (it * 10).roundToInt() / 10.0 })
    }
    val deviation = listOf(RecentPoint(at(today, 6, 30), 0.3))
    val breathing = (0..7).map { i -> RecentPoint(night.start + (20 + i * 55) * MINUTE, listOf(14.0, 13.5, 13.0, 14.5, 15.0, 13.5, 14.0, 15.5)[i]) }

    fun series(metric: Metric?, points: List<RecentPoint>, counts: Boolean = false): RecentSeries {
        if (counts) return RecentSeries(metric, points, total = points.sumOf { it.value }, bucketMinutes = 60)
        val values = points.map { it.value }
        return RecentSeries(
            metric, points,
            min = values.min(), max = values.max(), average = (values.average() * 10).roundToInt() / 10.0, latest = points.maxBy { it.at },
        )
    }

    /** All devices: the ring's readings and night, the phone's steps, and a kind from a newer server (no metric) to skip. */
    val all: RecentReadings by lazy {
        RecentReadings(
            from = from, to = now, device = null,
            devices = listOf(ring, phone),
            sleep = listOf(night),
            series = listOf(
                series(Metric.HEART_RATE, heart), series(Metric.SPO2, spo2), series(Metric.HRV, hrv), series(Metric.STRESS, stress),
                series(null, listOf(RecentPoint(now - 3_600_000L, 12.0))),
                series(Metric.STEPS, steps, counts = true),
            ),
        )
    }

    /** The ring alone: everything but the phone's steps. */
    val ringAlone: RecentReadings by lazy { all.copy(device = RING, series = all.series.filter { it.metric != Metric.STEPS && it.metric != null }) }

    /** As [all], with skin temperature and breathing too (a ring that measures them), for °F. */
    val withTemperature: RecentReadings by lazy {
        all.copy(
            series = all.series.filter { it.metric != Metric.STEPS } + listOf(
                series(Metric.RESPIRATORY_RATE, breathing), series(Metric.SKIN_TEMPERATURE, skin), series(Metric.TEMPERATURE_DEVIATION, deviation),
                series(Metric.STEPS, steps, counts = true),
            ),
        )
    }

    /** Nothing in the window. */
    val empty = RecentReadings(from = from, to = now)
}
