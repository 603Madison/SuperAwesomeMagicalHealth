package com.health.app.ui.trends

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.MicroAim
import com.health.api.NutritionTarget
import com.health.api.TrendDay
import com.health.app.ui.components.BarChart
import com.health.app.ui.food.MicroText
import com.health.app.ui.food.NutrientGroup

/**
 * A chosen nutrient's daily intake against its target (or limit): a day nothing carried it is a
 * gap, never a zero, and only foods that came with it count.
 */
@Composable
internal fun NutrientTrendCard(days: List<TrendDay>, target: NutritionTarget?, labels: Triple<String, String?, String>, initial: String = "FIBER") {
    var key by rememberSaveable { mutableStateOf(initial) }
    val info = MicroText.info(key)
    val values = days.map { MicroText.amount(it, info) }
    val known = values.filterNotNull()
    val goal = MicroText.target(target, info)
    val color = if (info.aim == MicroAim.AT_MOST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val line = when {
        goal == null -> "No target for this one."
        info.aim == MicroAim.AT_MOST -> "The line is your limit: ${MicroText.withUnit(goal, info.unit)} a day."
        else -> "The line is your target: ${MicroText.withUnit(goal, info.unit)} a day."
    }
    ChartCard(
        "Vitamins & minerals", Icons.Rounded.Eco, color,
        known.takeIf { it.isNotEmpty() }?.average()?.let { MicroText.withUnit(it, info.unit) }, "a day it came with food", labels,
        footer = "$line ${info.label} came with food on ${known.size} of ${days.size} days; only the foods that carried it count.",
    ) {
        NutrientPicker(key) { key = it }
        Spacer(Modifier.height(10.dp))
        BarChart(values, color = color, goal = goal)
    }
}

/** A chip naming the nutrient, with every one in a menu by group. */
@Composable
private fun NutrientPicker(key: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true, onClick = { open = true },
            label = { Text(MicroText.info(key).label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, "Choose a nutrient", Modifier.size(18.dp)) },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            NutrientGroup.entries.forEachIndexed { i, group ->
                if (i > 0) HorizontalDivider()
                Text(group.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
                MicroText.ALL.filter { it.group == group }.forEach { n ->
                    DropdownMenuItem(text = { Text(n.label) }, onClick = { open = false; onPick(n.key) })
                }
            }
        }
    }
}
