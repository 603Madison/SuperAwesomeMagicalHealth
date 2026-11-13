package com.health.app.ui.modules.transition.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The trainer's measures against made-up sounds whose answers are known: the same sounds the web
 * app tests against, so both agree (the contract's header in shared/…/Voice.kt).
 */
class VoiceAnalysisTest {
    private val rate = VoiceScale.RATE

    private fun sine(hz: Double, seconds: Double, amplitude: Double = 0.3) =
        FloatArray((rate * seconds).toInt()) { i -> (amplitude * sin(2 * PI * hz * i / rate + 0.2)).toFloat() }

    /** A tone whose harmonics have these levels in dB (relative), from f0 up. */
    private fun harmonics(f0: Double, seconds: Double, levelsDb: List<Double>, amplitude: Double = 0.2) =
        FloatArray((rate * seconds).toInt()) { i ->
            levelsDb.withIndex().sumOf { (h, db) -> amplitude * 10.0.pow(db / 20) * sin(2 * PI * f0 * (h + 1) * i / rate + h * 0.7) }.toFloat()
        }

    /**
     * A synthetic vowel: an impulse train at [f0] through second-order formant resonators (Klatt's)
     * at [formants], with bandwidths [bandwidths], in cascade.
     */
    private fun vowel(f0: Double, formants: List<Double>, seconds: Double = 1.0, bandwidths: List<Double> = listOf(80.0, 100.0, 140.0)): FloatArray {
        val n = (rate * seconds).toInt()
        val period = rate / f0
        var signal = DoubleArray(n) { i -> if ((i % period) < 1.0) 1.0 else 0.0 }
        val t = 1.0 / rate
        formants.forEachIndexed { k, f ->
            val bw = bandwidths[k]
            val c = -exp(-2 * PI * bw * t)
            val b = 2 * exp(-PI * bw * t) * cos(2 * PI * f * t)
            val a = 1 - b - c
            val out = DoubleArray(n)
            for (i in 0 until n) out[i] = a * signal[i] + b * (if (i > 0) out[i - 1] else 0.0) + c * (if (i > 1) out[i - 2] else 0.0)
            signal = out
        }
        val peak = signal.maxOf { kotlin.math.abs(it) }
        return FloatArray(n) { (signal[it] / peak * 0.3).toFloat() }
    }

    private fun voiced(frames: List<VoiceFrame>) = frames.filter { it.voiced }

    // ---- The contract's scales ----

    @Test
    fun `the contract's check values come out as it says`() {
        assertEquals(1029.3, VoiceScale.spacing(515.0, 1544.0, 2573.0), 0.1)
        assertEquals(17.0, VoiceScale.tractLength(VoiceScale.spacing(515.0, 1544.0, 2573.0)), 0.01)
        assertEquals(12.0, VoiceScale.resonance(515.0, 1544.0, 2573.0), 0.5)
        assertEquals(14.6, VoiceScale.tractLength(VoiceScale.spacing(600.0, 1800.0, 3000.0)), 0.05)
        assertEquals(73.0, VoiceScale.resonance(600.0, 1800.0, 3000.0), 0.5)
        // Clamped at the ends.
        assertEquals(0.0, VoiceScale.resonance(300.0, 900.0, 1500.0), 0.0)
        assertEquals(100.0, VoiceScale.resonance(1000.0, 2500.0, 4000.0), 0.0)
        assertEquals(0.0, VoiceScale.weight(12.0), 1e-9)
        assertEquals(100.0, VoiceScale.weight(-6.0), 1e-9)
        assertEquals(50.0, VoiceScale.weight(3.0), 1e-9)
        assertEquals(0.0, VoiceScale.weight(20.0), 0.0)
    }

    @Test
    fun `notes are named, and semitones counted`() {
        assertEquals("A4", VoiceScale.note(440.0))
        assertEquals("A3", VoiceScale.note(220.0))
        assertEquals("C4", VoiceScale.note(261.6))
        assertEquals("F#3", VoiceScale.note(185.0))
        assertEquals("G#2", VoiceScale.note(104.0))
        assertEquals(12.0, VoiceScale.semitones(440.0, 220.0), 1e-9)
        assertEquals(233.08, VoiceScale.shift(220.0, 1.0), 0.01)
        assertEquals(200.0, VoiceScale.middle(160.0, 250.0), 0.01)
    }

    // ---- Pitch ----

    @Test
    fun `sines are heard at their pitch, from low to high`() {
        for (hz in listOf(75.0, 110.0, 165.0, 220.0, 330.0, 440.0)) {
            val pitches = voiced(VoiceAnalyzer.analyse(sine(hz, 0.5))).mapNotNull { it.hz }
            assertTrue("$hz Hz: too few voiced frames (${pitches.size})", pitches.size >= 20)
            assertEquals("$hz Hz", hz, median(pitches), hz * 0.01)
        }
    }

    @Test
    fun `a harmonic tone is heard at its fundamental, even with a weak one`() {
        val pitches = voiced(VoiceAnalyzer.analyse(harmonics(147.0, 0.5, listOf(-6.0, 0.0, -3.0, -8.0, -12.0)))).mapNotNull { it.hz }
        assertTrue(pitches.size >= 20)
        assertEquals(147.0, median(pitches), 1.5)
    }

    @Test
    fun `silence and sound under −50 dBFS have no pitch, and read low on loudness`() {
        val frames = VoiceAnalyzer.analyse(FloatArray(rate / 2))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.none { it.voiced })
        assertTrue(frames.all { it.db <= -100 })
        // RMS of a sine is its amplitude over √2: 0.004 is −51 dBFS, 0.005 is −49.
        assertTrue(VoiceAnalyzer.analyse(sine(200.0, 0.3, amplitude = 0.004)).none { it.voiced })
        assertTrue(VoiceAnalyzer.analyse(sine(200.0, 0.3, amplitude = 0.005)).all { it.voiced })
    }

    @Test
    fun `frames come every 20 ms, 40 ms long`() {
        val frames = VoiceAnalyzer.analyse(sine(200.0, 1.0))
        // (16 000 − 640) / 320 + 1 = 49 frames in a second.
        assertEquals(49, frames.size)
        assertEquals(20L, frames[1].at - frames[0].at)
    }

    // ---- Loudness ----

    @Test
    fun `loudness is RMS in dBFS`() {
        // A sine's RMS is its amplitude over √2: 0.5 → −9.03 dBFS.
        val frames = VoiceAnalyzer.analyse(sine(200.0, 0.3, amplitude = 0.5))
        assertEquals(-9.03, median(frames.map { it.db }), 0.05)
        assertEquals(-29.03, median(VoiceAnalyzer.analyse(sine(200.0, 0.3, amplitude = 0.05)).map { it.db }), 0.05)
        assertEquals(0.0, VoiceScale.dbfs(1.0), 1e-9)
    }

    @Test
    fun `pushing is a while spent well above the person's usual level`() {
        val usual = -38.0
        val loud = List(250) { -24.0 }
        assertTrue(Loudness.pushing(loud, usual))
        // A moment isn't a while.
        assertTrue(!Loudness.pushing(List(60) { -20.0 }, usual))
        // Comfortable speech isn't pushing.
        assertTrue(!Loudness.pushing(List(250) { -36.0 }, usual))
        assertEquals("Loud", Loudness.words(-20.0, usual))
        assertEquals("Comfortable", Loudness.words(-36.0, usual))
        assertEquals("Soft", Loudness.words(-55.0, usual))
        assertEquals("Quiet", Loudness.words(null, usual))
    }

    // ---- Resonance ----

    @Test
    fun `formants of a synthetic vowel are found by LPC`() {
        val frames = voiced(VoiceAnalyzer.analyse(vowel(120.0, listOf(515.0, 1544.0, 2573.0))))
        val found = frames.mapNotNull { it.formants }
        assertTrue("Formants in too few frames (${found.size} of ${frames.size})", found.size >= frames.size * 0.8)
        assertEquals(515.0, median(found.map { it.f1 }), 40.0)
        assertEquals(1544.0, median(found.map { it.f2 }), 50.0)
        assertEquals(2573.0, median(found.map { it.f3 }), 60.0)
    }

    @Test
    fun `a darker synthetic vowel scores near 12 for resonance`() {
        val resonance = measuredResonance(vowel(120.0, listOf(515.0, 1544.0, 2573.0)))
        println("Resonance of the 515/1544/2573 Hz vowel at 120 Hz: ${"%.1f".format(resonance)} (contract: about 12)")
        assertEquals(12.0, resonance, 8.0)
    }

    @Test
    fun `a brighter synthetic vowel scores near 73 for resonance`() {
        val resonance = measuredResonance(vowel(200.0, listOf(600.0, 1800.0, 3000.0)))
        println("Resonance of the 600/1800/3000 Hz vowel at 200 Hz: ${"%.1f".format(resonance)} (contract: about 73)")
        assertEquals(73.0, resonance, 8.0)
    }

    @Test
    fun `resonance doesn't depend much on pitch, at the same pitches the web app checks`() {
        // The web app's numbers for the same vowels at 110, 150 and 210 Hz, for comparison.
        val web = mapOf(110.0 to (9.4 to 72.5), 150.0 to (7.5 to 70.8), 210.0 to (5.1 to 71.9))
        for ((f0, theirs) in web) {
            val darker = measuredResonance(vowel(f0, listOf(515.0, 1544.0, 2573.0)))
            val brighter = measuredResonance(vowel(f0, listOf(600.0, 1800.0, 3000.0)))
            println("At $f0 Hz: 515/1544/2573 → ${"%.1f".format(darker)} (web ${theirs.first}), 600/1800/3000 → ${"%.1f".format(brighter)} (web ${theirs.second})")
            assertEquals("Darker vowel at $f0 Hz", 12.0, darker, 8.0)
            assertEquals("Brighter vowel at $f0 Hz", 73.0, brighter, 8.0)
        }
    }

    private fun measuredResonance(sound: FloatArray): Double {
        val values = voiced(VoiceAnalyzer.analyse(sound)).mapNotNull { it.resonance }
        assertTrue("No resonance measured", values.size >= 10)
        return median(values)
    }

    @Test
    fun `LPC finds the poles of a known all-pole filter`() {
        // Roots of z² − 2r·cos(θ)·z + r² are r·e^(±iθ).
        val r = 0.95
        val theta = 2 * PI * 1000 / VoiceScale.LPC_RATE
        val roots = Polynomial.roots(doubleArrayOf(1.0, -2 * r * cos(theta), r * r))
        val upper = roots.single { it.second > 0 }
        assertEquals(r * cos(theta), upper.first, 1e-9)
        assertEquals(r * sin(theta), upper.second, 1e-9)
        // A degree-12 polynomial with known roots comes back whole.
        val known = listOf(0.9 to 0.3, 0.5 to 0.7, -0.2 to 0.85, -0.8 to 0.4, 0.1 to 0.2, 0.7 to 0.1)
        var poly = doubleArrayOf(1.0)
        known.forEach { (re, im) -> poly = multiply(poly, doubleArrayOf(1.0, -2 * re, re * re + im * im)) }
        val found = Polynomial.roots(poly).filter { it.second > 0 }.sortedBy { it.first }
        assertEquals(6, found.size)
        known.sortedBy { it.first }.zip(found).forEach { (want, got) ->
            assertEquals(want.first, got.first, 1e-7)
            assertEquals(want.second, got.second, 1e-7)
        }
    }

    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size + b.size - 1).also { out ->
        for (i in a.indices) for (j in b.indices) out[i + j] += a[i] * b[j]
    }

    @Test
    fun `resampling keeps a tone's pitch and level, and drops what's too high to keep`() {
        val r = Resampler(VoiceScale.RATE, VoiceScale.LPC_RATE)
        val tone = sine(1000.0, 0.2, amplitude = 0.5)
        val out = r.block(tone)
        assertEquals(((tone.size - 1) * VoiceScale.LPC_RATE / VoiceScale.RATE + 1).toDouble(), out.size.toDouble(), 1.0)
        // The middle, away from the edges: same level, same frequency (zero crossings).
        val middle = out.copyOfRange(200, out.size - 200)
        val rms = sqrt(middle.sumOf { it * it } / middle.size)
        assertEquals(0.5 / sqrt(2.0), rms, 0.01)
        val crossings = (1 until middle.size).count { middle[it - 1] < 0 && middle[it] >= 0 }
        assertEquals(1000.0 * middle.size / VoiceScale.LPC_RATE, crossings.toDouble(), 2.0)
        // 7 kHz can't be kept at 11 025 Hz: it's filtered out rather than folded down.
        val high = r.block(sine(7000.0, 0.2, amplitude = 0.5)).let { it.copyOfRange(200, it.size - 200) }
        assertTrue(sqrt(high.sumOf { it * it } / high.size) < 0.01)
    }

    @Test
    fun `a 48 kHz microphone stream is brought to 16 kHz in pieces, without seams`() {
        val stream = StreamResampler(48_000, VoiceScale.RATE)
        val source = FloatArray(48_000) { i -> (0.4 * sin(2 * PI * 220 * i / 48_000.0)).toFloat() }
        val out = ArrayList<Float>()
        var i = 0
        while (i < source.size) {
            val n = minOf(777, source.size - i)
            out += stream.push(source.copyOfRange(i, i + n), n).toList()
            i += n
        }
        assertEquals(16_000.0, out.size.toDouble(), 60.0)
        val pitches = voiced(VoiceAnalyzer.analyse(out.toFloatArray())).mapNotNull { it.hz }
        assertEquals(220.0, median(pitches), 1.0)
        // Smooth: no sample jumps more than a sine at 220 Hz can.
        val maxStep = 0.4 * 2 * PI * 220 / VoiceScale.RATE
        assertTrue((200 until out.size - 200).all { kotlin.math.abs(out[it] - out[it - 1]) <= maxStep * 1.05 })
    }

    // ---- Weight ----

    @Test
    fun `H1 − H2 is measured from the first two harmonics`() {
        for ((h1, h2) in listOf(0.0 to -12.0, 0.0 to 0.0, -6.0 to 0.0, 0.0 to -3.0)) {
            val frames = voiced(VoiceAnalyzer.analyse(harmonics(150.0, 0.5, listOf(h1, h2, -10.0, -14.0))))
            val measured = median(frames.mapNotNull { it.h1MinusH2 })
            assertEquals("H1 $h1 dB, H2 $h2 dB", h1 - h2, measured, 1.0)
        }
    }

    @Test
    fun `weight follows H1 − H2, lighter with a strong H1 and heavier with a strong H2`() {
        fun weightOf(h1: Double, h2: Double, f0: Double = 180.0) =
            median(voiced(VoiceAnalyzer.analyse(harmonics(f0, 0.5, listOf(h1, h2, -12.0, -18.0)))).mapNotNull { it.weight })
        assertEquals(0.0, weightOf(0.0, -12.0), 6.0)
        assertEquals(50.0, weightOf(0.0, -3.0), 6.0)
        assertEquals(100.0, weightOf(-6.0, 0.0), 6.0)
        assertEquals(50.0, weightOf(0.0, -3.0, f0 = 110.0), 6.0)
        assertTrue(weightOf(0.0, -9.0) < weightOf(0.0, 0.0))
    }

    // ---- Intonation ----

    @Test
    fun `a steady tone has almost no spread and a glide over an octave has a lot`() {
        val steady = voiced(VoiceAnalyzer.analyse(sine(180.0, 1.0))).mapNotNull { it.hz }
        assertEquals(0.0, VoiceScale.intonation(steady)!!, 0.1)
        // A glide from 150 to 300 Hz, even in semitones: uniform over 12 semitones, so a spread of 12/√12 ≈ 3.46.
        val seconds = 2.0
        val n = (rate * seconds).toInt()
        var phase = 0.0
        val glide = FloatArray(n) { i ->
            val hz = 150.0 * 2.0.pow(i.toDouble() / n)
            phase += 2 * PI * hz / rate
            (0.3 * sin(phase)).toFloat()
        }
        val pitches = voiced(VoiceAnalyzer.analyse(glide)).mapNotNull { it.hz }
        assertTrue(pitches.size > 80)
        println("Spread of the 150 → 300 Hz glide: ${"%.2f".format(VoiceScale.intonation(pitches))} semitones (web 3.43)")
        assertEquals(12 / sqrt(12.0), VoiceScale.intonation(pitches)!!, 0.15)
        assertTrue(pitches.first() < 160 && pitches.last() > 285)
        assertNull(VoiceScale.intonation(listOf(200.0)))
    }

    // ---- Steadying ----

    @Test
    fun `the shown value is a median over about 300 ms, and lets go after a pause`() {
        val steady = Steady()
        assertEquals(200.0, steady.add(0, 200.0)!!, 0.0)
        steady.add(20, 202.0)
        steady.add(40, 404.0) // an octave slip
        assertEquals(201.5, steady.add(60, 201.0)!!, 0.0)
        // Old readings drop out of the window.
        assertNotNull(steady.add(400, 180.0))
        assertEquals(180.0, steady.add(420, 180.0)!!, 0.0)
        // A short gap holds; a longer one lets go.
        assertNotNull(steady.add(560, null))
        assertNull(steady.add(700, null))
    }
}
