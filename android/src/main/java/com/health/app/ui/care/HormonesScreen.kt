@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.DoseChange
import com.health.api.HormoneMed
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.LabSeries
import com.health.api.LabTarget
import com.health.app.Container
import com.health.app.net.addDoseChange
import com.health.app.net.deleteDoseChange
import com.health.app.net.hormones
import com.health.app.net.saveHormoneSettings
import com.health.app.ui.Format
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.TitleAction
import com.health.app.ui.modules.EmptyLine
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.sectionId
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.meds.formIcon
import com.health.app.ui.modules.IconTile
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class HormonesState(
    val today: LocalDate,
    val overview: HormoneOverview? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HormonesViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(HormonesState(LocalDate.now(c.zone())))
    val state: StateFlow<HormonesState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.overview == null, error = null, today = LocalDate.now(c.zone())) }
        viewModelScope.launch {
            try {
                val overview = c.ui.hormones()
                _state.update { it.copy(overview = overview, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load hormone therapy") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            message?.let(c::tell)
            c.dataChanged()
        } catch (e: Exception) {
            c.tell(e.message ?: "That didn't save")
        }
    }

    fun saveSettings(settings: HormoneSettings) = act("Saved") { c.api.saveHormoneSettings(settings) }

    fun addChange(change: DoseChange) = act("Dose change noted") { c.api.addDoseChange(change) }

    fun deleteChange(id: String) = act(null) { c.api.deleteDoseChange(id) }
}

class HormonesActions(
    val saveSettings: (HormoneSettings) -> Unit = {},
    val addChange: (DoseChange) -> Unit = {},
    val deleteChange: (String) -> Unit = {},
    val editMedication: (String?) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun HormonesRoute(onEditMedication: (String?) -> Unit) {
    val c = appContainer()
    val vm = viewModel { HormonesViewModel(c) }
    val state by vm.state.collectAsState()
    HormonesScreen(
        state, c.zone(),
        HormonesActions(
            saveSettings = { vm.saveSettings(it) },
            addChange = { vm.addChange(it) },
            deleteChange = { vm.deleteChange(it) },
            editMedication = onEditMedication,
            retry = { vm.load() },
        ),
    )
}

/**
 * Hormone therapy's sections in the default order (the sections of [FeatureScreens.HORMONES]):
 * when labs are due, the medications, and each test's levels (or where they'll go).
 */
fun hormoneSections(labs: List<LabSeries>): List<ArrangeItem> = buildList {
    add(ArrangeItem("labs", "When labs are due"))
    add(ArrangeItem("meds", "Medications"))
    if (labs.isEmpty()) add(ArrangeItem("levels", "Levels"))
    else labs.map { ArrangeItem(sectionId("level", it.name), it.name) }.distinctBy { it.id }.forEach(::add)
}

@Composable
fun HormonesScreen(state: HormonesState, zone: ZoneId, actions: HormonesActions) {
    val readOnly = LocalReadOnly.current
    var addingChange by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<LabSeries?>(null) }
    var editingSchedule by remember { mutableStateOf(false) }
    val overview = state.overview
    val sections = hormoneSections(overview?.labs.orEmpty())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Hormone therapy", "Doses over time, levels and labs") {
            ArrangeAction(FeatureScreens.HORMONES, "Hormone therapy", sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && overview == null -> Loading()
                state.error != null && overview == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                overview != null -> {
                    val levels = overview.labs.associateBy { sectionId("level", it.name) }
                    Sections(arrangedIn(FeatureScreens.HORMONES, sections)) { id ->
                        when (id) {
                            "labs" -> LabsDueCard(overview, state.today, readOnly) { editingSchedule = true }
                            "meds" -> if (overview.medications.isEmpty()) {
                                EmptyLine(
                                    "Mark a medication as hormone therapy to see it here.",
                                    action = "Add".takeIf { !readOnly }, onAction = { actions.editMedication(null) },
                                )
                            } else {
                                InfoCard("Medications", icon = Icons.Rounded.Medication, trailing = if (readOnly) null else ({
                                    TitleAction(Icons.Rounded.Add, "Note a dose change") { addingChange = true }
                                })) {
                                    overview.medications.forEachIndexed { i, med ->
                                        if (i > 0) Spacer(Modifier.height(12.dp))
                                        HormoneMedRow(med, state.today, zone, readOnly, actions)
                                    }
                                }
                            }
                            "levels" -> InfoCard("Levels", icon = Icons.Rounded.Science) {
                                Hint("Add lab results in Medications › Labs, and each test is charted here against your own range.")
                            }
                            else -> levels[id]?.let { series -> LevelCard(series, zone, readOnly) { editingTarget = series } }
                        }
                    }
                    Hint(
                        "Ranges are your own, from your clinician. A result outside one is worth mentioning at your next appointment.",
                        Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    val o = overview ?: return
    if (addingChange) {
        DoseChangeDialog(o.medications, zone, onDismiss = { addingChange = false }) { actions.addChange(it); addingChange = false }
    }
    editingTarget?.let { series ->
        TargetDialog(series, onDismiss = { editingTarget = null }) { target ->
            val others = o.settings.targets.filterNot { it.name.equals(series.name, ignoreCase = true) }
            actions.saveSettings(o.settings.copy(targets = if (target == null) others else others + target))
            editingTarget = null
        }
    }
    if (editingSchedule) {
        LabsScheduleDialog(o.settings.labsEveryWeeks, onDismiss = { editingSchedule = false }) { weeks ->
            actions.saveSettings(o.settings.copy(labsEveryWeeks = weeks))
            editingSchedule = false
        }
    }
}

@Composable
private fun LabsDueCard(overview: HormoneOverview, today: LocalDate, readOnly: Boolean, onEdit: () -> Unit) {
    val due = overview.nextLabsDue?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    InfoCard("Labs", icon = Icons.Rounded.EventRepeat, trailing = if (readOnly) null else ({ TitleAction(Icons.Rounded.Edit, "How often labs are checked", onEdit) })) {
        when {
            due == null && overview.settings.labsEveryWeeks == null -> Hint("No lab reminders. Set how often your labs are checked, and the next ones show here.")
            due == null -> Hint("Once you've added a lab result, the next ones show here.")
            else -> {
                val days = ChronoUnit.DAYS.between(today, due)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Next labs around ${CareFormat.date(due)}", style = MaterialTheme.typography.titleMedium)
                        overview.lastLabs?.let { CareFormat.date(it) }?.let { Hint("Last checked $it") }
                    }
                    when {
                        days < 0 -> Pill("Due now", AppTheme.status.warn)
                        days <= 14 -> Pill("In $days ${if (days == 1L) "day" else "days"}", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        overview.settings.labsEveryWeeks?.let { Hint("Every $it weeks, as you set it.", Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun HormoneMedRow(med: HormoneMed, today: LocalDate, zone: ZoneId, readOnly: Boolean, actions: HormonesActions) {
    var all by remember { mutableStateOf(false) }
    val m = med.medication
    Row(verticalAlignment = Alignment.Top) {
        IconTile(formIcon(m.form))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(m.dose, if (!m.active) "stopped" else null).joinToString(" · ").ifBlank { "No dose noted" }, style = MaterialTheme.typography.bodyMedium)
            med.since?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { since ->
                Hint("On it ${CareFormat.since(since, today)} · since ${CareFormat.date(since)}")
            }
        }
        if (!readOnly && m.id != null) TextButton(onClick = { actions.editMedication(m.id) }) { Text("Edit") }
    }
    // The dose history, newest first, across the card.
    if (med.changes.isNotEmpty()) {
        Column(Modifier.padding(start = 56.dp, top = 6.dp)) {
            val shown = med.changes.sortedByDescending { it.at }.let { if (all) it else it.take(3) }
            shown.forEach { change ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(
                        CareFormat.date(Instant.ofEpochMilli(change.at).atZone(zone).toLocalDate()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        (change.from?.let { "$it → " } ?: "Started on ") + change.to + (change.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (med.changes.size > 3) {
                TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "All ${med.changes.size} changes") }
            }
        }
    }
}

/** "100–200 pg/mL", "above 2.5 ng/mL", "not set": to follow "your range". */
fun rangeText(target: LabTarget?, unit: String): String {
    val low = target?.low
    val high = target?.high
    return when {
        low != null && high != null -> "${Format.upTo2(low)}–${Format.upTo2(high)} $unit"
        low != null -> "above ${Format.upTo2(low)} $unit"
        high != null -> "below ${Format.upTo2(high)} $unit"
        else -> "not set"
    }
}

@Composable
private fun LevelCard(series: LabSeries, zone: ZoneId, readOnly: Boolean, onTarget: () -> Unit) {
    val sorted = series.results.sortedBy { it.at }
    val latest = sorted.lastOrNull()
    val target = series.target
    val outside = latest != null && ((target?.low != null && latest.value < target.low!!) || (target?.high != null && latest.value > target.high!!))
    InfoCard(series.name, icon = Icons.Rounded.Science, trailing = if (readOnly) null else ({ TitleAction(Icons.Rounded.Tune, "Your range for ${series.name}", onTarget) })) {
        if (latest != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Format.upTo2(latest.value)} ${latest.unit.ifBlank { series.unit }}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (target != null) {
                    if (outside) Pill("Outside your range", AppTheme.status.warn) else Pill("In your range", AppTheme.status.good)
                }
            }
            Hint("${CareFormat.date(Instant.ofEpochMilli(latest.at).atZone(zone).toLocalDate())} · your range: ${rangeText(target, series.unit)}")
            if (sorted.size >= 2) {
                Spacer(Modifier.height(10.dp))
                RangeChart(
                    sorted.map { it.value }, target?.low, target?.high,
                    color = MaterialTheme.colorScheme.primary,
                    band = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                )
            }
        }
    }
}

@Composable
private fun DoseChangeDialog(meds: List<HormoneMed>, zone: ZoneId, onDismiss: () -> Unit, onSave: (DoseChange) -> Unit) {
    var chosen by remember { mutableStateOf(meds.firstOrNull()?.medication) }
    var to by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note a dose change") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Changes made in Medications are noted by themselves; this is for ones made elsewhere.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    meds.forEach { m -> FilterChip(selected = chosen?.id == m.medication.id, onClick = { chosen = m.medication }, label = { Text(m.medication.name) }) }
                }
                OutlinedTextField(to, { to = it }, label = { Text("New dose") }, placeholder = { Text("4 mg, 0.1 mg/day") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { picking = true }) { Text("On ${CareFormat.date(date)}") }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen?.id != null && to.isNotBlank(), onClick = {
                val med = chosen ?: return@TextButton
                onSave(DoseChange(med.id!!, date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), med.dose, to.trim(), note.trim().ifBlank { null }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) DatePickDialog(date, { picking = false }) { date = it; picking = false }
}

@Composable
private fun TargetDialog(series: LabSeries, onDismiss: () -> Unit, onSave: (LabTarget?) -> Unit) {
    var low by remember { mutableStateOf(series.target?.low?.let { Format.upTo2(it) }.orEmpty()) }
    var high by remember { mutableStateOf(series.target?.high?.let { Format.upTo2(it) }.orEmpty()) }
    val lowValue = Format.parse(low)
    val highValue = Format.parse(high)
    val valid = (lowValue != null || highValue != null) && (lowValue == null || highValue == null || lowValue < highValue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your range for ${series.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("The range your clinician aims for. Leave one end empty if there's only a floor or a ceiling.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(low, { low = it }, "From", Modifier.weight(1f), suffix = series.unit)
                    NumberField(high, { high = it }, "To", Modifier.weight(1f), suffix = series.unit)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(LabTarget(series.name, series.unit, lowValue, highValue)) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (series.target != null) TextButton(onClick = { onSave(null) }) { Text("No range") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LabsScheduleDialog(current: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How often are labs checked?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 8, 12, 26, 52).forEach { w ->
                        FilterChip(selected = choice == w, onClick = { choice = w }, label = { Text(if (w == 52) "Yearly" else "Every $w weeks") })
                    }
                    FilterChip(selected = choice == null, onClick = { choice = null }, label = { Text("No reminder") }, leadingIcon = null)
                }
                Hint("Today shows when the next ones are due.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
