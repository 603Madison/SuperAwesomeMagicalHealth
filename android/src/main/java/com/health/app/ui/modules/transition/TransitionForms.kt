@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.BodyChange
import com.health.api.ChecklistItem
import com.health.api.GenderMoment
import com.health.api.HairRemovalMethod
import com.health.api.HairRemovalSession
import com.health.api.Milestone
import com.health.api.MilestoneArea
import com.health.api.MilestoneStatus
import com.health.api.MilestoneTemplate
import com.health.api.MomentKind
import com.health.api.TransitionProfile
import com.health.api.VoiceSession
import com.health.app.attempt
import com.health.app.ui.Format
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.DatePickDialog
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.NavRow
import com.health.app.ui.modules.StepScale
import java.time.LocalDate
import kotlin.math.roundToInt

/** Pronouns, when things began, the person's own goals, and a voice target. */
@Composable
fun TransitionProfileEditor(initial: TransitionProfile, today: LocalDate, onBack: () -> Unit, onSave: (TransitionProfile) -> Unit) {
    var p by remember(initial) { mutableStateOf(initial) }
    var target by remember(initial) { mutableStateOf(initial.voiceTargetHz?.roundToInt()?.toString().orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    fun result() = p.copy(voiceTargetHz = Format.parse(target)?.takeIf { it in 50.0..400.0 })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Your transition") { TextButton(onClick = { onSave(result()) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Pronouns") {
                OutlinedTextField(p.pronouns.orEmpty(), { p = p.copy(pronouns = it.take(40).ifEmpty { null }) }, label = { Text("Your pronouns") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint("Shown on your own pages, and to people you share with.")
            }
            InfoCard("When you began") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.startedOn?.let { CareFormat.date(if (it.length == 7) "$it-01" else it) } ?: "Not set", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text(if (p.startedOn == null) "Set" else "Change") }
                    if (p.startedOn != null) TextButton(onClick = { p = p.copy(startedOn = null) }) { Text("Clear") }
                }
                Hint("In whatever sense matters to you: coming out to yourself, a first step, a new name.")
            }
            InfoCard("Your goals") {
                ChipPicker(TransitionWords.GOALS, p.goals.toSet(), { p = p.copy(goals = it.toList()) }, addLabel = "In your words")
            }
            InfoCard("Voice target") {
                NumberField(target, { target = it.take(5) }, "A pitch to practise towards", suffix = "Hz", decimals = false)
                Hint("Optional. The pitch meter shades a range around it. Any target is yours to choose, or none.")
            }
            Button(onClick = { onSave(result()) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (picking) {
        DatePickDialog(p.startedOn?.let { attempt { LocalDate.parse(if (it.length == 7) "$it-01" else it) }.getOrNull() } ?: today, onDismiss = { picking = false }) {
            p = p.copy(startedOn = it.coerceAtMost(today).toString())
            picking = false
        }
    }
}

/** The ready-made milestones to start from, by area, and writing one's own. */
@Composable
fun MilestoneTemplates(templates: List<MilestoneTemplate>, onBack: () -> Unit, onPick: (Int?) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Add a milestone")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Write your own") }
            Hint("Or start from one of these, and change it to fit. They're suggestions, not a list to work through.", Modifier.padding(horizontal = 4.dp))
            MilestoneArea.entries.forEach { area ->
                val list = templates.withIndex().filter { it.value.area == area }
                if (list.isEmpty()) return@forEach
                InfoCard(area.label) {
                    list.forEach { (i, t) ->
                        NavRow(
                            Icons.Rounded.Flag, t.title,
                            listOfNotNull(t.about, t.checklist.takeIf { it.isNotEmpty() }?.let { "${it.size} steps" }).joinToString(" · ").ifEmpty { null },
                            onClick = { onPick(i) },
                        )
                    }
                }
            }
        }
    }
}

/** A milestone: what, which area, how far along, when, notes, and the checklist. */
@Composable
fun MilestoneEditor(initial: Milestone, today: LocalDate, onBack: () -> Unit, onSave: (Milestone) -> Unit, onDelete: ((String) -> Unit)?) {
    var m by remember(initial) { mutableStateOf(initial) }
    var item by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (initial.id == null) "New milestone" else "Milestone") {
            TextButton(enabled = m.title.isNotBlank(), onClick = { onSave(m) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(null) {
                OutlinedTextField(m.title, { m = m.copy(title = it.take(200)) }, label = { Text("What it is") }, modifier = Modifier.fillMaxWidth())
                Text("Area", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MilestoneArea.entries.forEach { a -> FilterChip(selected = m.area == a, onClick = { m = m.copy(area = a) }, label = { Text(a.label) }) }
                }
                Text("Where it's at", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MilestoneStatus.entries.forEach { s -> FilterChip(selected = m.status == s, onClick = { m = m.copy(status = s) }, label = { Text(s.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        (if (m.status == MilestoneStatus.DONE) "Done on " else "Planned for ") + (CareFormat.date(m.date) ?: "no date"),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) { Text(if (m.date == null) "Set" else "Change") }
                    if (m.date != null) TextButton(onClick = { m = m.copy(date = null) }) { Text("Clear") }
                }
            }
            InfoCard("Checklist") {
                if (m.checklist.isEmpty()) Hint("What's needed, step by step: forms, documents, people to tell.")
                m.checklist.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) {
                            m = m.copy(checklist = m.checklist.toMutableList().also { it[i] = c.copy(done = !c.done) })
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.done, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { m = m.copy(checklist = m.checklist.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove ${c.text}") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(item, { item = it.take(200) }, label = { Text("A step") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(enabled = item.isNotBlank(), onClick = { m = m.copy(checklist = m.checklist + ChecklistItem(item.trim())); item = "" }) { Icon(Icons.Rounded.Add, "Add step") }
                }
                if (m.checklist.isNotEmpty() && m.checklist.all { it.done } && m.status != MilestoneStatus.DONE) {
                    TextButton(onClick = { m = m.copy(status = MilestoneStatus.DONE, date = m.date ?: today.toString()) }) { Text("Every step's done: mark it done") }
                }
            }
            InfoCard("Notes") {
                OutlinedTextField(m.notes.orEmpty(), { m = m.copy(notes = it.take(4000).ifEmpty { null }) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
            Button(enabled = m.title.isNotBlank(), onClick = { onSave(m) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (initial.id != null && onDelete != null) OutlinedButton(onClick = { onDelete(initial.id!!) }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    if (picking) {
        DatePickDialog(m.date?.let { LocalDate.parse(it) } ?: today, onDismiss = { picking = false }) { m = m.copy(date = it.toString()); picking = false }
    }
}

/** A change the person notices, and how they feel about it. */
@Composable
fun BodyChangeForm(initial: BodyChange?, today: LocalDate, onBack: () -> Unit, onSave: (BodyChange) -> Unit, onDelete: ((String) -> Unit)?) {
    var c by remember(initial) { mutableStateOf(initial ?: BodyChange(date = today.toString(), area = "", note = "")) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "A change you notice") { TextButton(enabled = c.area.isNotBlank(), onClick = { onSave(c) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Where") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransitionWords.CHANGE_AREAS.forEach { a -> FilterChip(selected = c.area.equals(a, true), onClick = { c = c.copy(area = a) }, label = { Text(a) }) }
                }
                OutlinedTextField(
                    c.area.takeIf { a -> TransitionWords.CHANGE_AREAS.none { it.equals(a, true) } }.orEmpty(), { c = c.copy(area = it.take(60)) },
                    label = { Text("Or in your words") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            InfoCard("What you notice") {
                OutlinedTextField(c.note, { c = c.copy(note = it.take(4000)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CareFormat.date(c.date).orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text("Change the day") }
                }
            }
            InfoCard("How you feel about it") {
                StepScale("Feeling", c.feeling, "Not good", "Love it", { c = c.copy(feeling = it) })
            }
            Button(enabled = c.area.isNotBlank(), onClick = { onSave(c) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (initial?.id != null && onDelete != null) OutlinedButton(onClick = { onDelete(initial.id!!) }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    if (picking) DatePickDialog(LocalDate.parse(c.date), onDismiss = { picking = false }) { c = c.copy(date = it.coerceAtMost(today).toString()); picking = false }
}

/** A hair removal session. */
@Composable
fun HairRemovalForm(now: Long, onBack: () -> Unit, onSave: (HairRemovalSession) -> Unit) {
    var h by remember { mutableStateOf(HairRemovalSession(at = now, method = HairRemovalMethod.LASER, area = "")) }
    var minutes by remember { mutableStateOf("") }
    var pulses by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    fun result() = h.copy(minutes = minutes.toIntOrNull(), pulses = pulses.toIntOrNull(), cost = Format.parse(cost))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Hair removal") { TextButton(enabled = h.area.isNotBlank(), onClick = { onSave(result()) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("How") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HairRemovalMethod.entries.forEach { m -> FilterChip(selected = h.method == m, onClick = { h = h.copy(method = m) }, label = { Text(m.label) }) }
                }
            }
            InfoCard("Where") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransitionWords.HAIR_AREAS.forEach { a -> FilterChip(selected = h.area.equals(a, true), onClick = { h = h.copy(area = a) }, label = { Text(a) }) }
                }
                OutlinedTextField(h.area.takeIf { a -> TransitionWords.HAIR_AREAS.none { it.equals(a, true) } }.orEmpty(), { h = h.copy(area = it.take(60)) }, label = { Text("Or another area") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            InfoCard("Details (all optional)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, "Minutes", Modifier.weight(1f), decimals = false)
                    NumberField(pulses, { pulses = it.filter(Char::isDigit).take(7) }, "Pulses", Modifier.weight(1f), decimals = false)
                }
                OutlinedTextField(h.provider.orEmpty(), { h = h.copy(provider = it.take(120).ifEmpty { null }) }, label = { Text("Clinic or provider") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField(cost, { cost = it.take(10) }, "Cost")
                Text("Pain: ${h.pain?.let { "$it of 10" } ?: "not set"}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    (h.pain ?: 0).toFloat(), { h = h.copy(pain = it.roundToInt()) }, valueRange = 0f..10f, steps = 9,
                    modifier = Modifier.semantics { contentDescription = "Pain, 0 none to 10 the worst" },
                )
                OutlinedTextField(h.note.orEmpty(), { h = h.copy(note = it.take(2000).ifEmpty { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            }
            Button(enabled = h.area.isNotBlank(), onClick = { onSave(result()) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** A moment of euphoria (worth keeping) or dysphoria (worth understanding). */
@Composable
fun MomentForm(kind: MomentKind, now: Long, onBack: () -> Unit, onSave: (GenderMoment) -> Unit) {
    var m by remember { mutableStateOf(GenderMoment(at = now, kind = kind)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (m.kind == MomentKind.EUPHORIA) "A moment of euphoria" else "A moment of dysphoria") { TextButton(onClick = { onSave(m) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MomentKind.entries.forEach { k -> FilterChip(selected = m.kind == k, onClick = { m = m.copy(kind = k) }, label = { Text(k.label) }) }
                }
                Hint(
                    if (m.kind == MomentKind.EUPHORIA) "Worth keeping: looking back on these can help on harder days."
                    else "Worth understanding. Dysphoria can be heavy; if it is, reaching out to someone you trust can help.",
                )
                StepScale("How strong", m.intensity, "A little", "Very", { m = m.copy(intensity = it ?: 3) })
            }
            InfoCard("What it was about") {
                ChipPicker(TransitionWords.MOMENT_CONTEXTS, m.context.toSet(), { m = m.copy(context = it.toList()) })
            }
            InfoCard("Note") {
                OutlinedTextField(m.note.orEmpty(), { m = m.copy(note = it.take(2000).ifEmpty { null }) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { onSave(m) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

/** Voice practice: time, exercises, how it felt, and pitch from the meter if it was used. */
@Composable
fun VoiceForm(initial: VoiceSession, onBack: () -> Unit, onSave: (VoiceSession) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    var minutes by remember(initial) { mutableStateOf(initial.minutes.toString()) }
    fun result() = v.copy(minutes = minutes.toIntOrNull()?.coerceIn(1, 600) ?: 1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Voice practice") { TextButton(onClick = { onSave(result()) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(null) {
                NumberField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, "Minutes", suffix = "min", decimals = false)
                if (v.pitchAvgHz != null) {
                    Hint("Pitch from the meter: ${v.pitchMinHz?.roundToInt()}–${v.pitchMaxHz?.roundToInt()} Hz, averaging ${v.pitchAvgHz?.roundToInt()} Hz.")
                }
            }
            InfoCard("What you practised") {
                ChipPicker(TransitionWords.VOICE_EXERCISES, v.exercises.toSet(), { v = v.copy(exercises = it.toList()) })
            }
            InfoCard("How it felt") {
                StepScale("Feeling", v.feeling, "Hard", "Great", { v = v.copy(feeling = it) })
                OutlinedTextField(v.note.orEmpty(), { v = v.copy(note = it.take(2000).ifEmpty { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { onSave(result()) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
