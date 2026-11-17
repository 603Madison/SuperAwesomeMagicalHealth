package com.health.app.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.api.Goals
import com.health.api.Module
import com.health.api.Sample
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.api.VitalsTargets
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.TitledCard
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.Moods
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.TitleAction
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.TrackingTrend
import com.health.app.ui.tracking.trackingTrendSections
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Trends' charts for the features that are on, in the default order: the sections of
 * [FeatureScreens.TRENDS]. Water only while it's tracked, and a nutrient's chart while vitamins and
 * minerals show ([micros]). A chart with nothing in the range says so.
 */
fun trendsSections(modules: Set<Module>, water: Boolean = true, micros: Boolean = false): List<ArrangeItem> = buildList {
    val tracking = Module.ACTIVITY in modules || Module.SLEEP in modules
    if (tracking) add(ArrangeItem("scores", "Scores"))
    if (Module.MIND in modules) add(ArrangeItem("mood", "Mood"))
    if (Module.MEDICATIONS in modules) add(ArrangeItem("doses", "Doses taken"))
    if (Module.CYCLE in modules) add(ArrangeItem("period", "Period days"))
    if (Module.CONTINENCE in modules) {
        add(ArrangeItem("trips", "Toilet trips"))
        add(ArrangeItem("leaks", "Leaks"))
    }
    if (Module.SLEEP in modules) add(ArrangeItem("sleep", "Sleep"))
    if (Module.ACTIVITY in modules) add(ArrangeItem("steps", "Steps"))
    if (tracking) {
        add(ArrangeItem("heart", "Resting heart rate"))
        add(ArrangeItem("hrv", "Heart rate variability"))
    }
    if (Module.NUTRITION in modules) add(ArrangeItem("calories", "Calories eaten"))
    if (Module.NUTRITION in modules && water) add(ArrangeItem("water", "Water"))
    if (Module.NUTRITION in modules && micros) add(ArrangeItem(NUTRIENT, "Vitamins & minerals"))
    addAll(trackingTrendSections(modules))
    if (Module.BODY in modules) add(ArrangeItem("weight", "Weight"))
    if (Module.ACTIVITY in modules) add(ArrangeItem("workouts", "Workouts"))
}

/**
 * Body's week, month or year ([trends] over its days): the charts for the features on, in the
 * person's order ([FeatureScreens.TRENDS]); weight with its weigh-ins ([weights]). [combined] and
 * [combinedSleep] are today's readings more than one device measured ("From 2 devices" under their
 * charts); [onRecent], where given, opens the last 24 hours from the Sleep chart.
 */
@Composable
fun TrendSections(
    trends: Trends,
    weights: List<Sample>,
    units: Units,
    goals: Goals,
    zone: ZoneId,
    modules: Set<Module> = Module.DEFAULT,
    vitalsTargets: VitalsTargets = VitalsTargets(),
    water: Boolean = true,
    micros: Boolean = false,
    combined: List<com.health.api.CombinedValue> = emptyList(),
    combinedSleep: com.health.api.CombinedSleep? = null,
    onRecent: (() -> Unit)? = null,
    onWeighIn: () -> Unit = {},
    onDeleteWeight: (Long) -> Unit = {},
) {
    val days = trends.days
    val labels = axis(days)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Sections(arrangedIn(FeatureScreens.TRENDS, trendsSections(modules, water, micros))) { id ->
            when (id) {
                "scores" -> ScoresCard(days, labels)
                "weight" -> WeightCard(weights, units, goals, zone, onAdd = onWeighIn, onDelete = onDeleteWeight)
                "workouts" -> WorkoutsSummary(days)
                NUTRIENT -> NutrientTrendCard(days, trends.nutritionTarget, labels)
                // Combined devices: the chart's "From 2 devices", if today's reading has more than one (ui/components/Sources.kt).
                in TREND_CHARTS -> androidx.compose.runtime.CompositionLocalProvider(
                    com.health.app.ui.components.LocalChartSources provides com.health.app.ui.components.Sources.chart(id, combined, combinedSleep, units, zone),
                ) { TrendChart(id, days, labels, goals, units, onRecent) }
                else -> TrackingTrend(id, days, labels, vitalsTargets)
            }
        }
    }
}

/** The chart of a chosen vitamin, mineral or the like (its id is saved: keep it). */
internal const val NUTRIENT = "nutrient"

/** Trends' own charts' ids; the rest are [TrackingTrend]'s. */
private val TREND_CHARTS = setOf("mood", "doses", "period", "trips", "leaks", "sleep", "steps", "heart", "hrv", "calories", "water")

/** One of Trends' own charts ([TREND_CHARTS]), by its section id; Sleep's chevron opens the last 24 hours ([onRecent]). */
@Composable
private fun TrendChart(id: String, days: List<TrendDay>, labels: Triple<String, String?, String>, goals: Goals, units: Units, onRecent: (() -> Unit)? = null) {
    when (id) {
        "mood" -> ChartCard(
            "Mood", ModuleInfo.icon(Module.MIND), MaterialTheme.colorScheme.primary,
            average(days.map { it.mood })?.let { Moods.label(Moods.nearest(it)) }, "on average", labels,
            footer = days.sumOf { it.mindfulMinutes ?: 0.0 }.takeIf { it > 0 }?.let { "${Format.minutes(it.roundToInt())} of calm tools" },
        ) { LineChart(days.map { it.mood }, color = MaterialTheme.colorScheme.primary, min = 1.0, max = 5.0) }
        "doses" -> {
            val due = days.sumOf { it.dosesDue ?: 0 }
            val taken = days.sumOf { minOf(it.dosesTaken ?: 0, it.dosesDue ?: 0) }
            ChartCard(
                "Doses taken", ModuleInfo.icon(Module.MEDICATIONS), MaterialTheme.colorScheme.tertiary,
                if (due > 0) "${(taken * 100.0 / due).roundToInt()}%" else null, "of $due scheduled", labels,
            ) {
                BarChart(
                    days.map { d -> d.dosesDue?.takeIf { it > 0 }?.let { (d.dosesTaken ?: 0).coerceAtMost(it) * 100.0 / it } },
                    color = MaterialTheme.colorScheme.tertiary, goal = 100.0,
                )
            }
        }
        "period" -> ChartCard(
            "Period days", ModuleInfo.icon(Module.CYCLE), AppTheme.data.protein,
            days.count { it.flow != null }.takeIf { it > 0 }?.toString(), "in these ${days.size} days", labels,
        ) { BarChart(days.map { d -> d.flow?.let { it.ordinal + 1.0 } }, color = AppTheme.data.protein) }
        "trips" -> ChartCard(
            "Toilet trips", ModuleInfo.icon(Module.CONTINENCE), AppTheme.data.water,
            average(days.map { it.voids?.toDouble() })?.let { Format.compact(it) }, "a day", labels,
            footer = average(days.map { it.leaks?.toDouble() })?.let { "${Format.compact(it)} leaks a day" + (average(days.map { d -> d.nightVoids?.toDouble() })?.let { n -> " · ${Format.compact(n)} trips at night" } ?: "") },
        ) { BarChart(days.map { it.voids?.toDouble() }, color = AppTheme.data.water) }
        // Only once there's been a leak in the range.
        "leaks" -> if (days.any { (it.leaks ?: 0) > 0 }) {
            ChartCard(
                "Leaks", ModuleInfo.icon(Module.CONTINENCE), AppTheme.status.warn,
                days.sumOf { it.leaks ?: 0 }.toString(), "in these ${days.size} days", labels,
            ) { BarChart(days.map { it.leaks?.toDouble() }, color = AppTheme.status.warn) }
        }
        "sleep" -> ChartCard(
            "Sleep", Icons.Rounded.Bedtime, AppTheme.data.rem,
            average(days.map { it.sleepMinutes?.toDouble() })?.let { Format.minutes(it.roundToInt()) },
            "a night", labels,
            onOpen = onRecent, openLabel = "last 24 hours",
        ) { BarChart(days.map { d -> d.sleepMinutes?.let { it / 60.0 } }, color = AppTheme.data.rem, goal = goals.sleepMinutes / 60.0) }
        "steps" -> ChartCard(
            "Steps", Icons.AutoMirrored.Rounded.DirectionsWalk, MaterialTheme.colorScheme.tertiary,
            average(days.map { it.steps?.toDouble() })?.let { Format.number(it) }, "a day", labels,
        ) { BarChart(days.map { it.steps?.toDouble() }, color = MaterialTheme.colorScheme.tertiary, goal = goals.steps.toDouble()) }
        "heart" -> ChartCard(
            "Resting heart rate", Icons.Rounded.Favorite, AppTheme.data.heart,
            average(days.map { it.restingHeartRate })?.let { "${it.roundToInt()} bpm" }, "on average", labels,
        ) { LineChart(days.map { it.restingHeartRate }, color = AppTheme.data.heart) }
        "hrv" -> ChartCard(
            "Heart rate variability", Icons.Rounded.MonitorHeart, MaterialTheme.colorScheme.primary,
            average(days.map { it.hrv })?.let { "${it.roundToInt()} ms" }, "on average", labels,
        ) { LineChart(days.map { it.hrv }, color = MaterialTheme.colorScheme.primary) }
        "calories" -> ChartCard(
            "Calories eaten", Icons.Rounded.Restaurant, AppTheme.data.carbs,
            average(days.map { it.calories })?.let { "${Format.number(it)} kcal" }, "a day you logged", labels,
            footer = average(days.map { it.protein })?.let { "${Format.number(it)} g protein a day" },
        ) { BarChart(days.map { it.calories }, color = AppTheme.data.carbs, goal = goals.calories?.toDouble()) }
        "water" -> ChartCard(
            "Water", Icons.Rounded.WaterDrop, AppTheme.data.water,
            average(days.map { d -> d.waterMl?.takeIf { it > 0 }?.toDouble() })?.let { Format.water(it.roundToInt(), units) }, "a day you logged", labels,
        ) { BarChart(days.map { d -> d.waterMl?.takeIf { it > 0 }?.toDouble() }, color = AppTheme.data.water, goal = goals.waterMl.toDouble()) }
    }
}

private fun average(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

private fun axis(days: List<TrendDay>): Triple<String, String?, String> {
    val first = days.firstOrNull()?.date?.let { Format.shortDay(LocalDate.parse(it)) } ?: ""
    val last = days.lastOrNull()?.date?.let { Format.shortDay(LocalDate.parse(it)) } ?: ""
    val middle = days.getOrNull(days.size / 2)?.date?.takeIf { days.size > 8 }?.let { Format.shortDay(LocalDate.parse(it)) }
    return Triple(first, middle, last)
}

@Composable
internal fun ChartCard(
    title: String,
    icon: ImageVector,
    tint: Color,
    headline: String?,
    caption: String,
    labels: Triple<String, String?, String>,
    footer: String? = null,
    onOpen: (() -> Unit)? = null,
    openLabel: String? = null,
    chart: @Composable () -> Unit,
) {
    TitledCard(
        title,
        trailing = onOpen?.let { open ->
            {
                IconButton(onClick = open) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open ${openLabel ?: title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(headline ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (headline == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(if (headline == null) "nothing recorded yet" else caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Column {
            chart()
            AxisLabels(labels.first, labels.second, labels.third)
        }
        footer?.let { Hint(it) }
        // Combined devices: "From 2 devices", where Body provides one for this chart (ui/components/Sources.kt).
        com.health.app.ui.components.LocalChartSources.current?.invoke()
    }
}

@Composable
private fun ScoresCard(days: List<TrendDay>, labels: Triple<String, String?, String>) {
    TitledCard("Scores") {
        listOf(
            Triple("Readiness", days.map { it.readiness?.toDouble() }, MaterialTheme.colorScheme.primary),
            Triple("Sleep", days.map { it.sleepScore?.toDouble() }, AppTheme.data.rem),
            Triple("Activity", days.map { it.activityScore?.toDouble() }, MaterialTheme.colorScheme.tertiary),
        ).forEach { (name, values, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(92.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(average(values)?.let { "avg ${it.roundToInt()}" } ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LineChart(values, Modifier.weight(1f), color = color, height = 44.dp, min = 0.0, max = 100.0, dots = false)
            }
        }
        AxisLabels(labels.first, labels.second, labels.third, Modifier.padding(start = 92.dp))
    }
}

@Composable
private fun WeightCard(weights: List<Sample>, units: Units, goals: Goals, zone: ZoneId, onAdd: () -> Unit, onDelete: (Long) -> Unit) {
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    TitledCard("Weight", trailing = if (readOnly) null else ({ TitleAction(Icons.Rounded.Add, "Weigh in", onAdd) })) {
        if (weights.isEmpty()) {
            Hint(if (readOnly) "No weigh-ins yet." else "Weigh in now and then (or connect a smart scale through Health Connect) to see your trend.")
            return@TitledCard
        }
        val latest = weights.last()
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.weight(latest.value, units), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            val first = weights.first()
            val change = latest.value - first.value
            if (weights.size > 1) {
                Text(
                    "${if (change >= 0) "+" else "−"}${Format.compact(kotlin.math.abs(Format.weightValue(change, units)))} ${Format.weightUnit(units)} since ${Format.shortDay(Instant.ofEpochMilli(first.start).atZone(zone).toLocalDate())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        LineChart(weights.map { Format.weightValue(it.value, units) }, color = MaterialTheme.colorScheme.secondary, goal = goals.targetWeightKg?.let { Format.weightValue(it, units) })
        Column {
        weights.takeLast(4).reversed().forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(Format.shortDay(Instant.ofEpochMilli(w.start).atZone(zone).toLocalDate()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Format.weight(w.value, units), style = MaterialTheme.typography.bodyMedium)
                val id = w.id
                val source = w.source
                if (!readOnly && id != null && (source == null || source.contains("hand", ignoreCase = true))) {
                    IconButton(onClick = { onDelete(id) }) { Icon(Icons.Rounded.Close, "Delete", Modifier.size(18.dp)) }
                } else Spacer(Modifier.width(48.dp))
            }
        }
        }
    }
}

@Composable
private fun WorkoutsSummary(days: List<TrendDay>) {
    val count = days.sumOf { it.workouts }
    val minutes = days.sumOf { it.workoutMinutes }
    TitledCard("Workouts") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(
                if (count == 0) "none in these ${days.size} days" else "in ${days.size} days · ${Format.minutes(minutes)} in all",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (count > 0) {
            BarChart(days.map { it.workoutMinutes.toDouble().takeIf { m -> m > 0 } }, color = MaterialTheme.colorScheme.primary, height = 56.dp)
        }
    }
}
