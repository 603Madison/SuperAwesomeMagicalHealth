package com.health.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.devices.strap.StrapConduit
import com.health.app.devices.strap.StrapLive
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.theme.AppTheme
import java.time.ZoneId

/** What the strap card says about the strap right now, while it records. */
internal fun strapLiveLine(live: StrapLive): String = when {
    !live.connected -> "Looking for your strap…" + (live.problem?.let { " ($it)" } ?: "")
    !live.touching -> "It can't feel your heartbeat yet: dampen the pads a little and check it sits snugly."
    live.bpm == null -> "Connected, waiting for your heartbeat…"
    else -> "${live.bpm} bpm"
}

/**
 * A heart rate strap (any standard Bluetooth chest strap), read by the app itself: chosen once,
 * then recorded through a night to check the ring against.
 */
@Composable
internal fun StrapCard(state: DevicesState, actions: DevicesActions, now: Long, zone: ZoneId) {
    val strap = state.strap
    InfoCard(null) {
        if (strap == null) {
            SourceHeader(Icons.Rounded.MonitorHeart, "Heart rate strap", "Polar, Garmin, Wahoo, Coospo, Decathlon or any other Bluetooth chest strap")
            Spacer(Modifier.height(10.dp))
            Hint("A chest strap is the most accurate heart rate you can wear. Record a night with it now and then, and your server can check how close your ring comes. The app reads it directly: no other app or account.")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = actions.findStrap) { Text("Add a heart rate strap") }
            return@InfoCard
        }
        val recording = state.strapRecording
        SourceHeader(Icons.Rounded.MonitorHeart, strap.name, listOfNotNull(strap.battery?.let { "$it% battery" }, strap.address).joinToString(" · "))
        Spacer(Modifier.height(10.dp))
        if (recording != null) {
            val live = state.strapLive
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live.connected && live.touching && live.bpm != null) {
                    Icon(Icons.Rounded.Favorite, null, tint = AppTheme.data.heart, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${live.bpm}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(4.dp))
                    Text("bpm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (!live.connected) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(strapLiveLine(live), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Recording since ${Format.timeOfDay(recording.since, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Hint("Wear it on your chest, a little damp, with your ring on, and press Record before bed.")
        }
        state.statuses[StrapConduit.ID]?.let { status ->
            Spacer(Modifier.height(6.dp))
            StatusLine(status, state.running == StrapConduit.ID, now)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording == null) {
                FilledTonalButton(onClick = actions.recordStrap) {
                    Icon(Icons.Rounded.FiberManualRecord, null, tint = AppTheme.data.heart, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Record")
                }
            } else {
                FilledTonalButton(onClick = actions.stopStrap) {
                    Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
            OutlinedButton(onClick = actions.forgetStrap) { Text("Forget") }
        }
    }
}
