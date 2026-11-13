package com.health.app.ui.food

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CardItem
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.Pill
import com.health.app.ui.components.SearchField
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.log.NumberField
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.LocalDate

class AddFoodActions(
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (Food?) -> Unit = {},
    val scan: () -> Unit = {},
    val add: (Food, Double) -> Unit = { _, _ -> },
    val quickAdd: (String, Nutrients) -> Unit = { _, _ -> },
    val create: (Food, Double) -> Unit = { _, _ -> },
    val dismissUnknown: () -> Unit = {},
    val snap: () -> Unit = {},
)

@Composable
fun AddFoodRoute(date: LocalDate, meal: Meal, onDone: () -> Unit) {
    val c = appContainer()
    val vm = viewModel(key = "add-$date-$meal") { AddFoodViewModel(c, date, meal) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::barcode) }
    var snapping by rememberSaveable { mutableStateOf(false) }
    if (snapping && !LocalReadOnly.current) {
        FoodPhotoRoute(date, meal, onBack = { snapping = false }, onDone = onDone)
        return
    }
    AddFoodScreen(
        meal, if (LocalReadOnly.current) state.copy(photoOffer = null) else state,
        AddFoodActions(
            back = onDone,
            query = vm::query,
            pick = vm::pick,
            scan = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A, ScanOptions.UPC_E)
                        .setPrompt("Point at the barcode on the packet")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            add = vm::add,
            quickAdd = vm::quickAdd,
            create = vm::create,
            dismissUnknown = vm::dismissUnknown,
            snap = { snapping = true },
        ),
    )
}

@Composable
fun AddFoodScreen(meal: Meal, state: AddFoodState, actions: AddFoodActions) {
    var quick by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar(actions.back, "Add to ${meal.label.lowercase()}")
        SearchField(state.query, actions.query, "Search foods", busy = state.searching, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.photoOffer == PhotoOffer.READY) {
                FilledTonalButton(onClick = actions.snap, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Snap a photo")
                }
            }
            FilledTonalButton(onClick = actions.scan, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan barcode")
            }
            FilledTonalButton(onClick = { quick = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Quick add")
            }
            FilledTonalButton(onClick = { creating = "" }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New food")
            }
        }
        if (state.photoOffer == PhotoOffer.SETTINGS) Hint(FoodPhotoText.SETTINGS_LINE, Modifier.padding(horizontal = 20.dp))
        state.error?.let { ErrorCard(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        val results = state.results
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (results == null) {
                if (state.recent.isEmpty()) {
                    item {
                        EmptyState(Icons.Rounded.Restaurant, "Find a food", "Search by name, scan a packet's barcode, or quick-add calories. Foods you log show up here next time.")
                    }
                } else {
                    item { SectionTitle("Your foods") }
                    items(state.recent.size) { i -> CardItem(i, state.recent.size) { FoodRow(state.recent[i]) { actions.pick(state.recent[i]) } } }
                }
            } else {
                if (results.mine.isNotEmpty()) {
                    item { SectionTitle("Your foods") }
                    items(results.mine.size) { i -> CardItem(i, results.mine.size) { FoodRow(results.mine[i]) { actions.pick(results.mine[i]) } } }
                }
                item { SectionTitle("Open Food Facts") }
                when {
                    results.onlineError != null -> item { Hint(results.onlineError.orEmpty(), Modifier.padding(horizontal = 20.dp)) }
                    results.online.isEmpty() -> item { Hint("Nothing found. Try fewer words, or make it a new food.", Modifier.padding(horizontal = 20.dp)) }
                    else -> items(results.online.size) { i -> CardItem(i, results.online.size) { FoodRow(results.online[i]) { actions.pick(results.online[i]) } } }
                }
            }
        }
    }

    state.picked?.let { food -> ServingsDialog(food, onDismiss = { actions.pick(null) }) { actions.add(food, it) } }
    state.unknownBarcode?.let { code ->
        AlertDialog(
            onDismissRequest = actions.dismissUnknown,
            title = { Text("Not known yet") },
            text = { Text("Nobody has added barcode $code to Open Food Facts. Add it as a food of your own? You'll find it by name or barcode next time.") },
            confirmButton = { TextButton(onClick = { creating = code; actions.dismissUnknown() }) { Text("Add it") } },
            dismissButton = { TextButton(onClick = actions.dismissUnknown) { Text("Not now") } },
        )
    }
    if (quick) QuickAddDialog(onDismiss = { quick = false }) { name, n -> actions.quickAdd(name, n); quick = false }
    creating?.let { barcode -> NewFoodDialog(barcode.ifBlank { null }, onDismiss = { creating = null }) { food, servings -> actions.create(food, servings); creating = null } }
}

@Composable
fun FoodRow(food: Food, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(food.brand, food.serving).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.number(food.nutrients.calories), style = MaterialTheme.typography.titleSmall)
            Text("kcal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How much of [food]: a stepper in quarter servings, with what that amount holds. */
@Composable
fun ServingsDialog(food: Food, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
    var servings by remember { mutableDoubleStateOf(1.0) }
    var text by remember { mutableStateOf("1") }
    fun set(v: Double) {
        servings = v.coerceIn(0.25, 50.0)
        text = Format.compact(servings)
    }
    val n = food.nutrients * servings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOfNotNull(food.brand, if (food.source == FoodSource.OPEN_FOOD_FACTS) "Open Food Facts" else null).takeIf { it.isNotEmpty() }?.let {
                    Hint(it.joinToString(" · "))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { set(servings - 0.25) }) { Icon(Icons.Rounded.Remove, "Less") }
                    OutlinedTextField(
                        text, { t -> text = t; Format.parse(t)?.takeIf { it > 0 }?.let { servings = it } },
                        singleLine = true,
                        label = { Text("Servings") },
                        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    FilledTonalIconButton(onClick = { set(servings + 0.25) }) { Icon(Icons.Rounded.Add, "More") }
                }
                Hint("1 serving = ${food.serving}${food.servingGrams?.let { " (${Format.compact(it)} g)" } ?: ""}")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Nutrient(Format.number(n.calories), "kcal")
                    Nutrient("${Format.number(n.protein)} g", "protein")
                    Nutrient("${Format.number(n.carbs)} g", "carbs")
                    Nutrient("${Format.number(n.fat)} g", "fat")
                }
            }
        },
        confirmButton = { TextButton(enabled = servings > 0, onClick = { onAdd(servings) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Nutrient(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Just the numbers: calories, and macros if you know them. */
@Composable
fun QuickAddDialog(onDismiss: () -> Unit, onAdd: (String, Nutrients) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val calories = Format.parse(kcal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick add") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("What was it? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumberField(kcal, { kcal = it }, "Calories", suffix = "kcal", decimals = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(protein, { protein = it }, "Protein", Modifier.weight(1f), suffix = "g")
                    NumberField(carbs, { carbs = it }, "Carbs", Modifier.weight(1f), suffix = "g")
                    NumberField(fat, { fat = it }, "Fat", Modifier.weight(1f), suffix = "g")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = calories != null && calories >= 0, onClick = {
                onAdd(name.trim(), Nutrients(calories!!, Format.parse(protein) ?: 0.0, Format.parse(carbs) ?: 0.0, Format.parse(fat) ?: 0.0))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A food of the person's own, kept for next time (with its [barcode], if it came from a scan). */
@Composable
fun NewFoodDialog(barcode: String?, onDismiss: () -> Unit, onSave: (Food, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var serving by remember { mutableStateOf("1 serving") }
    var grams by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val calories = Format.parse(kcal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New food") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                barcode?.let { Pill("Barcode $it", MaterialTheme.colorScheme.primary) }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(brand, { brand = it }, label = { Text("Brand (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(serving, { serving = it }, label = { Text("A serving is") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    NumberField(grams, { grams = it }, "Weighs", Modifier.weight(1f), suffix = "g")
                }
                Spacer(Modifier.height(2.dp))
                Hint("In one serving:")
                NumberField(kcal, { kcal = it }, "Calories", suffix = "kcal", decimals = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(protein, { protein = it }, "Protein", Modifier.weight(1f), suffix = "g")
                    NumberField(carbs, { carbs = it }, "Carbs", Modifier.weight(1f), suffix = "g")
                    NumberField(fat, { fat = it }, "Fat", Modifier.weight(1f), suffix = "g")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && calories != null, onClick = {
                onSave(
                    Food(
                        name = name.trim(),
                        brand = brand.trim().ifBlank { null },
                        serving = serving.trim().ifBlank { "1 serving" },
                        servingGrams = Format.parse(grams),
                        barcode = barcode,
                        nutrients = Nutrients(calories!!, Format.parse(protein) ?: 0.0, Format.parse(carbs) ?: 0.0, Format.parse(fat) ?: 0.0),
                    ),
                    1.0,
                )
            }) { Text("Save and add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
