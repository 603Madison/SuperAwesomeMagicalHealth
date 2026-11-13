@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.BladderTraining
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.Fields
import com.health.api.Supply
import com.health.app.Container
import com.health.app.net.continenceSettings
import com.health.app.net.saveContinenceSettings
import com.health.app.net.supplies
import com.health.app.reminders.BladderTrainingPlan
import com.health.app.reminders.ReminderPlanner
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.ValueRow
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.CustomFieldsEditor
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class DiarySettingsState(
    val saved: ContinenceSettings? = null,
    val supplies: List<Supply> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
)

class DiarySettingsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(DiarySettingsState())
    val state: StateFlow<DiarySettingsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val settings = async { c.api.continenceSettings() }
                val supplies = async { runCatching { c.api.supplies() }.getOrDefault(emptyList()) }
                _state.update { it.copy(saved = settings.await(), supplies = supplies.await(), loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the diary's settings") }
            }
        }
    }

    fun save(settings: ContinenceSettings) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = c.api.saveContinenceSettings(settings)
                _state.update { it.copy(saved = saved, saving = false) }
                c.tell("Saved")
                c.dataChanged()
                // Bladder training's reminders on this phone follow straight away.
                runCatching { c.refreshReminders() }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                c.tell(e.message ?: "Couldn't save that")
            }
        }
    }
}

@Composable
fun DiarySettingsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { DiarySettingsViewModel(c) }
    val state by vm.state.collectAsState()
    DiarySettingsScreen(state, shownFields(), onBack, onSave = { vm.save(it) }, onRetry = vm::load, canNotify = remember { c.reminders.canNotify() })
}

/**
 * How the diary works for this person: the quick buttons, their own words and fields, the usual
 * product, when night is, and bladder training with its reminders.
 */
@Composable
fun DiarySettingsScreen(
    state: DiarySettingsState,
    fields: Set<String>,
    onBack: () -> Unit,
    onSave: (ContinenceSettings) -> Unit = {},
    onRetry: () -> Unit = {},
    canNotify: Boolean = true,
    today: LocalDate = LocalDate.now(),
) {
    var s by remember { mutableStateOf(state.saved) }
    LaunchedEffect(state.saved) { if (state.saved != null) s = state.saved }
    var picking by remember { mutableStateOf<String?>(null) }
    val current = s
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Diary settings") {
            TextButton(enabled = current != null && current != state.saved && !state.saving, onClick = { current?.let(onSave) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.loading && current == null -> Loading()
                current == null -> ErrorCard(state.error ?: "Couldn't load the diary's settings", actionLabel = "Try again", onAction = onRetry)
                else -> {
                    fun change(f: (ContinenceSettings) -> ContinenceSettings) { s = f(current) }
                    SectionCard("Quick buttons") {
                        Hint("One tap logs these now. Pick up to eight; they show in the order you pick them.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ContinenceText.kinds(fields).forEach { k ->
                                val on = k in current.quickButtons
                                FilterChip(
                                    selected = on,
                                    onClick = { change { it.copy(quickButtons = if (on) it.quickButtons - k else (it.quickButtons + k).take(8)) } },
                                    label = { Text(k.label) },
                                )
                            }
                        }
                        if (current.quickButtons.isNotEmpty()) Hint("In order: " + current.quickButtons.joinToString(", ") { it.label })
                    }
                    if (Fields.CONTINENCE_PROTECTION in fields || Fields.CONTINENCE_SUPPLIES in fields) {
                        val worn = ContinenceText.suppliesFor(ContinenceKind.PAD_CHANGE, state.supplies)
                        SectionCard("What you usually wear") {
                            if (worn.isEmpty()) Hint("Add your products in Supplies, and the one you usually wear is chosen for you when you log.")
                            else {
                                Hint("Chosen for you when you log a change or a leak.")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = current.defaultSupplyId == null, onClick = { change { it.copy(defaultSupplyId = null) } }, label = { Text("Nothing in particular") })
                                    worn.forEach { sup ->
                                        FilterChip(selected = current.defaultSupplyId == sup.id, onClick = { change { it.copy(defaultSupplyId = sup.id) } }, label = { Text(ContinenceText.supplyName(sup)) })
                                    }
                                }
                            }
                        }
                    }
                    if (Fields.CONTINENCE_PROTECTION in fields || Fields.CONTINENCE_SUPPLIES in fields || current.changeReminderHours != null) {
                        SectionCard("Change reminder") {
                            val hours = current.changeReminderHours
                            LabeledSwitch(
                                "Remind me to check", hours != null, { on -> change { it.copy(changeReminderHours = if (on) it.changeReminderHours ?: 4 else null) } },
                                subtitle = "A quiet nudge that only says \"Time for a check\", never what you're wearing.",
                            )
                            if (hours != null) {
                                if (!canNotify) ErrorCard("Notifications are off for this app, so reminders can't show on this phone. Settings › Reminders can turn them on.")
                                Text("After", style = MaterialTheme.typography.titleSmall)
                                Choices(listOf(2, 3, 4, 5, 6, 8, 10, 12), hours, { if (it == 1) "1 hour" else "$it hours" }) { v -> change { it.copy(changeReminderHours = v) } }
                                Hint("From when something goes on.")
                                LabeledSwitch(
                                    "At night too", current.changeReminderAtNight, { on -> change { it.copy(changeReminderAtNight = on) } },
                                    subtitle = "Otherwise it waits until your night ends.",
                                )
                            }
                        }
                    }
                    SectionCard("Your own words") {
                        Hint("Offered first when you log, alongside the usual ones.")
                        Text("What sets it off", style = MaterialTheme.typography.titleSmall)
                        ChipPicker(emptyList(), current.triggers.toSet(), { v -> change { it.copy(triggers = v.toList()) } }, addLabel = "Add one")
                        if (Fields.CONTINENCE_ACTIVITY in fields) {
                            Text("What you were doing", style = MaterialTheme.typography.titleSmall)
                            ChipPicker(emptyList(), current.activities.toSet(), { v -> change { it.copy(activities = v.toList()) } }, addLabel = "Add one")
                        }
                        if (Fields.CONTINENCE_LOCATION in fields) {
                            Text("Places", style = MaterialTheme.typography.titleSmall)
                            ChipPicker(emptyList(), current.locations.toSet(), { v -> change { it.copy(locations = v.toList()) } }, addLabel = "Add one")
                        }
                    }
                    SectionCard("Your own fields") {
                        CustomFieldsEditor(current.customFields, { v -> change { it.copy(customFields = v) } })
                    }
                    SectionCard("Night") {
                        Hint("Trips during recorded sleep count as night-time. Without a recording, these hours do (11 pm to 6 am unless you set them).")
                        ValueRow("Night starts", current.nightFrom ?: "Not set", onClick = { picking = "nightFrom" })
                        ValueRow("Night ends", current.nightTo ?: "Not set", onClick = { picking = "nightTo" })
                        if (current.nightFrom != null || current.nightTo != null) {
                            TextButton(onClick = { change { it.copy(nightFrom = null, nightTo = null) } }) { Text("Use the usual hours") }
                        }
                    }
                    if (Fields.CONTINENCE_BLADDER_TRAINING in fields || current.bladderTraining.enabled) {
                        BladderTrainingSection(current.bladderTraining, today, canNotify, { t -> change { it.copy(bladderTraining = t) } }) { picking = it }
                    }
                    Button(
                        enabled = current != state.saved && !state.saving,
                        onClick = { onSave(current) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.saving) "Saving…" else "Save") }
                }
            }
        }
    }
    val c = current ?: return
    picking?.let { which ->
        val initial = when (which) {
            "nightFrom" -> c.nightFrom ?: "23:00"
            "nightTo" -> c.nightTo ?: "06:00"
            "trainingFrom" -> c.bladderTraining.from
            else -> c.bladderTraining.to
        }
        TimePickDialog(ReminderPlanner.parseTime(initial) ?: LocalTime.of(8, 0), "Time", { picking = null }) { t ->
            val text = ReminderPlanner.formatTime(t)
            s = when (which) {
                "nightFrom" -> c.copy(nightFrom = text)
                "nightTo" -> c.copy(nightTo = text)
                "trainingFrom" -> c.copy(bladderTraining = c.bladderTraining.copy(from = text))
                else -> c.copy(bladderTraining = c.bladderTraining.copy(to = text))
            }
            picking = null
        }
    }
}

@Composable
private fun BladderTrainingSection(t: BladderTraining, today: LocalDate, canNotify: Boolean, onChange: (BladderTraining) -> Unit, onPick: (String) -> Unit) {
    SectionCard("Bladder training") {
        LabeledSwitch(
            "Remind me to go", t.enabled, { onChange(t.copy(enabled = it, startedOn = if (it && !t.enabled) null else t.startedOn)) },
            subtitle = "Trips at set times while you're up, the gap growing a little at a time. It often helps with sudden urges.",
        )
        if (t.enabled && !canNotify) ErrorCard("Notifications are off for this app, so reminders can't show on this phone. Settings › Reminders can turn them on.")
        Text("Start with every", style = MaterialTheme.typography.titleSmall)
        Choices(listOf(30, 45, 60, 90, 120), t.intervalMinutes, { BladderTrainingPlan.say(it).let { s -> if (s == "hour") "1 hour" else s } }) { v ->
            onChange(t.copy(intervalMinutes = v, goalMinutes = maxOf(t.goalMinutes, v)))
        }
        Text("Add each time", style = MaterialTheme.typography.titleSmall)
        Choices(listOf(0, 10, 15, 30), t.stepMinutes, { if (it == 0) "Keep it the same" else "$it minutes" }) { onChange(t.copy(stepMinutes = it)) }
        if (t.stepMinutes > 0) {
            Text("Every", style = MaterialTheme.typography.titleSmall)
            Choices(listOf(3, 5, 7, 14), t.stepEveryDays, { "$it days" }) { onChange(t.copy(stepEveryDays = it)) }
            Text("Up to every", style = MaterialTheme.typography.titleSmall)
            Choices(listOf(120, 150, 180, 210, 240).filter { it >= t.intervalMinutes }, t.goalMinutes, { BladderTrainingPlan.say(it) }) { onChange(t.copy(goalMinutes = it)) }
        }
        ValueRow("From", t.from, onClick = { onPick("trainingFrom") })
        ValueRow("Until", t.to, onClick = { onPick("trainingTo") })
        if (t.enabled) Hint(BladderTrainingPlan.today(t, today) + ".")
        Hint("Reminders come on this phone and wherever else you get them. On a locked screen they only say \"Time for your planned break\".")
        if (t.startedOn != null) OutlinedButton(onClick = { onChange(t.copy(startedOn = today.toString())) }) { Text("Start again from today") }
    }
}

@Composable
private fun Choices(options: List<Int>, selected: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (options + selected).distinct().sorted().forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}

/** For a row of the diary's quick buttons: what's offered, in the order picked. */
fun quickButtonsFor(settings: ContinenceSettings, fields: Set<String>): List<ContinenceKind> =
    settings.quickButtons.filter { it in ContinenceText.kinds(fields) }.ifEmpty { QUICK_KINDS }
