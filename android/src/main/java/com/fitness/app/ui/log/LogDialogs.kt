@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.fitness.app.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fitness.api.CheckIn
import com.fitness.api.SleepSession
import com.fitness.api.Units
import com.fitness.api.Workout
import com.fitness.api.WorkoutType
import com.fitness.app.ui.Format
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** A number field that keeps what's typed and reports the value it reads as. */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value, onValueChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Weighing in: in kilograms or pounds, as the profile says. */
@Composable
fun WeightDialog(units: Units, initialKg: Double?, onDismiss: () -> Unit, onSave: (kg: Double) -> Unit) {
    var text by remember { mutableStateOf(initialKg?.let { Format.compact(Format.weightValue(it, units)) }.orEmpty()) }
    val value = Format.parse(text)
    val valid = value != null && Format.weightToKg(value, units) in 20.0..400.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = { NumberField(text, { text = it }, "Weight", suffix = Format.weightUnit(units)) },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(Format.weightToKg(value!!, units)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A workout entered by hand: what, how long, and optionally how far, how many calories, how hard. */
@Composable
fun WorkoutDialog(units: Units, date: LocalDate, zone: ZoneId, onDismiss: () -> Unit, onSave: (Workout) -> Unit) {
    var type by remember { mutableStateOf(WorkoutType.WALK) }
    var minutes by remember { mutableStateOf("30") }
    var distance by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var effort by remember { mutableIntStateOf(5) }
    var note by remember { mutableStateOf("") }
    var typeOpen by remember { mutableStateOf(false) }
    val duration = minutes.trim().toIntOrNull()
    val valid = duration != null && duration in 1..1440
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a workout") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = typeOpen, onExpandedChange = { typeOpen = it }) {
                    OutlinedTextField(
                        type.label, {},
                        readOnly = true,
                        label = { Text("Kind") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                        WorkoutType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeOpen = false })
                        }
                    }
                }
                NumberField(minutes, { minutes = it }, "How long", suffix = "min", decimals = false)
                NumberField(distance, { distance = it }, "Distance (optional)", suffix = Format.distanceUnit(units))
                NumberField(calories, { calories = it }, "Active calories (optional)", suffix = "kcal", decimals = false)
                Text("How hard it felt: $effort of 10", style = MaterialTheme.typography.bodyMedium)
                Slider(value = effort.toFloat(), onValueChange = { effort = it.roundToInt() }, valueRange = 1f..10f, steps = 8)
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val today = LocalDate.now(zone)
                val end = if (date == today) System.currentTimeMillis() else date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
                onSave(
                    Workout(
                        type = type,
                        start = end - duration!! * 60_000L,
                        end = end,
                        distanceM = Format.parse(distance)?.let { Format.distanceToMetres(it, units) },
                        activeCalories = calories.trim().toDoubleOrNull(),
                        effort = effort,
                        note = note.trim().ifBlank { null },
                        manual = true,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A night entered by hand: when you went to bed, and when you woke on [date]. */
@Composable
fun SleepDialog(date: LocalDate, zone: ZoneId, onDismiss: () -> Unit, onSave: (SleepSession) -> Unit) {
    val bed = rememberTimePickerState(initialHour = 23, initialMinute = 0)
    val wake = rememberTimePickerState(initialHour = 7, initialMinute = 0)
    var step by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "When did you go to bed?" else "When did you wake up?") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimeInput(if (step == 0) bed else wake)
                Text(
                    "For the night before ${Format.day(date, LocalDate.now(zone)).lowercase().let { if (it == "today") "this morning" else it }}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (step == 0) {
                    step = 1
                    return@TextButton
                }
                val wakeAt = date.atTime(wake.hour, wake.minute).atZone(zone)
                // A bedtime after noon was the evening before.
                val bedDay = if (bed.hour >= 12 || (bed.hour * 60 + bed.minute) > (wake.hour * 60 + wake.minute)) date.minusDays(1) else date
                val bedAt = bedDay.atTime(bed.hour, bed.minute).atZone(zone)
                if (wakeAt.isAfter(bedAt)) {
                    onSave(SleepSession(start = bedAt.toInstant().toEpochMilli(), end = wakeAt.toInstant().toEpochMilli(), manual = true))
                }
            }) { Text(if (step == 0) "Next" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 1) step = 0 else onDismiss() }) { Text(if (step == 1) "Back" else "Cancel") }
        },
    )
}

/** Tags offered for a check-in (things that tend to show up in sleep and readiness). */
val CHECK_IN_TAGS = listOf(
    "Alcohol", "Late caffeine", "Late meal", "Stress", "Sick", "Travel", "Hard workout",
    "Sauna", "Meditation", "Nap", "Screens in bed", "Medication",
)

/** How you feel, 1–5 for each, with tags and a note. */
@Composable
fun CheckInDialog(initial: CheckIn, onDismiss: () -> Unit, onSave: (CheckIn) -> Unit) {
    var mood by remember { mutableStateOf(initial.mood) }
    var energy by remember { mutableStateOf(initial.energy) }
    var stress by remember { mutableStateOf(initial.stress) }
    var soreness by remember { mutableStateOf(initial.soreness) }
    var tags by remember { mutableStateOf(initial.tags.toSet()) }
    var note by remember { mutableStateOf(initial.note.orEmpty()) }
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How are you feeling?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Scale("Mood", mood, "Low", "Great") { mood = it }
                Scale("Energy", energy, "Drained", "Full") { energy = it }
                Scale("Stress", stress, "Calm", "Very") { stress = it }
                Scale("Soreness", soreness, "None", "Very") { soreness = it }
                Text("Anything else?", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    (CHECK_IN_TAGS + tags.filter { it !in CHECK_IN_TAGS }).forEach { tag ->
                        FilterChip(selected = tag in tags, onClick = { tags = if (tag in tags) tags - tag else tags + tag }, label = { Text(tag) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTag, { newTag = it.take(30) }, label = { Text("Your own tag") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = newTag.isNotBlank(), onClick = { tags = tags + newTag.trim(); newTag = "" }) {
                        Icon(Icons.Rounded.Add, "Add tag")
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(mood = mood, energy = energy, stress = stress, soreness = soreness, tags = tags.toList(), note = note.trim().ifBlank { null }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Five round steps, 1 to 5; tapping the chosen one again clears it. */
@Composable
private fun Scale(label: String, value: Int?, low: String, high: String, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value?.let { "$it of 5" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (1..5).forEach { n ->
                val selected = value == n
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .clickable { onChange(if (selected) null else n) }
                        .semantics { contentDescription = "$label $n of 5" },
                ) { Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.titleSmall) } }
            }
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
