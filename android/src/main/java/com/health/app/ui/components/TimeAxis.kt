package com.health.app.ui.components

import com.health.api.Point
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * The arithmetic behind charts drawn against the clock (Charts.kt's TimeChart, TimeBars and
 * TimeAxisLabels): one time axis several charts share, so a night lines up from one to the next,
 * and a vertical scale on round numbers. Pure, so it's tested on its own.
 */

/** A moment marked under a time chart, with its label ("6 AM", "Midnight"). */
data class TimeTick(val at: Long, val label: String)

/** One time axis, [from] to [to] across a chart's width, marked at [ticks]. */
data class TimeAxis(val from: Long, val to: Long, val ticks: List<TimeTick> = emptyList()) {
    /** Where [at] falls: 0 at [from], 1 at [to] (beyond them outside the window). */
    fun fraction(at: Long): Float = ((at - from).toDouble() / (to - from).coerceAtLeast(1)).toFloat()

    companion object {
        /**
         * [from] to [to], marked on the hours of the day that divide by [everyHours] (midnight,
         * 6 AM, noon and 6 PM by default) in [zone], labelled with [TimeLabels.hour].
         */
        fun of(from: Long, to: Long, zone: ZoneId, everyHours: Int = 6, locale: Locale = Locale.getDefault()): TimeAxis {
            val ticks = ArrayList<TimeTick>()
            var t = Instant.ofEpochMilli(from).atZone(zone).truncatedTo(ChronoUnit.HOURS)
            while (t.toInstant().toEpochMilli() <= to) {
                val at = t.toInstant().toEpochMilli()
                // A clock that goes back over one of these hours shows it once.
                if (at >= from && t.hour % everyHours == 0 && ticks.none { abs(it.at - at) <= 3_600_000L }) ticks += TimeTick(at, TimeLabels.hour(at, zone, locale))
                t = t.plusHours(1)
            }
            return TimeAxis(from, to, ticks)
        }
    }
}

/** Times of day for chart axes, the way the app shows times. */
object TimeLabels {
    /** "Midnight", "Noon", else the hour as the person's clock shows it: "6 AM" (or "06:00" on a 24-hour clock). */
    fun hour(at: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        return when {
            time.hour == 0 && time.minute == 0 -> "Midnight"
            time.hour == 12 && time.minute == 0 -> "Noon"
            twelveHour(locale) -> DateTimeFormatter.ofPattern(if (time.minute == 0) "h a" else "h:mm a", locale).format(time)
            else -> DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
        }
    }

    /** Whether [locale] tells the time with AM and PM, as Format.timeOfDay then does. */
    fun twelveHour(locale: Locale = Locale.getDefault()): Boolean =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale).contains('a')
}

/** A chart's vertical range, [low] to [high], on round numbers [step] apart. */
data class ChartScale(val low: Double, val high: Double, val step: Double) {
    /** Where [value] falls: 0 at [low], 1 at [high]. */
    fun fraction(value: Double): Float = ((value - low) / (high - low).coerceAtLeast(1e-9)).toFloat()

    /** A value on this scale as its label: whole numbers, or one decimal when the steps are smaller than one; "−0.5" below zero. */
    fun label(value: Double): String {
        val text = if (step < 1) String.format(Locale.getDefault(), "%.1f", abs(value)) else abs(value).roundToLong().toString()
        return if (value < 0 && text.any { it in '1'..'9' }) "−$text" else text
    }

    companion object {
        private val STEPS = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /**
         * Round numbers around [min]..[max] (heart rate 52–118 is 50–120): a step of 1, 2, 2.5 or 5
         * times a power of ten, at most about eight steps from end to end. [bottom] and [top] fix
         * an end (0 for counts, 100 for blood oxygen); readings past them still fit.
         */
        fun nice(min: Double, max: Double, bottom: Double? = null, top: Double? = null): ChartScale {
            val fixedLow = bottom != null && bottom <= min
            val fixedHigh = top != null && top >= max
            var lo = if (fixedLow) bottom!! else min
            var hi = if (fixedHigh) top!! else max
            if (hi - lo < 1e-9) {
                // One value (or none but a floor): a little room around it.
                val room = if (abs(lo) >= 10) 5.0 else 1.0
                if (!fixedLow) lo -= room
                if (!fixedHigh || hi - lo < 1e-9) hi += room
            }
            val step = step((hi - lo) / 8)
            val low = if (fixedLow) lo else floor(lo / step + 1e-9) * step
            val high = if (fixedHigh) hi else ceil(hi / step - 1e-9) * step
            return ChartScale(low, if (high > low) high else low + step, step)
        }

        /** The smallest round step at least [raw]. */
        fun step(raw: Double): Double {
            if (raw <= 0 || raw.isNaN()) return 1.0
            val magnitude = 10.0.pow(floor(log10(raw)))
            return STEPS.map { it * magnitude }.first { it >= raw - 1e-12 }
        }
    }
}

/**
 * Readings split where they stop for longer than [gap] (a ring off its finger, a phone left
 * behind), so a line isn't drawn across the time between.
 */
fun timeRuns(points: List<Point>, gap: Long): List<List<Point>> {
    val runs = ArrayList<List<Point>>()
    var run = ArrayList<Point>()
    points.sortedBy { it.t }.forEach { p ->
        if (run.isNotEmpty() && p.t - run.last().t > gap) {
            runs += run
            run = ArrayList()
        }
        run += p
    }
    if (run.isNotEmpty()) runs += run
    return runs
}
