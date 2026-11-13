@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.neuro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.RoutineStep
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ProgressBar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private val DAY_LETTERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Every routine, with how far each got today. */
@Composable
fun RoutinesList(routines: List<Routine>, runs: List<RoutineRun>, today: LocalDate, readOnly: Boolean, onBack: () -> Unit, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Routines") {
            if (!readOnly) IconButton(onClick = { open("neuro/routine/edit") }) { Icon(Icons.Rounded.Add, "New routine") }
        }
        if (routines.isEmpty()) {
            EmptyState(
                Icons.Rounded.Checklist, "No routines yet",
                "A routine is a short checklist (getting ready, winding down, leaving the house) with how long each step takes.",
            ) { if (!readOnly) Button(onClick = { open("neuro/routine/edit") }) { Text("Make one") } }
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            routines.forEach { r ->
                val done = runs.firstOrNull { it.routineId == r.id && it.date == today.toString() }?.done?.size ?: 0
                val minutes = r.steps.mapNotNull { it.minutes }.sum()
                InfoCard(
                    r.name, icon = Icons.Rounded.Checklist,
                    trailing = { if (!readOnly) TextButton(onClick = { open("neuro/routine/edit?id=${r.id}") }) { Text("Edit") } },
                ) {
                    Hint(listOfNotNull(
                        "${r.steps.size} ${if (r.steps.size == 1) "step" else "steps"}",
                        minutes.takeIf { it > 0 }?.let { "about $it min" },
                        r.reminder?.let { "reminds at $it" },
                        r.days.takeIf { it.isNotEmpty() && it.size < 7 }?.let { d -> d.joinToString(" ") { DAY_LETTERS[it - 1] } },
                        "paused".takeIf { !r.active },
                    ).joinToString(" · "))
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(if (r.steps.isEmpty()) 0f else done.toFloat() / r.steps.size)
                    Hint("$done of ${r.steps.size} done today")
                    if (!readOnly) FilledTonalButton(onClick = { open("neuro/routine/run?id=${r.id}") }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (done in 1 until r.steps.size) "Carry on" else "Start")
                    }
                }
            }
        }
    }
}

/** Making or changing a routine: steps with minutes, a reminder, the days, and (if the assistant is on) breaking a task into steps. */
@Composable
fun RoutineEditor(
    initial: Routine?,
    canBreakDown: Boolean,
    breakDown: suspend (String) -> List<RoutineStep>,
    onBack: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var r by remember(initial) { mutableStateOf(initial ?: Routine(name = "", steps = emptyList())) }
    var stepText by remember { mutableStateOf("") }
    var stepMinutes by remember { mutableStateOf("") }
    var pickingTime by remember { mutableStateOf(false) }
    val valid = r.name.isNotBlank() && r.steps.isNotEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (initial == null) "New routine" else "Edit routine") {
            TextButton(enabled = valid, onClick = { onSave(r) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(null) {
                OutlinedTextField(r.name, { r = r.copy(name = it.take(80)) }, label = { Text("Name, like \"Getting ready\"") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            InfoCard("Steps") {
                if (r.steps.isEmpty()) Hint("Small steps work best: \"brush teeth\" rather than \"get ready\".")
                r.steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${s.text}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        s.minutes?.let { Text("$it min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(enabled = i > 0, onClick = { r = r.copy(steps = r.steps.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                        IconButton(enabled = i < r.steps.lastIndex, onClick = { r = r.copy(steps = r.steps.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { r = r.copy(steps = r.steps.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove ${s.text}") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(stepText, { stepText = it.take(200) }, label = { Text("A step") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(stepMinutes, { stepMinutes = it.filter(Char::isDigit).take(3) }, label = { Text("Min") }, singleLine = true, modifier = Modifier.width(80.dp))
                    IconButton(enabled = stepText.isNotBlank(), onClick = {
                        r = r.copy(steps = r.steps + RoutineStep(stepText.trim(), stepMinutes.toIntOrNull()?.takeIf { it > 0 }))
                        stepText = ""
                        stepMinutes = ""
                    }) { Icon(Icons.Rounded.Add, "Add step") }
                }
                val total = r.steps.mapNotNull { it.minutes }.sum()
                if (total > 0) Hint("About $total minutes in all.")
            }
            if (canBreakDown) BreakDownCard(breakDown) { steps -> r = r.copy(steps = r.steps + steps) }
            InfoCard("Reminder") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.reminder?.let { "A gentle nudge at $it" } ?: "No reminder", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickingTime = true }) { Text(if (r.reminder == null) "Set" else "Change") }
                    if (r.reminder != null) TextButton(onClick = { r = r.copy(reminder = null) }) { Text("None") }
                }
                Text("On", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_LETTERS.forEachIndexed { i, d ->
                        val day = i + 1
                        val on = r.days.isEmpty() || day in r.days
                        FilterChip(selected = on, onClick = {
                            val now = if (r.days.isEmpty()) (1..7).toList() else r.days
                            val next = if (day in now) now - day else (now + day).sorted()
                            r = r.copy(days = if (next.size == 7) emptyList() else next)
                        }, label = { Text(d) })
                    }
                }
                Hint("Reminders come from this phone, and from your server to your browsers.")
            }
            InfoCard(null) {
                com.health.app.ui.components.LabeledSwitch("On", r.active, { r = r.copy(active = it) }, subtitle = "Paused routines keep their steps but don't remind.")
            }
            Button(enabled = valid, onClick = { onSave(r) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (initial?.id != null && onDelete != null) OutlinedButton(onClick = { onDelete(initial.id!!) }, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    if (pickingTime) {
        TimePickDialog(r.reminder?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(8, 0), "Remind me at", onDismiss = { pickingTime = false }) { t ->
            r = r.copy(reminder = "%02d:%02d".format(t.hour, t.minute))
            pickingTime = false
        }
    }
}

/** "Break it into steps": only the task's words go to the assistant. */
@Composable
private fun BreakDownCard(breakDown: suspend (String) -> List<RoutineStep>, onSteps: (List<RoutineStep>) -> Unit) {
    var task by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    InfoCard("Break it into steps", icon = Icons.Rounded.AutoAwesome) {
        Hint("Type a task and the AI assistant suggests small steps. Only the task's words are sent to it (it's run by Anthropic), nothing else about you.")
        OutlinedTextField(task, { task = it.take(300) }, label = { Text("A task, like \"clean the kitchen\"") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(
            enabled = task.isNotBlank() && !busy,
            onClick = {
                busy = true
                problem = null
                scope.launch {
                    runCatching { breakDown(task.trim()) }
                        .onSuccess { onSteps(it); task = "" }
                        .onFailure { problem = it.message ?: "The assistant couldn't help just now" }
                    busy = false
                }
            },
            modifier = Modifier.padding(top = 6.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp) else Text("Suggest steps")
        }
    }
}

/** "Now doing": one step at a time, large, with a timer for the step and the checklist below. */
@Composable
fun RoutineRunner(routine: Routine, run: RoutineRun?, now: Long, stepStartedAt: Long, onBack: () -> Unit, onDone: (List<Int>) -> Unit, onStepStarted: () -> Unit) {
    val done = run?.done.orEmpty()
    var skipped by remember { mutableStateOf(emptySet<Int>()) }
    val current = routine.steps.indices.firstOrNull { it !in done && it !in skipped } ?: routine.steps.indices.firstOrNull { it !in done }
    fun set(next: List<Int>) {
        onDone(next.distinct().sorted())
        onStepStarted()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, routine.name)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressBar(if (routine.steps.isEmpty()) 0f else done.size.toFloat() / routine.steps.size, height = 10.dp)
            Hint("${done.size} of ${routine.steps.size} done")
            if (current == null) {
                AppCard(padding = 24.dp) {
                    Text("All done", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text("Nicely done. That's ${routine.name.lowercase()} finished.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            } else {
                val step = routine.steps[current]
                val elapsed = (now - stepStartedAt).coerceAtLeast(0)
                AppCard(padding = 24.dp) {
                    Text("Now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(step.text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        CareFormat.clock(elapsed) + (step.minutes?.let { " of about $it min" } ?: ""),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp).semantics { contentDescription = "${CareFormat.duration(elapsed)} on this step" },
                    )
                    step.minutes?.let { m -> ProgressBar((elapsed / (m * 60_000f)).coerceAtMost(1f), Modifier.padding(top = 8.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Button(onClick = { set(done + current) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Done") }
                        OutlinedButton(onClick = { skipped = skipped + current; onStepStarted() }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Skip for now") }
                    }
                    routine.steps.getOrNull(current + 1)?.let { Hint("Then: ${it.text}", Modifier.padding(top = 10.dp)) }
                }
            }
            InfoCard("Checklist") {
                routine.steps.forEachIndexed { i, s ->
                    val checked = i in done
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { set(if (checked) done - i else done + i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        s.minutes?.let { Hint("$it min") }
                    }
                }
            }
        }
    }
}
