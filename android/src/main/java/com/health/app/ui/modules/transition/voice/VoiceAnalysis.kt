package com.health.app.ui.modules.transition.voice

import com.health.app.ui.modules.transition.PitchDetector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * How the voice trainer listens, in plain Kotlin so it can be tested with made-up sounds. The
 * measures follow the contract's header (shared/…/Voice.kt) exactly, so the phone and the web agree:
 *
 * - Sound comes in at 16 kHz mono, in 40 ms frames every 20 ms.
 * - Pitch: YIN (PitchDetector) on each frame, 60–500 Hz; a frame is voiced when it's above
 *   −50 dBFS and passes YIN's 0.15 threshold.
 * - Resonance: on voiced frames, the 25 ms centred in the 40 ms frame, resampled to 11 025 Hz,
 *   pre-emphasised by 0.97, Hamming-windowed, LPC of order 12 (autocorrelation and
 *   Levinson–Durbin), and the polynomial's roots (Aberth's method); a root counts as a formant above
 *   200 Hz and 1.15 × f0, with a bandwidth under 500 Hz, and the first three are F1–F3. Then the
 *   contract's spacing, tract length and 0–100 score.
 * - Weight: H1 − H2 from the same frame's Hann-windowed spectrum (zero-padded to 4096 points), the
 *   peak within ±2% of f0 and of 2·f0 (between bins by a parabola); then the contract's 0–100 score.
 * - Intonation: the standard deviation of voiced pitch in semitones from its median.
 * - Loudness: RMS in dBFS (samples in −1…1, so a full-scale sine is −3 dBFS).
 * - The screen shows each value as the median of the last 300 ms ([Steady]).
 *
 * These details match the web app's trainer, so both show the same numbers for the same voice.
 * Nothing here keeps sound: frames are numbers in, numbers out.
 */

/** The contract's scales, and the units the trainer shows. */
object VoiceScale {
    const val RATE = 16_000
    /** 40 ms frames, every 20 ms. */
    const val FRAME = 640
    const val HOP = 320
    const val HOP_MS = 20
    const val MIN_HZ = 60.0
    const val MAX_HZ = 500.0
    const val YIN_THRESHOLD = 0.15

    /** Quieter than this is silence: −50 dBFS RMS (as the web app). */
    const val SILENCE_DB = -50.0
    val SILENCE_RMS: Double = 10.0.pow(SILENCE_DB / 20)

    /** Pre-emphasis before LPC. */
    const val PRE_EMPHASIS = 0.97

    const val LPC_RATE = 11_025
    const val LPC_ORDER = 12
    /** 25 ms at 11 025 Hz. */
    const val LPC_FRAME = 276

    /** The middle of the dark–bright scale's ends: a 17.5 cm tract is 0, 13.5 cm is 100. */
    private const val LONG_TRACT = 17.5
    private const val SHORT_TRACT = 13.5

    /** Formant spacing ΔF by least squares through the origin: Σ Fn·(n − ½) / Σ (n − ½)². */
    fun spacing(f1: Double, f2: Double, f3: Double): Double = (f1 * 0.5 + f2 * 1.5 + f3 * 2.5) / (0.25 + 2.25 + 6.25)

    /** Apparent vocal-tract length in cm, L = c / (2·ΔF) with c = 35 000 cm/s. */
    fun tractLength(spacing: Double): Double = 35_000.0 / (2 * spacing)

    /** Resonance 0 (darker) … 100 (brighter). */
    fun resonance(f1: Double, f2: Double, f3: Double): Double =
        ((LONG_TRACT - tractLength(spacing(f1, f2, f3))) / (LONG_TRACT - SHORT_TRACT) * 100).coerceIn(0.0, 100.0)

    /** Vocal weight 0 (lighter) … 100 (heavier), from H1 − H2 in dB. */
    fun weight(h1MinusH2: Double): Double = ((12 - h1MinusH2) / 18 * 100).coerceIn(0.0, 100.0)

    /** RMS in dBFS; silence reads as −120. */
    fun dbfs(rms: Double): Double = if (rms <= 1e-6) -120.0 else 20 * log10(rms)

    /** How many semitones [hz] is above [from] (below: negative). */
    fun semitones(hz: Double, from: Double): Double = 12 * ln(hz / from) / ln(2.0)

    /** [hz] moved by [semitones]. */
    fun shift(hz: Double, semitones: Double): Double = hz * 2.0.pow(semitones / 12)

    /** The middle of a range, by ear: halfway in semitones. */
    fun middle(low: Double, high: Double): Double = sqrt(low * high)

    /** Intonation: the spread (standard deviation) of [pitches] in semitones from their median; null under two. */
    fun intonation(pitches: List<Double>): Double? {
        if (pitches.size < 2) return null
        val centre = median(pitches)
        val st = pitches.map { semitones(it, centre) }
        val mean = st.average()
        return sqrt(st.sumOf { (it - mean) * (it - mean) } / st.size)
    }

    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** The nearest note's name, "A3" for 220 Hz. */
    fun note(hz: Double): String {
        val midi = (69 + semitones(hz, 440.0)).roundToInt()
        return NAMES[((midi % 12) + 12) % 12] + (floor(midi / 12.0).toInt() - 1)
    }

    /** "182 Hz (F#3)". */
    fun hzAndNote(hz: Double): String = "${hz.roundToInt()} Hz (${note(hz)})"
}

/** The median of [values] (the mean of the middle two for an even count). */
fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** The [p]th percentile (0–100) of [values], interpolated. */
fun percentile(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val rank = (p / 100 * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
    val lo = floor(rank).toInt()
    val hi = ceil(rank).toInt()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
}

/** One 40 ms frame, measured. Only voiced frames have a pitch, and only they get a resonance and weight. */
data class VoiceFrame(
    /** Milliseconds from the start of listening, at the frame's end. */
    val at: Long,
    val db: Double,
    val hz: Double? = null,
    val resonance: Double? = null,
    val weight: Double? = null,
    val formants: Formants.Found? = null,
    val h1MinusH2: Double? = null,
) {
    val voiced: Boolean get() = hz != null
}

/**
 * Turns 16 kHz sound, pushed in any sized pieces, into a [VoiceFrame] every 20 ms. Not thread-safe:
 * one listener feeds it from one thread.
 */
class VoiceAnalyzer {
    private val pitch = PitchDetector(VoiceScale.RATE, VoiceScale.MIN_HZ, VoiceScale.MAX_HZ, VoiceScale.YIN_THRESHOLD, VoiceScale.SILENCE_RMS)
    private val toLpc = Resampler(VoiceScale.RATE, VoiceScale.LPC_RATE)
    private val window = FloatArray(VoiceScale.FRAME)
    private var filled = 0
    private var sinceHop = 0
    private var hops = 0L

    /** Takes [count] samples (−1…1) and hands each new frame to [onFrame]. */
    fun push(samples: FloatArray, count: Int = samples.size, onFrame: (VoiceFrame) -> Unit) {
        for (i in 0 until count) {
            if (filled < window.size) {
                window[filled++] = samples[i]
            } else {
                System.arraycopy(window, 1, window, 0, window.size - 1)
                window[window.size - 1] = samples[i]
            }
            sinceHop++
            if (filled == window.size && sinceHop >= VoiceScale.HOP) {
                sinceHop = 0
                hops++
                onFrame(frame(window, hops * VoiceScale.HOP_MS + 20))
            }
        }
    }

    /** Measures one 40 ms frame ([VoiceScale.FRAME] samples at 16 kHz). */
    fun frame(samples: FloatArray, at: Long = 0): VoiceFrame {
        val rms = PitchDetector.rms(samples, VoiceScale.FRAME)
        val db = VoiceScale.dbfs(rms)
        val hz = pitch.detect(samples, VoiceScale.FRAME) ?: return VoiceFrame(at, db)
        val h1h2 = Harmonics.h1MinusH2(samples, VoiceScale.FRAME, VoiceScale.RATE, hz)
        val resampled = toLpc.block(samples, 0, VoiceScale.FRAME)
        val start = ((resampled.size - VoiceScale.LPC_FRAME) / 2).coerceAtLeast(0)
        val middle = resampled.copyOfRange(start, min(resampled.size, start + VoiceScale.LPC_FRAME))
        val formants = Formants.of(middle, VoiceScale.LPC_RATE, f0 = hz)
        return VoiceFrame(
            at = at, db = db, hz = hz,
            resonance = formants?.let { VoiceScale.resonance(it.f1, it.f2, it.f3) },
            weight = h1h2?.let(VoiceScale::weight),
            formants = formants, h1MinusH2 = h1h2,
        )
    }

    companion object {
        /** Every frame of a whole recording (16 kHz), for the baseline and for tests. */
        fun analyse(samples: FloatArray): List<VoiceFrame> {
            val frames = ArrayList<VoiceFrame>()
            VoiceAnalyzer().push(samples, samples.size) { frames += it }
            return frames
        }
    }
}

/**
 * Steadies a live value for the screen: the median of the voiced readings in the last [spanMs]
 * (about 300 ms), so odd frames don't jump about; null once there's been no voice for [holdMs].
 */
class Steady(private val spanMs: Long = 300, private val holdMs: Long = 200) {
    private val values = ArrayDeque<Pair<Long, Double>>()
    private var lastVoiced = Long.MIN_VALUE / 2

    fun add(at: Long, value: Double?): Double? {
        if (value != null) {
            values.addLast(at to value)
            lastVoiced = at
        }
        while (values.isNotEmpty() && values.first().first < at - spanMs) values.removeFirst()
        if (values.isEmpty() || at - lastVoiced > holdMs) return null
        return median(values.map { it.second })
    }

    fun clear() {
        values.clear()
        lastVoiced = Long.MIN_VALUE / 2
    }
}

/** Loudness: when the person has been pushing for a while, and words for the meter. */
object Loudness {
    /** Ordinary speech at arm's length on most phones, when there's no baseline to go by. */
    const val DEFAULT_SPEAKING_DB = -40.0

    /** How much louder than their usual speech counts as pushing: 9 dB is about three times the sound energy. */
    const val ABOVE_USUAL_DB = 9.0

    /** The level that counts as pushing for someone whose usual speech is [speakingDb]. */
    fun limit(speakingDb: Double?): Double = ((speakingDb ?: DEFAULT_SPEAKING_DB) + ABOVE_USUAL_DB).coerceIn(-32.0, -16.0)

    /**
     * Whether the recent voiced frames' levels ([recentDb], the last five seconds or so) show
     * sustained pushing: at least three seconds of voice, mostly over the limit.
     */
    fun pushing(recentDb: List<Double>, speakingDb: Double?): Boolean {
        if (recentDb.size < 3_000 / VoiceScale.HOP_MS) return false
        return median(recentDb) > limit(speakingDb)
    }

    fun words(db: Double?, speakingDb: Double?): String = when {
        db == null -> "Quiet"
        db > limit(speakingDb) -> "Loud"
        db > limit(speakingDb) - 14 -> "Comfortable"
        else -> "Soft"
    }
}

/** A radix-2 FFT, in place. */
internal object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "The size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/** The first two harmonics' levels, for vocal weight. */
object Harmonics {
    const val FFT_SIZE = 4_096

    /** H1 − H2 in dB for a frame with fundamental [f0], or null when either can't be found. */
    fun h1MinusH2(samples: FloatArray, count: Int, rate: Int, f0: Double): Double? {
        val levels = levels(samples, count, rate, f0, 2) ?: return null
        return levels[0] - levels[1]
    }

    /** The levels in dB of the first [harmonics] harmonics of [f0]. */
    fun levels(samples: FloatArray, count: Int, rate: Int, f0: Double, harmonics: Int): DoubleArray? {
        val n = FFT_SIZE
        if (count > n || 2 * f0 * harmonics > rate) return null
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until count) re[i] = samples[i] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1)))
        Fft.transform(re, im)
        val db = DoubleArray(n / 2 + 1) { k -> 20 * log10(hypot(re[k], im[k]) + 1e-12) }
        val binHz = rate.toDouble() / n
        return DoubleArray(harmonics) { h ->
            // Within ±2% of the harmonic: the bins either side of that window's edges, so there's always one to read.
            val centre = (h + 1) * f0
            val from = floor(centre * (1 - SEARCH) / binHz).toInt().coerceAtLeast(1)
            val to = ceil(centre * (1 + SEARCH) / binHz).toInt().coerceAtMost(db.size - 2)
            peak(db, from, to) ?: return null
        }
    }

    /** How far either side of each harmonic to look for its peak. */
    const val SEARCH = 0.02

    /** The highest point between bins [from] and [to], between bins by a parabola through its neighbours. */
    private fun peak(db: DoubleArray, from: Int, to: Int): Double? {
        if (to <= from) return null
        var best = from
        for (k in from..to) if (db[k] > db[best]) best = k
        val a = db[best - 1]
        val b = db[best]
        val c = db[best + 1]
        val denominator = a - 2 * b + c
        // Only a true peak is refined; on a slope, the bin's own level is the answer.
        if (denominator >= 0 || a > b || c > b) return b
        val shift = 0.5 * (a - c) / denominator
        return b - 0.25 * (a - c) * shift
    }
}

/**
 * Band-limited resampling with a Blackman-windowed sinc, for 16 kHz → 11 025 Hz (LPC) and for
 * microphones that only offer 44.1 or 48 kHz. [halfWidth] is the kernel's zero crossings each side.
 */
class Resampler(val from: Int, val to: Int, halfWidth: Int = 16) {
    /** Input samples per output sample. */
    val step: Double = from.toDouble() / to

    /** The cut-off as a share of the input's Nyquist: a little under the lower of the two. */
    private val cutoff = min(1.0, to.toDouble() / from) * 0.92

    /** How far the kernel reaches, in input samples. */
    val reach: Int = ceil(halfWidth / cutoff).toInt()

    private fun kernel(d: Double): Double {
        if (abs(d) >= reach) return 0.0
        val x = cutoff * d
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
        val window = 0.42 + 0.5 * cos(PI * d / reach) + 0.08 * cos(2 * PI * d / reach)
        return cutoff * sinc * window
    }

    /** The output sample at input position [t] (input index, fractional) in [input]; silence outside it. */
    fun at(input: FloatArray, size: Int, t: Double, offset: Int = 0): Double {
        val first = max(0, ceil(t - reach).toInt())
        val last = min(size - 1, floor(t + reach).toInt())
        var sum = 0.0
        for (k in first..last) sum += input[offset + k] * kernel(t - k)
        return sum
    }

    /** One block, resampled, with silence assumed beyond its ends. */
    fun block(input: FloatArray, offset: Int = 0, count: Int = input.size - offset): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        val n = floor((count - 1) / step).toInt() + 1
        return DoubleArray(n) { i -> at(input, count, i * step, offset) }
    }
}

/** [Resampler] for a stream that comes in pieces: keeps enough of the last piece to carry on smoothly. */
class StreamResampler(from: Int, to: Int) {
    private val core = Resampler(from, to)
    private var pending = FloatArray(0)
    private var position = 0.0

    fun push(input: FloatArray, count: Int = input.size): FloatArray {
        val joined = FloatArray(pending.size + count)
        System.arraycopy(pending, 0, joined, 0, pending.size)
        System.arraycopy(input, 0, joined, pending.size, count)
        pending = joined
        val out = ArrayList<Float>(((count / core.step) + 2).toInt())
        while (position + core.reach < pending.size) {
            out += core.at(pending, pending.size, position).toFloat()
            position += core.step
        }
        val drop = (floor(position).toInt() - core.reach).coerceAtLeast(0)
        if (drop > 0) {
            pending = pending.copyOfRange(drop, pending.size)
            position -= drop
        }
        return out.toFloatArray()
    }
}

/** Formants by linear prediction. */
object Formants {
    /** F1–F3 in Hz. */
    data class Found(val f1: Double, val f2: Double, val f3: Double)

    /** A formant's bandwidth is under this; wider poles shape the spectrum's slope instead. */
    const val MAX_BANDWIDTH = 500.0

    /** A formant is above this, and above [ABOVE_F0] × f0 (so the voice's own pitch isn't taken for one). */
    const val MIN_HZ = 200.0
    const val ABOVE_F0 = 1.15

    /**
     * F1–F3 of a frame at [rate] (25 ms at 11 025 Hz in the trainer), for a voice at [f0] (when
     * known), or null when three can't be found.
     */
    fun of(frame: DoubleArray, rate: Int = VoiceScale.LPC_RATE, order: Int = VoiceScale.LPC_ORDER, f0: Double? = null): Found? {
        val a = lpc(frame, rate, order) ?: return null
        val floor = max(MIN_HZ, (f0 ?: 0.0) * ABOVE_F0)
        val found = Polynomial.roots(a)
            .filter { (_, im) -> im > 0 }
            .map { (re, im) -> atan2(im, re) / (2 * PI) * rate to -ln(hypot(re, im)) * rate / PI }
            .filter { (hz, bandwidth) -> hz > floor && bandwidth >= 0 && bandwidth < MAX_BANDWIDTH }
            .map { it.first }
            .sorted()
        if (found.size < 3) return null
        return Found(found[0], found[1], found[2])
    }

    /**
     * LPC coefficients a[0] = 1, a[1…order] of a frame: pre-emphasised by 0.97, Hamming-windowed,
     * by the autocorrelation method and Levinson–Durbin. Null for silence or an unstable fit.
     */
    fun lpc(frame: DoubleArray, rate: Int, order: Int): DoubleArray? {
        val n = frame.size
        if (n <= order + 1) return null
        val alpha = VoiceScale.PRE_EMPHASIS
        val x = DoubleArray(n) { i ->
            val emphasised = frame[i] - (if (i > 0) alpha * frame[i - 1] else 0.0)
            emphasised * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))
        }
        val r = DoubleArray(order + 1) { lag -> (0 until n - lag).sumOf { x[it] * x[it + lag] } }
        if (r[0] <= 1e-12) return null
        // A whisper of white noise (−40 dB) keeps the fit stable.
        r[0] *= 1.0001
        val a = DoubleArray(order + 1)
        a[0] = 1.0
        var error = r[0]
        val previous = DoubleArray(order + 1)
        for (i in 1..order) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val k = -acc / error
            a.copyInto(previous)
            for (j in 1 until i) a[j] = previous[j] + k * previous[i - j]
            a[i] = k
            error *= 1 - k * k
            if (error <= 0) return null
        }
        return a
    }
}

/** Roots of polynomials, by Aberth's method (all roots at once, converging cubically). */
internal object Polynomial {
    /** The roots of c[0]·zⁿ + c[1]·zⁿ⁻¹ + … + c[n], as (re, im). */
    fun roots(c: DoubleArray, iterations: Int = 500): List<Pair<Double, Double>> {
        val n = c.size - 1
        if (n < 1 || c[0] == 0.0) return emptyList()
        val p = DoubleArray(c.size) { c[it] / c[0] }
        val radius = abs(p[n]).pow(1.0 / n).coerceIn(0.5, 2.0)
        val zr = DoubleArray(n) { radius * cos(2 * PI * it / n + 0.4) }
        val zi = DoubleArray(n) { radius * sin(2 * PI * it / n + 0.4) }
        repeat(iterations) {
            var largest = 0.0
            for (k in 0 until n) {
                // p(z) and p'(z) by Horner.
                var vr = 1.0
                var vi = 0.0
                var dr = 0.0
                var di = 0.0
                for (i in 1..n) {
                    val ndr = dr * zr[k] - di * zi[k] + vr
                    val ndi = dr * zi[k] + di * zr[k] + vi
                    dr = ndr
                    di = ndi
                    val nvr = vr * zr[k] - vi * zi[k] + p[i]
                    val nvi = vr * zi[k] + vi * zr[k]
                    vr = nvr
                    vi = nvi
                }
                if (vr == 0.0 && vi == 0.0) continue
                // ratio = p / p'
                val dd = dr * dr + di * di
                if (dd == 0.0) {
                    zr[k] += 1e-6
                    continue
                }
                val rr = (vr * dr + vi * di) / dd
                val ri = (vi * dr - vr * di) / dd
                // sum = Σ 1 / (z_k − z_j)
                var sr = 0.0
                var si = 0.0
                for (j in 0 until n) {
                    if (j == k) continue
                    val er = zr[k] - zr[j]
                    val ei = zi[k] - zi[j]
                    val ee = er * er + ei * ei
                    if (ee == 0.0) continue
                    sr += er / ee
                    si += -ei / ee
                }
                // w = ratio / (1 − ratio·sum)
                val br = 1 - (rr * sr - ri * si)
                val bi = -(rr * si + ri * sr)
                val bb = br * br + bi * bi
                if (bb == 0.0) continue
                val wr = (rr * br + ri * bi) / bb
                val wi = (ri * br - rr * bi) / bb
                zr[k] -= wr
                zi[k] -= wi
                largest = max(largest, hypot(wr, wi))
            }
            if (largest < 1e-13) return List(n) { zr[it] to zi[it] }
        }
        return List(n) { zr[it] to zi[it] }
    }
}
