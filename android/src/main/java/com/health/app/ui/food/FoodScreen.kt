@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.food

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
import com.health.api.FoodDay
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.InfoCard
import com.health.api.FoodEntry
import com.health.api.FoodEntryChange
import com.health.api.Meal
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.GoalBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Ring
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.arranged
import com.health.app.ui.components.showsField
import com.health.api.Fields
import com.health.api.Layouts
import androidx.compose.runtime.key
import com.health.app.ui.log.NumberField
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.tracking.DrinksCard
import com.health.api.Drink
import com.health.api.DrinkKind
import java.time.LocalDate
import java.time.ZoneId

class FoodActions(
    val go: (LocalDate) -> Unit = {},
    val retry: () -> Unit = {},
    val add: (LocalDate, Meal) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val change: (String, FoodEntryChange) -> Unit = { _, _ -> },
    val addWater: (Int) -> Unit = {},
    val deleteWater: (String) -> Unit = {},
    val copyYesterday: (Meal?) -> Unit = {},
    val quickDrink: (DrinkKind) -> Unit = {},
    val saveDrink: (Drink) -> Unit = {},
    val deleteDrink: (String) -> Unit = {},
    /** Ideas: one logged with a tap, or ideas for another meal. */
    val addIdea: (FoodSuggestion) -> Unit = {},
    val ideasFor: (Meal) -> Unit = {},
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
            quickDrink = { vm.quickDrink(it) }, saveDrink = { vm.saveDrink(it) }, deleteDrink = { vm.deleteDrink(it) },
            addIdea = vm::addIdea, ideasFor = vm::ideasFor,
        ),
        zone = c.zone(),
        water = showsField(Fields.NUTRITION_WATER),
        numbers = com.health.app.ui.components.foodNumbers(),
        micros = showsMicros(),
    )
}

/** Whether vitamins and minerals show: their field (on by default), and never in eating disorder recovery. */
@Composable
fun showsMicros(): Boolean = showsField(Fields.NUTRITION_MICROS) && com.health.app.ui.components.foodNumbers()

/** The Food tab's sections, arranged under `android.food`: their ids are saved, so they stay as they are. */
object FoodSections {
    val KEY = Layouts.screen(Layouts.ANDROID, "food")
    const val IDEAS = "ideas"
    const val TOTALS = "totals"
    const val WATER = "water"
    const val DRINKS = "drinks"
    const val MICROS = "micros"

    /** A meal's section: "breakfast", "lunch", "dinner", "snack". */
    fun meal(meal: Meal): String = meal.name.lowercase()

    /**
     * The sections, in the default order; ideas only for today, water only while it's shown, the
     * totals only while amounts of food show ([numbers]: not in eating disorder recovery), and
     * vitamins and minerals while they show ([micros]).
     */
    fun items(water: Boolean, ideas: Boolean = true, numbers: Boolean = true, micros: Boolean = false): List<ArrangeItem> = buildList {
        if (ideas) add(ArrangeItem(IDEAS, "Ideas"))
        if (numbers) add(ArrangeItem(TOTALS, "Calories and nutrients"))
        if (numbers && micros) add(ArrangeItem(MICROS, "Vitamins & minerals"))
        Meal.entries.forEach { add(ArrangeItem(meal(it), it.label)) }
        if (water) add(ArrangeItem(WATER, "Water"))
        add(ArrangeItem(DRINKS, "Drinks"))
    }
}

/**
 * The day's food: totals, each meal, water (unless it's hidden) and drinks, in the person's order.
 * Without [numbers] (eating disorder recovery), no calories, macros or targets anywhere: no
 * totals, meals by what's in them, water without its goal.
 */
@Composable
fun FoodScreen(
    state: FoodState, units: Units, actions: FoodActions, zone: ZoneId = ZoneId.systemDefault(), water: Boolean = true, numbers: Boolean = true,
    micros: Boolean = false,
) {
    var editing by remember { mutableStateOf<FoodEntry?>(null) }
    var arranging by remember { mutableStateOf(false) }
    val day = state.day
    val items = FoodSections.items(water, ideas = state.date == state.today, numbers = numbers, micros = micros)
    val shown = arranged(FoodSections.KEY, items)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Food", Format.day(state.date, state.today)) {
            IconButton(onClick = { actions.go(state.date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Day before") }
            IconButton(onClick = { actions.go(state.date.plusDays(1)) }, enabled = state.date < state.today) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Day after")
            }
            ArrangeButton { arranging = true }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && day == null -> Loading()
                state.error != null && day == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                day != null -> shown.forEach { id ->
                    key(id) {
                        val meal = Meal.entries.firstOrNull { FoodSections.meal(it) == id }
                        when {
                            id == FoodSections.IDEAS -> IdeasCard(state.ideas, state.ideasMeal, state.ideasError, actions, numbers)
                            id == FoodSections.TOTALS -> if (numbers) Totals(day)
                            id == FoodSections.MICROS -> if (numbers && micros) MicrosCard(day, state.date == state.today)
                            meal != null -> MealCard(
                                meal, day.entries.filter { it.meal == meal }, numbers,
                                onAdd = { actions.add(state.date, meal) }, onEdit = { editing = it }, onCopy = { actions.copyYesterday(meal) },
                            )
                            id == FoodSections.WATER -> WaterCard(day, units, actions, numbers)
                            id == FoodSections.DRINKS -> DrinksCard(state.drinks, units, zone, actions.quickDrink, actions.saveDrink, actions.deleteDrink, water = water)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    if (arranging) ArrangeSheet(FoodSections.KEY, "Food", items, { arranging = false })
    editing?.let { entry ->
        EditEntryDialog(
            entry, numbers,
            onDismiss = { editing = null },
            onSave = { change -> entry.id?.let { actions.change(it, change) }; editing = null },
            onDelete = { entry.id?.let(actions.delete); editing = null },
        )
    }
}

/**
 * "Ideas for dinner": foods and meals eaten before, best first, each added with one tap (not while
 * viewing a share); the menu asks for another meal. Without numbers (eating disorder recovery), no amounts.
 */
@Composable
private fun IdeasCard(ideas: FoodSuggestions?, meal: Meal?, error: String?, actions: FoodActions, numbers: Boolean = true) {
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    val shown = ideas?.meal ?: meal
    val word = shown?.let { com.health.app.ui.today.InsightLinks.mealWord(it) } ?: "now"
    InfoCard(
        "Ideas for $word", icon = Icons.Rounded.Lightbulb,
        action = CardAction(Icons.Rounded.MoreVert, "Ideas for another meal", menu = Meal.entries.map { m -> "Ideas for ${com.health.app.ui.today.InsightLinks.mealWord(m)}" to { actions.ideasFor(m) } }),
    ) {
        when {
            ideas == null && error != null -> Hint(error)
            ideas == null -> Hint("Finding ideas…")
            ideas.items.isEmpty() -> Hint("Ideas show up here once you've logged a few meals.")
            else -> ideas.items.forEachIndexed { i, idea ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(idea.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // Amounts stay together on a line ("38 g protein · 650 kcal").
                        val amounts = if (ideas.showNumbers && numbers) " ·${Format.number(idea.nutrients.protein)}\u00a0g\u00a0protein\u00a0·\u00a0${Format.number(idea.nutrients.calories)}\u00a0kcal" else ""
                        Text(idea.why + amounts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!readOnly) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { actions.addIdea(idea) }, contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
                            modifier = Modifier.semantics { contentDescription = "Add ${idea.name} to $word" },
                        ) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
            }
        }
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
private fun MealCard(meal: Meal, entries: List<FoodEntry>, numbers: Boolean, onAdd: () -> Unit, onEdit: (FoodEntry) -> Unit, onCopy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(meal.label, style = MaterialTheme.typography.titleMedium)
                val kcal = entries.sumOf { it.nutrients.calories }
                Text(
                    when {
                        entries.isEmpty() -> "Nothing yet"
                        !numbers -> if (entries.size == 1) "1 thing" else "${entries.size} things"
                        else -> "${Format.number(kcal)} kcal · ${Format.number(entries.sumOf { it.nutrients.protein })} g protein"
                    },
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
                    EntryRow(entry, numbers) { onEdit(entry) }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: FoodEntry, numbers: Boolean, onClick: () -> Unit) {
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
        if (numbers) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.number(entry.nutrients.calories), style = MaterialTheme.typography.titleSmall)
                Text("kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaterCard(day: FoodDay, units: Units, actions: FoodActions, numbers: Boolean) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WaterDrop, null, tint = AppTheme.data.water)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Water", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (numbers) "${Format.water(day.waterMl, units)} of ${Format.water(day.target.waterMl, units)}" else "${Format.water(day.waterMl, units)} so far today",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (numbers) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(day.waterMl.toFloat() / day.target.waterMl.coerceAtLeast(1), color = AppTheme.data.water, height = 10.dp)
        }
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
private fun EditEntryDialog(entry: FoodEntry, numbers: Boolean, onDismiss: () -> Unit, onSave: (FoodEntryChange) -> Unit, onDelete: () -> Unit) {
    var servings by remember { mutableStateOf(Format.compact(entry.servings)) }
    var meal by remember { mutableStateOf(entry.meal) }
    val value = Format.parse(servings)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(servings, { servings = it }, "Servings", supporting = entry.serving)
                value?.takeIf { it > 0 && numbers }?.let { v ->
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
