package com.health.app.ui.meditation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Insight
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationSession
import com.health.api.MeditationSettings
import com.health.api.MeditationSummary
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.deleteMeditationSession
import com.health.app.net.meditationSessions
import com.health.app.net.meditationSettings
import com.health.app.net.meditationSummary
import com.health.app.net.meditations
import com.health.app.net.saveMeditationSettings
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.Tile
import com.health.app.ui.components.TitledCard
import com.health.app.ui.components.ValueRow
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * The Meditation page (route "meditation"): this week's minutes and days, with what the nights
 * after meditating were like when the server has enough to say; the five meditations, each in 5,
 * 10 or 15 minutes, the one used last first; recent sessions; and the nightly reminder. Arranged
 * under android.meditation. Start opens the player (MeditationActivity). While viewing a share,
 * only what's shared shows (the summary and sessions), read-only.
 */

data class MeditationPageState(
    val meditations: List<Meditation> = emptyList(),
    val summary: MeditationSummary? = null,
    val sessions: List<MeditationSession>? = null,
    /** The person's own; never while viewing a share. */
    val settings: MeditationSettings? = null,
    /** The one used last, when the summary hasn't said. */
    val lastId: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val savingSettings: Boolean = false,
)

object MeditationSections {
    const val SUMMARY = "summary"
    const val MEDITATIONS = "meditations"
    const val RECENT = "recent"
    const val REMINDER = "reminder"

    /** The page's sections, in the default order (saved ids: never rename one). Viewing a share, only what's shared. */
    fun of(readOnly: Boolean): List<ArrangeItem> = buildList {
        add(ArrangeItem(SUMMARY, "This week"))
        if (!readOnly) add(ArrangeItem(MEDITATIONS, "Meditations"))
        add(ArrangeItem(RECENT, "Recent"))
        if (!readOnly) add(ArrangeItem(REMINDER, "Reminder"))
    }
}

class MeditationViewModel(private val c: Container) : ViewModel() {
    private val store = MeditationStore(c.context)
    private val _state = MutableStateFlow(MeditationPageState(meditations = store.meditations(), lastId = store.lastId, settings = store.settings()))
    val state: StateFlow<MeditationPageState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val viewing = c.viewing.value != null
        _state.update { it.copy(loading = !quiet || it.meditations.isEmpty(), error = null) }
        viewModelScope.launch {
            val ui = c.ui
            // The scripts are the same for everyone; kept, so the player works without the server.
            val scripts = async { attempt { c.api.meditations() }.onSuccess(store::saveMeditations) }
            val summary = async { attempt { ui.meditationSummary(30) } }
            val sessions = async { attempt { ui.meditationSessions(30) } }
            val settings = async { if (viewing) null else attempt { c.api.meditationSettings() } }
            val loaded = scripts.await()
            val s = summary.await()
            val list = sessions.await()
            val own = settings.await()
            own?.getOrNull()?.let { c.meditationSettingsLoaded(it) }
            _state.update { st ->
                val failure = listOfNotNull(loaded.exceptionOrNull(), s.exceptionOrNull(), list.exceptionOrNull()).firstOrNull()
                st.copy(
                    meditations = loaded.getOrNull()?.takeIf { it.isNotEmpty() } ?: st.meditations,
                    summary = s.getOrNull() ?: st.summary,
                    sessions = list.getOrNull() ?: st.sessions,
                    settings = if (viewing) null else own?.getOrNull() ?: st.settings,
                    lastId = store.lastId,
                    loading = false,
                    error = failure?.message?.takeIf { st.summary == null && s.isFailure || st.meditations.isEmpty() && loaded.isFailure },
                )
            }
        }
    }

    fun delete(session: MeditationSession) {
        val id = session.id ?: return
        viewModelScope.launch {
            try {
                c.api.deleteMeditationSession(id)
                _state.update { it.copy(sessions = it.sessions?.filterNot { s -> s.id == id }) }
                c.tell("Deleted")
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't delete that")
            }
        }
    }

    fun saveSettings(settings: MeditationSettings) {
        val before = _state.value.settings
        _state.update { it.copy(settings = settings, savingSettings = true) }
        viewModelScope.launch {
            try {
                val saved = c.api.saveMeditationSettings(settings)
                _state.update { it.copy(settings = saved, savingSettings = false) }
                c.meditationSettingsLoaded(saved)
                c.tell(if (saved.reminder) "Reminder set" else "Reminder off")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(settings = before, savingSettings = false) }
                c.tell(e.message ?: "Couldn't save the reminder")
            }
        }
    }
}

/** What the page can do, as callbacks (so it can be drawn with sample data). */
class MeditationActions(
    val back: () -> Unit = {},
    val start: (Meditation) -> Unit = {},
    val delete: (MeditationSession) -> Unit = {},
    val saveSettings: (MeditationSettings) -> Unit = {},
    val retry: () -> Unit = {},
)

/** The page; with [play] (a meditation's id, from Today's "Wind down" or Care), its player opens straight away. */
@Composable
fun MeditationRoute(play: String?, onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MeditationViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val viewing by c.viewing.collectAsState()
    val readOnly = LocalReadOnly.current || viewing != null
    var pending by remember { mutableStateOf<String?>(null) }
    // The player's ongoing notification needs permission (Android 13 and later); it plays either way.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { MeditationActivity.open(context, it) }
        pending = null
    }
    val start = { id: String ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !c.reminders.canNotify()) {
            pending = id
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MeditationActivity.open(context, id)
        }
    }
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(play) {
        if (play != null && !opened && !readOnly) {
            opened = true
            start(play)
        }
    }
    val askReminder = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val zone = c.zone()
    MeditationScreen(
        state, zone, LocalDate.now(zone), readOnly,
        MeditationActions(
            back = onBack,
            start = { start(it.id) },
            delete = vm::delete,
            saveSettings = { s ->
                if (s.reminder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !c.reminders.canNotify()) askReminder.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.saveSettings(s)
            },
            retry = { vm.load() },
        ),
    )
}

@Composable
fun MeditationScreen(state: MeditationPageState, zone: ZoneId, today: LocalDate, readOnly: Boolean, actions: MeditationActions) {
    val sections = MeditationSections.of(readOnly)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(actions.back, "Meditation", subtitle = "Guided, for winding down at night") {
            if (!readOnly) ArrangeAction(FeatureScreens.MEDITATION, "Meditation", sections)
        }
        when {
            state.loading && state.meditations.isEmpty() && state.summary == null -> Loading()
            state.error != null && state.meditations.isEmpty() && state.summary == null ->
                ErrorCard(state.error, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry)
            else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                Sections(arrangedIn(FeatureScreens.MEDITATION, sections)) { id ->
                    when (id) {
                        MeditationSections.SUMMARY -> SummaryCard(state.summary, state.sessions, today)
                        MeditationSections.MEDITATIONS -> MeditationsCard(state.meditations, state.summary?.lastMeditationId ?: state.lastId, actions.start)
                        MeditationSections.RECENT -> RecentCard(state.sessions, zone, today, readOnly, actions.delete)
                        MeditationSections.REMINDER -> ReminderCard(state.settings, state.savingSettings, actions.saveSettings)
                    }
                }
            }
        }
    }
}

/** This week: minutes and days; and, when the server has enough nights to say, what sleep was like after. */
@Composable
private fun SummaryCard(summary: MeditationSummary?, sessions: List<MeditationSession>?, today: LocalDate) {
    val (minutes, days) = MeditationWords.week(summary, sessions, today)
    TitledCard("This week") {
        if (minutes <= 0.0) {
            Text(MeditationWords.NOTHING_YET, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(MeditationWords.minutes(minutes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                days?.let { Text(MeditationWords.days(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        summary?.insights.orEmpty().forEach { InsightLine(it) }
    }
}

@Composable
private fun InsightLine(insight: Insight) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(insight.title, style = MaterialTheme.typography.titleSmall)
        Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The five meditations, each with its line and a length to choose; the one used last first. */
@Composable
private fun MeditationsCard(meditations: List<Meditation>, lastId: String?, onStart: (Meditation) -> Unit) {
    if (meditations.isEmpty()) {
        TitledCard("Meditations") {
            Text("The meditations come from your server. Connect once and they'll be here, even offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val chosen = remember { mutableStateMapOf<MeditationKind, Int>() }
    val kinds = MeditationChoice.kinds(meditations, lastId)
    TitledCard("Meditations", note = "Read aloud on your phone, offline") {
        kinds.forEachIndexed { i, kind ->
            if (i > 0) RowDivider(inset = 0.dp)
            val minutes = chosen[kind] ?: MeditationChoice.minutes(meditations, kind, lastId)
            val meditation = MeditationChoice.find(meditations, kind, minutes) ?: return@forEachIndexed
            MeditationRow(meditation, MeditationChoice.lengths(meditations, kind), minutes, onMinutes = { chosen[kind] = it }, onStart = { onStart(meditation) })
        }
    }
}

@Composable
private fun MeditationRow(meditation: Meditation, lengths: List<Int>, minutes: Int, onMinutes: (Int) -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tile(Icons.Rounded.SelfImprovement, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meditation.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(meditation.about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lengths.size > 1) {
                SegmentedChoice(lengths, minutes, { "$it min" }, onMinutes, Modifier.weight(1f))
            } else {
                Text("${meditation.minutes} min", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onStart,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Start ${meditation.title}, ${meditation.minutes} minutes" },
            ) { Text("Start", maxLines = 1) }
        }
    }
}

/** The last month's sessions, newest first, each with Delete. */
@Composable
private fun RecentCard(sessions: List<MeditationSession>?, zone: ZoneId, today: LocalDate, readOnly: Boolean, onDelete: (MeditationSession) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MeditationSession?>(null) }
    TitledCard("Recent") {
        when {
            sessions == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sessions.isEmpty() -> EmptyState(Icons.Rounded.SelfImprovement, "Nothing here yet", "Each meditation you do shows here, and on Today.")
            else -> {
                val shown = if (all) sessions else sessions.take(RECENT)
                shown.forEachIndexed { i, s ->
                    if (i > 0) RowDivider(inset = 0.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(MeditationWords.title(s), style = MaterialTheme.typography.bodyLarge)
                            Text(MeditationWords.detail(s, zone, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!readOnly && s.id != null) {
                            IconButton(onClick = { deleting = s }) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete ${MeditationWords.title(s)}, ${MeditationWords.detail(s, zone, today)}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (sessions.size > RECENT) {
                    TextButton(onClick = { all = !all }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (all) "Show fewer" else "Show all ${sessions.size}") }
                }
            }
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this session?") },
            text = { Text("${MeditationWords.title(s)}, ${MeditationWords.detail(s, zone, today)}. It goes from here and from Today.") },
            confirmButton = { TextButton(onClick = { onDelete(s); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

private const val RECENT = 6

/** The nightly reminder: on or off, and its time. */
@Composable
private fun ReminderCard(settings: MeditationSettings?, saving: Boolean, onSave: (MeditationSettings) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val current = settings ?: MeditationSettings()
    val time = MeditationReminderPlan.parse(current.time) ?: LocalTime.of(21, 30)
    TitledCard("Reminder") {
        if (settings == null) {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@TitledCard
        }
        LabeledSwitch(
            "Remind me to wind down", current.reminder, { onSave(current.copy(reminder = it)) },
            subtitle = "A gentle \"Time to wind down\" each night", enabled = !saving,
        )
        if (current.reminder) {
            ValueRow("At", DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time), onClick = { if (!saving) picking = true })
        }
    }
    if (picking) {
        TimePickDialog(time, "Remind me at", onDismiss = { picking = false }) { t ->
            picking = false
            onSave(current.copy(time = "%02d:%02d".format(t.hour, t.minute)))
        }
    }
}
