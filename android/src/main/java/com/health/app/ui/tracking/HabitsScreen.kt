@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitStatus
import com.health.api.HabitTick
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addHabit
import com.health.app.net.deleteHabit
import com.health.app.net.habitsToday
import com.health.app.net.tickHabit
import com.health.app.net.updateHabit
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.showsField
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.EmptyLine
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.sectionId
import com.health.api.Fields
import com.health.app.ui.modules.IconTile
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HabitsState(
    val statuses: List<HabitStatus> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class HabitsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(HabitsState())
    val state: StateFlow<HabitsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.statuses.isEmpty(), error = null) }
        viewModelScope.launch {
            try {
                val statuses = c.ui.habitsToday()
                _state.update { it.copy(statuses = statuses, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your habits") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        attempt { block() }
            .onSuccess { message?.let(c::tell); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "That didn't work") }
    }

    fun tick(status: HabitStatus, count: Int) = act(if (count >= status.habit.perDay && !status.done) "Nice: ${status.habit.name} done" else null) {
        c.ui.tickHabit(status.habit.id!!, HabitTick(status.date, count.coerceAtLeast(0)))
    }

    fun save(habit: Habit) = act(if (habit.id == null) "Habit added" else "Saved") {
        if (habit.id == null) c.ui.addHabit(habit) else c.ui.updateHabit(habit)
    }

    fun delete(id: String) = act("Habit removed") { c.ui.deleteHabit(id) }
}

class HabitsActions(
    val retry: () -> Unit = {},
    val tick: (HabitStatus, Int) -> Unit = { _, _ -> },
    val save: (Habit) -> Unit = {},
    val delete: (String) -> Unit = {},
)

@Composable
fun HabitsRoute() {
    val c = appContainer()
    val vm = viewModel { HabitsViewModel(c) }
    val state by vm.state.collectAsState()
    HabitsScreen(
        state, HabitsActions(retry = { vm.load() }, tick = { s, n -> vm.tick(s, n) }, save = { vm.save(it) }, delete = { vm.delete(it) }),
        water = showsField(Fields.NUTRITION_WATER),
    )
}

/** The habits, one section each (the sections of [FeatureScreens.HABITS]): the person puts them in their own order. */
fun habitSections(statuses: List<HabitStatus>): List<ArrangeItem> =
    statuses.mapNotNull { s -> s.habit.id?.let { ArrangeItem(sectionId("habit", it), s.habit.name) } }

/** How a habit can be ticked: not by the water goal while water isn't tracked, unless it already is. */
fun habitSources(water: Boolean, current: HabitSource? = null): List<HabitSource> =
    HabitSource.entries.filter { water || it != HabitSource.WATER_GOAL || it == current }

@Composable
fun HabitsScreen(state: HabitsState, actions: HabitsActions, water: Boolean = true) {
    var editing by remember { mutableStateOf<Habit?>(null) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    val sections = habitSections(state.statuses)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Habits", "Small things, kept up kindly") {
            if (!readOnly) IconButton(onClick = { editing = Habit("") }) { Icon(Icons.Rounded.Add, "Add a habit") }
            ArrangeAction(FeatureScreens.HABITS, "Habits", sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && state.statuses.isEmpty() -> Loading()
                state.error != null && state.statuses.isEmpty() -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                state.statuses.isEmpty() -> EmptyLine(
                    "No habits yet. Start with one small thing, a few days a week.",
                    action = "Add".takeIf { !readOnly }, onAction = { editing = Habit("") },
                )
                else -> {
                    val byId = state.statuses.filter { it.habit.id != null }.associateBy { sectionId("habit", it.habit.id!!) }
                    Sections(arrangedIn(FeatureScreens.HABITS, sections)) { id ->
                        byId[id]?.let { s -> HabitCard(s, onTick = { n -> actions.tick(s, n) }, onEdit = { if (!readOnly) editing = s.habit }) }
                    }
                    // Any not saved yet go last.
                    state.statuses.filter { it.habit.id == null }.forEach { s -> HabitCard(s, onTick = { n -> actions.tick(s, n) }, onEdit = { editing = s.habit }) }
                }
            }
            if (state.statuses.isNotEmpty()) {
                Hint("Streaks forgive: weeks count when they reach your aim, and one missed day in seven doesn't break a day streak.", Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    editing?.let { habit ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            HabitForm(habit, onSave = { actions.save(it); editing = null }, onDelete = habit.id?.let { id -> { actions.delete(id); editing = null } }, water = water)
        }
    }
}

/** What a habit's aim reads as: "Every day", "5 days a week". */
fun aimText(h: Habit): String = (if (h.timesPerWeek >= 7) "Every day" else "${h.timesPerWeek} days a week") + (if (h.perDay > 1) ", ${h.perDay} times" else "")

@Composable
fun HabitCard(s: HabitStatus, onTick: (Int) -> Unit, onEdit: () -> Unit) {
    AppCard(padding = 14.dp, onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(HabitIcons.of(s.habit.icon), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.habit.name, style = MaterialTheme.typography.titleMedium)
                val auto = if (s.habit.source != HabitSource.MANUAL) " · ticks itself" else ""
                Text("${aimText(s.habit)} · ${s.weekDone} this week$auto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HabitTickControl(s, onTick)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecentDots(s.recent, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Streak(s)
        }
    }
}

/** Tick it (or count up and down, for habits done several times a day); auto habits only show. */
@Composable
fun HabitTickControl(s: HabitStatus, onTick: (Int) -> Unit) {
    if (s.habit.perDay > 1 && s.habit.source == HabitSource.MANUAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onTick(s.count - 1) }, enabled = s.count > 0) { Icon(Icons.Rounded.Remove, "One fewer") }
            Text("${s.count}/${s.habit.perDay}", style = MaterialTheme.typography.titleMedium, color = if (s.done) AppTheme.status.good else MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = { onTick(s.count + 1) }) { Icon(Icons.Rounded.Add, "One more") }
        }
    } else {
        // An empty ring to tick; a filled green check once done. Habits that tick themselves only show.
        val manual = s.habit.source == HabitSource.MANUAL
        val good = AppTheme.status.good
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .let { if (manual) it.clickable(onClickLabel = if (s.done) "Not done" else "Done") { onTick(if (s.done) 0 else s.habit.perDay) } else it }
                .semantics {
                    contentDescription = when {
                        s.done -> "${s.habit.name}: done"
                        manual -> "${s.habit.name}: not done yet"
                        else -> "${s.habit.name}: ticks itself, not yet"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).let {
                    if (s.done) it.background(good) else it.border(2.dp, if (manual) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                },
                contentAlignment = Alignment.Center,
            ) { if (s.done) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Whole numbers to pick from, evenly across the row, each a 48dp target. */
@Composable
fun NumberSteps(label: String, value: Int, range: IntRange, describe: (Int) -> String, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(describe(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            range.forEach { n ->
                val on = n == value
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { onChange(n) }.semantics { contentDescription = "$label: ${describe(n)}"; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp),
                    ) { Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) } }
                }
            }
        }
    }
}

/** The last 14 days: filled done, ringed missed, faint before the habit began. */
@Composable
fun RecentDots(recent: List<Boolean?>, modifier: Modifier = Modifier) {
    val done = AppTheme.status.good
    val missed = MaterialTheme.colorScheme.outline
    val none = MaterialTheme.colorScheme.surfaceContainerHighest
    val text = "Last two weeks: ${recent.count { it == true }} days done"
    Row(modifier.semantics { contentDescription = text }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        recent.forEach { d ->
            Box(
                Modifier.size(12.dp).clip(CircleShape).let { m ->
                    when (d) {
                        true -> m.background(done)
                        false -> m.border(1.5.dp, missed, CircleShape)
                        null -> m.background(none)
                    }
                },
            )
        }
    }
}

@Composable
fun Streak(s: HabitStatus) {
    if (s.streakWeeks <= 0 && s.streakDays <= 1) return
    Surface(color = AppTheme.data.carbs.copy(alpha = 0.14f), contentColor = AppTheme.data.carbs, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            val text = if (s.habit.timesPerWeek >= 7 && s.streakDays > 1) "${s.streakDays} days" else "${s.streakWeeks} ${if (s.streakWeeks == 1) "week" else "weeks"}"
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private val REMINDER_TIMES = listOf("07:30", "09:00", "12:30", "18:00", "21:00")

@Composable
fun HabitForm(initial: Habit, onSave: (Habit) -> Unit, onDelete: (() -> Unit)?, water: Boolean = true) {
    var h by remember { mutableStateOf(initial) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (initial.id == null) "A new habit" else "Habit", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(h.name, { h = h.copy(name = it.take(60)) }, label = { Text("Name") }, placeholder = { Text("e.g. A walk after lunch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("How it's ticked", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            habitSources(water, initial.source).forEach { src ->
                FilterChip(h.source == src, {
                    val name = if (h.name.isBlank() || HabitSource.entries.any { it.label == h.name }) (if (src == HabitSource.MANUAL) "" else src.label) else h.name
                    h = h.copy(source = src, name = name, perDay = if (src == HabitSource.MANUAL) h.perDay else 1)
                }, label = { Text(src.label) })
            }
        }
        NumberSteps("Days a week", h.timesPerWeek, 1..7, { if (it == 7) "Every day" else "$it days a week" }) { h = h.copy(timesPerWeek = it) }
        if (h.source == HabitSource.MANUAL) {
            NumberSteps("Times a day", h.perDay.coerceIn(1, 5), 1..5, { if (it == 1) "Once" else "$it times" }) { h = h.copy(perDay = it) }
        }
        Text("Icon", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HabitIcons.all.forEach { (name, icon) ->
                val on = h.icon == name
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { h = h.copy(icon = name) }
                        .semantics { contentDescription = "Icon $name"; selected = on },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text("A nudge if it isn't done", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(h.reminderTime == null, { h = h.copy(reminderTime = null) }, label = { Text("No nudge") })
            (REMINDER_TIMES + listOfNotNull(h.reminderTime?.takeIf { it !in REMINDER_TIMES })).forEach { t ->
                FilterChip(h.reminderTime == t, { h = h.copy(reminderTime = t) }, label = { Text(t) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(h.copy(name = h.name.trim())) }, enabled = h.name.isNotBlank()) { Text("Save") }
            onDelete?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
