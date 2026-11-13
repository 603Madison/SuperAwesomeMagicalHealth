@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.neuro

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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.EnergyDay
import com.health.api.Fields
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.api.Sense
import com.health.app.ui.Format
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How long it took to feel settled again, as offered. */
val RECOVERY = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 360 to "Half a day", 1440 to "A day", 4320 to "A few days")

/** A time things got too much, in full: what, how strong, senses, triggers, signs, where, what helped, recovery. */
@Composable
fun OverloadForm(
    initial: OverloadEvent?,
    profile: NeuroProfile,
    fields: Set<String>,
    zone: ZoneId,
    now: Long,
    onBack: () -> Unit,
    onSave: (OverloadEvent) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var e by remember(initial) { mutableStateOf(initial ?: OverloadEvent(start = now, kind = OverloadKind.OVERWHELM)) }
    var picking by remember { mutableStateOf(false) }
    val lasted = e.end?.let { ((it - e.start) / 60_000).toInt() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (initial?.id == null) "Things got too much" else "Edit") {
            TextButton(onClick = { onSave(e) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Overwhelm is your nervous system saying it's had too much. Noticing what led there, and what helped, is useful; nothing here is a judgement.", Modifier.padding(horizontal = 4.dp))
            InfoCard("What it was") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverloadKind.entries.forEach { k -> FilterChip(selected = e.kind == k, onClick = { e = e.copy(kind = k) }, label = { Text(k.label) }) }
                }
                Spacer(Modifier.height(8.dp))
                StepScale("How strong", e.intensity, "Mild", "Intense", { e = e.copy(intensity = it ?: 3) })
            }
            InfoCard("When") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CareFormat.dayTime(e.start, zone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text("Change") }
                }
                Text("How long it lasted", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 15, 30, 60, 120).forEach { m ->
                        FilterChip(selected = lasted == m, onClick = { e = e.copy(end = if (lasted == m) null else e.start + m * 60_000L) }, label = { Text(FocusMath.mins(m)) })
                    }
                }
            }
            if (Fields.NEURO_SENSORY in fields) {
                InfoCard("Senses involved") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sense.entries.forEach { s ->
                            val on = s in e.senses
                            FilterChip(selected = on, onClick = { e = e.copy(senses = if (on) e.senses - s else e.senses + s) }, label = { Text(s.label.substringBefore(" (")) })
                        }
                    }
                }
            }
            InfoCard("What led to it") {
                ChipPicker(NeuroSuggestions.triggers, e.triggers.toSet(), { e = e.copy(triggers = it.toList()) })
            }
            InfoCard("Signs beforehand") {
                ChipPicker((profile.warningSigns + NeuroSuggestions.warningSigns).distinct(), e.warningSigns.toSet(), { e = e.copy(warningSigns = it.toList()) })
            }
            InfoCard("Where you were") {
                OutlinedTextField(e.whereAt.orEmpty(), { e = e.copy(whereAt = it.take(120).ifEmpty { null }) }, label = { Text("Home, work, the shops…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            InfoCard("What helped") {
                ChipPicker((profile.whatHelps + profile.stims + NeuroSuggestions.whatHelped).distinct(), e.whatHelped.toSet(), { e = e.copy(whatHelped = it.toList()) })
            }
            InfoCard("Time to feel settled again") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RECOVERY.forEach { (m, label) ->
                        FilterChip(selected = e.recoveryMinutes == m, onClick = { e = e.copy(recoveryMinutes = if (e.recoveryMinutes == m) null else m) }, label = { Text(label) })
                    }
                }
            }
            InfoCard("Anything else") {
                OutlinedTextField(e.note.orEmpty(), { e = e.copy(note = it.take(4000).ifEmpty { null }) }, label = { Text("Note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { onSave(e) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (initial?.id != null && onDelete != null) OutlinedButton(onClick = { onDelete(initial.id!!) }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    if (picking) {
        val at = Instant.ofEpochMilli(e.start).atZone(zone)
        TimePickDialog(at.toLocalTime(), "When it started", onDismiss = { picking = false }) { time ->
            val start = at.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli().coerceAtMost(now)
            e = e.copy(start = start, end = e.end?.let { end -> start + (end - e.start) })
            picking = false
        }
    }
}

/** A day's energy budget: start with, what used energy, what gave some back, what's left, the social battery. */
@Composable
fun EnergyEditor(initial: EnergyDay, social: Boolean, now: Long, onBack: () -> Unit, onSave: (EnergyDay) -> Unit) {
    var day by remember(initial) { mutableStateOf(initial) }
    var what by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(1) }
    val date = LocalDate.parse(initial.date)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Energy budget") {
            TextButton(onClick = { onSave(day) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Format.longDay(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
            Hint("Spoons are a way to count energy: you start with some, and everything uses or gives back a few. Use any number that feels right.", Modifier.padding(horizontal = 4.dp))
            InfoCard("You start with") {
                Stepper(day.startWith ?: 0, { day = day.copy(startWith = it.coerceIn(0, 100)) }, "spoons", "Spoons to start with")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 8, 10, 12, 15, 20).forEach { n -> FilterChip(selected = day.startWith == n, onClick = { day = day.copy(startWith = n) }, label = { Text("$n") }) }
                }
            }
            InfoCard("What's left") {
                Text(EnergyMath.leftText(day) ?: "Set a starting number to see what's left.", style = MaterialTheme.typography.titleLarge)
                EnergyMath.fraction(day)?.let { f ->
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(f, color = if (EnergyMath.low(day)) AppTheme.status.warn else MaterialTheme.colorScheme.primary)
                }
                Hint("Used ${EnergyMath.used(day)} · ${EnergyMath.gained(day)} back")
            }
            InfoCard("Used energy") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuroSuggestions.uses.forEach { (w, n) -> AssistChip(onClick = { day = EnergyMath.spend(day, w, n, now) }, label = { Text("$w −$n") }) }
                }
            }
            InfoCard("Gave some back") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuroSuggestions.gives.forEach { (w, n) -> AssistChip(onClick = { day = EnergyMath.spend(day, w, n, now) }, label = { Text("$w +${-n}") }) }
                }
            }
            InfoCard("Your own") {
                OutlinedTextField(what, { what = it.take(120) }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Stepper(amount, { amount = it.coerceIn(-50, 100) }, if (amount >= 0) "used" else "back", "Spoons", allowNegative = true)
                Hint("Below zero gives energy back.")
                OutlinedButton(enabled = what.isNotBlank() && amount != 0, onClick = { day = EnergyMath.spend(day, what, amount, now); what = "" }) { Text("Add") }
            }
            if (day.spends.isNotEmpty()) {
                InfoCard("Today so far") {
                    day.spends.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.what, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(if (s.amount > 0) "−${s.amount}" else "+${-s.amount}", color = if (s.amount > 0) MaterialTheme.colorScheme.onSurface else AppTheme.status.good)
                            IconButton(onClick = { day = day.copy(spends = day.spends.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove ${s.what}") }
                        }
                    }
                }
            }
            if (social) {
                InfoCard("Social battery") {
                    val v = day.socialBattery
                    Text(v?.let { "$it of 10" } ?: "Not set", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = (v ?: 5).toFloat(), onValueChange = { day = day.copy(socialBattery = it.toInt()) }, valueRange = 0f..10f, steps = 9,
                        modifier = Modifier.semantics { contentDescription = "Social battery, 0 empty to 10 full" },
                    )
                    Row { Hint("Empty", Modifier.weight(1f)); Hint("Full") }
                }
            }
            InfoCard("Note") {
                OutlinedTextField(day.note.orEmpty(), { day = day.copy(note = it.take(2000).ifEmpty { null }) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { onSave(day) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** − n + with 48dp buttons. */
@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, unit: String, description: String, allowNegative: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(if (allowNegative) value - 1 else (value - 1).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Remove, "Fewer $description") }
        Text("$value", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Rounded.Add, "More $description") }
        Spacer(Modifier.width(4.dp))
        Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A check-in: masking, how loud the day's been, executive function, burnout and medication, each 1–5 where given. */
@Composable
fun NeuroCheckInForm(fields: Set<String>, now: Long, onBack: () -> Unit, onSave: (NeuroCheckIn) -> Unit) {
    var c by remember { mutableStateOf(NeuroCheckIn(at = now)) }
    var pickingWoreOff by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Check in") { TextButton(onClick = { onSave(c) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Answer what you like and skip the rest. Tap a number again to clear it.", Modifier.padding(horizontal = 4.dp))
            if (Fields.NEURO_MASKING in fields || Fields.NEURO_SOCIAL in fields || Fields.NEURO_SENSORY in fields) {
                InfoCard("Today so far") {
                    if (Fields.NEURO_MASKING in fields) StepScale("Masking", c.masking, "None", "A lot", { c = c.copy(masking = it) })
                    if (Fields.NEURO_SOCIAL in fields) StepScale("Social load", c.socialLoad, "Light", "Heavy", { c = c.copy(socialLoad = it) })
                    if (Fields.NEURO_SENSORY in fields) StepScale("Sensory load", c.sensoryLoad, "Calm", "Overloaded", { c = c.copy(sensoryLoad = it) })
                }
            }
            if (Fields.NEURO_EXECUTIVE in fields) {
                InfoCard("How your brain's working") {
                    Hint("Higher is easier.")
                    StepScale("Getting started", c.starting, "Hard", "Easy", { c = c.copy(starting = it) })
                    StepScale("Focus", c.focus, "Scattered", "Clear", { c = c.copy(focus = it) })
                    StepScale("Organising", c.organising, "Hard", "Easy", { c = c.copy(organising = it) })
                    StepScale("Remembering", c.remembering, "Hard", "Easy", { c = c.copy(remembering = it) })
                    StepScale("Switching tasks", c.switching, "Hard", "Easy", { c = c.copy(switching = it) })
                    StepScale("Restlessness", c.restlessness, "Settled", "Restless", { c = c.copy(restlessness = it) })
                    StepScale("Emotions", c.emotions, "Steady", "Big feelings", { c = c.copy(emotions = it) })
                }
            }
            if (Fields.NEURO_BURNOUT in fields) {
                InfoCard("How depleted you feel") {
                    StepScale("Depleted", c.burnout, "Fine", "Burnt out", { c = c.copy(burnout = it) })
                }
            }
            if (Fields.NEURO_MED_EFFECT in fields) {
                InfoCard("Medication") {
                    StepScale("How well it worked", c.medEffect, "Not at all", "Very well", { c = c.copy(medEffect = it) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wore off ${c.woreOffAt?.let { "at $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickingWoreOff = true }) { Text(if (c.woreOffAt == null) "Set the time" else "Change") }
                        if (c.woreOffAt != null) TextButton(onClick = { c = c.copy(woreOffAt = null) }) { Text("Clear") }
                    }
                }
            }
            InfoCard("Note") {
                OutlinedTextField(c.note.orEmpty(), { c = c.copy(note = it.take(2000).ifEmpty { null }) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { onSave(c) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (pickingWoreOff) {
        TimePickDialog(c.woreOffAt?.let { LocalTime.parse(it) } ?: LocalTime.of(15, 0), "When it wore off", onDismiss = { pickingWoreOff = false }) { t ->
            c = c.copy(woreOffAt = "%02d:%02d".format(t.hour, t.minute))
            pickingWoreOff = false
        }
    }
}
