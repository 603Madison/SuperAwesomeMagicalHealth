@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.DataDestination
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathState
import com.health.app.Container
import com.health.app.attempt
import com.health.app.data.DeviceChoices
import com.health.app.devices.healthconnect.ExportKind
import com.health.app.net.dataMap
import com.health.app.net.pauseDataPath
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.StatusDot
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

/*
 * You › Where your data goes: each way something about the person can leave their server, as
 * the server lists it (GET /api/privacy), with a switch to pause or resume the ones that are set
 * up; then what this phone does (its Bluetooth devices, Health Connect, Gadgetbridge, and that the
 * app talks only to the server). Arranged under android.privacy. Only ever the person's own.
 */

data class WhereState(
    val map: DataMap? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The ways out being paused or resumed just now. */
    val busy: Set<DataPath> = emptySet(),
)

class WhereDataGoesViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(WhereState())
    val state: StateFlow<WhereState> = _state.asStateFlow()

    fun load() {
        // The person's own: never asked about while viewing a share.
        if (c.viewing.value != null) return _state.update { it.copy(loading = false) }
        _state.update { it.copy(loading = it.map == null, error = null) }
        viewModelScope.launch {
            try {
                val map = c.api.dataMap()
                _state.update { it.copy(map = map, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach your server") }
            }
        }
    }

    /** Lets [path] send again ([on]) or pauses it: shown at once, then as the server says. */
    fun set(path: DataPath, on: Boolean) {
        val before = _state.value.map ?: return
        if (path in _state.value.busy || c.viewing.value != null) return
        _state.update { it.copy(map = WhereDataGoes.pausing(before, path, paused = !on), busy = it.busy + path) }
        viewModelScope.launch {
            attempt { c.api.pauseDataPath(path, paused = !on) }
                .onSuccess { map ->
                    _state.update { it.copy(map = map, busy = it.busy - path) }
                    // Food's meal plan, the assistant and the rest follow it.
                    c.dataChanged()
                }
                .onFailure { e ->
                    _state.update { it.copy(map = before, busy = it.busy - path) }
                    c.tell(e.message ?: "Couldn't change that")
                }
        }
    }
}

class WhereActions(
    val back: () -> Unit = {},
    /** Pauses (false) or resumes (true) a way out. */
    val set: (DataPath, Boolean) -> Unit = { _, _ -> },
    /** Opens one of the app's pages (where something is set up). */
    val open: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun WhereDataGoesRoute(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val vm = viewModel { WhereDataGoesViewModel(c) }
    val state by vm.state.collectAsState()
    val readOnly = LocalReadOnly.current
    // Each time the page shows: something may have been set up or paused elsewhere.
    LaunchedEffect(readOnly) { if (!readOnly) vm.load() }
    val settings by c.settings.collectAsState()
    val ring by c.syncStore.ring.collectAsState()
    val healthConnect by c.syncStore.healthConnectOn.collectAsState()
    val gadgetbridge by c.syncStore.gadgetbridgeUri.collectAsState()
    val choices = remember { DeviceChoices.of(context) }
    val exports by choices.healthConnectExport.collectAsState()
    val gadgetbridgeFile = remember(gadgetbridge) { gadgetbridge?.let { c.gadgetbridge.fileName() ?: "Export file" } }
    val phone = WhereDataGoes.Phone(
        server = settings.server,
        ring = ring?.name,
        healthConnect = healthConnect,
        writes = ExportKind.entries.filter { it.name in exports }.map { it.label },
        gadgetbridgeFile = gadgetbridgeFile,
    )
    WhereDataGoesScreen(state, phone, WhereActions(back = onBack, set = vm::set, open = onOpen, retry = vm::load))
}

/**
 * The page: what it's for, then its two sections in the person's order ([FeatureScreens.PRIVACY]):
 * the ways out from the server (with the server's note after them), and this phone.
 */
@Composable
fun WhereDataGoesScreen(state: WhereState, phone: WhereDataGoes.Phone, actions: WhereActions) {
    val readOnly = LocalReadOnly.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(actions.back, WhereDataGoes.TITLE) {
            if (!readOnly) ArrangeAction(FeatureScreens.PRIVACY, WhereDataGoes.TITLE, WhereDataGoes.SECTIONS)
        }
        if (readOnly) {
            EmptyState(Icons.Rounded.Lock, "Only for your own data", "Go back to your own data to see where it goes.")
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sections(arrangedIn(FeatureScreens.PRIVACY, WhereDataGoes.SECTIONS)) { id ->
                when (id) {
                    WhereDataGoes.SERVER -> FromServer(state, actions)
                    WhereDataGoes.PHONE -> OnThisPhone(phone, actions)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

/** The server's list: what it's for, each way out, and what stays put. */
@Composable
private fun FromServer(state: WhereState, actions: WhereActions) {
    val map = state.map
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("From your server")
        when {
            state.loading && map == null -> Loading()
            map == null -> ErrorCard(state.error ?: "Couldn't reach your server", actionLabel = "Try again", onAction = actions.retry)
            else -> {
                InfoCard(null, icon = Icons.Rounded.Shield) {
                    Text(WhereDataGoes.INTRO, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(WhereDataGoes.summary(map), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                map.destinations.forEach { d -> DestinationCard(d, d.path in state.busy, actions) }
                InfoCard("Everything else", icon = Icons.Rounded.Lock, iconTint = AppTheme.status.good) {
                    Text(map.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** How a way out stands, as a colour: on in the theme's colour, paused amber, the rest quiet. */
@Composable
private fun stateColor(state: DataPathState): Color = when (state) {
    DataPathState.ON -> MaterialTheme.colorScheme.primary
    DataPathState.PAUSED -> AppTheme.status.warn
    DataPathState.NOT_SET_UP, DataPathState.OFF_ON_SERVER -> AppTheme.status.idle
}

/** One way out: who, where, what and when, how it stands, and its switch (or where to set it up). */
@Composable
private fun DestinationCard(d: DataDestination, busy: Boolean, actions: WhereActions) {
    val switchable = WhereDataGoes.switchable(d)
    val on = d.state == DataPathState.ON
    AppCard {
        Row(
            Modifier.fillMaxWidth().then(
                if (switchable) Modifier.toggleable(value = on, enabled = !busy, role = Role.Switch) { actions.set(d.path, it) } else Modifier,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(d.who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (switchable) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = null, enabled = !busy)
            }
        }
        if (d.hosts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.hosts.forEach { Host(it) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(d.what, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(d.whenSent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(stateColor(d.state), size = 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(WhereDataGoes.stateLine(d), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (d.state == DataPathState.NOT_SET_UP) {
            WhereDataGoes.link(d.setUp)?.let { link ->
                TextButton(onClick = { actions.open(link.route) }, modifier = Modifier.padding(top = 2.dp).offset(x = (-12).dp)) { Text(link.label) }
            }
        }
    }
}

/** An address it goes to, small and exact. */
@Composable
private fun Host(host: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(
            host, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** What this phone does with what it reads, and the one place it talks to. */
@Composable
private fun OnThisPhone(phone: WhereDataGoes.Phone, actions: WhereActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("On this phone")
        InfoCard("Bluetooth devices", icon = Icons.Rounded.Bluetooth) {
            Text(WhereDataGoes.BLUETOOTH, style = MaterialTheme.typography.bodyMedium)
            phone.ring?.let { Hint("Paired here: $it", Modifier.padding(top = 6.dp)) }
            TextButton(onClick = { actions.open("devices") }, modifier = Modifier.padding(top = 2.dp).offset(x = (-12).dp)) { Text("Open Devices") }
        }
        InfoCard("Health Connect", icon = Icons.Rounded.HealthAndSafety) {
            Text(WhereDataGoes.healthConnectRead(phone.healthConnect), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(WhereDataGoes.healthConnectWrites(phone.writes), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp).offset(x = (-12).dp)) {
                TextButton(onClick = { actions.open("devices") }) { Text("Reading: Devices") }
                TextButton(onClick = { actions.open("settings/export") }) { Text("Writing: $SEND_TO_APPS") }
            }
        }
        InfoCard("Gadgetbridge", icon = Icons.Rounded.Description) {
            Text(WhereDataGoes.gadgetbridge(phone.gadgetbridgeFile), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { actions.open("devices") }, modifier = Modifier.padding(top = 2.dp).offset(x = (-12).dp)) { Text("Open Devices") }
        }
        InfoCard(WhereDataGoes.ONLY_SERVER, icon = Icons.Rounded.Dns, iconTint = AppTheme.status.good) {
            Text(
                phone.server.ifBlank { "Not signed in" },
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "Your server: ${phone.server}" },
            )
            Spacer(Modifier.height(6.dp))
            Text(WhereDataGoes.ONLY_SERVER_MORE, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Where something set up is paused in Where your data goes: one line saying so ([text]), and a way
 * there ([onOpen]) where the page can go.
 */
@Composable
fun PausedNote(text: String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.PauseCircle, null, tint = AppTheme.status.warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = "Open ${WhereDataGoes.TITLE}" }) { Text("Open") }
        }
    }
}
