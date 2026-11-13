package com.health.app.devices.colmi

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Bluetooth protocol of Colmi's smart rings (R02, R03, R06, R09, R10, R12, the Colmi Ring 2)
 * and other rings made for the QRing app. Plain Kotlin, so it can be tested without a phone.
 *
 * Commands and replies are 16-byte packets on a UART-like service: the command, its data
 * (zero-padded), and a checksum (the sum of the first 15 bytes). Sleep comes from a second
 * "big data" service, in replies of any length.
 *
 * Following what's been worked out by Gadgetbridge and colmi_r02_client (tahnok).
 *
 * Blood oxygen, stress, HRV and skin temperature follow Gadgetbridge's Yawell ring support
 * (codeberg.org/Freeyourgadget/Gadgetbridge, app/src/main/java/nodomain/freeyourgadget/gadgetbridge/
 * devices/yawell/ring/YawellRingConstants.java and YawellRingPacketHandler.java, and
 * service/devices/yawell/ring/YawellRingDeviceSupport.java; the Colmi R0x support was renamed so
 * in June 2025), checked against Puxtril's Colmi BLE API notes (colmi.puxtril.com: Commands,
 * Settings, Big Data). Only what those document is used.
 */
object ColmiProtocol {
    val SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val BIG_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
    val BIG_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val BIG_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

    /** The standard Device Information service, for the firmware version. */
    val DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    const val CMD_SET_TIME = 0x01
    const val CMD_BATTERY = 0x03
    const val CMD_HEART_RATE_LOG = 0x15
    const val CMD_HEART_RATE_SETTINGS = 0x16
    const val CMD_STEPS = 0x43
    const val CMD_BIG_DATA = 0xBC
    const val BIG_DATA_SLEEP = 0x27

    // Automatic measurement settings: [command, action, …] (Gadgetbridge CMD_AUTO_*_PREF; Puxtril
    // "Settings": Blood Oxygen 44, Pressure 54, HRV 56). Action 1 reads, 2 writes.
    const val CMD_SPO2_SETTINGS = 0x2C
    const val CMD_STRESS_SETTINGS = 0x36
    const val CMD_HRV_SETTINGS = 0x38
    /** Skin temperature's setting has an extra 0x03 before the action (Gadgetbridge CMD_AUTO_TEMP_PREF). */
    const val CMD_TEMPERATURE_SETTINGS = 0x3A
    const val ACTION_READ = 1
    const val ACTION_WRITE = 2

    // The day's stress and HRV logs, in split arrays like the heart-rate log (Gadgetbridge
    // CMD_SYNC_STRESS and CMD_SYNC_HRV; Puxtril "Pressure" 55 and "HRV" 57).
    const val CMD_STRESS_LOG = 0x37
    const val CMD_HRV_LOG = 0x39

    // Big-data types (Gadgetbridge BIG_DATA_TYPE_*; Puxtril "Big Data": Blood Oxygen 42).
    const val BIG_DATA_TEMPERATURE = 0x25
    const val BIG_DATA_SPO2 = 0x2A

    /** The top bit of a reply's command says the ring couldn't do it (Puxtril "Commands"; tahnok's client checks it too). */
    const val ERROR_FLAG = 0x80

    const val PACKET_SIZE = 16

    /** Names rings advertise themselves with. */
    private val NAME_PREFIXES = listOf("R0", "R1", "COLMI", "QRING", "Q RING", "RING")

    fun looksLikeRing(name: String?): Boolean {
        val n = name?.trim()?.uppercase() ?: return false
        return NAME_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * Whether the ring keeps skin temperature: only the models Gadgetbridge knows do (its
     * ColmiR09Coordinator, "R09_…", and YawellR10Coordinator, "R10_" and four hex digits).
     * Other rings aren't asked: nothing public says how they'd answer.
     */
    fun measuresTemperature(name: String?): Boolean {
        val n = name?.trim()?.uppercase() ?: return false
        return n.startsWith("R09_") || Regex("^R10_[0-9A-F]{4}$").matches(n)
    }

    /** Whether [packet] is the ring saying it couldn't do [command] (the reply's command with [ERROR_FLAG] set). */
    fun isRefusal(packet: ByteArray, command: Int): Boolean =
        packet.isNotEmpty() && (packet[0].toInt() and 0xFF) == (command or ERROR_FLAG)

    fun checksum(bytes: ByteArray, upTo: Int = PACKET_SIZE - 1): Int {
        var sum = 0
        for (i in 0 until minOf(upTo, bytes.size)) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    /** A command packet: [command], [data] padded with zeros, and the checksum. */
    fun packet(command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= PACKET_SIZE - 2) { "At most ${PACKET_SIZE - 2} bytes of data" }
        val p = ByteArray(PACKET_SIZE)
        p[0] = command.toByte()
        data.copyInto(p, 1)
        p[PACKET_SIZE - 1] = checksum(p).toByte()
        return p
    }

    fun isValid(packet: ByteArray): Boolean =
        packet.size == PACKET_SIZE && (packet[PACKET_SIZE - 1].toInt() and 0xFF) == checksum(packet)

    fun command(packet: ByteArray): Int = if (packet.isEmpty()) -1 else packet[0].toInt() and 0xFF

    fun toBcd(value: Int): Byte = (((value / 10) shl 4) or (value % 10)).toByte()

    fun fromBcd(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }

    /** Sets the ring's clock to [time] (the phone's local time, as the QRing app does), in English. */
    fun setTime(time: LocalDateTime): ByteArray = packet(
        CMD_SET_TIME,
        byteArrayOf(
            toBcd(time.year % 100), toBcd(time.monthValue), toBcd(time.dayOfMonth),
            toBcd(time.hour), toBcd(time.minute), toBcd(time.second), 1,
        ),
    )

    fun battery(): ByteArray = packet(CMD_BATTERY)

    data class Battery(val level: Int, val charging: Boolean)

    fun parseBattery(packet: ByteArray): Battery? {
        if (command(packet) != CMD_BATTERY || packet.size < 3) return null
        val level = packet[1].toInt() and 0xFF
        return if (level in 0..100) Battery(level, packet[2].toInt() != 0) else null
    }

    /**
     * Asks for [date]'s heart-rate log. The ring keeps local wall-clock time, so the day is named
     * by its midnight written as if it were UTC.
     */
    fun heartRateLog(date: LocalDate): ByteArray {
        val seconds = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return packet(CMD_HEART_RATE_LOG, littleEndian32(seconds))
    }

    fun readHeartRateSettings(): ByteArray = packet(CMD_HEART_RATE_SETTINGS, byteArrayOf(1))

    /** Turns the ring's own heart-rate logging on (every [intervalMinutes]) or off. */
    fun writeHeartRateSettings(enabled: Boolean, intervalMinutes: Int): ByteArray =
        packet(CMD_HEART_RATE_SETTINGS, byteArrayOf(2, if (enabled) 1 else 2, intervalMinutes.coerceIn(5, 60).toByte()))

    /** Asks for the activity (steps, calories, distance) of the day [dayOffset] days ago. */
    fun steps(dayOffset: Int): ByteArray =
        packet(CMD_STEPS, byteArrayOf(dayOffset.coerceIn(0, 29).toByte(), 0x0F, 0x00, 0x5F, 0x01))

    /** Asks the big-data service for the sleep it holds (the last week or so). */
    fun sleepRequest(): ByteArray = byteArrayOf(CMD_BIG_DATA.toByte(), BIG_DATA_SLEEP.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte())

    // ---- Blood oxygen, stress, HRV and skin temperature ----

    /** Has the ring measure blood oxygen by itself (Gadgetbridge: [0x2C, 2, on]); it keeps each hour's lowest and highest. */
    fun writeSpo2Settings(enabled: Boolean): ByteArray = packet(CMD_SPO2_SETTINGS, byteArrayOf(ACTION_WRITE.toByte(), if (enabled) 1 else 0))

    /** Has the ring rate stress by itself (Gadgetbridge: [0x36, 2, on]); it keeps a reading each half hour. */
    fun writeStressSettings(enabled: Boolean): ByteArray = packet(CMD_STRESS_SETTINGS, byteArrayOf(ACTION_WRITE.toByte(), if (enabled) 1 else 0))

    /** Has the ring measure HRV by itself (Gadgetbridge: [0x38, 2, on]); it keeps a reading each half hour. */
    fun writeHrvSettings(enabled: Boolean): ByteArray = packet(CMD_HRV_SETTINGS, byteArrayOf(ACTION_WRITE.toByte(), if (enabled) 1 else 0))

    /** Has the ring measure skin temperature by itself (Gadgetbridge: [0x3A, 3, 2, on]); it keeps a reading each half hour. */
    fun writeTemperatureSettings(enabled: Boolean): ByteArray =
        packet(CMD_TEMPERATURE_SETTINGS, byteArrayOf(0x03, ACTION_WRITE.toByte(), if (enabled) 1 else 0))

    /**
     * Asks for today's stress log. Gadgetbridge sends the bare command and reads the reply as
     * today's; nothing public says how to ask for another day, so only today is asked for.
     */
    fun stressLog(): ByteArray = packet(CMD_STRESS_LOG)

    /** Asks for the HRV log of the day [daysAgo] days ago (0–6), as a little-endian number (Gadgetbridge fetchHistoryHRV). */
    fun hrvLog(daysAgo: Int): ByteArray = packet(CMD_HRV_LOG, littleEndian32(daysAgo.coerceIn(0, 6).toLong()))

    /**
     * A big-data request: [0xBC, type, payload length (LE), CRC-16/MODBUS of the payload (LE),
     * payload] (Gadgetbridge buildBigDataV2Packet). Gadgetbridge's fixed requests for sleep and
     * blood oxygen (payload 0xFF) and temperature (payload 0x02) are exactly this.
     */
    fun bigDataRequest(type: Int, payload: ByteArray): ByteArray {
        val crc = crc16Modbus(payload)
        return byteArrayOf(
            CMD_BIG_DATA.toByte(), type.toByte(),
            (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte(),
        ) + payload
    }

    /** Asks for the blood oxygen the ring holds: each hour's lowest and highest, for the last week or so. */
    fun spo2Request(): ByteArray = bigDataRequest(BIG_DATA_SPO2, byteArrayOf(0xFF.toByte()))

    /** Asks for the skin temperature the ring holds (Gadgetbridge fetchTemperature: BC 25 01 00 3E 81 02). */
    fun temperatureRequest(): ByteArray = bigDataRequest(BIG_DATA_TEMPERATURE, byteArrayOf(0x02))

    /** CRC-16/MODBUS (reflected 0xA001, starting at 0xFFFF), as Gadgetbridge's crc16Modbus. */
    fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    fun littleEndian32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    fun uint16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    fun uint32(bytes: ByteArray, at: Int): Long =
        (uint16(bytes, at).toLong()) or (uint16(bytes, at + 2).toLong() shl 16)
}

/** A day's heart-rate log: a reading every [intervalMinutes] from midnight (0 = none taken). */
data class HeartRateLog(val intervalMinutes: Int, val values: List<Int>)

/** Reads the replies to a heart-rate log request, packet by packet. */
class HeartRateLogParser {
    private var size = 0
    private var interval = 5
    private val values = ArrayList<Int>()
    var noData = false
        private set

    /** Takes one reply; true once the log is complete (or the ring has none). */
    fun feed(packet: ByteArray): Boolean {
        if (ColmiProtocol.command(packet) != ColmiProtocol.CMD_HEART_RATE_LOG || packet.size < ColmiProtocol.PACKET_SIZE) return false
        val subType = packet[1].toInt() and 0xFF
        when {
            subType == 0xFF -> {
                noData = true
                return true
            }
            subType == 0 -> {
                size = packet[2].toInt() and 0xFF
                interval = (packet[3].toInt() and 0xFF).takeIf { it in 1..60 } ?: 5
                if (size <= 1) {
                    noData = true
                    return true
                }
            }
            subType == 1 -> for (i in 6..14) values += packet[i].toInt() and 0xFF
            else -> for (i in 2..14) values += packet[i].toInt() and 0xFF
        }
        return size > 0 && subType == size - 1
    }

    fun result(): HeartRateLog? = if (noData || values.isEmpty()) null else HeartRateLog(interval, values.toList())
}

/** Activity in one quarter hour ([quarter] 0–95 from midnight) of [date]. */
data class StepSlot(val date: LocalDate, val quarter: Int, val steps: Int, val calories: Double, val distanceM: Int)

/** Reads the replies to a steps request. */
class StepsParser {
    private var newCalories = false
    private var first = true
    val slots = ArrayList<StepSlot>()
    var noData = false
        private set

    fun feed(packet: ByteArray): Boolean {
        if (ColmiProtocol.command(packet) != ColmiProtocol.CMD_STEPS || packet.size < ColmiProtocol.PACKET_SIZE) return false
        val marker = packet[1].toInt() and 0xFF
        if (first && marker == 0xFF) {
            noData = true
            return true
        }
        if (first && marker == 0xF0) {
            newCalories = (packet[3].toInt() and 0xFF) == 1
            first = false
            return false
        }
        first = false
        val date = runCatching {
            LocalDate.of(2000 + ColmiProtocol.fromBcd(packet[1]), ColmiProtocol.fromBcd(packet[2]), ColmiProtocol.fromBcd(packet[3]))
        }.getOrNull()
        val quarter = packet[4].toInt() and 0xFF
        val index = packet[5].toInt() and 0xFF
        val count = packet[6].toInt() and 0xFF
        val rawCalories = ColmiProtocol.uint16(packet, 7) * (if (newCalories) 10 else 1)
        val steps = ColmiProtocol.uint16(packet, 9)
        val distance = ColmiProtocol.uint16(packet, 11)
        if (date != null && quarter in 0..95) slots += StepSlot(date, quarter, steps, rawCalories / 1000.0, distance)
        return count == 0 || index >= count - 1
    }
}

/**
 * Joins the big-data service's notifications into one reply: [0xBC, type, length (LE),
 * CRC (2 bytes), then length bytes].
 */
class BigDataAssembler(private val type: Int) {
    private var buffer = ByteArray(0)
    private var expected = -1

    /** Whether the reply's first part has come. */
    val started: Boolean get() = expected >= 0

    /** Takes one notification; the whole reply once it's all there, else null. */
    fun feed(bytes: ByteArray): ByteArray? {
        if (expected < 0) {
            if (bytes.size < 6 || (bytes[0].toInt() and 0xFF) != ColmiProtocol.CMD_BIG_DATA || (bytes[1].toInt() and 0xFF) != type) return null
            expected = 6 + ColmiProtocol.uint16(bytes, 2)
            buffer = bytes
        } else {
            buffer += bytes
        }
        return if (buffer.size >= expected) buffer.copyOf(expected) else null
    }
}

enum class RingStage { LIGHT, DEEP, REM, AWAKE }

/** A night as the ring recorded it: from [start] to [end] (wall-clock), in [stages] of so many minutes. */
data class RingNight(val start: LocalDateTime, val end: LocalDateTime, val stages: List<Pair<RingStage, Int>>)

object ColmiSleep {
    fun stage(code: Int): RingStage? = when (code) {
        2 -> RingStage.LIGHT
        3 -> RingStage.DEEP
        4 -> RingStage.REM
        5 -> RingStage.AWAKE
        else -> null
    }

    /**
     * The nights in a sleep reply. Each day: how many days ago it ended, its length in bytes,
     * the start and end (minutes after midnight; a start later than the end was the evening
     * before), then (stage, minutes) pairs.
     */
    fun parse(reply: ByteArray, today: LocalDate): List<RingNight> {
        if (reply.size < 7) return emptyList()
        val length = ColmiProtocol.uint16(reply, 2)
        if (length < 2) return emptyList()
        val days = reply[6].toInt() and 0xFF
        val nights = ArrayList<RingNight>()
        var i = 7
        repeat(days) {
            if (i + 6 > reply.size) return nights
            val daysAgo = reply[i].toInt() and 0xFF
            val dayBytes = reply[i + 1].toInt() and 0xFF
            val startMinutes = ColmiProtocol.uint16(reply, i + 2)
            val endMinutes = ColmiProtocol.uint16(reply, i + 4)
            i += 6
            val day = today.minusDays(daysAgo.toLong())
            val start = if (startMinutes > endMinutes) {
                day.minusDays(1).atStartOfDay().plusMinutes(startMinutes.toLong())
            } else {
                day.atStartOfDay().plusMinutes(startMinutes.toLong())
            }
            val end = day.atStartOfDay().plusMinutes(endMinutes.toLong())
            val stages = ArrayList<Pair<RingStage, Int>>()
            var j = 4
            while (j < dayBytes && i + 1 < reply.size) {
                val stage = stage(reply[i].toInt() and 0xFF)
                val minutes = reply[i + 1].toInt() and 0xFF
                if (stage != null && minutes > 0) stages += stage to minutes
                i += 2
                j += 2
            }
            if (end.isAfter(start)) nights += RingNight(start, end, stages)
        }
        return nights
    }
}

/** A day's half-hourly log (stress or HRV): [values] by half hour from midnight, 0 where none was taken. */
data class HalfHourLog(val values: List<Int>) {
    companion object {
        const val SLOTS = 48
        const val MINUTES = 30
    }
}

/**
 * Reads the replies to a stress or HRV log request, packet by packet, as Gadgetbridge's
 * historicalStress and historicalHRV do (and Puxtril's "Pressure" and "HRV"): packet 0 is a header
 * whose byte 2 says how many packets there are, and an index of 0xFF means there's nothing; packet
 * 1 carries 12 readings from byte 3 and each later one 13 from byte 2, a reading every 30 minutes
 * from midnight. Gadgetbridge stops after packet 4 (enough for 48 half hours); a header that says
 * how many there are is followed instead. Readings past midnight, and packets the header didn't
 * promise, are left out.
 */
class HalfHourLogParser(private val command: Int) {
    private val values = IntArray(HalfHourLog.SLOTS)
    private var total = 0
    private var any = false
    var noData = false
        private set

    private val last: Int get() = if (total in 2..MAX_PACKETS) total - 1 else GADGETBRIDGE_LAST

    /** Takes one reply; true once the log is complete (or the ring has none). */
    fun feed(packet: ByteArray): Boolean {
        if (ColmiProtocol.command(packet) != command || packet.size < ColmiProtocol.PACKET_SIZE) return false
        val index = packet[1].toInt() and 0xFF
        if (index == 0xFF) {
            noData = true
            return true
        }
        if (index == 0) {
            total = packet[2].toInt() and 0xFF
            // A header alone (as with the heart-rate log): nothing follows.
            if (total <= 1) noData = true
            return noData
        }
        if (index > last) return false
        val first = if (index == 1) 3 else 2
        val before = if (index == 1) 0 else FIRST_PACKET + (index - 2) * LATER_PACKET
        for (i in first until ColmiProtocol.PACKET_SIZE - 1) {
            val slot = before + i - first
            if (slot >= HalfHourLog.SLOTS) break
            val value = packet[i].toInt() and 0xFF
            values[slot] = value
            if (value != 0) any = true
        }
        return index >= last
    }

    fun result(): HalfHourLog? = if (noData || !any) null else HalfHourLog(values.toList())

    private companion object {
        const val FIRST_PACKET = 12
        const val LATER_PACKET = 13
        const val GADGETBRIDGE_LAST = 4
        const val MAX_PACKETS = 8
    }
}

/** One hour of blood oxygen as the ring kept it: its lowest and highest, in %. */
data class Spo2Hour(val date: LocalDate, val hour: Int, val min: Int, val max: Int)

/** A skin-temperature reading, in °C, at [time] (wall-clock). */
data class TemperatureReading(val time: LocalDateTime, val celsius: Double)

/** Where a big-data reply's data is: after [0xBC, type, length (LE), CRC (2 bytes)]; null if it isn't one of [type]. */
private fun bigDataBody(reply: ByteArray, type: Int): IntRange? {
    if (reply.size < 6 || (reply[0].toInt() and 0xFF) != ColmiProtocol.CMD_BIG_DATA || (reply[1].toInt() and 0xFF) != type) return null
    val end = minOf(reply.size, 6 + ColmiProtocol.uint16(reply, 2))
    return if (end > 6) 6 until end else null
}

/** How far back a day in a big-data reply can be: rings keep about a week; more is a garbled reply. */
private const val MAX_DAYS_AGO = 31

object ColmiSpo2 {
    /**
     * The hours in a blood-oxygen reply, as Gadgetbridge's historicalSpo2 reads it: for each day,
     * how many days ago it was, then 24 (lowest, highest) pairs from midnight; the days run up to
     * today (0 days ago). Hours with no reading are zeros. (Puxtril's notes show an unknown byte
     * before the day; Gadgetbridge's reading is the one rings are synced with, so it's used.) A
     * reply cut short gives the hours that arrived whole.
     */
    fun parse(reply: ByteArray, today: LocalDate): List<Spo2Hour> {
        val body = bigDataBody(reply, ColmiProtocol.BIG_DATA_SPO2) ?: return emptyList()
        val hours = ArrayList<Spo2Hour>()
        var i = body.first
        while (i <= body.last) {
            val daysAgo = reply[i].toInt() and 0xFF
            if (daysAgo > MAX_DAYS_AGO) break
            i++
            val date = today.minusDays(daysAgo.toLong())
            for (hour in 0..23) {
                if (i + 1 > body.last) return hours
                val min = reply[i].toInt() and 0xFF
                val max = reply[i + 1].toInt() and 0xFF
                i += 2
                if (min in 1..100 && max in 1..100) hours += Spo2Hour(date, hour, minOf(min, max), maxOf(min, max))
            }
            if (daysAgo == 0) break
        }
        return hours
    }
}

object ColmiTemperature {
    /** A day's part of a reply: its number, a byte, then 24 pairs. */
    private const val DAY_BYTES = 50

    /**
     * The readings in a skin-temperature reply, as Gadgetbridge's historicalTemperature reads
     * them: a reply shorter than one day holds nothing; each day is how many days ago it was, a
     * byte Gadgetbridge skips (so far always 0x1E), then 24 pairs, on the hour and at half past,
     * each in tenths of a degree above 20 °C (0 for none). The days run up to today. A reply cut
     * short gives the readings that arrived.
     */
    fun parse(reply: ByteArray, today: LocalDate): List<TemperatureReading> {
        val body = bigDataBody(reply, ColmiProtocol.BIG_DATA_TEMPERATURE) ?: return emptyList()
        if (ColmiProtocol.uint16(reply, 2) < DAY_BYTES) return emptyList()
        val readings = ArrayList<TemperatureReading>()
        var i = body.first
        while (i + 1 <= body.last) {
            val daysAgo = reply[i].toInt() and 0xFF
            if (daysAgo > MAX_DAYS_AGO) break
            i += 2
            val midnight = today.minusDays(daysAgo.toLong()).atStartOfDay()
            for (half in 0 until HalfHourLog.SLOTS) {
                if (i > body.last) return readings
                val raw = reply[i].toInt() and 0xFF
                i++
                // raw / 10 + 20, in one division so 152 reads as exactly 35.2.
                if (raw > 0) readings += TemperatureReading(midnight.plusMinutes(half * 30L), (raw + 200) / 10.0)
            }
            if (daysAgo == 0) break
        }
        return readings
    }
}
