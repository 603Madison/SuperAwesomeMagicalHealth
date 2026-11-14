@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ProductStats
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyCategory
import com.health.api.SupplyKind
import com.health.api.SupplyReason
import com.health.api.SupplyStatus
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addSupply
import com.health.app.net.continenceProducts
import com.health.app.net.adjustSupply
import com.health.app.net.deleteSupply
import com.health.app.net.supplies
import com.health.app.net.supplyLog
import com.health.app.net.supplyStatus
import com.health.app.net.updateSupply
import com.health.app.ui.Format
import com.health.app.ui.care.CareFormat
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.EmptyLine
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.sectionId
import com.health.app.ui.log.NumberField
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Where supplies live: bladder and bowel products (and wipes, creams) with the diary; period products with the cycle. */
enum class SupplyArea(val route: String, val title: String, val categories: Set<SupplyCategory>) {
    CONTINENCE("continence", "Supplies", setOf(SupplyCategory.CONTINENCE, SupplyCategory.OTHER)),
    CYCLE("cycle", "Period products", setOf(SupplyCategory.PERIOD)),
    ;

    /** The kinds offered when adding one here. */
    val kinds: List<SupplyKind> get() = SupplyKind.entries.filter { it.category in categories }

    companion object {
        fun of(route: String?): SupplyArea = entries.firstOrNull { it.route == route } ?: CONTINENCE

        /** Where to look after low supplies: period products alone go to the cycle. */
        fun forLow(low: List<SupplyStatus>): SupplyArea =
            if (low.isNotEmpty() && low.all { it.supply.kind.category == SupplyCategory.PERIOD }) CYCLE else CONTINENCE
    }
}

/** How a change is put, plainly. */
fun reasonLabel(r: SupplyReason): String = when (r) {
    SupplyReason.RESTOCKED -> "Bought"
    SupplyReason.USED -> "Used"
    SupplyReason.GAVE_AWAY -> "Gave away"
    SupplyReason.COUNTED -> "Counted"
}

data class SuppliesState(
    val statuses: List<SupplyStatus> = emptyList(),
    /** Ones put away, to bring back. */
    val putAway: List<Supply> = emptyList(),
    val history: Map<String, List<SupplyAdjustment>> = emptyMap(),
    /** How each bladder and bowel product goes (from the diary's changes). */
    val products: List<ProductStats> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class SuppliesViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(SuppliesState())
    val state: StateFlow<SuppliesState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.statuses.isEmpty() && it.loading, error = null) }
        viewModelScope.launch {
            try {
                val statuses = async { c.ui.supplyStatus() }
                val all = async { attempt { c.ui.supplies(all = true) }.getOrDefault(emptyList()) }
                val products = async { attempt { c.ui.continenceProducts(90) }.getOrNull() }
                _state.update {
                    it.copy(statuses = statuses.await(), putAway = all.await().filter { s -> !s.active }, products = products.await() ?: it.products, loading = false)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your supplies") }
            }
        }
    }

    fun history(id: String) = viewModelScope.launch {
        attempt { c.ui.supplyLog(id, 365) }.onSuccess { list -> _state.update { it.copy(history = it.history + (id to list)) } }
    }

    private fun act(done: String, block: suspend () -> Unit) = viewModelScope.launch {
        attempt { block() }.onSuccess { c.tell(done); c.dataChanged() }.onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun save(supply: Supply) = act("Saved") { if (supply.id == null) c.ui.addSupply(supply) else c.ui.updateSupply(supply) }

    fun delete(id: String) = act("Deleted") { c.ui.deleteSupply(id) }

    fun adjust(id: String, adjustment: SupplyAdjustment) = act("Updated") {
        c.ui.adjustSupply(id, adjustment)
        history(id)
    }
}

class SuppliesActions(
    val save: (Supply) -> Unit = {},
    val delete: (String) -> Unit = {},
    val adjust: (String, SupplyAdjustment) -> Unit = { _, _ -> },
    val history: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun SuppliesRoute(area: SupplyArea, onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { SuppliesViewModel(c) }
    val state by vm.state.collectAsState()
    SuppliesScreen(area, state, c.zone(), onBack, SuppliesActions(vm::save, { vm.delete(it) }, { id, a -> vm.adjust(id, a) }, { vm.history(it) }, { vm.load() }))
}

/** The supplies kept in [area], one section each (the sections of [FeatureScreens.SUPPLIES], shared by both areas). */
fun supplySections(statuses: List<SupplyStatus>, area: SupplyArea): List<ArrangeItem> =
    statuses.filter { it.supply.kind.category in area.categories }.mapNotNull { s ->
        s.supply.id?.let { ArrangeItem(sectionId("supply", it), ContinenceText.supplyName(s.supply)) }
    }

/**
 * What's on hand: each supply with how many are left, how fast they go and how long they'll
 * last, marked when low; adding, changing, restocking or counting them; and each one's history.
 * The person puts them in their own order, and can hide ones they rarely look at.
 */
@Composable
fun SuppliesScreen(area: SupplyArea, state: SuppliesState, zone: ZoneId, onBack: () -> Unit, actions: SuppliesActions, openId: String? = null) {
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    var editing by remember { mutableStateOf<Supply?>(null) }
    var open by remember { mutableStateOf(openId) }
    var adjusting by remember { mutableStateOf<Pair<Supply, SupplyReason>?>(null) }
    val all = state.statuses.filter { it.supply.kind.category in area.categories }
    val sections = supplySections(state.statuses, area)
    val order = arrangedIn(FeatureScreens.SUPPLIES, sections)
    // In the person's order; any not saved yet (no id) at the end.
    val mine = order.mapNotNull { id -> all.firstOrNull { it.supply.id != null && sectionId("supply", it.supply.id!!) == id } } + all.filter { it.supply.id == null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, area.title) {
            if (!readOnly) IconButton(onClick = { editing = Supply("", area.kinds.first()) }) { Icon(Icons.Rounded.Add, "Add a supply") }
            ArrangeAction(FeatureScreens.SUPPLIES, area.title, sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && state.statuses.isEmpty() -> Loading()
                state.error != null && state.statuses.isEmpty() -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                all.isEmpty() -> EmptyLine(
                    if (area == SupplyArea.CYCLE) "None yet. Each product you log comes off its count." else "None yet. What you put on in the diary comes off its count.",
                    action = "Add".takeIf { !readOnly }, onAction = { editing = Supply("", area.kinds.first()) },
                )
                mine.isEmpty() -> EmptyLine("All of these are hidden. Show them again from Arrange.")
                else -> {
                    if (area == SupplyArea.CONTINENCE) ProductsCard(state.products)
                    InfoCard(null) {
                        mine.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            SupplyRow(s) { open = s.supply.id; s.supply.id?.let(actions.history) }
                        }
                    }
                    Hint("Used a day is from the last two weeks of your diary. Low means about a week left, or your own mark.", Modifier.padding(horizontal = 4.dp))
                }
            }
            val away = state.putAway.filter { it.kind.category in area.categories }
            if (away.isNotEmpty() && !readOnly) {
                InfoCard("Put away") {
                    away.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ContinenceText.supplyName(s), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { actions.save(s.copy(active = true)) }) { Text("Bring back") }
                        }
                    }
                }
            }
        }
    }
    val opened = all.firstOrNull { it.supply.id == open }
    if (opened != null) {
        ModalBottomSheet(onDismissRequest = { open = null }) {
            SupplyDetail(
                opened, state.history[opened.supply.id].orEmpty(), zone, readOnly,
                onAdjust = { reason -> adjusting = opened.supply to reason },
                onEdit = { editing = opened.supply; open = null },
            )
        }
    }
    editing?.let { s ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            SupplyEditor(
                s, area,
                onSave = { actions.save(it); editing = null },
                onPutAway = s.id?.let { { actions.save(s.copy(active = false)); editing = null } },
                onDelete = s.id?.let { id -> { actions.delete(id); editing = null } },
            )
        }
    }
    adjusting?.let { (supply, reason) ->
        AdjustDialog(supply, reason, onDismiss = { adjusting = null }) { a -> actions.adjust(supply.id!!, a); adjusting = null }
    }
}

@Composable
private fun SupplyRow(s: SupplyStatus, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ContinenceText.supplyName(s.supply), style = MaterialTheme.typography.bodyLarge)
            Hint(listOfNotNull(s.supply.kind.label, s.supply.brand, s.supply.size).joinToString(" · "))
            Text(ContinenceText.stock(s), style = MaterialTheme.typography.bodyMedium)
        }
        if (s.low) Pill("Low", AppTheme.status.warn, icon = Icons.Rounded.WarningAmber)
    }
}

@Composable
fun SupplyDetail(s: SupplyStatus, history: List<SupplyAdjustment>, zone: ZoneId, readOnly: Boolean, onAdjust: (SupplyReason) -> Unit, onEdit: () -> Unit) {
    val supply = s.supply
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ContinenceText.supplyName(supply), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (s.low) Pill("Low", AppTheme.status.warn, icon = Icons.Rounded.WarningAmber)
        }
        Text(ContinenceText.stock(s), style = MaterialTheme.typography.bodyLarge)
        Hint(
            listOfNotNull(
                supply.kind.label,
                supply.dryWeightG?.let { "${ContinenceText.fmt(it)} g dry" },
                supply.perPack?.let { "$it in a pack" },
                supply.lowAt?.let { "low at $it" },
                s.lastRestocked?.let { "last bought ${CareFormat.date(it)}" },
            ).joinToString(" · "),
        )
        supply.notes?.let { Hint(it) }
        if (!readOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onAdjust(SupplyReason.RESTOCKED) }) { Text("Bought more") }
                OutlinedButton(onClick = { onAdjust(SupplyReason.COUNTED) }) { Text("Count them") }
                OutlinedButton(onClick = { onAdjust(SupplyReason.USED) }) { Text("Used some") }
                OutlinedButton(onClick = { onAdjust(SupplyReason.GAVE_AWAY) }) { Text("Gave away") }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
        Text("History", style = MaterialTheme.typography.titleSmall)
        if (history.isEmpty()) Hint("Nothing in the last year yet. Changes in the diary and here show up as they happen.")
        history.take(60).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(reasonLabel(a.reason), style = MaterialTheme.typography.bodyMedium)
                    Hint(CareFormat.dayTime(a.at, zone) + (a.note?.let { " · $it" } ?: ""))
                }
                Text(
                    if (a.delta > 0) "+${a.delta}" else if (a.delta < 0) "−${-a.delta}" else "±0",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (a.delta < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Adding or changing a supply: what it is, how absorbent, its dry weight, how many, and when it counts as low. */
@Composable
fun SupplyEditor(initial: Supply, area: SupplyArea, onSave: (Supply) -> Unit, onPutAway: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var s by remember { mutableStateOf(initial) }
    var onHand by remember { mutableStateOf(if (initial.id == null && initial.onHand == 0) "" else initial.onHand.toString()) }
    var perPack by remember { mutableStateOf(initial.perPack?.toString().orEmpty()) }
    var lowAt by remember { mutableStateOf(initial.lowAt?.toString().orEmpty()) }
    var dry by remember { mutableStateOf(initial.dryWeightG?.let { Format.upTo2(it) }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (initial.id == null) "Add a supply" else "Change ${initial.name}", style = MaterialTheme.typography.titleLarge)
        Text("What it is", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            area.kinds.forEach { k ->
                FilterChip(
                    selected = s.kind == k,
                    onClick = { s = s.copy(kind = k, reusable = if (k == SupplyKind.CUP || k == SupplyKind.REUSABLE_UNDERWEAR || k == SupplyKind.PERIOD_UNDERWEAR) true else s.reusable) },
                    label = { Text(k.label) },
                )
            }
        }
        OutlinedTextField(s.name, { s = s.copy(name = it.take(80)) }, label = { Text("Name") }, placeholder = { Text("e.g. Night pull-ups") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(s.brand.orEmpty(), { s = s.copy(brand = it.take(80).ifBlank { null }) }, label = { Text("Brand") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(s.size.orEmpty(), { s = s.copy(size = it.take(40).ifBlank { null }) }, label = { Text("Size") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            s.absorbency.orEmpty(), { s = s.copy(absorbency = it.take(60).ifBlank { null }) },
            label = { Text("Absorbency") }, supportingText = { Text("As the pack says: \"Maxi\", \"6 drops\", \"overnight\"") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        LabeledSwitch("Reusable", s.reusable, { s = s.copy(reusable = it) }, subtitle = "Washed and worn again, so not counted down")
        if (!s.reusable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(onHand, { onHand = it }, "On hand", Modifier.weight(1f), decimals = false)
                NumberField(perPack, { perPack = it }, "In a pack", Modifier.weight(1f), decimals = false)
            }
            NumberField(lowAt, { lowAt = it }, "Low when this few are left", decimals = false, supporting = "Leave empty for about a week's worth")
        }
        if (area == SupplyArea.CONTINENCE) {
            NumberField(dry, { dry = it }, "Dry weight", suffix = "g", supporting = "Weigh one unused: with a used one's weight, the diary works out how much was lost")
        }
        OutlinedTextField(s.notes.orEmpty(), { s = s.copy(notes = it.take(2000).ifBlank { null }) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        Button(
            enabled = s.name.isNotBlank(),
            onClick = {
                onSave(
                    s.copy(
                        name = s.name.trim(),
                        onHand = onHand.trim().toIntOrNull()?.coerceIn(0, 100_000) ?: if (s.reusable) s.onHand else 0,
                        perPack = perPack.trim().toIntOrNull()?.takeIf { it in 1..10_000 },
                        lowAt = lowAt.trim().toIntOrNull()?.takeIf { it in 0..10_000 },
                        dryWeightG = Format.parse(dry)?.takeIf { it > 0 && it <= 2000 },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (onPutAway != null) OutlinedButton(onClick = onPutAway, modifier = Modifier.fillMaxWidth()) { Text("Put away (stop using it)") }
        if (onDelete != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Delete") }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("Its history goes too. Diary entries that used it stay.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Bought, used, gave away or counted, with how many and a note. */
@Composable
fun AdjustDialog(supply: Supply, initialReason: SupplyReason, onDismiss: () -> Unit, onSave: (SupplyAdjustment) -> Unit) {
    var reason by remember { mutableStateOf(initialReason) }
    var amount by remember { mutableStateOf(if (initialReason == SupplyReason.RESTOCKED) supply.perPack?.toString().orEmpty() else "") }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(reason) { if (reason == SupplyReason.COUNTED && amount.isEmpty()) amount = supply.onHand.toString() }
    val n = amount.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(supply.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SupplyReason.RESTOCKED, SupplyReason.USED, SupplyReason.GAVE_AWAY, SupplyReason.COUNTED).forEach { r ->
                        FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(reasonLabel(r)) })
                    }
                }
                NumberField(
                    amount, { amount = it }, if (reason == SupplyReason.COUNTED) "How many there are now" else "How many", decimals = false,
                    supporting = when (reason) {
                        SupplyReason.RESTOCKED -> supply.perPack?.let { "A pack holds $it" } ?: "${supply.onHand} on hand now"
                        SupplyReason.COUNTED -> "Sets the count; ${supply.onHand} on hand now"
                        else -> "${supply.onHand} on hand now"
                    },
                )
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = n != null && n >= 0 && (n > 0 || reason == SupplyReason.COUNTED), onClick = {
                onSave(SupplyAdjustment(System.currentTimeMillis(), if (reason == SupplyReason.RESTOCKED || reason == SupplyReason.COUNTED) n!! else -n!!, reason, note.trim().ifBlank { null }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Supplies running low, for Today and the diary: each named, with what's left, and the way to restock. */
@Composable
fun LowSuppliesCard(low: List<SupplyStatus>, onOpen: () -> Unit) {
    InfoCard(
        "Running low", icon = Icons.Rounded.Inventory2, iconTint = AppTheme.status.warn, onClick = onOpen,
        trailing = { TextButton(onClick = onOpen) { Text("Supplies") } },
    ) {
        low.take(4).forEach { s ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ContinenceText.supplyName(s.supply), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "${s.supply.onHand} left" + (s.daysLeft?.let { " · about $it ${if (it == 1) "day" else "days"}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = AppTheme.status.warn,
                )
            }
        }
        if (low.size > 4) Hint("And ${low.size - 4} more.")
    }
}
