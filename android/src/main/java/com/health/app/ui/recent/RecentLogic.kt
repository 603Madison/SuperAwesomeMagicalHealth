package com.health.app.ui.recent

import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.Point
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ChartScale
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The last 24 hours as the person's devices recorded them (GET /api/recent): what the page shows,
 * worked out without drawing anything, so it can be tested. Body draws it at 24 hours (ui/body).
 */
object Recent {
    /** How far back the page looks. */
    const val HOURS = 24

    // The sections' ids: saved with the person's arrangement (android.recent), so never rename one.
    const val SLEEP = "sleep"
    const val HEART = "heart"
    const val SPO2 = "spo2"
    const val HRV = "hrv"
    const val STRESS = "stress"
    const val BREATHING = "breathing"
    const val TEMPERATURE = "temperature"
    const val STEPS = "steps"

    /** Every section, in the default order. */
    val SECTIONS = listOf(
        ArrangeItem(SLEEP, "Sleep"),
        ArrangeItem(HEART, "Heart rate"),
        ArrangeItem(SPO2, "Blood oxygen"),
        ArrangeItem(HRV, "Heart rate variability"),
        ArrangeItem(STRESS, "Stress"),
        ArrangeItem(BREATHING, "Breathing rate"),
        ArrangeItem(TEMPERATURE, "Skin temperature"),
        ArrangeItem(STEPS, "Steps"),
    )

    /** The readings a section shows. */
    fun metrics(section: String): List<Metric> = when (section) {
        HEART -> listOf(Metric.HEART_RATE)
        SPO2 -> listOf(Metric.SPO2)
        HRV -> listOf(Metric.HRV)
        STRESS -> listOf(Metric.STRESS)
        BREATHING -> listOf(Metric.RESPIRATORY_RATE)
        TEMPERATURE -> listOf(Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION)
        STEPS -> listOf(Metric.STEPS)
        else -> emptyList()
    }

    /** Each kind of reading with something in it, by its metric. A kind this app doesn't know (a newer server's) is left out. */
    fun series(readings: RecentReadings): Map<Metric, RecentSeries> =
        readings.series.filter { it.metric != null && it.points.isNotEmpty() }.associateBy { it.metric!! }

    /** Whether anything at all was recorded in the window. */
    fun hasData(readings: RecentReadings): Boolean = readings.sleep.isNotEmpty() || series(readings).isNotEmpty()

    /** The sections to show (and arrange) now: Sleep whenever anything was recorded, the rest with readings. */
    fun sections(readings: RecentReadings): List<ArrangeItem> {
        if (!hasData(readings)) return emptyList()
        val present = series(readings).keys
        return SECTIONS.filter { it.id == SLEEP || metrics(it.id).any { m -> m in present } }
    }

    // ---- Devices ----

    /** A device to choose; a null [id] is all of them. */
    data class Chip(val id: String?, val name: String)

    const val ALL_DEVICES = "All devices"

    /**
     * The devices to choose from: "All devices", then each with something recorded, and the one
     * chosen if it has nothing ([known] names it). None when there's only one to choose.
     */
    fun chips(readings: RecentReadings, chosen: String?, known: List<Device> = emptyList()): List<Chip> {
        val devices = readings.devices.map { Chip(it.id, it.name) }.toMutableList()
        if (chosen != null && devices.none { it.id == chosen }) devices += Chip(chosen, known.firstOrNull { it.id == chosen }?.name ?: "This device")
        return if (devices.size > 1) listOf(Chip(null, ALL_DEVICES)) + devices else emptyList()
    }

    /**
     * Whether a kind of device records what the page shows (a night, heart rate, steps…), so its
     * row on Devices opens its last 24 hours. Blood pressure monitors, glucose meters and what's
     * entered by hand don't.
     */
    fun records(kind: DeviceKind): Boolean = kind !in setOf(DeviceKind.BP_MONITOR, DeviceKind.GLUCOSE_METER, DeviceKind.MANUAL)

    /** When the chosen device last synced, or for all of them the latest any did; null if none ever has. */
    fun lastSync(readings: RecentReadings, known: List<Device>): Long? {
        val chosen = readings.device
        val synced = known.map { it.id to it.lastSyncAt } + readings.devices.map { it.id to it.lastSyncAt }
        return synced.filter { chosen == null || it.first == chosen }.mapNotNull { it.second }.maxOrNull()
    }

    /** "Last synced 3 h ago". */
    fun lastSyncLine(at: Long?, now: Long): String = if (at == null) "Not synced yet" else "Last synced ${Format.ago(at, now)}"

    // ---- Sleep ----

    /** Whether each night's line names its device: when the nights could come from more than one. */
    fun namesDevices(readings: RecentReadings): Boolean = readings.device == null && readings.devices.size > 1

    /** "11:08 PM – 6:30 AM", with the device's name after it when [withSource]. */
    fun sessionLine(session: SleepSession, zone: ZoneId, withSource: Boolean): String =
        "${Format.timeOfDay(session.start, zone)} – ${Format.timeOfDay(session.end, zone)}" +
            (session.source?.takeIf { withSource && it.isNotBlank() }?.let { " · $it" } ?: "")

    /**
     * The nights to draw and add up: where two devices recorded the same night, the one with more
     * stages (then the longer), so no time is counted twice.
     */
    fun nights(sessions: List<SleepSession>): List<SleepSession> {
        val ranked = sessions.sortedWith(compareByDescending<SleepSession> { it.stages.size }.thenByDescending { it.end - it.start })
        val kept = ArrayList<SleepSession>()
        ranked.forEach { s -> if (kept.none { it.start < s.end && s.start < it.end }) kept += s }
        return kept.sortedBy { it.start }
    }

    /** A night's stages; one recorded without them is asleep from start to end. */
    fun stages(session: SleepSession): List<StageSpan> =
        session.stages.ifEmpty { listOf(StageSpan(SleepStage.ASLEEP, session.start, session.end)) }

    /** The stages of [nights] inside [from]..[to], cut at its edges: the hypnogram. */
    fun stagesIn(nights: List<SleepSession>, from: Long, to: Long): List<StageSpan> = nights.flatMap(::stages).mapNotNull { s ->
        val start = maxOf(s.start, from)
        val end = minOf(s.end, to)
        if (end > start) s.copy(start = start, end = end) else null
    }

    /** The nights in the window, faintly shaded behind every chart. */
    fun shade(readings: RecentReadings): List<Pair<Long, Long>> = nights(readings.sleep).mapNotNull { s ->
        val start = maxOf(s.start, readings.from)
        val end = minOf(s.end, readings.to)
        (start to end).takeIf { end > start }
    }

    /** Minutes asleep, and in each stage ([unknown] is asleep with no stage known). */
    data class SleepTotals(val asleep: Int, val deep: Int, val rem: Int, val light: Int, val awake: Int, val unknown: Int) {
        /** Whether the device told the stages apart. */
        val staged: Boolean get() = deep + rem + light > 0
    }

    /** How long [nights] were asleep, and in each stage, as recorded (all of each night, even past the window). */
    fun totals(nights: List<SleepSession>): SleepTotals {
        val ms = nights.flatMap(::stages).groupBy { it.stage }.mapValues { (_, spans) -> spans.sumOf { (it.end - it.start).coerceAtLeast(0) } }
        fun minutes(millis: Long) = (millis / 60_000.0).roundToInt()
        fun of(stage: SleepStage) = minutes(ms[stage] ?: 0L)
        return SleepTotals(
            asleep = minutes(ms.filterKeys { it != SleepStage.AWAKE }.values.sum()),
            deep = of(SleepStage.DEEP),
            rem = of(SleepStage.REM),
            light = of(SleepStage.LIGHT),
            awake = of(SleepStage.AWAKE),
            unknown = of(SleepStage.ASLEEP),
        )
    }

    /** What the hypnogram says to a screen reader. */
    fun sleepDescription(t: SleepTotals): String =
        if (t.staged) "Sleep stages: deep ${Format.minutes(t.deep)}, REM ${Format.minutes(t.rem)}, light ${Format.minutes(t.light)}, awake ${Format.minutes(t.awake)}"
        else "Asleep ${Format.minutes(t.asleep)}"

    const val NO_SLEEP = "No sleep recorded in the last 24 hours."
    /** The empty page's title ("24 hours" kept together on one line). */
    const val NOTHING = "Nothing recorded in the last 24 hours"

    // ---- Readings ----

    /** °C as the person reads temperatures: °F with imperial units. */
    fun temperature(celsius: Double, units: Units): Double = if (units == Units.IMPERIAL) celsius * 9 / 5 + 32 else celsius

    /** A difference in °C (from the person's usual) in their units: a °F difference is 9/5 as big, with nothing added. */
    fun temperatureChange(celsius: Double, units: Units): Double = if (units == Units.IMPERIAL) celsius * 9 / 5 else celsius

    /** A reading in the person's units (temperatures in °F for imperial); the rest as they come. */
    fun shown(metric: Metric, value: Double, units: Units): Double = when (metric) {
        Metric.SKIN_TEMPERATURE -> temperature(value, units)
        Metric.TEMPERATURE_DEVIATION -> temperatureChange(value, units)
        else -> value
    }

    /** A reading's unit as shown: "bpm", "%", "°F". */
    fun unit(metric: Metric, units: Units): String = when (metric) {
        Metric.HEART_RATE -> "bpm"
        Metric.HRV -> "ms"
        Metric.SPO2 -> "%"
        Metric.STRESS -> ""
        Metric.RESPIRATORY_RATE -> "per min"
        Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION -> if (units == Units.IMPERIAL) "°F" else "°C"
        Metric.STEPS -> "steps"
        else -> metric.unit
    }

    /** A value already in the person's units (see [shown]), as its number alone: "64", "33.4", "+0.3", "4,210". */
    fun number(metric: Metric, shownValue: Double): String = when (metric) {
        Metric.SKIN_TEMPERATURE -> String.format(Locale.getDefault(), "%.1f", shownValue)
        Metric.TEMPERATURE_DEVIATION -> {
            val sign = if (shownValue > 0.049) "+" else if (shownValue < -0.049) "−" else "±"
            sign + String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(shownValue))
        }
        Metric.RESPIRATORY_RATE -> Format.compact(shownValue)
        Metric.STEPS -> Format.number(shownValue)
        else -> shownValue.roundToInt().toString()
    }

    /** A reading (in °C and the like, as the server sends it) with its unit, as shown: "64 bpm", "97%", "91.8 °F", "+0.3 °C". */
    fun value(metric: Metric, value: Double, units: Units): String {
        val n = number(metric, shown(metric, value, units))
        val u = unit(metric, units)
        return when {
            u.isEmpty() -> n
            u == "%" -> "$n%"
            else -> "$n $u"
        }
    }

    /** One figure under a chart: "Lowest" and "52 bpm". */
    data class Stat(val label: String, val value: String)

    /** The figures under a reading's chart (its latest is the headline above it); none for a single reading, which the headline says. */
    fun stats(series: RecentSeries, units: Units): List<Stat> {
        val metric = series.metric ?: return emptyList()
        if (series.points.size < 2) return emptyList()
        fun stat(label: String, v: Double?) = v?.let { Stat(label, value(metric, it, units)) }
        val low = stat("Lowest", series.min)
        val high = stat("Highest", series.max)
        val average = stat("Average", series.average)
        return when (metric) {
            Metric.HEART_RATE, Metric.SKIN_TEMPERATURE, Metric.RESPIRATORY_RATE -> listOfNotNull(low, high, average)
            Metric.SPO2 -> listOfNotNull(low, average)
            Metric.HRV, Metric.TEMPERATURE_DEVIATION -> listOfNotNull(average)
            Metric.STRESS -> listOfNotNull(average, high)
            else -> emptyList()
        }
    }

    /** The latest reading and when: "64 bpm" and "latest at 2:05 PM". */
    fun latest(series: RecentSeries, units: Units, zone: ZoneId): Pair<String, String>? {
        val metric = series.metric ?: return null
        val latest = series.latest ?: series.points.lastOrNull() ?: return null
        return value(metric, latest.value, units) to "latest at ${Format.timeOfDay(latest.at, zone)}"
    }

    /** What a reading's chart says to a screen reader: "Heart rate from 52 to 118 bpm, average 71". */
    fun description(label: String, series: RecentSeries, units: Units): String {
        val metric = series.metric ?: return label
        if (metric == Metric.STEPS) return "Steps by the hour, ${Format.number(series.total ?: series.points.sumOf { it.value })} in all"
        val values = series.points.map { it.value }
        val min = series.min ?: values.minOrNull() ?: return label
        val max = series.max ?: values.maxOrNull() ?: return label
        val average = series.average ?: values.average()
        return "$label from ${number(metric, shown(metric, min, units))} to ${value(metric, max, units)}, average ${number(metric, shown(metric, average, units))}"
    }

    /** A series' points in the person's units, to draw. */
    fun points(series: RecentSeries, units: Units): List<Point> {
        val metric = series.metric ?: return emptyList()
        return series.points.map { Point(it.at, shown(metric, it.value, units)) }
    }

    /**
     * The chart's scale for a reading (its [points] already in [units]), on round numbers: blood
     * oxygen up to 100, stress 0–100, counts from 0, a change around 0. The rest span at least
     * [span], so a breath a minute or a few beats either way doesn't fill the chart.
     */
    fun scale(metric: Metric, points: List<Point>, units: Units = Units.METRIC): ChartScale {
        val values = points.map { it.v }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        return when (metric) {
            Metric.SPO2 -> ChartScale.nice(min, max, bottom = 90.0, top = 100.0)
            Metric.STRESS -> ChartScale.nice(min, max, bottom = 0.0, top = 100.0)
            Metric.STEPS -> ChartScale.nice(0.0, max, bottom = 0.0)
            Metric.TEMPERATURE_DEVIATION -> ChartScale.nice(minOf(min, -0.5), maxOf(max, 0.5))
            else -> {
                val (low, high) = widened(min, max, span(metric, units))
                ChartScale.nice(low, high)
            }
        }
    }

    /** The least a reading's chart spans, in the person's units. */
    fun span(metric: Metric, units: Units): Double = when (metric) {
        Metric.HEART_RATE -> 30.0
        Metric.HRV -> 20.0
        Metric.RESPIRATORY_RATE -> 6.0
        Metric.SKIN_TEMPERATURE -> temperatureChange(2.0, units)
        else -> 0.0
    }

    /** [min]..[max] widened about its middle to at least [span]. */
    fun widened(min: Double, max: Double, span: Double): Pair<Double, Double> {
        if (max - min >= span) return min to max
        val middle = (min + max) / 2
        return (middle - span / 2) to (middle + span / 2)
    }
}
