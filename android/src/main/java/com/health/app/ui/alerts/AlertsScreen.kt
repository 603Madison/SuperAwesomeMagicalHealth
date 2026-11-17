@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.alerts

import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.AlertFeedback
import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.alerts
import com.health.app.net.alertsSeen
import com.health.app.net.dismissAlert
import com.health.app.net.saveAlertSettings
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/*
 * Health alerts (GET /api/alerts): what the server noticed in the person's readings, newest first
 * (one that could need care now on top), each saying how soon to act. One that could need care
 * now leads with what to do, in the theme's danger colours. Opening the page marks them seen;
 * putting one away keeps it in the list, quieter, with why. Then what it's watching, and the
 * settings, arranged under android.alerts. Only ever the person's own: never while viewing a share.
 */

data class AlertsState(
    val data: HealthAlerts? = null,
    /** In the order shown ([Alerts.newestFirst] when loaded; one put away stays where it was). */
    val alerts: List<HealthAlert> = emptyList(),
    /** What was new when the page opened: still "New" on this visit, though it's marked seen at once. */
    val fresh: Set<String> = emptySet(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

class AlertsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(AlertsState())
    val state: StateFlow<AlertsState> = _state.asStateFlow()
    private var marked = false

    init {
        load()
    }

    fun load(quiet: Boolean = false) {
        // Never asked for someone else's: alerts are only ever the person's own.
        if (c.viewing.value != null) return _state.update { it.copy(loading = false, refreshing = false) }
        _state.update { it.copy(loading = !quiet || it.data == null, error = null) }
        viewModelScope.launch {
            try {
                val data = c.api.alerts(Alerts.DAYS)
                c.alertsLoaded(data, Alerts.DAYS)
                val fresh = if (marked) _state.value.fresh else Alerts.unseenIds(data.alerts)
                _state.update { it.copy(data = data, alerts = Alerts.newestFirst(data.alerts), fresh = fresh, loading = false, refreshing = false) }
                if (!marked) {
                    marked = true
                    if (fresh.isNotEmpty()) markSeen(data.alerts.filter { it.id in fresh })
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load your health alerts") }
            }
        }
    }

    /** Opening the page marks them all seen: Today's bell goes, and so do their notifications (an urgent one stays until swiped). */
    private fun markSeen(seen: List<HealthAlert>) {
        c.alertsSeen()
        attempt { c.alertNotices.seen(seen) }
        // In the app's scope: leaving the page at once mustn't stop it.
        c.scope.launch { attempt { c.api.alertsSeen() }.onFailure { Log.w(TAG, "Couldn't mark the alerts seen: ${it.javaClass.simpleName}") } }
    }

    fun putAway(alert: HealthAlert, feedback: AlertFeedback?) {
        viewModelScope.launch {
            try {
                val changed = c.api.dismissAlert(alert.id, feedback)
                _state.update { it.copy(alerts = Alerts.replace(it.alerts, changed)) }
                c.alertsSeen(alert.id)
                attempt { c.alertNotices.cancel(alert) }
                c.tell("Put away. It stays in the list, in case you want it later.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't put it away")
            }
        }
    }

    fun saveSettings(settings: AlertSettings) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = c.api.saveAlertSettings(settings)
                _state.update { it.copy(data = it.data?.copy(settings = saved), saving = false) }
                c.tell("Saved")
                // What it's watching follows the settings.
                load(quiet = true)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                c.tell(e.message ?: "Couldn't save your alert settings")
            }
        }
    }

    /** Pull to refresh: brings in the devices' latest, then looks again. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            attempt { c.sync.sync() }
            load(quiet = true)
        }
    }

    private companion object {
        const val TAG = "Alerts"
    }
}

/** What the page can do, as callbacks (so it can be drawn with sample data). */
class AlertsActions(
    val back: () -> Unit = {},
    val putAway: (HealthAlert, AlertFeedback?) -> Unit = { _, _ -> },
    val saveSettings: (AlertSettings) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun AlertsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { AlertsViewModel(c) }
    val state by vm.state.collectAsState()
    AlertsScreen(state, c.zone(), AlertsActions(back = onBack, putAway = vm::putAway, saveSettings = vm::saveSettings, refresh = vm::refresh, retry = { vm.load() }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(state: AlertsState, zone: ZoneId, actions: AlertsActions, today: LocalDate = LocalDate.now(zone)) {
    val readOnly = LocalReadOnly.current
    val data = state.data
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { if (!readOnly) actions.refresh() }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            BackBar(actions.back, "Health alerts", subtitle = "When your readings look out of the ordinary") {
                if (!readOnly && data != null) ArrangeAction(FeatureScreens.ALERTS, "Health alerts", Alerts.SECTIONS)
            }
            when {
                readOnly -> EmptyState(Icons.Rounded.Lock, "Only for your own data", "Health alerts are only ever your own. Go back to your own data to see them.")
                state.loading && data == null -> Loading()
                state.error != null && data == null -> ErrorCard(state.error, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry)
                data != null -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                    if (data.limits.isNotBlank()) Limits(data.limits)
                    Sections(arrangedIn(FeatureScreens.ALERTS, Alerts.SECTIONS)) { id ->
                        when (id) {
                            "alerts" -> AlertList(data, state.alerts, state.fresh, zone, today, actions.putAway)
                            "watching" -> WatchingCard(data.watching)
                            "settings" -> SettingsCard(data.settings, state.saving, actions.saveSettings)
                        }
                    }
                }
            }
        }
    }
}

/** What alerts can and can't do, in plain words and plain view. */
@Composable
private fun Limits(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AlertList(data: HealthAlerts, alerts: List<HealthAlert>, fresh: Set<String>, zone: ZoneId, today: LocalDate, putAway: (HealthAlert, AlertFeedback?) -> Unit) {
    if (alerts.isEmpty()) {
        InfoCard(null) {
            EmptyState(Icons.Rounded.MonitorHeart, "Nothing out of the ordinary", Alerts.emptyText(data.settings, data.watching)) {
                val watched = data.watching.filter { it.watching }
                if (data.settings.enabled && watched.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        watched.forEach { Pill(it.what, AppTheme.status.good) }
                    }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        alerts.forEach { a -> AlertCard(a, a.id in fresh, zone, today) { feedback -> putAway(a, feedback) } }
    }
}

/** A level's colour: the theme's danger colour for one that could need care now; quiet once put away. */
@Composable
private fun levelColor(level: AlertLevel, away: Boolean): Color = when {
    away -> AppTheme.status.idle
    level == AlertLevel.URGENT -> MaterialTheme.colorScheme.error
    level == AlertLevel.DOCTOR -> AppTheme.status.warn
    else -> AppTheme.status.info
}

/** One alert: how soon to act, what happened and when, the readings behind it, and what to do. */
@Composable
private fun AlertCard(a: HealthAlert, isNew: Boolean, zone: ZoneId, today: LocalDate, onPutAway: (AlertFeedback?) -> Unit) {
    val away = Alerts.isAway(a)
    val urgent = a.level == AlertLevel.URGENT && !away
    var showDetails by remember(a.id) { mutableStateOf(false) }
    InfoCard(null) {
        if (urgent) {
            UrgentAdvice(a.advice)
            Spacer(Modifier.height(12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(Alerts.levelText(a.level), levelColor(a.level, away))
            if (isNew && !away) Pill("New", MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            a.title, style = MaterialTheme.typography.titleMedium,
            color = if (away) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(Alerts.whenText(a, zone, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (away) {
            Spacer(Modifier.height(6.dp))
            Text(Alerts.awayText(a), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide what it said" else "What it said") }
            if (showDetails) Details(a, advice = true)
        } else {
            Spacer(Modifier.height(10.dp))
            Details(a, advice = !urgent)
            Spacer(Modifier.height(6.dp))
            PutAway(a.title, onPutAway)
        }
    }
}

/** What to do now, first and in the theme's danger colours (errorContainer and its text: checked for contrast, light and dark). */
@Composable
private fun UrgentAdvice(advice: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Rounded.Phone, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("What to do now", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(advice, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Details(a: HealthAlert, advice: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(a.body, style = MaterialTheme.typography.bodyMedium)
        if (a.evidence.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.evidence.forEach { r ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(r.value, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Alerts.devicesText(a.devices)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (advice) {
            Column {
                Text("What to do", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(a.advice, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Put away, with why (or without saying). */
@Composable
private fun PutAway(title: String, onPutAway: (AlertFeedback?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Put away") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text(
                "Why are you putting “$title” away?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AlertFeedback.entries.forEach { f -> DropdownMenuItem(text = { Text(f.label) }, onClick = { open = false; onPutAway(f) }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Put it away without saying") }, onClick = { open = false; onPutAway(null) })
        }
    }
}

/** What the alerts can watch with the person's devices, and how. */
@Composable
private fun WatchingCard(watching: List<AlertWatch>) {
    InfoCard("What it's watching", icon = Icons.Rounded.Radar) {
        if (watching.isEmpty()) {
            Text("Nothing yet: readings from a device bring it in.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        watching.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val state = Alerts.watchState(w)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.what, style = MaterialTheme.typography.bodyLarge)
                    Text(w.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Pill(state.text, if (state == Alerts.WatchState.ON) AppTheme.status.good else AppTheme.status.idle)
            }
        }
    }
}

/** A number within its range, a step at a time, with what it's for. */
@Composable
private fun NumberSetting(title: String, help: String, value: Int, range: Alerts.Range, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(enabled = value > range.min, onClick = { onChange(range.step(value, -1)) }) { Icon(Icons.Rounded.Remove, "Lower: $title") }
        Text(if (range.unit == "%") "$value%" else "$value", style = MaterialTheme.typography.titleMedium)
        IconButton(enabled = value < range.max, onClick = { onChange(range.step(value, 1)) }) { Icon(Icons.Rounded.Add, "Higher: $title") }
    }
}

/** The person's alert settings, saved together. */
@Composable
private fun SettingsCard(initial: AlertSettings, saving: Boolean, onSave: (AlertSettings) -> Unit) {
    var s by remember(initial) { mutableStateOf(initial) }
    val changed = s != initial
    InfoCard("Settings", icon = Icons.Rounded.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledSwitch("Health alerts", s.enabled, { s = s.copy(enabled = it) }, subtitle = "Watch your readings and say when something looks out of the ordinary for you.")
            if (s.enabled) {
                NumberSetting(
                    "High heart rate at rest", "When it stays at or above this for 10 minutes while you're resting (${Alerts.HIGH_HEART_RATE.text}).",
                    s.highHeartRate, Alerts.HIGH_HEART_RATE,
                ) { s = s.copy(highHeartRate = it) }
                NumberSetting(
                    "Low heart rate while awake", "When it stays at or below this for 10 minutes while you're awake (${Alerts.LOW_HEART_RATE.text}).",
                    s.lowHeartRate, Alerts.LOW_HEART_RATE,
                ) { s = s.copy(lowHeartRate = it) }
                NumberSetting("Low blood oxygen", "When readings stay at or below this (${Alerts.LOW_OXYGEN.text}).", s.lowOxygen, Alerts.LOW_OXYGEN) { s = s.copy(lowOxygen = it) }
                LabeledSwitch("Signs of getting ill", s.illness, { s = s.copy(illness = it) }, subtitle = "Resting heart rate, HRV, temperature and breathing together, against your own usual.")
                LabeledSwitch("Heart rhythm", s.rhythm, { s = s.copy(rhythm = it) }, subtitle = "From a chest strap's beat-to-beat timing. A ring or watch can't tell.")
                LabeledSwitch(
                    "Tell me by notification", s.notify, { s = s.copy(notify = it) },
                    subtitle = "On this phone, and in your browser if its notifications are on. Off, they still show here and on Today.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onSave(s.clamped()) }, enabled = changed && !saving) { Text(if (saving) "Saving…" else "Save") }
                if (changed) TextButton(onClick = { s = initial }) { Text("Undo changes") }
            }
        }
    }
}
