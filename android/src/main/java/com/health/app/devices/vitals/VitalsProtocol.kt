package com.health.app.devices.vitals

import com.health.api.GlucoseContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Bluetooth SIG's Blood Pressure and Glucose profiles, as monitors and meters from Omron,
 * A&D, Beurer, Contour, Accu-Chek, OneTouch and others speak them: the bytes of a reading in,
 * readings out. Pure, so it's tested without a device.
 */
object VitalsProtocol {
    private fun uuid16(short: Int): UUID = UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

    val BP_SERVICE: UUID = uuid16(0x1810)
    val BP_MEASUREMENT: UUID = uuid16(0x2A35)
    val GLUCOSE_SERVICE: UUID = uuid16(0x1808)
    val GLUCOSE_MEASUREMENT: UUID = uuid16(0x2A18)
    val GLUCOSE_CONTEXT: UUID = uuid16(0x2A34)
    val RACP: UUID = uuid16(0x2A52)

    const val MMHG_PER_KPA = 7.50061683
    const val MG_PER_MMOL = 18.0182

    /** An IEEE 11073 16-bit float: a 4-bit exponent and a 12-bit mantissa, both signed. Null for its special values. */
    fun sfloat(lo: Int, hi: Int): Double? {
        val raw = (lo and 0xFF) or ((hi and 0xFF) shl 8)
        var mantissa = raw and 0x0FFF
        var exponent = raw shr 12
        // NaN, not at this resolution, +infinity, reserved, -infinity.
        if (mantissa in SPECIAL) return null
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 0x8) exponent -= 0x10
        return mantissa * 10.0.pow(exponent)
    }

    private val SPECIAL = setOf(0x07FF, 0x0800, 0x07FE, 0x0801, 0x0802)

    /** A date and time as the profiles send it (year 0 means the device doesn't know). */
    fun dateTime(b: ByteArray, at: Int): LocalDateTime? {
        if (b.size < at + 7) return null
        val year = u16(b, at)
        val month = b[at + 2].toInt() and 0xFF
        val day = b[at + 3].toInt() and 0xFF
        if (year == 0 || month == 0 || day == 0) return null
        return runCatching {
            LocalDateTime.of(year, month, day, b[at + 4].toInt() and 0xFF, b[at + 5].toInt() and 0xFF, b[at + 6].toInt() and 0xFF)
        }.getOrNull()
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, at: Int) = u16(b, at).let { if (it >= 0x8000) it - 0x10000 else it }

    data class BpReading(val systolic: Int, val diastolic: Int, val pulse: Int?, val time: LocalDateTime?, val user: Int?)

    /** A Blood Pressure Measurement (0x2A35); null if it can't be read. */
    fun bloodPressure(b: ByteArray): BpReading? {
        if (b.size < 7) return null
        val flags = b[0].toInt()
        val kpa = flags and 0x01 != 0
        var at = 7
        fun mmhg(v: Double?) = v?.let { if (kpa) it * MMHG_PER_KPA else it }?.roundToInt()
        val systolic = mmhg(sfloat(b[1].toInt(), b[2].toInt())) ?: return null
        val diastolic = mmhg(sfloat(b[3].toInt(), b[4].toInt())) ?: return null
        var time: LocalDateTime? = null
        if (flags and 0x02 != 0) {
            time = dateTime(b, at)
            at += 7
        }
        var pulse: Int? = null
        if (flags and 0x04 != 0 && b.size >= at + 2) {
            pulse = sfloat(b[at].toInt(), b[at + 1].toInt())?.roundToInt()
            at += 2
        }
        val user = if (flags and 0x08 != 0 && b.size > at) (b[at].toInt() and 0xFF).takeIf { it != 0xFF } else null
        if (systolic !in 40..300 || diastolic !in 20..250) return null
        return BpReading(systolic, diastolic, pulse?.takeIf { it in 20..250 }, time, user)
    }

    data class GlucoseRecord(val sequence: Int, val time: LocalDateTime?, val mmolL: Double?, val hasContext: Boolean)

    /** A Glucose Measurement (0x2A18); [GlucoseRecord.mmolL] is null for a record without a value. */
    fun glucose(b: ByteArray): GlucoseRecord? {
        if (b.size < 10) return null
        val flags = b[0].toInt()
        val sequence = u16(b, 1)
        var time = dateTime(b, 3)
        var at = 10
        if (flags and 0x01 != 0 && b.size >= at + 2) {
            time = time?.plusMinutes(s16(b, at).toLong())
            at += 2
        }
        var mmol: Double? = null
        if (flags and 0x02 != 0 && b.size >= at + 3) {
            val value = sfloat(b[at].toInt(), b[at + 1].toInt())
            // kg/L (×100,000 is mg/dL) or mol/L (×1,000 is mmol/L).
            mmol = value?.let { if (flags and 0x04 != 0) it * 1000 else it * 100_000 / MG_PER_MMOL }
        }
        return GlucoseRecord(sequence, time, mmol?.takeIf { it > 0.5 && it < 50 }, flags and 0x10 != 0)
    }

    /** From a Glucose Measurement Context (0x2A34): the record it belongs to and when it was taken, if it says. */
    fun glucoseContext(b: ByteArray): Pair<Int, GlucoseContext?>? {
        if (b.size < 3) return null
        val flags = b[0].toInt()
        val sequence = u16(b, 1)
        var at = 3
        if (flags and 0x80 != 0) at += 1
        if (flags and 0x01 != 0) at += 3
        val meal = if (flags and 0x02 != 0 && b.size > at) b[at].toInt() and 0xFF else null
        return sequence to when (meal) {
            1 -> GlucoseContext.BEFORE_MEAL
            2 -> GlucoseContext.AFTER_MEAL
            3 -> GlucoseContext.FASTING
            5 -> GlucoseContext.BEDTIME
            4 -> GlucoseContext.OTHER
            else -> null
        }
    }

    /** Asks the meter for every record ([after] null), or the ones after a sequence number. */
    fun reportRecords(after: Int?): ByteArray =
        if (after == null) {
            byteArrayOf(0x01, 0x01)
        } else {
            val from = (after + 1).coerceAtMost(0xFFFF)
            byteArrayOf(0x01, 0x03, 0x01, (from and 0xFF).toByte(), (from shr 8).toByte())
        }

    /** A Record Access Control Point reply: null if it isn't one, else whether it went well ("no records" counts). */
    fun racpDone(b: ByteArray): Boolean? {
        if (b.size < 4 || b[0].toInt() != 0x06) return null
        val code = b[3].toInt() and 0xFF
        return code == 0x01 || code == 0x06
    }

    /** A device's local time as an instant; readings without one (or from the future) get [fallback]. */
    fun epoch(time: LocalDateTime?, zone: ZoneId, fallback: Long): Long =
        time?.atZone(zone)?.toInstant()?.toEpochMilli()?.takeIf { it <= fallback + 5 * 60_000L } ?: fallback
}
