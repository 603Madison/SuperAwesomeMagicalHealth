package com.health.app.ui.food

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Food
import com.health.api.Layouts
import com.health.api.ShoppingAdd
import com.health.api.ShoppingItem
import com.health.api.ShoppingList
import com.health.app.Container
import com.health.app.net.addShopping
import com.health.app.net.clearBought
import com.health.app.net.removeShopping
import com.health.app.net.shopping
import com.health.app.net.tickShopping
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.SearchField
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.arranged
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Shopping (from Food): look a product up by name or by scanning its barcode, and see its grade
 * for the person's goals, why, and what a serving holds; put two side by side; and keep a
 * shopping list. The person's own: never shared.
 */

/** Shopping's sections, arranged under `android.shopping`: their ids are saved, so they stay as they are. */
object ShoppingSections {
    val KEY = Layouts.screen(Layouts.ANDROID, "shopping")
    const val FIND = "find"
    const val COMPARE = "compare"
    const val LIST = "list"

    val items = listOf(ArrangeItem(FIND, "Look up a product"), ArrangeItem(COMPARE, "Compare two"), ArrangeItem(LIST, "Shopping list"))
}

/** Shopping worked out without Compose (for tests): key nutrients, a side-by-side compare, the list's order. */
object ShoppingText {
    /** What's in one serving: calories and the macros, then what grades weigh most; "—" where it isn't known. */
    fun keyNutrients(food: Food): List<Pair<String, String>> {
        val n = food.nutrients
        fun g(v: Double?) = v?.let { "${Format.compact(Math.round(it * 10) / 10.0)} g" } ?: "—"
        return listOf(
            "Calories" to "${Format.number(n.calories)} kcal",
            "Protein" to g(n.protein),
            "Carbs" to g(n.carbs),
            "Sugars" to g(n.sugar),
            "Fat" to g(n.fat),
            "Saturated fat" to g(n.saturatedFat),
            "Fibre" to g(n.fiber),
            "Sodium" to (n.sodiumMg?.let { "${Format.number(it)} mg" } ?: "—"),
        )
    }

    /** Two products side by side: (label, first, second). Without [numbers], only their grades and servings. */
    fun compare(a: Food, b: Food, numbers: Boolean = true): List<Triple<String, String, String>> {
        val lines = listOf(Triple("Grade", a.grade?.letter ?: "—", b.grade?.letter ?: "—"), Triple("Serving", a.serving, b.serving))
        if (!numbers) return lines
        return lines + keyNutrients(a).zip(keyNutrients(b)).map { (l, r) -> Triple(l.first, l.second, r.second) }
    }

    /** Still to buy first (in the order added), then what's bought. */
    fun order(items: List<ShoppingItem>): List<ShoppingItem> = items.filterNot { it.checked } + items.filter { it.checked }

    /** "Clear 2 bought", or null when nothing is. */
    fun clearLabel(items: List<ShoppingItem>): String? = items.count { it.checked }.takeIf { it > 0 }?.let { "Clear $it bought" }

    /** Where a product goes to be compared: the first empty side, else the second. */
    fun compareWith(pair: List<Food?>, food: Food): List<Food?> = when {
        pair.getOrNull(0) == null -> listOf(food, pair.getOrNull(1))
        else -> listOf(pair[0], food)
    }
}

data class ShoppingState(
    val query: String = "",
    val results: List<Food>? = null,
    val searching: Boolean = false,
    val product: Food? = null,
    val compare: List<Food?> = listOf(null, null),
    val list: ShoppingList? = null,
    val error: String? = null,
    /** A scanned barcode nobody knows yet. */
    val unknown: String? = null,
)

class ShoppingActions(
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val scan: () -> Unit = {},
    val open: (Food?) -> Unit = {},
    val addToList: (Food) -> Unit = {},
    val compare: (Food) -> Unit = {},
    val uncompare: (Int) -> Unit = {},
    val addName: (String) -> Unit = {},
    val tick: (ShoppingItem) -> Unit = {},
    val remove: (ShoppingItem) -> Unit = {},
    val clearBought: () -> Unit = {},
    val retry: () -> Unit = {},
)

class ShoppingViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(ShoppingState())
    val state: StateFlow<ShoppingState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init { load() }

    fun load() = change(null) { c.api.shopping() }

    /** Runs a change to the list, and shows the list the server sends back. */
    private fun change(done: String?, work: suspend () -> ShoppingList) {
        viewModelScope.launch {
            try {
                val list = work()
                _state.update { it.copy(list = list, error = null) }
                done?.let(c::tell)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't load the shopping list") }
            }
        }
    }

    fun query(text: String) {
        _state.update { it.copy(query = text, product = null) }
        searchJob?.cancel()
        if (text.trim().length < 2) {
            _state.update { it.copy(results = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(searching = true) }
            try {
                val found = c.api.searchFoods(text.trim())
                _state.update { if (it.query == text) it.copy(results = found.mine + found.online, searching = false) else it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't search") }
            }
        }
    }

    fun barcode(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            try {
                val food = c.api.barcode(code)
                _state.update { it.copy(searching = false, product = food, unknown = if (food == null) code else null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't look that up") }
            }
        }
    }

    fun open(food: Food?) = _state.update { it.copy(product = food, unknown = null) }

    fun addToList(food: Food) = change("Added ${food.name} to your list") { c.api.addShopping(ShoppingAdd(food = food)) }

    fun addName(name: String) = change(null) { c.api.addShopping(ShoppingAdd(name = name)) }

    fun tick(item: ShoppingItem) = change(null) { c.api.tickShopping(item.id, !item.checked) }

    fun remove(item: ShoppingItem) = change(null) { c.api.removeShopping(item.id) }

    fun clearBought() = change(null) { c.api.clearBought() }

    fun compare(food: Food) {
        _state.update { it.copy(compare = ShoppingText.compareWith(it.compare, food)) }
        c.tell("${food.name} is ready to compare")
    }

    fun uncompare(index: Int) = _state.update { s -> s.copy(compare = s.compare.mapIndexed { i, f -> if (i == index) null else f }) }
}

@Composable
fun ShoppingRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { ShoppingViewModel(c) }
    val state by vm.state.collectAsState()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::barcode) }
    ShoppingScreen(
        state,
        ShoppingActions(
            back = onBack, query = vm::query, open = vm::open, addToList = vm::addToList, compare = vm::compare, uncompare = vm::uncompare,
            addName = vm::addName, tick = vm::tick, remove = vm::remove, clearBought = vm::clearBought, retry = { vm.load() },
            scan = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A, ScanOptions.UPC_E)
                        .setPrompt("Point at the barcode on the packet")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
        ),
        numbers = com.health.app.ui.components.foodNumbers(),
    )
}

/** Shopping: look up, compare and the list, in the person's order. Without [numbers] (eating disorder recovery), no amounts. */
@Composable
fun ShoppingScreen(state: ShoppingState, actions: ShoppingActions, numbers: Boolean = true) {
    var arranging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(actions.back, "Shopping", "See how a food fits your goals") { ArrangeButton { arranging = true } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
            arranged(ShoppingSections.KEY, ShoppingSections.items).forEach { id ->
                key(id) {
                    when (id) {
                        ShoppingSections.FIND -> FindCard(state, actions, numbers)
                        ShoppingSections.COMPARE -> CompareCard(state.compare, actions, numbers)
                        ShoppingSections.LIST -> ListCard(state.list, actions)
                    }
                }
            }
        }
    }
    if (arranging) ArrangeSheet(ShoppingSections.KEY, "Shopping", ShoppingSections.items, { arranging = false })
}

@Composable
private fun FindCard(state: ShoppingState, actions: ShoppingActions, numbers: Boolean) {
    InfoCard("Look up a product", icon = Icons.Rounded.Search) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(state.query, actions.query, "A food's name", busy = state.searching)
            FilledTonalButton(onClick = actions.scan) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan barcode")
            }
            state.unknown?.let { Hint(FoodSources.unknownBarcode(it)) }
            val product = state.product
            if (product != null) {
                Product(product, numbers, actions)
            } else {
                state.results?.let { results ->
                    if (results.isEmpty()) Hint("Nothing found. Try other words, or scan the barcode.")
                    results.take(12).forEach { food ->
                        Row(Modifier.fillMaxWidth().clickable { actions.open(food) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(food.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(food.brand, food.serving).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            GradeBadge(food.grade)
                        }
                    }
                }
            }
        }
    }
}

/** One product: its grade and why, what a serving holds (while amounts of food show), and what to do with it. */
@Composable
private fun Product(food: Food, numbers: Boolean, actions: ShoppingActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(food.name, style = MaterialTheme.typography.titleMedium)
                Hint(listOfNotNull(food.brand, "per ${food.serving}").joinToString(" · "))
            }
            IconButton(onClick = { actions.open(null) }) { Icon(Icons.Rounded.Close, "Close") }
        }
        GradeReasons(food.grade)
        if (numbers) {
            ShoppingText.keyNutrients(food).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (label, value) ->
                        Row(Modifier.weight(1f)) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.addToList(food) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to list") }
            TextButton(onClick = { actions.compare(food) }) { Text("Compare") }
        }
    }
}

/** Two products side by side. */
@Composable
private fun CompareCard(pair: List<Food?>, actions: ShoppingActions, numbers: Boolean) {
    val a = pair.getOrNull(0)
    val b = pair.getOrNull(1)
    InfoCard("Compare two", icon = Icons.Rounded.CompareArrows) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a == null && b == null) {
                Hint("Look up a product and choose Compare, then another.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(0.9f))
                listOf(a, b).forEachIndexed { i, f ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(f?.name ?: "Choose another", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = if (f != null) FontWeight.Bold else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (f != null) IconButton(onClick = { actions.uncompare(i) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Take ${f.name} out", Modifier.size(16.dp)) }
                    }
                }
            }
            if (a != null && b != null) {
                ShoppingText.compare(a, b, numbers).forEach { (label, left, right) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(a to left, b to right).forEach { (food, text) ->
                            Row(Modifier.weight(1f)) {
                                if (label == "Grade" && food.grade != null) GradeBadge(food.grade) else Text(text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The shopping list: add by name, tick off, clear what's bought. */
@Composable
private fun ListCard(list: ShoppingList?, actions: ShoppingActions) {
    var name by remember { mutableStateOf("") }
    val items = ShoppingText.order(list?.items.orEmpty())
    InfoCard("Shopping list", icon = Icons.Rounded.Checklist) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Add something") }, singleLine = true)
                IconButton(onClick = { name.trim().takeIf { it.isNotEmpty() }?.let { actions.addName(it); name = "" } }, enabled = name.isNotBlank()) {
                    Icon(Icons.Rounded.Add, "Add to the list")
                }
            }
            if (list != null && items.isEmpty()) Hint("Nothing on the list yet.", Modifier.padding(top = 8.dp))
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { actions.tick(item) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.checked, { actions.tick(item) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                            color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        item.brand?.let { Hint(it) }
                    }
                    GradeBadge(item.food?.grade)
                    RemoveFoodButton(item.name) { actions.remove(item) }
                }
            }
            ShoppingText.clearLabel(items)?.let { label -> TextButton(onClick = actions.clearBought) { Text(label) } }
        }
    }
}
