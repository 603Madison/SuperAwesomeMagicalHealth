package com.health.app.ui.modules.mind

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.MindfulnessSession
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.FaceScale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A breathing pattern: seconds to breathe in, hold, breathe out, and hold again (0 skips a step). */
data class BreathPattern(val inhale: Double, val holdIn: Double, val exhale: Double, val holdOut: Double) {
    val phases: List<Pair<String, Double>>
        get() = listOf("Breathe in" to inhale, "Hold" to holdIn, "Breathe out" to exhale, "Hold" to holdOut).filter { it.second > 0 }

    val cycleSeconds: Double get() = inhale + holdIn + exhale + holdOut

    companion object {
        fun of(kind: String): BreathPattern = when (kind) {
            "breathing-478" -> BreathPattern(4.0, 7.0, 8.0, 0.0)
            "coherent-breathing" -> BreathPattern(5.5, 0.0, 5.5, 0.0)
            else -> BreathPattern(4.0, 4.0, 4.0, 4.0)
        }
    }
}

@Composable
fun CalmToolRoute(kind: String, onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MindViewModel(c) }
    val log: (MindfulnessSession) -> Unit = { vm.logSession(it); onBack() }
    when (kind) {
        "grounding" -> GroundingScreen(onBack, log)
        "meditation" -> MeditationScreen(onBack, log)
        else -> BreathingScreen(kind, onBack, log)
    }
}

/** Whether the phone asks for less motion (animations off): the circle then stays still and counts. */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * A breathing exercise: a circle that grows as you breathe in and shrinks as you breathe out, with
 * the step and a countdown in words, and a gentle buzz at each change if wanted.
 */
@Composable
fun BreathingScreen(kind: String, onBack: () -> Unit, onLog: (MindfulnessSession) -> Unit, startRunning: Boolean = false) {
    val tool = CALM_TOOLS.firstOrNull { it.kind == kind } ?: CALM_TOOLS.first()
    val pattern = BreathPattern.of(kind)
    val still = reducedMotion()
    val haptics = LocalHapticFeedback.current
    var running by remember { mutableStateOf(startRunning) }
    var buzz by remember { mutableStateOf(false) }
    var phase by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(ceil(pattern.phases.first().second).toInt()) }
    var cycles by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var finishing by remember { mutableStateOf(false) }
    val scale = remember { Animatable(0.55f) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        while (running) {
            val (name, seconds) = pattern.phases[phase]
            if (buzz) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val target = when (name) {
                "Breathe in" -> 1f
                "Breathe out" -> 0.55f
                else -> scale.value
            }
            val ms = (seconds * 1000).roundToInt()
            if (!still) {
                coroutineScope {
                    launch { scale.animateTo(target, tween(ms, easing = LinearEasing)) }
                    var remaining = ms
                    while (remaining > 0) {
                        left = ceil(remaining / 1000.0).toInt()
                        val step = minOf(remaining, 250)
                        delay(step.toLong())
                        remaining -= step
                    }
                }
            } else {
                scale.snapTo(target)
                var remaining = ms
                while (remaining > 0) {
                    left = ceil(remaining / 1000.0).toInt()
                    val step = minOf(remaining, 250)
                    delay(step.toLong())
                    remaining -= step
                }
            }
            phase = (phase + 1) % pattern.phases.size
            if (phase == 0) cycles++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackBar(onBack, tool.title)
        Hint(tool.subtitle, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(24.dp))
        BreathCircle(scale.value, Modifier.size(260.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(if (running) pattern.phases[phase].first else "Ready when you are", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (running) Text("$left", style = MaterialTheme.typography.displayMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (cycles == 0) "${pattern.phases.size} steps a breath, ${pattern.cycleSeconds.let { if (it % 1.0 == 0.0) it.toInt() else it }} seconds each round"
            else "$cycles ${if (cycles == 1) "round" else "rounds"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }) { Text(if (running) "Pause" else if (startedAt == 0L) "Start" else "Carry on") }
            if (startedAt != 0L) OutlinedButton(onClick = { running = false; finishing = true }) { Text("Finish") }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            LabeledSwitch("A gentle buzz at each change", buzz, { buzz = it }, subtitle = "So you can close your eyes.")
        }
    }
    if (finishing) {
        LogSessionDialog(tool.kind, minutes(startedAt), onDismiss = { finishing = false; onBack() }, onLog = onLog)
    }
}

private fun minutes(startedAt: Long): Double =
    if (startedAt == 0L) 1.0 else ((System.currentTimeMillis() - startedAt) / 60_000.0).coerceAtLeast(0.5)

@Composable
private fun BreathCircle(scale: Float, modifier: Modifier, center: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(track, radius = r, style = Stroke(2.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(primary.copy(alpha = 0.45f), tertiary.copy(alpha = 0.18f)), center = Offset(size.width / 2, size.height / 2), radius = r * scale),
                radius = r * scale,
            )
            drawCircle(primary, radius = r * scale, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        center()
    }
}

/** 5-4-3-2-1: noticing what's around you, one sense at a time. */
@Composable
fun GroundingScreen(onBack: () -> Unit, onLog: (MindfulnessSession) -> Unit) {
    val steps = listOf(
        5 to "things you can see",
        4 to "things you can touch",
        3 to "things you can hear",
        2 to "things you can smell",
        1 to "thing you can taste",
    )
    var step by remember { mutableIntStateOf(0) }
    var found by remember { mutableIntStateOf(0) }
    val startedAt = remember { System.currentTimeMillis() }
    var finishing by remember { mutableStateOf(false) }
    val (count, what) = steps[step]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackBar(onBack, "5-4-3-2-1 grounding")
        Hint("Take your time. Look around and name them, out loud or in your head.", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(24.dp))
        AppCard(Modifier.padding(horizontal = 16.dp), padding = 24.dp) {
            Text("$count", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(what, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..count).forEach { i ->
                    FilterChip(selected = i <= found, onClick = { found = if (found >= i) i - 1 else i }, label = { Text("$i") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Hint("Tap one for each you notice.", Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { step--; found = 0 }) { Text("Back") }
            Button(onClick = { if (step < steps.lastIndex) { step++; found = 0 } else finishing = true }) {
                Text(if (step < steps.lastIndex) "Next" else "Done")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Step ${step + 1} of ${steps.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (finishing) LogSessionDialog("grounding", minutes(startedAt), onDismiss = { finishing = false; onBack() }, onLog = onLog)
}

/** A meditation timer: pick the minutes, and a ring counts them down. */
@Composable
fun MeditationScreen(onBack: () -> Unit, onLog: (MindfulnessSession) -> Unit) {
    var length by remember { mutableIntStateOf(5) }
    var running by remember { mutableStateOf(false) }
    var leftMs by remember { mutableLongStateOf(5 * 60_000L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var finishing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        while (running && leftMs > 0) {
            delay(250)
            leftMs = (leftMs - 250).coerceAtLeast(0)
        }
        if (leftMs == 0L) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            running = false
            finishing = true
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackBar(onBack, "Meditation timer")
        Hint("Sit comfortably, let your breathing be as it is, and come back to it whenever your mind wanders.", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(20.dp))
        if (startedAt == 0L) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, 15, 20).forEach { m ->
                    FilterChip(selected = length == m, onClick = { length = m; leftMs = m * 60_000L }, label = { Text("$m min") })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 10.dp.toPx()
                drawArc(track, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
                val f = leftMs / (length * 60_000f)
                drawArc(primary, -90f, 360f * f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            val seconds = ceil(leftMs / 1000.0).toInt()
            Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }) { Text(if (running) "Pause" else if (startedAt == 0L) "Begin" else "Carry on") }
            if (startedAt != 0L) OutlinedButton(onClick = { running = false; finishing = true }) { Text("Finish") }
        }
    }
    if (finishing) {
        val done = ((length * 60_000L - leftMs) / 60_000.0).coerceAtLeast(0.5)
        LogSessionDialog("meditation", done, onDismiss = { finishing = false; onBack() }, onLog = onLog)
    }
}

/** At the end of a tool: keep a note of it (minutes, and how you felt before and after, if you like). */
@Composable
fun LogSessionDialog(kind: String, minutes: Double, onDismiss: () -> Unit, onLog: (MindfulnessSession) -> Unit) {
    var before by remember { mutableStateOf<Int?>(null) }
    var after by remember { mutableStateOf<Int?>(null) }
    val rounded = (minutes * 2).roundToInt() / 2.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nicely done") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${if (rounded % 1.0 == 0.0) rounded.toInt() else rounded} minutes. Keep it in your log?")
                Text("Before, you felt", style = MaterialTheme.typography.titleSmall)
                FaceScale(before, { before = it }, size = 40.dp, labels = false)
                Text("And now", style = MaterialTheme.typography.titleSmall)
                FaceScale(after, { after = it }, size = 40.dp, labels = false)
            }
        },
        confirmButton = { TextButton(onClick = { onLog(MindfulnessSession(System.currentTimeMillis(), rounded, kind, before, after)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
