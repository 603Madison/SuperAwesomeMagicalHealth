package com.health.app.ui.modules.continence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.api.Fullness
import com.health.app.ui.theme.AppTheme

/**
 * How full a pad, brief or other product was, 0 (dry) to 5 (leaked through), as a row of little
 * containers filling up: tap one to choose it, tap it again to clear. Each says its level in
 * words, so nothing hangs on the colour.
 */
@Composable
fun FullnessGauge(value: Fullness?, onChange: (Fullness?) -> Unit, modifier: Modifier = Modifier, title: String = "How full it was") {
    val fill = AppTheme.data.water
    val over = AppTheme.status.warn
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                value?.let { "${it.label} · ${it.level} of 5" } ?: "Not noted",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Fullness.entries.forEach { f ->
                val selected = value == f
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.RadioButton) { onChange(if (selected) null else f) }
                        .heightIn(min = 48.dp).padding(vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${f.label}, ${f.level} of 5" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cup(f.level, active = value != null && f.level <= value.level, selected = selected, fill = if (f == Fullness.LEAKED) over else fill)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        f.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2,
                        fontWeight = if (selected) FontWeight.Bold else null,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/** One container, filled to [level] of 5; "leaked through" spills over its top. */
@Composable
private fun Cup(level: Int, active: Boolean, selected: Boolean, fill: Color, width: Dp = 26.dp, height: Dp = 40.dp) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.size(width, height)) {
        val top = 6.dp.toPx()
        val body = Size(size.width, size.height - top)
        val r = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        drawRoundRect(track, Offset(0f, top), body, r)
        val fraction = (level.coerceAtMost(4) / 4f)
        if (fraction > 0f) {
            val h = body.height * fraction
            drawRoundRect(fill.copy(alpha = if (active) 0.85f else 0.28f), Offset(0f, top + body.height - h), Size(body.width, h), r)
        }
        if (level >= 5) {
            // Drops over the rim.
            val drop = Path().apply {
                val cx = size.width * 0.72f
                moveTo(cx, 0f)
                quadraticTo(cx + 4.dp.toPx(), top * 0.9f, cx, top + 1.dp.toPx())
                quadraticTo(cx - 4.dp.toPx(), top * 0.9f, cx, 0f)
            }
            drawPath(drop, fill.copy(alpha = if (active) 0.9f else 0.35f))
        }
        drawRoundRect(outline, Offset(0f, top), body, r, style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
    }
}

/**
 * A small read-only gauge for lists: five steps, filled to the level, with its name ([label])
 * beside. [large] is the Wearing now card's: wider, with the word in the body's size.
 */
@Composable
fun FullnessMeter(value: Fullness, modifier: Modifier = Modifier, label: String = value.label, large: Boolean = false) {
    val fill = if (value == Fullness.LEAKED) AppTheme.status.warn else AppTheme.data.water
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "$label, ${value.level} of 5" }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = if (large) 80.dp else 44.dp, height = if (large) 12.dp else 8.dp)) {
            val gap = (if (large) 3 else 2).dp.toPx()
            val w = (size.width - gap * 4) / 5
            val r = (if (large) 3 else 2).dp.toPx()
            for (i in 0 until 5) {
                drawRoundRect(if (i < value.level) fill else track, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(r, r))
            }
        }
        Spacer(Modifier.width(if (large) 10.dp else 6.dp))
        Text(
            label,
            style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
