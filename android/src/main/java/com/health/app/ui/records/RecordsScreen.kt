@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.records

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Vaccines
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.HealthRecords
import com.health.api.Medication
import com.health.api.Module
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordFlag
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordOrganization
import com.health.api.RecordProvider
import com.health.api.Role as UserRole
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.confirmRecords
import com.health.app.net.connectRecords
import com.health.app.net.disconnectRecords
import com.health.app.net.recordItems
import com.health.app.net.recordOrganizations
import com.health.app.net.records
import com.health.app.net.syncRecords
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.settings.PausedNote
import com.health.app.ui.settings.WhereDataGoes
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * You › Medical records: a hospital, clinic or practice's own record of the person, from its patient
 * portal (Epic's MyChart, athenahealth). Connecting opens the portal's own sign-in in a browser tab;
 * the portal sends the phone back to samhealth://records (MainActivity), which lands here with
 * "Connected" or what went wrong. Each portal is a card; the record follows a kind at a time,
 * arranged under android.records with the web's section ids. Only ever the person's own.
 */

data class RecordsState(
    val records: HealthRecords? = null,
    val items: List<RecordItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Organizations matching the search; null before one. */
    val results: List<RecordOrganization>? = null,
    val searching: Boolean = false,
    /** The portals being connected, updated or disconnected just now (by address or id). */
    val busy: Set<String> = emptySet(),
    /** The names of the person's own medications, to say "In your medications". */
    val myMeds: Set<String> = emptySet(),
    /** Whether Medications is on, so a record's medication can be added there. */
    val medsOn: Boolean = true,
    /** The person is this server's admin (who sets medical records up). */
    val admin: Boolean = false,
)

/** What the portal said on its way back, for the page to tell and to load again. */
object RecordsReturn {
    val pending = MutableStateFlow<RecordsLogic.Return?>(null)
}

class RecordsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(RecordsState())
    val state: StateFlow<RecordsState> = _state.asStateFlow()
    private var search: Job? = null

    fun load() {
        // Always the person's own: never asked while viewing a share.
        if (c.viewing.value != null) return _state.update { it.copy(loading = false) }
        _state.update {
            it.copy(
                loading = it.records == null, error = null, medsOn = Module.MEDICATIONS in c.modules(),
                admin = c.settings.value.user?.role == UserRole.ADMIN,
            )
        }
        viewModelScope.launch {
            attempt {
                val records = async { c.api.records() }
                val items = async { attempt { c.api.recordItems() }.getOrDefault(emptyList()) }
                val meds = async { attempt { c.api.medications(all = true).map { it.name } }.getOrDefault(emptyList()) }
                Triple(records.await(), items.await(), meds.await())
            }.onSuccess { (records, items, meds) ->
                _state.update { it.copy(records = records, items = items, myMeds = meds.toSet(), loading = false) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach your server") }
            }
        }
    }

    /**
     * Finds organizations: Epic's need a word or two; athenahealth's one entry comes without (when
     * it's set up), so it's offered before any search.
     */
    fun search(query: String) {
        search?.cancel()
        val q = query.trim().takeIf { it.length >= 2 }.orEmpty()
        val athena = RecordProvider.ATHENA in _state.value.records?.providers.orEmpty()
        if (q.isEmpty() && !athena) return _state.update { it.copy(results = null, searching = false) }
        _state.update { it.copy(searching = q.isNotEmpty()) }
        search = viewModelScope.launch {
            if (q.isNotEmpty()) delay(300)
            attempt { c.api.recordOrganizations(q) }
                .onSuccess { found -> _state.update { it.copy(results = found, searching = false) } }
                .onFailure { e -> _state.update { it.copy(searching = false) }; c.tell(e.message ?: "Couldn't search just now") }
        }
    }

    /** Asks the server for the portal's sign-in, then opens it in a browser tab. */
    fun connect(connect: RecordConnect) {
        val key = connect.fhirBaseUrl
        if (key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            attempt { c.api.connectRecords(connect.copy(app = true)) }
                .onSuccess { auth ->
                    attempt {
                        CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            .launchUrl(c.context, auth.url.toUri())
                    }.onFailure { c.tell("Couldn't open a browser for the sign-in") }
                }
                .onFailure { e -> c.tell(e.message ?: "Couldn't start connecting") }
            _state.update { it.copy(busy = it.busy - key) }
        }
    }

    /** The portal sent the phone back: confirm the sign-in where it asks, then say how it went and load again. */
    fun back(back: RecordsLogic.Return) {
        viewModelScope.launch {
            when {
                !back.error.isNullOrBlank() -> c.tell(back.error)
                !back.confirm.isNullOrBlank() -> attempt { c.api.confirmRecords(back.confirm) }
                    .onSuccess { c.tell("Connected"); c.dataChanged() }
                    .onFailure { e -> c.tell(e.message ?: "Couldn't finish connecting. Try again.") }
                !back.connected.isNullOrBlank() -> { c.tell("Connected"); c.dataChanged() }
            }
            load()
        }
    }

    fun sync(c0: RecordConnection) = act(c0.id, "${c0.organization}: up to date") { c.api.syncRecords(c0.id) }

    fun disconnect(c0: RecordConnection, keep: Boolean) =
        act(c0.id, if (keep) "Disconnected ${c0.organization}; what it brought in is kept" else "Disconnected ${c0.organization}") { c.api.disconnectRecords(c0.id, keep) }

    /** Removes a disconnected portal, and all it brought in. */
    fun remove(c0: RecordConnection) = act(c0.id, "Removed ${c0.organization}") { c.api.disconnectRecords(c0.id, keep = false) }

    private fun act(id: String, done: String, block: suspend () -> Unit) {
        if (id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            attempt { block() }
                .onSuccess { c.tell(done); c.dataChanged() }
                .onFailure { e -> c.tell(e.message ?: "Couldn't do that just now") }
            _state.update { it.copy(busy = it.busy - id) }
            load()
        }
    }
}

class RecordsActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val search: (String) -> Unit = {},
    val connect: (RecordConnect) -> Unit = {},
    val sync: (RecordConnection) -> Unit = {},
    val disconnect: (RecordConnection, Boolean) -> Unit = { _, _ -> },
    /** Removes a disconnected portal with all it brought in. */
    val remove: (RecordConnection) -> Unit = {},
    /** Opens the med editor with this one filled in. */
    val addMedication: (Medication) -> Unit = {},
    /** Opens one of the app's pages (Where your data goes). */
    val open: (String) -> Unit = {},
    /** Opens the server's Admin page in the browser. */
    val openAdmin: () -> Unit = {},
)

@Composable
fun RecordsRoute(onBack: () -> Unit, onOpen: (String) -> Unit, onAddMedication: (Medication) -> Unit) {
    val c = appContainer()
    val vm = viewModel { RecordsViewModel(c) }
    val state by vm.state.collectAsState()
    val revision by c.revision.collectAsState()
    val back by RecordsReturn.pending.collectAsState()
    val settings by c.settings.collectAsState()
    LaunchedEffect(revision) { vm.load() }
    // Back from the portal: confirm where asked, say how it went, and bring the page up to date.
    LaunchedEffect(back) {
        back?.let { RecordsReturn.pending.value = null; vm.back(it) }
    }
    // athenahealth's entry is offered before any search, once the page knows it's set up.
    LaunchedEffect(state.records?.providers) { if (state.results == null) vm.search("") }
    RecordsScreen(
        state,
        RecordsActions(
            back = onBack, retry = vm::load, search = vm::search, connect = vm::connect, sync = vm::sync, disconnect = vm::disconnect, remove = vm::remove,
            addMedication = { onAddMedication(RecordsLogic.draft(it)) }, open = onOpen,
            openAdmin = { attempt { c.context.startActivity(Intent(Intent.ACTION_VIEW, "${settings.server.trimEnd('/')}/admin#records".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        ),
        zone = c.zone(),
    )
}

/** The page: what it's for, where it stands, then its sections in the person's order ([FeatureScreens.RECORDS]). */
@Composable
fun RecordsScreen(state: RecordsState, actions: RecordsActions, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
    val readOnly = LocalReadOnly.current
    val records = state.records
    val ready = records != null && records.providers.isNotEmpty()
    val connected = records?.connections?.isNotEmpty() == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(actions.back, RecordsLogic.TITLE) {
            if (ready && !readOnly) ArrangeAction(FeatureScreens.RECORDS, RecordsLogic.TITLE, RecordsLogic.sections(connected))
        }
        if (readOnly) {
            EmptyState(Icons.Rounded.Lock, "Only for your own records", "Go back to your own data to see your medical records.")
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && records == null -> Loading()
                records == null -> ErrorCard(state.error ?: "Couldn't reach your server", actionLabel = "Try again", onAction = actions.retry)
                records.providers.isEmpty() -> NotSetUp(state.admin, actions)
                else -> {
                    if (records.paused) {
                        AppCard { PausedNote("Paused in ${WhereDataGoes.TITLE}: nothing is brought in until you turn it back on.", onOpen = { actions.open(WhereDataGoes.ROUTE) }) }
                    }
                    val byKind = RecordsLogic.byKind(state.items)
                    Sections(arrangedIn(FeatureScreens.RECORDS, RecordsLogic.sections(connected))) { id ->
                        if (id == RecordsLogic.CONNECTIONS) {
                            records.connections.forEach { ConnectionCard(it, records.paused, it.id in state.busy || it.fhirBaseUrl in state.busy, now, zone, actions) }
                            if (!records.paused) ConnectCard(!connected, state, actions)
                        } else {
                            val kind = RecordsLogic.kindOf(id)
                            val list = kind?.let { byKind[it] }.orEmpty()
                            if (kind != null && list.isNotEmpty()) KindCard(kind, list, state, actions)
                        }
                    }
                }
            }
        }
    }
}

/** The server can't connect anywhere yet: its admin sets it up first. */
@Composable
private fun NotSetUp(admin: Boolean, actions: RecordsActions) {
    AppCard {
        EmptyState(
            Icons.Rounded.LocalHospital, "Not set up on this server yet",
            if (admin) "Set up medical records in Admin first, on your server's web page: the app you register with Epic or athenahealth."
            else "Your server's admin hasn't set this up yet.",
            action = if (admin) ({ FilledTonalButton(onClick = actions.openAdmin) { Text("Open Admin") } }) else null,
        )
    }
}

/** Searching for a hospital, clinic or practice, and connecting to its portal. */
@Composable
private fun ConnectCard(first: Boolean, state: RecordsState, actions: RecordsActions) {
    var query by rememberSaveable { mutableStateOf("") }
    InfoCard(if (first) "Connect your patient portal" else "Connect another", icon = Icons.Rounded.LocalHospital) {
        // On the card, a field in a step darker than it, and the whole placeholder showing.
        TextField(
            query, { query = it; actions.search(it) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            placeholder = { Text(RecordsLogic.SEARCH, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        val searched = query.trim().length >= 2
        if (!searched) {
            Spacer(Modifier.height(8.dp))
            Hint(RecordsLogic.SEARCH_HINT)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(RecordsLogic.PROMISE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val results = state.results
        if (results != null && (searched || results.isNotEmpty())) {
            Spacer(Modifier.height(6.dp))
            if (results.isEmpty()) Hint("Nothing by that name. Try the health system's name, like the one on your bills.")
            results.forEachIndexed { i, o ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val busy = o.fhirBaseUrl in state.busy
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = state.busy.isEmpty()) {
                        actions.connect(RecordConnect(o.name, o.fhirBaseUrl, o.provider, app = true))
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(o.name, style = MaterialTheme.typography.titleSmall)
                        Text(o.provider.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (busy) "Opening…" else "Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** One connected portal: when it was brought up to date, how, what went wrong, what it brought, and what can be done. */
@Composable
private fun ConnectionCard(c: RecordConnection, paused: Boolean, busy: Boolean, now: Long, zone: ZoneId, actions: RecordsActions) {
    var disconnecting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // Disconnected but kept: it can be connected again (the same one comes back), or removed with all it brought.
    val gone = RecordsLogic.isDisconnected(c)
    InfoCard(c.organization, icon = Icons.Rounded.LocalHospital) {
        Hint(c.provider.label)
        Spacer(Modifier.height(6.dp))
        Text(
            if (gone) RecordsLogic.updatedText(c, now, zone) else "${RecordsLogic.updatedText(c, now, zone)} · ${RecordsLogic.keepsText(c)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        c.problem?.let { problem ->
            Spacer(Modifier.height(8.dp))
            val tint = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.status.warn
            Surface(color = if (gone) MaterialTheme.colorScheme.surfaceContainerHighest else AppTheme.status.warn.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (gone) Icons.Rounded.LinkOff else Icons.Rounded.WarningAmber, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(problem, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        RecordsLogic.countLine(c.counts)?.let {
            Spacer(Modifier.height(6.dp))
            Hint(it)
        }
        Spacer(Modifier.height(8.dp))
        if (gone) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { actions.connect(RecordsLogic.reconnect(c)) }, enabled = !busy && !paused) { Text("Connect again") }
                TextButton(onClick = { removing = true }, enabled = !busy) { Text("Remove") }
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (c.keepsUpdating) FilledTonalButton(onClick = { actions.sync(c) }, enabled = !busy && !paused) { Text("Update now") }
            if (!c.keepsUpdating || c.problem != null) {
                val signIn = { actions.connect(RecordsLogic.reconnect(c)) }
                if (c.keepsUpdating) FilledTonalButton(onClick = signIn, enabled = !busy && !paused) { Text("Sign in again") }
                else Button(onClick = signIn, enabled = !busy && !paused) { Text("Sign in again") }
            }
            TextButton(onClick = { disconnecting = true }, enabled = !busy) { Text("Disconnect") }
        }
    }
    if (disconnecting) DisconnectDialog(c, onDismiss = { disconnecting = false }) { keep -> disconnecting = false; actions.disconnect(c, keep) }
    if (removing) {
        val counts = RecordsLogic.countLine(c.counts)
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${c.organization}?") },
            text = { Text(counts?.let { "What it brought in goes too: $it, and its lab results in Medications." } ?: "What it brought in goes too, lab results in Medications included.") },
            confirmButton = { TextButton(onClick = { removing = false; actions.remove(c) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

/** Disconnecting forgets the portal's access; what it brought in goes too, unless the person keeps it. */
@Composable
private fun DisconnectDialog(c: RecordConnection, onDismiss: () -> Unit, onConfirm: (keep: Boolean) -> Unit) {
    var keep by remember { mutableStateOf(false) }
    val counts = RecordsLogic.countLine(c.counts)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${c.organization}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This server forgets its access, and nothing more is brought in from it. You can connect again any time.")
                Column(Modifier.selectableGroup()) {
                    Choice(!keep, "Remove what it brought in", counts?.let { "$it, lab results in Medications too" } ?: "Anything it brought in, lab results in Medications too") { keep = false }
                    Choice(keep, "Keep it here", "It stays, as it was when you disconnected") { keep = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(keep) }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun icon(kind: RecordKind): ImageVector = when (kind) {
    RecordKind.LAB -> Icons.Rounded.Science
    RecordKind.VITAL -> Icons.Rounded.MonitorHeart
    RecordKind.MEDICATION -> Icons.Rounded.Medication
    RecordKind.CONDITION -> Icons.Rounded.MedicalInformation
    RecordKind.ALLERGY -> Icons.Rounded.HealthAndSafety
    RecordKind.IMMUNIZATION -> Icons.Rounded.Vaccines
    RecordKind.PROCEDURE -> Icons.Rounded.ContentPaste
}

/** How many rows a kind shows before "Show all". */
private const val FIRST_ROWS = 8

/** One kind of the record, newest first: a lab's flag in words, a medication's way into Medications. */
@Composable
private fun KindCard(kind: RecordKind, items: List<RecordItem>, state: RecordsState, actions: RecordsActions) {
    var all by rememberSaveable(kind) { mutableStateOf(false) }
    InfoCard(kind.label, icon = icon(kind), status = items.size.toString()) {
        val shown = if (all) items else items.take(FIRST_ROWS)
        shown.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    RecordsLogic.itemLine(item).takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val med = item.medication?.takeIf { kind == RecordKind.MEDICATION && state.medsOn }
                    if (med != null) {
                        if (RecordsLogic.alreadyHas(med, state.myMeds)) {
                            Text("In your medications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            TextButton(onClick = { actions.addMedication(med) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                Text("Add to my medications")
                            }
                        }
                    }
                }
                item.flag?.let { Spacer(Modifier.width(8.dp)); Flag(it) }
            }
        }
        if (items.size > FIRST_ROWS) {
            TextButton(onClick = { all = !all }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (all) "Show fewer" else "Show all ${items.size}") }
        }
    }
}

/** A lab's flag: a word with an arrow, never colour alone. */
@Composable
private fun Flag(flag: RecordFlag) {
    val icon = when (flag) {
        RecordFlag.HIGH -> Icons.Rounded.ArrowUpward
        RecordFlag.LOW -> Icons.Rounded.ArrowDownward
        RecordFlag.ABNORMAL -> Icons.Rounded.WarningAmber
    }
    Pill(RecordsLogic.flagWord(flag), AppTheme.status.warn, icon = icon)
}
