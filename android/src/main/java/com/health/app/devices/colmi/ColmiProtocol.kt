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

    const val PACKET_SIZE = 16

    /** Names rings advertise themselves with. */
    private val NAME_PREFIXES = listOf("R0", "R1", "COLMI", "QRING", "Q RING", "RING")

    fun looksLikeRing(name: String?): Boolean {
        val n = name?.trim()?.uppercase() ?: return false
        return NAME_PREFIXES.any { n.startsWith(it) }
    }

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
