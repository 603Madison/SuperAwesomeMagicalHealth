@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.CheckIn
import com.health.api.ContinenceKind
import com.health.api.DaySummary
import com.health.api.DrinkKind
import com.health.api.DueDose
import com.health.api.Fields
import com.health.api.GlucoseUnit
import com.health.api.Insight
import com.health.api.LifeStage
import com.health.api.Layouts
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.Score
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.SymptomEntry
import com.health.api.TodaySection
import com.health.api.Units
import com.health.api.Workout
import com.health.app.ui.AppIcons
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.CardAction
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
import com.health.app.ui.components.arrangedBy
import com.health.app.ui.components.screenLayout
import com.health.app.ui.components.showsField
import com.health.app.ui.components.stageColor
import com.health.app.ui.log.CheckInDialog
import com.health.app.ui.log.SleepDialog
import com.health.app.ui.log.WeightDialog
import com.health.app.ui.log.WorkoutDialog
import com.health.app.ui.modules.FaceScale
import com.health.app.ui.modules.continence.QuickButtons
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.BloodPressureDialog
import com.health.app.ui.tracking.DrinkDialog
import com.health.app.ui.tracking.GlucoseDialog
import com.health.app.ui.tracking.SymptomForm
import com.health.app.ui.tracking.TrackingTodayActions
import com.health.app.ui.tracking.quickDrink
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
    /** Opens a page ("mind", "cycle?log=2026-11-12", "continence", "meds", "food", "trends"). */
    val open: (String) -> Unit = {},
    val quickMood: (Int) -> Unit = {},
    val logContinence: (ContinenceKind) -> Unit = {},
    val takeDose: (DueDose, String?) -> Unit = { _, _ -> },
    /** Blood pressure and glucose, habits, symptoms, drinks (ui/tracking). */
    val tracking: TrackingTodayActions = TrackingTodayActions(),
    /** Logs a food idea from the protein insight (its items, at its meal), with Undo. */
    val addIdea: (com.health.api.FoodSuggestion) -> Unit = {},
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
        modules = com.health.app.ui.care.shownModules(),
        water = showsField(Fields.NUTRITION_WATER),
        measurements = com.health.app.ui.modules.body.measurementsShown(),
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
            addIdea = vm::addIdea,
        ),
        serverNotice = com.health.app.ui.components.serverNotice(),
    )
}

private enum class Dialog { WEIGHT, WORKOUT, SLEEP, CHECK_IN, DRINK, BLOOD_PRESSURE, GLUCOSE }

private enum class Sheet { MOOD, BLADDER, SYMPTOM, MORE, ARRANGE_TODAY, ARRANGE_QUICK }

/**
 * Today: a card for each feature that's on, in the person's order ([Layouts.TODAY]), and the quick
 * actions ([Layouts.QUICK]): the first few on Today, the rest under "More". [water] is whether
 * water is shown (a field that can be hidden); [measurements] whether body measurements are.
 */
@Composable
fun TodayScreen(
    state: TodayState,
    units: Units,
    zone: ZoneId,
    actions: TodayActions,
    modules: Set<Module> = Module.DEFAULT,
    water: Boolean = true,
    measurements: Boolean = false,
    serverNotice: com.health.app.ui.components.ServerNoticeState? = null,
) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var score by remember { mutableStateOf<Pair<String, Score>?>(null) }
    var arrangeMenu by remember { mutableStateOf(false) }
    val summary = state.summary
    val readOnly = LocalReadOnly.current

    val sections = TodayLayout.sections(modules, water)
    val visible = arrangedBy(Layouts.TODAY, sections) { it.name }
    val quickAvailable = TodayLayout.quickActions(modules, water, measurements)
    val quick = TodayLayout.quickRow(quickAvailable, screenLayout(Layouts.QUICK))
    // Water is logged from one place: its quick action when that's on Today, else the Water card.
    val quickHasWater = !readOnly && TodaySection.QUICK in visible && QuickAction.WATER in quick.shown

    fun doQuick(action: QuickAction) {
        sheet = null
        when (action) {
            QuickAction.FOOD -> actions.logFood(state.date)
            QuickAction.WATER -> actions.addWater(QUICK_WATER_ML)
            QuickAction.MOOD -> sheet = Sheet.MOOD
            QuickAction.PERIOD -> actions.open("cycle?log=${state.date}")
            QuickAction.BLADDER -> sheet = Sheet.BLADDER
            QuickAction.DOSE -> actions.open("meds")
            QuickAction.WEIGHT -> dialog = Dialog.WEIGHT
            QuickAction.COFFEE -> actions.tracking.quickDrink(DrinkKind.COFFEE)
            QuickAction.DRINK -> dialog = Dialog.DRINK
            QuickAction.WORKOUT -> dialog = Dialog.WORKOUT
            QuickAction.SLEEP -> dialog = Dialog.SLEEP
            QuickAction.CHECK_IN -> dialog = Dialog.CHECK_IN
            QuickAction.SYMPTOM -> sheet = Sheet.SYMPTOM
            QuickAction.BLOOD_PRESSURE -> dialog = Dialog.BLOOD_PRESSURE
            QuickAction.GLUCOSE -> dialog = Dialog.GLUCOSE
            QuickAction.HABIT -> actions.open("habits")
            QuickAction.ENERGY -> actions.open("neuro/energy")
            QuickAction.OVERLOAD -> actions.open("neuro/overload")
            QuickAction.CALM -> actions.open("mind/tool/box-breathing")
            QuickAction.MOMENT -> actions.open("transition/moment")
            QuickAction.PHOTO -> actions.open("photos/add?id=camera")
            QuickAction.MEASUREMENTS -> actions.open("measurements")
            QuickAction.APPOINTMENT -> actions.open("appointments/edit")
        }
    }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ScreenTitle(Format.day(state.date, state.today), Format.longDay(state.date)) {
                IconButton(onClick = { actions.go(state.date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before") }
                IconButton(onClick = { actions.go(state.date.plusDays(1)) }, enabled = state.date < state.today) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after")
                }
                Box {
                    ArrangeButton { arrangeMenu = true }
                    DropdownMenu(arrangeMenu, onDismissRequest = { arrangeMenu = false }) {
                        DropdownMenuItem(text = { Text("Arrange Today's cards") }, onClick = { arrangeMenu = false; sheet = Sheet.ARRANGE_TODAY })
                        DropdownMenuItem(text = { Text("Choose quick actions") }, onClick = { arrangeMenu = false; sheet = Sheet.ARRANGE_QUICK })
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                serverNotice?.let { com.health.app.ui.components.ServerUpdateNotice(it) }
                when {
                    state.loading && summary == null -> Loading()
                    state.error != null && summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                    summary != null -> {
                        state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                        val isToday = state.date == state.today
                        val m = summary.modules
                        val open = actions.open
                        visible.forEach { section ->
                            key(section) {
                                when (section) {
                                    TodaySection.SCORES -> ScoresCard(
                                        summary, isToday, readOnly,
                                        onScore = { name, s -> score = name to s },
                                        onConnect = actions.openDevices,
                                        onCheckIn = { dialog = Dialog.CHECK_IN },
                                    )
                                    TodaySection.INSIGHTS -> ForYouCard(
                                        // The day's doses aren't repeated while Medications shows them.
                                        InsightLinks.shown(summary.insights, modules, if (TodaySection.MEDS in visible && m.meds?.due?.isNotEmpty() == true) setOf(TodaySection.MEDS) else emptySet()),
                                        targetOf = { InsightLinks.of(it, modules, readOnly, measurements) },
                                        onOpen = { t -> t.route?.let(open) ?: summary.readiness?.let { score = "Readiness" to it } },
                                        readOnly = readOnly,
                                        onAdd = actions.addIdea,
                                    )
                                    TodaySection.QUICK -> if (!readOnly) QuickActionsRow(quick, units, ::doQuick, onMore = { sheet = Sheet.MORE })
                                    TodaySection.MEDS -> if (isToday || m.meds?.due?.isNotEmpty() == true) MedsTodayCard(m.meds, zone, actions.takeDose) { open("meds") }
                                    TodaySection.MIND -> MindTodayCard(m.mind, actions.quickMood) { open("mind") }
                                    TodaySection.CYCLE -> CycleTodayCard(m.cycle, onLog = { open("cycle?log=${state.date}") }) { open("cycle") }
                                    TodaySection.CONTINENCE -> ContinenceTodayCard(m.continence, actions.logContinence) { open("continence") }
                                    TodaySection.SUPPLIES -> m.lowSupplies?.takeIf { it.isNotEmpty() }?.let { low ->
                                        SuppliesTodayCard(low) { open("supplies/" + SupplyArea.forLow(low).route) }
                                    }
                                    TodaySection.NEURO -> m.neuro?.let { n ->
                                        val energyOnly = Module.NEURO !in modules
                                        NeuroTodayCard(n, energyOnly) { open(if (energyOnly) "mind" else "neuro") }
                                    }
                                    TodaySection.TRANSITION -> m.transition?.let { TransitionTodayCard(it) { open("transition") } }
                                    TodaySection.VITALS -> VitalsTodayCard(
                                        m.vitals, actions.tracking.glucoseUnit, readOnly,
                                        onBloodPressure = { dialog = Dialog.BLOOD_PRESSURE }, onGlucose = { dialog = Dialog.GLUCOSE },
                                    ) { open("vitals") }
                                    TodaySection.DRINKS -> DrinksTodayCard(summary.drinks, units, water, readOnly, onAdd = { dialog = Dialog.DRINK }) { open("food") }
                                    TodaySection.SYMPTOMS -> SymptomsTodayCard(m.symptoms, readOnly, onLog = { sheet = Sheet.SYMPTOM }) { open("symptoms") }
                                    TodaySection.HABITS -> HabitsTodayCard(m.habits.orEmpty(), readOnly, actions.tracking.tickHabit) { open("habits") }
                                    TodaySection.HORMONES -> m.hormones?.let { HormonesTodayCard(it, state.date) { open("hormones") } }
                                    TodaySection.LIFE_STAGE -> m.lifeStage?.takeIf { it.stage != LifeStage.CYCLING }?.let { LifeStageTodayCard(it, zone) { open("cycle") } }
                                    TodaySection.APPOINTMENTS -> m.appointments?.takeIf { it.isNotEmpty() }?.let {
                                        AppointmentsTodayCard(it, zone, System.currentTimeMillis()) { open("appointments") }
                                    }
                                    TodaySection.ACTIVITY -> ActivityCard(summary, units) { open("trends") }
                                    TodaySection.SLEEP -> SleepCard(summary.sleepSummary, zone, readOnly, onAdd = { dialog = Dialog.SLEEP }) { open("trends") }
                                    TodaySection.HEART -> HeartCard(summary, units, zone) { open("trends") }
                                    TodaySection.STRESS -> if (summary.stressDay != null || summary.resilience != null) {
                                        StressTodayCard(summary.stressDay, summary.resilience) { open("trends") }
                                    }
                                    TodaySection.FOOD -> FoodCard(summary, readOnly, onLog = { actions.logFood(state.date) }) { open("food") }
                                    TodaySection.WATER -> WaterTodayCard(
                                        summary.waterMl, summary.nutritionTarget?.waterMl ?: summary.goals.waterMl, units,
                                        onAdd = if (readOnly || quickHasWater) null else ({ actions.addWater(QUICK_WATER_ML) }),
                                    ) { open("food") }
                                    TodaySection.WORKOUTS -> if (summary.workouts.isNotEmpty()) WorkoutsCard(summary.workouts, units, zone) { open("trends") }
                                    TodaySection.BODY -> BodyCard(summary, units, readOnly, onLog = { dialog = Dialog.WEIGHT }) { open(if (measurements) "measurements" else "trends") }
                                }
                                Unit
                            }
                        }
                    }
                }
            }
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.WEIGHT -> WeightDialog(units, summary?.weightKg, close) { actions.logWeight(it); close() }
        Dialog.WORKOUT -> WorkoutDialog(units, state.date, zone, close) { actions.logWorkout(it); close() }
        Dialog.SLEEP -> SleepDialog(state.date, zone, close) { actions.logSleep(it); close() }
        Dialog.CHECK_IN -> CheckInDialog(summary?.checkIn ?: CheckIn(state.date.toString()), close) { actions.saveCheckIn(it); close() }
        Dialog.DRINK -> DrinkDialog(quickDrink(DrinkKind.COFFEE), units, close, onSave = { actions.tracking.saveDrink(it); close() })
        Dialog.BLOOD_PRESSURE -> BloodPressureDialog(close) { actions.tracking.logBloodPressure(it); close() }
        Dialog.GLUCOSE -> GlucoseDialog(actions.tracking.glucoseUnit, close) { actions.tracking.logGlucose(it); close() }
        null -> Unit
    }

    val closeSheet = { sheet = null }
    when (sheet) {
        Sheet.MOOD -> ModalBottomSheet(onDismissRequest = closeSheet) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("How are you feeling?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(16.dp))
                FaceScale(null, { mood -> mood?.let { actions.quickMood(it); closeSheet() } })
            }
        }
        Sheet.BLADDER -> ModalBottomSheet(onDismissRequest = closeSheet) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("Bladder & bowel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                QuickButtons({ kind -> actions.logContinence(kind); closeSheet() })
            }
        }
        Sheet.SYMPTOM -> ModalBottomSheet(onDismissRequest = closeSheet) {
            SymptomForm(SymptomEntry(System.currentTimeMillis(), "", 4), onSave = { actions.tracking.logSymptom(it); closeSheet() }, onDelete = null)
        }
        Sheet.MORE -> ModalBottomSheet(onDismissRequest = closeSheet) {
            QuickMoreContent(quick.more, units, ::doQuick, onArrange = { sheet = Sheet.ARRANGE_QUICK })
        }
        Sheet.ARRANGE_TODAY -> ArrangeSheet(Layouts.TODAY, "Today", sections.map { ArrangeItem(it.name, TodayLayout.label(it, modules)) }, closeSheet)
        Sheet.ARRANGE_QUICK -> ArrangeSheet(
            Layouts.QUICK, "quick actions", quickAvailable.map { ArrangeItem(it.name, it.label) }, closeSheet,
            hiddenMeans = "Under More", defaultHidden = TodayLayout.defaultUnderMore(quickAvailable),
        )
        null -> Unit
    }

    score?.let { (name, s) ->
        ModalBottomSheet(onDismissRequest = { score = null }) { ScoreDetails(name, s) }
    }
}

/**
 * Readiness, sleep and activity, as rings; with none yet, one short line instead (and the way to
 * connect a device, when nothing's come in). How the person feels, once they've checked in, goes
 * with them: it's part of readiness.
 */
@Composable
private fun ScoresCard(summary: DaySummary, isToday: Boolean, readOnly: Boolean, onScore: (String, Score) -> Unit, onConnect: () -> Unit, onCheckIn: () -> Unit) {
    val scores = listOf("Readiness" to summary.readiness, "Sleep" to summary.sleep, "Activity" to summary.activity)
    val checkIn = summary.checkIn?.takeIf { it.mood != null || it.energy != null || it.stress != null || it.soreness != null || it.tags.isNotEmpty() || it.note != null }
    if (scores.all { it.second == null }) {
        InfoCard(
            if (isToday) "No scores yet today" else "No scores for this day", icon = Icons.Rounded.Watch,
            trailing = if (!summary.hasData && !readOnly) ({ TextButton(onClick = onConnect) { Text("Connect a device") } }) else null,
            content = checkIn?.let { c -> { CheckInPills(c, readOnly, onCheckIn) } },
        )
        return
    }
    AppCard(padding = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            scores.forEach { (name, s) ->
                ScoreRing(
                    name, s?.score, s?.label,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = s != null) { s?.let { onScore(name, it) } }
                        .padding(6.dp),
                )
            }
        }
        when {
            checkIn != null -> {
                Spacer(Modifier.height(12.dp))
                CheckInPills(checkIn, readOnly, onCheckIn)
            }
            isToday && !readOnly -> TextButton(onClick = onCheckIn, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("How are you feeling? Check in") }
        }
    }
}

/** The day's check-in as small labels; a tap changes it. */
@Composable
private fun CheckInPills(checkIn: CheckIn, readOnly: Boolean, onEdit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (readOnly) Modifier else Modifier.clickable(onClickLabel = "Change your check-in", onClick = onEdit)),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            checkIn.mood?.let { Pill("Mood $it/5", MaterialTheme.colorScheme.primary) }
            checkIn.energy?.let { Pill("Energy $it/5", MaterialTheme.colorScheme.tertiary) }
            checkIn.stress?.let { Pill("Stress $it/5", AppTheme.status.warn) }
            checkIn.soreness?.let { Pill("Soreness $it/5", AppTheme.data.protein) }
            checkIn.tags.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        checkIn.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.padding(top = 8.dp)) }
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
private fun ActivityCard(summary: DaySummary, units: Units, onOpen: () -> Unit) {
    val goal = summary.goals.steps
    InfoCard("Activity", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
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
        // Combined devices: "From 2 devices" where more than one counted these (ui/components/Sources.kt).
        com.health.app.ui.components.CombinedSources("Activity", summary.combined, com.health.app.ui.components.Sources.ACTIVITY, units)
    }
}

@Composable
private fun SleepCard(sleep: SleepSummary?, zone: ZoneId, readOnly: Boolean, onAdd: () -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Sleep", icon = Icons.Rounded.Bedtime, iconTint = AppTheme.data.rem, onClick = onOpen, onOpen = onOpen, openLabel = "trends",
        status = if (sleep == null) "None last night" else null,
        action = if (sleep == null && !readOnly) CardAction(Icons.Rounded.Add, "Add a night by hand", onClick = onAdd) else null,
        content = sleep?.let { s -> { SleepBody(s, zone) } },
    )
}

@Composable
private fun SleepBody(sleep: SleepSummary, zone: ZoneId) {
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
    // Combined devices: "From 2 devices" where more than one recorded the night (ui/components/Sources.kt).
    sleep.combined?.let { com.health.app.ui.components.CombinedSleepSources(it, zone) }
}

@Composable
private fun HeartCard(summary: DaySummary, units: Units, zone: ZoneId, onOpen: () -> Unit) {
    val any = summary.restingHeartRate != null || summary.hrv != null || summary.heartRate.isNotEmpty() || summary.spo2 != null
    if (!any) return
    InfoCard("Heart", icon = Icons.Rounded.Favorite, iconTint = AppTheme.data.heart, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
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
        // Combined devices: "From 2 devices" where more than one measured these (ui/components/Sources.kt).
        com.health.app.ui.components.CombinedSources("Heart", summary.combined, com.health.app.ui.components.Sources.HEART, units)
    }
}

/** Calories and the macros against the target; water has its own card. */
@Composable
private fun FoodCard(summary: DaySummary, readOnly: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    val target = summary.nutritionTarget
    val eaten = summary.nutrition
    InfoCard(
        "Food", icon = Icons.Rounded.Restaurant, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log food", onClick = onLog),
    ) {
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
    }
}

@Composable
fun WorkoutsCard(workouts: List<Workout>, units: Units, zone: ZoneId, onOpen: (() -> Unit)? = null) {
    InfoCard("Workouts", icon = Icons.Rounded.FitnessCenter, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
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
private fun BodyCard(summary: DaySummary, units: Units, readOnly: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    val empty = summary.weightKg == null && summary.bodyFat == null
    InfoCard(
        "Body", icon = Icons.Rounded.MonitorWeight, onClick = onOpen, onOpen = onOpen,
        status = if (empty) "No weight yet" else null,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log your weight", onClick = onLog),
        content = if (empty) null else {
            {
                Row(Modifier.fillMaxWidth()) {
                    Stat(summary.weightKg?.let { Format.compact(Format.weightValue(it, units)) } ?: "—", "weight", Modifier.weight(1f), unit = Format.weightUnit(units))
                    summary.bodyFat?.let { Stat(Format.compact(it), "body fat", Modifier.weight(1f), unit = "%") }
                    summary.goals.targetWeightKg?.let { Stat(Format.compact(Format.weightValue(it, units)), "goal", Modifier.weight(1f), unit = Format.weightUnit(units)) }
                }
            }
        },
    )
}
