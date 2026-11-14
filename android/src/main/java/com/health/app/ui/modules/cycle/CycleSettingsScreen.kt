@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.cycle

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
import com.health.api.CyclePhase
import com.health.api.CycleSettings
import com.health.api.Fields
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.cycleSettings
import com.health.app.net.saveCycleSettings
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
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.CustomFieldsEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/** The cycle's words, plain where the person wants them plain. */
object CycleText {
    /** No fertile window or ovulation: plain wording chosen, or the fertile window hidden (as the server decides). */
    fun plain(settings: CycleSettings, fields: Set<String>): Boolean = settings.neutralWording || Fields.CYCLE_FERTILITY !in fields

    fun phase(phase: CyclePhase, plain: Boolean): String = if (plain && phase == CyclePhase.FERTILE) "Mid-cycle" else phase.label

    /** A length typed in, within the server's range, or null for "use my averages". */
    fun length(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }

    /** Whether a typed length is fine: empty, or within range. */
    fun validLength(text: String, range: IntRange): Boolean = text.isBlank() || length(text, range) != null
}

data class CycleSettingsState(val saved: CycleSettings? = null, val loading: Boolean = true, val saving: Boolean = false, val error: String? = null)

class CycleSettingsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(CycleSettingsState())
    val state: StateFlow<CycleSettingsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = c.api.cycleSettings()
                CycleWording.neutral.value = saved.neutralWording
                _state.update { it.copy(saved = saved, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the cycle's settings") }
            }
        }
    }

    fun save(settings: CycleSettings) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = c.api.saveCycleSettings(settings)
                _state.update { it.copy(saved = saved, saving = false) }
                CycleWording.neutral.value = saved.neutralWording
                c.tell("Saved")
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                c.tell(e.message ?: "Couldn't save that")
            }
        }
    }
}

@Composable
fun CycleSettingsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { CycleSettingsViewModel(c) }
    val state by vm.state.collectAsState()
    CycleSettingsScreen(state, onBack, onSave = { vm.save(it) }, onRetry = vm::load)
}

/**
 * The cycle, as the person wants it: their own symptoms, moods and pain areas (and ones to hide),
 * their own fields, their usual lengths, reminders, and plain wording without fertility words.
 */
@Composable
fun CycleSettingsScreen(state: CycleSettingsState, onBack: () -> Unit, onSave: (CycleSettings) -> Unit = {}, onRetry: () -> Unit = {}) {
    var s by remember { mutableStateOf(state.saved) }
    LaunchedEffect(state.saved) { if (state.saved != null) s = state.saved }
    var cycle by remember(state.saved) { mutableStateOf(state.saved?.cycleLength?.toString().orEmpty()) }
    var period by remember(state.saved) { mutableStateOf(state.saved?.periodLength?.toString().orEmpty()) }
    var luteal by remember(state.saved) { mutableStateOf(state.saved?.lutealLength?.toString().orEmpty()) }
    var pickingTime by remember { mutableStateOf(false) }
    val current = s
    val valid = CycleText.validLength(cycle, 15..90) && CycleText.validLength(period, 1..15) && CycleText.validLength(luteal, 7..20)
    fun withLengths(x: CycleSettings) = x.copy(cycleLength = CycleText.length(cycle, 15..90), periodLength = CycleText.length(period, 1..15), lutealLength = CycleText.length(luteal, 7..20))
    val changed = current != null && withLengths(current) != state.saved
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Cycle settings") {
            TextButton(enabled = changed && valid && !state.saving, onClick = { current?.let { onSave(withLengths(it)) } }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when {
                state.loading && current == null -> Loading()
                current == null -> ErrorCard(state.error ?: "Couldn't load the cycle's settings", actionLabel = "Try again", onAction = onRetry)
                else -> {
                    fun change(f: (CycleSettings) -> CycleSettings) { s = f(current) }
                    SectionCard("Wording") {
                        LabeledSwitch(
                            "Plain wording", current.neutralWording, { v -> change { it.copy(neutralWording = v) } },
                            subtitle = "Leaves out the fertile window and words like ovulation, for anyone who'd rather not see them.",
                        )
                    }
                    SectionCard("Symptoms") {
                        Hint("Your own are offered first when you log a day.")
                        ChipPicker(emptyList(), current.symptoms.toSet(), { v -> change { it.copy(symptoms = v.toList()) } }, addLabel = "Add a symptom")
                        Text("Leave these out", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SYMPTOMS.forEach { sym ->
                                val hidden = current.hiddenSymptoms.any { it.equals(sym, ignoreCase = true) }
                                FilterChip(
                                    selected = hidden,
                                    onClick = { change { it.copy(hiddenSymptoms = if (hidden) it.hiddenSymptoms.filterNot { h -> h.equals(sym, ignoreCase = true) } else it.hiddenSymptoms + sym) } },
                                    label = { Text(sym) },
                                )
                            }
                        }
                    }
                    SectionCard("Moods and pain") {
                        Text("Your own moods", style = MaterialTheme.typography.titleSmall)
                        ChipPicker(emptyList(), current.moods.toSet(), { v -> change { it.copy(moods = v.toList()) } }, addLabel = "Add a mood")
                        Text("Where pain can be", style = MaterialTheme.typography.titleSmall)
                        ChipPicker(emptyList(), current.painAreas.toSet(), { v -> change { it.copy(painAreas = v.toList()) } }, addLabel = "Add a place")
                    }
                    SectionCard("Your own fields") {
                        CustomFieldsEditor(current.customFields, { v -> change { it.copy(customFields = v) } })
                    }
                    SectionCard("Your lengths") {
                        Hint("Leave these empty to use your averages. Set them if you know your cycle better than the app does yet.")
                        NumberField(cycle, { cycle = it }, "Cycle length", suffix = "days", decimals = false, supporting = if (CycleText.validLength(cycle, 15..90)) "15 to 90" else "From 15 to 90 days")
                        NumberField(period, { period = it }, "Period length", suffix = "days", decimals = false, supporting = if (CycleText.validLength(period, 1..15)) "1 to 15" else "From 1 to 15 days")
                        NumberField(
                            luteal, { luteal = it }, "From ovulation to a period", suffix = "days", decimals = false,
                            supporting = if (CycleText.validLength(luteal, 7..20)) "Usually about 14; 7 to 20" else "From 7 to 20 days",
                        )
                    }
                    SectionCard("Reminders") {
                        Text("Before a period", style = MaterialTheme.typography.titleSmall)
                        DayChoices(listOf(null, 1, 2, 3, 5, 7), current.remindBeforePeriod?.takeIf { it > 0 }, { if (it == null) "Off" else "$it ${if (it == 1) "day" else "days"} before" }) { v ->
                            change { it.copy(remindBeforePeriod = v) }
                        }
                        Text("When it's late", style = MaterialTheme.typography.titleSmall)
                        DayChoices(listOf(null, 1, 2, 3, 5, 7), current.remindLateAfter, { if (it == null) "Off" else "$it ${if (it == 1) "day" else "days"} late" }) { v ->
                            change { it.copy(remindLateAfter = v) }
                        }
                        ValueRow("A daily nudge to log", current.dailyReminder ?: "Off", onClick = { pickingTime = true })
                        if (current.dailyReminder != null) TextButton(onClick = { change { it.copy(dailyReminder = null) } }) { Text("Turn the daily nudge off") }
                        Hint("These come by notification wherever you've turned on reminders from your server.")
                    }
                    Button(enabled = changed && valid && !state.saving, onClick = { onSave(withLengths(current)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Saving…" else "Save")
                    }
                }
            }
        }
    }
    val c = current
    if (pickingTime && c != null) {
        TimePickDialog(c.dailyReminder?.let(ReminderPlanner::parseTime) ?: LocalTime.of(20, 30), "Daily nudge", { pickingTime = false }) { t ->
            s = c.copy(dailyReminder = ReminderPlanner.formatTime(t))
            pickingTime = false
        }
    }
}

@Composable
private fun DayChoices(options: List<Int?>, selected: Int?, label: (Int?) -> String, onPick: (Int?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (options + selected).distinct().sortedBy { it ?: -1 }.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}

/** The person's own choice of plain wording, once known: for Today's cycle card, which has no settings of its own. */
object CycleWording {
    val neutral = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
}

/** Whether Today's cycle card leaves out fertility words: the fields (the viewed person's while viewing), and the person's own choice. */
@Composable
fun plainOnToday(): Boolean {
    val c = com.health.app.ui.conditions.containerOrNull() ?: return false
    val layout by c.fields.collectAsState()
    val neutral by CycleWording.neutral.collectAsState()
    val viewing by c.viewing.collectAsState()
    LaunchedEffect(viewing == null) {
        if (viewing == null && CycleWording.neutral.value == null) CycleWording.neutral.value = attempt { c.api.cycleSettings().neutralWording }.getOrNull()
    }
    return Fields.CYCLE_FERTILITY !in (layout?.effective ?: Fields.DEFAULTS) || (viewing == null && neutral == true)
}
