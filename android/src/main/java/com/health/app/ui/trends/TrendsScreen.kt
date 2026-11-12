package com.health.app.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Goals
import com.health.api.MeasurementInput
import com.health.api.Module
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.app.Container
import com.health.app.ui.Format
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.Moods
import com.health.app.ui.log.WeightDialog
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.TrackingTrends
import com.health.api.VitalsTargets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class TrendsState(
    val days: Int = 30,
    val trends: Trends? = null,
    val weights: List<Sample> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class TrendsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(TrendsState())
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun range(days: Int) {
        _state.update { it.copy(days = days) }
        load()
    }

    fun load(quiet: Boolean = false) {
        val days = _state.value.days
        _state.update { it.copy(loading = !quiet || it.trends == null, error = null) }
        viewModelScope.launch {
            try {
                val trends = c.ui.trends(days)
                val weights = runCatching { c.ui.measurements(Metric.WEIGHT, maxOf(days, 90)) }.getOrDefault(emptyList())
                _state.update { if (it.days == days) it.copy(trends = trends, weights = weights, loading = false) else it }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your trends") }
            }
        }
    }

    fun logWeight(kg: Double) = viewModelScope.launch {
        runCatching { c.ui.addMeasurement(MeasurementInput(Metric.WEIGHT, kg, System.currentTimeMillis())) }
            .onSuccess { c.tell("Weight saved"); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun deleteWeight(id: Long) = viewModelScope.launch {
        runCatching { c.ui.deleteMeasurement(id) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't delete that") }
    }
}

class TrendsActions(
    val range: (Int) -> Unit = {},
    val retry: () -> Unit = {},
    val logWeight: (Double) -> Unit = {},
    val deleteWeight: (Long) -> Unit = {},
)

@Composable
fun TrendsRoute() {
    val c = appContainer()
    val vm = viewModel { TrendsViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    TrendsScreen(
        state, settings.units, profile?.goals ?: Goals(), c.zone(),
        TrendsActions(vm::range, { vm.load() }, { vm.logWeight(it) }, { vm.deleteWeight(it) }),
        modules = com.health.app.ui.care.shownModules(),
        vitalsTargets = profile?.vitalsTargets ?: VitalsTargets(),
    )
}

@Composable
fun TrendsScreen(
    state: TrendsState, units: Units, goals: Goals, zone: ZoneId, actions: TrendsActions, modules: Set<Module> = Module.DEFAULT,
    vitalsTargets: VitalsTargets = VitalsTargets(),
) {
    var weighing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Trends", "How things are going over time")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(listOf(7, 30, 90), state.days, { "$it days" }, actions.range)
            val trends = state.trends
            when {
                state.loading && trends == null -> Loading()
                state.error != null && trends == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                trends != null -> {
                    val days = trends.days
                    val labels = axis(days)
                    val tracking = Module.ACTIVITY in modules || Module.SLEEP in modules
                    if (tracking) ScoresCard(days, labels)
                    if (Module.MIND in modules) {
                        ChartCard(
                            "Mood", ModuleInfo.icon(Module.MIND), MaterialTheme.colorScheme.primary,
                            average(days.map { it.mood })?.let { Moods.label(Moods.nearest(it)) }, "on average", labels,
                            footer = days.sumOf { it.mindfulMinutes ?: 0.0 }.takeIf { it > 0 }?.let { "${Format.minutes(it.roundToInt())} of calm tools" },
                        ) { LineChart(days.map { it.mood }, color = MaterialTheme.colorScheme.primary, min = 1.0, max = 5.0) }
                    }
                    if (Module.MEDICATIONS in modules && days.any { (it.dosesDue ?: 0) > 0 }) {
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
                    if (Module.CYCLE in modules) {
                        ChartCard(
                            "Period days", ModuleInfo.icon(Module.CYCLE), AppTheme.data.protein,
                            days.count { it.flow != null }.takeIf { it > 0 }?.toString(), "in these ${days.size} days", labels,
                        ) { BarChart(days.map { d -> d.flow?.let { it.ordinal + 1.0 } }, color = AppTheme.data.protein) }
                    }
                    if (Module.CONTINENCE in modules) {
                        ChartCard(
                            "Toilet trips", ModuleInfo.icon(Module.CONTINENCE), AppTheme.data.water,
                            average(days.map { it.voids?.toDouble() })?.let { Format.compact(it) }, "a day", labels,
                            footer = average(days.map { it.leaks?.toDouble() })?.let { "${Format.compact(it)} leaks a day" + (average(days.map { d -> d.nightVoids?.toDouble() })?.let { n -> " · ${Format.compact(n)} trips at night" } ?: "") },
                        ) { BarChart(days.map { it.voids?.toDouble() }, color = AppTheme.data.water) }
                        if (days.any { (it.leaks ?: 0) > 0 }) {
                            ChartCard(
                                "Leaks", ModuleInfo.icon(Module.CONTINENCE), AppTheme.status.warn,
                                days.sumOf { it.leaks ?: 0 }.toString(), "in these ${days.size} days", labels,
                            ) { BarChart(days.map { it.leaks?.toDouble() }, color = AppTheme.status.warn) }
                        }
                    }
                    if (Module.SLEEP in modules) ChartCard(
                        "Sleep", Icons.Rounded.Bedtime, AppTheme.data.rem,
                        average(days.map { it.sleepMinutes?.toDouble() })?.let { Format.minutes(it.roundToInt()) },
                        "a night", labels,
                    ) { BarChart(days.map { d -> d.sleepMinutes?.let { it / 60.0 } }, color = AppTheme.data.rem, goal = goals.sleepMinutes / 60.0) }
                    if (Module.ACTIVITY in modules) ChartCard(
                        "Steps", Icons.AutoMirrored.Rounded.DirectionsWalk, MaterialTheme.colorScheme.tertiary,
                        average(days.map { it.steps?.toDouble() })?.let { Format.number(it) }, "a day", labels,
                    ) { BarChart(days.map { it.steps?.toDouble() }, color = MaterialTheme.colorScheme.tertiary, goal = goals.steps.toDouble()) }
                    if (tracking) ChartCard(
                        "Resting heart rate", Icons.Rounded.Favorite, AppTheme.data.heart,
                        average(days.map { it.restingHeartRate })?.let { "${it.roundToInt()} bpm" }, "on average", labels,
                    ) { LineChart(days.map { it.restingHeartRate }, color = AppTheme.data.heart) }
                    if (tracking) ChartCard(
                        "Heart rate variability", Icons.Rounded.MonitorHeart, MaterialTheme.colorScheme.primary,
                        average(days.map { it.hrv })?.let { "${it.roundToInt()} ms" }, "on average", labels,
                    ) { LineChart(days.map { it.hrv }, color = MaterialTheme.colorScheme.primary) }
                    if (Module.NUTRITION in modules) ChartCard(
                        "Calories eaten", Icons.Rounded.Restaurant, AppTheme.data.carbs,
                        average(days.map { it.calories })?.let { "${Format.number(it)} kcal" }, "a day you logged", labels,
                        footer = average(days.map { it.protein })?.let { "${Format.number(it)} g protein a day" },
                    ) { BarChart(days.map { it.calories }, color = AppTheme.data.carbs, goal = goals.calories?.toDouble()) }
                    TrackingTrends(days, labels, modules, vitalsTargets)
                    if (Module.BODY in modules) WeightCard(state.weights, units, goals, zone, onAdd = { weighing = true }, onDelete = actions.deleteWeight)
                    if (Module.ACTIVITY in modules) WorkoutsSummary(days)
                }
            }
        }
    }
    if (weighing) WeightDialog(units, state.weights.lastOrNull()?.value, { weighing = false }) { actions.logWeight(it); weighing = false }
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
    chart: @Composable () -> Unit,
) {
    InfoCard(title, icon = icon, iconTint = tint) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(headline ?: "—", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(6.dp))
            Text(if (headline == null) "nothing recorded yet" else caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        chart()
        AxisLabels(labels.first, labels.second, labels.third)
        footer?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun ScoresCard(days: List<TrendDay>, labels: Triple<String, String?, String>) {
    InfoCard("Scores", icon = Icons.Rounded.AutoAwesome) {
        listOf(
            Triple("Readiness", days.map { it.readiness?.toDouble() }, MaterialTheme.colorScheme.primary),
            Triple("Sleep", days.map { it.sleepScore?.toDouble() }, AppTheme.data.rem),
            Triple("Activity", days.map { it.activityScore?.toDouble() }, MaterialTheme.colorScheme.tertiary),
        ).forEachIndexed { i, (name, values, color) ->
            if (i > 0) Spacer(Modifier.height(10.dp))
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
    InfoCard("Weight", icon = Icons.Rounded.MonitorWeight, trailing = { TextButton(onClick = onAdd) { Text("Weigh in") } }) {
        if (weights.isEmpty()) {
            Hint("Weigh in now and then (or connect a smart scale through Health Connect) to see your trend.")
            return@InfoCard
        }
        val latest = weights.last()
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.weight(latest.value, units), style = MaterialTheme.typography.headlineSmall)
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
        Spacer(Modifier.height(10.dp))
        LineChart(weights.map { Format.weightValue(it.value, units) }, color = MaterialTheme.colorScheme.secondary, goal = goals.targetWeightKg?.let { Format.weightValue(it, units) })
        Spacer(Modifier.height(8.dp))
        weights.takeLast(4).reversed().forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(Format.shortDay(Instant.ofEpochMilli(w.start).atZone(zone).toLocalDate()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Format.weight(w.value, units), style = MaterialTheme.typography.bodyMedium)
                val id = w.id
                val source = w.source
                if (id != null && (source == null || source.contains("hand", ignoreCase = true))) {
                    IconButton(onClick = { onDelete(id) }) { Icon(Icons.Rounded.Close, "Delete", Modifier.size(18.dp)) }
                } else Spacer(Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun WorkoutsSummary(days: List<TrendDay>) {
    val count = days.sumOf { it.workouts }
    val minutes = days.sumOf { it.workoutMinutes }
    InfoCard("Workouts", icon = Icons.Rounded.FitnessCenter) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$count", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                if (count == 0) "none in these ${days.size} days" else "in ${days.size} days · ${Format.minutes(minutes)} in all",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (count > 0) {
            Spacer(Modifier.height(10.dp))
            BarChart(days.map { it.workoutMinutes.toDouble().takeIf { m -> m > 0 } }, color = MaterialTheme.colorScheme.primary, height = 56.dp)
        }
    }
}
