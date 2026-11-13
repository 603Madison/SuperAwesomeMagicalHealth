package com.health.app.devices.colmi

import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Blood oxygen, stress, HRV and skin temperature, from replies built to the layouts Gadgetbridge
 * (YawellRingPacketHandler) and Puxtril's notes document.
 */
class ColmiReadingsTest {
    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun ints(bytes: ByteArray, count: Int = bytes.size) = bytes.take(count).map { it.toInt() and 0xFF }

    /** A 16-byte reply: [bytes], zero-padded, with its checksum. */
    private fun packet(vararg bytes: Int): ByteArray {
        val p = ByteArray(16)
        bytes.forEachIndexed { i, b -> p[i] = b.toByte() }
        p[15] = ColmiProtocol.checksum(p).toByte()
        return p
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- Requests ----

    @Test
    fun `the measurements are turned on as Gadgetbridge does`() {
        assertEquals(listOf(0x2C, 2, 1), ints(ColmiProtocol.writeSpo2Settings(true), 3))
        assertEquals(listOf(0x36, 2, 1), ints(ColmiProtocol.writeStressSettings(true), 3))
        assertEquals(listOf(0x38, 2, 1), ints(ColmiProtocol.writeHrvSettings(true), 3))
        assertEquals(listOf(0x3A, 3, 2, 1), ints(ColmiProtocol.writeTemperatureSettings(true), 4))
        assertEquals(listOf(0x38, 2, 0), ints(ColmiProtocol.writeHrvSettings(false), 3))
        listOf(
            ColmiProtocol.writeSpo2Settings(true), ColmiProtocol.writeStressSettings(true),
            ColmiProtocol.writeHrvSettings(true), ColmiProtocol.writeTemperatureSettings(true),
        ).forEach { assertTrue(ColmiProtocol.isValid(it)) }
    }

    @Test
    fun `only the rings that keep temperature are asked to measure it`() {
        assertEquals(3, ColmiConduit.autoMeasurements(temperature = false).size)
        assertEquals(4, ColmiConduit.autoMeasurements(temperature = true).size)
        assertTrue(ColmiProtocol.measuresTemperature("R09_1A2B"))
        assertTrue(ColmiProtocol.measuresTemperature("R10_7B20"))
        assertFalse(ColmiProtocol.measuresTemperature("COLMI R10_7B20"))
        assertFalse(ColmiProtocol.measuresTemperature("R02_9E33"))
        assertFalse(ColmiProtocol.measuresTemperature("R10_XYZ1"))
        assertFalse(ColmiProtocol.measuresTemperature(null))
    }

    @Test
    fun `stress is asked for bare, HRV by how many days ago`() {
        assertEquals(hex("37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 37").toList(), ColmiProtocol.stressLog().toList())
        val hrv = ColmiProtocol.hrvLog(3)
        assertEquals(listOf(0x39, 3, 0, 0, 0, 0), ints(hrv, 6))
        assertTrue(ColmiProtocol.isValid(hrv))
        assertEquals(6, ColmiProtocol.hrvLog(40)[1].toInt())
    }

    @Test
    fun `big-data requests match Gadgetbridge's bytes`() {
        assertArrayEquals(hex("BC 25 01 00 3E 81 02"), ColmiProtocol.temperatureRequest())
        assertArrayEquals(hex("BC 2A 01 00 FF 00 FF"), ColmiProtocol.spo2Request())
        assertArrayEquals(ColmiProtocol.sleepRequest(), ColmiProtocol.bigDataRequest(ColmiProtocol.BIG_DATA_SLEEP, hex("FF")))
    }

    @Test
    fun `the big-data checksum is CRC-16 MODBUS`() {
        // The standard check value for "123456789".
        assertEquals(0x4B37, ColmiProtocol.crc16Modbus("123456789".toByteArray()))
        assertEquals(0xFFFF, ColmiProtocol.crc16Modbus(ByteArray(0)))
    }

    @Test
    fun `a reply with the error flag is the ring saying no`() {
        assertTrue(ColmiProtocol.isRefusal(packet(0xB7), ColmiProtocol.CMD_STRESS_LOG))
        assertFalse(ColmiProtocol.isRefusal(packet(0x37, 1), ColmiProtocol.CMD_STRESS_LOG))
        assertFalse(ColmiProtocol.isRefusal(packet(0xB9), ColmiProtocol.CMD_STRESS_LOG))
        assertFalse(ColmiProtocol.isRefusal(ByteArray(0), ColmiProtocol.CMD_STRESS_LOG))
    }

    // ---- Stress and HRV logs ----

    /** A whole log: the header, then packets 1–4 (12 readings, then 13 each), value = 10 + slot. */
    private fun halfHourReplies(command: Int, packets: Int = 5): List<ByteArray> {
        val replies = mutableListOf(packet(command, 0, packets, 30))
        var slot = 0
        for (index in 1 until packets) {
            val first = if (index == 1) 3 else 2
            val bytes = IntArray(15)
            bytes[0] = command
            bytes[1] = index
            if (index == 1) bytes[2] = 30
            for (i in first until 15) bytes[i] = (10 + slot++).coerceAtMost(255)
            replies += packet(*bytes)
        }
        return replies
    }

    @Test
    fun `a stress log is put together a half hour at a time`() {
        val parser = HalfHourLogParser(ColmiProtocol.CMD_STRESS_LOG)
        val replies = halfHourReplies(ColmiProtocol.CMD_STRESS_LOG)
        replies.dropLast(1).forEach { assertFalse(parser.feed(it)) }
        assertTrue(parser.feed(replies.last()))
        val log = parser.result()!!
        assertEquals(48, log.values.size)
        assertEquals(10, log.values[0])
        assertEquals(21, log.values[11])
        // Packet 2 starts at 6:00 (slot 12), at byte 2.
        assertEquals(22, log.values[12])
        // The last half hour, 23:30, is in packet 4; what's past midnight is left out.
        assertEquals(57, log.values[47])
    }

    @Test
    fun `an HRV log is read the same way, and stops where its header says`() {
        val parser = HalfHourLogParser(ColmiProtocol.CMD_HRV_LOG)
        val replies = halfHourReplies(ColmiProtocol.CMD_HRV_LOG, packets = 3)
        assertFalse(parser.feed(replies[0]))
        assertFalse(parser.feed(replies[1]))
        assertTrue(parser.feed(replies[2]))
        val log = parser.result()!!
        assertEquals(34, log.values[24])
        assertEquals(0, log.values[25])
    }

    @Test
    fun `no stress kept is no data`() {
        val none = HalfHourLogParser(ColmiProtocol.CMD_STRESS_LOG)
        assertTrue(none.feed(packet(0x37, 0xFF)))
        assertTrue(none.noData)
        assertNull(none.result())

        val headerOnly = HalfHourLogParser(ColmiProtocol.CMD_HRV_LOG)
        assertTrue(headerOnly.feed(packet(0x39, 0, 1, 30)))
        assertNull(headerOnly.result())

        val zeros = HalfHourLogParser(ColmiProtocol.CMD_STRESS_LOG)
        halfHourReplies(0x37).take(1).forEach { zeros.feed(it) }
        (1..4).forEach { zeros.feed(packet(0x37, it)) }
        assertNull(zeros.result())
    }

    @Test
    fun `a malformed log reply is passed over`() {
        val parser = HalfHourLogParser(ColmiProtocol.CMD_STRESS_LOG)
        assertFalse(parser.feed(packet(0x37, 0, 3, 30)))
        // Another command's reply, a packet the header didn't promise, and a short packet.
        assertFalse(parser.feed(packet(0x39, 1, 30, 50, 50)))
        assertFalse(parser.feed(packet(0x37, 7, 99, 99, 99)))
        assertFalse(parser.feed(hex("37 01 1E 2A 2B")))
        assertNull(parser.result())
        assertFalse(parser.feed(packet(0x37, 1, 30, 40, 41)))
        assertTrue(parser.feed(packet(0x37, 2, 60)))
        assertEquals(listOf(40, 41), parser.result()!!.values.take(2))
        assertEquals(60, parser.result()!!.values[12])
    }

    @Test
    fun `a log cut short keeps what came`() {
        val parser = HalfHourLogParser(ColmiProtocol.CMD_HRV_LOG)
        val replies = halfHourReplies(ColmiProtocol.CMD_HRV_LOG)
        assertFalse(parser.feed(replies[0]))
        assertFalse(parser.feed(replies[1]))
        val log = parser.result()!!
        assertEquals((10..21).toList(), log.values.take(12))
        assertTrue(log.values.drop(12).all { it == 0 })
    }

    @Test
    fun `stress and HRV become samples at their half hours`() {
        val values = MutableList(48) { 0 }
        values[0] = 35
        values[17] = 62 // 8:30
        values[18] = 101 // out of range
        values[40] = 50 // 20:00, still to come
        val now = at(today, 14, 20)
        val stress = ColmiMapping.stress(today, HalfHourLog(values), zone, now)
        assertEquals(listOf(35.0, 62.0), stress.map { it.value })
        assertEquals(listOf(at(today, 0), at(today, 8, 30)), stress.map { it.start })
        assertTrue(stress.all { it.metric == Metric.STRESS && it.end == null })

        val hrv = ColmiMapping.hrv(today.minusDays(1), HalfHourLog(List(48) { if (it == 47) 255 else if (it == 46) 48 else 0 }), zone, now)
        assertEquals(listOf(Sample(Metric.HRV, at(today.minusDays(1), 23), value = 48.0)), hrv)
    }

    @Test
    fun `half hours follow the clock across the change to summer time`() {
        // 8 March 2026: clocks in New York go from 2:00 to 3:00.
        val day = LocalDate.of(2026, 3, 8)
        val values = MutableList(48) { 0 }
        values[3] = 40 // 1:30
        values[6] = 41 // 3:00
        val samples = ColmiMapping.stress(day, HalfHourLog(values), zone, Long.MAX_VALUE)
        assertEquals(listOf(at(day, 1, 30), at(day, 3, 0)), samples.map { it.start })
        assertEquals(30 * 60_000L, samples[1].start - samples[0].start)
    }

    // ---- Blood oxygen ----

    private fun bigData(type: Int, body: List<Int>, length: Int = body.size): ByteArray =
        (listOf(0xBC, type, length and 0xFF, length shr 8, 0, 0) + body).map { it.toByte() }.toByteArray()

    /** A day of blood oxygen: [daysAgo], then 24 (lowest, highest) pairs, [hours] filled in. */
    private fun spo2Day(daysAgo: Int, hours: Map<Int, Pair<Int, Int>>): List<Int> =
        listOf(daysAgo) + (0..23).flatMap { h -> hours[h]?.let { listOf(it.first, it.second) } ?: listOf(0, 0) }

    @Test
    fun `blood oxygen is read a day at a time, up to today`() {
        val reply = bigData(0x2A, spo2Day(1, mapOf(2 to (94 to 98), 23 to (95 to 97))) + spo2Day(0, mapOf(3 to (96 to 99))))
        val hours = ColmiSpo2.parse(reply, today)
        assertEquals(
            listOf(Spo2Hour(today.minusDays(1), 2, 94, 98), Spo2Hour(today.minusDays(1), 23, 95, 97), Spo2Hour(today, 3, 96, 99)),
            hours,
        )
    }

    @Test
    fun `a malformed blood-oxygen reply gives nothing it can't stand behind`() {
        // Another big-data type, a day too far back to be real, and readings over 100%.
        assertTrue(ColmiSpo2.parse(bigData(0x27, spo2Day(0, mapOf(1 to (95 to 98)))), today).isEmpty())
        assertTrue(ColmiSpo2.parse(bigData(0x2A, spo2Day(200, mapOf(1 to (95 to 98)))), today).isEmpty())
        assertEquals(listOf(4), ColmiSpo2.parse(bigData(0x2A, spo2Day(0, mapOf(1 to (95 to 180), 4 to (97 to 96)))), today).map { it.hour })
        assertEquals(96 to 97, ColmiSpo2.parse(bigData(0x2A, spo2Day(0, mapOf(4 to (97 to 96)))), today).single().let { it.min to it.max })
        assertTrue(ColmiSpo2.parse(byteArrayOf(0xBC.toByte(), 0x2A, 0, 0), today).isEmpty())
    }

    @Test
    fun `a blood-oxygen reply cut short keeps the whole hours`() {
        val day = spo2Day(0, mapOf(0 to (95 to 98), 1 to (94 to 97), 2 to (96 to 99)))
        // The length says a whole day, but the reply stops halfway through hour 2.
        val reply = bigData(0x2A, day.take(1 + 2 * 2 + 1), length = day.size)
        assertEquals(listOf(0, 1), ColmiSpo2.parse(reply, today).map { it.hour })
    }

    @Test
    fun `blood oxygen becomes a sample on each hour`() {
        val now = at(today, 14, 20)
        val samples = ColmiMapping.spo2(
            listOf(Spo2Hour(today, 3, 94, 98), Spo2Hour(today, 4, 40, 60), Spo2Hour(today, 15, 96, 98)),
            zone, now,
        )
        assertEquals(listOf(Sample(Metric.SPO2, at(today, 3), value = 96.0)), samples)
    }

    // ---- Skin temperature ----

    /** A day of temperature: [daysAgo], the byte Gadgetbridge skips, then 48 readings (tenths above 20 °C). */
    private fun temperatureDay(daysAgo: Int, readings: Map<Int, Int>): List<Int> =
        listOf(daysAgo, 0x1E) + (0 until 48).map { readings[it] ?: 0 }

    @Test
    fun `skin temperature is read on the hour and at half past`() {
        val reply = bigData(0x25, temperatureDay(1, mapOf(47 to 140)) + temperatureDay(0, mapOf(0 to 150, 1 to 152, 20 to 161)))
        val readings = ColmiTemperature.parse(reply, today)
        assertEquals(
            listOf(
                TemperatureReading(today.minusDays(1).atTime(23, 30), 34.0),
                TemperatureReading(today.atTime(0, 0), 35.0),
                TemperatureReading(today.atTime(0, 30), 35.2),
                TemperatureReading(today.atTime(10, 0), 36.1),
            ),
            readings,
        )
    }

    @Test
    fun `a malformed temperature reply gives nothing`() {
        // Shorter than a day (Gadgetbridge doesn't read those), another type, and a garbled day.
        assertTrue(ColmiTemperature.parse(bigData(0x25, listOf(0, 0x1E, 150, 150)), today).isEmpty())
        assertTrue(ColmiTemperature.parse(bigData(0x2A, temperatureDay(0, mapOf(0 to 150))), today).isEmpty())
        assertTrue(ColmiTemperature.parse(bigData(0x25, temperatureDay(99, mapOf(0 to 150))), today).isEmpty())
    }

    @Test
    fun `a temperature reply cut short keeps what came`() {
        val days = temperatureDay(1, mapOf(0 to 150)) + temperatureDay(0, mapOf(0 to 151, 30 to 155))
        val reply = bigData(0x25, days.take(50 + 2 + 10), length = days.size)
        assertEquals(listOf(35.0, 35.1), ColmiTemperature.parse(reply, today).map { it.celsius })
    }

    @Test
    fun `skin temperatures off the finger are left out`() {
        val now = at(today, 14, 20)
        val samples = ColmiMapping.skinTemperature(
            listOf(
                TemperatureReading(LocalDateTime.of(today, java.time.LocalTime.of(2, 30)), 34.6),
                TemperatureReading(LocalDateTime.of(today, java.time.LocalTime.of(3, 0)), 22.1),
                TemperatureReading(LocalDateTime.of(today, java.time.LocalTime.of(20, 0)), 35.0),
            ),
            zone, now,
        )
        assertEquals(listOf(Sample(Metric.SKIN_TEMPERATURE, at(today, 2, 30), value = 34.6)), samples)
    }

    // ---- Which readings the ring gives ----

    @Test
    fun `each reading remembers its newest time and how the ring answered`() {
        val now = at(today, 14, 20)
        val samples = listOf(
            Sample(Metric.HEART_RATE, at(today, 13, 55), value = 61.0),
            Sample(Metric.STEPS, at(today, 13, 45), at(today, 14, 0), 300.0),
            Sample(Metric.HRV, at(today, 13, 30), value = 44.0),
            Sample(Metric.HRV, at(today, 2, 0), value = 50.0),
        )
        val sleep = listOf(SleepSession(at(today.minusDays(1), 23), at(today, 6, 40)))
        val offered = RingReading.entries.toSet() - RingReading.SKIN_TEMPERATURE
        val answers = mapOf(RingReading.HRV to Answer.COMPLETE, RingReading.STRESS to Answer.SILENT, RingReading.SPO2 to Answer.REFUSED)
        val state = RingReadingsState().after("A", samples, sleep, answers, offered, now)
        val r = state.readings
        assertEquals(at(today, 13, 55), r[RingReading.HEART_RATE]!!.latest)
        assertEquals(at(today, 14, 0), r[RingReading.ACTIVITY]!!.latest)
        assertEquals(at(today, 6, 40), r[RingReading.SLEEP]!!.latest)
        assertEquals(at(today, 13, 30), r[RingReading.HRV]!!.latest)
        assertEquals(now, r[RingReading.HRV]!!.arrivedAt)
        assertNull(r[RingReading.STRESS]!!.latest)
        assertEquals(Answer.SILENT, r[RingReading.STRESS]!!.answer)
        assertEquals(Answer.REFUSED, r[RingReading.SPO2]!!.answer)
        assertFalse(r[RingReading.SKIN_TEMPERATURE]!!.offered)

        // A later sync with nothing new keeps what was known; another ring starts afresh.
        val later = state.after("A", emptyList(), emptyList(), mapOf(RingReading.STRESS to Answer.COMPLETE), offered, now + 3_600_000)
        assertEquals(at(today, 13, 30), later.readings[RingReading.HRV]!!.latest)
        assertEquals(now, later.readings[RingReading.HRV]!!.arrivedAt)
        assertEquals(Answer.COMPLETE, later.readings[RingReading.STRESS]!!.answer)
        val other = later.copy(autoMeasure = RingReadingsState.AUTO_MEASURE).after("B", emptyList(), emptyList(), emptyMap(), offered, now)
        assertNull(other.readings[RingReading.HRV]!!.latest)
        assertTrue(other.needsAutoMeasure("B"))
        assertFalse(later.copy(autoMeasure = RingReadingsState.AUTO_MEASURE).needsAutoMeasure("A"))
    }
}
