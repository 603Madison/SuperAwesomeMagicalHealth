@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition.voice

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.health.api.ApiJson
import com.health.api.VoiceGoal
import com.health.api.VoicePlan
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CARD_CORNER
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.Stat
import com.health.app.ui.components.ValueRow
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * The plan: on first run, a goal (explained warmly, assuming nothing), the vocal-health notes, a
 * baseline recording (a passage, then 20 seconds of free speech, analysed on the phone), and
 * targets with gentle starting suggestions the person can always change. Afterwards, the same
 * choices as settings, with recordings and a new baseline.
 */

/** The steps of setting up. */
enum class PlanStep { GOAL, HEALTH, BASELINE, TARGETS }

/** A plan kept across rotation as JSON. */
val PlanSaver = Saver<VoicePlan, String>(
    save = { ApiJson.encodeToString(VoicePlan.serializer(), it) },
    restore = { runCatching { ApiJson.decodeFromString(VoicePlan.serializer(), it) }.getOrNull() },
)

/** Vocal-health notes for when the programme hasn't loaded. */
val HEALTH_NOTES = listOf(
    "Practise little and often: ten minutes most days does more than an hour once a week.",
    "Warm up first, and keep water nearby.",
    "It should never hurt. If your throat feels tight, scratchy or tired, stop and rest.",
    "Take rest days, and go easy when you have a cold.",
    "A speech therapist (SLP) is worth seeing for anything that worries you.",
)

/** "Read this aloud": a short passage for the baseline (Aesop's fable, retold). */
const val BASELINE_PASSAGE =
    "The North Wind and the Sun were arguing about which of them was stronger, when a traveller came along wrapped in a warm cloak. " +
        "They agreed that whoever first made the traveller take off the cloak would be called the stronger."

val BASELINE_PROMPTS = listOf("what you did yesterday", "a place you love", "a meal you enjoy", "something you're looking forward to")

// ---- The baseline ----

enum class BaselinePhase { READY, READING, SPEAKING, RESULT }

/** Records the baseline: a passage, then 20 seconds of speaking freely, analysed as it comes in. */
class BaselineViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val app: Context get() = getApplication()
    val store = VoiceStore(application)
    private val listener = VoiceListener()
    val live: StateFlow<VoiceLive> = listener.live
    val problem: StateFlow<String?> = listener.problem

    private val _phase = MutableStateFlow(BaselinePhase.READY)
    val phase: StateFlow<BaselinePhase> = _phase.asStateFlow()
    private val _left = MutableStateFlow(SPEAK_SECONDS)
    val secondsLeft: StateFlow<Int> = _left.asStateFlow()
    private val _result = MutableStateFlow<Baseline?>(null)
    val result: StateFlow<Baseline?> = _result.asStateFlow()

    private var recording: java.io.File? = null
    private var countdown: Job? = null

    fun start(context: Context) {
        recording = if (store.keepRecordings) VoiceRecordings(app).newFile(VoiceRecordings.BASELINE) else null
        listener.start(context, recording)
        _result.value = null
        _phase.value = BaselinePhase.READING
    }

    fun doneReading() {
        _phase.value = BaselinePhase.SPEAKING
        _left.value = SPEAK_SECONDS
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (_left.value > 0) {
                delay(1_000)
                _left.value -= 1
            }
            finish()
        }
    }

    fun finish() {
        countdown?.cancel()
        viewModelScope.launch {
            val frames = listener.stop()
            val baseline = Baseline.of(frames)
            if (baseline == null) recording?.delete()
            _result.value = baseline
            _phase.value = BaselinePhase.RESULT
        }
    }

    fun again() {
        recording?.delete()
        recording = null
        _result.value = null
        _phase.value = BaselinePhase.READY
    }

    /** Leaving partway (skipping, or going back a step): stops listening and keeps nothing. */
    fun cancel() {
        if (_phase.value != BaselinePhase.READING && _phase.value != BaselinePhase.SPEAKING) return
        countdown?.cancel()
        viewModelScope.launch {
            listener.stop()
            recording?.delete()
            recording = null
        }
        _phase.value = BaselinePhase.READY
    }

    override fun onCleared() {
        listener.close()
    }

    companion object {
        const val SPEAK_SECONDS = 20
    }
}

class BaselineActions(
    val start: () -> Unit = {},
    val doneReading: () -> Unit = {},
    val finish: () -> Unit = {},
    val again: () -> Unit = {},
    val use: (Baseline) -> Unit = {},
    val skip: (() -> Unit)? = null,
    val openSettings: () -> Unit = {},
)

/** The baseline, step by step. */
@Composable
fun BaselineRecorder(phase: BaselinePhase, live: VoiceLive, secondsLeft: Int, result: Baseline?, mic: MicState, actions: BaselineActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (phase) {
            BaselinePhase.READY -> {
                InfoCard("Your starting point", icon = Icons.Rounded.Mic) {
                    Text(
                        "A short recording to hear where your voice is today: read a few lines aloud, then talk about anything for 20 seconds. " +
                            "It isn't a test, and there's nothing to get right.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                InfoCard("The microphone", icon = Icons.Rounded.Lock) {
                    Text(if (mic == MicState.DENIED) VoiceWords.NO_MIC else VoiceWords.MIC_WHY, style = MaterialTheme.typography.bodyMedium)
                    if (mic == MicState.DENIED) TextButton(onClick = actions.openSettings) { Text("Open the app's settings") }
                }
                if (mic != MicState.DENIED) {
                    Button(onClick = actions.start, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Mic, null)
                        Text(if (mic == MicState.ALLOWED) "Start" else "Allow the microphone and start", Modifier.padding(start = 8.dp))
                    }
                }
            }
            BaselinePhase.READING -> {
                InfoCard("Read this aloud, at your usual pace", icon = Icons.Rounded.Mic) {
                    Text(BASELINE_PASSAGE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    Listening(live)
                }
                Button(onClick = actions.doneReading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Done reading") }
            }
            BaselinePhase.SPEAKING -> {
                InfoCard("Now talk about anything", icon = Icons.Rounded.Mic, status = "0:${secondsLeft.toString().padStart(2, '0')}") {
                    Text("Maybe ${BASELINE_PROMPTS.joinToString(", ", limit = 3, truncated = "")}or ${BASELINE_PROMPTS.last()}.", style = MaterialTheme.typography.titleMedium)
                    com.health.app.ui.components.ProgressBar(1f - secondsLeft / BaselineViewModel.SPEAK_SECONDS.toFloat(), Modifier.padding(vertical = 10.dp))
                    Listening(live)
                }
                OutlinedButton(onClick = actions.finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish early") }
            }
            BaselinePhase.RESULT -> if (result != null) {
                InfoCard("Your voice today", icon = Icons.Rounded.Flag) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("${result.pitchHz.roundToInt()}", "average pitch, ${VoiceScale.note(result.pitchHz)}", unit = "Hz")
                        result.resonance?.let { Stat("${it.roundToInt()}", "resonance, ${VoiceWords.resonance(it)}") }
                    }
                    Text("Mostly between ${result.lowHz.roundToInt()} and ${result.highHz.roundToInt()} Hz.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Hint("This is just where your voice is today. It isn't a score, and it'll be nice to look back on.", Modifier.padding(top = 6.dp))
                }
                Button(onClick = { actions.use(result) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Use this") }
                OutlinedButton(onClick = actions.again, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Record again") }
            } else {
                InfoCard("Let's try that again", icon = Icons.Rounded.Mic) {
                    Text("There wasn't quite enough voice to go on. Somewhere quiet, with the phone a little closer, usually helps.", style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = actions.again, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Try again") }
            }
        }
        actions.skip?.let { skip -> TextButton(onClick = skip, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") } }
    }
}

@Composable
private fun Listening(live: VoiceLive) {
    val hz = live.pitchHz
    Text(
        if (hz != null) "Hearing you: ${VoiceScale.hzAndNote(hz)}" else "Listening…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

// ---- Setting up, and the plan's settings ----

class PlanActions(
    val back: () -> Unit = {},
    val step: (PlanStep) -> Unit = {},
    val change: (VoicePlan) -> Unit = {},
    val save: () -> Unit = {},
    val keepRecordings: (Boolean) -> Unit = {},
    val deleteRecordings: () -> Unit = {},
    val openRecordings: () -> Unit = {},
    val newBaseline: () -> Unit = {},
)

/** First run: goal, health, baseline, targets. [baseline] draws the baseline step. */
@Composable
fun PlanSetup(step: PlanStep, plan: VoicePlan, health: List<String>, actions: PlanActions, baseline: @Composable () -> Unit) {
    val steps = PlanStep.entries
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar({ if (step.ordinal == 0) actions.back() else actions.step(steps[step.ordinal - 1]) }, "Your voice plan") {
            Text("${step.ordinal + 1} of ${steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.health.app.ui.components.ProgressBar((step.ordinal + 1f) / steps.size, height = 6.dp)
            when (step) {
                PlanStep.GOAL -> {
                    Text("Your voice, your way", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(
                        "Free, guided practice with live feedback, at your own pace. Everything is heard on your phone; no sound ever leaves it.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("What would you like to work on?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    GoalChoice(plan.goal) { actions.change(plan.copy(goal = it)) }
                    Hint("There's no right answer, and you can change it whenever you like.")
                    Button(onClick = { actions.step(PlanStep.HEALTH) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Next") }
                }
                PlanStep.HEALTH -> {
                    InfoCard("Looking after your voice", icon = Icons.Rounded.Spa) {
                        health.ifEmpty { HEALTH_NOTES }.forEach { line ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                                Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Button(onClick = { actions.step(PlanStep.BASELINE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Got it") }
                }
                PlanStep.BASELINE -> baseline()
                PlanStep.TARGETS -> {
                    Text("Where you'd like to head", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Targets(plan, actions)
                    Practice(plan, actions)
                    Hint("These are only starting points. Voices find their own way over time: change any of this whenever you like.")
                    Button(onClick = actions.save, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save my plan") }
                }
            }
        }
    }
}

/** The plan's settings, once it's set up. */
@Composable
fun PlanSettings(plan: VoicePlan, keepRecordings: Boolean, recordings: Int, actions: PlanActions) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(actions.back, "Your voice plan")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("What you're working on", icon = Icons.Rounded.Flag) { GoalChoice(plan.goal) { actions.change(plan.copy(goal = it)) } }
            Targets(plan, actions)
            Practice(plan, actions)
            InfoCard("Your baseline", icon = Icons.Rounded.Mic) {
                val hz = plan.baselinePitchHz
                if (hz == null) Hint("No baseline yet. A short recording shows where your voice is today, to look back on.")
                else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("${hz.roundToInt()}", "average pitch, ${VoiceScale.note(hz)}", unit = "Hz")
                        plan.baselineResonance?.let { Stat("${it.roundToInt()}", "resonance") }
                    }
                    plan.baselineAt?.let { Hint("Recorded ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy"))}.", Modifier.padding(top = 4.dp)) }
                }
                OutlinedButton(onClick = actions.newBaseline, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text(if (hz == null) "Record a baseline" else "Record a new baseline") }
            }
            InfoCard("Recordings", icon = Icons.Rounded.Lock) {
                LabeledSwitch("Keep recordings on this phone", keepRecordings, actions.keepRecordings, subtitle = "To listen back and compare with your baseline. Off unless you turn it on.")
                Text(
                    "On this phone only: recordings are never uploaded, synced or backed up, and signing out deletes them.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                )
                if (recordings > 0) {
                    ValueRow("Your recordings", "$recordings", onClick = actions.openRecordings, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Delete, null)
                        Text("Delete all recordings", Modifier.padding(start = 8.dp))
                    }
                }
            }
            Button(onClick = actions.save, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save changes") }
        }
    }
    if (confirm) DeleteRecordingsDialog(onDismiss = { confirm = false }) {
        confirm = false
        actions.deleteRecordings()
    }
}

@Composable
fun DeleteRecordingsDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all recordings?") },
        text = { Text("They're only on this phone, so they'll be gone for good. Your progress and plan stay as they are.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The three goals, each explained, as large choices. */
@Composable
fun GoalChoice(selected: VoiceGoal, onSelect: (VoiceGoal) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceGoal.entries.forEach { goal ->
            val on = goal == selected
            Card(
                shape = RoundedCornerShape(CARD_CORNER),
                colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { onSelect(goal) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = on, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(goal.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            goal.about, style = MaterialTheme.typography.bodyMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The target range and resonance, with the suggestion for the goal. */
@Composable
private fun Targets(plan: VoicePlan, actions: PlanActions) {
    val suggestion = VoicePlans.suggest(plan.goal, plan.baselinePitchHz, plan.baselineResonance)
    val low = plan.targetLowHz ?: suggestion.lowHz
    val high = plan.targetHighHz ?: suggestion.highHz
    val resonance = plan.targetResonance ?: suggestion.resonance
    val following = low == suggestion.lowHz && high == suggestion.highHz && resonance == suggestion.resonance
    InfoCard("Pitch range", icon = com.health.app.ui.modules.transition.voice.skillIcon(com.health.api.VoiceSkill.PITCH)) {
        RangeStrip(low, high, plan.baselinePitchHz)
        HzStepper("From", low, onChange = { actions.change(plan.copy(targetLowHz = it, targetHighHz = high, targetResonance = resonance)) }, min = 60.0, max = VoiceScale.shift(high, -2.0))
        HzStepper("To", high, onChange = { actions.change(plan.copy(targetLowHz = low, targetHighHz = it, targetResonance = resonance)) }, min = VoiceScale.shift(low, 2.0), max = 500.0)
        plan.baselinePitchHz?.let { Hint("The line on the strip is your voice today: ${VoiceScale.hzAndNote(it)}.", Modifier.padding(top = 4.dp)) }
    }
    InfoCard("Resonance to aim for", icon = Icons.Rounded.WbTwilight) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${resonance.roundToInt()}", style = MaterialTheme.typography.headlineSmall)
            Text("  ${VoiceWords.resonance(resonance)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
        Slider(
            value = resonance.toFloat(),
            onValueChange = { actions.change(plan.copy(targetLowHz = low, targetHighHz = high, targetResonance = it.roundToInt().toDouble())) },
            valueRange = 0f..100f,
            modifier = Modifier.semantics { contentDescription = "Resonance to aim for, from darker (0) to brighter (100)" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text("Darker", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Brighter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        plan.baselineResonance?.let { Hint("Your voice today: about ${it.roundToInt()}.", Modifier.padding(top = 4.dp)) }
    }
    InfoCard("A suggestion to start from", icon = Icons.Rounded.Tune) {
        Text(suggestion.why, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${suggestion.lowHz.roundToInt()}–${suggestion.highHz.roundToInt()} Hz, resonance ${suggestion.resonance.roundToInt()}.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
        if (!following) {
            TextButton(onClick = { actions.change(plan.copy(targetLowHz = suggestion.lowHz, targetHighHz = suggestion.highHz, targetResonance = suggestion.resonance)) }) {
                Text("Use the suggestion")
            }
        } else {
            Hint("Pitch is only part of how a voice sounds: resonance, weight and melody matter just as much.", Modifier.padding(top = 6.dp))
        }
    }
}

/** A frequency moved a semitone at a time, shown in Hz and as a note. */
@Composable
private fun HzStepper(label: String, hz: Double, onChange: (Double) -> Unit, min: Double, max: Double) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${hz.roundToInt()} Hz · ${VoiceScale.note(hz)}", style = MaterialTheme.typography.titleLarge)
        }
        val down = VoiceScale.shift(hz, -1.0).roundToInt().toDouble()
        val up = VoiceScale.shift(hz, 1.0).roundToInt().toDouble()
        IconButton(onClick = { onChange(down) }, enabled = down >= min) { Icon(Icons.Rounded.Remove, "$label a semitone lower") }
        IconButton(onClick = { onChange(up) }, enabled = up <= max) { Icon(Icons.Rounded.Add, "$label a semitone higher") }
    }
}

/** The range on a strip from 70 to 400 Hz (in semitones, as it sounds), with today's voice marked. */
@Composable
private fun RangeStrip(low: Double, high: Double, baseline: Double?) {
    val band = AppTheme.data.light
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val mark = MaterialTheme.colorScheme.onSurface
    val from = 70.0
    val to = 400.0
    fun at(hz: Double) = (ln(hz.coerceIn(from, to) / from) / ln(to / from)).toFloat()
    Canvas(
        Modifier.fillMaxWidth().height(24.dp).padding(vertical = 4.dp).semantics {
            contentDescription = "Your range, ${low.roundToInt()} to ${high.roundToInt()} Hz" + (baseline?.let { ", with your voice today at ${it.roundToInt()} Hz" } ?: "")
        },
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val x0 = at(low) * size.width
        val x1 = at(high) * size.width
        drawRoundRect(band, Offset(x0, 0f), Size(x1 - x0, size.height), r)
        baseline?.let {
            val x = at(it) * size.width
            drawLine(mark, Offset(x, -3f), Offset(x, size.height + 3f), 3.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("Lower", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Higher", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Daily minutes, the reminder, and rest days. */
@Composable
private fun Practice(plan: VoicePlan, actions: PlanActions) {
    var picking by remember { mutableStateOf(false) }
    InfoCard("Practice", icon = Icons.Rounded.Notifications) {
        Text("Minutes a day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
        SegmentedChoice(listOf(5, 10, 15, 20), plan.dailyMinutes.takeIf { it in listOf(5, 10, 15, 20) } ?: 10, { "$it" }, { actions.change(plan.copy(dailyMinutes = it)) })
        Spacer(Modifier.height(12.dp))
        LabeledSwitch(
            "A gentle reminder", plan.reminder != null, { on -> actions.change(plan.copy(reminder = if (on) (plan.reminder ?: "18:30") else null)) },
            subtitle = "It only says \"Time for a little practice\", never what for.",
        )
        plan.reminder?.let { ValueRow("Time", it, onClick = { picking = true }, modifier = Modifier.padding(top = 4.dp)) }
        Text("Rest days", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEachIndexed { i, name ->
                val day = i + 1
                val on = day in plan.restDays
                FilterChip(selected = on, onClick = { actions.change(plan.copy(restDays = if (on) plan.restDays - day else (plan.restDays + day).sorted())) }, label = { Text(name) })
            }
        }
        Hint("Rest is part of training: voices get stronger in between.", Modifier.padding(top = 4.dp))
    }
    if (picking) {
        val current = plan.reminder?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(18, 30)
        TimePickDialog(current, "Reminder time", onDismiss = { picking = false }) { t ->
            picking = false
            actions.change(plan.copy(reminder = "%02d:%02d".format(java.util.Locale.ROOT, t.hour, t.minute)))
        }
    }
}
