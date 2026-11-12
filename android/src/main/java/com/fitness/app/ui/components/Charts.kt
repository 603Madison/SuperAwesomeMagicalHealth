package com.fitness.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitness.api.SleepStage
import com.fitness.api.StageSpan
import com.fitness.app.ui.theme.AppTheme

/**
 * An Oura-style score ring: the theme's two colours sweeping round as far as [fraction] (0–1),
 * with [center] inside. A null fraction draws only the track.
 */
@Composable
fun Ring(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
    colors: List<Color> = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    center: @Composable () -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val arcSize = Size(this.size.width - w, this.size.height - w)
            val topLeft = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            val f = fraction?.coerceIn(0f, 1f) ?: return@Canvas
            if (f <= 0f) return@Canvas
            rotate(-90f) {
                // The gradient's colours sit along the sweep, ending where the arc ends; past the
                // end it turns back to the first colour, so the round cap at the start (which
                // reaches just behind 0°) is drawn in the start's colour, not the end's.
                val stops = if (colors.size == 1) arrayOf(0f to colors[0], 1f to colors[0])
                else arrayOf(0f to colors.first(), (f * 0.97f).coerceIn(0.01f, 0.97f) to colors.last(), 0.985f to colors.last(), 1f to colors.first())
                drawArc(Brush.sweepGradient(*stops), 0f, 360f * f, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        center()
    }
}

/** A score ring with its number inside and its name and verdict under it. */
@Composable
fun ScoreRing(label: String, score: Int?, verdict: String?, modifier: Modifier = Modifier, size: Dp = 92.dp) {
    Column(
        modifier.semantics { contentDescription = "$label ${score ?: "not measured"}${verdict?.let { ", $it" } ?: ""}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ring(score?.let { it / 100f }, size = size, stroke = 9.dp) {
            Text(score?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(verdict ?: "No data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A horizontal bar filled to [fraction] (0–1, capped), in [color]. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 8.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** A labelled progress bar: "Protein · 82 of 120 g". */
@Composable
fun GoalBar(label: String, value: Double, goal: Double?, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (goal != null && goal > 0) "${com.fitness.app.ui.Format.number(value)} / ${com.fitness.app.ui.Format.number(goal)} $unit" else "${com.fitness.app.ui.Format.number(value)} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(if (goal != null && goal > 0) (value / goal).toFloat() else 0f, color = color)
    }
}

/**
 * A small line chart, filled underneath; nulls are gaps. [min] and [max] fix the scale; otherwise
 * it follows the data with a little room.
 */
@Composable
fun LineChart(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 96.dp,
    min: Double? = null,
    max: Double? = null,
    goal: Double? = null,
    dots: Boolean = values.count { it != null } <= 14,
) {
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val present = values.filterNotNull() + listOfNotNull(goal)
        if (present.isEmpty()) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            return@Canvas
        }
        var lo = min ?: present.min()
        var hi = max ?: present.max()
        if (hi - lo < 1e-6) {
            lo -= 1
            hi += 1
        }
        val pad = if (min == null && max == null) (hi - lo) * 0.15 else 0.0
        lo -= pad
        hi += pad
        val n = values.size
        val step = if (n > 1) size.width / (n - 1) else size.width
        fun x(i: Int) = if (n > 1) i * step else size.width / 2
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).toFloat() * size.height).coerceIn(0f, size.height)

        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        goal?.let {
            drawLine(goalColor, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        // Runs of consecutive values, each drawn as its own line and fill.
        val runs = ArrayList<List<Int>>()
        var run = ArrayList<Int>()
        values.forEachIndexed { i, v ->
            if (v == null) {
                if (run.isNotEmpty()) runs += run
                run = ArrayList()
            } else run += i
        }
        if (run.isNotEmpty()) runs += run
        for (r in runs) {
            if (r.size >= 2) {
                val line = Path()
                r.forEachIndexed { k, i -> if (k == 0) line.moveTo(x(i), y(values[i]!!)) else line.lineTo(x(i), y(values[i]!!)) }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(r.last()), size.height)
                    lineTo(x(r.first()), size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.02f))))
                drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (dots || r.size == 1) r.forEach { i -> drawCircle(color, 3.5.dp.toPx(), Offset(x(i), y(values[i]!!))) }
        }
    }
}

/** A bar per value (nulls are gaps), with an optional dashed goal line; bars at or over the goal are full colour. */
@Composable
fun BarChart(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 96.dp,
    goal: Double? = null,
    highlightLast: Boolean = false,
) {
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val top = (values.filterNotNull() + listOfNotNull(goal)).maxOrNull()?.takeIf { it > 0 }?.times(1.12) ?: 1.0
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v == null || v <= 0) return@forEachIndexed
            val h = (v / top).toFloat() * size.height
            val reached = goal == null || v >= goal
            val alpha = when {
                highlightLast && i == values.lastIndex -> 1f
                reached -> 0.9f
                else -> 0.45f
            }
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 3, barWidth / 3),
            )
        }
        goal?.let {
            val y = size.height - (it / top).toFloat() * size.height
            drawLine(goalColor, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
    }
}

/** Labels under a chart: the first and last, and one in the middle. */
@Composable
fun AxisLabels(first: String, middle: String?, last: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (middle != null) Text(middle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(last, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
fun stageColor(stage: SleepStage): Color = when (stage) {
    SleepStage.DEEP -> AppTheme.data.deep
    SleepStage.REM -> AppTheme.data.rem
    SleepStage.LIGHT, SleepStage.ASLEEP -> AppTheme.data.light
    SleepStage.AWAKE -> AppTheme.data.awake
}

/** Where a stage sits in the hypnogram, top (awake) to bottom (deep). */
private fun level(stage: SleepStage) = when (stage) {
    SleepStage.AWAKE -> 0
    SleepStage.REM -> 1
    SleepStage.LIGHT, SleepStage.ASLEEP -> 2
    SleepStage.DEEP -> 3
}

/** The night as a hypnogram: each stage a rounded block on its own level, in its colour. */
@Composable
fun Hypnogram(stages: List<StageSpan>, start: Long, end: Long, modifier: Modifier = Modifier, height: Dp = 84.dp) {
    val colors = SleepStage.entries.associateWith { stageColor(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = (end - start).coerceAtLeast(1).toFloat()
        val rowH = size.height / 4
        for (r in 1..3) drawLine(grid.copy(alpha = 0.5f), Offset(0f, r * rowH), Offset(size.width, r * rowH), 0.5.dp.toPx())
        stages.forEach { s ->
            val x0 = ((s.start - start) / total) * size.width
            val x1 = ((s.end - start) / total) * size.width
            val l = level(s.stage)
            drawRoundRect(
                colors.getValue(s.stage),
                topLeft = Offset(x0, l * rowH + rowH * 0.12f),
                size = Size((x1 - x0).coerceAtLeast(1.5f), rowH * 0.76f),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

/** A coloured key: "● Deep 1h 20m". */
@Composable
fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier, value: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 9.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value != null) {
            Spacer(Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One segmented bar showing parts of a whole (the sleep stages, the macros). */
@Composable
fun StackedBar(parts: List<Pair<Color, Double>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = parts.sumOf { it.second }
        val r = CornerRadius(size.height / 2, size.height / 2)
        if (total <= 0) {
            drawRoundRect(track, cornerRadius = r)
            return@Canvas
        }
        val gap = 2.dp.toPx()
        var x = 0f
        val visible = parts.filter { it.second > 0 }
        visible.forEachIndexed { i, (color, value) ->
            val w = (value / total).toFloat() * size.width - if (i < visible.lastIndex) gap else 0f
            drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(w.coerceAtLeast(1f), size.height), cornerRadius = r)
            x += w + gap
        }
    }
}

/** Steps hour by hour: 24 small bars. */
@Composable
fun HourlyBars(values: List<Int>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier) {
        BarChart(values.map { it.toDouble() }, color = color, height = 52.dp)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12a", "6a", "12p", "6p", "").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
