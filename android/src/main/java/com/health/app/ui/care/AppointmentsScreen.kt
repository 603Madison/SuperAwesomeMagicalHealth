@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.SuggestedQuestion
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.appointmentSuggestions
import com.health.app.net.appointments
import com.health.app.net.deleteAppointment
import com.health.app.net.saveAppointment
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import androidx.compose.material.icons.rounded.History
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.IconTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class AppointmentsState(
    val now: Long,
    val list: List<Appointment> = emptyList(),
    val suggestions: Map<String, List<SuggestedQuestion>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val upcoming: List<Appointment> get() = list.filter { it.at >= now - 2 * 3600_000L }.sortedBy { it.at }
    val past: List<Appointment> get() = list.filter { it.at < now - 2 * 3600_000L }.sortedByDescending { it.at }
}

class AppointmentsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(AppointmentsState(System.currentTimeMillis()))
    val state: StateFlow<AppointmentsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.list.isEmpty(), error = null, now = System.currentTimeMillis()) }
        viewModelScope.launch {
            try {
                val list = c.ui.appointments(days = 730)
                _state.update { it.copy(list = list, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your appointments") }
            }
        }
    }

    fun loadSuggestions(id: String) = viewModelScope.launch {
        attempt { c.ui.appointmentSuggestions(id) }.onSuccess { s -> _state.update { it.copy(suggestions = it.suggestions + (id to s)) } }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            message?.let(c::tell)
            c.dataChanged()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            c.tell(e.message ?: "That didn't save")
        }
    }

    fun save(appointment: Appointment) = act("Saved") { c.api.saveAppointment(appointment) }

    fun delete(id: String) = act("Appointment removed") { c.api.deleteAppointment(id) }
}

@Composable
fun AppointmentsRoute(onOpen: (String?) -> Unit) {
    val c = appContainer()
    val vm = viewModel { AppointmentsViewModel(c) }
    val state by vm.state.collectAsState()
    AppointmentsScreen(state, c.zone(), onOpen = onOpen, onRetry = { vm.load() })
}

/** Appointments' sections, in the default order (the sections of [FeatureScreens.APPOINTMENTS]). */
val APPOINTMENT_SECTIONS = listOf(ArrangeItem("upcoming", "Coming up"), ArrangeItem("past", "Before"))

@Composable
fun AppointmentsScreen(state: AppointmentsState, zone: ZoneId, onOpen: (String?) -> Unit, onRetry: () -> Unit = {}) {
    val readOnly = LocalReadOnly.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Appointments", "Questions to ask, and what was said") {
            if (!readOnly) IconButton(onClick = { onOpen(null) }) { Icon(Icons.Rounded.Add, "Add an appointment") }
            ArrangeAction(FeatureScreens.APPOINTMENTS, "Appointments", APPOINTMENT_SECTIONS)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && state.list.isEmpty() -> Loading()
                state.error != null && state.list.isEmpty() -> ErrorCard(state.error, actionLabel = "Try again", onAction = onRetry)
                state.list.isEmpty() -> com.health.app.ui.modules.EmptyLine(
                    "No appointments yet. Keep your questions, a reminder and notes for each one here.",
                    action = "Add".takeIf { !readOnly }, onAction = { onOpen(null) },
                )
                else -> Sections(arrangedIn(FeatureScreens.APPOINTMENTS, APPOINTMENT_SECTIONS)) { id ->
                    when (id) {
                        "upcoming" -> if (state.upcoming.isNotEmpty()) {
                            InfoCard("Coming up", icon = Icons.Rounded.Event) {
                                state.upcoming.forEachIndexed { i, a ->
                                    if (i > 0) Spacer(Modifier.height(4.dp))
                                    AppointmentRow(a, zone, state.now) { onOpen(a.id) }
                                }
                            }
                        }
                        "past" -> if (state.past.isNotEmpty()) {
                            InfoCard("Before", icon = Icons.Rounded.History) {
                                state.past.take(12).forEach { a -> AppointmentRow(a, zone, state.now) { onOpen(a.id) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppointmentRow(a: Appointment, zone: ZoneId, now: Long, onClick: () -> Unit) {
    val open = a.questions.count { !it.done }
    com.health.app.ui.modules.NavRow(
        Icons.Rounded.Event,
        a.title,
        listOfNotNull(
            CareFormat.dayTime(a.at, zone),
            a.with?.takeIf { it.isNotBlank() },
            a.place?.takeIf { it.isNotBlank() },
            if (a.at >= now && open > 0) "$open ${if (open == 1) "question" else "questions"} to ask" else null,
            if (a.at < now && !a.notes.isNullOrBlank()) "notes kept" else null,
        ).joinToString(" · "),
        onClick = onClick,
    )
}

@Composable
fun AppointmentEditorRoute(id: String?, onDone: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { AppointmentsViewModel(c) }
    val state by vm.state.collectAsState()
    LaunchedEffect(id) { if (id != null) vm.loadSuggestions(id) }
    val existing = id?.let { i -> state.list.firstOrNull { it.id == i } }
    if (id != null && existing == null) {
        if (state.loading) Loading() else BackBar(onDone, "Not found")
        return
    }
    AppointmentEditor(
        existing, id?.let { state.suggestions[it] }.orEmpty(), c.zone(),
        onBack = onDone,
        onSave = { vm.save(it); onDone() },
        onDelete = { vm.delete(it); onDone() },
    )
}

private val DURATIONS = listOf(15, 30, 45, 60, 90)
private val REMINDERS = listOf(2880, 1440, 120, 60, 30)

@Composable
fun AppointmentEditor(
    existing: Appointment?,
    suggestions: List<SuggestedQuestion>,
    zone: ZoneId,
    onBack: () -> Unit,
    onSave: (Appointment) -> Unit,
    onDelete: (String) -> Unit,
) {
    val readOnly = LocalReadOnly.current
    val start = existing ?: Appointment(
        at = LocalDate.now(zone).plusDays(7).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), title = "",
    )
    val startAt = Instant.ofEpochMilli(start.at).atZone(zone)
    var title by remember { mutableStateOf(start.title) }
    var with by remember { mutableStateOf(start.with.orEmpty()) }
    var place by remember { mutableStateOf(start.place.orEmpty()) }
    var date by remember { mutableStateOf(startAt.toLocalDate()) }
    var time by remember { mutableStateOf(startAt.toLocalTime().withSecond(0).withNano(0)) }
    var duration by remember { mutableStateOf(start.durationMinutes) }
    var remind by remember { mutableStateOf(start.remindBefore.toSet()) }
    var questions by remember { mutableStateOf(start.questions) }
    var newQuestion by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(start.notes.orEmpty()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    val past = existing != null && at < System.currentTimeMillis()

    fun build() = start.copy(
        at = at, title = title.trim(), with = with.trim().ifBlank { null }, place = place.trim().ifBlank { null },
        durationMinutes = duration, remindBefore = remind.sortedDescending(), questions = questions,
        notes = notes.trim().ifBlank { null },
    )

    fun addQuestion(text: String, suggested: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || questions.any { it.text.equals(t, ignoreCase = true) }) return
        questions = questions + AppointmentQuestion(t, suggested = suggested)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, if (existing == null) "Add an appointment" else existing.title) {
            if (!readOnly) TextButton(enabled = title.isNotBlank(), onClick = { onSave(build()) }) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("What and when") {
                OutlinedTextField(
                    title, { title = it }, label = { Text("What it is") }, placeholder = { Text("Check-up, blood test, physio") },
                    singleLine = true, readOnly = readOnly, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(with, { with = it }, label = { Text("With (optional)") }, singleLine = true, readOnly = readOnly, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(place, { place = it }, label = { Text("Where (optional)") }, singleLine = true, readOnly = readOnly, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(enabled = !readOnly, onClick = { picking = "date" }) { Text(CareFormat.date(date)) }
                    OutlinedButton(enabled = !readOnly, onClick = { picking = "time" }) { Text("%d:%02d".format(time.hour, time.minute)) }
                }
                if (!readOnly) {
                    Text("How long", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DURATIONS.forEach { d -> FilterChip(selected = duration == d, onClick = { duration = if (duration == d) null else d }, label = { Text(if (d < 60) "$d min" else if (d == 60) "1 hour" else "1½ hours") }) }
                    }
                    Text("Remind me", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        REMINDERS.forEach { m -> FilterChip(selected = m in remind, onClick = { remind = if (m in remind) remind - m else remind + m }, label = { Text(CareFormat.before(m)) }) }
                    }
                }
            }

            InfoCard("Questions to ask", icon = Icons.Rounded.QuestionAnswer) {
                if (questions.isEmpty()) Hint("Write down what you'd like to ask, so nothing's forgotten on the day.")
                questions.forEachIndexed { i, q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = q.done, enabled = !readOnly, onCheckedChange = { v -> questions = questions.toMutableList().also { it[i] = q.copy(done = v) } })
                        Text(q.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (!readOnly) IconButton(onClick = { questions = questions.filterIndexed { k, _ -> k != i } }) { Icon(Icons.Rounded.Close, "Remove the question") }
                    }
                }
                if (!readOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            newQuestion, { newQuestion = it }, label = { Text("Add a question") }, singleLine = false, maxLines = 3,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                        IconButton(enabled = newQuestion.isNotBlank(), onClick = { addQuestion(newQuestion); newQuestion = "" }) { Icon(Icons.Rounded.Add, "Add it") }
                    }
                }
            }

            if (!readOnly) {
                val fresh = suggestions.filter { s -> questions.none { it.text.equals(s.text, ignoreCase = true) } }
                InfoCard("Suggested from your logs", icon = Icons.Rounded.Lightbulb) {
                    when {
                        existing?.id == null -> Hint("Once it's saved, questions suggested from what you've logged show here.")
                        fresh.isEmpty() -> Hint("Nothing to suggest from what's been logged since your last appointment.")
                        else -> fresh.forEach { s ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.text, style = MaterialTheme.typography.bodyLarge)
                                    Hint(s.why)
                                }
                                TextButton(onClick = { addQuestion(s.text, suggested = true) }) { Text("Add") }
                            }
                        }
                    }
                }
            }

            if (past || !notes.isBlank()) {
                InfoCard("Afterwards") {
                    OutlinedTextField(
                        notes, { notes = it }, label = { Text("What was said and decided") }, minLines = 3, readOnly = readOnly,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!readOnly) {
                Button(onClick = { onSave(build()) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
                if (existing?.id != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete this appointment", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    when (picking) {
        "date" -> DatePickDialog(date, { picking = null }) { date = it; picking = null }
        "time" -> TimePickDialog(time, "When", { picking = null }) { time = it; picking = null }
    }
    if (confirmDelete && existing?.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this appointment?") },
            text = { Text("Its questions and notes go too.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(existing.id!!) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
