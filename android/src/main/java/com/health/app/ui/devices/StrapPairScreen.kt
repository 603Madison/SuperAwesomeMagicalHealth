package com.health.app.ui.devices

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.MonitorHeart
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
import com.health.app.devices.strap.FoundStrap
import com.health.app.devices.strap.StrapScanner
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CardItem
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Looking for a heart rate strap nearby: tap it to choose it, then Record on the Devices page. */
@Composable
fun StrapPairRoute(onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    var found by remember { mutableStateOf<List<FoundStrap>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        searching = true
        error = null
        try {
            withTimeoutOrNull(20_000) {
                StrapScanner.scan(context).collect { strap ->
                    found = (found.filterNot { it.address == strap.address } + strap).sortedByDescending { it.rssi }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: "Couldn't look for straps"
        }
        searching = false
    }
    StrapPairScreen(
        found, searching, error,
        onBack = onDone,
        onSearchAgain = { round++ },
        onPick = { strap ->
            c.strapStore.pair(strap.address, strap.name)
            c.tell("Added ${strap.name}. Press Record when you put it on.")
            onDone()
        },
    )
}

@Composable
fun StrapPairScreen(found: List<FoundStrap>, searching: Boolean, error: String?, onBack: () -> Unit, onSearchAgain: () -> Unit, onPick: (FoundStrap) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Add a heart rate strap")
        Hint(
            "Put the strap on (it wakes when it feels a heartbeat; dampen its pads a little). If its own app is open, close it: a strap often talks to one app at a time.",
            Modifier.padding(horizontal = 20.dp),
        )
        error?.let { ErrorCard(it, Modifier.padding(16.dp)) }
        SectionTitle(if (searching) "Looking…" else "Found", trailing = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) })
        if (found.isEmpty() && !searching) {
            EmptyState(Icons.Rounded.MonitorHeart, "No straps found", "Check that Bluetooth is on and the strap is on, a little damp, then look again.") {
                FilledTonalButton(onClick = onSearchAgain) { Text("Look again") }
            }
        }
        found.forEachIndexed { i, strap ->
            CardItem(i, found.size) {
                Row(Modifier.fillMaxWidth().clickable { onPick(strap) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MonitorHeart, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(strap.name, style = MaterialTheme.typography.bodyLarge)
                        Text(strap.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.SignalCellularAlt, "Signal ${strap.rssi} dBm", tint = if (strap.rssi > -70) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
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
