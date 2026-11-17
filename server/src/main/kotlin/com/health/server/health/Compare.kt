package com.health.server.health

import com.health.api.ComparedMetric
import com.health.api.ComparedNight
import com.health.api.ComparedValue
import com.health.api.CompareKey
import com.health.api.Device
import com.health.api.DeviceComparison
import com.health.api.Metric
import com.health.api.RecentDevice
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.server.ApiError
import com.health.server.insights.Combine
import com.health.server.util.Days
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two devices side by side, night by night, to see how far one reads from the other (a ring
 * against a chest strap, say). Readings are only set against each other over the time both were
 * measuring, so a device that slept in a drawer for an hour isn't counted against the other.
 * Nothing here changes any reading.
 */
class CompareService(
    private val records: Records,
    private val devices: DeviceStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun compare(userId: String, aId: String?, bId: String?, days: Int = DEFAULT_DAYS): DeviceComparison {
        val span = days.coerceIn(1, MAX_DAYS)
        val d = profiles.days(userId)
        val today = d.today(clock())
        val first = today.minusDays(span - 1L)
        val from = d.at(first.minusDays(1), EVENING)
        val to = d.end(today)
        val known = devices.list(userId)
        val askedA = aId?.takeIf { it.isNotBlank() }
        val askedB = bId?.takeIf { it.isNotBlank() }
        for (id in listOfNotNull(askedA, askedB)) if (known.none { it.id == id }) throw ApiError.notFound("No such device")
        if (askedA != null && askedA == askedB) throw ApiError.badRequest("Choose two different devices")

        val samples = records.samples(userId, from, to, METRICS)
        val sleep = records.sleep(userId, from, to).filter { !it.manual && it.deviceId != null }
        val counts = (samples.map { it.deviceId } + sleep.mapNotNull { it.deviceId }).groupingBy { it }.eachCount()
        val present = known.filter { it.id in counts }
        // A strap first: it's the one to measure the others against. Then the devices with the most readings.
        val ranked = present.sortedWith(
            compareByDescending<Device> { Combine.placement(it.kind, it.name) == Combine.Placement.CHEST }.thenByDescending { counts[it.id] ?: 0 },
        )
        val a = askedA ?: ranked.firstOrNull { it.id != askedB }?.id
        val b = askedB ?: ranked.firstOrNull { it.id != a }?.id
        fun ref(id: String?) = id?.let { i -> known.firstOrNull { it.id == i } }?.let { RecentDevice(it.id, it.name, it.kind, it.lastSyncAt) }
        val base = DeviceComparison(
            days = span, from = first.toString(), to = today.toString(), a = ref(a), b = ref(b),
            devices = present.map { RecentDevice(it.id, it.name, it.kind, it.lastSyncAt) },
        )
        if (a == null || b == null) return base

        val ofA = samples.filter { it.deviceId == a }.groupBy { it.metric }
        val ofB = samples.filter { it.deviceId == b }.groupBy { it.metric }
        val dates = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val windows = (dates + today.plusDays(1)).associateWith { date ->
            nightWindow(d, date, own(sleep, a, d, date), own(sleep, b, d, date))
        }
        val nights = dates.mapNotNull { date ->
            val sa = own(sleep, a, d, date)
            val sb = own(sleep, b, d, date)
            val window = windows.getValue(date)
            // The day: from this morning's waking to tonight's bedtime (or midnight, for today).
            val day = window.last until minOf(windows.getValue(date.plusDays(1)).first, d.end(date))
            val values = sleepValues(sa, sb, d.at(date.minusDays(1), EVENING)) +
                nightValues(ofA, ofB, window) +
                listOfNotNull(
                    alignedValue(CompareKey.DAY_HEART_RATE, ofA[Metric.HEART_RATE], ofB[Metric.HEART_RATE], day, HEART_MS),
                    steps(ofA[Metric.STEPS], ofB[Metric.STEPS], d.start(date) until d.end(date)),
                )
            if (sa == null && sb == null && values.isEmpty()) null else ComparedNight(date.toString(), sa, sb, values)
        }
        return base.copy(metrics = summarize(nights), nights = nights.reversed())
    }

    companion object {
        const val DEFAULT_DAYS = 14
        const val MAX_DAYS = 90
        private const val HOUR = 3_600_000L
        /** Heart rate is set side by side five minutes at a time, the rest half an hour at a time. */
        private const val HEART_MS = 5 * 60_000L
        private const val SPELL_MS = 30 * 60_000L
        /** A night's times are counted from 6 pm the evening before. */
        private val EVENING: LocalTime = LocalTime.of(18, 0)

        val METRICS = listOf(Metric.HEART_RATE, Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE, Metric.STEPS)

        private val NIGHT = listOf(
            Triple(CompareKey.HRV, Metric.HRV, SPELL_MS),
            Triple(CompareKey.SPO2, Metric.SPO2, SPELL_MS),
            Triple(CompareKey.RESPIRATORY_RATE, Metric.RESPIRATORY_RATE, SPELL_MS),
            Triple(CompareKey.SKIN_TEMPERATURE, Metric.SKIN_TEMPERATURE, SPELL_MS),
        )

        /** A device's main sleep that ended on the morning (or day) of [date]: its longest. */
        fun own(sleep: List<SleepSession>, deviceId: String, d: Days, date: LocalDate): SleepSession? =
            sleep.filter { it.deviceId == deviceId && it.end >= d.start(date) && it.end < d.at(date, EVENING) }.maxByOrNull { it.end - it.start }

        /** The night to compare readings over: both devices' sleep together, or the usual night hours with none. */
        fun nightWindow(d: Days, date: LocalDate, a: SleepSession?, b: SleepSession?): LongRange {
            val sessions = listOfNotNull(a, b)
            return if (sessions.isEmpty()) d.at(date.minusDays(1), LocalTime.of(22, 0)) until d.at(date, LocalTime.of(7, 0))
            else sessions.minOf { it.start } until sessions.maxOf { it.end }
        }

        /** Each device's own sleep: time asleep, the stages (where it tells them apart), and when. */
        fun sleepValues(a: SleepSession?, b: SleepSession?, evening: Long): List<ComparedValue> {
            if (a == null && b == null) return emptyList()
            fun minutes(ms: Long) = ms / 60_000.0
            fun staged(s: SleepSession?) = s?.stages?.any { it.stage == SleepStage.DEEP || it.stage == SleepStage.LIGHT || it.stage == SleepStage.REM } == true
            fun asleep(s: SleepSession?): Double? = s?.let {
                if (it.stages.isEmpty()) minutes(it.end - it.start) else minutes(it.stages.filter { st -> st.stage != SleepStage.AWAKE }.sumOf { st -> st.end - st.start })
            }
            fun stage(s: SleepSession?, stage: SleepStage): Double? = s?.takeIf(::staged)?.let { minutes(it.stages.filter { st -> st.stage == stage }.sumOf { st -> st.end - st.start }) }
            fun fellAsleep(s: SleepSession?): Double? = s?.let { (it.stages.firstOrNull { st -> st.stage != SleepStage.AWAKE }?.start ?: it.start) - evening }?.let(::minutes)
            fun woke(s: SleepSession?): Double? = s?.let { (it.stages.lastOrNull { st -> st.stage != SleepStage.AWAKE }?.end ?: it.end) - evening }?.let(::minutes)
            return listOf(
                ComparedValue(CompareKey.ASLEEP, asleep(a), asleep(b)),
                ComparedValue(CompareKey.DEEP, stage(a, SleepStage.DEEP), stage(b, SleepStage.DEEP)),
                ComparedValue(CompareKey.LIGHT, stage(a, SleepStage.LIGHT), stage(b, SleepStage.LIGHT)),
                ComparedValue(CompareKey.REM, stage(a, SleepStage.REM), stage(b, SleepStage.REM)),
                ComparedValue(CompareKey.AWAKE, stage(a, SleepStage.AWAKE), stage(b, SleepStage.AWAKE)),
                ComparedValue(CompareKey.BEDTIME, fellAsleep(a), fellAsleep(b)),
                ComparedValue(CompareKey.WAKE, woke(a), woke(b)),
            ).filter { it.a != null || it.b != null }.map { it.rounded() }
        }

        /** Heart rate, its lowest, HRV, blood oxygen, breathing and skin temperature over the time both measured in [window]. */
        fun nightValues(a: Map<Metric, List<StoredSample>>, b: Map<Metric, List<StoredSample>>, window: LongRange): List<ComparedValue> {
            val heart = aligned(a[Metric.HEART_RATE], b[Metric.HEART_RATE], window, HEART_MS)
            val heartValues = if (heart.isEmpty()) emptyList() else listOf(
                ComparedValue(CompareKey.NIGHT_HEART_RATE, heart.map { it.second }.average(), heart.map { it.third }.average(), heart.size),
                ComparedValue(CompareKey.LOWEST_HEART_RATE, Combine.lowest(heart.map { it.second }), Combine.lowest(heart.map { it.third }), heart.size),
            )
            return (heartValues + NIGHT.mapNotNull { (key, metric, width) -> alignedValue(key, a[metric], b[metric], window, width) }).map { it.rounded() }
        }

        fun alignedValue(key: CompareKey, a: List<StoredSample>?, b: List<StoredSample>?, window: LongRange, width: Long): ComparedValue? {
            val pairs = aligned(a, b, window, width)
            if (pairs.isEmpty()) return null
            return ComparedValue(key, pairs.map { it.second }.average(), pairs.map { it.third }.average(), pairs.size).rounded()
        }

        /** Each spell of [width] in [window] both devices had readings in: each one's average there, in time order. */
        fun aligned(a: List<StoredSample>?, b: List<StoredSample>?, window: LongRange, width: Long): List<Triple<Long, Double, Double>> {
            if (a.isNullOrEmpty() || b.isNullOrEmpty()) return emptyList()
            fun means(list: List<StoredSample>) = list.filter { it.start in window }.groupBy { it.start.floorDiv(width) }.mapValues { (_, l) -> l.sumOf { it.value } / l.size }
            val ma = means(a)
            val mb = means(b)
            return ma.keys.filter { it in mb }.sorted().map { Triple(it * width, ma.getValue(it), mb.getValue(it)) }
        }

        /** Each device's own steps for the day; compared only when both counted some. */
        fun steps(a: List<StoredSample>?, b: List<StoredSample>?, day: LongRange): ComparedValue? {
            val sa = a.orEmpty().filter { it.start in day }.sumOf { it.value }
            val sb = b.orEmpty().filter { it.start in day }.sumOf { it.value }
            if (sa <= 0 && sb <= 0) return null
            return ComparedValue(CompareKey.STEPS, sa.takeIf { it > 0 }?.roundToInt()?.toDouble(), sb.takeIf { it > 0 }?.roundToInt()?.toDouble())
        }

        /** Over every night both had a value: their averages, the average difference (b − a) and how far apart. */
        fun summarize(nights: List<ComparedNight>): List<ComparedMetric> = CompareKey.entries.mapNotNull { key ->
            val both = nights.mapNotNull { n -> n.values.firstOrNull { it.key == key }?.takeIf { it.a != null && it.b != null } }
            if (both.isEmpty()) return@mapNotNull null
            val differences = both.map { it.b!! - it.a!! }
            ComparedMetric(
                key = key,
                count = both.size,
                a = round(both.map { it.a!! }.average()),
                b = round(both.map { it.b!! }.average()),
                difference = round(differences.average()),
                apart = round(differences.map(::abs).average()),
                lowest = round(differences.min()),
                highest = round(differences.max()),
            )
        }

        private fun ComparedValue.rounded() = copy(a = a?.let(::round), b = b?.let(::round))
        private fun round(v: Double) = (v * 10).roundToInt() / 10.0
    }
}
