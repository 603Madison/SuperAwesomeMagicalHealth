package com.fitness.app.devices.colmi

import com.fitness.api.Metric
import com.fitness.api.SleepStage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ColmiProtocolTest {
    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun packet(vararg bytes: Int): ByteArray {
        val p = ByteArray(16)
        bytes.forEachIndexed { i, b -> p[i] = b.toByte() }
        p[15] = ColmiProtocol.checksum(p).toByte()
        return p
    }

    @Test
    fun `the example activity packet's checksum is right`() {
        val p = hex("43 23 08 13 10 00 05 C8 00 30 00 1B 00 00 00 A9")
        assertTrue(ColmiProtocol.isValid(p))
        assertEquals(0xA9, ColmiProtocol.checksum(p))
        p[3] = 0x14
        assertFalse(ColmiProtocol.isValid(p))
    }

    @Test
    fun `packets are sixteen bytes of command, data and checksum`() {
        val p = ColmiProtocol.battery()
        assertEquals(16, p.size)
        assertEquals(0x03, p[0].toInt())
        assertEquals(0x03, p[15].toInt())
        assertTrue(ColmiProtocol.isValid(ColmiProtocol.steps(2)))
        assertEquals(listOf(0x43, 2, 0x0F, 0x00, 0x5F, 0x01), ColmiProtocol.steps(2).take(6).map { it.toInt() and 0xFF })
    }

    @Test
    fun `the clock is set in BCD, in English`() {
        val p = ColmiProtocol.setTime(LocalDateTime.of(2026, 11, 12, 21, 7, 45))
        assertEquals(listOf(0x01, 0x26, 0x11, 0x12, 0x21, 0x07, 0x45, 0x01), p.take(8).map { it.toInt() and 0xFF })
        assertEquals(26, ColmiProtocol.fromBcd(0x26))
        assertEquals(0x59.toByte(), ColmiProtocol.toBcd(59))
    }

    @Test
    fun `a day's heart-rate log is asked for by its midnight, little-endian`() {
        val p = ColmiProtocol.heartRateLog(LocalDate.of(2024, 8, 13))
        val seconds = LocalDate.of(2024, 8, 13).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        assertEquals(seconds, ColmiProtocol.uint32(p, 1))
        assertEquals(0x15, p[0].toInt())
    }

    @Test
    fun `battery replies are read`() {
        val b = ColmiProtocol.parseBattery(packet(0x03, 82, 1))!!
        assertEquals(82, b.level)
        assertTrue(b.charging)
        assertNull(ColmiProtocol.parseBattery(packet(0x15, 82)))
    }

    @Test
    fun `steps replies are read, header first`() {
        val parser = StepsParser()
        assertFalse(parser.feed(hex("43 F0 05 00 00 00 00 00 00 00 00 00 00 00 00 38")))
        assertTrue(parser.feed(packet(0x43, 0x23, 0x08, 0x13, 0x10, 0x00, 0x01, 0xC8, 0x00, 0x30, 0x00, 0x1B, 0x00)))
        val slot = parser.slots.single()
        assertEquals(LocalDate.of(2023, 8, 13), slot.date)
        assertEquals(16, slot.quarter)
        assertEquals(48, slot.steps)
        assertEquals(27, slot.distanceM)
        assertEquals(0.2, slot.calories, 1e-9)
    }

    @Test
    fun `the new calorie protocol counts in tens`() {
        val parser = StepsParser()
        parser.feed(packet(0x43, 0xF0, 0x05, 0x01))
        parser.feed(packet(0x43, 0x24, 0x08, 0x08, 36, 0, 1, 0xFF, 0x00, 95, 0, 69, 0))
        assertEquals(2.55, parser.slots.single().calories, 1e-9)
    }

    @Test
    fun `steps stop at the last packet, or when there's nothing`() {
        val parser = StepsParser()
        assertFalse(parser.feed(packet(0x43, 0x24, 0x01, 0x02, 0, 0, 3, 0, 0, 10, 0, 7, 0)))
        assertFalse(parser.feed(packet(0x43, 0x24, 0x01, 0x02, 1, 1, 3, 0, 0, 10, 0, 7, 0)))
        assertTrue(parser.feed(packet(0x43, 0x24, 0x01, 0x02, 2, 2, 3, 0, 0, 10, 0, 7, 0)))
        assertEquals(3, parser.slots.size)

        val empty = StepsParser()
        assertTrue(empty.feed(packet(0x43, 0xFF)))
        assertTrue(empty.noData)
    }

    @Test
    fun `the heart-rate log is put together from its packets`() {
        val parser = HeartRateLogParser()
        assertFalse(parser.feed(packet(0x15, 0, 3, 5)))
        assertFalse(parser.feed(packet(0x15, 1, 0, 0, 0, 0, 60, 61, 0, 63, 64, 65, 66, 67, 68)))
        assertTrue(parser.feed(packet(0x15, 2, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82)))
        val log = parser.result()!!
        assertEquals(5, log.intervalMinutes)
        assertEquals(22, log.values.size)
        assertEquals(listOf(60, 61, 0, 63), log.values.take(4))
        assertEquals(82, log.values.last())
    }

    @Test
    fun `no heart-rate log is no data`() {
        val parser = HeartRateLogParser()
        assertTrue(parser.feed(packet(0x15, 0xFF)))
        assertNull(parser.result())
    }

    @Test
    fun `heart rates become samples at their times, without zeros or the future`() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 11, 12)
        val log = HeartRateLog(5, listOf(60, 0, 255, 70, 72))
        val now = date.atTime(0, 16).atZone(zone).toInstant().toEpochMilli()
        val samples = ColmiMapping.heartRates(date, log, zone, now)
        assertEquals(listOf(60.0, 70.0), samples.map { it.value })
        assertEquals(date.atTime(0, 15).atZone(zone).toInstant().toEpochMilli(), samples[1].start)
        assertTrue(samples.all { it.metric == Metric.HEART_RATE })
    }

    @Test
    fun `activity becomes quarter-hour steps, distance and calories`() {
        val zone = ZoneId.of("UTC")
        val samples = ColmiMapping.activity(listOf(StepSlot(LocalDate.of(2026, 1, 1), 4, 400, 15.0, 300)), zone)
        val start = LocalDate.of(2026, 1, 1).atTime(1, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(setOf(Metric.STEPS, Metric.DISTANCE, Metric.ACTIVE_CALORIES), samples.map { it.metric }.toSet())
        assertTrue(samples.all { it.start == start && it.end == start + 15 * 60_000L })
    }

    @Test
    fun `calories out of all proportion to steps are estimated instead`() {
        assertEquals(15.0, ColmiMapping.plausibleCalories(StepSlot(LocalDate.MIN, 0, 400, 15.0, 0)), 1e-9)
        assertEquals(16.0, ColmiMapping.plausibleCalories(StepSlot(LocalDate.MIN, 0, 400, 2_000.0, 0)), 1e-9)
        assertEquals(16.0, ColmiMapping.plausibleCalories(StepSlot(LocalDate.MIN, 0, 400, 0.4, 0)), 1e-9)
    }

    private fun sleepReply(vararg days: List<Int>): ByteArray {
        val body = ArrayList<Int>()
        body += days.size
        days.forEach { body += it }
        val length = body.size
        return (listOf(0xBC, 0x27, length and 0xFF, length shr 8, 0, 0) + body).map { it.toByte() }.toByteArray()
    }

    @Test
    fun `sleep is put together from several notifications`() {
        val reply = sleepReply(listOf(0, 8, 0x50, 0x05, 0x68, 0x01, 2, 60, 3, 45))
        val assembler = BigDataAssembler(ColmiProtocol.BIG_DATA_SLEEP)
        assertNull(assembler.feed(reply.copyOfRange(0, 9)))
        assertArrayEquals(reply, assembler.feed(reply.copyOfRange(9, reply.size)))
    }

    @Test
    fun `a night that began the evening before is read so`() {
        // Began at 22:24 (1344 minutes after midnight), ended 06:00; an hour light then 45 min deep.
        val today = LocalDate.of(2026, 11, 12)
        val reply = sleepReply(listOf(0, 8, 1344 and 0xFF, 1344 shr 8, 360 and 0xFF, 360 shr 8, 2, 60, 3, 45))
        val night = ColmiSleep.parse(reply, today).single()
        assertEquals(LocalDateTime.of(2026, 11, 11, 22, 24), night.start)
        assertEquals(LocalDateTime.of(2026, 11, 12, 6, 0), night.end)
        assertEquals(listOf(RingStage.LIGHT to 60, RingStage.DEEP to 45), night.stages)

        val session = ColmiMapping.sleep(listOf(night), ZoneId.of("UTC")).single()
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP), session.stages.map { it.stage })
        assertEquals(session.start + 60 * 60_000L, session.stages[1].start)
    }

    @Test
    fun `several nights, and the days before, are read`() {
        val today = LocalDate.of(2026, 11, 12)
        val reply = sleepReply(
            listOf(1, 6, 0x00, 0x00, 0x68, 0x01, 4, 30),
            listOf(0, 4, 0x3C, 0x00, 0x68, 0x01),
        )
        val nights = ColmiSleep.parse(reply, today)
        assertEquals(2, nights.size)
        assertEquals(LocalDate.of(2026, 11, 11), nights[0].start.toLocalDate())
        assertEquals(listOf(RingStage.REM to 30), nights[0].stages)
        assertTrue(nights[1].stages.isEmpty())
    }

    @Test
    fun `empty sleep replies give no nights`() {
        assertTrue(ColmiSleep.parse(byteArrayOf(0xBC.toByte(), 0x27, 0, 0, 0, 0), LocalDate.now()).isEmpty())
        assertTrue(ColmiSleep.parse(ByteArray(3), LocalDate.now()).isEmpty())
    }

    @Test
    fun `rings are recognised by name`() {
        assertTrue(ColmiProtocol.looksLikeRing("R02_A1B2"))
        assertTrue(ColmiProtocol.looksLikeRing("COLMI R10"))
        assertTrue(ColmiProtocol.looksLikeRing("R12_0C3D"))
        assertFalse(ColmiProtocol.looksLikeRing("Pebble Time 2"))
        assertFalse(ColmiProtocol.looksLikeRing(null))
    }
}
