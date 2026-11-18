package com.health.app.ui.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.AiStatus
import com.health.api.DataMap
import com.health.api.HealthRecords
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.app.BuildConfig
import com.health.app.attempt
import com.health.app.devices.strap.PairedStrap
import com.health.app.devices.strap.StrapService
import com.health.app.net.aiStatus
import com.health.app.net.dataMap
import com.health.app.net.latestDigest
import com.health.app.net.records
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BigTitle
import com.health.app.ui.components.CardHeading
import com.health.app.ui.components.LinkRow
import com.health.app.ui.components.PillButton
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.ServerNoticeState
import com.health.app.ui.components.ServerUpdateNotice
import com.health.app.ui.components.Tile
import com.health.app.ui.components.appContainer
import com.health.app.ui.devices.DeviceKinds
import com.health.app.ui.recent.Recent
import com.health.app.ui.records.RecordsLogic
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.WhereDataGoes

/*
 * You, the fourth tab: setting things up. Your devices (each with how it's doing, and the strap's
 * Record tonight), your records (Your week, a report for your doctor, your medical records from
 * patient portals), the assistant where there is
 * one, Where your data goes on a highlighted row, and Settings in five plain groups.
 */

/** What You shows besides its fixed rows. */
data class YouState(
    /** The person's devices that record, most recently synced first. */
    val devices: List<Device> = emptyList(),
    /** The chest strap chosen on this phone, and since when it's been recording (null: it isn't). */
    val strap: PairedStrap? = null,
    val recordingSince: Long? = null,
    /** The assistant is on this server for this person. */
    val assistant: Boolean = false,
    /** Where your data goes, in a line ("2 ways out on · 1 paused"); null until known. */
    val privacy: String? = null,
    /** Medical records in a line ("Mass General Brigham · updated 2 h ago"); null until known. */
    val records: String? = null,
    /** This week's digest headline; null until known. */
    val week: String? = null,
    val serverNotice: ServerNoticeState? = null,
    val serverVersion: String? = null,
)

class YouActions(
    val open: (String) -> Unit = {},
    val recordStrap: () -> Unit = {},
    val stopStrap: () -> Unit = {},
)

@Composable
fun YouRoute(open: (String) -> Unit) {
    val c = appContainer()
    val viewing by c.viewing.collectAsState()
    val strap by c.strapStore.strap.collectAsState()
    val recording by c.strapStore.recording.collectAsState()
    val revision by c.revision.collectAsState()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var ai by remember { mutableStateOf<AiStatus?>(null) }
    var privacy by remember { mutableStateOf<DataMap?>(null) }
    var week by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<HealthRecords?>(null) }
    // Each time You shows: devices may have synced, the assistant been turned on, a way out paused.
    LaunchedEffect(viewing, revision) {
        if (viewing == null) {
            devices = attempt { c.api.devices() }.getOrDefault(devices)
            ai = attempt { c.api.aiStatus() }.getOrNull()
            privacy = attempt { c.api.dataMap() }.getOrNull() ?: privacy
            records = attempt { c.api.records() }.getOrNull() ?: records
        }
        week = attempt { c.ui.latestDigest().headline }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val own = viewing == null
    YouScreen(
        YouState(
            devices = if (own) YouText.devices(devices) else emptyList(),
            strap = strap.takeIf { own },
            recordingSince = recording?.since,
            assistant = own && ai?.available == true,
            privacy = privacy?.let(WhereDataGoes::youLine),
            week = week,
            records = if (own) RecordsLogic.youLine(records, System.currentTimeMillis(), c.zone()) else null,
            serverNotice = com.health.app.ui.components.serverNotice(),
            serverVersion = c.serverVersion.collectAsState().value,
        ),
        YouActions(
            open = open,
            recordStrap = { if (!StrapService.start(c.context)) c.tell("Couldn't start recording. Try again with the app open.") },
            stopStrap = { c.strapRecorder.stop(); c.tell("Stopped recording; sending what it recorded") },
        ),
        zone = c.zone(),
    )
}

/** You's words, worked out without drawing anything. */
object YouText {
    /** The devices You lists: those that record (not ones entered by hand), most recently synced first, at most [max]. */
    fun devices(all: List<Device>, max: Int = 4): List<Device> =
        all.filter { Recent.records(it.kind) || it.kind == DeviceKind.HEART_RATE_STRAP }.sortedByDescending { it.lastSyncAt ?: 0 }.take(max)

    /** "Synced 4 min ago · battery 78%". */
    fun status(d: Device, now: Long): String =
        listOfNotNull(d.lastSyncAt?.let { "Synced ${Format.ago(it, now)}" } ?: "Not synced yet", d.battery?.let { "battery $it%" }).joinToString(" · ")
}

/** The page. [now] is when "Synced … ago" counts from. */
@Composable
fun YouScreen(state: YouState, actions: YouActions, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    val readOnly = LocalReadOnly.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BigTitle("You", Modifier.padding(top = 8.dp, bottom = 4.dp))
        state.serverNotice?.let { ServerUpdateNotice(it) }
        if (!readOnly) Devices(state, actions, now, zone)
        AppCard(padding = 0.dp) {
            LinkRow("Your week", state.week ?: "This week against the one before", onClick = { actions.open("digest") })
            RowDivider()
            LinkRow("A report for your doctor", "PDF, CSV or FHIR, for the dates you choose", onClick = { actions.open("reports") })
            if (!readOnly) {
                RowDivider()
                LinkRow(RecordsLogic.TITLE, state.records ?: RecordsLogic.youLine(null, now), onClick = { actions.open(RecordsLogic.ROUTE) })
            }
            if (state.assistant) {
                RowDivider()
                LinkRow("Assistant", "Ask the assistant about your own data", onClick = { actions.open("assistant") })
            }
        }
        if (!readOnly) {
            Surface(shape = RoundedCornerShape(com.health.app.ui.components.CARD_CORNER), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                LinkRow(
                    WhereDataGoes.TITLE, state.privacy ?: "Everything that can leave your server, and pausing it", onClick = { actions.open(WhereDataGoes.ROUTE) },
                    leading = { androidx.compose.material3.Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onTertiaryContainer) },
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer, quietColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        AppCard(padding = 0.dp) {
            SettingsPage.entries.forEachIndexed { i, page ->
                if (i > 0) RowDivider()
                LinkRow(page.title, page.about, onClick = { actions.open(page.route) })
            }
        }
        Text(
            "SuperAwesomeMagicalHealth ${BuildConfig.VERSION_NAME}" + (state.serverVersion?.let { " · server $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Your devices: each with how it's doing; the strap chosen on this phone with Record tonight (or Stop); Manage devices. */
@Composable
private fun Devices(state: YouState, actions: YouActions, now: Long, zone: java.time.ZoneId) {
    AppCard(padding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeading("Your devices")
            val strap = state.strap
            val strapDevice = strap?.let { s -> state.devices.firstOrNull { it.kind == DeviceKind.HEART_RATE_STRAP && it.identifier == s.address } }
            state.devices.filter { it != strapDevice }.forEach { d ->
                DeviceRow(d.kind, d.name, YouText.status(d, now))
            }
            if (strap != null) {
                val recording = state.recordingSince
                val status = if (recording != null) "Recording since ${Format.timeOfDay(recording, zone)}"
                else listOfNotNull("Ready", (strapDevice?.battery ?: strap.battery)?.let { "battery $it%" }).joinToString(" · ")
                DeviceRow(DeviceKind.HEART_RATE_STRAP, strapDevice?.name ?: strap.name, status) {
                    if (recording == null) {
                        PillButton("Record tonight", actions.recordStrap, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, spoken = "Record tonight with ${strap.name}")
                    } else {
                        PillButton("Stop", actions.stopStrap, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, spoken = "Stop recording")
                    }
                }
            }
            if (state.devices.isEmpty() && strap == null) {
                Text("No devices yet. A ring, a watch or Health Connect brings in your sleep, heart rate and steps.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.open("devices") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text(if (state.devices.isEmpty() && strap == null) "Add a device" else "Manage devices")
            }
        }
    }
}

@Composable
private fun DeviceRow(kind: DeviceKind, name: String, status: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(DeviceKinds.icon(kind), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, round = true)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
