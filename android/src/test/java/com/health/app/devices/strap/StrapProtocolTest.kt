package com.health.app.devices.strap

import com.health.api.ApiJson
import com.health.api.Metric
import com.health.api.Sample
import com.health.app.devices.strap.StrapProtocol.Contact
import com.health.app.devices.strap.StrapProtocol.Measurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Heart Rate Measurements as straps send them (the Bluetooth SIG's Heart Rate profile), and what's made of them. */
class StrapProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** The start of a five-minute window on the clock. */
    private val t0 = StrapMaths.windowOf(1_795_384_800_000L)

    // ---- The bytes ----

    @Test
    fun `an 8-bit heart rate with nothing else`() {
        val m = StrapProtocol.measurement(bytes(0x00, 72))!!
        assertEquals(72, m.bpm)
        assertEquals(Contact.UNSUPPORTED, m.contact)
        assertTrue(m.worn)
        assertTrue(m.rrMs.isEmpty())
    }

    @Test
    fun `a 16-bit heart rate`() {
        assertEquals(72, StrapProtocol.measurement(bytes(0x01, 0x48, 0x00))!!.bpm)
        assertEquals(300, StrapProtocol.measurement(bytes(0x01, 0x2C, 0x01))!!.bpm)
    }

    @Test
    fun `skin contact, when the strap can tell`() {
        assertEquals(Contact.DETECTED, StrapProtocol.measurement(bytes(0x06, 60))!!.contact)
        val off = StrapProtocol.measurement(bytes(0x04, 0))!!
        assertEquals(Contact.NOT_DETECTED, off.contact)
        assertFalse(off.worn)
        // "Detected" without "supported" means it can't tell.
        assertEquals(Contact.UNSUPPORTED, StrapProtocol.measurement(bytes(0x02, 60))!!.contact)
    }

    @Test
    fun `beat-to-beat intervals in 1024ths of a second, after the energy expended`() {
        // Energy (16 kJ) and two intervals: 1024/1024 s and 512/1024 s.
        val m = StrapProtocol.measurement(bytes(0x18, 60, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(60, m.bpm)
        assertEquals(listOf(1000.0, 500.0), m.rrMs)
        // As a Polar H10 sends it: contact detected, one interval (0x03A6 = 934/1024 s).
        val polar = StrapProtocol.measurement(bytes(0x16, 64, 0xA6, 0x03))!!
        assertEquals(Contact.DETECTED, polar.contact)
        assertEquals(912.1, polar.rrMs.single(), 0.05)
        // A 16-bit heart rate with intervals.
        assertEquals(listOf(1000.0), StrapProtocol.measurement(bytes(0x11, 60, 0x00, 0x00, 0x04))!!.rrMs)
    }

    @Test
    fun `short or garbled packets are read as far as they go, and never crash`() {
        assertNull(StrapProtocol.measurement(ByteArray(0)))
        assertNull(StrapProtocol.measurement(bytes(0x00)))
        assertNull(StrapProtocol.measurement(bytes(0x01, 0x48)))
        // Intervals promised, half of one sent.
        assertTrue(StrapProtocol.measurement(bytes(0x10, 60, 0x00))!!.rrMs.isEmpty())
        // Energy promised, cut short: no intervals read out of it.
        assertTrue(StrapProtocol.measurement(bytes(0x18, 60, 0x10))!!.rrMs.isEmpty())
        // An odd byte at the end is left.
        assertEquals(1, StrapProtocol.measurement(bytes(0x10, 60, 0x00, 0x04, 0x07))!!.rrMs.size)
        val random = Random(7)
        repeat(20_000) {
            val packet = ByteArray(random.nextInt(0, 24)).also(random::nextBytes)
            StrapProtocol.measurement(packet)?.let { m -> assertTrue(m.rrMs.all { it >= 0 }) }
        }
    }

    @Test
    fun `battery level`() {
        assertEquals(85, StrapProtocol.battery(bytes(0x55)))
        assertEquals(0, StrapProtocol.battery(bytes(0x00)))
        assertNull(StrapProtocol.battery(bytes(0xFF)))
        assertNull(StrapProtocol.battery(ByteArray(0)))
        assertNull(StrapProtocol.battery(null))
    }

    @Test
    fun `straps that don't advertise the service are known by name`() {
        listOf("Polar H10 A1B2C3D4", "Polar H9 1234", "HRM-Dual:123456", "TICKR 1A2B", "COOSPO H808S", "HRM-Pro", "Heart Rate Sensor").forEach {
            assertTrue(it, StrapProtocol.looksLikeStrap(it))
        }
        listOf("R02_9E33", "Pebble Time 2", "Galaxy Buds", null).forEach { assertFalse("$it", StrapProtocol.looksLikeStrap(it)) }
    }

    // ---- Cleaning and HRV ----

    @Test
    fun `intervals out of range or jumping more than 20 percent are dropped`() {
        assertEquals(listOf(1000.0, 1010.0, 990.0, 1000.0), StrapMaths.clean(listOf(1000.0, 250.0, 1010.0, 2100.0, 1300.0, 990.0, 1000.0)))
    }

    @Test
    fun `an odd first interval doesn't hold the rest out for good`() {
        // A missed beat first (1900 ms): three in a row far from it, and the chain starts again.
        assertEquals(listOf(1900.0, 955.0, 950.0), StrapMaths.clean(listOf(1900.0, 950.0, 960.0, 955.0, 950.0)))
    }

    @Test
    fun `RMSSD`() {
        assertEquals(15.811, StrapMaths.rmssd(listOf(1000.0, 1010.0, 990.0))!!, 0.001)
        assertNull(StrapMaths.rmssd(listOf(1000.0)))
    }

    // ---- Each minute's heart rate ----

    private fun worn(bpm: Int, vararg rr: Double) = Measurement(bpm, Contact.DETECTED, rr.toList())

    private fun off(bpm: Int = 0) = Measurement(bpm, Contact.NOT_DETECTED)

    @Test
    fun `each minute's average heart rate, at the minute's start, once the minute is over`() {
        val a = StrapAggregator()
        for (i in 0 until 60) assertTrue(a.add(worn(60 + i % 2), t0 + i * 1000L).isEmpty())
        val out = a.add(worn(70), t0 + 60_000L)
        assertEquals(listOf(Sample(Metric.HEART_RATE, t0, value = 60.5)), out)
    }

    @Test
    fun `beats the strap says aren't touching skin are left out`() {
        val a = StrapAggregator()
        for (i in 0 until 60) a.add(if (i % 2 == 0) worn(58) else off(120), t0 + i * 1000L)
        assertEquals(58.0, a.close(t0 + 60_000L).single { it.metric == Metric.HEART_RATE }.value, 0.0)
        // A minute without a beat on skin has no heart rate at all.
        for (i in 0 until 60) a.add(off(), t0 + 60_000L + i * 1000L)
        assertTrue(a.close(t0 + 120_000L).none { it.metric == Metric.HEART_RATE })
    }

    @Test
    fun `a quiet strap's minute comes out once the time has passed, and the last one when recording stops`() {
        val a = StrapAggregator()
        a.add(worn(61), t0 + 5_000L)
        assertTrue(a.close(t0 + 59_000L).isEmpty())
        assertEquals(61.0, a.close(t0 + 60_000L).single().value, 0.0)
        a.add(worn(64), t0 + 70_000L)
        assertEquals(listOf(Sample(Metric.HEART_RATE, t0 + 60_000L, value = 64.0)), a.finish())
        assertTrue(a.finish().isEmpty())
    }

    // ---- Each five minutes' HRV ----

    /** [beats] at [rr] (ms) from [from], one a packet, each arriving when its beat ends. */
    private fun StrapAggregator.feed(from: Long, rr: List<Double>, contact: (Int) -> Boolean = { true }): List<Sample> {
        val out = ArrayList<Sample>()
        var t = from.toDouble()
        rr.forEachIndexed { i, v ->
            t += v
            val bpm = (60_000 / v).roundToInt()
            out += add(if (contact(i)) worn(bpm, v) else off(), t.toLong())
        }
        return out
    }

    private fun hrv(samples: List<Sample>) = samples.filter { it.metric == Metric.HRV }

    private fun rhythm(samples: List<Sample>) = samples.filter { it.metric == Metric.IRREGULAR_RHYTHM }

    @Test
    fun `HRV is the RMSSD of a window's clean intervals, from its start to its end`() {
        val a = StrapAggregator()
        // Alternating 1000 and 1020 ms for the whole window (and a little past it): every difference is 20 ms.
        val out = a.feed(t0 - 500, List(305) { if (it % 2 == 0) 1000.0 else 1020.0 })
        val h = hrv(out).single()
        assertEquals(t0, h.start)
        assertEquals(t0 + StrapMaths.WINDOW_MS, h.end)
        assertEquals(20.0, h.value, 0.0)
    }

    @Test
    fun `artefacts don't count towards HRV`() {
        val a = StrapAggregator()
        // A missed beat (2000) and an extra one (480) now and then: dropped, and no difference counted across them.
        val rr = List(300) { i -> if (i % 50 == 25) 2000.0 else if (i % 50 == 40) 480.0 else if (i % 2 == 0) 1000.0 else 1010.0 }
        val h = hrv(a.feed(t0, rr) + a.close(t0 + StrapMaths.WINDOW_MS)).single()
        assertEquals(10.0, h.value, 0.0)
    }

    @Test
    fun `HRV only when the clean intervals cover two minutes of the window`() {
        val short = StrapAggregator()
        short.feed(t0, List(100) { if (it % 2 == 0) 1000.0 else 1020.0 })
        assertTrue(hrv(short.close(t0 + StrapMaths.WINDOW_MS)).isEmpty())

        val enough = StrapAggregator()
        enough.feed(t0, List(125) { if (it % 2 == 0) 1000.0 else 1020.0 })
        assertEquals(20.0, hrv(enough.close(t0 + StrapMaths.WINDOW_MS)).single().value, 0.0)
    }

    @Test
    fun `no difference is counted across a dropped connection`() {
        val a = StrapAggregator()
        a.feed(t0, List(80) { if (it % 2 == 0) 1000.0 else 1010.0 })
        a.gap()
        // Back at a different pace (within 20%): no 100 ms jump counted between the two.
        a.feed(t0 + 120_000, List(80) { if (it % 2 == 0) 900.0 else 910.0 })
        assertEquals(10.0, hrv(a.close(t0 + StrapMaths.WINDOW_MS)).single().value, 0.0)
    }

    @Test
    fun `an unfinished recording picks up where it was after the app is closed`() {
        val rr = List(300) { if (it % 3 == 0) 1000.0 else if (it % 3 == 1) 1030.0 else 1015.0 }
        val whole = StrapAggregator()
        val expected = whole.feed(t0, rr) + whole.finish()

        val first = StrapAggregator()
        val before = first.feed(t0, rr.take(140))
        // Kept on the phone, read back by a fresh app.
        val kept = ApiJson.decodeFromString(StrapAggregate.serializer(), ApiJson.encodeToString(StrapAggregate.serializer(), first.state))
        val second = StrapAggregator(kept)
        var t = t0 + rr.take(140).sum()
        val after = rr.drop(140).flatMap { v -> t += v; second.add(worn((60_000 / v).roundToInt(), v), t.toLong()) } + second.finish()
        assertEquals(expected, before + after)
    }

    // ---- Each five minutes' rhythm ----

    @Test
    fun `a perfectly regular rhythm isn't irregular`() {
        assertFalse(StrapMaths.irregular(List(128) { 1000.0 }))
        val a = StrapAggregator()
        val out = a.feed(t0, List(300) { 1000.0 }) + a.close(t0 + StrapMaths.WINDOW_MS)
        val r = rhythm(out).single()
        assertEquals(0.0, r.value, 0.0)
        assertEquals(t0, r.start)
        assertEquals(t0 + StrapMaths.WINDOW_MS, r.end)
    }

    @Test
    fun `breathing's gentle swing (sinus arrhythmia) isn't irregular`() {
        val sinus = List(400) { 1000 + 50 * sin(it.toDouble()) }
        assertFalse(StrapMaths.irregular(sinus.take(128)))
        val slow = List(400) { 1000 + 50 * sin(2 * PI * it / 5) }
        assertFalse(StrapMaths.irregular(slow.take(128)))
        val a = StrapAggregator()
        assertEquals(0.0, rhythm(a.feed(t0, sinus) + a.close(t0 + StrapMaths.WINDOW_MS)).single().value, 0.0)
    }

    @Test
    fun `a random rhythm (as in atrial fibrillation) is irregular`() {
        val random = Random(2009)
        val rr = List(1200) { random.nextDouble(400.0, 1200.0) }
        rr.chunked(128).filter { it.size == 128 }.forEach { segment ->
            val r = StrapMaths.rhythm(segment)
            assertTrue("$r", r.irregular)
        }
        val a = StrapAggregator()
        val out = a.feed(t0, rr.take(370)) + a.close(t0 + StrapMaths.WINDOW_MS)
        assertEquals(100.0, rhythm(out).single().value, 0.0)
        // HRV leaves such a rhythm's jumps out; the rhythm reading is what notices it.
    }

    @Test
    fun `a few extra beats in a regular rhythm aren't irregular`() {
        // An early beat (650 ms) and the pause after it (1350 ms), three times in each 128 beats.
        fun ectopic(base: (Int) -> Double) = List(384) { i ->
            when (i % 128) {
                20, 60, 100 -> 650.0
                21, 61, 101 -> 1350.0
                else -> base(i)
            }
        }
        val steady = ectopic { 1000.0 }
        val swinging = ectopic { 1000 + 30 * sin(it.toDouble()) }
        steady.chunked(128).forEach { assertFalse(StrapMaths.irregular(it)) }
        swinging.chunked(128).forEach { assertFalse(StrapMaths.irregular(it)) }
        val a = StrapAggregator()
        assertEquals(0.0, rhythm(a.feed(t0, swinging.take(300)) + a.close(t0 + StrapMaths.WINDOW_MS)).single().value, 0.0)
    }

    @Test
    fun `half a window irregular reads 50`() {
        val random = Random(11)
        val rr = List(128) { 1000.0 } + List(128) { random.nextDouble(400.0, 1200.0) }
        val a = StrapAggregator()
        assertEquals(50.0, rhythm(a.feed(t0, rr) + a.close(t0 + StrapMaths.WINDOW_MS)).single().value, 0.0)
    }

    @Test
    fun `no rhythm reading without a whole 128-beat stretch`() {
        val a = StrapAggregator()
        assertTrue(rhythm(a.feed(t0, List(127) { 1000.0 }) + a.close(t0 + StrapMaths.WINDOW_MS)).isEmpty())
    }

    @Test
    fun `no rhythm reading for a window mostly off the skin`() {
        // 210 beats on skin, then 90 readings off it: 30% of the window's readings.
        val rr = List(300) { if (it % 2 == 0) 1000.0 else 1020.0 }
        val loose = StrapAggregator()
        val out = loose.feed(t0, rr) { it < 210 } + loose.close(t0 + StrapMaths.WINDOW_MS)
        assertTrue(rhythm(out).isEmpty())
        // HRV still has its two minutes.
        assertEquals(1, hrv(out).size)

        // 15% off the skin is fine.
        val snug = StrapAggregator()
        assertEquals(1, rhythm(snug.feed(t0, rr) { it < 255 } + snug.close(t0 + StrapMaths.WINDOW_MS)).size)
    }

    @Test
    fun `entropy and turning points`() {
        assertEquals(0.0, StrapMaths.entropy(List(50) { 1000.0 }), 0.0)
        // Evenly over all 16 bins.
        assertEquals(1.0, StrapMaths.entropy(List(160) { 400.0 + (it % 16) * 50 + 1 }), 1e-9)
        assertEquals(1.0, StrapMaths.turningPointRatio(listOf(1.0, 2.0, 1.0, 2.0, 1.0)), 0.0)
        assertEquals(0.0, StrapMaths.turningPointRatio(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
    }
}
