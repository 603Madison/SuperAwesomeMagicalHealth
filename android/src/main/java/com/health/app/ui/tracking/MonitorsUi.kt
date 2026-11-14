package com.health.app.ui.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.health.app.devices.vitals.FoundMonitor
import com.health.app.devices.vitals.MonitorKind
import com.health.app.devices.vitals.MonitorScanner
import com.health.app.devices.vitals.PairedMonitor
import com.health.app.devices.vitals.VitalsConduit
import com.health.app.sync.ConduitStatus
import com.health.app.ui.Format
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CardItem
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

fun monitorIcon(kind: MonitorKind): ImageVector = if (kind == MonitorKind.BLOOD_PRESSURE) Icons.Rounded.MonitorHeart else Icons.Rounded.Bloodtype

/** What the Devices page shows of blood pressure monitors and glucose meters, and what it can do. */
class MonitorsUi(
    val monitors: List<PairedMonitor> = emptyList(),
    val status: ConduitStatus? = null,
    val running: Boolean = false,
    val find: () -> Unit = {},
    val sync: () -> Unit = {},
    val forget: (MonitorKind) -> Unit = {},
)

@Composable
fun MonitorsCard(ui: MonitorsUi, now: Long = System.currentTimeMillis()) {
    InfoCard("Blood pressure monitor & glucose meter", icon = Icons.Rounded.MonitorHeart) {
        if (ui.monitors.isEmpty()) {
            Hint("Monitors and meters that speak Bluetooth's standard profiles (Omron, A&D, Beurer, Contour, Accu-Chek and others) send their readings through this phone.")
        }
        ui.monitors.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(monitorIcon(m.kind), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    Text(m.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { ui.forget(m.kind) }) { Text("Forget") }
            }
        }
        ui.status?.takeIf { ui.monitors.isNotEmpty() }?.let { s ->
            val ago = s.lastSyncAt?.let { Format.ago(it, now) }
            val line = when {
                !s.ok -> s.message ?: "The last sync didn't work"
                s.message != null -> listOfNotNull(ago?.replaceFirstChar { it.uppercase() }, s.message).joinToString(": ")
                ago != null -> "Synced $ago" + (if (s.samples > 0) ", ${s.samples} readings" else "")
                else -> null
            }
            line?.let { Hint(it, Modifier.padding(top = 6.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (MonitorKind.entries.any { k -> ui.monitors.none { it.kind == k } }) FilledTonalButton(onClick = ui.find) { Text("Pair a monitor") }
            if (ui.monitors.isNotEmpty()) {
                if (ui.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else FilledTonalButton(onClick = ui.sync) { Text("Get readings") }
            }
        }
        if (ui.monitors.isNotEmpty()) Hint("Monitors wake for a little while after a reading: take one, then tap Get readings.", Modifier.padding(top = 6.dp))
    }
}

/** Looking for monitors nearby: tap one to pair, then it sends its readings. */
@Composable
fun MonitorPairRoute(onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var found by remember { mutableStateOf<List<FoundMonitor>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        searching = true
        error = null
        try {
            withTimeoutOrNull(20_000) {
                MonitorScanner.scan(context).collect { m -> found = (found.filterNot { it.address == m.address } + m).sortedByDescending { it.rssi } }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: "Couldn't look for monitors"
        }
        searching = false
    }
    MonitorPairScreen(found, searching, error, onBack = onDone, onSearchAgain = { round++ }) { m ->
        c.monitors.pair(m.kind, m.address, m.name)
        c.syncNow(VitalsConduit.ID)
        c.tell("Paired ${m.name}; getting its readings")
        onDone()
    }
}

@Composable
fun MonitorPairScreen(found: List<FoundMonitor>, searching: Boolean, error: String?, onBack: () -> Unit, onSearchAgain: () -> Unit, onPick: (FoundMonitor) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Pair a monitor")
        Hint(
            "Put the monitor or meter in pairing mode (often a long press on its Bluetooth or memory button; its manual says). Some ask for a PIN shown on their screen.",
            Modifier.padding(horizontal = 20.dp),
        )
        error?.let { ErrorCard(it, Modifier.padding(16.dp)) }
        SectionTitle(if (searching) "Looking…" else "Found", trailing = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
        if (found.isEmpty() && !searching) {
            EmptyState(Icons.Rounded.MonitorHeart, "No monitors found", "Check that Bluetooth is on and the monitor is in pairing mode, then look again.") {
                FilledTonalButton(onClick = onSearchAgain) { Text("Look again") }
            }
        }
        found.forEachIndexed { i, m ->
            CardItem(i, found.size) {
                Row(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(monitorIcon(m.kind), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${m.kind.label} · ${m.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.SignalCellularAlt, "Signal ${m.rssi} dBm", tint = if (m.rssi > -70) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (found.isNotEmpty() && !searching) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = onSearchAgain) { Text("Look again") }
            }
        }
    }
}
