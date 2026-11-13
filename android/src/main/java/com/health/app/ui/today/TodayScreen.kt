@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.BladderTraining
import com.health.api.CheckIn
import com.health.api.ContinenceKind
import com.health.api.DaySummary
import com.health.api.DrinkKind
import com.health.api.DueDose
import com.health.api.Fields
import com.health.api.GlucoseUnit
import com.health.api.Insight
import com.health.api.Layouts
import com.health.api.Meal
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.Score
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
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Ring
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.arrangedBy
import com.health.app.ui.components.foodNumbers
import com.health.app.ui.components.screenLayout
import com.health.app.ui.components.showsField
import com.health.app.ui.log.CheckInDialog
import com.health.app.ui.log.SleepDialog
import com.health.app.ui.log.WeightDialog
import com.health.app.ui.log.WorkoutDialog
import com.health.app.ui.modules.FaceScale
import com.health.app.ui.modules.continence.QuickButtons
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
    val skipDose: (DueDose) -> Unit = {},
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
    val readOnly = LocalReadOnly.current
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
            skipDose = vm::skipDose,
            tracking = remember(profile?.vitalsTargets?.glucoseUnit) { TrackingTodayActions.of(c, profile?.vitalsTargets?.glucoseUnit ?: GlucoseUnit.MMOL_L) },
            addIdea = vm::addIdea,
        ),
        serverNotice = com.health.app.ui.components.serverNotice(),
        foodNumbers = foodNumbers(),
        // Bladder training's next trip is the person's own (kept on the phone for reminders): never someone else's share.
        bladder = remember(state.summary, readOnly) { if (readOnly) null else c.reminderCache.bladder() },
    )
}

private enum class Dialog { WEIGHT, WORKOUT, SLEEP, CHECK_IN, DRINK, BLOOD_PRESSURE, GLUCOSE }

private enum class Sheet { MOOD, BLADDER, SYMPTOM, LOG, ARRANGE_TODAY, ARRANGE_QUICK }

/**
 * Today, top to bottom by default: the scores, Up next, For you, Quick log, a card for each
 * feature with something today, and Also today for those with nothing yet, all in the person's
 * order ([Layouts.TODAY]); "+ Log" opens every way to log. [water] is whether water is shown (a
 * field that can be hidden), [measurements] whether body measurements are, [foodNumbers] whether
 * amounts of food are (not in eating disorder recovery). [bladder] is the person's bladder
 * training, for its next trip; [now] the time Up next works from.
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
    foodNumbers: Boolean = true,
    bladder: BladderTraining? = null,
    now: Long = System.currentTimeMillis(),
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
    val quickLayout = screenLayout(Layouts.QUICK)
    val quick = TodayLayout.quickRow(quickAvailable, quickLayout)
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

    /** Also today's "+" for a feature: the same as its quick action, or its card's. */
    fun alsoAction(section: TodaySection): CardAction? = when (section) {
        TodaySection.MEDS -> CardAction(Icons.Rounded.Add, "Take a dose") { actions.open("meds") }
        TodaySection.MIND -> CardAction(Icons.Rounded.Add, "Log your mood") { sheet = Sheet.MOOD }
        TodaySection.CYCLE -> CardAction(Icons.Rounded.Add, "Log your cycle today") { actions.open("cycle?log=${state.date}") }
        TodaySection.CONTINENCE -> CardAction(Icons.Rounded.Add, "Log bladder and bowel") { sheet = Sheet.BLADDER }
        TodaySection.NEURO -> CardAction(Icons.Rounded.Add, "Log energy and focus") { actions.open("neuro/energy") }
        TodaySection.TRANSITION -> CardAction(Icons.Rounded.Add, "Note a moment of euphoria") { actions.open("transition/moment") }
        TodaySection.VITALS -> CardAction(
            Icons.Rounded.Add, "Log blood pressure or glucose",
            menu = listOf("Blood pressure" to { dialog = Dialog.BLOOD_PRESSURE }, "Glucose" to { dialog = Dialog.GLUCOSE }),
        )
        TodaySection.DRINKS -> CardAction(Icons.Rounded.Add, "Add a drink") { dialog = Dialog.DRINK }
        TodaySection.SYMPTOMS -> CardAction(Icons.Rounded.Add, "Log a symptom") { sheet = Sheet.SYMPTOM }
        TodaySection.HABITS -> CardAction(Icons.Rounded.Add, "Open habits") { actions.open("habits") }
        TodaySection.APPOINTMENTS -> CardAction(Icons.Rounded.Add, "Add an appointment") { actions.open("appointments/edit") }
        TodaySection.ACTIVITY -> CardAction(Icons.Rounded.Add, "Log a workout") { dialog = Dialog.WORKOUT }
        TodaySection.SLEEP -> CardAction(Icons.Rounded.Add, "Add a night's sleep") { dialog = Dialog.SLEEP }
        TodaySection.FOOD -> CardAction(Icons.Rounded.Add, "Log food") { actions.logFood(state.date) }
        TodaySection.WATER -> CardAction(Icons.Rounded.Add, "Add ${Format.water(QUICK_WATER_ML, units)} of water") { actions.addWater(QUICK_WATER_ML) }
        TodaySection.BODY -> CardAction(Icons.Rounded.Add, "Log your weight") { dialog = Dialog.WEIGHT }
        else -> null
    }

    val logs = !readOnly
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (logs) 96.dp else 24.dp)) {
                ScreenTitle(Format.day(state.date, state.today), Format.longDay(state.date)) {
                    IconButton(onClick = { actions.go(state.date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before") }
                    IconButton(onClick = { actions.go(state.date.plusDays(1)) }, enabled = state.date < state.today) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after")
                    }
                    Box {
                        ArrangeButton { arrangeMenu = true }
                        DropdownMenu(arrangeMenu, onDismissRequest = { arrangeMenu = false }) {
                            DropdownMenuItem(text = { Text("Arrange Today") }, onClick = { arrangeMenu = false; sheet = Sheet.ARRANGE_TODAY })
                            DropdownMenuItem(text = { Text("Choose what's in Quick log") }, onClick = { arrangeMenu = false; sheet = Sheet.ARRANGE_QUICK })
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    serverNotice?.let { com.health.app.ui.components.ServerUpdateNotice(it) }
                    when {
                        state.loading && summary == null -> Loading()
                        state.error != null && summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                        summary != null -> {
                            state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                            val isToday = state.date == state.today
                            val m = summary.modules
                            val open = actions.open
                            val upNext = if (TodaySection.UP_NEXT in visible) TodayPlan.upNext(summary, modules, visible, now, zone, isToday, bladder) else emptyList()
                            val day = TodayPlan.Day(summary, modules, upNext, now, zone, state.meals)
                            val also = if (TodaySection.ALSO in visible) TodayPlan.also(visible, day) else emptyList()
                            visible.forEach { section ->
                                key(section) {
                                    when (section) {
                                        TodaySection.SCORES -> ScoresStrip(
                                            summary.readiness, summary.sleep, summary.activity, isToday, readOnly,
                                            onScore = { name, s -> score = name to s }, onDevices = actions.openDevices,
                                        )
                                        TodaySection.UP_NEXT -> UpNextCard(
                                            upNext, readOnly,
                                            UpNextActions(
                                                take = { item -> item.dose?.let { actions.takeDose(it, null) } },
                                                skip = { item -> item.dose?.let(actions.skipDose) },
                                                trip = { actions.logContinence(ContinenceKind.URINATION) },
                                                open = open,
                                            ),
                                        )
                                        TodaySection.INSIGHTS -> ForYouCard(
                                            // The day's doses and today's appointment aren't repeated while Up next shows them.
                                            InsightLinks.shown(
                                                summary.insights, modules,
                                                // Transition's card says this week's euphoria itself, so its insight steps aside.
                                                TodayPlan.showing(upNext) + listOfNotNull(
                                                    TodaySection.TRANSITION.takeIf { it in visible && (summary.modules.transition?.euphoriaThisWeek ?: 0) > 0 },
                                                ),
                                            ),
                                            targetOf = { InsightLinks.of(it, modules, readOnly, measurements) },
                                            onOpen = { t -> t.route?.let(open) ?: summary.readiness?.let { score = "Readiness" to it } },
                                            readOnly = readOnly,
                                            onAdd = actions.addIdea,
                                            showNumbers = foodNumbers,
                                        )
                                        TodaySection.QUICK -> if (logs) QuickLogRow(quick, units, ::doQuick, onMore = { sheet = Sheet.LOG })
                                        TodaySection.ALSO -> AlsoTodayCard(
                                            also.map { s -> AlsoRow(s, TodayLayout.label(s, modules), TodayPlan.alsoStatus(s, day, isToday), if (logs) alsoAction(s) else null) },
                                            modules, isToday,
                                        )
                                        else -> if (TodayPlan.place(section, day) == TodayPlan.Place.CARD) when (section) {
                                            TodaySection.MIND -> m.mind?.let { MindTodayCard(it, onLog = { sheet = Sheet.MOOD }) { open("mind") } }
                                            TodaySection.CYCLE -> m.cycle?.let {
                                                CycleTodayCard(it, com.health.app.ui.modules.cycle.plainOnToday(), onLog = { open("cycle?log=${state.date}") }) { open("cycle") }
                                            }
                                            TodaySection.CONTINENCE -> m.continence?.let { ContinenceTodayCard(it, onLog = { sheet = Sheet.BLADDER }) { open("continence") } }
                                            TodaySection.NEURO -> m.neuro?.let { n ->
                                                val energyOnly = Module.NEURO !in modules
                                                NeuroTodayCard(n, energyOnly, onLog = if (energyOnly) null else ({ open("neuro/energy") })) { open(if (energyOnly) "mind" else "neuro") }
                                            }
                                            TodaySection.TRANSITION -> m.transition?.let { TransitionTodayCard(it, onLog = { open("transition/moment") }) { open("transition") } }
                                            TodaySection.VITALS -> m.vitals?.let {
                                                VitalsTodayCard(
                                                    it, actions.tracking.glucoseUnit, readOnly,
                                                    onBloodPressure = { dialog = Dialog.BLOOD_PRESSURE }, onGlucose = { dialog = Dialog.GLUCOSE },
                                                ) { open("vitals") }
                                            }
                                            TodaySection.DRINKS -> summary.drinks?.let { DrinksTodayCard(it, readOnly, onAdd = { dialog = Dialog.DRINK }) { open("food") } }
                                            TodaySection.SYMPTOMS -> m.symptoms?.let { SymptomsTodayCard(it, readOnly, onLog = { sheet = Sheet.SYMPTOM }) { open("symptoms") } }
                                            TodaySection.HABITS -> HabitsTodayCard(m.habits.orEmpty()) { open("habits") }
                                            TodaySection.HORMONES -> m.hormones?.let { HormonesTodayCard(it, state.date) { open("hormones") } }
                                            TodaySection.LIFE_STAGE -> m.lifeStage?.let { LifeStageTodayCard(it, zone) { open("cycle") } }
                                            TodaySection.APPOINTMENTS -> AppointmentsTodayCard(TodayPlan.laterAppointments(day), zone, now) { open("appointments") }
                                            TodaySection.ACTIVITY -> ActivityCard(summary, units, foodNumbers) { open("trends") }
                                            TodaySection.SLEEP -> summary.sleepSummary?.let { SleepCard(it, zone) { open("trends") } }
                                            TodaySection.HEART -> HeartCard(summary, units) { open("trends") }
                                            TodaySection.STRESS -> StressTodayCard(summary.stressDay, summary.resilience) { open("trends") }
                                            TodaySection.FOOD -> FoodCard(summary, readOnly, foodNumbers, state.meals, onLog = { actions.logFood(state.date) }) { open("food") }
                                            TodaySection.WATER -> WaterTodayCard(
                                                summary.waterMl, summary.nutritionTarget?.waterMl ?: summary.goals.waterMl, units, target = foodNumbers,
                                                onAdd = if (readOnly || quickHasWater) null else ({ actions.addWater(QUICK_WATER_ML) }),
                                            ) { open("food") }
                                            TodaySection.WORKOUTS -> WorkoutsCard(summary.workouts, units, zone, foodNumbers) { open("trends") }
                                            TodaySection.BODY -> BodyCard(summary, units, readOnly, foodNumbers, onLog = { dialog = Dialog.WEIGHT }) {
                                                open(if (measurements) "measurements" else "trends")
                                            }
                                            else -> Unit
                                        }
                                    }
                                    Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        if (logs) {
            ExtendedFloatingActionButton(
                onClick = { sheet = Sheet.LOG },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Log") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
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
        Sheet.LOG -> ModalBottomSheet(onDismissRequest = closeSheet) {
            LogSheetContent(TodayLayout.logGroups(quickAvailable, quickLayout), units, ::doQuick, onArrange = { sheet = Sheet.ARRANGE_QUICK })
        }
        Sheet.ARRANGE_TODAY -> ArrangeSheet(Layouts.TODAY, "Today", sections.map { ArrangeItem(it.name, TodayLayout.label(it, modules)) }, closeSheet)
        Sheet.ARRANGE_QUICK -> ArrangeSheet(
            Layouts.QUICK, "Quick log", quickAvailable.map { ArrangeItem(it.name, it.label) }, closeSheet,
            hiddenMeans = "Under More", defaultHidden = TodayLayout.defaultUnderMore(quickAvailable),
        )
        null -> Unit
    }

    score?.let { (name, s) ->
        ModalBottomSheet(onDismissRequest = { score = null }) { ScoreDetails(name, s, checkIn = if (name == "Readiness") summary?.checkIn else null) }
    }
}

/** The day's check-in as small labels, under readiness (it's part of it). */
@Composable
private fun CheckInPills(checkIn: CheckIn) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        checkIn.mood?.let { Pill("Mood $it/5", MaterialTheme.colorScheme.primary) }
        checkIn.energy?.let { Pill("Energy $it/5", MaterialTheme.colorScheme.tertiary) }
        checkIn.stress?.let { Pill("Stress $it/5", AppTheme.status.warn) }
        checkIn.soreness?.let { Pill("Soreness $it/5", AppTheme.data.protein) }
        checkIn.tags.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    checkIn.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start, modifier = Modifier.padding(top = 8.dp)) }
}

/** A score's parts, each with its own 0–100: what the bottom sheet shows (readiness with the day's check-in). */
@Composable
fun ScoreDetails(name: String, score: Score, checkIn: CheckIn? = null) {
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
            checkIn?.takeIf { it.mood != null || it.energy != null || it.stress != null || it.soreness != null || it.tags.isNotEmpty() || it.note != null }?.let {
                Column(Modifier.fillMaxWidth()) {
                    Text("How you said you feel", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    CheckInPills(it)
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

/** An insight as a card of its own (the features' pages use these; Today has "For you"). */
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

/** Steps against the goal; distance and active calories (not in eating disorder recovery: [numbers]) under them. */
@Composable
private fun ActivityCard(summary: DaySummary, units: Units, numbers: Boolean, onOpen: () -> Unit) {
    val goal = summary.goals.steps
    InfoCard("Activity", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
        Figure(
            Format.number(summary.steps), if (goal > 0) "of ${Format.number(goal)} steps" else "steps",
            listOfNotNull(
                summary.distanceM.takeIf { it > 0 }?.let { Format.distance(it, units) },
                summary.activeCalories.takeIf { numbers && it > 0 }?.let { "${Format.number(it)} kcal active" },
            ).joinToString(" · ").ifEmpty { null },
        )
        // Combined devices: "From 2 devices" where more than one counted these (ui/components/Sources.kt).
        com.health.app.ui.components.CombinedSources("Activity", summary.combined, com.health.app.ui.components.Sources.ACTIVITY, units)
    }
}

/** Last night: time asleep, then when and how well. */
@Composable
private fun SleepCard(sleep: SleepSummary, zone: ZoneId, onOpen: () -> Unit) {
    InfoCard("Sleep", icon = Icons.Rounded.Bedtime, iconTint = AppTheme.data.rem, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
        Figure(
            Format.minutes(sleep.asleepMinutes), "asleep",
            "${Format.timeOfDay(sleep.start, zone)} – ${Format.timeOfDay(sleep.end, zone)} · ${sleep.efficiency}% efficient",
        )
        // Combined devices: "From 2 devices" where more than one recorded the night (ui/components/Sources.kt).
        sleep.combined?.let { com.health.app.ui.components.CombinedSleepSources(it, zone) }
    }
}

/** Resting heart rate, with HRV and blood oxygen under it. */
@Composable
private fun HeartCard(summary: DaySummary, units: Units, onOpen: () -> Unit) {
    InfoCard("Heart", icon = Icons.Rounded.Favorite, iconTint = AppTheme.data.heart, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
        val hrv = summary.hrv?.let { "HRV ${it.roundToInt()} ms" }
        val spo2 = summary.spo2?.let { "blood oxygen ${it.roundToInt()}%" }
        when {
            summary.restingHeartRate != null -> Figure("${summary.restingHeartRate!!.roundToInt()}", "bpm resting", listOfNotNull(hrv, spo2).joinToString(" · ").ifEmpty { null })
            summary.hrv != null -> Figure("${summary.hrv!!.roundToInt()}", "ms HRV", spo2?.replaceFirstChar(Char::uppercase))
            summary.spo2 != null -> Figure("${summary.spo2!!.roundToInt()}%", "blood oxygen")
            else -> Figure("${summary.minHeartRate?.roundToInt() ?: "—"}–${summary.maxHeartRate?.roundToInt() ?: "—"}", "bpm today")
        }
        // Combined devices: "From 2 devices" where more than one measured these (ui/components/Sources.kt).
        com.health.app.ui.components.CombinedSources("Heart", summary.combined, com.health.app.ui.components.Sources.HEART, units)
    }
}

/**
 * Calories against the target and protein; when amounts of food don't show ([numbers]: eating
 * disorder recovery), just which meals are logged ([meals]).
 */
@Composable
private fun FoodCard(summary: DaySummary, readOnly: Boolean, numbers: Boolean, meals: List<Meal>?, onLog: () -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Food", icon = Icons.Rounded.Restaurant, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log food", onClick = onLog),
    ) {
        if (numbers) {
            val target = summary.nutritionTarget
            val eaten = summary.nutrition
            Figure(
                Format.number(eaten.calories), target?.let { "of ${Format.number(it.calories)} kcal" } ?: "kcal",
                target?.protein?.takeIf { it > 0 }?.let { "Protein ${Format.number(eaten.protein)} of $it g" } ?: "${Format.number(eaten.protein)} g protein",
            )
        } else {
            Headline(TodayPlan.mealsLogged(meals) ?: "Food logged today")
        }
    }
}

/** The day's workouts: one by name with its time and distance, or how many. */
@Composable
fun WorkoutsCard(workouts: List<Workout>, units: Units, zone: ZoneId, numbers: Boolean = true, onOpen: (() -> Unit)? = null) {
    if (workouts.isEmpty()) return
    InfoCard("Workouts", icon = Icons.Rounded.FitnessCenter, onClick = onOpen, onOpen = onOpen, openLabel = "trends") {
        val sorted = workouts.sortedBy { it.start }
        val minutes = sorted.sumOf { ((it.end - it.start) / 60_000).toInt() }
        if (sorted.size == 1) {
            val w = sorted.first()
            Headline(
                w.name ?: w.type.label,
                supporting = listOfNotNull(
                    Format.minutes(minutes),
                    Format.timeOfDay(w.start, zone),
                    w.distanceM?.takeIf { it > 0 }?.let { Format.distance(it, units) },
                    w.activeCalories?.takeIf { numbers && it > 0 }?.let { "${Format.number(it)} kcal" },
                ).joinToString(" · "),
            )
        } else {
            Headline("${sorted.size} workouts", supporting = "${Format.minutes(minutes)} · ${sorted.joinToString { it.name ?: it.type.label }}")
        }
    }
}

/** Weight (or body fat), with body fat and the goal under it; no goal in eating disorder recovery ([numbers]). */
@Composable
private fun BodyCard(summary: DaySummary, units: Units, readOnly: Boolean, numbers: Boolean, onLog: () -> Unit, onOpen: () -> Unit) {
    InfoCard(
        "Body", icon = Icons.Rounded.MonitorWeight, onClick = onOpen, onOpen = onOpen,
        action = if (readOnly) null else CardAction(Icons.Rounded.Add, "Log your weight", onClick = onLog),
    ) {
        val fat = summary.bodyFat?.let { "${Format.compact(it)}% body fat" }
        val goal = summary.goals.targetWeightKg?.takeIf { numbers }?.let { "goal ${Format.compact(Format.weightValue(it, units))} ${Format.weightUnit(units)}" }
        val weight = summary.weightKg
        if (weight != null) Figure(Format.compact(Format.weightValue(weight, units)), Format.weightUnit(units), listOfNotNull(fat, goal).joinToString(" · ").replaceFirstChar(Char::uppercase).ifEmpty { null })
        else Figure(Format.compact(summary.bodyFat ?: 0.0) + "%", "body fat", goal?.replaceFirstChar(Char::uppercase))
    }
}
