@file:OptIn(ExperimentalMaterial3Api::class)

package com.fitness.app.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fitness.api.FoodDay
import com.fitness.api.FoodEntry
import com.fitness.api.FoodEntryChange
import com.fitness.api.Meal
import com.fitness.api.Units
import com.fitness.app.ui.Format
import com.fitness.app.ui.components.AppCard
import com.fitness.app.ui.components.ErrorCard
import com.fitness.app.ui.components.GoalBar
import com.fitness.app.ui.components.Hint
import com.fitness.app.ui.components.Loading
import com.fitness.app.ui.components.ProgressBar
import com.fitness.app.ui.components.Ring
import com.fitness.app.ui.components.ScreenTitle

import com.fitness.app.ui.components.appContainer
import com.fitness.app.ui.log.NumberField
import com.fitness.app.ui.theme.AppTheme
import java.time.LocalDate

class FoodActions(
    val go: (LocalDate) -> Unit = {},
    val retry: () -> Unit = {},
    val add: (LocalDate, Meal) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val change: (String, FoodEntryChange) -> Unit = { _, _ -> },
    val addWater: (Int) -> Unit = {},
    val deleteWater: (String) -> Unit = {},
    val copyYesterday: (Meal?) -> Unit = {},
)

@Composable
fun FoodRoute(onAdd: (LocalDate, Meal) -> Unit) {
    val c = appContainer()
    val vm = viewModel { FoodViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    FoodScreen(
        state, settings.units,
        FoodActions(
            go = vm::go, retry = { vm.load() }, add = onAdd, delete = vm::delete, change = vm::change,
            addWater = vm::addWater, deleteWater = vm::deleteWater, copyYesterday = vm::copyYesterday,
        ),
    )
}

@Composable
fun FoodScreen(state: FoodState, units: Units, actions: FoodActions) {
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    val day = state.day
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Food", Format.day(state.date, state.today)) {
            IconButton(onClick = { actions.go(state.date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before") }
            IconButton(onClick = { actions.go(state.date.plusDays(1)) }, enabled = state.date < state.today) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after")
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && day == null -> Loading()
                state.error != null && day == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                day != null -> {
                    Totals(day)
                    Meal.entries.forEach { meal ->
                        MealCard(meal, day.entries.filter { it.meal == meal }, onAdd = { actions.add(state.date, meal) }, onEdit = { editing = it }, onCopy = { actions.copyYesterday(meal) })
                    }
                    WaterCard(day, units, actions)
                }
            }
        }
    }
    editing?.let { entry ->
        EditEntryDialog(
            entry,
            onDismiss = { editing = null },
            onSave = { change -> entry.id?.let { actions.change(it, change) }; editing = null },
            onDelete = { entry.id?.let(actions.delete); editing = null },
        )
    }
}

@Composable
private fun Totals(day: FoodDay) {
    val t = day.target
    val eaten = day.totals
    AppCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring((eaten.calories / t.calories.coerceAtLeast(1)).toFloat(), size = 120.dp, stroke = 11.dp, colors = listOf(AppTheme.data.carbs, AppTheme.data.protein)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val left = t.calories - eaten.calories
                    Text(Format.number(kotlin.math.abs(left)), style = MaterialTheme.typography.headlineSmall)
                    Text(if (left >= 0) "kcal left" else "kcal over", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Format.number(eaten.calories)} of ${Format.number(t.calories)} kcal", style = MaterialTheme.typography.titleSmall)
                GoalBar("Protein", eaten.protein, t.protein.toDouble(), "g", AppTheme.data.protein)
                GoalBar("Carbs", eaten.carbs, t.carbs.toDouble(), "g", AppTheme.data.carbs)
                GoalBar("Fat", eaten.fat, t.fat.toDouble(), "g", AppTheme.data.fat)
            }
        }
        Spacer(Modifier.height(10.dp))
        val burned = day.burned?.let { " · ${Format.number(it)} kcal burned so far today" }?.takeUnless { t.basis.contains("burned so far") } ?: ""
        Hint("${t.basis}$burned")
    }
}

@Composable
private fun MealCard(meal: Meal, entries: List<FoodEntry>, onAdd: () -> Unit, onEdit: (FoodEntry) -> Unit, onCopy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(meal.label, style = MaterialTheme.typography.titleMedium)
                val kcal = entries.sumOf { it.nutrients.calories }
                Text(
                    if (entries.isEmpty()) "Nothing yet" else "${Format.number(kcal)} kcal · ${Format.number(entries.sumOf { it.nutrients.protein })} g protein",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "Add to ${meal.label.lowercase()}", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy from the day before") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { menu = false; onCopy() },
                )
            }
        }
        if (entries.isNotEmpty()) {
            Column(Modifier.padding(bottom = 8.dp)) {
                entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    EntryRow(entry) { onEdit(entry) }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: FoodEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val amount = if (entry.servings == 1.0) entry.serving else "${Format.compact(entry.servings)} × ${entry.serving}"
            Text(
                listOfNotNull(entry.brand, amount).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.number(entry.nutrients.calories), style = MaterialTheme.typography.titleSmall)
            Text("kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WaterCard(day: FoodDay, units: Units, actions: FoodActions) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WaterDrop, null, tint = AppTheme.data.water)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Water", style = MaterialTheme.typography.titleMedium)
                Text("${Format.water(day.waterMl, units)} of ${Format.water(day.target.waterMl, units)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(day.waterMl.toFloat() / day.target.waterMl.coerceAtLeast(1), color = AppTheme.data.water, height = 10.dp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.addWater(250) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+250 ml") }
            FilledTonalButton(onClick = { actions.addWater(500) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+500 ml") }
            day.water.lastOrNull()?.id?.let { last ->
                OutlinedButton(onClick = { actions.deleteWater(last) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun EditEntryDialog(entry: FoodEntry, onDismiss: () -> Unit, onSave: (FoodEntryChange) -> Unit, onDelete: () -> Unit) {
    var servings by remember { mutableStateOf(Format.compact(entry.servings)) }
    var meal by remember { mutableStateOf(entry.meal) }
    val value = Format.parse(servings)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(servings, { servings = it }, "Servings", supporting = entry.serving)
                value?.takeIf { it > 0 }?.let { v ->
                    val n = entry.nutrients * (v / entry.servings.coerceAtLeast(0.01))
                    Hint("${Format.number(n.calories)} kcal · ${Format.number(n.protein)} g protein · ${Format.number(n.carbs)} g carbs · ${Format.number(n.fat)} g fat")
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Meal.entries.forEach { m ->
                        androidx.compose.material3.FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null && value > 0, onClick = {
                onSave(FoodEntryChange(meal = meal.takeIf { it != entry.meal }, servings = value?.takeIf { it != entry.servings }))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(28.dp)),
    )
}
