@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.tracking

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Migraine
import com.health.api.Place
import com.health.api.SymptomEntry
import com.health.api.SymptomsSummary
import com.health.app.Container
import com.health.app.net.addMigraine
import com.health.app.net.addSymptom
import com.health.app.net.deleteMigraine
import com.health.app.net.deleteSymptom
import com.health.app.net.migraines
import com.health.app.net.places
import com.health.app.net.symptoms
import com.health.app.net.symptomsSummary
import com.health.app.net.updateMigraine
import com.health.app.net.updateSymptom
import com.health.app.ui.Format
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class SymptomsState(
    val entries: List<SymptomEntry> = emptyList(),
    val migraines: List<Migraine> = emptyList(),
    val summary: SymptomsSummary? = null,
    val place: Place? = null,
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** A migraine still going: started, not yet over. */
    val open: Migraine? get() = migraines.filter { it.end == null }.maxByOrNull { it.start }
}

class SymptomsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(SymptomsState(place = c.profile.value?.place))
    val state: StateFlow<SymptomsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
        viewModelScope.launch { c.profile.collect { p -> _state.update { it.copy(place = p?.place) } } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null) }
        viewModelScope.launch {
            try {
                val entries = async { c.ui.symptoms(30) }
                val migraines = async { c.ui.migraines(90) }
                val summary = async { c.ui.symptomsSummary(90) }
                _state.update { it.copy(entries = entries.await(), migraines = migraines.await(), summary = summary.await(), loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your symptoms") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { message?.let(c::tell); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "That didn't work") }
    }

    fun save(entry: SymptomEntry) = act("${entry.symptom.replaceFirstChar { it.uppercase() }} logged") {
        if (entry.id == null) c.ui.addSymptom(entry) else c.ui.updateSymptom(entry)
    }

    fun delete(id: String) = act(null) { c.ui.deleteSymptom(id) }

    fun saveMigraine(m: Migraine) = act(if (m.id == null) "Migraine noted; take care" else if (m.end != null) "Glad it's over" else null) {
        if (m.id == null) c.ui.addMigraine(m) else c.ui.updateMigraine(m)
    }

    fun deleteMigraine(id: String) = act(null) { c.ui.deleteMigraine(id) }

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            val found = runCatching { c.ui.places(query) }.getOrElse { c.tell(it.message ?: "Couldn't look that up"); emptyList() }
            _state.update { it.copy(places = found, searching = false) }
        }
    }

    fun setPlace(place: Place?) = act(if (place != null) "Comparing with the weather in ${place.name}" else null) {
        val profile = c.profile.value ?: c.loadProfile() ?: return@act
        c.saveProfile(profile.copy(place = place))
        _state.update { it.copy(places = emptyList()) }
    }
}

class SymptomsActions(
    val retry: () -> Unit = {},
    val save: (SymptomEntry) -> Unit = {},
    val delete: (String) -> Unit = {},
    val saveMigraine: (Migraine) -> Unit = {},
    val deleteMigraine: (String) -> Unit = {},
    val search: (String) -> Unit = {},
    val setPlace: (Place?) -> Unit = {},
)

@Composable
fun SymptomsRoute() {
    val c = appContainer()
    val vm = viewModel { SymptomsViewModel(c) }
    val state by vm.state.collectAsState()
    SymptomsScreen(
        state, c.zone(),
        SymptomsActions(
            retry = { vm.load() }, save = { vm.save(it) }, delete = { vm.delete(it) }, saveMigraine = { vm.saveMigraine(it) },
            deleteMigraine = { vm.deleteMigraine(it) }, search = vm::search, setPlace = { vm.setPlace(it) },
        ),
    )
}

@Composable
fun severityColor(severity: Int) = when {
    severity >= 7 -> AppTheme.status.bad
    severity >= 4 -> AppTheme.status.warn
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun SymptomsScreen(state: SymptomsState, zone: ZoneId, actions: SymptomsActions, now: Long = System.currentTimeMillis()) {
    var logging by remember { mutableStateOf<SymptomEntry?>(null) }
    var migraine by remember { mutableStateOf<Migraine?>(null) }
    var choosingPlace by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Symptoms & pain", "What you feel, and what goes with it")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("How are you feeling?", icon = Icons.Rounded.Healing) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SymptomWords.symptoms.take(10).forEach { s ->
                        SuggestionChip(onClick = { logging = SymptomEntry(System.currentTimeMillis(), s, 4) }, label = { Text(s) })
                    }
                }
                TextButton(onClick = { logging = SymptomEntry(System.currentTimeMillis(), "", 4) }) { Text("Something else") }
            }
            MigraineCard(state.open, zone, now, onStart = { migraine = Migraine(System.currentTimeMillis(), severity = 6) }, onEnd = { m -> actions.saveMigraine(m.copy(end = System.currentTimeMillis())) }, onOpen = { migraine = it })
            when {
                state.loading && state.summary == null -> Loading()
                state.error != null && state.summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                else -> {
                    RecentCard(state, zone, now, onSymptom = { logging = it }, onMigraine = { migraine = it })
                    state.summary?.let { SummaryCard(it) }
                    PlaceCard(state.place) { choosingPlace = true }
                }
            }
            Hint("If something is severe, new or worrying you, talk to a doctor. This diary is what they'll often ask for.", Modifier.padding(horizontal = 4.dp))
        }
    }
    logging?.let { entry ->
        ModalBottomSheet(onDismissRequest = { logging = null }) {
            SymptomForm(entry, onSave = { actions.save(it); logging = null }, onDelete = entry.id?.let { id -> { actions.delete(id); logging = null } })
        }
    }
    migraine?.let { m ->
        ModalBottomSheet(onDismissRequest = { migraine = null }) {
            MigraineForm(m, onSave = { actions.saveMigraine(it); migraine = null }, onDelete = m.id?.let { id -> { actions.deleteMigraine(id); migraine = null } })
        }
    }
    if (choosingPlace) PlaceDialog(state, actions) { choosingPlace = false }
}

@Composable
private fun MigraineCard(open: Migraine?, zone: ZoneId, now: Long, onStart: () -> Unit, onEnd: (Migraine) -> Unit, onOpen: (Migraine) -> Unit) {
    InfoCard("Migraine", icon = Icons.Rounded.Bolt, iconTint = AppTheme.status.warn) {
        if (open == null) {
            Hint("Starting one? Note it now; you can add the rest when you feel better.")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onStart) { Text("A migraine is starting") }
        } else {
            Text(
                "Since ${Format.timeOfDay(open.start, zone)}, ${Format.minutes(((now - open.start) / 60_000).toInt().coerceAtLeast(0))} so far",
                style = MaterialTheme.typography.titleMedium,
            )
            Hint("Rest somewhere dark and quiet if you can. Tap below when it's over.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onEnd(open) }) { Text("It's over") }
                OutlinedButton(onClick = { onOpen(open) }) { Text("Add details") }
            }
        }
    }
}

@Composable
private fun RecentCard(state: SymptomsState, zone: ZoneId, now: Long, onSymptom: (SymptomEntry) -> Unit, onMigraine: (Migraine) -> Unit) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun day(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val items: List<Pair<Long, @Composable () -> Unit>> =
        state.entries.map { e ->
            e.at to @Composable {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSymptom(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.symptom.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                        val detail = listOfNotNull(
                            Format.day(day(e.at), today) + ", " + Format.timeOfDay(e.at, zone),
                            e.bodyAreas.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            e.triggers.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        ).joinToString(" · ")
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("${e.severity}/10", severityColor(e.severity))
                }
            }
        } + state.migraines.filter { it.end != null }.map { m ->
            m.start to @Composable {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onMigraine(m) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (m.aura) "Migraine with aura" else "Migraine", style = MaterialTheme.typography.bodyLarge)
                        val length = m.end?.let { Format.minutes(((it - m.start) / 60_000).toInt()) }
                        Text(listOfNotNull(Format.day(day(m.start), today), length, m.symptoms.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("${m.severity}/10", severityColor(m.severity))
                }
            }
        }
    InfoCard("Lately") {
        if (items.isEmpty()) {
            Hint("Nothing logged in the last month.")
            return@InfoCard
        }
        items.sortedByDescending { it.first }.take(12).forEachIndexed { i, (_, row) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

@Composable
private fun SummaryCard(summary: SymptomsSummary) {
    InfoCard("Last three months", icon = Icons.Rounded.Insights) {
        Row(Modifier.fillMaxWidth()) {
            Stat("${summary.days.count { it.entries.isNotEmpty() || it.migraines.isNotEmpty() }}", "days with symptoms", Modifier.weight(1f))
            Stat("${summary.migraineDays}", "migraine days", Modifier.weight(1f), color = AppTheme.status.warn)
        }
        if (summary.counts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Most often", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.counts.take(6).forEach { c -> SuggestionChip(onClick = {}, label = { Text("${c.name} · ${c.count}") }) }
            }
        }
        if (summary.triggers.isNotEmpty()) {
            Text("What seemed to set them off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.triggers.take(6).forEach { c -> SuggestionChip(onClick = {}, label = { Text("${c.name} · ${c.count}") }) }
            }
        }
        if (summary.correlations.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("What went with them", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            summary.correlations.forEach { c ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint("Patterns in your own data, not causes; worth mentioning to your doctor.")
        } else if (summary.days.count { it.entries.isNotEmpty() || it.migraines.isNotEmpty() } > 0) {
            Hint("Patterns show once there are a few weeks of days to compare.", Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PlaceCard(place: Place?, onChoose: () -> Unit) {
    InfoCard("Air pressure", icon = Icons.Rounded.Air) {
        if (place == null) {
            Hint("Some people's migraines and aches follow the weather. Add your town to compare with falling air pressure (your server looks it up, roughly).")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onChoose) { Text("Add your town") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(place.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onChoose) { Text("Change") }
            }
        }
    }
}

@Composable
private fun PlaceDialog(state: SymptomsState, actions: SymptomsActions, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Where are you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Town or city") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.search(query) }, enabled = query.isNotBlank() && !state.searching) { Text("Find") }
                }
                state.places.forEach { p ->
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { actions.setPlace(p); onClose() }.padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                if (state.place != null) TextButton(onClick = { actions.setPlace(null); onClose() }) { Text("Stop comparing with the weather") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** 0 (none) to 10 (worst imaginable), as a slider with its words. */
@Composable
fun SeveritySlider(value: Int, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row {
            Text("How bad", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${v.roundToInt()} of 10", style = MaterialTheme.typography.bodyLarge, color = severityColor(v.roundToInt()))
        }
        Slider(
            v, { v = it }, valueRange = 0f..10f, steps = 9, onValueChangeFinished = { onChange(v.roundToInt()) },
            modifier = Modifier.semantics { contentDescription = "How bad, ${v.roundToInt()} of 10" },
        )
        Row {
            Text("None", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Worst imaginable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SymptomForm(initial: SymptomEntry, onSave: (SymptomEntry) -> Unit, onDelete: (() -> Unit)?) {
    var e by remember { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (initial.id == null) "Log a symptom" else "Symptom", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(e.symptom, { e = e.copy(symptom = it.take(60)) }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (initial.symptom.isBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SymptomWords.symptoms.forEach { s -> FilterChip(e.symptom == s, { e = e.copy(symptom = s) }, label = { Text(s) }) }
            }
        }
        SeveritySlider(e.severity) { e = e.copy(severity = it) }
        Text("Where", style = MaterialTheme.typography.labelLarge)
        ChipPicker(SymptomWords.areas, e.bodyAreas.toSet(), { e = e.copy(bodyAreas = it.toList()) })
        Text("What might have set it off", style = MaterialTheme.typography.labelLarge)
        ChipPicker(SymptomWords.triggers, e.triggers.toSet(), { e = e.copy(triggers = it.toList()) })
        Text("What you tried", style = MaterialTheme.typography.labelLarge)
        ChipPicker(SymptomWords.relief, e.relief.toSet(), { e = e.copy(relief = it.toList()) })
        if (e.relief.isNotEmpty()) {
            Text("Did it help?", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SymptomWords.helped.forEachIndexed { i, label -> FilterChip(e.reliefHelped == i, { e = e.copy(reliefHelped = if (e.reliefHelped == i) null else i) }, label = { Text(label) }) }
            }
        }
        OutlinedTextField(e.note.orEmpty(), { e = e.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(e.copy(symptom = e.symptom.trim().lowercase())) }, enabled = e.symptom.isNotBlank()) { Text("Save") }
            onDelete?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
fun MigraineForm(initial: Migraine, onSave: (Migraine) -> Unit, onDelete: (() -> Unit)?) {
    var m by remember { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (initial.id == null) "A migraine" else "Migraine", style = MaterialTheme.typography.titleLarge)
        SeveritySlider(m.severity) { m = m.copy(severity = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(m.aura, { m = m.copy(aura = !m.aura) }, label = { Text("With aura") })
            FilterChip(m.missedPlans, { m = m.copy(missedPlans = !m.missedPlans) }, label = { Text("Missed plans because of it") })
        }
        Text("It came with", style = MaterialTheme.typography.labelLarge)
        ChipPicker(SymptomWords.migraine, m.symptoms.toSet(), { m = m.copy(symptoms = it.toList()) })
        Text("What might have set it off", style = MaterialTheme.typography.labelLarge)
        ChipPicker(SymptomWords.triggers, m.triggers.toSet(), { m = m.copy(triggers = it.toList()) })
        Text("What you took", style = MaterialTheme.typography.labelLarge)
        ChipPicker(listOf("ibuprofen", "paracetamol", "naproxen", "aspirin", "triptan", "anti-sickness"), m.medications.toSet(), { m = m.copy(medications = it.toList()) })
        if (m.medications.isNotEmpty()) {
            Text("Did it help?", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SymptomWords.helped.forEachIndexed { i, label -> FilterChip(m.reliefHelped == i, { m = m.copy(reliefHelped = if (m.reliefHelped == i) null else i) }, label = { Text(label) }) }
            }
        }
        OutlinedTextField(m.note.orEmpty(), { m = m.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(m) }) { Text("Save") }
            if (m.id != null && m.end == null) OutlinedButton(onClick = { onSave(m.copy(end = System.currentTimeMillis())) }) { Text("It's over") }
            onDelete?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
