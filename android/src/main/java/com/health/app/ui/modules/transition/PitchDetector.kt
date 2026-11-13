package com.health.app.ui.modules.transition

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pitch of a voice in a short stretch of sound, by YIN (de Cheveigné and Kawahara, 2002): the
 * difference of the signal with itself at each lag, normalised, and the first lag that dips under
 * [threshold] is the period. Works on the phone, on samples in memory; nothing is kept.
 */
class PitchDetector(
    val sampleRate: Int,
    /** The range a speaking or singing voice covers. */
    val minHz: Double = 60.0,
    val maxHz: Double = 600.0,
    val threshold: Double = 0.15,
    /** Quieter than this (RMS, samples in −1…1) counts as silence. */
    val silence: Double = 0.01,
) {
    private var difference = DoubleArray(0)

    /** The pitch in Hz, or null for silence or sound without a clear pitch (breath, noise). */
    fun detect(samples: FloatArray, count: Int = samples.size): Double? {
        if (count < 64 || rms(samples, count) < silence) return null
        val maxTau = min((sampleRate / minHz).toInt(), count / 2)
        val minTau = max(2, (sampleRate / maxHz).toInt())
        if (maxTau <= minTau + 2) return null
        val window = count - maxTau
        if (difference.size < maxTau + 1) difference = DoubleArray(maxTau + 1)
        val d = difference
        // The difference function.
        for (tau in 1..maxTau) {
            var sum = 0.0
            for (j in 0 until window) {
                val delta = samples[j] - samples[j + tau]
                sum += delta * delta
            }
            d[tau] = sum
        }
        // Normalised by its running mean, so lag 0's dip doesn't count.
        d[0] = 1.0
        var running = 0.0
        for (tau in 1..maxTau) {
            running += d[tau]
            d[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
        }
        // The first dip under the threshold, followed down to its lowest point.
        var tau = minTau
        var found = -1
        while (tau <= maxTau) {
            if (d[tau] < threshold) {
                while (tau + 1 <= maxTau && d[tau + 1] < d[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) return null
        // Between samples: a parabola through the dip and its neighbours.
        val better = if (found in 1 until maxTau) {
            val a = d[found - 1]
            val b = d[found]
            val c = d[found + 1]
            val shift = (a - c) / (2 * (a - 2 * b + c))
            if (shift.isFinite() && kotlin.math.abs(shift) < 1) found + shift else found.toDouble()
        } else {
            found.toDouble()
        }
        val hz = sampleRate / better
        return hz.takeIf { it in minHz..maxHz }
    }

    companion object {
        fun rms(samples: FloatArray, count: Int = samples.size): Double {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += samples[i] * samples[i]
            return sqrt(sum / count)
        }
    }
}

/** A session's pitch: the lowest, average and highest, from readings steadied over a few at a time. */
class PitchStats {
    private val readings = ArrayList<Double>()
    private val recent = ArrayDeque<Double>()

    /** Adds a reading (null for silence) and returns the steadied pitch, or null. */
    fun add(hz: Double?): Double? {
        if (hz == null) {
            recent.clear()
            return null
        }
        recent.addLast(hz)
        if (recent.size > 5) recent.removeFirst()
        // A median of the last few drops the odd octave jump.
        val steady = recent.sorted()[recent.size / 2]
        if (recent.size >= 3) readings += steady
        return steady
    }

    val count: Int get() = readings.size

    val min: Double? get() = readings.minOrNull()?.round()
    val max: Double? get() = readings.maxOrNull()?.round()
    val average: Double? get() = readings.takeIf { it.isNotEmpty() }?.average()?.round()

    private fun Double.round() = (this * 10).roundToInt() / 10.0
}

/** Where a pitch sits against the person's target, in words (never by colour alone). */
object PitchTarget {
    /** The band around a target: a tenth either side. */
    fun band(target: Double?): ClosedFloatingPointRange<Double>? = target?.let { (it * 0.9)..(it * 1.1) }

    fun describe(hz: Double, band: ClosedFloatingPointRange<Double>?): String? = when {
        band == null -> null
        hz < band.start -> "Below your range"
        hz > band.endInclusive -> "Above your range"
        else -> "In your range"
    }
}
