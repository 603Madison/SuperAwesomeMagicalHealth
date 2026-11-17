package com.health.app.devices.strap

import com.health.api.Metric
import com.health.api.Sample
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The Bluetooth SIG's Heart Rate profile, as every standard chest strap speaks it (Polar, Garmin,
 * Wahoo, Coospo, Decathlon and the rest): the bytes of a measurement in, readings out. Pure, so
 * it's tested without a strap.
 */
object StrapProtocol {
    private fun uuid16(short: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)

    /** Whether the strap can tell it's touching skin, and whether it is. */
    enum class Contact { UNSUPPORTED, DETECTED, NOT_DETECTED }

    /** One Heart Rate Measurement (0x2A37): the heart rate, skin contact, and the beat-to-beat intervals in ms. */
    data class Measurement(val bpm: Int, val contact: Contact, val rrMs: List<Double> = emptyList()) {
        /** False when the strap says it isn't touching skin: what it sends then isn't a heartbeat. */
        val worn: Boolean get() = contact != Contact.NOT_DETECTED
    }

    /** A Heart Rate Measurement, or null if it's too short to read. Anything after a cut-off field is left out. */
    fun measurement(b: ByteArray): Measurement? {
        if (b.size < 2) return null
        val flags = b[0].toInt() and 0xFF
        var at: Int
        val bpm = if (flags and 0x01 != 0) {
            if (b.size < 3) return null
            at = 3
            u16(b, 1)
        } else {
            at = 2
            b[1].toInt() and 0xFF
        }
        // Bit 2: the strap can tell; bit 1: it's touching skin.
        val contact = when ((flags shr 1) and 0x03) {
            0x03 -> Contact.DETECTED
            0x02 -> Contact.NOT_DETECTED
            else -> Contact.UNSUPPORTED
        }
        // Energy expended (kJ so far), not used.
        if (flags and 0x08 != 0) at += 2
        val rr = ArrayList<Double>()
        if (flags and 0x10 != 0) {
            while (at + 1 < b.size) {
                rr += u16(b, at) * 1000.0 / 1024.0
                at += 2
            }
        }
        return Measurement(bpm, contact, rr)
    }

    /** A Battery Level (0x2A19), 0–100, or null. */
    fun battery(b: ByteArray?): Int? = b?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }

    /** Straps that don't say they offer the heart rate service until asked, by the names they go by. */
    fun looksLikeStrap(name: String?): Boolean =
        name != null && Regex("""(?i)\b(polar h\d+|hrm|tickr|coospo|h808|h6m|heart ?rate|hr ?(belt|strap|sensor|monitor))""").containsMatchIn(name)

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

/** How a strap's beats are turned into readings: each minute's heart rate, each five minutes' HRV and rhythm. */
object StrapMaths {
    const val MINUTE_MS = 60_000L
    const val WINDOW_MS = 5 * MINUTE_MS

    /** Beat-to-beat intervals outside this (ms; 30–200 bpm) are missed or extra beats, not a heartbeat. */
    const val RR_MIN = 300.0
    const val RR_MAX = 2000.0

    /** An interval further than this from the one before it is an artefact, for HRV. */
    const val RR_JUMP = 0.20

    /** When this many in a row jump from the last one kept, that one was the odd one out: the last of them starts afresh. */
    const val RESTART_AFTER = 3

    /** HRV only for a window whose clean intervals cover this much of it. */
    const val HRV_COVERAGE_MS = 2 * MINUTE_MS

    /** …and with at least this many successive differences. */
    const val HRV_MIN_DIFFS = 10

    /** Heart rates the server takes. */
    const val BPM_MIN = 25
    const val BPM_MAX = 240

    /** Rhythm: stretches of this many beats (Dash et al. 2009), less the [TRIM] longest and shortest. */
    const val SEGMENT = 128
    const val TRIM = 8

    /** A window whose readings were more than this share without skin contact gets no rhythm reading. */
    const val NO_CONTACT_SHARE = 0.20

    fun inRange(rr: Double): Boolean = rr in RR_MIN..RR_MAX

    /** Root mean square of successive differences, or null with fewer than two values. */
    fun rmssd(rr: List<Double>): Double? {
        if (rr.size < 2) return null
        var sum = 0.0
        for (i in 1 until rr.size) {
            val d = rr[i] - rr[i - 1]
            sum += d * d
        }
        return sqrt(sum / (rr.size - 1))
    }

    /**
     * For HRV: [rr] without the intervals outside 300–2000 ms or more than 20% from the last one kept
     * (the third of those in a row starts afresh instead). Successive differences only
     * count between neighbours, so use [StrapAggregator] for the HRV itself.
     */
    fun clean(rr: List<Double>): List<Double> {
        var chain = RrChain()
        val kept = ArrayList<Double>()
        for (v in rr) {
            val (next, step) = chain.take(v)
            chain = next
            if (step != null) kept += v
        }
        return kept
    }

    /** How evenly [rr] spreads over a 16-bin histogram of its range: Shannon entropy over ln 16, 0 when all alike, 1 when evenly spread. */
    fun entropy(rr: List<Double>): Double {
        if (rr.isEmpty()) return 0.0
        val lo = rr.min()
        val hi = rr.max()
        if (hi <= lo) return 0.0
        val bins = IntArray(BINS)
        for (v in rr) bins[((v - lo) / (hi - lo) * BINS).toInt().coerceIn(0, BINS - 1)]++
        var h = 0.0
        for (count in bins) {
            if (count == 0) continue
            val p = count.toDouble() / rr.size
            h -= p * ln(p)
        }
        return h / ln(BINS.toDouble())
    }

    /** Turning points (a value above both neighbours, or below both) over the n − 2 that could be one. */
    fun turningPointRatio(rr: List<Double>): Double {
        if (rr.size < 3) return 0.0
        var turns = 0
        for (i in 1 until rr.size - 1) {
            val up = rr[i] > rr[i - 1] && rr[i] > rr[i + 1]
            val down = rr[i] < rr[i - 1] && rr[i] < rr[i + 1]
            if (up || down) turns++
        }
        return turns.toDouble() / (rr.size - 2)
    }

    /** What [irregular] looks at in a stretch of beats. */
    data class Rhythm(val nRmssd: Double, val entropy: Double, val turningPoints: Double) {
        /** Dash et al. 2009: varying a lot, spread evenly, and as random in order as a random series. */
        val irregular: Boolean get() = nRmssd > 0.1 && entropy > 0.7 && turningPoints in 0.54..0.77
    }

    /** A [SEGMENT]-beat stretch, less its [TRIM] longest and shortest intervals (in their order), measured. */
    fun rhythm(segment: List<Double>): Rhythm {
        val drop = segment.indices.sortedBy { segment[it] }.let { (it.take(TRIM) + it.takeLast(TRIM)).toSet() }
        val trimmed = segment.filterIndexed { i, _ -> i !in drop }
        val mean = trimmed.average()
        val n = (rmssd(trimmed) ?: 0.0) / mean
        return Rhythm(if (n.isFinite()) n else 0.0, entropy(trimmed), turningPointRatio(trimmed))
    }

    /** Whether a [SEGMENT]-beat stretch looks irregular. */
    fun irregular(segment: List<Double>): Boolean = rhythm(segment).irregular

    fun minuteOf(at: Long): Long = Math.floorDiv(at, MINUTE_MS) * MINUTE_MS

    /** Five-minute windows on the clock (every time zone's offset is a whole quarter hour, so they line up locally too). */
    fun windowOf(at: Long): Long = Math.floorDiv(at, WINDOW_MS) * WINDOW_MS

    fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0

    private const val BINS = 16
}

/**
 * Where the beat-to-beat intervals got to for HRV: the last one kept, whether the next one follows
 * it directly (nothing dropped or missed between), and how many in a row have been dropped for jumping.
 */
@Serializable
data class RrChain(val last: Double? = null, val follows: Boolean = false, val jumps: Int = 0) {
    /** Takes the next interval [rr]: the chain after it, and a [Step] if it's kept (null if it's dropped). */
    fun take(rr: Double): Pair<RrChain, Step?> {
        if (!StrapMaths.inRange(rr)) return copy(follows = false) to null
        val last = last ?: return RrChain(rr, follows = true) to Step(null)
        if (abs(rr - last) <= last * StrapMaths.RR_JUMP) return RrChain(rr, follows = true) to Step(if (follows) rr - last else null)
        // Several in a row far from the last one kept: that one was the odd one out, so start again from here.
        if (jumps + 1 >= StrapMaths.RESTART_AFTER) return RrChain(rr, follows = true) to Step(null)
        return copy(follows = false, jumps = jumps + 1) to null
    }

    /** A kept interval, and its difference from the one kept before it, when that one came directly before. */
    data class Step(val difference: Double?)

    /** After a gap (the strap went away, or lost touch): the next interval starts afresh. */
    fun broken(): RrChain = RrChain()
}

/** A minute's heart rates so far. */
@Serializable
data class MinuteSum(val start: Long, val sum: Double = 0.0, val count: Int = 0)

/** A five-minute window so far: its HRV, and its rhythm in [StrapMaths.SEGMENT]-beat stretches. */
@Serializable
data class WindowSum(
    val start: Long,
    /** HRV: the clean intervals' total (ms), the sum of their squared successive differences, and how many. */
    val cleanMs: Double = 0.0,
    val squares: Double = 0.0,
    val diffs: Int = 0,
    /** Readings, and those the strap said weren't touching skin. */
    val readings: Int = 0,
    val noContact: Int = 0,
    /** Rhythm: the stretch being gathered (only the 300–2000 ms filter), then how many stretches and how many irregular. */
    val segment: List<Double> = emptyList(),
    val segments: Int = 0,
    val irregular: Int = 0,
)

/** Everything an unfinished recording holds, kept on the phone so it carries on after the app is closed. */
@Serializable
data class StrapAggregate(val minute: MinuteSum? = null, val window: WindowSum? = null, val chain: RrChain = RrChain())

/**
 * Turns a strap's measurements into readings for the server, as they come: each minute's average
 * heart rate (at the minute's start), and for each five minutes on the clock its HRV (RMSSD of the
 * clean intervals, when they cover two minutes of it) and how irregular its rhythm looked. A
 * reading comes out once its minute or window is over: [add] with a later measurement, [close]
 * once the time has passed, or [finish] when recording stops.
 */
class StrapAggregator(var state: StrapAggregate = StrapAggregate()) {
    /** Takes [m], measured at [at]; returns the readings that finished. */
    fun add(m: StrapProtocol.Measurement, at: Long): List<Sample> {
        val out = ArrayList<Sample>()
        val minuteStart = StrapMaths.minuteOf(at)
        val windowStart = StrapMaths.windowOf(at)
        var minute = state.minute
        if (minute != null && minute.start != minuteStart) {
            minuteSample(minute)?.let(out::add)
            minute = null
        }
        var window = state.window
        if (window != null && window.start != windowStart) {
            out += windowSamples(window)
            window = null
        }
        var min = minute ?: MinuteSum(minuteStart)
        var w = (window ?: WindowSum(windowStart)).let { it.copy(readings = it.readings + 1, noContact = it.noContact + if (m.worn) 0 else 1) }
        var chain = state.chain
        if (!m.worn) {
            // Not touching skin: neither the heart rate nor the intervals are a heartbeat.
            chain = chain.broken()
            w = w.copy(segment = emptyList())
        } else {
            if (m.bpm in StrapMaths.BPM_MIN..StrapMaths.BPM_MAX) min = min.copy(sum = min.sum + m.bpm, count = min.count + 1)
            for (rr in m.rrMs) {
                val (next, step) = chain.take(rr)
                chain = next
                if (step != null) {
                    w = w.copy(cleanMs = w.cleanMs + rr)
                    val d = step.difference
                    if (d != null) w = w.copy(squares = w.squares + d * d, diffs = w.diffs + 1)
                }
                // The rhythm sees every beat in range: the jumps HRV leaves out are what it looks for.
                if (StrapMaths.inRange(rr)) w = rhythmBeat(w, rr)
            }
        }
        state = StrapAggregate(min, w, chain)
        return out
    }

    /** Readings whose minute or window is over by [now] (when the strap has gone quiet). */
    fun close(now: Long): List<Sample> {
        val out = ArrayList<Sample>()
        var minute = state.minute
        if (minute != null && now >= minute.start + StrapMaths.MINUTE_MS) {
            minuteSample(minute)?.let(out::add)
            minute = null
        }
        var window = state.window
        if (window != null && now >= window.start + StrapMaths.WINDOW_MS) {
            out += windowSamples(window)
            window = null
        }
        state = state.copy(minute = minute, window = window)
        return out
    }

    /** The connection dropped: the beats either side of the gap aren't neighbours. */
    fun gap() {
        state = state.copy(chain = state.chain.broken(), window = state.window?.copy(segment = emptyList()))
    }

    /** Recording stopped: what the last minute and window have, then nothing held. */
    fun finish(): List<Sample> {
        val out = ArrayList<Sample>()
        state.minute?.let { minuteSample(it)?.let(out::add) }
        state.window?.let { out += windowSamples(it) }
        state = StrapAggregate()
        return out
    }

    private fun rhythmBeat(window: WindowSum, rr: Double): WindowSum {
        val segment = window.segment + rr
        if (segment.size < StrapMaths.SEGMENT) return window.copy(segment = segment)
        return window.copy(
            segment = emptyList(),
            segments = window.segments + 1,
            irregular = window.irregular + if (StrapMaths.irregular(segment)) 1 else 0,
        )
    }

    private fun minuteSample(m: MinuteSum): Sample? =
        if (m.count == 0) null else Sample(Metric.HEART_RATE, m.start, value = StrapMaths.round1(m.sum / m.count))

    private fun windowSamples(w: WindowSum): List<Sample> {
        val end = w.start + StrapMaths.WINDOW_MS
        val hrv = if (w.cleanMs >= StrapMaths.HRV_COVERAGE_MS && w.diffs >= StrapMaths.HRV_MIN_DIFFS) {
            StrapMaths.round1(sqrt(w.squares / w.diffs)).takeIf { it in 1.0..300.0 }?.let { Sample(Metric.HRV, w.start, end, it) }
        } else {
            null
        }
        val wornEnough = w.readings > 0 && w.noContact.toDouble() / w.readings <= StrapMaths.NO_CONTACT_SHARE
        val rhythm = if (w.segments > 0 && wornEnough) {
            Sample(Metric.IRREGULAR_RHYTHM, w.start, end, StrapMaths.round1(100.0 * w.irregular / w.segments))
        } else {
            null
        }
        return listOfNotNull(hrv, rhythm)
    }
}
