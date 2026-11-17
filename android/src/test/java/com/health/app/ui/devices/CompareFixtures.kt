package com.health.app.ui.devices

import com.health.api.ComparedMetric
import com.health.api.ComparedNight
import com.health.api.ComparedValue
import com.health.api.CompareKey
import com.health.api.DeviceComparison
import com.health.api.DeviceKind
import com.health.api.RecentDevice
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two comparisons shaped like hers, in New York, "now" 2:20 PM on 12 November: her COLMI ring
 * checked against a Polar chest strap over four nights (the strap records no sleep, only heart rate
 * and HRV), and the ring against her Garmin watch over three nights, with each one's own night.
 * Worked out the way the server does (Compare.kt): sleep from each device's own stages, minutes
 * after 6 pm for when, and the summary over the nights both had a value.
 */
object CompareFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val today: LocalDate = LocalDate.of(2026, 11, 12)
    fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    val now = at(today, 14, 20)
    private const val MINUTE = 60_000L

    const val STRAP = "dev-strap"
    const val RING = "dev-ring"
    const val WATCH = "dev-watch"
    const val PHONE = "dev-phone"
    val strap = RecentDevice(STRAP, "Polar H10 7A2B4C1D", DeviceKind.HEART_RATE_STRAP, lastSyncAt = now - 35 * MINUTE)
    val ring = RecentDevice(RING, "COLMI R02_C301", DeviceKind.COLMI_RING, lastSyncAt = now - 18 * MINUTE)
    val watch = RecentDevice(WATCH, "Garmin Connect", DeviceKind.GARMIN, lastSyncAt = now - 52 * MINUTE)
    val phone = RecentDevice(PHONE, "Health Connect", DeviceKind.HEALTH_CONNECT, lastSyncAt = now - 42 * MINUTE)

    /** A night that ended on the morning of [date], starting at [h]:[m] the evening before (or after midnight for h < 12). */
    fun session(deviceId: String, source: String, date: LocalDate, h: Int, m: Int, pattern: List<Pair<SleepStage, Int>>): SleepSession {
        val start = at(if (h >= 12) date.minusDays(1) else date, h, m)
        var t = start
        val stages = pattern.map { (stage, minutes) -> StageSpan(stage, t, t + minutes * MINUTE).also { t += minutes * MINUTE } }
        return SleepSession(start, t, stages, id = "$deviceId-$date", deviceId = deviceId, source = source)
    }

    private val RING_PATTERN = listOf(
        SleepStage.LIGHT to 32, SleepStage.DEEP to 38, SleepStage.LIGHT to 26, SleepStage.REM to 18, SleepStage.LIGHT to 34,
        SleepStage.DEEP to 30, SleepStage.AWAKE to 4, SleepStage.LIGHT to 28, SleepStage.REM to 26, SleepStage.LIGHT to 36,
        SleepStage.DEEP to 17, SleepStage.LIGHT to 30, SleepStage.REM to 32, SleepStage.AWAKE to 3, SleepStage.LIGHT to 50,
        SleepStage.REM to 30, SleepStage.AWAKE to 8,
    )

    /** The same night as a watch on the wrist tells it: a little later to sleep, less deep, more light. */
    private val WATCH_PATTERN = listOf(
        SleepStage.AWAKE to 9, SleepStage.LIGHT to 35, SleepStage.DEEP to 29, SleepStage.LIGHT to 33, SleepStage.REM to 21, SleepStage.LIGHT to 40,
        SleepStage.DEEP to 22, SleepStage.AWAKE to 6, SleepStage.LIGHT to 35, SleepStage.REM to 24, SleepStage.LIGHT to 39,
        SleepStage.DEEP to 12, SleepStage.LIGHT to 33, SleepStage.REM to 29, SleepStage.LIGHT to 47, SleepStage.REM to 22, SleepStage.AWAKE to 5,
    )

    /** A pattern a few minutes longer or shorter in its light sleep, so the nights aren't all alike. */
    private fun varied(pattern: List<Pair<SleepStage, Int>>, by: Int) = pattern.mapIndexed { i, (s, m) -> s to if (s == SleepStage.LIGHT && i % 3 == 0) (m + by).coerceAtLeast(5) else m }

    private fun round(v: Double) = (v * 10).roundToInt() / 10.0

    /** Each device's own sleep, as the server works it out (minutes; when, as minutes after 6 pm). */
    fun sleepValues(a: SleepSession?, b: SleepSession?, date: LocalDate): List<ComparedValue> {
        if (a == null && b == null) return emptyList()
        val evening = at(date.minusDays(1), 18)
        fun minutes(ms: Long) = ms / 60_000.0
        fun asleep(s: SleepSession?) = s?.let { minutes(it.stages.filter { st -> st.stage != SleepStage.AWAKE }.sumOf { st -> st.end - st.start }) }
        fun stage(s: SleepSession?, stage: SleepStage) = s?.let { minutes(it.stages.filter { st -> st.stage == stage }.sumOf { st -> st.end - st.start }) }
        fun fell(s: SleepSession?) = s?.let { minutes((it.stages.first { st -> st.stage != SleepStage.AWAKE }.start) - evening) }
        fun woke(s: SleepSession?) = s?.let { minutes((it.stages.last { st -> st.stage != SleepStage.AWAKE }.end) - evening) }
        return listOf(
            ComparedValue(CompareKey.ASLEEP, asleep(a), asleep(b)),
            ComparedValue(CompareKey.DEEP, stage(a, SleepStage.DEEP), stage(b, SleepStage.DEEP)),
            ComparedValue(CompareKey.LIGHT, stage(a, SleepStage.LIGHT), stage(b, SleepStage.LIGHT)),
            ComparedValue(CompareKey.REM, stage(a, SleepStage.REM), stage(b, SleepStage.REM)),
            ComparedValue(CompareKey.AWAKE, stage(a, SleepStage.AWAKE), stage(b, SleepStage.AWAKE)),
            ComparedValue(CompareKey.BEDTIME, fell(a), fell(b)),
            ComparedValue(CompareKey.WAKE, woke(a), woke(b)),
        ).map { it.copy(a = it.a?.let(::round), b = it.b?.let(::round)) }
    }

    /** Over every night both had a value, as the server sums it up. */
    fun summarize(nights: List<ComparedNight>): List<ComparedMetric> = CompareKey.entries.mapNotNull { key ->
        val both = nights.mapNotNull { n -> n.values.firstOrNull { it.key == key }?.takeIf { it.a != null && it.b != null } }
        if (both.isEmpty()) return@mapNotNull null
        val d = both.map { it.b!! - it.a!! }
        ComparedMetric(
            key, both.size, round(both.map { it.a!! }.average()), round(both.map { it.b!! }.average()),
            round(d.average()), round(d.map(::abs).average()), round(d.min()), round(d.max()),
        )
    }

    private fun v(key: CompareKey, a: Double?, b: Double?, pairs: Int = 0) = ComparedValue(key, a, b, pairs)

    // ---- The ring checked against the strap: four nights out of the last fortnight (newest first).

    val ringVsStrap: DeviceComparison by lazy {
        // date offset, the ring's bedtime, light-sleep change, then the strap's and the ring's heart rate, lowest and HRV.
        data class N(val ago: Long, val h: Int, val m: Int, val by: Int, val hr: Pair<Double, Double>, val low: Pair<Double, Double>, val hrv: Pair<Double, Double>, val day: Pair<Double, Double>?)
        val spec = listOf(
            N(0, 23, 8, 0, 55.2 to 58.4, 48.0 to 50.0, 54.0 to 47.0, 72.6 to 76.3),
            N(1, 22, 51, 6, 57.0 to 56.4, 50.0 to 51.0, 49.0 to 50.8, null),
            N(3, 23, 34, -4, 54.1 to 60.9, 47.0 to 51.0, 58.0 to 45.6, 71.8 to 71.3),
            N(4, 0, 12, 3, 56.9 to 59.9, 51.0 to 54.0, 48.0 to 41.0, null),
        )
        val nights = spec.map { n ->
            val date = today.minusDays(n.ago)
            val night = session(RING, ring.name, date, n.h, n.m, varied(RING_PATTERN, n.by))
            ComparedNight(
                date.toString(), a = null, b = night,
                values = sleepValues(null, night, date) + listOfNotNull(
                    v(CompareKey.NIGHT_HEART_RATE, n.hr.first, n.hr.second, 74),
                    v(CompareKey.LOWEST_HEART_RATE, n.low.first, n.low.second, 74),
                    v(CompareKey.HRV, n.hrv.first, n.hrv.second, 12),
                    n.day?.let { v(CompareKey.DAY_HEART_RATE, it.first, it.second, 31) },
                ),
            )
        }
        DeviceComparison(
            days = 14, from = today.minusDays(13).toString(), to = today.toString(), a = strap, b = ring,
            devices = listOf(strap, ring, phone), metrics = summarize(nights), nights = nights,
        )
    }

    // ---- The ring checked against the watch: three nights, each with both nights' stages.

    val ringVsWatch: DeviceComparison by lazy {
        val spec = listOf(Triple(0L, 0, 0), Triple(1L, 9, 5), Triple(2L, -14, -3))
        val nights = spec.map { (ago, shift, by) ->
            val date = today.minusDays(ago)
            val a = session(WATCH, watch.name, date, 23, (14 + shift).coerceIn(0, 59), varied(WATCH_PATTERN, by))
            val b = session(RING, ring.name, date, 23, (8 + shift).coerceIn(0, 59), varied(RING_PATTERN, by))
            ComparedNight(
                date.toString(), a = a, b = b,
                values = sleepValues(a, b, date) + listOf(
                    v(CompareKey.NIGHT_HEART_RATE, 57.3 + ago, 58.6 + ago, 66),
                    v(CompareKey.LOWEST_HEART_RATE, 50.0 + ago, 51.0 + ago, 66),
                    v(CompareKey.HRV, 44.0 - ago, 47.5 - ago, 11),
                    v(CompareKey.SPO2, 95.8, 96.9 - ago * 0.3, 9),
                    v(CompareKey.STEPS, 8214.0 + ago * 900, 7610.0 + ago * 1050),
                ),
            )
        }
        DeviceComparison(
            days = 7, from = today.minusDays(6).toString(), to = today.toString(), a = watch, b = ring,
            devices = listOf(ring, watch, phone), metrics = summarize(nights), nights = nights,
        )
    }

    /** Only the ring had readings: nothing to set it against. */
    val oneDevice = DeviceComparison(days = 14, from = today.minusDays(13).toString(), to = today.toString(), a = ring, devices = listOf(ring))

    /** Both have readings, but never on the same night. */
    val neverTogether = DeviceComparison(days = 14, from = today.minusDays(13).toString(), to = today.toString(), a = strap, b = ring, devices = listOf(strap, ring))
}
