package com.health.app.ui.body

import com.health.api.CompareKey
import com.health.api.DeviceComparison
import com.health.api.Metric
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.api.SleepSession
import com.health.api.TrendDay
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.devices.Compare
import com.health.app.ui.recent.Recent
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Body: what the devices measured, over 24 hours, a week, a month or a year. What its cards say,
 * worked out without drawing anything so it can be tested; BodyScreen.kt draws it. The 24 hours
 * are the last 24 hours (GET /api/recent, the person's own only); the rest are the trends.
 */

/** Body's time spans, as its switch names them; [days] is how many days of trends each covers. */
enum class BodySpan(val label: String, val days: Int) {
    DAY("24 hours", 1),
    WEEK("Week", 7),
    MONTH("Month", 30),
    YEAR("Year", 365),
}

object BodyText {
    // ---- The night ----

    /** The night to show at the top: the longest one in the window (the others, naps, are listed under it). */
    fun mainNight(readings: RecentReadings): SleepSession? = Recent.nights(readings.sleep).maxByOrNull { it.end - it.start }

    /** Each stage's time as a small tile: deep, REM, light and awake; or asleep and awake when the device didn't tell them apart. */
    fun stageTiles(t: Recent.SleepTotals): List<Pair<String, String>> =
        if (t.staged) listOf("Deep" to Format.minutes(t.deep), "REM" to Format.minutes(t.rem), "Light" to Format.minutes(t.light), "Awake" to Format.minutes(t.awake))
        else listOfNotNull("Asleep" to Format.minutes(t.asleep), ("Awake" to Format.minutes(t.awake)).takeIf { t.awake > 0 })

    // ---- Where the readings came from ----

    /**
     * Whose readings these are, under the heart card: the chosen device's name, both names when two
     * recorded ("Colmi R02 and Polar H10 together"), how many with more; null with one device.
     */
    fun sources(readings: RecentReadings): String? {
        val names = readings.devices.map { it.name }
        readings.device?.let { id -> return readings.devices.firstOrNull { it.id == id }?.name }
        return when (names.size) {
            0, 1 -> null
            2 -> "${names[0]} and ${names[1]} together"
            else -> "${names.size} devices together"
        }
    }

    // ---- Against the person's usual ----

    /**
     * The person's usual for a reading: the middle value of the days before [today] that have one
     * (at least three of them); null without enough.
     */
    fun usual(days: List<TrendDay>, today: String, value: (TrendDay) -> Double?): Double? {
        val values = days.filter { it.date < today }.mapNotNull(value).sorted()
        if (values.size < 3) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }

    /** How [value] sits against [usual]: "22% below your usual 49", "about your usual 49", "8% above your usual 49". */
    fun againstUsual(value: Double, usual: Double, unit: String = ""): String {
        val pct = ((value - usual) / usual * 100).roundToInt()
        val u = "${usual.roundToInt()}$unit"
        return when {
            abs(pct) < 5 -> "about your usual $u"
            pct < 0 -> "${-pct}% below your usual $u"
            else -> "$pct% above your usual $u"
        }
    }

    /** Whether a reading is far enough below the person's usual to say so in the warning colour (a tenth). */
    fun wellBelow(value: Double, usual: Double): Boolean = value < usual * 0.9

    /** "lowest 92% at 3:10 AM": the lowest reading and when. */
    fun lowestAt(series: RecentSeries, units: Units, zone: ZoneId): String? {
        val metric = series.metric ?: return null
        val low = series.points.minByOrNull { it.value } ?: return null
        return "lowest ${Recent.value(metric, low.value, units)} at ${Format.timeOfDay(low.at, zone)}"
    }

    /** "lowest 13.2 · highest 16.8": a reading's range. */
    fun range(series: RecentSeries, units: Units): String? {
        val metric = series.metric ?: return null
        val low = series.min ?: series.points.minOfOrNull { it.value } ?: return null
        val high = series.max ?: series.points.maxOfOrNull { it.value } ?: return null
        if (series.points.size < 2) return null
        return "lowest ${Recent.number(metric, Recent.shown(metric, low, units))} · highest ${Recent.number(metric, Recent.shown(metric, high, units))}"
    }

    /** A reading's average (or its one value), as shown: "38 ms", "96%". */
    fun average(series: RecentSeries, units: Units): String? {
        val metric = series.metric ?: return null
        val v = series.average ?: series.points.map { it.value }.takeIf { it.isNotEmpty() }?.average() ?: return null
        return Recent.value(metric, v, units)
    }

    /** Breathing's average in a tile, short: "14.1 /min". */
    fun breaths(series: RecentSeries): String? {
        val v = series.average ?: series.points.map { it.value }.takeIf { it.isNotEmpty() }?.average() ?: return null
        return "${Recent.number(Metric.RESPIRATORY_RATE, v)} /min"
    }

    /** What a change in temperature from the usual says: warmer, cooler, or about the same (within 0.3 °C). */
    fun warmth(celsius: Double): String = when {
        celsius >= 0.3 -> "warmer than usual"
        celsius <= -0.3 -> "cooler than usual"
        else -> "about your usual"
    }

    // ---- Compare devices ----

    /** The order Compare devices' one line looks for a reading both devices measured. */
    private val COMPARE_FIRST = listOf(CompareKey.NIGHT_HEART_RATE, CompareKey.LOWEST_HEART_RATE, CompareKey.ASLEEP, CompareKey.HRV, CompareKey.DEEP)

    /**
     * Compare devices' one line: how the devices read against each other ("The ring read 3 bpm
     * higher than the strap"), or "Only 1 night together so far" while that's all there is; null
     * with nothing yet.
     */
    fun compareLine(c: DeviceComparison?, units: Units): String? {
        if (c == null) return null
        val a = c.a ?: return null
        val b = c.b ?: return null
        val metric = COMPARE_FIRST.firstNotNullOfOrNull { key -> c.metrics.firstOrNull { it.key == key && it.count > 0 } }
            ?: c.metrics.firstOrNull { it.count > 0 } ?: return null
        if (metric.count < DeviceComparison.ENOUGH) return "Only ${Compare.count(metric.key, metric.count)} together so far"
        return Compare.headline(metric, Compare.names(a, b), units)
    }

    /** The metrics Body's tiles show, by their section ids (android.recent). */
    val TILES = setOf(Recent.HRV, Recent.SPO2, Recent.BREATHING, Recent.TEMPERATURE, Recent.STRESS)

    /**
     * The 24 hours' sections in the person's order, with the small tiles that come one after the
     * other paired two to a row: a list of rows, each one section or two tiles.
     */
    fun rows(ids: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var pending: String? = null
        ids.forEach { id ->
            if (id in TILES) {
                if (pending == null) pending = id else { rows += listOf(pending!!, id); pending = null }
            } else {
                pending?.let { rows += listOf(it); pending = null }
                rows += listOf(id)
            }
        }
        pending?.let { rows += listOf(it) }
        return rows
    }

    /** The unit Body says a metric's average in, for its tile. */
    fun metricOf(id: String): Metric? = Recent.metrics(id).firstOrNull()
}
