@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.conditions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ConditionDefinition
import com.health.api.ConditionKind
import com.health.api.ConditionStatus
import com.health.api.Fields
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.conditionCatalog
import com.health.app.net.conditions
import com.health.app.net.saveConditions
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.SearchField
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ModuleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConditionsState(
    val catalog: List<ConditionDefinition> = emptyList(),
    /** As saved on the server. */
    val saved: MyConditions = MyConditions(),
    /** As the person has it now, before saving. */
    val mine: MyConditions = MyConditions(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val changed: Boolean get() = saved != mine
}

class ConditionsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(ConditionsState())
    val state: StateFlow<ConditionsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Conditions are never shared, so these are always the person's own.
                val catalog = async { c.api.conditionCatalog() }
                val mine = async { c.api.conditions() }
                val saved = mine.await()
                _state.update { it.copy(catalog = catalog.await(), saved = saved, mine = saved, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your conditions") }
            }
        }
    }

    fun change(mine: MyConditions) = _state.update { it.copy(mine = mine) }

    fun save(onSaved: () -> Unit = {}) {
        val s = _state.value
        val before = c.modules()
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = c.api.saveConditions(s.mine)
                _state.update { it.copy(saved = saved, mine = saved, saving = false) }
                // Saving can turn features on, and brings their fields.
                c.loadProfile()
                c.refreshFields()
                attempt { c.refreshReminders() }
                c.dataChanged()
                val turnedOn = Module.entries.filter { it in c.modules() && it !in before }.map { it.label }
                c.tell(if (turnedOn.isEmpty()) "Saved" else "Saved. Turned on ${ConditionsLogic.sayList(turnedOn)}.")
                onSaved()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                c.tell(e.message ?: "Couldn't save that")
            }
        }
    }
}

class ConditionsActions(
    val change: (MyConditions) -> Unit = {},
    val save: () -> Unit = {},
    val retry: () -> Unit = {},
    val openFields: () -> Unit = {},
)

@Composable
fun ConditionsRoute(onBack: () -> Unit, onFields: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { ConditionsViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    ConditionsScreen(
        state, profile?.modules ?: c.modules(), onBack,
        ConditionsActions(change = vm::change, save = { vm.save() }, retry = vm::load, openFields = onFields),
    )
}

/**
 * Picking the conditions and goals someone lives with or wants to keep track of: searched by
 * name or other names, grouped, each with how it stands, since when and notes, and their own
 * ones too. Saving turns on the features they need, and their fields show in the forms.
 */
@Composable
fun ConditionsScreen(state: ConditionsState, on: Set<Module>, onBack: () -> Unit, actions: ConditionsActions) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MyCondition?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, "Conditions and goals") {
            TextButton(enabled = state.changed && !state.saving, onClick = actions.save) { Text("Save") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint(
                "Pick what you live with, or want to keep track of. The app then shows the features and fields that fit, and leaves out the rest. You can change it any time.",
                Modifier.padding(horizontal = 4.dp),
            )
            when {
                state.loading -> Loading()
                state.error != null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                else -> {
                    if (state.mine.conditions.isNotEmpty()) {
                        InfoCard("Yours") {
                            state.mine.conditions.forEachIndexed { i, pick ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                PickRow(pick, state.catalog) { editing = pick }
                            }
                        }
                        val turnsOn = ConditionsLogic.turnsOn(state.mine, state.catalog, on)
                        if (turnsOn.isNotEmpty()) {
                            Hint("Saving turns on ${ConditionsLogic.sayList(turnsOn.map { it.label })}.", Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    SearchField(query, { query = it }, "Search, e.g. OAB, endometriosis, ADHD")
                    val found = ConditionsLogic.search(state.catalog, query)
                    if (found.isEmpty()) {
                        Hint("Nothing called that. You can add your own below.", Modifier.padding(horizontal = 4.dp))
                    }
                    ConditionKind.entries.forEach { kind ->
                        val ofKind = found.filter { it.kind == kind }
                        if (ofKind.isEmpty()) return@forEach
                        Text(
                            ConditionsLogic.kindTitle(kind),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
                        )
                        ConditionsLogic.grouped(ofKind).forEach { (group, list) ->
                            InfoCard(group.takeIf { it != ConditionsLogic.kindTitle(kind) }) {
                                list.forEachIndexed { i, def ->
                                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    CatalogRow(def, picked = state.mine.conditions.any { it.id == def.id }) {
                                        actions.change(ConditionsLogic.toggle(state.mine, def))
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add your own")
                    }
                    AppCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                            Hint("Kept encrypted on your server, and never shared with anyone you share your data with.", Modifier.weight(1f))
                        }
                    }
                    TextButton(onClick = actions.openFields) { Text("Choose the fields yourself") }
                }
            }
        }
        if (state.changed) {
            Button(
                onClick = actions.save, enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(if (state.saving) "Saving…" else "Save") }
        }
    }
    editing?.let { pick ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            PickEditor(
                pick, state.catalog.firstOrNull { it.id == pick.id },
                onDone = { actions.change(ConditionsLogic.put(state.mine, it)); editing = null },
                onRemove = { actions.change(ConditionsLogic.remove(state.mine, pick.id)); editing = null },
            )
        }
    }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            CustomEditor(
                taken = state.mine.conditions.map { it.id }.toSet(),
                onDone = { actions.change(ConditionsLogic.put(state.mine, it)); adding = false },
            )
        }
    }
}

@Composable
private fun PickRow(pick: MyCondition, catalog: List<ConditionDefinition>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pick.name, style = MaterialTheme.typography.bodyLarge)
            val modules = ConditionsLogic.modulesOf(pick, catalog)
            val parts = listOfNotNull(
                pick.since?.let { "since $it" },
                modules.takeIf { it.isNotEmpty() }?.let { m -> ConditionsLogic.sayList(m.map { it.label }) },
                "your own".takeIf { pick.id.startsWith("custom:") },
            )
            if (parts.isNotEmpty()) Hint(parts.joinToString(" · "))
            pick.notes?.let { Hint(it, Modifier.padding(top = 2.dp)) }
        }
        pick.status?.let { Pill(it.label, MaterialTheme.colorScheme.primary) }
        TextButton(onClick = onClick) { Text("Edit") }
    }
}

@Composable
private fun CatalogRow(def: ConditionDefinition, picked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = picked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(def.name, style = MaterialTheme.typography.bodyLarge)
            Text(def.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (def.aliases.isNotEmpty()) Hint("Also: ${def.aliases.take(3).joinToString(", ")}")
        }
    }
}

/** How a pick stands, since when, and notes; or taking it away. */
@Composable
private fun PickEditor(initial: MyCondition, def: ConditionDefinition?, onDone: (MyCondition) -> Unit, onRemove: () -> Unit) {
    var status by remember { mutableStateOf(initial.status) }
    var since by remember { mutableStateOf(initial.since.orEmpty()) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    val validSince = ConditionsLogic.validSince(since)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(initial.name, style = MaterialTheme.typography.titleLarge)
        def?.let { Hint(it.about) }
        Text("Where things are", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConditionStatus.entries.forEach { s ->
                FilterChip(selected = status == s, onClick = { status = if (status == s) null else s }, label = { Text(s.label) })
            }
        }
        OutlinedTextField(
            since, { since = it.take(10) },
            label = { Text("Since") },
            supportingText = { Text(if (validSince) "Year and month, like 2024-05, or a whole date" else "Like 2024-05 or 2024-05-31") },
            isError = !validSince,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
        Button(
            enabled = validSince,
            onClick = { onDone(initial.copy(status = status, since = since.trim().ifBlank { null }, notes = notes.trim().ifBlank { null })) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
        OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Take it off my list") }
    }
}

/** The person's own condition or goal: its name, and the features and fields it should bring. */
@Composable
fun CustomEditor(taken: Set<String>, onDone: (MyCondition) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ConditionKind.CONDITION) }
    var modules by remember { mutableStateOf(emptySet<Module>()) }
    var fields by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf<ConditionStatus?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Add your own", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConditionKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(ConditionsLogic.kindTitle(k).removeSuffix("s")) }) }
        }
        if (kind == ConditionKind.CONDITION) {
            Text("Where things are", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConditionStatus.entries.forEach { s -> FilterChip(selected = status == s, onClick = { status = if (status == s) null else s }, label = { Text(s.label) }) }
            }
        }
        Text("Features it needs", style = MaterialTheme.typography.titleSmall)
        Hint("Saving turns these on if they're off.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModuleInfo.OPT_IN.forEach { m ->
                FilterChip(
                    selected = m in modules,
                    onClick = {
                        modules = if (m in modules) modules - m else modules + m
                        fields = fields.filter { Fields.ALL[it]?.first in modules }.toSet()
                    },
                    label = { Text(m.label) },
                )
            }
        }
        val groups = ConditionsLogic.fieldGroups().filter { (m, _) -> m in modules }
        if (groups.isNotEmpty()) {
            Text("Fields to show", style = MaterialTheme.typography.titleSmall)
            groups.forEach { (module, list) ->
                Text(module.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { (key, label) ->
                        FilterChip(selected = key in fields, onClick = { fields = if (key in fields) fields - key else fields + key }, label = { Text(label) })
                    }
                }
            }
        }
        Button(
            enabled = name.isNotBlank(),
            onClick = {
                onDone(
                    MyCondition(
                        ConditionsLogic.customId(name, taken), name.trim(), kind,
                        status = status.takeIf { kind == ConditionKind.CONDITION }, modules = modules, fields = fields,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add") }
    }
}
