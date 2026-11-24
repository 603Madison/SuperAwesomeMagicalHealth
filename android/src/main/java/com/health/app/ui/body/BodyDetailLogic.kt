package com.health.app.ui.body

import com.health.api.GlucoseUnit
import com.health.api.Goals
import com.health.api.Metric
import com.health.api.Point
import com.health.api.RecentPoint
import com.health.api.RecentReadings
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.StageSpan
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.api.VitalsTargets
import com.health.api.Workout
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ChartScale
import com.health.app.ui.components.TimeAxis
import com.health.app.ui.components.TimeLabels
import com.health.app.ui.components.TimeTick
import com.health.app.ui.food.MicroText
import com.health.app.ui.modules.Moods
import com.health.app.ui.recent.Recent
import com.health.app.ui.tracking.Glucose
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A card on Body, opened: what it shows over 24 hours, a week, a month or a year, with the latest,
 * a large chart (and the person's usual where known), the average, lowest and highest, and each
 * reading or day. Worked out here without drawing anything, so it can be tested; BodyDetail.kt
 * draws it. 24 hours come from GET /api/recent (the person's own only), the rest from the trends,
 * with the weigh-ins, the workouts and the nights where those are what the card is about.
 */

/** What a detail page has loaded for its span (each part only where the page needs it). */
data class BodyDetailState(
    val span: BodySpan = BodySpan.WEEK,
    /** The last 24 hours, at 24 hours. */
    val readings: RecentReadings? = null,
    /** The week, month or year. */
    val trends: Trends? = null,
    /** The 30 days before the span, for the person's usual ([BodyDetail.usualWindow]). */
    val usual: Trends? = null,
    val weights: List<Sample> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val nights: List<SleepSummary> = emptyList(),
    /** Breathing over the span, from the series (the trends don't carry it). */
    val breathing: List<Point> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

object BodyDetail {
    /** The page's route; [route] fills it in. */
    const val ROUTE = "body/detail/{id}?span={span}&device={device}"

    /** The details of the card [id], at the [span] Body was on (and, at 24 hours, the [device] it showed). */
    fun route(id: String, span: BodySpan, device: String? = null): String =
        "body/detail/$id?span=${span.name}" + (device?.let { "&device=${java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")}" } ?: "")

    fun spanNamed(name: String?): BodySpan? = BodySpan.entries.firstOrNull { it.name == name }

    // ---- The sections (android.bodyDetail): saved with the person's arrangement, so never rename one.

    const val HEADLINE = "headline"
    const val CHART = "chart"
    const val STATS = "stats"
    const val LIST = "list"

    val SECTIONS = listOf(
        ArrangeItem(HEADLINE, "The latest"),
        ArrangeItem(CHART, "Chart"),
        ArrangeItem(STATS, "Average, lowest and highest"),
        ArrangeItem(LIST, "Each reading"),
    )

    /** A chosen nutrient's detail: "nutrient.FIBER". */
    const val NUTRIENT = "nutrient."

    fun nutrient(key: String) = "$NUTRIENT$key"

    /** The details that have the last 24 hours as recorded (Body's 24-hour cards). */
    val DAY_IDS = setOf(Recent.SLEEP, Recent.HEART, Recent.HRV, Recent.SPO2, Recent.BREATHING, Recent.STRESS, Recent.TEMPERATURE, Recent.STEPS)

    /** The spans a detail offers: 24 hours only for what the devices recorded, and never while viewing a share. */
    fun spans(id: String, readOnly: Boolean): List<BodySpan> =
        if (id in DAY_IDS && !readOnly) BodySpan.entries else BodySpan.entries - BodySpan.DAY

    /** The span to start at: the one Body was on, where this detail has it; else the week. */
    fun startSpan(id: String, asked: BodySpan?, readOnly: Boolean): BodySpan {
        val offered = spans(id, readOnly)
        return asked?.takeIf { it in offered } ?: if (BodySpan.DAY in offered && asked == null) BodySpan.DAY else BodySpan.WEEK
    }

    /** The page's title: the name of the card it opened from, at that span. */
    fun title(id: String, span: BodySpan): String = when (id) {
        Recent.HEART -> if (span == BodySpan.DAY) "Heart rate" else "Resting heart rate"
        Recent.STRESS -> if (span == BodySpan.DAY) "Stress" else "Stress and recovery"
        else -> NAMES[id] ?: nutrientKey(id)?.let { key -> MicroText.ALL.firstOrNull { it.key == key }?.label } ?: "Details"
    }

    private val NAMES = mapOf(
        Recent.SLEEP to "Sleep", Recent.HRV to "Heart rate variability", Recent.SPO2 to "Blood oxygen", Recent.BREATHING to "Breathing",
        Recent.TEMPERATURE to "Temperature", Recent.STEPS to "Steps", "scores" to "Scores", "mood" to "Mood", "doses" to "Doses taken",
        "period" to "Period days", "trips" to "Toilet trips", "leaks" to "Leaks", "calories" to "Calories eaten", "water" to "Water",
        "bp" to "Blood pressure", "glucose" to "Glucose", "caffeine" to "Caffeine", "alcohol" to "Alcohol", "symptoms" to "Symptoms",
        "habits" to "Habits", "weight" to "Weight", "workouts" to "Workouts",
    )

    private fun nutrientKey(id: String): String? = id.takeIf { it.startsWith(NUTRIENT) }?.removePrefix(NUTRIENT)

    /** What a detail needs besides the trends: the weigh-ins, the workouts, the nights or breathing's series. */
    enum class Extra { WEIGHTS, WORKOUTS, NIGHTS, BREATHING }

    fun extra(id: String): Extra? = when (id) {
        "weight" -> Extra.WEIGHTS
        "workouts" -> Extra.WORKOUTS
        Recent.SLEEP -> Extra.NIGHTS
        Recent.BREATHING -> Extra.BREATHING
        else -> null
    }

    /** Whether a detail compares with the person's usual (not temperature, already measured against it). */
    fun usesUsual(id: String): Boolean = id in setOf(Recent.SLEEP, Recent.HEART, Recent.HRV, Recent.SPO2, Recent.STEPS)

    /** The person's usual is the 30 days before the span (before today, at 24 hours): its first and last day. */
    fun usualWindow(span: BodySpan, today: LocalDate): Pair<LocalDate, LocalDate> {
        val first = if (span == BodySpan.DAY) today else today.minusDays(span.days - 1L)
        return first.minusDays(30) to first.minusDays(1)
    }

    /** The spans' own words over the chart: "Past 24 hours". */
    fun spanTitle(span: BodySpan): String = when (span) {
        BodySpan.DAY -> "Past 24 hours"
        BodySpan.WEEK -> "Past 7 days"
        BodySpan.MONTH -> "Past 30 days"
        BodySpan.YEAR -> "Past year"
    }

    const val EMPTY = "No readings in this time."

    /** How many rows show before "Show all". */
    const val FEW = 7

    // ---- What the page shows

    /** The person's settings the page reads with: their units, clock, today, goals and targets. */
    data class Ctx(
        val units: Units,
        val zone: ZoneId,
        val today: LocalDate,
        val goals: Goals = Goals(),
        val targets: VitalsTargets = VitalsTargets(),
    )

    /** A figure in the stats line: "Lowest", "52 bpm", "3:10 AM". */
    data class Stat(val label: String, val value: String, val note: String? = null)

    /**
     * A row of the list: when ([label]), the value, and a line under it. A weigh-in the person
     * entered has its [id] to delete; a night has its [night] to draw.
     */
    data class Row(
        val label: String,
        val value: String,
        val note: String? = null,
        val at: Long = 0,
        val id: Long? = null,
        val night: SleepSession? = null,
    )

    /**
     * The large chart: up to three [lines] of points (in the person's units) on [axis] against
     * [scale], as bars [bucket] wide or lines, with a dashed [reference] (the usual, or a goal)
     * that [referenceText] explains. [names] name the lines when there's more than one.
     */
    data class Chart(
        val lines: List<List<Point>>,
        val names: List<String>,
        val bars: Boolean,
        val bucket: Long,
        val axis: TimeAxis,
        val scale: ChartScale,
        val reference: Double? = null,
        val referenceText: String? = null,
        val description: String,
        /** How far apart readings can be before the line breaks. */
        val gap: Long = 45 * 60_000L,
    )

    /** The page for one span. [empty] when nothing in it; [night] is the 24 hours' night, drawn in place of a chart. */
    data class Page(
        val title: String,
        val headline: String? = null,
        val headlineWhen: String? = null,
        val chart: Chart? = null,
        val night: SleepSession? = null,
        val stats: List<Stat> = emptyList(),
        val against: String? = null,
        val listTitle: String = "Each day",
        val rows: List<Row> = emptyList(),
    ) {
        val empty: Boolean get() = headline == null && chart == null && night == null && rows.isEmpty()
    }

    /** The page for [id] from what [state] holds. */
    fun page(id: String, state: BodyDetailState, ctx: Ctx): Page {
        val title = title(id, state.span)
        if (state.span == BodySpan.DAY) {
            val readings = state.readings ?: return Page(title)
            return if (id == Recent.SLEEP) daySleep(readings, state.usual?.days.orEmpty(), ctx, title) else dayReadings(id, readings, state.usual?.days.orEmpty(), ctx, title)
        }
        if (id == "weight") return weight(state.weights, state.span, ctx, title)
        val trends = state.trends ?: return Page(title)
        val daily = daily(id, ctx, trends, state.breathing) ?: return Page(title)
        val page = days(daily, trends.days, state.usual?.days.orEmpty(), state.span, ctx, title)
        return when (id) {
            "workouts" -> withWorkouts(page, trends.days, state.workouts, state.span, ctx)
            Recent.SLEEP -> withNights(page, state.nights, ctx)
            else -> page
        }
    }

    // ---- 24 hours

    /** The reading a 24-hour detail shows: temperature's change from usual where there is one, else the skin's. */
    fun dayMetric(id: String, readings: RecentReadings): Metric? {
        val present = Recent.series(readings).keys
        return when (id) {
            Recent.TEMPERATURE -> listOf(Metric.TEMPERATURE_DEVIATION, Metric.SKIN_TEMPERATURE).firstOrNull { it in present }
            else -> Recent.metrics(id).firstOrNull { it in present }
        }
    }

    private fun dayReadings(id: String, readings: RecentReadings, usualDays: List<TrendDay>, ctx: Ctx, title: String): Page {
        val metric = dayMetric(id, readings) ?: return Page(title, listTitle = "Each hour")
        val series = Recent.series(readings).getValue(metric)
        val counts = metric == Metric.STEPS
        val points = Recent.points(series, ctx.units)
        val usual = when (id) {
            Recent.HRV -> if (readings.device != null) null else BodyText.usual(usualDays, ctx.today.toString()) { it.hrv }
            Recent.SPO2 -> if (readings.device != null) null else BodyText.usual(usualDays, ctx.today.toString()) { it.spo2 }
            else -> null
        }
        fun shown(v: Double) = Recent.value(metric, v, ctx.units)
        val scaled = if (usual != null) points + Point(readings.from, Recent.shown(metric, usual, ctx.units)) else points
        val chart = Chart(
            lines = listOf(points), names = listOf(title), bars = counts, bucket = (series.bucketMinutes ?: 60) * 60_000L,
            axis = TimeAxis.of(readings.from, readings.to, ctx.zone), scale = Recent.scale(metric, scaled, ctx.units),
            reference = usual?.let { Recent.shown(metric, it, ctx.units) }, referenceText = usual?.let { "Dashed line: your usual, ${shown(it)}" },
            description = Recent.description(title, series, ctx.units),
        )
        val values = series.points
        val stats: List<Stat>
        val headline: Pair<String, String>?
        val average = series.average ?: values.map { it.value }.average()
        if (counts) {
            val total = series.total ?: values.sumOf { it.value }
            headline = shown(total) to "in the past 24 hours"
            val top = values.maxByOrNull { it.value }
            stats = listOfNotNull(
                Stat("In all", shown(total)),
                top?.takeIf { it.value > 0 }?.let { Stat("Busiest hour", shown(it.value), hourLabel(it.at, ctx)) },
            )
        } else {
            headline = Recent.latest(series, ctx.units, ctx.zone)
            val low = values.minByOrNull { it.value }
            val high = values.maxByOrNull { it.value }
            stats = if (values.size < 2 || low == null || high == null) emptyList() else listOf(
                Stat("Average", shown(average)),
                Stat("Lowest", shown(low.value), Format.timeOfDay(low.at, ctx.zone)),
                Stat("Highest", shown(high.value), Format.timeOfDay(high.at, ctx.zone)),
            )
        }
        return Page(
            title, headline?.first, headline?.second, chart = chart, stats = stats,
            against = usual?.let { against(average, it, ::shown) },
            listTitle = "Each hour", rows = hours(values, metric, counts, ctx),
        )
    }

    /** The readings an hour at a time, newest first: the hour's total (steps, leaving out hours without) or its average and range. */
    fun hours(points: List<RecentPoint>, metric: Metric, counts: Boolean, ctx: Ctx): List<Row> =
        points.groupBy { Instant.ofEpochMilli(it.at).atZone(ctx.zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli() }
            .mapNotNull { (hour, readings) ->
                val values = readings.map { it.value }
                if (counts) {
                    val total = values.sum()
                    if (total <= 0) null else Row(hourLabel(hour, ctx), Recent.value(metric, total, ctx.units), at = hour)
                } else {
                    val note = if (values.size > 1 && values.max() > values.min()) {
                        "from ${Recent.number(metric, Recent.shown(metric, values.min(), ctx.units))} to ${Recent.value(metric, values.max(), ctx.units)}"
                    } else null
                    Row(hourLabel(hour, ctx), Recent.value(metric, values.average(), ctx.units), note, at = hour)
                }
            }
            .sortedByDescending { it.at }

    /** "2 PM", or "2 PM yesterday" for an hour before today. */
    fun hourLabel(at: Long, ctx: Ctx): String {
        val hour = TimeLabels.hour(Instant.ofEpochMilli(at).atZone(ctx.zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli(), ctx.zone)
        val day = Instant.ofEpochMilli(at).atZone(ctx.zone).toLocalDate()
        return if (day.isBefore(ctx.today)) "$hour yesterday" else hour
    }

    private fun daySleep(readings: RecentReadings, usualDays: List<TrendDay>, ctx: Ctx, title: String): Page {
        val nights = Recent.nights(readings.sleep)
        val main = BodyText.mainNight(readings) ?: return Page(title, listTitle = "Each sleep")
        val totals = Recent.totals(listOf(main))
        val usual = if (readings.device != null) null else BodyText.usual(usualDays, ctx.today.toString()) { it.sleepMinutes?.toDouble() }
        val rows = nights.sortedByDescending { it.start }.map { s ->
            val t = Recent.totals(listOf(s))
            Row(Recent.sessionLine(s, ctx.zone, Recent.namesDevices(readings)), Format.minutes(t.asleep), stageLine(t), at = s.start, night = s)
        }
        return Page(
            title, Format.minutes(totals.asleep), "asleep, ${Recent.sessionLine(main, ctx.zone, false)}",
            night = main,
            stats = BodyText.stageTiles(totals).map { (name, time) -> Stat(name, time) },
            against = usual?.let { against(totals.asleep.toDouble(), it) { m -> Format.minutes(m.roundToInt()) } },
            listTitle = "Each sleep", rows = rows,
        )
    }

    /** A sleep's stages in words: "deep 1h 25m · REM 1h 46m · light 3h 56m · awake 15m", or just awake when not told apart. */
    fun stageLine(t: Recent.SleepTotals): String? =
        if (t.staged) "deep ${Format.minutes(t.deep)} · REM ${Format.minutes(t.rem)} · light ${Format.minutes(t.light)} · awake ${Format.minutes(t.awake)}"
        else t.awake.takeIf { it > 0 }?.let { "awake ${Format.minutes(it)}" }

    // ---- The week, month and year: a value a day

    private enum class Summary { AVERAGE, TOTAL, DAYS, PERCENT, LINES, PAIR, WORKOUTS }

    /**
     * How a detail reads its days: each line's value a day in the person's units (the first is
     * the main one), a value as shown ([one]), and a day's row ([row]: its value and a line under it).
     */
    private class Daily(
        val values: List<Pair<String, (TrendDay) -> Double?>>,
        val one: (Double) -> String,
        row: ((TrendDay) -> Pair<String, String?>?)? = null,
        val bars: Boolean = false,
        val summary: Summary = Summary.AVERAGE,
        /** What the average is per: "a day", "a night". */
        val per: String? = "a day",
        val usual: ((TrendDay) -> Double?)? = null,
        val goal: Double? = null,
        val low: Double? = null,
        val high: Double? = null,
        val metric: Metric? = null,
        /** Done and due, for a percentage over the span. */
        val parts: ((TrendDay) -> Pair<Int, Int>?)? = null,
    ) {
        val main: (TrendDay) -> Double? get() = values.first().second
        val row: (TrendDay) -> Pair<String, String?>? = row ?: { d -> main(d)?.let { one(it) to null } }
    }

    private fun Int.plural(one: String, many: String = "${one}s") = "$this ${if (this == 1) one else many}"

    private fun daily(id: String, ctx: Ctx, trends: Trends, breathing: List<Point>): Daily? {
        val u = ctx.units
        return when (id) {
            Recent.SLEEP -> Daily(
                listOf("Asleep" to { d -> d.sleepMinutes?.let { it / 60.0 } }), { Format.minutes((it * 60).roundToInt()) },
                row = { d ->
                    d.sleepMinutes?.let { m ->
                        Format.minutes(m) to listOfNotNull(d.deepMinutes?.let { "deep ${Format.minutes(it)}" }, d.remMinutes?.let { "REM ${Format.minutes(it)}" }).joinToString(" · ").ifEmpty { null }
                    }
                },
                bars = true, per = "a night", usual = { d -> d.sleepMinutes?.let { it / 60.0 } }, goal = ctx.goals.sleepMinutes / 60.0,
            )
            Recent.STEPS -> Daily(
                listOf("Steps" to { d -> d.steps?.toDouble() }), { "${Format.number(it)} steps" },
                bars = true, usual = { d -> d.steps?.toDouble() }, goal = ctx.goals.steps.toDouble(),
            )
            Recent.HEART -> Daily(listOf("Resting" to { d -> d.restingHeartRate }), { "${it.roundToInt()} bpm" }, per = null, usual = { it.restingHeartRate }, metric = Metric.HEART_RATE)
            Recent.HRV -> Daily(listOf("HRV" to { d -> d.hrv }), { "${it.roundToInt()} ms" }, per = null, usual = { it.hrv }, metric = Metric.HRV)
            Recent.SPO2 -> Daily(listOf("Blood oxygen" to { d -> d.spo2 }), { "${it.roundToInt()}%" }, per = null, usual = { it.spo2 }, metric = Metric.SPO2)
            Recent.TEMPERATURE -> Daily(
                listOf("Change" to { d -> d.temperatureDeviation?.let { Recent.temperatureChange(it, u) } }),
                { "${Recent.number(Metric.TEMPERATURE_DEVIATION, it)} ${Recent.unit(Metric.TEMPERATURE_DEVIATION, u)}" },
                row = { d -> d.temperatureDeviation?.let { Recent.value(Metric.TEMPERATURE_DEVIATION, it, u) to BodyText.warmth(it) } },
                per = "on average", metric = Metric.TEMPERATURE_DEVIATION,
            )
            Recent.BREATHING -> {
                val byDay = breathing.groupBy { Instant.ofEpochMilli(it.t).atZone(ctx.zone).toLocalDate().toString() }.mapValues { (_, ps) -> ps.map { it.v }.average() }
                Daily(listOf("Breathing" to { d -> byDay[d.date] }), { Recent.value(Metric.RESPIRATORY_RATE, it, u) }, per = "on average", metric = Metric.RESPIRATORY_RATE)
            }
            Recent.STRESS -> Daily(
                listOf("Stressed" to { d -> d.stressedMinutes?.toDouble() }, "Restored" to { d -> d.restoredMinutes?.toDouble() }), { Format.minutes(it.roundToInt()) },
                row = { d ->
                    if (d.stressedMinutes == null && d.restoredMinutes == null) null
                    else "${Format.minutes(d.stressedMinutes ?: 0)} stressed" to d.restoredMinutes?.let { "${Format.minutes(it)} restored" }
                },
                summary = Summary.LINES, low = 0.0,
            )
            "scores" -> Daily(
                listOf("Readiness" to { d -> d.readiness?.toDouble() }, "Sleep" to { d -> d.sleepScore?.toDouble() }, "Activity" to { d -> d.activityScore?.toDouble() }),
                { it.roundToInt().toString() },
                row = { d ->
                    val parts = listOfNotNull(d.readiness?.let { "Readiness $it" }, d.sleepScore?.let { "Sleep $it" }, d.activityScore?.let { "Activity $it" })
                    parts.firstOrNull()?.let { it to parts.drop(1).joinToString(" · ").ifEmpty { null } }
                },
                summary = Summary.LINES, per = "on average", high = 100.0,
            )
            "mood" -> Daily(
                listOf("Mood" to { d -> d.mood }), { Moods.label(Moods.nearest(it)) },
                row = { d -> d.mood?.let { m -> Moods.label(Moods.nearest(m)) to d.mindfulMinutes?.takeIf { it > 0 }?.let { "${Format.minutes(it.roundToInt())} of calm tools" } } },
                per = "on average", low = 1.0, high = 5.0,
            )
            "doses" -> Daily(
                listOf("Taken" to { d -> d.dosesDue?.takeIf { it > 0 }?.let { (d.dosesTaken ?: 0).coerceAtMost(it) * 100.0 / it } }), { "${it.roundToInt()}%" },
                row = { d -> d.dosesDue?.takeIf { it > 0 }?.let { due -> "${(d.dosesTaken ?: 0).coerceAtMost(due)} of $due" to "taken" } },
                bars = true, summary = Summary.PERCENT, low = 0.0, high = 100.0,
                parts = { d -> d.dosesDue?.takeIf { it > 0 }?.let { (d.dosesTaken ?: 0).coerceAtMost(it) to it } },
            )
            "habits" -> Daily(
                listOf("Done" to { d -> d.habitsDue?.takeIf { it > 0 }?.let { (d.habitsDone ?: 0).coerceAtMost(it) * 100.0 / it } }), { "${it.roundToInt()}%" },
                row = { d -> d.habitsDue?.takeIf { it > 0 }?.let { due -> "${(d.habitsDone ?: 0).coerceAtMost(due)} of $due" to "done" } },
                bars = true, summary = Summary.PERCENT, low = 0.0, high = 100.0,
                parts = { d -> d.habitsDue?.takeIf { it > 0 }?.let { (d.habitsDone ?: 0).coerceAtMost(it) to it } },
            )
            "period" -> Daily(
                listOf("Flow" to { d -> d.flow?.let { it.ordinal + 1.0 } }), { it.roundToInt().toString() },
                row = { d -> d.flow?.let { it.label to null } }, bars = true, summary = Summary.DAYS,
            )
            "trips" -> Daily(
                listOf("Trips" to { d -> d.voids?.toDouble() }), { Format.compact(it) },
                row = { d ->
                    d.voids?.let { v ->
                        v.plural("trip") to listOfNotNull(d.nightVoids?.takeIf { it > 0 }?.let { "$it at night" }, d.leaks?.takeIf { it > 0 }?.plural("leak")).joinToString(" · ").ifEmpty { null }
                    }
                },
                bars = true,
            )
            "leaks" -> Daily(
                listOf("Leaks" to { d -> d.leaks?.toDouble() }), { Format.compact(it) },
                row = { d -> d.leaks?.let { it.plural("leak") to null } }, bars = true, summary = Summary.TOTAL,
            )
            "calories" -> Daily(
                listOf("Eaten" to { d -> d.calories }), { "${Format.number(it)} kcal" },
                row = { d -> d.calories?.let { c -> "${Format.number(c)} kcal" to d.protein?.let { "${Format.number(it)} g protein" } } },
                bars = true, per = "a day you logged", goal = ctx.goals.calories?.toDouble(),
            )
            "water" -> {
                fun shown(ml: Double) = if (u == Units.IMPERIAL) ml / Format.ML_PER_FL_OZ else ml
                fun ml(v: Double) = if (u == Units.IMPERIAL) v * Format.ML_PER_FL_OZ else v
                Daily(
                    listOf("Water" to { d -> d.waterMl?.takeIf { it > 0 }?.let { shown(it.toDouble()) } }), { Format.water(ml(it).roundToInt(), u) },
                    bars = true, per = "a day you logged", goal = shown(ctx.goals.waterMl.toDouble()),
                )
            }
            "bp" -> Daily(
                listOf("Top" to { d -> d.systolic }, "Bottom" to { d -> d.diastolic }), { it.roundToInt().toString() },
                row = { d -> d.systolic?.let { s -> "${s.roundToInt()}/${d.diastolic?.roundToInt() ?: "—"}" to "mmHg" } },
                summary = Summary.PAIR,
            )
            "glucose" -> {
                val unit = ctx.targets.glucoseUnit
                Daily(
                    listOf("Glucose" to { d -> d.glucose?.let { Glucose.value(it, unit) } }),
                    { v -> if (unit == GlucoseUnit.MG_DL) "${v.roundToInt()} ${unit.label}" else String.format(Locale.getDefault(), "%.1f %s", v, unit.label) },
                    per = "on average",
                )
            }
            "caffeine" -> Daily(listOf("Caffeine" to { d -> d.caffeineMg?.takeIf { it > 0 } }), { "${it.roundToInt()} mg" }, bars = true)
            "alcohol" -> Daily(
                listOf("Standard drinks" to { d -> d.standardDrinks }), { "${Format.compact(it)} ${if (abs(it - 1) < 0.05) "drink" else "drinks"}" },
                bars = true, summary = Summary.TOTAL,
            )
            "symptoms" -> Daily(
                listOf("Worst" to { d -> d.symptomWorst?.toDouble() ?: d.migraine?.takeIf { it }?.let { 0.0 } }), { "${it.roundToInt()} of 10" },
                row = { d ->
                    if (d.symptomWorst == null && d.migraine != true) null
                    else (d.symptomWorst?.let { "worst $it of 10" } ?: "migraine") to (if (d.migraine == true && d.symptomWorst != null) "migraine" else null)
                },
                bars = true, summary = Summary.DAYS, low = 0.0, high = 10.0,
            )
            "workouts" -> Daily(
                listOf("Minutes" to { d -> d.workoutMinutes.toDouble().takeIf { it > 0 } }), { Format.minutes(it.roundToInt()) },
                row = { d -> d.workoutMinutes.takeIf { it > 0 }?.let { Format.minutes(it) to d.workouts.plural("workout") } },
                bars = true, summary = Summary.WORKOUTS,
            )
            else -> nutrientKey(id)?.let { key ->
                val info = MicroText.ALL.firstOrNull { it.key == key } ?: return null
                Daily(
                    listOf(info.label to { d -> MicroText.amount(d, info) }), { MicroText.withUnit(it, info.unit) },
                    bars = true, per = "a day it came with food", goal = MicroText.target(trends.nutritionTarget, info),
                )
            }
        }
    }

    /** The least a chart of daily values spans, so a beat or two either way doesn't fill it. */
    private val DAILY_SPAN = mapOf(Metric.HEART_RATE to 10.0, Metric.HRV to 12.0, Metric.RESPIRATORY_RATE to 3.0)

    private fun dayStart(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The span's days as a time axis: each day at a week, every seventh (to the last) at a month, each month at a year. */
    fun dayAxis(first: LocalDate, last: LocalDate, span: BodySpan, zone: ZoneId, locale: Locale = Locale.getDefault()): TimeAxis {
        val from = dayStart(first, zone)
        val to = dayStart(last.plusDays(1), zone)
        val ticks = ArrayList<TimeTick>()
        var d = first
        while (!d.isAfter(last)) {
            val back = ChronoUnit.DAYS.between(d, last)
            when (span) {
                BodySpan.YEAR -> if (d.dayOfMonth == 1) ticks += TimeTick(dayStart(d, zone), DateTimeFormatter.ofPattern("MMM", locale).format(d))
                BodySpan.MONTH -> if (back % 7 == 0L) ticks += TimeTick(dayStart(d, zone) + 43_200_000L, DateTimeFormatter.ofPattern("d MMM", locale).format(d))
                else -> ticks += TimeTick(dayStart(d, zone) + 43_200_000L, DateTimeFormatter.ofPattern("EEE", locale).format(d))
            }
            d = d.plusDays(1)
        }
        return TimeAxis(from, to, ticks)
    }

    /** A line's points: each day's value at noon; as bars, from the day's start. Over a year, a week's average to a point (or bar). */
    fun dayPoints(days: List<TrendDay>, value: (TrendDay) -> Double?, bars: Boolean, span: BodySpan, zone: ZoneId): List<Point> {
        if (span == BodySpan.YEAR) {
            return days.chunked(7).mapNotNull { week ->
                val values = week.mapNotNull(value)
                val start = dayStart(LocalDate.parse(week.first().date), zone)
                values.takeIf { it.isNotEmpty() }?.let { Point(if (bars) start else start + week.size * 43_200_000L, it.average()) }
            }
        }
        return days.mapNotNull { d -> value(d)?.let { Point(dayStart(LocalDate.parse(d.date), zone) + if (bars) 0 else 43_200_000L, it) } }
    }

    private fun days(daily: Daily, days: List<TrendDay>, usualDays: List<TrendDay>, span: BodySpan, ctx: Ctx, title: String): Page {
        if (days.isEmpty()) return Page(title)
        val first = LocalDate.parse(days.first().date)
        val last = LocalDate.parse(days.last().date)
        val lines = daily.values.map { (_, value) -> dayPoints(days, value, daily.bars, span, ctx.zone) }
        val withValues = days.filter { daily.row(it) != null }
        if (withValues.isEmpty() && lines.all { it.isEmpty() }) return Page(title)
        val usual = daily.usual?.let { BodyText.usual(usualDays, first.toString(), it) }
        val reference = usual ?: daily.goal
        val referenceText = when {
            usual != null -> "Dashed line: your usual, ${daily.one(usual)}"
            daily.goal != null -> "Dashed line: your goal, ${daily.one(daily.goal)}"
            else -> null
        }
        val all = lines.flatten().map { it.v } + listOfNotNull(reference)
        val scale = when {
            daily.bars -> ChartScale.nice(0.0, all.maxOrNull() ?: 0.0, bottom = 0.0, top = daily.high)
            // A day's resting heart rate, HRV or breathing moves less than a day's readings: a tighter scale than the 24 hours'.
            daily.metric in DAILY_SPAN -> {
                val (lo, hi) = Recent.widened(all.minOrNull() ?: 0.0, all.maxOrNull() ?: 0.0, DAILY_SPAN.getValue(daily.metric!!))
                ChartScale.nice(lo, hi)
            }
            daily.metric != null -> Recent.scale(daily.metric, all.map { Point(0, it) }, ctx.units)
            else -> ChartScale.nice(all.minOrNull() ?: 0.0, all.maxOrNull() ?: 0.0, bottom = daily.low, top = daily.high)
        }
        val mainValues = days.mapNotNull { d -> daily.main(d)?.let { d to it } }
        val chart = Chart(
            lines = lines, names = daily.values.map { it.first }, bars = daily.bars, bucket = if (span == BodySpan.YEAR) 7 * 86_400_000L else 86_400_000L,
            axis = dayAxis(first, last, span, ctx.zone), scale = scale, reference = reference, referenceText = referenceText,
            description = describe(title, span, mainValues.map { it.second }, daily.one),
            gap = if (span == BodySpan.YEAR) 15 * 86_400_000L else 36 * 3_600_000L,
        )
        val latest = withValues.lastOrNull()
        val stats = stats(daily, days, mainValues)
        val average = mainValues.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
        return Page(
            title,
            latest?.let { daily.row(it)?.first }, latest?.let { Format.day(LocalDate.parse(it.date), ctx.today) },
            chart = chart, stats = stats,
            against = if (usual != null && average != null) against(average, usual, daily.one) else null,
            rows = withValues.reversed().mapNotNull { d ->
                daily.row(d)?.let { (value, note) -> Row(Format.day(LocalDate.parse(d.date), ctx.today), value, note, at = dayStart(LocalDate.parse(d.date), ctx.zone)) }
            },
        )
    }

    private fun stats(daily: Daily, days: List<TrendDay>, main: List<Pair<TrendDay, Double>>): List<Stat> {
        fun whenOf(d: TrendDay) = Format.shortDay(LocalDate.parse(d.date))
        val low = main.minByOrNull { it.second }
        val high = main.maxByOrNull { it.second }
        val range = if (main.size >= 2 && low != null && high != null) {
            listOf(Stat("Lowest", daily.one(low.second), whenOf(low.first)), Stat("Highest", daily.one(high.second), whenOf(high.first)))
        } else emptyList()
        return when (daily.summary) {
            Summary.AVERAGE -> if (main.isEmpty()) emptyList() else listOf(Stat("Average", daily.one(main.map { it.second }.average()), daily.per)) + range
            Summary.TOTAL -> if (main.isEmpty()) emptyList() else listOf(
                Stat("In all", daily.one(main.sumOf { it.second }), "in ${days.size.plural("day")}"),
                Stat("Days without", "${days.size - main.count { it.second > 0 }}", "of ${days.size}"),
            ).let { if (high != null && high.second > 0) it + Stat("Most", daily.one(high.second), whenOf(high.first)) else it }
            Summary.DAYS -> listOf(Stat("Days", main.size.toString(), "of ${days.size}")) +
                listOfNotNull(high?.takeIf { daily.high != null && it.second > 0 }?.let { Stat("Highest", daily.one(it.second), whenOf(it.first)) })
            Summary.PERCENT -> {
                val parts = days.mapNotNull { daily.parts?.invoke(it) }
                val due = parts.sumOf { it.second }
                if (due == 0) emptyList() else listOf(Stat("In all", "${(parts.sumOf { it.first } * 100.0 / due).roundToInt()}%", "${parts.sumOf { it.first }} of $due")) + range
            }
            Summary.LINES -> daily.values.mapNotNull { (name, value) ->
                days.mapNotNull(value).takeIf { it.isNotEmpty() }?.let { Stat(name, daily.one(it.average()), daily.per) }
            }
            Summary.PAIR -> {
                val second = daily.values.getOrNull(1)?.second
                val averages = listOfNotNull(main.takeIf { it.isNotEmpty() }?.map { it.second }?.average(), second?.let { v -> days.mapNotNull(v).takeIf { it.isNotEmpty() }?.average() })
                if (averages.isEmpty()) emptyList() else listOf(Stat("Average", averages.joinToString("/") { it.roundToInt().toString() }, "mmHg")) +
                    if (main.size >= 2 && low != null && high != null) {
                        listOf(Stat("Lowest", daily.row(low.first)?.first ?: "", whenOf(low.first)), Stat("Highest", daily.row(high.first)?.first ?: "", whenOf(high.first)))
                    } else emptyList()
            }
            Summary.WORKOUTS -> {
                val count = days.sumOf { it.workouts }
                if (count == 0) emptyList() else listOf(
                    Stat("Workouts", count.toString(), "in ${days.size.plural("day")}"),
                    Stat("Time", Format.minutes(days.sumOf { it.workoutMinutes }), "in all"),
                )
            }
        }
    }

    /** What a chart says to a screen reader: "Steps over the past 7 days, from 5,200 to 9,800 steps, average 7,400 steps". */
    private fun describe(title: String, span: BodySpan, values: List<Double>, one: (Double) -> String): String {
        val over = spanTitle(span).replaceFirstChar { it.lowercase() }
        if (values.isEmpty()) return "$title over the $over: nothing recorded"
        return "$title over the $over, from ${one(values.min())} to ${one(values.max())}, average ${one(values.average())}"
    }

    /** How [value] sits against [usual], both shown by [one]: "about your usual 7h 20m", "12% below your usual 53 bpm". */
    fun against(value: Double, usual: Double, one: (Double) -> String): String {
        if (usual == 0.0) return "your usual is ${one(usual)}"
        val pct = ((value - usual) / abs(usual) * 100).roundToInt()
        return when {
            abs(pct) < 5 -> "About your usual ${one(usual)}"
            pct < 0 -> "${-pct}% below your usual ${one(usual)}"
            else -> "$pct% above your usual ${one(usual)}"
        }
    }

    // ---- Weight: each weigh-in

    private fun weight(all: List<Sample>, span: BodySpan, ctx: Ctx, title: String): Page {
        val first = ctx.today.minusDays(span.days - 1L)
        val from = dayStart(first, ctx.zone)
        val weights = all.filter { it.start >= from }.sortedBy { it.start }
        val latest = weights.lastOrNull() ?: return Page(title, listTitle = "Each weigh-in")
        val u = ctx.units
        fun dayOf(s: Sample) = Instant.ofEpochMilli(s.start).atZone(ctx.zone).toLocalDate()
        fun one(v: Double) = "${Format.compact(v)} ${Format.weightUnit(u)}"
        val points = weights.map { Point(it.start, Format.weightValue(it.value, u)) }
        val goal = ctx.goals.targetWeightKg?.let { Format.weightValue(it, u) }
        val values = points.map { it.v } + listOfNotNull(goal)
        val (lo, hi) = Recent.widened(values.min(), values.max(), if (u == Units.IMPERIAL) 4.0 else 2.0)
        val chart = Chart(
            lines = listOf(points), names = listOf(title), bars = false, bucket = 0,
            axis = dayAxis(first, ctx.today, span, ctx.zone), scale = ChartScale.nice(lo, hi),
            reference = goal, referenceText = goal?.let { "Dashed line: your goal, ${Format.weight(ctx.goals.targetWeightKg!!, u)}" },
            description = describe(title, span, points.map { it.v }, ::one),
            gap = span.days * 86_400_000L,
        )
        val low = weights.minByOrNull { it.value }!!
        val high = weights.maxByOrNull { it.value }!!
        val stats = if (weights.size < 2) emptyList() else {
            val change = Format.weightValue(latest.value - weights.first().value, u)
            listOf(
                Stat("Change", "${if (change >= 0) "+" else "−"}${Format.compact(abs(change))} ${Format.weightUnit(u)}", "since ${Format.shortDay(dayOf(weights.first()))}"),
                Stat("Lowest", Format.weight(low.value, u), Format.shortDay(dayOf(low))),
                Stat("Highest", Format.weight(high.value, u), Format.shortDay(dayOf(high))),
            )
        }
        return Page(
            title, Format.weight(latest.value, u), Format.day(dayOf(latest), ctx.today),
            chart = chart, stats = stats, listTitle = "Each weigh-in",
            rows = weights.reversed().map { w ->
                val note = listOfNotNull(Format.timeOfDay(w.start, ctx.zone), w.source?.takeIf { it.isNotBlank() }).joinToString(" · ")
                Row(Format.day(dayOf(w), ctx.today), Format.weight(w.value, u), note, at = w.start, id = w.id?.takeIf { deletable(w) })
            },
        )
    }

    /** Whether a weigh-in is the person's own to delete: one entered by hand. */
    fun deletable(w: Sample): Boolean = w.id != null && (w.source == null || w.source!!.contains("hand", ignoreCase = true))

    // ---- Workouts: each one

    private fun withWorkouts(page: Page, days: List<TrendDay>, workouts: List<Workout>, span: BodySpan, ctx: Ctx): Page {
        val from = dayStart(LocalDate.parse(days.firstOrNull()?.date ?: return page), ctx.zone)
        val shown = workouts.filter { it.start >= from }.sortedByDescending { it.start }
        if (shown.isEmpty()) return page.copy(listTitle = "Each day")
        val latest = shown.first()
        val longest = shown.maxByOrNull { it.end - it.start }!!
        return page.copy(
            headline = workoutName(latest),
            headlineWhen = "${Format.minutes(minutes(latest))} · ${Format.day(Instant.ofEpochMilli(latest.start).atZone(ctx.zone).toLocalDate(), ctx.today)}",
            stats = page.stats + Stat("Longest", Format.minutes(minutes(longest)), Format.shortDay(Instant.ofEpochMilli(longest.start).atZone(ctx.zone).toLocalDate())),
            listTitle = "Each workout",
            rows = shown.map { workoutRow(it, ctx) },
        )
    }

    private fun minutes(w: Workout) = ((w.end - w.start) / 60_000L).toInt().coerceAtLeast(0)

    fun workoutName(w: Workout): String = w.name?.takeIf { it.isNotBlank() } ?: w.type.label

    /** A workout's row: its name, how long, and when, how far, how much and how hard. */
    fun workoutRow(w: Workout, ctx: Ctx): Row {
        val day = Instant.ofEpochMilli(w.start).atZone(ctx.zone).toLocalDate()
        val note = listOfNotNull(
            "${Format.day(day, ctx.today)}, ${Format.timeOfDay(w.start, ctx.zone)}",
            w.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, ctx.units) },
            w.activeCalories?.takeIf { it > 0 }?.let { "${Format.number(it)} kcal" },
            w.avgHeartRate?.let { "average ${it.roundToInt()} bpm" },
            w.maxHeartRate?.let { "highest ${it.roundToInt()} bpm" },
            w.steps?.takeIf { it > 0 }?.let { "${Format.number(it)} steps" },
            w.effort?.let { "effort $it of 10" },
        ).joinToString(" · ")
        return Row(workoutName(w), Format.minutes(minutes(w)), note, at = w.start)
    }

    // ---- Sleep: each night

    private fun withNights(page: Page, nights: List<SleepSummary>, ctx: Ctx): Page {
        if (nights.isEmpty()) return page
        val rows = nights.sortedByDescending { it.end }.map { nightRow(it, ctx) }
        val latest = nights.maxByOrNull { it.end }!!
        return page.copy(
            headline = Format.minutes(latest.asleepMinutes),
            headlineWhen = "asleep, ${Format.day(Instant.ofEpochMilli(latest.end).atZone(ctx.zone).toLocalDate(), ctx.today)}",
            listTitle = "Each night", rows = rows,
        )
    }

    /** A night's row: the morning it ended, how long asleep, its times and stages, and the stages to draw. */
    fun nightRow(n: SleepSummary, ctx: Ctx): Row {
        val day = Instant.ofEpochMilli(n.end).atZone(ctx.zone).toLocalDate()
        val stages = if (n.staged) "deep ${Format.minutes(n.deepMinutes)} · REM ${Format.minutes(n.remMinutes)} · light ${Format.minutes(n.lightMinutes)} · awake ${Format.minutes(n.awakeMinutes)}"
        else n.awakeMinutes.takeIf { it > 0 }?.let { "awake ${Format.minutes(it)}" }
        val note = listOfNotNull("${Format.timeOfDay(n.start, ctx.zone)} – ${Format.timeOfDay(n.end, ctx.zone)}", stages).joinToString(" · ")
        val session = SleepSession(n.start, n.end, n.stages.ifEmpty { listOf(StageSpan(SleepStage.ASLEEP, n.start, n.end)) })
        return Row(Format.day(day, ctx.today), Format.minutes(n.asleepMinutes), note, at = n.end, night = session)
    }
}
