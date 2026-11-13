package com.health.app.ui.modules.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchDetectorTest {
    private val rate = 44_100
    private val detector = PitchDetector(rate)

    private fun sine(hz: Double, n: Int = 2_048, amplitude: Double = 0.5, phase: Double = 0.3) =
        FloatArray(n) { i -> (amplitude * sin(2 * PI * hz * i / rate + phase)).toFloat() }

    @Test
    fun `sine waves come back at their pitch, within 2 Hz`() {
        for (hz in listOf(110.0, 180.0, 220.0)) {
            val found = detector.detect(sine(hz))
            assertNotNull("No pitch found for $hz Hz", found)
            assertEquals("$hz Hz", hz, found!!, 2.0)
        }
    }

    @Test
    fun `a voice-like sound with overtones still reads at its fundamental`() {
        val hz = 147.0
        val samples = FloatArray(2_048) { i ->
            val t = 2 * PI * hz * i / rate
            (0.4 * sin(t) + 0.25 * sin(2 * t + 0.4) + 0.12 * sin(3 * t + 1.1)).toFloat()
        }
        assertEquals(hz, detector.detect(samples)!!, 2.0)
    }

    @Test
    fun `silence, and very quiet sound, give no pitch`() {
        assertNull(detector.detect(FloatArray(2_048)))
        assertNull(detector.detect(sine(180.0, amplitude = 0.002)))
    }

    @Test
    fun `noise gives no pitch`() {
        val random = java.util.Random(7)
        assertNull(detector.detect(FloatArray(2_048) { (random.nextFloat() - 0.5f) * 0.8f }))
    }

    @Test
    fun `a session's lowest, average and highest are steadied against odd jumps`() {
        val stats = PitchStats()
        listOf(180.0, 182.0, 181.0, 362.0, 183.0, 179.0, 181.0).forEach { stats.add(it) }
        // The octave jump never makes it past the median.
        assertEquals(181.0, stats.average!!, 1.5)
        assertEquals(182.0, stats.max!!, 0.01)
        assertEquals(181.0, stats.min!!, 0.01)
        assertNull(PitchStats().average)
        // Silence breaks the run.
        assertNull(stats.add(null))
    }

    @Test
    fun `the target band is a tenth either side, said in words`() {
        val band = PitchTarget.band(180.0)!!
        assertEquals(162.0, band.start, 0.01)
        assertEquals(198.0, band.endInclusive, 0.01)
        assertEquals("In your range", PitchTarget.describe(175.0, band))
        assertEquals("Below your range", PitchTarget.describe(140.0, band))
        assertEquals("Above your range", PitchTarget.describe(220.0, band))
        assertNull(PitchTarget.describe(175.0, null))
    }
}
