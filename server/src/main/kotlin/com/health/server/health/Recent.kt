package com.health.server.health

import com.health.api.Metric
import com.health.api.RecentDevice
import com.health.api.RecentPoint
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.server.ApiError
import com.health.server.insights.Combine
import com.health.server.insights.DeviceRef
import kotlin.math.roundToInt

/**
 * The last day or so as it was recorded, for looking back over a night and a day: each night's
 * stages, heart rate, blood oxygen, HRV, stress, temperature and steps, from one device or all of
 * them. Nothing is worked out here beyond putting readings side by side.
 */
class RecentService(private val records: Records, private val devices: DeviceStore, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun recent(userId: String, hours: Int = 24, deviceId: String? = null): RecentReadings {
        val span = hours.coerceIn(1, MAX_HOURS)
        val to = clock()
        val from = to - span * HOUR
        val known = devices.list(userId)
        val chosen = deviceId?.takeIf { it.isNotBlank() }
        if (chosen != null && known.none { it.id == chosen }) throw ApiError.notFound("No such device")
        // A little past now: a device's clock can run a minute ahead of the server's.
        val samples = records.samples(userId, from, to + AHEAD, METRICS)
        val nights = records.sleep(userId, from, to + DAY).filter { it.start < to + AHEAD && it.end > from }
        val present = (samples.map { it.deviceId } + nights.mapNotNull { it.deviceId }).toSet()
        val mine = samples.filter { chosen == null || it.deviceId == chosen }
        val straps = Combine.straps(known.associate { it.id to DeviceRef(it.id, it.name, it.kind) })
        return RecentReadings(
            from = from,
            to = to,
            hours = span,
            device = chosen,
            devices = known.filter { it.id in present }.map { RecentDevice(it.id, it.name, it.kind, it.lastSyncAt) },
            sleep = nights.filter { chosen == null || it.deviceId == chosen }.sortedBy { it.start },
            series = METRICS.mapNotNull { metric -> series(metric, mine.filter { it.metric == metric }, from, straps) },
        )
    }

    companion object {
        const val MAX_HOURS = 72
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val AHEAD = 60_000L
        private const val SLOT = 15 * 60_000L

        /** At most this many points a kind: past it, readings are averaged a few minutes at a time. */
        const val MAX_POINTS = 720

        /** What's shown, in order. */
        val METRICS = listOf(
            Metric.HEART_RATE, Metric.SPO2, Metric.HRV, Metric.STRESS, Metric.RESPIRATORY_RATE,
            Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION, Metric.STEPS,
        )

        private val COUNTS = setOf(Metric.STEPS)

        /** One kind of reading over the window, or null with none. */
        fun series(metric: Metric, samples: List<StoredSample>, from: Long, straps: Set<String> = emptySet()): RecentSeries? {
            if (samples.isEmpty()) return null
            if (metric in COUNTS) return counts(metric, samples, from)
            val devices = samples.map { it.deviceId }.toSet()
            val points = when {
                // Heart rate from more than one device: merged in time, so none outweighs another (a strap, where it measured, alone).
                metric == Metric.HEART_RATE && devices.size > 1 -> Combine.line(samples, prefer = straps).map { RecentPoint(it.t, it.v) }
                else -> thinned(samples.sortedBy { it.start }.map { RecentPoint(it.start, it.value) })
            }
            val values = samples.map { it.value }
            return RecentSeries(
                metric = metric,
                points = points.map { it.copy(value = round(it.value)) },
                min = round(values.min()),
                max = round(values.max()),
                average = round(values.average()),
                latest = samples.maxBy { it.start }.let { RecentPoint(it.start, round(it.value)) },
            )
        }

        /**
         * Steps an hour at a time. Each quarter hour takes the device that counted most there, so two
         * devices counting the same walk count it once.
         */
        private fun counts(metric: Metric, samples: List<StoredSample>, from: Long): RecentSeries {
            val slots = samples.groupBy { (it.start - from).floorDiv(SLOT) }.mapValues { (_, inSlot) ->
                inSlot.groupBy { it.deviceId }.values.maxOf { device -> device.sumOf { it.value } }
            }
            val hours = slots.entries.groupBy { (slot, _) -> slot * SLOT / HOUR }.toSortedMap()
                .map { (hour, list) -> RecentPoint(from + hour * HOUR, list.sumOf { it.value }) }
            return RecentSeries(metric = metric, points = hours.map { it.copy(value = it.value.roundToInt().toDouble()) }, total = hours.sumOf { it.value }.roundToInt().toDouble(), bucketMinutes = 60)
        }

        /** Very many readings (a sensor that reads every second) averaged a few minutes at a time. */
        fun thinned(points: List<RecentPoint>): List<RecentPoint> {
            if (points.size <= MAX_POINTS) return points
            val first = points.first().at
            val width = ((points.last().at - first) / MAX_POINTS).coerceAtLeast(60_000L)
            return points.groupBy { (it.at - first) / width }.toSortedMap().map { (bucket, list) -> RecentPoint(first + bucket * width, list.sumOf { it.value } / list.size) }
        }

        private fun round(v: Double) = (v * 10).roundToInt() / 10.0
    }
}
