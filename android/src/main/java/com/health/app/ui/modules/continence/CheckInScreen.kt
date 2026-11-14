@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceCheckIn
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addContinenceCheckIn
import com.health.app.net.continenceCheckIns
import com.health.app.net.deleteContinenceCheckIn
import com.health.app.ui.care.CareFormat
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Compared with a month ago, -2 (much worse) to 2 (much better). */
val COMPARED = listOf(-2 to "Much worse", -1 to "A bit worse", 0 to "About the same", 1 to "A bit better", 2 to "Much better")

val IMPACTS = listOf("sleep", "work", "going out", "exercise", "sex", "travel", "time with people", "mood", "money")

data class CheckInState(val today: LocalDate, val list: List<ContinenceCheckIn> = emptyList(), val loading: Boolean = true, val error: String? = null)

class CheckInViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(CheckInState(LocalDate.now(c.zone())))
    val state: StateFlow<CheckInState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(list = c.ui.continenceCheckIns(365), loading = false, error = null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your check-ins") }
            }
        }
    }

    fun save(checkIn: ContinenceCheckIn) = viewModelScope.launch {
        attempt { c.ui.addContinenceCheckIn(checkIn) }
            .onSuccess { c.tell("Thanks for checking in"); c.dataChanged(); load() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun delete(id: String) = viewModelScope.launch {
        attempt { c.ui.deleteContinenceCheckIn(id) }.onSuccess { c.dataChanged(); load() }.onFailure { c.tell(it.message ?: "Couldn't delete that") }
    }
}

@Composable
fun CheckInRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { CheckInViewModel(c) }
    val state by vm.state.collectAsState()
    CheckInScreen(state, onBack, onSave = { vm.save(it) }, onDelete = { vm.delete(it) }, onRetry = vm::load)
}

/** A weekly "how much does it bother you", what it got in the way of, and how it compares; with the ones before. */
@Composable
fun CheckInScreen(state: CheckInState, onBack: () -> Unit, onSave: (ContinenceCheckIn) -> Unit = {}, onDelete: (String) -> Unit = {}, onRetry: () -> Unit = {}) {
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    var bother by remember { mutableStateOf<Int?>(null) }
    var compared by remember { mutableStateOf<Int?>(null) }
    var impact by remember { mutableStateOf(emptySet<String>()) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Weekly check-in")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!readOnly) InfoCard("This week") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("How much has it bothered you?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(bother?.let { "$it of 10" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = (bother ?: 0).toFloat(), onValueChange = { bother = it.roundToInt() }, valueRange = 0f..10f, steps = 9,
                        modifier = Modifier.semantics { contentDescription = "How much it bothered you, 0 not at all to 10 a great deal" },
                    )
                    Row { Hint("Not at all", Modifier.weight(1f)); Hint("A great deal") }
                    Text("Compared with a month ago", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        COMPARED.forEach { (v, label) -> FilterChip(selected = compared == v, onClick = { compared = if (compared == v) null else v }, label = { Text(label) }) }
                    }
                    Text("What it got in the way of", style = MaterialTheme.typography.titleSmall)
                    ChipPicker(IMPACTS, impact, { impact = it }, addLabel = "Something else")
                    OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
                    Button(
                        enabled = bother != null,
                        onClick = {
                            onSave(ContinenceCheckIn(state.today.toString(), bother!!, compared, impact.toList(), note.trim().ifBlank { null }))
                            bother = null; compared = null; impact = emptySet(); note = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
            }
            when {
                state.loading -> Loading()
                state.error != null -> ErrorCard(state.error, actionLabel = "Try again", onAction = onRetry)
                state.list.isEmpty() -> Hint("Once a week is plenty: over time it shows whether things are easing.", Modifier.padding(horizontal = 4.dp))
                else -> InfoCard("Before") {
                    val ordered = state.list.sortedBy { it.date }
                    if (ordered.size >= 2) {
                        LineChart(ordered.map { it.bother.toDouble() }, color = AppTheme.data.awake, min = 0.0, max = 10.0, height = 80.dp)
                        AxisLabels(CareFormat.date(ordered.first().date).orEmpty(), null, CareFormat.date(ordered.last().date).orEmpty())
                        Spacer(Modifier.height(10.dp))
                    }
                    state.list.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${CareFormat.date(c.date)} · bothered ${c.bother} of 10", style = MaterialTheme.typography.bodyLarge)
                                val more = listOfNotNull(
                                    c.compared?.let { v -> COMPARED.firstOrNull { it.first == v }?.second },
                                    c.impact.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                    c.note,
                                )
                                if (more.isNotEmpty()) Hint(more.joinToString(" · "))
                            }
                            if (!readOnly && c.id != null) IconButton(onClick = { onDelete(c.id!!) }) { Icon(Icons.Rounded.Delete, "Delete the check-in of ${CareFormat.date(c.date)}") }
                        }
                    }
                }
            }
        }
    }
}
