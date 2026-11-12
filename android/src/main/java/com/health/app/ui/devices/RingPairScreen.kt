package com.health.app.ui.devices

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
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.health.app.devices.ble.FoundRing
import com.health.app.devices.ble.RingScanner
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CardItem
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.withTimeoutOrNull

/** Looking for the ring nearby: tap it to pair, then it syncs. */
@Composable
fun RingPairRoute(onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var found by remember { mutableStateOf<List<FoundRing>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        searching = true
        error = null
        try {
            withTimeoutOrNull(20_000) {
                RingScanner.scan(context).collect { ring ->
                    found = (found.filterNot { it.address == ring.address } + ring).sortedByDescending { it.rssi }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't look for rings"
        }
        searching = false
    }
    RingPairScreen(
        found, searching, error,
        onBack = onDone,
        onSearchAgain = { round++ },
        onPick = { ring ->
            c.colmi.pair(ring.address, ring.name)
            c.syncNow(com.health.app.devices.colmi.ColmiConduit.ID)
            c.tell("Paired ${ring.name}; syncing it now")
            onDone()
        },
    )
}

@Composable
fun RingPairScreen(found: List<FoundRing>, searching: Boolean, error: String?, onBack: () -> Unit, onSearchAgain: () -> Unit, onPick: (FoundRing) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Find your ring")
        Hint(
            "Keep the ring close to the phone (on its charger wakes it up). If the QRing app is open, close it: a ring talks to one app at a time.",
            Modifier.padding(horizontal = 20.dp),
        )
        error?.let { ErrorCard(it, Modifier.padding(16.dp)) }
        SectionTitle(if (searching) "Looking…" else "Found", trailing = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
        if (found.isEmpty() && !searching) {
            EmptyState(Icons.Rounded.Radar, "No rings found", "Check that Bluetooth is on and the ring is charged, then look again.") {
                FilledTonalButton(onClick = onSearchAgain) { Text("Look again") }
            }
        }
        found.forEachIndexed { i, ring ->
            CardItem(i, found.size) {
                Row(Modifier.fillMaxWidth().clickable { onPick(ring) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Radar, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ring.name, style = MaterialTheme.typography.bodyLarge)
                        Text(ring.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.SignalCellularAlt, "Signal ${ring.rssi} dBm", tint = if (ring.rssi > -70) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (found.isNotEmpty() && !searching) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                FilledTonalButton(onClick = onSearchAgain) { Text("Look again") }
            }
        }
    }
}
