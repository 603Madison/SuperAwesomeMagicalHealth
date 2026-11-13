package com.health.server.insights

import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.DeviceChoices
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.Point
import com.health.api.SleepSession
import com.health.api.SleepSource
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.api.SourceValue
import com.health.server.health.StoredSample
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A device as combining needs it: what it's called and what kind it is. */
data class DeviceRef(val id: String, val name: String, val kind: DeviceKind?)

/** One device's own value for a reading on one day (a night's HRV, a day's stress), kept for its usual. */
@Serializable
data class DeviceReading(
    val metric: Metric,
    val deviceId: String,
    val device: String,
    val kind: DeviceKind? = null,
    val value: Double,
    val readings: Int,
    /** How much of the night (for stress, of the day) it measured, 0–1. */
    val coverage: Double = 1.0,
)

/**
 * When more than one device measures the same thing, the app's own number from all of them, as
 * pure functions (see Devices.kt for the rules in plain words). With one device, its numbers are
 * used as they are; combining only starts where a second device measured too, or the person
 * chose one device for a reading.
 */
object Combine {
    /** Counts are compared a quarter of an hour at a time. */
    const val SLOT_MS = 15 * 60_000L
    /** Heart rate is merged five minutes at a time. */
    const val HEART_MS = 5 * 60_000L
    /** A count reading longer than this (a day's total, say) only tops up what other devices counted over the same time. */
    const val LONG_MS = 60 * 60_000L
    /** Bedtimes (and wake times) this close are averaged. */
    const val CLOSE_MS = 45 * 60_000L
    /** A device's usual: the median of its own values over this many days before, once it has [BASELINE_MIN]. */
    const val BASELINE_DAYS = 14
    const val BASELINE_MIN = 3
    /** A device far from its own usual while another isn't counts for this share of its weight. */
    const val FAR_WEIGHT = 0.25
    /** A night with no stages (time in bed only) counts for this share of its weight in time asleep. */
    const val NO_STAGES_WEIGHT = 0.5
    /** Gaps between readings longer than this count as time not measured. */
    private const val MAX_GAP_MS = 90 * 60_000L

    val COUNTS = listOf(Metric.STEPS, Metric.DISTANCE, Metric.ACTIVE_CALORIES, Metric.FLOORS)

    /** Readings combined from each device's own night (or day) value. */
    val WEIGHTED = listOf(Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION, Metric.STRESS)

    /** The readings a person can pick one device for. */
    val CHOOSABLE: Set<Metric> = (COUNTS + Metric.HEART_RATE + Metric.RESTING_HEART_RATE + WEIGHTED).toSet()

    /** HRV and skin temperature read differently on a finger and a wrist: each device is compared with its own usual first. */
    private val RELATIVE = setOf(Metric.HRV, Metric.SKIN_TEMPERATURE)

    /** Readings of the night (the rest, stress, of the day). */
    private val NIGHT = setOf(Metric.RESTING_HEART_RATE, Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION)

    // ---- Devices and how well each suits a reading ----

    /** Where a device is worn. */
    enum class Placement(val noun: String?) { FINGER("ring"), WRIST("watch"), PHONE("phone"), OTHER(null) }

    fun placement(kind: DeviceKind?, name: String): Placement = when (kind) {
        DeviceKind.COLMI_RING -> Placement.FINGER
        DeviceKind.PEBBLE, DeviceKind.GARMIN -> Placement.WRIST
        DeviceKind.PHONE -> Placement.PHONE
        else -> {
            // Gadgetbridge, Health Connect and the rest pass on many devices: the name says which.
            val words = name.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            when {
                "ring" in words || "oura" in words || "colmi" in words -> Placement.FINGER
                words.any { it in WRIST_WORDS } -> Placement.WRIST
                else -> Placement.OTHER
            }
        }
    }

    private val WRIST_WORDS = setOf("watch", "band", "pebble", "garmin", "fitbit", "amazfit", "bip", "gtr", "gts", "huawei", "polar", "suunto", "withings", "bangle", "pinetime")

    /** How well a device on a finger, a wrist, in a pocket or elsewhere suits a reading, 0–1. */
    data class Suits(val finger: Double, val wrist: Double, val phone: Double, val other: Double) {
        fun of(p: Placement) = when (p) {
            Placement.FINGER -> finger
            Placement.WRIST -> wrist
            Placement.PHONE -> phone
            Placement.OTHER -> other
        }
    }

    /**
     * The weights, in one place. A ring sits on a finger, where the pulse is strong and the hand is
     * still at night: best for night-time HRV, blood oxygen and temperature. A watch is on the
     * wrist, better in the day (it moves less with the hands and is worn while active).
     */
    val SUITS: Map<Metric, Suits> = mapOf(
        Metric.RESTING_HEART_RATE to Suits(finger = 1.0, wrist = 0.8, phone = 0.2, other = 0.6),
        Metric.HRV to Suits(finger = 1.0, wrist = 0.6, phone = 0.1, other = 0.5),
        Metric.SPO2 to Suits(finger = 1.0, wrist = 0.5, phone = 0.1, other = 0.5),
        Metric.RESPIRATORY_RATE to Suits(finger = 0.9, wrist = 0.7, phone = 0.2, other = 0.6),
        Metric.SKIN_TEMPERATURE to Suits(finger = 1.0, wrist = 0.5, phone = 0.1, other = 0.5),
        Metric.TEMPERATURE_DEVIATION to Suits(finger = 1.0, wrist = 0.5, phone = 0.1, other = 0.5),
        Metric.STRESS to Suits(finger = 0.6, wrist = 1.0, phone = 0.2, other = 0.7),
    )

    /** For time asleep. */
    val SLEEP_SUITS = Suits(finger = 1.0, wrist = 0.9, phone = 0.4, other = 0.7)

    /** How far from its own usual a device can be before it counts for less (for HRV, as a share of the usual). */
    val FAR: Map<Metric, Double> = mapOf(
        Metric.HRV to 0.35,
        Metric.SPO2 to 3.0,
        Metric.RESPIRATORY_RATE to 3.0,
        Metric.SKIN_TEMPERATURE to 1.0,
        Metric.TEMPERATURE_DEVIATION to 1.0,
        Metric.STRESS to 25.0,
    )

    fun suits(metric: Metric, kind: DeviceKind?, name: String): Double = (SUITS[metric] ?: Suits(1.0, 1.0, 1.0, 1.0)).of(placement(kind, name))

    /** Short names for devices among [refs]: "ring", "watch" where that tells them apart, the device's own name otherwise. */
    fun shortNames(refs: Collection<DeviceRef>): Map<String, String> {
        val unique = refs.distinctBy { it.id }
        val placed = unique.associate { it.id to placement(it.kind, it.name) }
        return unique.associate { ref ->
            val p = placed.getValue(ref.id)
            ref.id to if (p.noun != null && placed.values.count { it == p } == 1) p.noun else ref.name
        }
    }

    /** Short names for the devices behind a combined value: "Resting heart rate 53 (ring 52, watch 55)". */
    fun shortNamesOf(sources: List<SourceValue>): Map<String, String> = shortNames(sources.map { DeviceRef(it.deviceId, it.device, it.kind) })

    /** How devices are named in [CombinedValue.how]: "the ring", or a device's own name. */
    private class Names(refs: Collection<DeviceRef>) {
        private val short = shortNames(refs)
        private val named = refs.associate { it.id to it.name }
        fun the(id: String?): String {
            val s = id?.let { short[it] } ?: return "the device you chose"
            return if (s != named[id]) "the $s" else s
        }
        fun theirs(id: String?) = "${the(id)}'s"
    }

    private fun capital(text: String) = text.replaceFirstChar { it.uppercase() }

    private fun allOf(n: Int) = when (n) {
        2 -> "both"
        3 -> "all three"
        else -> "all $n"
    }

    /** Whether the person's choice applies: none made, the device had readings ([USED]), or it had none ([FALLBACK]). */
    enum class Choice { NONE, USED, FALLBACK }

    fun choice(chosen: String?, present: Collection<String>): Choice = when {
        chosen == null -> Choice.NONE
        chosen in present -> Choice.USED
        else -> Choice.FALLBACK
    }

    /** The chosen device's readings of [metric] where it has some; everything otherwise. */
    fun pick(samples: List<StoredSample>, metric: Metric, chosen: String?): List<StoredSample> {
        if (chosen == null || samples.none { it.metric == metric && it.deviceId == chosen }) return samples
        return samples.filter { it.metric != metric || it.deviceId == chosen }
    }

    private fun fallback(how: String, chosen: String?, names: Names, what: String = "had none that day") =
        "You chose ${names.the(chosen)}, which $what. $how"

    // ---- Counts: steps, distance, calories, floors ----

    /** A day's count: its total, and each quarter hour's (for hourly steps). */
    class Counted(val total: Double, val slots: DoubleArray, val slotStart: Long, val combined: CombinedValue?)

    /**
     * Each quarter hour, the device that counted most: the same walk isn't counted twice, and one
     * taken off doesn't lose the steps the other counted. A reading longer than an hour (a day's
     * total) is spread over its time, but only tops up what the others counted then.
     */
    fun counts(metric: Metric, samples: List<StoredSample>, devices: Map<String, DeviceRef>, chosen: String?, dayStart: Long, dayEnd: Long): Counted? {
        val byDevice = samples.filter { it.metric == metric }.groupBy { it.deviceId }
        if (byDevice.isEmpty()) return null
        val own = byDevice.mapValues { (_, list) -> list.sumOf { it.value } }
        val choice = choice(chosen, byDevice.keys)
        val used = if (choice == Choice.USED) byDevice.filterKeys { it == chosen } else byDevice
        val n = ((dayEnd - dayStart + SLOT_MS - 1) / SLOT_MS).toInt().coerceAtLeast(1)

        // Short readings, spread over the quarter hours they cover.
        val fine = mutableMapOf<String, DoubleArray>()
        val long = mutableListOf<StoredSample>()
        used.forEach { (id, list) ->
            list.forEach { s -> if (length(s) > LONG_MS) long += s else parts(s, dayStart, n).forEach { (i, share, _) -> fine.getOrPut(id) { DoubleArray(n) }[i] += s.value * share } }
        }
        val slots = DoubleArray(n)
        val share = mutableMapOf<String, Double>()
        // Ties go to the device that counted more over the whole day.
        val order = used.keys.sortedByDescending { own[it] }
        for (i in 0 until n) {
            var best: String? = null
            var most = 0.0
            order.forEach { id ->
                val v = fine[id]?.get(i) ?: 0.0
                if (v > most) {
                    most = v
                    best = id
                }
            }
            slots[i] = most
            best?.let { share[it] = (share[it] ?: 0.0) + most }
        }
        // Long readings: what's beyond what the others counted over the same time goes where they counted nothing.
        val ownLong = mutableMapOf<String, DoubleArray>()
        long.sortedWith(compareBy<StoredSample> { it.start }.thenBy { it.end }).forEach { s ->
            val mine = ownLong.getOrPut(s.deviceId) { DoubleArray(n) }
            val covered = parts(s, dayStart, n)
            val have = covered.sumOf { (i, _, cover) -> cover * (slots[i] - mine[i]) }
            val extra = s.value - have
            if (extra <= 0) return@forEach
            val free = covered.filter { (i, _, _) -> slots[i] - mine[i] <= 1e-9 }
            val targets = free.takeIf { list -> list.sumOf { it.second } > 0 } ?: covered
            val total = targets.sumOf { it.second }
            targets.forEach { (i, part, _) ->
                val add = extra * part / total
                slots[i] += add
                mine[i] += add
            }
            share[s.deviceId] = (share[s.deviceId] ?: 0.0) + extra
        }
        val total = slots.sum()
        val combined = if (byDevice.size >= 2 || choice == Choice.FALLBACK) {
            val names = Names(byDevice.keys.mapNotNull { devices[it] } + listOfNotNull(chosen?.let { devices[it] }))
            val shareTotal = share.values.sum().takeIf { it > 0 } ?: 1.0
            val sources = byDevice.keys.map { id ->
                val ref = devices[id]
                SourceValue(
                    deviceId = id, device = ref?.name ?: byDevice.getValue(id).first().deviceName, kind = ref?.kind, value = own.getValue(id),
                    weight = when (choice) {
                        Choice.USED -> if (id == chosen) 1.0 else 0.0
                        else -> (share[id] ?: 0.0) / shareTotal
                    },
                    readings = byDevice.getValue(id).size,
                    note = if (choice == Choice.USED && id != chosen) "not chosen" else null,
                )
            }.sortedByDescending { it.weight }
            val first = sources.first().deviceId
            val how = when {
                choice == Choice.USED -> "Your choice: ${names.theirs(chosen)} count"
                byDevice.size == 1 -> "${capital(names.theirs(first))} count"
                sources.drop(1).any { it.weight > 0.0 } ->
                    "${capital(names.theirs(first))} count each 15 minutes, or " +
                        (if (byDevice.size == 2) names.theirs(sources[1].deviceId) else "another device's") + " where it counted more"
                else -> "${capital(names.theirs(first))} count: it counted more than ${if (byDevice.size == 2) names.the(sources[1].deviceId) else "the others"} every 15 minutes"
            }
            CombinedValue(metric, total, sources, if (choice == Choice.FALLBACK) fallback(how, chosen, names) else how, chosenDeviceId = chosen)
        } else {
            null
        }
        return Counted(total, slots, dayStart, combined)
    }

    private fun length(s: StoredSample) = (s.end ?: s.start) - s.start

    /**
     * The quarter hours a reading covers: (index, its share of the reading, how much of the quarter
     * hour it covers). Times past the day's ends fall into its first or last quarter hour.
     */
    private fun parts(s: StoredSample, dayStart: Long, n: Int): List<Triple<Int, Double, Double>> {
        fun index(t: Long) = ((t - dayStart).floorDiv(SLOT_MS)).toInt().coerceIn(0, n - 1)
        val end = s.end ?: s.start
        if (end <= s.start) return listOf(Triple(index(s.start), 1.0, 1.0))
        val span = (end - s.start).toDouble()
        val out = mutableMapOf<Int, Pair<Double, Double>>()
        var t = s.start
        while (t < end) {
            val slotEnd = dayStart + ((t - dayStart).floorDiv(SLOT_MS) + 1) * SLOT_MS
            val to = minOf(end, slotEnd)
            val i = index(t)
            val (a, b) = out[i] ?: (0.0 to 0.0)
            out[i] = (a + (to - t) / span) to minOf(1.0, b + (to - t).toDouble() / SLOT_MS)
            t = to
        }
        return out.map { (i, v) -> Triple(i, v.first, v.second) }
    }

    // ---- Heart rate ----

    /**
     * Heart rate merged in time: each five minutes, each device's average there, then the average
     * of the devices that had readings. One that samples every minute doesn't outweigh one that
     * samples every five.
     */
    fun line(samples: List<StoredSample>, width: Long = HEART_MS): List<Point> =
        samples.groupBy { it.start.floorDiv(width) }.toSortedMap().map { (bucket, list) ->
            Point(bucket * width, list.groupBy { it.deviceId }.values.map { l -> l.sumOf { it.value } / l.size }.average())
        }

    /** The day's heart rate: average, lowest and highest. */
    class Heart(val avg: Double?, val min: Double?, val max: Double?, val combined: CombinedValue?)

    /** With one device, its own readings; with more, the merged line (see [line]). */
    fun heart(samples: List<StoredSample>, devices: Map<String, DeviceRef>, chosen: String?): Heart {
        val byDevice = samples.filter { it.metric == Metric.HEART_RATE }.groupBy { it.deviceId }
        if (byDevice.isEmpty()) return Heart(null, null, null, null)
        val choice = choice(chosen, byDevice.keys)
        val used = if (choice == Choice.USED) byDevice.filterKeys { it == chosen } else byDevice
        val values: List<Double> = if (used.size == 1) used.values.single().map { it.value } else line(used.values.flatten()).map { it.v }
        val heart = Heart(values.average(), values.min(), values.max(), null)
        if (byDevice.size < 2 && choice != Choice.FALLBACK) return heart
        val names = Names(byDevice.keys.mapNotNull { devices[it] } + listOfNotNull(chosen?.let { devices[it] }))
        val buckets = byDevice.mapValues { (_, l) -> l.map { it.start.floorDiv(HEART_MS) }.distinct().size.toDouble() }
        val sources = sourcesOf(byDevice.mapValues { (_, l) -> l.map { it.value }.average() }, byDevice, devices, choice, chosen, buckets)
        val how = when {
            choice == Choice.USED -> "Your choice: ${names.theirs(chosen)} heart rate"
            byDevice.size == 1 -> "${capital(names.theirs(sources.first().deviceId))} heart rate"
            else -> "Merged every 5 minutes: where ${if (byDevice.size == 2) "both" else "more than one"} measured, their average"
        }
        return Heart(heart.avg, heart.min, heart.max, CombinedValue(Metric.HEART_RATE, heart.avg!!, sources, if (choice == Choice.FALLBACK) fallback(how, chosen, names) else how, chosen))
    }

    /** Sources weighted by [weights] (normalised), or 1 for the chosen device and 0 for the rest. */
    private fun sourcesOf(
        values: Map<String, Double>, samples: Map<String, List<StoredSample>>, devices: Map<String, DeviceRef>, choice: Choice, chosen: String?, weights: Map<String, Double>,
    ): List<SourceValue> {
        val total = weights.values.sum().takeIf { it > 0 } ?: 1.0
        return values.map { (id, v) ->
            val ref = devices[id]
            SourceValue(
                deviceId = id, device = ref?.name ?: samples[id]?.firstOrNull()?.deviceName ?: "A device", kind = ref?.kind, value = v,
                weight = if (choice == Choice.USED) (if (id == chosen) 1.0 else 0.0) else (weights[id] ?: 0.0) / total,
                readings = samples[id]?.size ?: 0,
                note = if (choice == Choice.USED && id != chosen) "not chosen" else null,
            )
        }.sortedByDescending { it.weight }
    }

    /** The lowest of three readings in a row, so one dropped reading isn't taken for the lowest. */
    fun lowest(values: List<Double>): Double? = when {
        values.isEmpty() -> null
        values.size < 3 -> values.min()
        else -> values.windowed(3).minOf { it.average() }
    }

    /** A night's heart rate: one device's readings as they are, or more than one's merged. */
    fun nightHeart(samples: List<StoredSample>): List<Double> =
        if (samples.map { it.deviceId }.distinct().size >= 2) line(samples).map { it.v } else samples.map { it.value }

    /**
     * Resting heart rate: the devices' own resting figures where they give them (weighted to the
     * one that suits the night), otherwise the lowest the heart stayed overnight, from the merged line.
     */
    fun resting(explicit: List<StoredSample>, night: List<StoredSample>, devices: Map<String, DeviceRef>, chosen: String?): Pair<Double?, CombinedValue?> {
        val stated = explicit.groupBy { it.deviceId }
        val overnight = night.groupBy { it.deviceId }
        val own: Map<String, Double> = (overnight.mapValues { (_, l) -> lowest(l.map { it.value }) }.filterValues { it != null }.mapValues { it.value!! }) +
            stated.mapValues { (_, l) -> l.map { it.value }.average() }
        if (own.isEmpty()) return null to null
        val names = Names(own.keys.mapNotNull { devices[it] } + listOfNotNull(chosen?.let { devices[it] }))
        val choice = choice(chosen, own.keys)
        if (choice == Choice.USED) {
            val value = own.getValue(chosen!!)
            if (own.size < 2) return value to null
            val samples = own.keys.associateWith { stated[it] ?: overnight[it].orEmpty() }
            return value to CombinedValue(Metric.RESTING_HEART_RATE, value, sourcesOf(own, samples, devices, choice, chosen, emptyMap()), "Your choice: ${names.theirs(chosen)} resting heart rate", chosen)
        }
        val (value, sources, how) = if (stated.isNotEmpty()) {
            val values = stated.mapValues { (_, l) -> l.map { it.value }.average() }
            val weights = values.keys.associateWith { id -> devices[id].let { suits(Metric.RESTING_HEART_RATE, it?.kind, it?.name.orEmpty()) } }
            val total = weights.values.sum()
            val value = values.entries.sumOf { (id, v) -> v * weights.getValue(id) } / total
            val how = if (values.size == 1) "${capital(names.theirs(values.keys.single()))} own resting heart rate"
            else "The average of ${allOf(values.size)} devices' own resting figures${weightedTo(weights, names)}"
            Triple(value, sourcesOf(values, stated, devices, choice, chosen, weights), how)
        } else {
            val value = lowest(nightHeart(night))!!
            val buckets = overnight.mapValues { (_, l) -> l.map { it.start.floorDiv(HEART_MS) }.distinct().size.toDouble() }
            val how = if (overnight.size == 1) "The lowest ${names.theirs(overnight.keys.single())} heart rate stayed overnight"
            else "The lowest it stayed overnight, from ${allOf(overnight.size)} devices merged every 5 minutes"
            Triple(value, sourcesOf(own, overnight, devices, choice, chosen, buckets), how)
        }
        val contributed = if (stated.isNotEmpty()) stated.size else overnight.size
        if (contributed < 2 && choice != Choice.FALLBACK) return value to null
        return value to CombinedValue(Metric.RESTING_HEART_RATE, value, sources, if (choice == Choice.FALLBACK) fallback(how, chosen, names) else how, chosen)
    }

    private fun weightedTo(weights: Map<String, Double>, names: Names, context: String = " at night"): String {
        val best = weights.maxByOrNull { it.value } ?: return ""
        val total = weights.values.sum().takeIf { it > 0 } ?: return ""
        val shares = weights.values.map { it / total }
        return if (shares.max() - shares.min() < 0.05) "" else ", weighted to ${names.the(best.key)}$context"
    }

    // ---- HRV, blood oxygen, breathing, skin temperature, stress ----

    /**
     * Each device's own value for [metric] on a day: [samples] are the readings to use (the night's
     * where there are any, else the day's), [span] the night they come from, if any.
     */
    fun readings(metric: Metric, samples: List<StoredSample>, devices: Map<String, DeviceRef>, span: LongRange?): List<DeviceReading> {
        val byDevice = samples.filter { it.metric == metric }.groupBy { it.deviceId }
        val covered = byDevice.mapValues { (_, l) -> covered(l.map { it.start }) }
        val most = covered.filterKeys { byDevice.getValue(it).size > 1 }.values.maxOrNull()
        return byDevice.map { (id, list) ->
            val ref = devices[id]
            val coverage = when {
                // One reading is a device's summary of the night (or day).
                list.size == 1 -> 1.0
                span != null && span.last > span.first -> covered.getValue(id) / (span.last - span.first)
                most != null && most > 0 -> covered.getValue(id) / most
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            DeviceReading(metric, id, ref?.name ?: list.first().deviceName, ref?.kind, list.map { it.value }.average(), list.size, coverage)
        }.sortedBy { it.deviceId }
    }

    /** The time a device's readings cover: up to 90 minutes from each, or its usual gap after the last. */
    private fun covered(times: List<Long>): Double {
        val sorted = times.sorted()
        if (sorted.size < 2) return 0.0
        val gaps = sorted.zipWithNext { a, b -> b - a }
        val usual = gaps.sorted()[gaps.size / 2]
        return (gaps.sumOf { minOf(it, MAX_GAP_MS) } + minOf(usual, MAX_GAP_MS)).toDouble()
    }

    /** Each device's usual for [metric]: the median of its own values over the days before (oldest first), with at least [BASELINE_MIN]. */
    fun baselines(metric: Metric, before: List<DayStats>, ids: Collection<String>): Map<String, Double> = ids.mapNotNull { id ->
        val values = before.takeLast(BASELINE_DAYS).mapNotNull { d -> d.readings.firstOrNull { it.metric == metric && it.deviceId == id }?.value }
        Scoring.median(values)?.takeIf { values.size >= BASELINE_MIN }?.let { id to it }
    }.toMap()

    /** A combined reading: its value, and (for skin temperature) how far it is from the devices' usual. */
    class Weighted(val value: Double, val deviation: Double?, val combined: CombinedValue?)

    /**
     * Each device's own value, weighted by how much it measured and how well it suits the reading.
     * For HRV and skin temperature, each device's difference from its own usual is combined instead,
     * and shown on the scale of the device that counts most. One far from its usual while another
     * isn't counts for less.
     */
    fun weighted(metric: Metric, readings: List<DeviceReading>, baselines: Map<String, Double>, chosen: String?, devices: Map<String, DeviceRef> = emptyMap()): Weighted? {
        val list = readings.filter { it.metric == metric }
        if (list.isEmpty()) return null
        val refs = list.map { DeviceRef(it.deviceId, it.device, it.kind) }
        val names = Names(refs + listOfNotNull(chosen?.let { devices[it] }))
        val context = if (metric in NIGHT) " at night" else " in the day"
        val choice = choice(chosen, list.map { it.deviceId })
        fun source(r: DeviceReading, weight: Double, note: String?) = SourceValue(r.deviceId, r.device, r.kind, r.value, weight, r.readings, note)
        fun deviation(r: DeviceReading): Double? = baselines[r.deviceId]?.let { b -> if (metric == Metric.HRV) (if (b > 0) r.value / b - 1 else null) else r.value - b }

        if (choice == Choice.USED) {
            val mine = list.first { it.deviceId == chosen }
            val dev = if (metric == Metric.SKIN_TEMPERATURE) deviation(mine) else null
            if (list.size < 2) return Weighted(mine.value, dev, null)
            val sources = list.map { r -> source(r, if (r === mine) 1.0 else 0.0, if (r === mine) null else "not chosen") }.sortedByDescending { it.weight }
            return Weighted(mine.value, dev, CombinedValue(metric, mine.value, sources, "Your choice: ${names.theirs(chosen)} own", chosen))
        }
        if (list.size == 1) {
            val only = list.single()
            val combined = if (choice == Choice.FALLBACK) {
                CombinedValue(metric, only.value, listOf(source(only, 1.0, null)), fallback("${capital(names.theirs(only.deviceId))} own", chosen, names), chosen)
            } else {
                null
            }
            return Weighted(only.value, null, combined)
        }

        val notes = mutableMapOf<String, String>()
        val weights = list.associate { r ->
            if (r.coverage < 0.5) notes[r.deviceId] = if (metric in NIGHT) "only part of the night" else "only part of the day"
            r.deviceId to suits(metric, r.kind, r.device) * r.coverage.coerceAtLeast(0.1)
        }.toMutableMap()
        // Far from its own usual while another device isn't: more likely the device than the body.
        val far = list.filter { r -> deviation(r)?.let { abs(it) > FAR.getValue(metric) } == true }
        val near = list.filter { r -> deviation(r)?.let { abs(it) <= FAR.getValue(metric) } == true }
        if (far.isNotEmpty() && near.isNotEmpty()) far.forEach { r ->
            weights[r.deviceId] = weights.getValue(r.deviceId) * FAR_WEIGHT
            notes[r.deviceId] = "far from its usual"
        }
        val farNote = if (far.isNotEmpty() && near.isNotEmpty()) {
            "; ${far.joinToString(" and ") { names.the(it.deviceId) }} ${if (far.size == 1) "was" else "were"} far from ${if (far.size == 1) "its" else "their"} usual, so counted for less"
        } else {
            ""
        }

        val value: Double
        var dev: Double? = null
        val used: Map<String, Double>
        val how: String
        if (metric in RELATIVE) {
            val known = list.filter { deviation(it) != null }
            if (known.isEmpty()) {
                // No device's usual is known yet: the one that counts most, as it reads.
                val best = list.maxBy { weights.getValue(it.deviceId) }
                list.filter { it !== best }.forEach { notes[it.deviceId] = "still learning its usual" }
                used = mapOf(best.deviceId to 1.0)
                value = best.value
                how = "${capital(names.theirs(best.deviceId))} own, until each device's usual is known"
            } else {
                list.filter { it !in known }.forEach { notes[it.deviceId] = "still learning its usual" }
                used = known.associate { it.deviceId to weights.getValue(it.deviceId) }
                val total = used.values.sum()
                val combinedDev = known.sumOf { deviation(it)!! * weights.getValue(it.deviceId) } / total
                val best = known.maxBy { weights.getValue(it.deviceId) }
                val usual = baselines.getValue(best.deviceId)
                value = if (metric == Metric.HRV) usual * (1 + combinedDev) else usual + combinedDev
                dev = if (metric == Metric.SKIN_TEMPERATURE) combinedDev else null
                how = if (known.size == 1) {
                    "${capital(names.theirs(best.deviceId))} own, while ${if (list.size > 2) "the others' usual is" else "the other's usual is"} still being learned"
                } else {
                    "Each against its own usual, then averaged${weightedTo(used, names, context)}, on ${names.theirs(best.deviceId)} scale" + farNote
                }
            }
        } else {
            used = weights
            val total = used.values.sum()
            value = list.sumOf { it.value * weights.getValue(it.deviceId) } / total
            how = "The average of ${allOf(list.size)}${weightedTo(used, names, context)}" + farNote
        }
        val total = used.values.sum().takeIf { it > 0 } ?: 1.0
        val sources = list.map { r -> source(r, (used[r.deviceId] ?: 0.0) / total, notes[r.deviceId]) }.sortedByDescending { it.weight }
        return Weighted(value, dev, CombinedValue(metric, value, sources, if (choice == Choice.FALLBACK) fallback(how, chosen, names) else how, chosen))
    }

    /** [day] with [metric] set from [w]: the value, its combined entry, and what goes with it. */
    fun DayStats.withReading(metric: Metric, w: Weighted?): DayStats {
        val combined = (combined.filter { it.metric != metric } + listOfNotNull(w?.combined)).sortedBy { it.metric.ordinal }
        val v = w?.value
        return when (metric) {
            Metric.HRV -> copy(
                hrv = v, combined = combined,
                // The night's HRV follows the day's where devices were combined.
                sleep = sleep?.let { s -> if (s.avgHrv != null && w?.combined != null && v != null) s.copy(avgHrv = v) else s },
            )
            Metric.SPO2 -> copy(spo2 = v, combined = combined)
            Metric.RESPIRATORY_RATE -> copy(respiratoryRate = v, combined = combined)
            Metric.SKIN_TEMPERATURE -> copy(skinTemp = v, skinDeviation = if (w?.combined != null) w.deviation else null, combined = combined)
            Metric.TEMPERATURE_DEVIATION -> copy(tempDeviation = v, combined = combined)
            Metric.STRESS -> copy(stress = v, combined = combined)
            else -> this
        }
    }

    /** Whether [day] has a reading that more than one device measured, which needs each device's usual. */
    fun needsBaselines(day: DayStats): Boolean = WEIGHTED.any { m -> day.readings.count { it.metric == m } >= 2 }

    /** [day] with its readings combined against each device's usual over [before] (the days before it, oldest first). */
    fun finish(day: DayStats, before: List<DayStats>, choices: DeviceChoices, devices: Map<String, DeviceRef> = emptyMap()): DayStats {
        var out = day
        WEIGHTED.forEach { m ->
            val list = day.readings.filter { it.metric == m }
            if (list.size < 2) return@forEach
            val chosen = choices.choices.lastOrNull { it.metric == m }?.deviceId
            if (chosen != null && list.any { it.deviceId == chosen }) return@forEach
            out = out.withReading(m, weighted(m, list, baselines(m, before, list.map { it.deviceId }), chosen, devices))
        }
        return out
    }

    // ---- Sleep ----

    /** A night (or nap) as recorded, or as the devices that recorded it agree; [asleepMinutes] where it was worked out from more than one. */
    class Night(val session: SleepSession, val asleepMinutes: Int? = null, val combined: CombinedSleep? = null)

    private fun staged(s: SleepSession) = s.stages.any { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM || it.stage == SleepStage.LIGHT }

    private fun asleep(s: SleepSession): Int =
        if (s.stages.isEmpty()) ((s.end - s.start) / 60_000).toInt() else (s.stages.filter { it.stage != SleepStage.AWAKE }.sumOf { it.end - it.start } / 60_000).toInt()

    private fun overlap(a: SleepSession, b: SleepSession) = (minOf(a.end, b.end) - maxOf(a.start, b.start)).coerceAtLeast(0)

    /**
     * Nights from different devices that overlap by more than half are one night: bedtime and
     * waking averaged where they're within 45 minutes (otherwise the staged device's), the stages
     * from the device that tells them apart, and time asleep weighted between them. Nights entered
     * by hand stay as they are. [chosen] is the device the person picked for sleep.
     */
    fun nights(sessions: List<SleepSession>, devices: Map<String, DeviceRef>, chosen: String?): List<Night> {
        val ranked = sessions.sortedWith(compareByDescending<SleepSession> { staged(it) }.thenByDescending { it.end - it.start })
        val groups = mutableListOf<MutableList<SleepSession>>()
        val alone = mutableListOf<SleepSession>()
        ranked.forEach { s ->
            if (s.manual || s.deviceId == null) {
                alone += s
                return@forEach
            }
            val group = groups.firstOrNull { g ->
                val first = g.first()
                g.none { it.deviceId == s.deviceId } && overlap(first, s) > 0.5 * minOf(first.end - first.start, s.end - s.start)
            }
            if (group != null) group += s else groups += mutableListOf(s)
        }
        return alone.map { Night(it) } + groups.map { night(it, devices, chosen) }
    }

    private fun night(group: List<SleepSession>, devices: Map<String, DeviceRef>, chosen: String?): Night {
        val refs = group.map { s -> devices[s.deviceId] ?: DeviceRef(s.deviceId!!, s.source ?: "A device", null) }
        val names = Names(refs + listOfNotNull(chosen?.let { devices[it] }))
        val sources = group.map { SleepSource(it.deviceId!!, refsName(it, devices), it.start, it.end, asleep(it), staged(it)) }
        val choice = choice(chosen, group.mapNotNull { it.deviceId })
        if (choice == Choice.USED) {
            val mine = group.first { it.deviceId == chosen }
            if (group.size == 1) return Night(mine)
            return Night(mine, null, CombinedSleep(sources, "Your choice: ${names.theirs(chosen)} night", mine.deviceId.takeIf { staged(mine) }))
        }
        val primary = group.first()
        if (group.size == 1) {
            if (choice != Choice.FALLBACK) return Night(primary)
            val how = fallback("${capital(names.theirs(primary.deviceId))} night", chosen, names, "didn't record it")
            return Night(primary, null, CombinedSleep(sources, how, primary.deviceId.takeIf { staged(primary) }))
        }
        fun near(times: List<Long>, own: Long): Pair<Long, Boolean> {
            val close = times.filter { abs(it - own) <= CLOSE_MS }
            return (close.sum() / close.size) to (close.size > 1)
        }
        val (start, startAveraged) = near(group.map { it.start }, primary.start)
        // A staged device whose night stops mid-sleep (a ring's battery running out, or taken off to charge)
        // didn't see the waking: where the others carry on asleep for more than 45 minutes, their waking is used,
        // and that last stretch counts as asleep without stages.
        val lastStage = primary.stages.lastOrNull()?.stage
        val carriedOn = group.drop(1).filter { it.end - primary.end > CLOSE_MS }
        val cutShort = staged(primary) && lastStage != null && lastStage != SleepStage.AWAKE && carriedOn.isNotEmpty()
        val (end, endAveraged) = if (cutShort) carriedOn.map { it.end }.sorted()[carriedOn.size / 2] to false else near(group.map { it.end }, primary.end)
        val tail = if (cutShort) listOf(StageSpan(SleepStage.ASLEEP, primary.end, end)) else emptyList()
        val stages = (primary.stages + tail)
            .map { it.copy(start = it.start.coerceIn(start, end), end = it.end.coerceIn(start, end)) }
            .filter { it.end > it.start }
        // The others' time asleep in that last stretch, added to the device that stopped.
        val tailAsleep = if (!cutShort) 0 else carriedOn.first().let { o ->
            if (o.stages.isEmpty()) ((end - primary.end) / 60_000).toInt()
            else (o.stages.filter { it.stage != SleepStage.AWAKE }.sumOf { (minOf(it.end, end) - maxOf(it.start, primary.end)).coerceAtLeast(0) } / 60_000).toInt()
        }
        val weights = group.associate { s ->
            val ref = devices[s.deviceId]
            s.deviceId!! to SLEEP_SUITS.of(placement(ref?.kind, ref?.name ?: s.source.orEmpty())) * (if (s.stages.isEmpty()) NO_STAGES_WEIGHT else 1.0)
        }
        val total = weights.values.sum()
        val asleep = (group.sumOf { (asleep(it) + if (it === primary) tailAsleep else 0) * weights.getValue(it.deviceId!!) } / total).let { kotlin.math.round(it).toInt() }
            .coerceAtMost(((end - start) / 60_000).toInt())
        val who = names.the(primary.deviceId)
        val timing = when {
            cutShort -> (if (startAveraged) "Bedtime averaged" else "Bedtime from $who") +
                ", waking from ${names.the(carriedOn.first().deviceId)}, as $who stopped recording while you were still asleep"
            startAveraged && endAveraged -> "Bedtime and waking averaged"
            startAveraged -> "Bedtime averaged, waking from $who"
            endAveraged -> "Waking averaged, bedtime from $who"
            else -> "Bedtime and waking from $who, as the devices were more than 45 minutes apart"
        }
        val how = listOfNotNull(
            timing,
            if (staged(primary)) "stages from $who" else null,
            "time asleep from ${allOf(group.size)}" + weightedTo(weights, names, ""),
        ).joinToString("; ")
        val session = primary.copy(start = start, end = end, stages = stages)
        return Night(
            session, asleep,
            CombinedSleep(sources, if (choice == Choice.FALLBACK) fallback(how, chosen, names, "didn't record it") else how, primary.deviceId.takeIf { staged(primary) }),
        )
    }

    private fun refsName(s: SleepSession, devices: Map<String, DeviceRef>) = devices[s.deviceId]?.name ?: s.source ?: "A device"
}
