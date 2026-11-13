package com.health.app.ui.conditions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ConditionDefinition
import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.Module
import com.health.api.MyConditions
import com.health.app.Container
import com.health.app.net.conditionCatalog
import com.health.app.net.conditions
import com.health.app.net.saveFieldChoices
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ModuleInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FieldsState(
    val layout: FieldLayout? = null,
    val catalog: List<ConditionDefinition> = emptyList(),
    val mine: MyConditions = MyConditions(),
    val loading: Boolean = true,
    val error: String? = null,
)

class FieldsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(FieldsState(layout = c.fields.value))
    val state: StateFlow<FieldsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.layout == null, error = null) }
        viewModelScope.launch {
            try {
                c.refreshFields()
                val catalog = async { runCatching { c.api.conditionCatalog() }.getOrDefault(emptyList()) }
                val mine = async { runCatching { c.api.conditions() }.getOrDefault(MyConditions()) }
                _state.update { it.copy(layout = c.fields.value, catalog = catalog.await(), mine = mine.await(), loading = false, error = if (c.fields.value == null) "Couldn't load your fields" else null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your fields") }
            }
        }
    }

    fun set(key: String, on: Boolean) {
        val s = _state.value
        val layout = s.layout ?: return
        val choices = ConditionsLogic.choose(FieldChoices(layout.show, layout.hide), key, on, ConditionsLogic.brought(s.mine, s.catalog))
        viewModelScope.launch {
            try {
                c.api.saveFieldChoices(choices)
                c.refreshFields()
                _state.update { it.copy(layout = c.fields.value) }
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't save that")
            }
        }
    }
}

@Composable
fun FieldsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { FieldsViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    FieldsScreen(state, profile?.modules ?: c.modules(), onBack, onSet = vm::set, onRetry = vm::load)
}

/**
 * Every field the forms can ask for, under its feature: on or off, and why ("Because of
 * Overactive bladder", "On for everyone", "You turned this on"). Saved as the person's choices.
 */
@Composable
fun FieldsScreen(state: FieldsState, on: Set<Module>, onBack: () -> Unit, onSet: (String, Boolean) -> Unit = { _, _ -> }, onRetry: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Fields")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(
                "The details each form asks for. Your conditions and goals turn on the ones that fit; turn any on or off here.",
                Modifier.padding(horizontal = 4.dp),
            )
            when {
                state.loading -> Loading()
                state.error != null && state.layout == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = onRetry)
                else -> {
                    val layout = state.layout
                    ConditionsLogic.fieldGroups().forEach { (module, fields) ->
                        InfoCard(module.label, icon = ModuleInfo.icon(module)) {
                            if (module !in on) Hint("This feature is off, so these don't show yet.", Modifier.padding(bottom = 6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                fields.forEach { (key, label) ->
                                    val shown = layout?.effective?.contains(key) ?: false
                                    LabeledSwitch(label, shown, { onSet(key, it) }, subtitle = ConditionsLogic.reason(layout, key), enabled = layout != null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
