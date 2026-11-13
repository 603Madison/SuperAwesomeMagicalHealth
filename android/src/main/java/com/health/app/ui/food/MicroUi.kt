package com.health.app.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.FoodDay
import com.health.api.MicroAim
import com.health.api.Nutrients
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.log.NumberField

/*
 * Vitamins and minerals on the Food tab (MicroText does the sums and the words): the day's
 * section, what a food carries, and "More nutrients" when adding one. Only while they show
 * (Fields.NUTRITION_MICROS, and never in eating disorder recovery).
 */

/**
 * The day's vitamins, minerals, fibre and the finer fats against their targets: the main few, or
 * every one by group. A nutrient no food carried says "No data", never 0; a line says how many
 * of the day's foods came with any.
 */
@Composable
fun MicrosCard(day: FoodDay, isToday: Boolean, startExpanded: Boolean = false) {
    var all by rememberSaveable { mutableStateOf(startExpanded) }
    val rows = MicroText.rows(day.totals, day.target, all)
    InfoCard(
        "Vitamins & minerals", icon = Icons.Rounded.Eco,
        trailing = {
            TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show all") }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(MicroText.coverage(day.entries, isToday) ?: "Nothing logged yet. Foods found by search or barcode often bring their vitamins and minerals.")
            if (all) {
                NutrientGroup.entries.forEach { group ->
                    val inGroup = rows.filter { it.info.group == group }
                    if (inGroup.isNotEmpty()) {
                        Text(group.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        inGroup.forEach { MicroRowView(it) }
                    }
                }
            } else {
                rows.forEach { MicroRowView(it) }
            }
            day.target.microBasis?.let { Hint("Targets: ${it.replaceFirstChar { c -> c.lowercase() }}. Change them in Settings › Goals.") }
        }
    }
}

@Composable
private fun MicroRowView(row: MicroRow) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(row.info.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                row.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                color = if (row.state == MicroRow.State.NONE) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        // A quiet bar, in calm colours either way, limits included.
        val color = if (row.info.aim == MicroAim.AT_MOST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        ProgressBar(row.fraction ?: 0f, color = color, height = 6.dp)
        row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** What a food carries in a line ("Fibre 2.7 g · Iron 4.2 mg · and 3 more"), for search results; nothing without any. */
@Composable
fun CarriedLine(nutrients: Nutrients, modifier: Modifier = Modifier) {
    MicroText.carried(nutrients, 3)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = modifier)
    }
}

/** Everything a food's amount carries, for its details: tap to see the list; a line when there's none. */
@Composable
fun CarriedList(nutrients: Nutrients) {
    val known = MicroText.ALL.mapNotNull { info -> MicroText.amount(nutrients, info)?.let { info to it } }
    if (known.isEmpty()) {
        Hint("No vitamins or minerals came with this food.")
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Expander("Vitamins & minerals (${known.size})", open) { open = !open }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                known.forEach { (info, v) ->
                    Row {
                        Text(info.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(MicroText.withUnit(v, info.unit), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * "More nutrients": fibre, sugar, the fats, minerals and vitamins of a serving, each optional (a
 * blank stays unknown, never 0). [values] are as typed, by nutrient key.
 */
@Composable
fun MoreNutrients(values: Map<String, String>, onChange: (Map<String, String>) -> Unit, startOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val filled = values.values.count { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Expander("More nutrients" + if (filled > 0) " ($filled)" else "", open) { open = !open }
        if (open) {
            Hint("All optional: fill in what the label says. Leave the rest blank.")
            NutrientGroup.entries.forEach { group ->
                Text(group.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                MicroText.inputRows(MicroText.ALL.filter { it.group == group }).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { info ->
                            // The unit is in the label, so it shows before anything's typed.
                            NumberField(values[info.key].orEmpty(), { onChange(values + (info.key to it)) }, MicroText.inputLabel(info), Modifier.weight(1f))
                        }
                        // A short one left on its own keeps the width of the others.
                        if (pair.size == 1 && !MicroText.isLong(pair[0])) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Expander(label: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp).semantics { contentDescription = "$label, ${if (open) "shown" else "hidden"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
