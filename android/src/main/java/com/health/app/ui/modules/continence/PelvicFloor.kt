package com.health.app.ui.modules.continence

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.delay

@Composable
fun PelvicFloorRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { ContinenceViewModel(c) }
    PelvicFloorScreen(onBack) { reps, hold -> vm.save(ContinenceEntry(System.currentTimeMillis(), ContinenceKind.PELVIC_FLOOR, reps = reps, holdSeconds = hold)); onBack() }
}

/**
 * A pelvic floor set: squeeze and lift for a few seconds, then let go fully for as long, a number
 * of times; or quick flicks, a second each. The ring fills while squeezing and empties while relaxing.
 */
@Composable
fun PelvicFloorScreen(onBack: () -> Unit, onLog: (reps: Int, holdSeconds: Int) -> Unit) {
    var quick by remember { mutableStateOf(false) }
    var reps by remember { mutableIntStateOf(10) }
    var hold by remember { mutableIntStateOf(5) }
    var running by remember { mutableStateOf(false) }
    var squeezing by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val seconds = if (quick) 1 else hold

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running && done < reps) {
            squeezing = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            for (s in seconds downTo 1) { left = s; delay(1000) }
            squeezing = false
            for (s in seconds downTo 1) { left = s; delay(1000) }
            done++
        }
        if (done >= reps) {
            running = false
            finished = true
        }
    }
    val fill by animateFloatAsState(if (squeezing) 1f else 0.25f, tween(if (running) seconds * 900 else 0), label = "squeeze")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackBar(onBack, "Pelvic floor exercises")
        Hint(
            "Squeeze and lift as if stopping yourself from passing wind and urine, without holding your breath or tightening " +
                "your tummy or bottom. Then let go completely.",
            Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (!running && done == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !quick, onClick = { quick = false }, label = { Text("Long squeezes") })
                FilterChip(selected = quick, onClick = { quick = true }, label = { Text("Quick flicks") })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { n -> FilterChip(selected = reps == n, onClick = { reps = n }, label = { Text("$n times") }) }
            }
            if (!quick) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 10).forEach { s -> FilterChip(selected = hold == s, onClick = { hold = s }, label = { Text("${s}s hold") }) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(track, radius = r, style = Stroke(10.dp.toPx()))
                drawCircle(primary.copy(alpha = 0.25f), radius = r * fill)
                drawCircle(primary, radius = r * fill, style = Stroke(3.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    when {
                        finished -> "All done"
                        !running -> "Ready"
                        squeezing -> "Squeeze"
                        else -> "Relax"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (running) Text("$left", style = MaterialTheme.typography.displayMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("$done of $reps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!finished) Button(onClick = { running = !running }) { Text(if (running) "Pause" else if (done == 0) "Start" else "Carry on") }
            if (done > 0) OutlinedButton(onClick = { onLog(done, seconds) }) { Text("Save the set") }
        }
    }
}
