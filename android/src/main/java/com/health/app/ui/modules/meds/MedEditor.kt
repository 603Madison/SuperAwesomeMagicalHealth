@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.meds

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.ScheduleKind
import com.health.app.attempt
import com.health.app.reminders.ReminderPlanner
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

val SITES_INJECTION = listOf("Left thigh", "Right thigh", "Left abdomen", "Right abdomen", "Left glute", "Right glute", "Left arm", "Right arm")
val SITES_PATCH = listOf("Left abdomen", "Right abdomen", "Left buttock", "Right buttock", "Left upper arm", "Right upper arm")

fun suggestedSites(form: MedForm): List<String> = when (form) {
    MedForm.INJECTION -> SITES_INJECTION
    MedForm.PATCH -> SITES_PATCH
    else -> emptyList()
}

private val FORMS = listOf(
    MedForm.PILL, MedForm.CAPSULE, MedForm.INJECTION, MedForm.PATCH, MedForm.GEL, MedForm.LIQUID,
    MedForm.SPRAY, MedForm.INHALER, MedForm.CREAM, MedForm.DROPS, MedForm.IMPLANT, MedForm.SUPPLEMENT, MedForm.OTHER,
)

private val KIND_LABELS = mapOf(
    ScheduleKind.DAILY to "Every day",
    ScheduleKind.WEEKLY to "Some days",
    ScheduleKind.EVERY_N_DAYS to "Every few days",
    ScheduleKind.AS_NEEDED to "As needed",
)

@Composable
fun MedEditorRoute(id: String?, onDone: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MedsViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (id != null && state.meds.none { it.id == id }) {
        if (state.loading) Loading() else BackBar(onDone, "Not found")
        return
    }
    MedEditorScreen(
        existing = id?.let { i -> state.meds.firstOrNull { it.id == i } },
        onBack = onDone,
        onSave = { med ->
            val first = med.reminders && med.active && med.schedule.kind != ScheduleKind.AS_NEEDED && state.meds.none { it.reminders && it.active }
            if (first && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            vm.save(med)
            onDone()
        },
        onDelete = { vm.delete(it); onDone() },
        hormones = com.health.api.Module.HORMONES in c.modules(),
    )
}

@Composable
fun MedEditorScreen(
    existing: Medication?,
    onBack: () -> Unit,
    onSave: (Medication) -> Unit,
    onDelete: (String) -> Unit,
    hormones: Boolean = false,
) {
    val start = existing ?: Medication("")
    // Hormone therapy: whether it's part of it, and since when.
    var hormone by remember { mutableStateOf(start.hormone) }
    var startedOn by remember { mutableStateOf(start.startedOn) }
    var pickingStarted by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(start.name) }
    var dose by remember { mutableStateOf(start.dose.orEmpty()) }
    var form by remember { mutableStateOf(start.form) }
    var kind by remember { mutableStateOf(start.schedule.kind) }
    var times by remember { mutableStateOf(start.schedule.times.ifEmpty { listOf("09:00") }) }
    var weekdays by remember { mutableStateOf(start.schedule.weekdays.toSet()) }
    var every by remember { mutableStateOf(start.schedule.everyDays.coerceAtLeast(2).toString()) }
    var startDate by remember { mutableStateOf(start.schedule.startDate) }
    var purpose by remember { mutableStateOf(start.purpose.orEmpty()) }
    var notes by remember { mutableStateOf(start.notes.orEmpty()) }
    var sites by remember { mutableStateOf(start.sites.toSet()) }
    var reminders by remember { mutableStateOf(start.reminders) }
    var active by remember { mutableStateOf(start.active) }
    var stock by remember { mutableStateOf(start.stock?.toString().orEmpty()) }
    var pickingTime by remember { mutableStateOf<Int?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val schedule = MedSchedule(
        kind = kind,
        times = if (kind == ScheduleKind.AS_NEEDED) emptyList() else times.sorted(),
        everyDays = if (kind == ScheduleKind.EVERY_N_DAYS) every.toIntOrNull()?.coerceIn(2, 365) ?: 2 else 1,
        weekdays = if (kind == ScheduleKind.WEEKLY) weekdays.sorted() else emptyList(),
        startDate = startDate,
    )
    fun build() = start.copy(
        name = name.trim(), dose = dose.trim().ifBlank { null }, form = form, schedule = schedule,
        purpose = purpose.trim().ifBlank { null }, notes = notes.trim().ifBlank { null },
        sites = sites.toList(), reminders = reminders && kind != ScheduleKind.AS_NEEDED, active = active,
        stock = stock.trim().toIntOrNull()?.coerceAtLeast(0),
        hormone = hormone, startedOn = startedOn,
    )
    val valid = name.isNotBlank() && (kind != ScheduleKind.WEEKLY || weekdays.isNotEmpty()) &&
        (kind != ScheduleKind.EVERY_N_DAYS || every.toIntOrNull() != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (existing == null) "Add a medication" else existing.name) {
            TextButton(enabled = valid, onClick = { onSave(build()) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("What it is") {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    dose, { dose = it }, label = { Text("Dose") }, placeholder = { Text("2 mg, 0.1 mg/day, 1 puff") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text("Form", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FORMS.forEach { f -> FilterChip(selected = form == f, onClick = { form = f }, label = { Text(f.label) }) }
                }
                OutlinedTextField(
                    purpose, { purpose = it }, label = { Text("What it's for (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            InfoCard("When") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(KIND_LABELS.getValue(k)) }) }
                }
                when (kind) {
                    ScheduleKind.WEEKLY -> {
                        Text("On", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                FilterChip(
                                    selected = d.value in weekdays,
                                    onClick = { weekdays = if (d.value in weekdays) weekdays - d.value else weekdays + d.value },
                                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                                )
                            }
                        }
                    }
                    ScheduleKind.EVERY_N_DAYS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                            NumberField(every, { every = it.filter(Char::isDigit) }, "Every this many days", Modifier.weight(1f))
                        }
                    }
                    else -> {}
                }
                if (kind != ScheduleKind.AS_NEEDED) {
                    Text("At", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        times.sorted().forEach { t ->
                            InputChip(
                                selected = false,
                                onClick = { pickingTime = times.indexOf(t) },
                                label = { Text(t) },
                            )
                        }
                        FilterChip(selected = false, onClick = { pickingTime = -1 }, label = { Text("Add a time") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
                    }
                    Hint("Tap a time to change or remove it.")
                }
                if (kind == ScheduleKind.EVERY_N_DAYS || kind == ScheduleKind.WEEKLY || startDate != null) {
                    Row(Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { pickingDate = true }) {
                            Text(startDate?.let { "Starting $it" } ?: "Choose a start date")
                        }
                    }
                }
                Hint(ReminderPlanner.describe(schedule), Modifier.padding(top = 8.dp))
            }

            if (form == MedForm.INJECTION || form == MedForm.PATCH || sites.isNotEmpty()) {
                InfoCard("Sites to rotate through") {
                    Hint("We'll suggest the one used longest ago each time.")
                    ChipPicker(suggestedSites(form), sites, { sites = it }, Modifier.padding(top = 8.dp), addLabel = "Add a site")
                }
            }

            if (hormones || hormone) {
                InfoCard("Hormone therapy") {
                    LabeledSwitch(
                        "Part of hormone therapy", hormone, { hormone = it },
                        subtitle = "Shown there, with every change to its dose noted",
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickingStarted = true }) {
                            Text(startedOn?.let { "Started ${com.health.app.ui.care.CareFormat.date(it) ?: it}" } ?: "When did you start it?")
                        }
                        if (startedOn != null) TextButton(onClick = { startedOn = null }) { Text("Clear") }
                    }
                }
            }

            InfoCard("Reminders and stock") {
                if (kind != ScheduleKind.AS_NEEDED) {
                    LabeledSwitch("Remind me", reminders, { reminders = it }, subtitle = "A notification at each time, with Taken and Snooze")
                }
                LabeledSwitch("Taking it now", active, { active = it }, subtitle = if (active) null else "Kept in your history, without reminders")
                NumberField(stock, { stock = it.filter(Char::isDigit) }, "Doses left (optional)", Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(
                    notes, { notes = it }, label = { Text("Notes") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            Button(onClick = { onSave(build()) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (existing?.id != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete this medication", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    pickingTime?.let { index ->
        val sorted = times.sorted()
        val current = if (index >= 0) ReminderPlanner.parseTime(sorted.getOrNull(index) ?: times[index]) else LocalTime.of(9, 0)
        val picker = rememberTimePickerState(current?.hour ?: 9, current?.minute ?: 0)
        AlertDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(if (index >= 0) "Change the time" else "Add a time") },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = ReminderPlanner.formatTime(LocalTime.of(picker.hour, picker.minute))
                    times = when {
                        index < 0 -> (times + chosen).distinct()
                        else -> (times - sorted[index] + chosen).distinct()
                    }
                    pickingTime = null
                }) { Text("OK") }
            },
            dismissButton = {
                if (index >= 0 && times.size > 1) {
                    TextButton(onClick = { times = times - sorted[index]; pickingTime = null }) { Text("Remove") }
                } else {
                    TextButton(onClick = { pickingTime = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (pickingDate) {
        val initial = startDate?.let { attempt { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { startDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }

    if (pickingStarted) {
        val initial = startedOn?.let { attempt { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        com.health.app.ui.care.DatePickDialog(initial, { pickingStarted = false }) { startedOn = it.toString(); pickingStarted = false }
    }

    if (confirmDelete && existing?.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("To keep it in your history without reminders, turn off \"Taking it now\" instead.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(existing.id!!) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
