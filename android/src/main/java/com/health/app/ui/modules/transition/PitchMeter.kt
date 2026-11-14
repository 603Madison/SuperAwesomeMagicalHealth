package com.health.app.ui.modules.transition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.health.app.attempt
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Stat
import com.health.app.ui.theme.LocalDataColors
import com.health.app.ui.theme.reduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A pitch reading at a moment, for the trace. */
data class PitchPoint(val at: Long, val hz: Double?)

/**
 * Listens to the microphone and works out the voice's pitch, live, in memory: the sound is read
 * into one buffer that's overwritten again and again, never stored and never sent. Only the
 * numbers (the trace, and the session's lowest, average and highest) are kept, until saved or left.
 */
class PitchMeter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _trace = MutableStateFlow<List<PitchPoint>>(emptyList())
    val trace: StateFlow<List<PitchPoint>> = _trace.asStateFlow()

    private val _current = MutableStateFlow<Double?>(null)
    val current: StateFlow<Double?> = _current.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    var stats = PitchStats()
        private set

    var startedAt: Long = 0
        private set

    val running: Boolean get() = job?.isActive == true

    /** Starts listening (the caller has the microphone permission). */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        stats = PitchStats()
        startedAt = System.currentTimeMillis()
        _trace.value = emptyList()
        _problem.value = null
        job = scope.launch {
            val rate = 44_100
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val frame = 2_048
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuffer, frame * 4))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _problem.value = "The microphone couldn't be opened"
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _problem.value = "The microphone couldn't be opened"
                return@launch
            }
            val detector = PitchDetector(rate)
            val buffer = FloatArray(frame)
            try {
                record.startRecording()
                while (isActive) {
                    var filled = 0
                    while (filled < frame && isActive) {
                        val n = record.read(buffer, filled, frame - filled, AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled < frame) continue
                    val steady = stats.add(detector.detect(buffer, filled))
                    _current.value = steady
                    val now = System.currentTimeMillis()
                    // The last 12 seconds.
                    _trace.value = (_trace.value + PitchPoint(now, steady)).dropWhile { it.at < now - 12_000 }
                }
            } finally {
                attempt { record.stop() }
                record.release()
                buffer.fill(0f)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _current.value = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}

/** The pitch meter: why the microphone is needed, then a live trace against the target band. */
@Composable
fun PitchMeterScreen(target: Double?, onBack: () -> Unit, onSave: (minutes: Int, min: Double?, avg: Double?, max: Double?) -> Unit) {
    val context = LocalContext.current
    val meter = remember { PitchMeter() }
    DisposableEffect(Unit) { onDispose { meter.close() } }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) { meter.start(context); listening = true; finished = false }
    }
    val trace by meter.trace.collectAsStateThrottled(reduceMotion())
    val current by meter.current.collectAsStateThrottled(reduceMotion())
    val problem by meter.problem.collectAsStateThrottled(false)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar({ meter.stop(); onBack() }, "Pitch meter")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!listening && !finished) {
                InfoCard("Before you start", icon = Icons.Rounded.Lock) {
                    Text(
                        "The meter listens through your phone's microphone and works out the pitch of your voice as you speak. " +
                            "Your voice is never recorded: the sound stays in the phone's memory for a split second, is never stored or sent, " +
                            "and only the numbers you choose to save go to your server.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (denied) {
                        ErrorCard("The microphone is off for this app.", Modifier.padding(top = 8.dp), actionLabel = "Settings") {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    Button(
                        onClick = {
                            if (granted) { meter.start(context); listening = true } else ask.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 10.dp),
                    ) {
                        Icon(Icons.Rounded.Mic, null)
                        Text(if (granted) "Start listening" else "Allow the microphone and start", Modifier.padding(start = 8.dp))
                    }
                }
            }
            problem?.let { ErrorCard(it) }
            if (listening || finished) {
                PitchTraceCard(trace, current, target, listening)
                val stats = meter.stats
                InfoCard("This session") {
                    Row {
                        Stat(stats.min?.roundToInt()?.toString() ?: "—", "lowest", Modifier.weight(1f), unit = "Hz")
                        Stat(stats.average?.roundToInt()?.toString() ?: "—", "average", Modifier.weight(1f), unit = "Hz")
                        Stat(stats.max?.roundToInt()?.toString() ?: "—", "highest", Modifier.weight(1f), unit = "Hz")
                    }
                }
                if (listening) {
                    Button(onClick = { meter.stop(); listening = false; finished = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Stop") }
                } else {
                    val minutes = (((System.currentTimeMillis() - meter.startedAt) / 60_000).toInt()).coerceAtLeast(1)
                    Button(onClick = { onSave(minutes, stats.min, stats.average, stats.max) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save this practice") }
                    OutlinedButton(onClick = { meter.start(context); listening = true; finished = false }, modifier = Modifier.fillMaxWidth()) { Text("Start again") }
                }
            }
            Hint("Pitch is only one part of a voice; resonance, rhythm and how it feels to you matter as much.", Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The last few seconds of pitch, with the target band shaded behind it. */
@Composable
fun PitchTraceCard(trace: List<PitchPoint>, current: Double?, target: Double?, listening: Boolean) {
    val band = PitchTarget.band(target)
    val colors = LocalDataColors.current
    val line = MaterialTheme.colorScheme.primary
    val bandColor = colors.light.copy(alpha = 0.35f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val words = current?.let { PitchTarget.describe(it, band) }
    InfoCard("Your pitch") {
        Row {
            Stat(current?.roundToInt()?.toString() ?: "—", if (listening) (if (current == null) "speak or hum" else "now") else "stopped", Modifier.weight(1f), unit = "Hz")
            words?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        val values = trace.mapNotNull { it.hz }
        val lo = (listOfNotNull(values.minOrNull(), band?.start).minOrNull() ?: 80.0) - 20
        val hi = (listOfNotNull(values.maxOrNull(), band?.endInclusive).maxOrNull() ?: 300.0) + 20
        Canvas(
            Modifier.fillMaxWidth().height(160.dp).semantics {
                contentDescription = "Pitch over the last few seconds" + (band?.let { ", against your range of ${it.start.roundToInt()} to ${it.endInclusive.roundToInt()} Hz" } ?: "")
            },
        ) {
            fun y(hz: Double) = (size.height - ((hz - lo) / (hi - lo)).toFloat() * size.height).coerceIn(0f, size.height)
            band?.let { b ->
                val top = y(b.endInclusive)
                drawRect(bandColor, Offset(0f, top), Size(size.width, y(b.start) - top))
                drawLine(grid, Offset(0f, y(target!!)), Offset(size.width, y(target)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (trace.size < 2) return@Canvas
            val end = trace.last().at
            fun x(at: Long) = size.width * (1f - (end - at) / 12_000f)
            var path: Path? = null
            trace.forEach { p ->
                val hz = p.hz
                if (hz == null) {
                    path?.let { drawPath(it, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
                    path = null
                } else {
                    val point = Offset(x(p.at), y(hz))
                    path = (path ?: Path().apply { moveTo(point.x, point.y) }).apply { lineTo(point.x, point.y) }
                }
            }
            path?.let { drawPath(it, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            LegendItem(line, "Your pitch")
            if (band != null) LegendItem(colors.light, "Your range, ${band.start.roundToInt()}–${band.endInclusive.roundToInt()} Hz")
        }
        if (band == null) Hint("Set a target pitch in your transition profile to see a range here.")
    }
}

/** A flow's latest value, redrawn at most four times a second when things should keep still. */
@Composable
private fun <T> StateFlow<T>.collectAsStateThrottled(slow: Boolean): androidx.compose.runtime.State<T> {
    val state = remember { mutableStateOf(value) }
    androidx.compose.runtime.LaunchedEffect(this, slow) {
        if (slow) {
            while (true) {
                state.value = value
                kotlinx.coroutines.delay(250)
            }
        } else {
            collect { state.value = it }
        }
    }
    return state
}
