package com.health.app.ui

import com.health.api.Units
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Numbers as people read them, in metric or imperial. The server keeps everything metric. */
object Format {
    const val LB_PER_KG = 2.2046226218
    const val M_PER_MILE = 1609.344
    const val CM_PER_INCH = 2.54
    const val ML_PER_FL_OZ = 29.5735

    private fun decimals(value: Double, places: Int): String =
        String.format(Locale.getDefault(), "%.${places}f", value)

    fun number(value: Number): String = NumberFormat.getIntegerInstance().format(value.toDouble().roundToLong())

    // ---- Weight ----

    fun weightUnit(units: Units) = if (units == Units.IMPERIAL) "lb" else "kg"

    fun weightValue(kg: Double, units: Units): Double = if (units == Units.IMPERIAL) kg * LB_PER_KG else kg

    fun weightToKg(value: Double, units: Units): Double = if (units == Units.IMPERIAL) value / LB_PER_KG else value

    fun weight(kg: Double, units: Units): String = "${decimals(weightValue(kg, units), 1)} ${weightUnit(units)}"

    // ---- Distance ----

    fun distance(metres: Double, units: Units): String = if (units == Units.IMPERIAL) {
        val miles = metres / M_PER_MILE
        "${decimals(miles, if (miles < 10) 2 else 1)} mi"
    } else {
        val km = metres / 1000
        "${decimals(km, if (km < 10) 2 else 1)} km"
    }

    fun distanceUnit(units: Units) = if (units == Units.IMPERIAL) "mi" else "km"

    fun distanceValue(metres: Double, units: Units): Double = if (units == Units.IMPERIAL) metres / M_PER_MILE else metres / 1000

    fun distanceToMetres(value: Double, units: Units): Double = if (units == Units.IMPERIAL) value * M_PER_MILE else value * 1000

    // ---- Height ----

    fun height(cm: Double, units: Units): String = if (units == Units.IMPERIAL) {
        val totalInches = (cm / CM_PER_INCH).roundToInt()
        "${totalInches / 12}′${totalInches % 12}″"
    } else {
        "${cm.roundToInt()} cm"
    }

    fun heightToCm(feet: Int, inches: Double): Double = (feet * 12 + inches) * CM_PER_INCH

    // ---- Water ----

    fun water(ml: Int, units: Units): String = if (units == Units.IMPERIAL) {
        "${(ml / ML_PER_FL_OZ).roundToInt()} fl oz"
    } else if (ml >= 1000) {
        "${decimals(ml / 1000.0, 1)} L"
    } else {
        "$ml ml"
    }

    // ---- Temperature (a difference, not a reading) ----

    fun temperatureDelta(celsius: Double, units: Units): String {
        val value = if (units == Units.IMPERIAL) celsius * 1.8 else celsius
        val sign = if (value > 0.049) "+" else if (value < -0.049) "−" else "±"
        return "$sign${decimals(abs(value), 1)} ${if (units == Units.IMPERIAL) "°F" else "°C"}"
    }

    // ---- Time ----

    /** "7h 32m", "45m", "0m". */
    fun minutes(total: Int): String {
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    fun timeOfDay(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** "Today", "Yesterday", "Tomorrow", else "Wed, 12 Nov" (with the year when it isn't this one). */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE, d MMM" else "d MMM yyyy").format(date)
    }

    /** "Wednesday, 12 November". */
    fun longDay(date: LocalDate): String = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(date)

    fun shortDay(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM").format(date)

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(epochMs: Long, now: Long): String {
        val seconds = (now - epochMs) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            seconds < 2 * 86_400 -> "yesterday"
            else -> "${seconds / 86_400} days ago"
        }
    }

    /** A decimal typed by a person ("72,5" or "72.5"), or null. */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    /** Up to two decimals, without trailing zeros: "0.25", "0.5", "1". */
    fun upTo2(value: Double): String =
        decimals(value, 2).trimEnd('0').trimEnd('.', ',')

    /** Up to one decimal, without a trailing ".0": "1.5", "2". */
    fun compact(value: Double): String =
        if (abs(value - value.roundToLong()) < 0.05) value.roundToLong().toString() else decimals(value, 1)
}
