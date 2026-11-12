@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.Module
import com.health.app.ui.theme.AppTheme

/** Each module's icon, and a line saying what it's for. */
object ModuleInfo {
    fun icon(module: Module): ImageVector = when (module) {
        Module.ACTIVITY -> Icons.AutoMirrored.Rounded.DirectionsWalk
        Module.SLEEP -> Icons.Rounded.Bedtime
        Module.NUTRITION -> Icons.Rounded.Restaurant
        Module.BODY -> Icons.Rounded.MonitorWeight
        Module.MIND -> Icons.Rounded.Psychology
        Module.CYCLE -> Icons.Rounded.CalendarMonth
        Module.CONTINENCE -> Icons.Rounded.Wc
        Module.MEDICATIONS -> Icons.Rounded.Medication
        Module.VITALS, Module.HABITS, Module.SYMPTOMS, Module.HORMONES, Module.APPOINTMENTS -> Icons.Rounded.Medication
    }

    fun description(module: Module): String = when (module) {
        Module.ACTIVITY -> "Steps, workouts and your activity score."
        Module.SLEEP -> "Your nights, sleep stages and sleep score."
        Module.NUTRITION -> "Food, water and calorie targets."
        Module.BODY -> "Weight and body measurements."
        Module.MIND -> "Mood, a private journal, check-ins and calming tools."
        Module.CYCLE -> "Periods, symptoms and predictions."
        Module.CONTINENCE -> "A diary of toilet trips, leaks and pelvic floor exercises."
        Module.MEDICATIONS -> "Doses, reminders, injection sites and lab results."
        Module.VITALS -> "Blood pressure and glucose, from a monitor or by hand."
        Module.HABITS -> "Habits with streaks that forgive a rest day."
        Module.SYMPTOMS -> "Symptoms, pain and migraines, and what goes with them."
        Module.HORMONES -> "Dose history, levels against your targets, and when labs are due."
        Module.APPOINTMENTS -> "Appointments, questions to ask, and notes afterwards."
    }

    /** The route each opt-in module's screen lives at. */
    fun route(module: Module): String? = when (module) {
        Module.MIND -> "mind"
        Module.CYCLE -> "cycle"
        Module.CONTINENCE -> "continence"
        Module.MEDICATIONS -> "meds"
        else -> null
    }

    /** The opt-in modules, in the order they're shown. */
    val OPT_IN = listOf(Module.MIND, Module.CYCLE, Module.CONTINENCE, Module.MEDICATIONS)
}

/** Mood 1–5 as faces, from very low to great. */
object Moods {
    val icons = listOf(
        Icons.Rounded.SentimentVeryDissatisfied, Icons.Rounded.SentimentDissatisfied, Icons.Rounded.SentimentNeutral,
        Icons.Rounded.SentimentSatisfied, Icons.Rounded.SentimentVerySatisfied,
    )
    val labels = listOf("Very low", "Low", "Okay", "Good", "Great")

    fun icon(mood: Int): ImageVector = icons[(mood - 1).coerceIn(0, 4)]

    fun label(mood: Int): String = labels[(mood - 1).coerceIn(0, 4)]

    /** "4.2" as the nearest face. */
    fun nearest(mood: Double): Int = mood.let { kotlin.math.round(it).toInt() }.coerceIn(1, 5)

    @Composable
    fun color(mood: Int): Color = when (mood) {
        1 -> AppTheme.status.bad
        2 -> AppTheme.status.warn
        3 -> MaterialTheme.colorScheme.secondary
        4 -> MaterialTheme.colorScheme.primary
        else -> AppTheme.status.good
    }
}

/** Five faces to tap, 1 (very low) to 5 (great); tapping the chosen one again clears it. */
@Composable
fun FaceScale(value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 52.dp, labels: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        (1..5).forEach { n ->
            val selected = value == n
            val tint = Moods.color(n)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(size).clip(CircleShape).clickable { onChange(if (selected) null else n) }
                        .semantics { contentDescription = "Mood: ${Moods.label(n)}"; this.selected = selected },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Moods.icon(n), null, tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
                    }
                }
                if (labels) {
                    Spacer(Modifier.height(4.dp))
                    Text(Moods.label(n), style = MaterialTheme.typography.labelSmall, color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 1 to 5 as round steps, with words at each end; tapping the chosen one again clears it. */
@Composable
fun StepScale(label: String, value: Int?, low: String, high: String, onChange: (Int?) -> Unit, range: IntRange = 1..5) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value?.let { "$it of ${range.last}" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
            range.forEach { n ->
                val selected = value == n
                // A 40dp circle in a 48dp tap target.
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable { onChange(if (selected) null else n) }
                        .semantics { contentDescription = "$label $n of ${range.last}"; this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp),
                    ) { Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) } }
                }
            }
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        }
    }
}

/** Chips to pick any of, from [suggestions] plus the person's own (typed and added). */
@Composable
fun ChipPicker(
    suggestions: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    addLabel: String = "Your own",
) {
    var custom by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            (suggestions + selected.filter { s -> suggestions.none { it.equals(s, ignoreCase = true) } }).forEach { item ->
                val on = selected.any { it.equals(item, ignoreCase = true) }
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) selected.filterNot { it.equals(item, ignoreCase = true) }.toSet() else selected + item) },
                    label = { Text(item) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(custom, { custom = it.take(40) }, label = { Text(addLabel) }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(enabled = custom.isNotBlank(), onClick = { onChange(selected + custom.trim().lowercase()); custom = "" }) {
                Icon(Icons.Rounded.Add, "Add")
            }
        }
    }
}

/** A round tile with an icon, for the hub and the tools lists. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onPrimaryContainer, background: Color = MaterialTheme.colorScheme.primaryContainer, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Surface(shape = RoundedCornerShape(14.dp), color = background, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f)) }
    }
}

/** A tappable row: icon, title, subtitle, and whatever goes on the right. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}
