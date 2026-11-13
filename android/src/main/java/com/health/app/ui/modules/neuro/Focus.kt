@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.neuro

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.FocusKind
import com.health.api.FocusSession
import com.health.app.reminders.RunningFocus
import com.health.app.ui.care.CareFormat
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme

/** What the focus page can do. */
class FocusActions(
    val back: () -> Unit = {},
    val start: (RunningFocus) -> Unit = {},
    val addBreak: () -> Unit = {},
    val finish: (rating: Int?, note: String?) -> Unit = { _, _ -> },
    val discard: () -> Unit = {},
)

private val KIND_ABOUT = mapOf(
    FocusKind.FOCUS to "A timer for one task.",
    FocusKind.BODY_DOUBLING to "Someone with you, in person or on a call, while you each get on with things.",
    FocusKind.INTEREST to "Time for something you love. It counts, and it recharges.",
    FocusKind.HYPERFOCUS to "Deep in something: log it to see where the hours go, without judging them.",
)

/** The focus timer: set up (task, a guess, chimes), then the time so far, breaks, and how it went. */
@Composable
fun FocusScreen(running: RunningFocus?, history: List<FocusSession>, ratio: Double?, showTime: Boolean, now: Long, readOnly: Boolean, actions: FocusActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(actions.back, "Focus")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                readOnly -> {}
                running == null -> FocusSetup(ratio, showTime, actions.start, now)
                else -> FocusRunning(running, now, actions)
            }
            if (showTime) {
                InfoCard("How long things take") {
                    if (ratio != null) Text(FocusMath.describe(ratio), style = MaterialTheme.typography.bodyLarge)
                    else Hint("Guess before you start. After three sessions with a guess, you'll see how long things really take for you.")
                    val withGuess = history.filter { FocusMath.ratio(it) != null }.take(8)
                    if (withGuess.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        GuessBars(withGuess)
                    }
                }
            }
            if (history.isNotEmpty()) {
                InfoCard("Recent sessions") {
                    history.filter { it.end != null }.take(10).forEach { f ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(f.task ?: f.kind.label, style = MaterialTheme.typography.bodyLarge)
                            Hint(listOfNotNull(f.kind.label.takeIf { f.task != null }, FocusMath.line(f).ifEmpty { null }, f.rating?.let { "went $it of 5" }).joinToString(" · "))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusSetup(ratio: Double?, showTime: Boolean, onStart: (RunningFocus) -> Unit, now: Long) {
    var kind by remember { mutableStateOf(FocusKind.FOCUS) }
    var task by remember { mutableStateOf("") }
    var guess by remember { mutableStateOf<Int?>(null) }
    var custom by remember { mutableStateOf("") }
    var chime by remember { mutableStateOf<Int?>(null) }
    InfoCard("Start a session") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        Hint(KIND_ABOUT.getValue(kind))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(task, { task = it.take(200) }, label = { Text("What you're doing (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (showTime) {
            Text("How long do you think it'll take?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 25, 45, 60, 90).forEach { m -> FilterChip(selected = guess == m, onClick = { guess = if (guess == m) null else m; custom = "" }, label = { Text(FocusMath.mins(m)) }) }
            }
            NumberField(custom, { custom = it.filter(Char::isDigit).take(4); guess = custom.toIntOrNull() }, "Or minutes", decimals = false)
            guess?.let { g -> FocusMath.allowFor(g, ratio)?.let { allow -> Hint("From your past sessions, it may be worth allowing about ${FocusMath.mins(allow)}.") } }
        }
        Text("Break chimes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf<Int?>(null, 25, 45, 60).forEach { m ->
                FilterChip(selected = chime == m, onClick = { chime = m }, label = { Text(m?.let { "Every $it min" } ?: "None") })
            }
        }
        Hint("A quiet notification suggesting a break. Carry on if you're in the flow.")
        Button(
            onClick = { onStart(RunningFocus(start = now, kind = kind, task = task.trim().ifEmpty { null }, estimateMinutes = guess, chimeEveryMinutes = chime)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 12.dp),
        ) { Text("Start") }
    }
}

@Composable
private fun FocusRunning(running: RunningFocus, now: Long, actions: FocusActions) {
    var finishing by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val elapsed = (now - running.start).coerceAtLeast(0)
    val guessMs = running.estimateMinutes?.times(60_000L)
    InfoCard(running.task ?: running.kind.label) {
        if (running.task != null) Hint(running.kind.label)
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            val color = if (guessMs != null && elapsed > guessMs) AppTheme.status.warn else MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(200.dp)) {
                val stroke = Stroke(14.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = stroke)
                val fraction = guessMs?.let { (elapsed.toFloat() / it).coerceAtMost(1f) } ?: ((elapsed % 3_600_000L) / 3_600_000f)
                drawArc(color, -90f, 360f * fraction, false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = "${CareFormat.duration(elapsed)} so far" }) {
                Text(CareFormat.clock(elapsed), style = MaterialTheme.typography.displaySmall)
                Text(guessMs?.let { "of about ${running.estimateMinutes} min" } ?: "so far", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (guessMs != null && elapsed > guessMs) {
            Text("Past your guess. That's information, not a failure.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Hint("${running.breaks} ${if (running.breaks == 1) "break" else "breaks"} so far" + (running.chimeEveryMinutes?.let { " · a chime every $it min" } ?: ""))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            FilledTonalButton(onClick = actions.addBreak, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Took a break") }
            Button(onClick = { finishing = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Finish") }
        }
        TextButton(onClick = { discarding = true }, modifier = Modifier.align(Alignment.End)) { Text("Discard") }
    }
    if (finishing) {
        var rating by remember { mutableStateOf<Int?>(null) }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { finishing = false },
            title = { Text("How did it go?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${CareFormat.duration(elapsed)}" + (running.estimateMinutes?.let { ", against a guess of $it min" } ?: "") + ".")
                    StepScale("It went", rating, "Hard", "Well", { rating = it })
                    OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { finishing = false; actions.finish(rating, note.trim().ifEmpty { null }) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("Keep going") } },
        )
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Discard this session?") },
            text = { Text("It won't be saved.") },
            confirmButton = { TextButton(onClick = { discarding = false; actions.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("Keep it") } },
        )
    }
}

/** Each session's guess against what it took, as pairs of bars (guess lighter, actual full). */
@Composable
private fun GuessBars(sessions: List<FocusSession>) {
    val guess = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val took = MaterialTheme.colorScheme.primary
    val top = sessions.maxOf { maxOf(it.estimateMinutes ?: 0, FocusMath.minutes(it) ?: 0) }.coerceAtLeast(1)
    val description = sessions.joinToString("; ") { FocusMath.line(it) }
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = "Guesses against real times: $description" }) {
        val slot = size.width / sessions.size
        val bar = (slot / 3).coerceAtMost(14.dp.toPx())
        sessions.reversed().forEachIndexed { i, s ->
            val g = (s.estimateMinutes ?: 0).toFloat() / top * size.height
            val t = (FocusMath.minutes(s) ?: 0).toFloat() / top * size.height
            val x = i * slot + slot / 2 - bar
            drawRect(guess, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - g), size = androidx.compose.ui.geometry.Size(bar * 0.9f, g))
            drawRect(took, topLeft = androidx.compose.ui.geometry.Offset(x + bar, size.height - t), size = androidx.compose.ui.geometry.Size(bar * 0.9f, t))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        com.health.app.ui.components.LegendItem(guess, "Your guess")
        com.health.app.ui.components.LegendItem(took, "What it took")
    }
}
