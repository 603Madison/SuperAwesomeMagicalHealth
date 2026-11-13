@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.VoiceMeter
import com.health.api.VoicePlan
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.modules.transition.PitchPoint
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.theme.reduceMotion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The trainer's live meters, big and clear, each saying what it shows in words as well as by
 * position and colour: pitch (a trace against the person's range, with the note), resonance
 * (darker ↔ brighter, the target marked), weight (lighter ↔ heavier), intonation (the spread of the
 * last few seconds) and loudness (with a gentle word after pushing for a while).
 */

/** The meters an exercise asks for, in a steady order. */
@Composable
fun Meters(meters: List<VoiceMeter>, live: VoiceLive, plan: VoicePlan, speakingDb: Double?, target: Double? = null, matched: Float? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (VoiceMeter.PITCH in meters || target != null) PitchCard(live, plan, target, matched)
        if (VoiceMeter.RESONANCE in meters) ResonanceCard(live.resonance, plan.targetResonance, live.listening)
        if (VoiceMeter.WEIGHT in meters) WeightCard(live.weight, live.listening)
        if (VoiceMeter.INTONATION in meters) IntonationCard(live.intonation, live.listening)
        if (VoiceMeter.LOUDNESS in meters || live.pushing) LoudnessCard(live.db, live.pushing, speakingDb)
    }
}

/** Pitch now, in Hz and as a note, and the last ten seconds against the range (and a note to match). */
@Composable
fun PitchCard(live: VoiceLive, plan: VoicePlan, target: Double? = null, matched: Float? = null) {
    val hz = live.pitchHz
    val words = when {
        hz == null -> if (live.listening) "Hum or speak when you're ready" else "Not listening"
        target != null -> matchWords(hz, target)
        else -> VoiceWords.pitch(hz, plan.targetLowHz, plan.targetHighHz) ?: "Listening"
    }
    InfoCard("Pitch", icon = Icons.AutoMirrored.Rounded.ShowChart) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hz?.roundToInt()?.toString() ?: "—", style = MaterialTheme.typography.displaySmall)
                Text(" Hz", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            hz?.let { Text(VoiceScale.note(it), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp)) }
            Text(words, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        PitchTrace(live.trace, plan, target)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            LegendItem(MaterialTheme.colorScheme.primary, "Your pitch")
            VoicePlans.range(plan)?.let { LegendItem(AppTheme.data.light, "Your range, ${plan.targetLowHz!!.roundToInt()}–${plan.targetHighHz!!.roundToInt()} Hz") }
            target?.let { LegendItem(MaterialTheme.colorScheme.tertiary, "The note, ${VoiceScale.hzAndNote(it)}") }
        }
        if (matched != null && target != null) {
            Spacer(Modifier.height(8.dp))
            com.health.app.ui.components.ProgressBar(matched, color = MaterialTheme.colorScheme.tertiary, height = 10.dp)
            Hint(if (matched >= 1f) "Matched" else "Hold it there a moment", Modifier.padding(top = 4.dp))
        }
    }
}

private fun matchWords(hz: Double, target: Double): String {
    val off = VoiceScale.semitones(hz, target)
    return when {
        kotlin.math.abs(off) <= 1 -> "On the note"
        off < 0 -> "A little low: slide up"
        else -> "A little high: slide down"
    }
}

/** The pitch trace: semitones up the side (so steps look even), with the range shaded and its edges named. */
@Composable
fun PitchTrace(trace: List<PitchPoint>, plan: VoicePlan, target: Double? = null, windowMs: Long = 10_000) {
    val line = MaterialTheme.colorScheme.primary
    val bandColor = AppTheme.data.light.copy(alpha = 0.35f)
    val targetColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val pill = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
    val measurer = rememberTextMeasurer()
    val low = plan.targetLowHz
    val high = plan.targetHighHz
    val centre = VoicePlans.centre(plan)
    val values = trace.mapNotNull { it.hz }
    // The scale in semitones from the centre: the range, the note and what's been heard, with room.
    val marks = listOfNotNull(low, high, target) + values
    val lowest = (marks.minOfOrNull { VoiceScale.semitones(it, centre) } ?: -5.0).coerceAtMost(-5.0) - 2
    val highest = (marks.maxOfOrNull { VoiceScale.semitones(it, centre) } ?: 5.0).coerceAtLeast(5.0) + 2
    val description = "Your pitch over the last ten seconds" +
        (VoicePlans.range(plan)?.let { ", against your range of $it" } ?: "") +
        (target?.let { ", with the note to match at ${VoiceScale.hzAndNote(it)}" } ?: "")
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        fun y(hz: Double): Float {
            val st = VoiceScale.semitones(hz, centre)
            return (size.height - ((st - lowest) / (highest - lowest)).toFloat() * size.height).coerceIn(0f, size.height)
        }
        if (low != null && high != null) {
            val top = y(high)
            drawRect(bandColor, Offset(0f, top), Size(size.width, y(low) - top))
        }
        target?.let { t ->
            val top = y(VoiceScale.shift(t, 1.0))
            drawRect(targetColor.copy(alpha = 0.18f), Offset(0f, top), Size(size.width, y(VoiceScale.shift(t, -1.0)) - top))
            drawLine(targetColor, Offset(0f, y(t)), Offset(size.width, y(t)), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (trace.size >= 2) {
            val end = trace.last().at
            fun x(at: Long) = size.width * (1f - (end - at).toFloat() / windowMs)
            var path: Path? = null
            val stroke = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            trace.forEach { p ->
                val hz = p.hz
                if (hz == null) {
                    path?.let { drawPath(it, line, style = stroke) }
                    path = null
                } else {
                    val point = Offset(x(p.at), y(hz))
                    path = (path ?: Path().apply { moveTo(point.x, point.y) }).apply { lineTo(point.x, point.y) }
                }
            }
            path?.let { drawPath(it, line, style = stroke) }
            trace.last().hz?.let { drawCircle(line, 6.dp.toPx(), Offset(size.width, y(it))) }
        }
        // The range's edges, named at the left (the newest reading is at the right), over the trace on a soft pill.
        if (low != null && high != null) {
            val pad = 4.dp.toPx()
            val labels = listOf(high, low).map { measurer.measure("${it.roundToInt()} Hz · ${VoiceScale.note(it)}", labelStyle) }
            val h = labels[0].size.height.toFloat()
            val inside = y(low) - y(high) > 2.6f * (h + pad)
            listOf(high, low).forEachIndexed { i, edge ->
                val upper = i == 0
                val ly = when {
                    inside && upper -> y(edge) + pad
                    inside -> y(edge) - h - pad
                    upper -> y(edge) - h - pad
                    else -> y(edge) + pad
                }.coerceIn(2f, size.height - h - 2f)
                val label = labels[i]
                drawRoundRect(pill, Offset(pad, ly - 2f), Size(label.size.width + 2 * pad, h + 4f), CornerRadius(6.dp.toPx()))
                drawText(label, topLeft = Offset(2 * pad, ly))
            }
        }
    }
}

/** Resonance, darker ↔ brighter, with the person's target marked. */
@Composable
fun ResonanceCard(value: Double?, target: Double?, listening: Boolean) {
    val words = when {
        value == null -> if (listening) "Hold a vowel or hum to measure" else "Not listening"
        else -> VoiceWords.against(value, target, "Darker than your target", "Brighter than your target")?.replaceFirstChar { it.uppercase() }
            ?: VoiceWords.resonance(value).replaceFirstChar { it.uppercase() }
    }
    ScaleCard("Resonance", Icons.Rounded.WbTwilight, value, target, "Darker", "Brighter", words, MaterialTheme.colorScheme.primary)
}

/** Vocal weight, lighter ↔ heavier. */
@Composable
fun WeightCard(value: Double?, listening: Boolean) {
    val words = when {
        value == null -> if (listening) "Speak or hum to measure" else "Not listening"
        else -> VoiceWords.weight(value).replaceFirstChar { it.uppercase() }
    }
    ScaleCard("Vocal weight", Icons.Rounded.Tune, value, null, "Lighter", "Heavier", words, MaterialTheme.colorScheme.tertiary)
}

/** A 0–100 scale with a marker for now and a tick for the target. */
@Composable
fun ScaleCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: Double?, target: Double?, left: String, right: String, words: String, color: Color) {
    val still = reduceMotion()
    val shown by animateFloatAsState(((value ?: 50.0) / 100).toFloat(), if (still) tween(0) else tween(180), label = title)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.onSurface
    InfoCard(title, icon = icon) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), itemVerticalAlignment = Alignment.Bottom) {
            Text(value?.roundToInt()?.toString() ?: "—", style = MaterialTheme.typography.displaySmall)
            Text(words, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).semantics {
                contentDescription = "$title ${value?.roundToInt() ?: "not measured"} on a scale from $left (0) to $right (100)" +
                    (target?.let { ", your target ${it.roundToInt()}" } ?: "")
            },
        ) {
            val barH = 12.dp.toPx()
            val top = (size.height - barH) / 2
            drawRoundRect(track, Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
            target?.let { t ->
                val x = (t / 100).toFloat() * size.width
                drawLine(tick, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), cap = StrokeCap.Round)
            }
            if (value != null) {
                val x = shown.coerceIn(0f, 1f) * size.width
                drawRoundRect(color.copy(alpha = 0.35f), Offset(0f, top), Size(max(x, barH), barH), CornerRadius(barH / 2))
                drawCircle(color, 11.dp.toPx(), Offset(x.coerceIn(11.dp.toPx(), size.width - 11.dp.toPx()), size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(left, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (target != null) Text("Target ${target.roundToInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(right, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/** Intonation: how much the last few seconds' pitch moved, in semitones and in words. */
@Composable
fun IntonationCard(spread: Double?, listening: Boolean) {
    InfoCard("Intonation", icon = Icons.Rounded.GraphicEq) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), itemVerticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(spread?.let { "%.1f".format(java.util.Locale.US, it) } ?: "—", style = MaterialTheme.typography.displaySmall)
                Text(" semitones", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                spread?.let { VoiceWords.intonation(it).replaceFirstChar { c -> c.uppercase() } } ?: if (listening) "Keep talking to measure" else "Not listening",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Hint("How much your pitch moves as you speak, over the last few seconds. More movement often sounds more expressive; there's no right amount.")
    }
}

/** Loudness, with a gentle word when the person has been pushing for a while. */
@Composable
fun LoudnessCard(db: Double?, pushing: Boolean, speakingDb: Double?) {
    val still = reduceMotion()
    val lowest = -70.0
    val highest = -10.0
    val fraction = db?.let { ((it - lowest) / (highest - lowest)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val shown by animateFloatAsState(fraction, if (still) tween(0) else tween(120), label = "loudness")
    val limit = ((Loudness.limit(speakingDb) - lowest) / (highest - lowest)).toFloat()
    val calm = AppTheme.data.water
    val warn = AppTheme.status.warn
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    InfoCard("Loudness", icon = Icons.AutoMirrored.Rounded.VolumeUp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), itemVerticalAlignment = Alignment.Bottom) {
            Text(Loudness.words(db, speakingDb), style = MaterialTheme.typography.headlineSmall)
            db?.let { Text("${it.roundToInt()} dBFS", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Loudness: ${Loudness.words(db, speakingDb)}" }) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val w = shown * size.width
            if (w > 0f) drawRoundRect(if (shown > limit) warn else calm, size = Size(max(w, size.height), size.height), cornerRadius = r)
            val x = min(limit, 1f) * size.width
            drawLine(warn, Offset(x, -2f), Offset(x, size.height + 2f), 2.dp.toPx())
        }
        if (pushing) {
            Text(
                "You've been quite loud for a while. Easing off a little is kinder to your voice: a comfortable level works just as well.",
                style = MaterialTheme.typography.bodyMedium, color = warn,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
