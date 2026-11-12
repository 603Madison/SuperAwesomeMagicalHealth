@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.CheckIn
import com.health.api.ContinenceKind
import com.health.api.DueDose
import com.health.api.Module
import com.health.api.DaySummary
import com.health.api.Insight
import com.health.api.Score
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.Units
import com.health.api.Workout
import com.health.app.ui.AppIcons
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.GoalBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.HourlyBars
import com.health.app.ui.components.Hypnogram
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Ring
import com.health.app.ui.components.ScoreRing
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.stageColor
import com.health.app.ui.log.CheckInDialog
import com.health.app.ui.log.SleepDialog
import com.health.app.ui.log.WeightDialog
import com.health.app.ui.log.WorkoutDialog
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.TrackingTodayActions
import com.health.app.ui.tracking.TrackingTodayCards
import com.health.api.GlucoseUnit
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** What Today can do, as callbacks (so it can be drawn with sample data). */
class TodayActions(
    val go: (LocalDate) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val addWater: (Int) -> Unit = {},
    val logFood: (LocalDate) -> Unit = {},
    val logWeight: (Double) -> Unit = {},
    val logWorkout: (Workout) -> Unit = {},
    val logSleep: (com.health.api.SleepSession) -> Unit = {},
    val saveCheckIn: (CheckIn) -> Unit = {},
    val openDevices: () -> Unit = {},
    /** Opens a module's page ("mind", "cycle?log=2026-11-12", "continence", "meds"). */
    val open: (String) -> Unit = {},
    val quickMood: (Int) -> Unit = {},
    val logContinence: (ContinenceKind) -> Unit = {},
    val takeDose: (DueDose, String?) -> Unit = { _, _ -> },
    /** Blood pressure and glucose, habits, symptoms, drinks (ui/tracking). */
    val tracking: TrackingTodayActions = TrackingTodayActions(),
)

@Composable
fun TodayRoute(onLogFood: (LocalDate) -> Unit, onOpenDevices: () -> Unit, onOpen: (String) -> Unit = {}) {
    val c = appContainer()
    val vm = viewModel { TodayViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    TodayScreen(
        state = state,
        units = settings.units,
        zone = c.zone(),
        modules = profile?.modules ?: Module.DEFAULT,
        actions = TodayActions(
            go = vm::go,
            refresh = vm::refresh,
            retry = { vm.load() },
            addWater = vm::addWater,
            logFood = onLogFood,
            logWeight = vm::logWeight,
            logWorkout = vm::logWorkout,
            logSleep = vm::logSleep,
            saveCheckIn = vm::saveCheckIn,
            openDevices = onOpenDevices,
            open = onOpen,
            quickMood = vm::quickMood,
            logContinence = vm::logContinence,
            takeDose = vm::takeDose,
            tracking = remember(profile?.vitalsTargets?.glucoseUnit) { TrackingTodayActions.of(c, profile?.vitalsTargets?.glucoseUnit ?: GlucoseUnit.MMOL_L) },
        ),
    )
}

private enum class Dialog { WEIGHT, WORKOUT, SLEEP, CHECK_IN }

@Composable
fun TodayScreen(state: TodayState, units: Units, zone: ZoneId, actions: TodayActions, modules: Set<Module> = Module.DEFAULT) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, Score>?>(null) }
    val summary = state.summary

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ScreenTitle(Format.day(state.date, state.today), Format.longDay(state.date)) {
                IconButton(onClick = { actions.go(state.date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before") }
                IconButton(onClick = { actions.go(state.date.plusDays(1)) }, enabled = state.date < state.today) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after")
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.loading && summary == null -> Loading()
                    state.error != null && summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                    summary != null -> {
                        state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                        val tracking = Module.ACTIVITY in modules || Module.SLEEP in modules
                        if (tracking) Scores(summary) { name, score -> sheet = name to score }
                        if (!summary.hasData && tracking) NoDataCard(actions.openDevices)
                        summary.insights.forEach { InsightCard(it) }
                        QuickActions(
                            modules,
                            onFood = { actions.logFood(state.date) },
                            onWater = { actions.addWater(250) },
                            onWeight = { dialog = Dialog.WEIGHT },
                            onWorkout = { dialog = Dialog.WORKOUT },
                            onSleep = { dialog = Dialog.SLEEP },
                            onCheckIn = { dialog = Dialog.CHECK_IN },
                            onPeriod = { actions.open("cycle?log=${state.date}") },
                        )
                        val isToday = state.date == state.today
                        if (Module.MEDICATIONS in modules && (isToday || summary.modules.meds?.due?.isNotEmpty() == true)) {
                            MedsTodayCard(summary.modules.meds, zone, actions.takeDose) { actions.open("meds") }
                        }
                        if (Module.MIND in modules) MindTodayCard(summary.modules.mind, actions.quickMood) { actions.open("mind") }
                        if (Module.CYCLE in modules) {
                            CycleTodayCard(summary.modules.cycle, onLog = { actions.open("cycle?log=${state.date}") }) { actions.open("cycle") }
                        }
                        if (Module.CONTINENCE in modules) {
                            ContinenceTodayCard(summary.modules.continence, actions.logContinence) { actions.open("continence") }
                        }
                        TrackingTodayCards(summary, modules, units, zone, actions.tracking, actions.open)
                        if (Module.ACTIVITY in modules) ActivityCard(summary, units)
                        if (Module.SLEEP in modules) SleepCard(summary.sleepSummary, zone) { dialog = Dialog.SLEEP }
                        if (tracking) HeartCard(summary, units, zone)
                        if (Module.NUTRITION in modules) FoodCard(summary, units, onLog = { actions.logFood(state.date) }, onWater = actions.addWater)
                        if (summary.workouts.isNotEmpty() && Module.ACTIVITY in modules) WorkoutsCard(summary.workouts, units, zone)
                        if (Module.BODY in modules) BodyCard(summary, units) { dialog = Dialog.WEIGHT }
                        CheckInCard(summary.checkIn) { dialog = Dialog.CHECK_IN }
                    }
                }
            }
        }
    }

    when (dialog) {
        Dialog.WEIGHT -> WeightDialog(units, summary?.weightKg, { dialog = null }) { actions.logWeight(it); dialog = null }
        Dialog.WORKOUT -> WorkoutDialog(units, state.date, zone, { dialog = null }) { actions.logWorkout(it); dialog = null }
        Dialog.SLEEP -> SleepDialog(state.date, zone, { dialog = null }) { actions.logSleep(it); dialog = null }
        Dialog.CHECK_IN -> CheckInDialog(summary?.checkIn ?: CheckIn(state.date.toString()), { dialog = null }) { actions.saveCheckIn(it); dialog = null }
        null -> Unit
    }

    sheet?.let { (name, score) ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) { ScoreDetails(name, score) }
    }
}

@Composable
private fun Scores(summary: DaySummary, onOpen: (String, Score) -> Unit) {
    AppCard(padding = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Readiness" to summary.readiness, "Sleep" to summary.sleep, "Activity" to summary.activity).forEach { (name, score) ->
                ScoreRing(
                    name, score?.score, score?.label,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = score != null) { score?.let { onOpen(name, it) } }
                        .padding(6.dp),
                )
            }
        }
    }
}

/** A score's parts, each with its own 0–100: what the bottom sheet shows. */
@Composable
fun ScoreDetails(name: String, score: Score) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(score.score / 100f, size = 132.dp, stroke = 12.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${score.score}", style = MaterialTheme.typography.displaySmall)
                Text(score.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            score.contributors.forEach { part ->
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(part.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(part.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(part.score / 100f, color = contributorColor(part.score))
                    part.detail?.let { Hint(it, Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun contributorColor(score: Int): Color = when {
    score >= 85 -> MaterialTheme.colorScheme.tertiary
    score >= 70 -> MaterialTheme.colorScheme.primary
    score >= 50 -> AppTheme.status.warn
    else -> AppTheme.status.bad
}

@Composable
private fun NoDataCard(onOpenDevices: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Nothing recorded yet", style = MaterialTheme.typography.titleSmall)
                Hint("Connect your ring or watch, or log something by hand, and your scores fill in.")
            }
        }
        TextButton(onClick = onOpenDevices, modifier = Modifier.align(Alignment.End)) { Text("Connect a device") }
    }
}

@Composable
fun InsightCard(insight: Insight) {
    val tone = when (insight.tone) {
        "good" -> AppTheme.status.good
        "warn" -> AppTheme.status.warn
        else -> MaterialTheme.colorScheme.primary
    }
    AppCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.insight(insight.icon), null, tint = tone, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(insight.title, style = MaterialTheme.typography.titleSmall)
                Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuickActions(
    modules: Set<Module>,
    onFood: () -> Unit,
    onWater: () -> Unit,
    onWeight: () -> Unit,
    onWorkout: () -> Unit,
    onSleep: () -> Unit,
    onCheckIn: () -> Unit,
    onPeriod: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (Module.NUTRITION in modules) {
            QuickAction(Icons.Rounded.Restaurant, "Food", onFood)
            QuickAction(Icons.Rounded.WaterDrop, "+250 ml", onWater)
        }
        if (Module.CYCLE in modules) QuickAction(Icons.Rounded.CalendarMonth, "Period", onPeriod)
        if (Module.BODY in modules) QuickAction(Icons.Rounded.MonitorWeight, "Weight", onWeight)
        if (Module.ACTIVITY in modules) QuickAction(Icons.Rounded.FitnessCenter, "Workout", onWorkout)
        if (Module.SLEEP in modules) QuickAction(Icons.Rounded.Bedtime, "Sleep", onSleep)
        QuickAction(Icons.Rounded.Mood, "Check in", onCheckIn)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun ActivityCard(summary: DaySummary, units: Units) {
    val goal = summary.goals.steps
    InfoCard("Activity", icon = Icons.AutoMirrored.Rounded.DirectionsWalk) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.number(summary.steps), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(8.dp))
            Text("of ${Format.number(goal)} steps", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(if (goal > 0) summary.steps.toFloat() / goal else 0f, color = MaterialTheme.colorScheme.tertiary, height = 10.dp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(Format.number(summary.activeCalories), "of ${Format.number(summary.goals.activeCalories)} active", Modifier.weight(1f), unit = "kcal")
            Stat(Format.distance(summary.distanceM, units).substringBefore(' '), "distance", Modifier.weight(1f), unit = Format.distanceUnit(units))
            summary.restingCalories?.let {
                Stat(Format.number(it + summary.activeCalories), "burned in all", Modifier.weight(1f), unit = "kcal")
            }
        }
        if (summary.hourlySteps.any { it > 0 }) {
            Spacer(Modifier.height(14.dp))
            HourlyBars(summary.hourlySteps, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun SleepCard(sleep: SleepSummary?, zone: ZoneId, onAdd: () -> Unit) {
    InfoCard("Sleep", icon = Icons.Rounded.Bedtime, iconTint = AppTheme.data.rem) {
        if (sleep == null) {
            Hint("No sleep recorded for last night.")
            TextButton(onClick = onAdd, contentPadding = PaddingValues(0.dp)) { Text("Add it by hand") }
            return@InfoCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.minutes(sleep.asleepMinutes), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(8.dp))
            Text("asleep", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            "${Format.timeOfDay(sleep.start, zone)} – ${Format.timeOfDay(sleep.end, zone)} · ${sleep.efficiency}% efficient" +
                (sleep.lowestHeartRate?.let { " · lowest ${it.roundToInt()} bpm" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sleep.staged && sleep.stages.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Hypnogram(sleep.stages, sleep.start, sleep.end)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendItem(stageColor(SleepStage.DEEP), "Deep", value = Format.minutes(sleep.deepMinutes))
                LegendItem(stageColor(SleepStage.REM), "REM", value = Format.minutes(sleep.remMinutes))
                LegendItem(stageColor(SleepStage.LIGHT), "Light", value = Format.minutes(sleep.lightMinutes))
                LegendItem(stageColor(SleepStage.AWAKE), "Awake", value = Format.minutes(sleep.awakeMinutes))
            }
        }
    }
}

@Composable
private fun HeartCard(summary: DaySummary, units: Units, zone: ZoneId) {
    val any = summary.restingHeartRate != null || summary.hrv != null || summary.heartRate.isNotEmpty() || summary.spo2 != null
    if (!any) return
    InfoCard("Heart", icon = Icons.Rounded.Favorite, iconTint = AppTheme.data.heart) {
        Row(Modifier.fillMaxWidth()) {
            Stat(summary.restingHeartRate?.roundToInt()?.toString() ?: "—", "resting", Modifier.weight(1f), unit = "bpm")
            Stat(summary.hrv?.roundToInt()?.toString() ?: "—", "HRV", Modifier.weight(1f), unit = "ms")
            summary.spo2?.let { Stat("${it.roundToInt()}", "blood oxygen", Modifier.weight(0.8f), unit = "%") }
        }
        if (summary.heartRate.size >= 2) {
            Spacer(Modifier.height(14.dp))
            LineChart(summary.heartRate.map { it.v }, color = AppTheme.data.heart, height = 80.dp, dots = false)
            AxisLabels(
                Format.timeOfDay(summary.heartRate.first().t, zone),
                null,
                Format.timeOfDay(summary.heartRate.last().t, zone),
            )
            Text(
                "Range ${summary.minHeartRate?.roundToInt() ?: "—"}–${summary.maxHeartRate?.roundToInt() ?: "—"} bpm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val extras = listOfNotNull(
            summary.temperatureDeviation?.let { "Temperature ${Format.temperatureDelta(it, units)}" },
            summary.respiratoryRate?.let { "Breathing ${Format.compact(it)} per min" },
            summary.stress?.let { "Stress ${it.roundToInt()}" },
        )
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.forEach { Pill(it, MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun FoodCard(summary: DaySummary, units: Units, onLog: () -> Unit, onWater: (Int) -> Unit) {
    val target = summary.nutritionTarget
    val eaten = summary.nutrition
    InfoCard("Food", icon = Icons.Rounded.Restaurant, trailing = {
        TextButton(onClick = onLog) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Log food")
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fraction = target?.calories?.takeIf { it > 0 }?.let { (eaten.calories / it).toFloat() }
            Ring(fraction ?: 0f, size = 100.dp, stroke = 9.dp, colors = listOf(AppTheme.data.carbs, AppTheme.data.protein)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Format.number(eaten.calories), style = MaterialTheme.typography.titleLarge)
                    Text(target?.let { "of ${Format.number(it.calories)}" } ?: "kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalBar("Protein", eaten.protein, target?.protein?.toDouble(), "g", AppTheme.data.protein)
                GoalBar("Carbs", eaten.carbs, target?.carbs?.toDouble(), "g", AppTheme.data.carbs)
                GoalBar("Fat", eaten.fat, target?.fat?.toDouble(), "g", AppTheme.data.fat)
            }
        }
        target?.let {
            val left = it.calories - eaten.calories
            Text(
                if (left >= 0) "${Format.number(left)} kcal left · ${it.basis}" else "${Format.number(-left)} kcal over · ${it.basis}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val waterGoal = target?.waterMl ?: summary.goals.waterMl
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WaterDrop, null, tint = AppTheme.data.water, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${Format.water(summary.waterMl, units)} of ${Format.water(waterGoal, units)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                ProgressBar(if (waterGoal > 0) summary.waterMl.toFloat() / waterGoal else 0f, color = AppTheme.data.water)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onWater(250) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+250 ml") }
        }
    }
}

@Composable
fun WorkoutsCard(workouts: List<Workout>, units: Units, zone: ZoneId) {
    InfoCard("Workouts", icon = Icons.Rounded.FitnessCenter) {
        workouts.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            WorkoutRow(w, units, zone)
        }
    }
}

@Composable
fun WorkoutRow(w: Workout, units: Units, zone: ZoneId) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(AppIcons.workout(w.type), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.name ?: w.type.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                Format.minutes(((w.end - w.start) / 60_000).toInt()),
                w.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, units) },
                w.activeCalories?.takeIf { it > 0 }?.let { "${Format.number(it)} kcal" },
                w.avgHeartRate?.let { "${it.roundToInt()} bpm avg" },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Format.timeOfDay(w.start, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BodyCard(summary: DaySummary, units: Units, onLog: () -> Unit) {
    InfoCard("Body", icon = Icons.Rounded.MonitorWeight, trailing = {
        TextButton(onClick = onLog) { Text(if (summary.weightKg == null) "Weigh in" else "Update") }
    }) {
        Row(Modifier.fillMaxWidth()) {
            Stat(summary.weightKg?.let { Format.compact(Format.weightValue(it, units)) } ?: "—", "weight", Modifier.weight(1f), unit = Format.weightUnit(units))
            summary.bodyFat?.let { Stat(Format.compact(it), "body fat", Modifier.weight(1f), unit = "%") }
            summary.goals.targetWeightKg?.let { Stat(Format.compact(Format.weightValue(it, units)), "goal", Modifier.weight(1f), unit = Format.weightUnit(units)) }
        }
    }
}

@Composable
private fun CheckInCard(checkIn: CheckIn?, onOpen: () -> Unit) {
    val filled = checkIn != null && (checkIn.mood != null || checkIn.energy != null || checkIn.stress != null || checkIn.soreness != null || checkIn.tags.isNotEmpty() || checkIn.note != null)
    InfoCard("How you feel", icon = Icons.Rounded.Mood, onClick = onOpen) {
        if (!filled) {
            Hint("How are you feeling today? A quick check-in helps make sense of your scores.")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpen) { Text("Check in") }
            return@InfoCard
        }
        checkIn!!
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            checkIn.mood?.let { Pill("Mood $it/5", MaterialTheme.colorScheme.primary) }
            checkIn.energy?.let { Pill("Energy $it/5", MaterialTheme.colorScheme.tertiary) }
            checkIn.stress?.let { Pill("Stress $it/5", AppTheme.status.warn) }
            checkIn.soreness?.let { Pill("Soreness $it/5", AppTheme.data.protein) }
            checkIn.tags.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        checkIn.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
        }
    }
}
