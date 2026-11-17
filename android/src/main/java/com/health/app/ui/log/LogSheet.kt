@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.CheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceWearing
import com.health.api.DrinkKind
import com.health.api.Fields
import com.health.api.GlucoseUnit
import com.health.api.Layouts
import com.health.api.MeasurementInput
import com.health.api.Metric
import com.health.api.MoodEntry
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.QuickAction
import com.health.api.SleepSession
import com.health.api.SymptomEntry
import com.health.api.Units
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.app.Container
import com.health.app.attempt
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.screenLayout
import com.health.app.ui.components.showsField
import com.health.app.ui.mealNow
import com.health.app.ui.modules.FaceScale
import com.health.app.ui.modules.continence.QuickButtons
import com.health.app.ui.modules.continence.WearText
import com.health.app.ui.today.QUICK_WATER_ML
import com.health.app.ui.today.QuickActions
import com.health.app.ui.today.TodayLayout
import com.health.app.ui.today.TodayRows
import com.health.app.ui.today.nowColors
import com.health.app.ui.today.nowIcon
import com.health.app.ui.tracking.BloodPressureDialog
import com.health.app.ui.tracking.DrinkDialog
import com.health.app.ui.tracking.GlucoseDialog
import com.health.app.ui.tracking.SymptomForm
import com.health.app.ui.tracking.TrackingTodayActions
import com.health.app.ui.tracking.quickDrink
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * The + sheet, from the round button in the middle of the tabs: the one way to log anything. What's
 * live first (Right now's actions, and the meal it is), then every way to log for the features on,
 * four across, in the person's order (today.quick). Each opens the log flow it always has.
 */

/** Logging from anywhere: each goes to the server in the app's scope (closing the sheet can't stop it), then the app reloads. */
class LogActions(private val c: Container) {
    private fun act(message: String?, block: suspend () -> Unit) {
        c.scope.launch {
            attempt { block() }
                .onSuccess { message?.let(c::tell); c.dataChanged() }
                .onFailure { c.tell(it.message ?: "That didn't work") }
        }
    }

    private fun today(): LocalDate = LocalDate.now(c.zone())

    fun addWater(ml: Int) = act("Added $ml ml of water") { c.ui.addWater(WaterEntry(today().toString(), ml)) }

    fun logWeight(kg: Double) = act("Weight saved") { c.ui.addMeasurement(MeasurementInput(Metric.WEIGHT, kg, System.currentTimeMillis())) }

    fun logWorkout(workout: Workout) = act("Workout saved") { c.ui.addWorkout(workout) }

    fun logSleep(session: SleepSession) = act("Sleep saved") { c.ui.addSleep(session) }

    fun saveCheckIn(checkIn: CheckIn) = act("Thanks for checking in") { c.ui.saveCheckIn(checkIn) }

    fun quickMood(mood: Int) = act("Mood saved") { c.ui.addMood(MoodEntry(System.currentTimeMillis(), mood)) }

    /** A one-tap bladder or bowel log: an accident is in what's on now ([wearing]). */
    fun logContinence(kind: ContinenceKind, wearing: ContinenceWearing?) = act("${kind.label} noted") {
        c.ui.addContinence(ContinenceEntry(System.currentTimeMillis(), kind, supplyId = WearText.quickSupply(kind, wearing)))
    }

    companion object {
        fun of(c: Container) = LogActions(c)
    }
}

/** What the + sheet's buttons do. */
class LogSheetActions(
    /** A Right now action (Change, Take, Start…). */
    val now: (NowItem) -> Unit = {},
    /** The meal suggestion: food for the meal it is. */
    val meal: () -> Unit = {},
    val quick: (QuickAction) -> Unit = {},
    /** Choosing the grid's order. */
    val arrange: () -> Unit = {},
)

/** The sheet's content: [now] its Right now buttons, [time] what "now" is, [meal] and [mealLine] the suggestion, [grid] every way to log. */
@Composable
fun LogSheetContent(
    now: List<NowItem>,
    time: String,
    meal: String?,
    mealLine: String,
    grid: List<QuickAction>,
    units: Units,
    actions: LogSheetActions,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            Text("now · $time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (now.isNotEmpty() || meal != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Right now")
                now.forEach { item ->
                    val (container, content) = nowColors(item)
                    BigButton(
                        nowIcon(item.kind), item.action?.label ?: item.title, listOfNotNull(item.title.takeIf { item.action != null }, item.detail).joinToString(" · "),
                        container, content, TodayRows.spoken(item),
                    ) { actions.now(item) }
                }
                if (meal != null) {
                    BigButton(
                        Icons.Rounded.Restaurant, meal, mealLine,
                        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Log $meal".lowercase().replaceFirstChar(Char::uppercase),
                        actions.meal,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Anything else")
            grid.chunked(4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { a -> GridTile(QuickActions.icon(a), QuickActions.short(a, units), QuickActions.spoken(a, units), Modifier.weight(1f)) { actions.quick(a) } }
                    repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Only what you track shows here, in the order you choose.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.arrange) {
                    Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp).padding(end = 2.dp))
                    Text("Arrange")
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
}

/** A big tinted button: an icon, what it does, and a line about it. */
@Composable
private fun BigButton(icon: ImageVector, title: String, line: String, container: Color, content: Color, spoken: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp), color = container, contentColor = content,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = if (line.isBlank()) spoken else "$spoken. $line"
            role = Role.Button
            onClick(spoken) { onClick(); true }
        },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One way to log in the grid: its icon and short name, read out in full. */
@Composable
private fun GridTile(icon: ImageVector, word: String, spoken: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
            onClick(spoken) { onClick(); true }
        },
    ) {
        Column(Modifier.heightIn(min = 76.dp).padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(word, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class Dialog { WEIGHT, WORKOUT, SLEEP, CHECK_IN, DRINK, BLOOD_PRESSURE, GLUCOSE }

private enum class Sheet { MOOD, BLADDER, CHANGE, SYMPTOM, ARRANGE }

/**
 * The + sheet while [showing], and the log flows it opens (which stay after it closes). [now] is
 * today's Right now (the day feed's); [plan] tonight's planned meal; [wearing] what's on now (an
 * accident goes in it); [checkIn] today's check-in so far; [weightKg] the last weight; [open]
 * opens a page; [takeDose] takes a dose (the medication, when it was due, its name).
 */
@Composable
fun LogHost(
    showing: Boolean,
    onClose: () -> Unit,
    open: (String) -> Unit,
    now: List<NowItem> = emptyList(),
    plan: String? = null,
    wearing: ContinenceWearing? = null,
    checkIn: CheckIn? = null,
    weightKg: Double? = null,
    takeDose: (String, Long?, String) -> Unit = { _, _, _ -> },
) {
    val c = appContainer()
    val log = remember(c) { LogActions.of(c) }
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    val units = settings.units
    val zone = c.zone()
    val tracking = remember(profile?.vitalsTargets?.glucoseUnit) { TrackingTodayActions.of(c, profile?.vitalsTargets?.glucoseUnit ?: GlucoseUnit.MMOL_L) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val today = LocalDate.now(zone)
    val modules = com.health.app.ui.care.shownModules()
    val available = TodayLayout.quickActions(modules, showsField(Fields.NUTRITION_WATER), com.health.app.ui.modules.body.measurementsShown(), energy = showsField(Fields.NEURO_ENERGY))

    fun quick(action: QuickAction) {
        onClose()
        when (action) {
            QuickAction.FOOD -> open("food/add/$today/${mealNow().name}")
            QuickAction.WATER -> log.addWater(QUICK_WATER_ML)
            QuickAction.MOOD -> sheet = Sheet.MOOD
            QuickAction.PERIOD -> open("cycle?log=$today")
            QuickAction.BLADDER -> sheet = Sheet.BLADDER
            QuickAction.DOSE -> open("meds")
            QuickAction.WEIGHT -> dialog = Dialog.WEIGHT
            QuickAction.COFFEE -> tracking.quickDrink(DrinkKind.COFFEE)
            QuickAction.DRINK -> dialog = Dialog.DRINK
            QuickAction.WORKOUT -> dialog = Dialog.WORKOUT
            QuickAction.SLEEP -> dialog = Dialog.SLEEP
            QuickAction.CHECK_IN -> dialog = Dialog.CHECK_IN
            QuickAction.SYMPTOM -> sheet = Sheet.SYMPTOM
            QuickAction.BLOOD_PRESSURE -> dialog = Dialog.BLOOD_PRESSURE
            QuickAction.GLUCOSE -> dialog = Dialog.GLUCOSE
            QuickAction.HABIT -> open("habits")
            QuickAction.ENERGY -> open("neuro/energy")
            QuickAction.OVERLOAD -> open("neuro/overload")
            QuickAction.CALM -> open("mind/tool/box-breathing")
            QuickAction.MOMENT -> open("transition/moment")
            QuickAction.PHOTO -> open("photos/add?id=camera")
            QuickAction.MEASUREMENTS -> open("measurements")
            QuickAction.APPOINTMENT -> open("appointments/edit")
        }
    }

    if (showing) {
        ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            val at = System.currentTimeMillis()
            LogSheetContent(
                now = TodayRows.logNow(now),
                time = com.health.app.ui.Format.timeOfDay(at, zone),
                meal = if (com.health.api.Module.NUTRITION in modules) TodayRows.mealTitle(java.time.Instant.ofEpochMilli(at).atZone(zone).hour) else null,
                mealLine = plan?.let { "Or tonight's plan: $it, from Mealie" } ?: "Log what you're eating",
                grid = TodayLayout.grid(available, screenLayout(Layouts.QUICK)),
                units = units,
                actions = LogSheetActions(
                    now = { item ->
                        onClose()
                        val a = item.action
                        when (a?.action) {
                            NowAction.CONTINENCE_CHANGE -> sheet = Sheet.CHANGE
                            NowAction.DOSE_TAKE -> TodayRows.id(a, "medicationId")?.let { takeDose(it, TodayRows.id(a, "scheduledFor")?.toLongOrNull(), item.title) }
                            else -> TodayRows.route(item, modules, today.toString())?.let(open)
                        }
                    },
                    meal = { onClose(); open("food/add/$today/${mealNow().name}") },
                    quick = ::quick,
                    arrange = { onClose(); sheet = Sheet.ARRANGE },
                ),
            )
        }
    }

    val close = { dialog = null }
    when (dialog) {
        Dialog.WEIGHT -> WeightDialog(units, weightKg, close) { log.logWeight(it); close() }
        Dialog.WORKOUT -> WorkoutDialog(units, today, zone, close) { log.logWorkout(it); close() }
        Dialog.SLEEP -> SleepDialog(today, zone, close) { log.logSleep(it); close() }
        Dialog.CHECK_IN -> CheckInDialog(checkIn ?: CheckIn(today.toString()), close) { log.saveCheckIn(it); close() }
        Dialog.DRINK -> DrinkDialog(quickDrink(DrinkKind.COFFEE), units, close, onSave = { tracking.saveDrink(it); close() })
        Dialog.BLOOD_PRESSURE -> BloodPressureDialog(close) { tracking.logBloodPressure(it); close() }
        Dialog.GLUCOSE -> GlucoseDialog(tracking.glucoseUnit, close) { tracking.logGlucose(it); close() }
        null -> Unit
    }

    val closeSheet = { sheet = null }
    when (sheet) {
        Sheet.MOOD -> ModalBottomSheet(onDismissRequest = closeSheet) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("How are you feeling?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(16.dp))
                FaceScale(null, { mood -> mood?.let { log.quickMood(it); closeSheet() } })
            }
        }
        Sheet.BLADDER -> ModalBottomSheet(onDismissRequest = closeSheet) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("Bladder & bowel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                QuickButtons({ kind -> if (kind == ContinenceKind.PAD_CHANGE) sheet = Sheet.CHANGE else { log.logContinence(kind, wearing); closeSheet() } })
            }
        }
        Sheet.CHANGE -> com.health.app.ui.modules.continence.ChangeSheet(onDismiss = closeSheet)
        Sheet.SYMPTOM -> ModalBottomSheet(onDismissRequest = closeSheet) {
            SymptomForm(SymptomEntry(System.currentTimeMillis(), "", 4), onSave = { tracking.logSymptom(it); closeSheet() }, onDelete = null)
        }
        Sheet.ARRANGE -> ArrangeSheet(Layouts.QUICK, "the + sheet", available.map { ArrangeItem(it.name, it.label) }, closeSheet, orderOnly = true)
        null -> Unit
    }
}
