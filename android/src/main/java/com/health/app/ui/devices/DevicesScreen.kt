package com.health.app.ui.devices

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Device
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.gadgetbridge.GadgetbridgeConduit
import com.health.app.devices.healthconnect.HealthConnectConduit
import com.health.app.devices.vitals.VitalsConduit
import com.health.app.ui.tracking.MonitorsCard
import com.health.app.ui.tracking.MonitorsUi
import com.health.app.sync.ConduitStatus
import com.health.app.ui.Format
import com.health.app.ui.components.CardItem
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.delay

class DevicesActions(
    val sync: (String?) -> Unit = {},
    val findRing: () -> Unit = {},
    val forgetRing: () -> Unit = {},
    val connectHealthConnect: () -> Unit = {},
    val healthConnectOff: () -> Unit = {},
    val installHealthConnect: () -> Unit = {},
    val chooseGadgetbridge: () -> Unit = {},
    val removeGadgetbridge: () -> Unit = {},
    val rename: (Device, String) -> Unit = { _, _ -> },
    val forget: (Device, Boolean) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    /** Uses one device for a reading (or, with null, combines them). */
    val choose: (Overlap, String?) -> Unit = { _, _ -> },
)

@Composable
fun DevicesRoute(onFindRing: () -> Unit, onFindMonitor: () -> Unit = {}) {
    val c = appContainer()
    val context = LocalContext.current
    val vm = viewModel { DevicesViewModel(c) }
    val state by vm.state.collectAsState()
    val pairedMonitors by c.monitors.monitors.collectAsState()
    val monitorBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onFindMonitor() else c.tell("The app needs Bluetooth permission to find your monitor")
    }
    val healthConnect = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        vm.healthConnectGranted(granted)
    }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onFindRing() else c.tell("The app needs Bluetooth permission to find your ring")
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::chooseGadgetbridge) }
    LaunchedEffect(Unit) {
        // Coming back from Health Connect's screens.
        while (true) {
            delay(5_000)
            vm.refreshHealthConnect()
        }
    }
    DevicesScreen(
        state,
        DevicesActions(
            sync = vm::sync,
            findRing = {
                if (ColmiConduit.hasPermission(context)) onFindRing() else bluetooth.launch(ColmiConduit.permissions())
            },
            forgetRing = vm::forgetRing,
            connectHealthConnect = { healthConnect.launch(vm.healthConnectPermissions()) },
            healthConnectOff = vm::healthConnectOff,
            installHealthConnect = {
                val uri = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding".toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                } catch (_: ActivityNotFoundException) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata".toUri())) }
                }
            },
            chooseGadgetbridge = { file.launch(arrayOf("*/*")) },
            removeGadgetbridge = vm::removeGadgetbridge,
            rename = vm::rename,
            forget = vm::forget,
            retry = vm::loadServer,
            choose = vm::choose,
        ),
        monitors = MonitorsUi(
            pairedMonitors,
            find = { if (ColmiConduit.hasPermission(context)) onFindMonitor() else monitorBluetooth.launch(ColmiConduit.permissions()) },
            forget = { kind -> c.monitors.forget(kind) },
        ),
    )
}

@Composable
fun DevicesScreen(state: DevicesState, actions: DevicesActions, now: Long = System.currentTimeMillis(), monitors: MonitorsUi = MonitorsUi()) {
    var renaming by remember { mutableStateOf<Device?>(null) }
    var forgetting by remember { mutableStateOf<Device?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Devices", "Where your data comes from") {
            if (state.running != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            } else {
                IconButton(onClick = { actions.sync(null) }) { Icon(Icons.Rounded.Sync, "Sync now") }
            }
        }
        SectionTitle("On this phone")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.outbox > 0) {
                Hint("${state.outbox} ${if (state.outbox == 1) "upload waits" else "uploads wait"} for the server; they're sent at the next sync.")
            }
            RingCard(state, actions, now)
            HealthConnectCard(state, actions, now)
            GadgetbridgeCard(state, actions, now)
            // Blood pressure monitors and glucose meters (ui/tracking).
            MonitorsCard(
                MonitorsUi(monitors.monitors, state.statuses[VitalsConduit.ID], state.running == VitalsConduit.ID, monitors.find, { actions.sync(VitalsConduit.ID) }, monitors.forget),
                now,
            )
        }
        if (state.overlaps.isNotEmpty()) {
            SectionTitle("When devices overlap")
            Column(Modifier.padding(horizontal = 16.dp)) { OverlapsCard(state.overlaps, actions.choose) }
        }
        SectionTitle("Pebble and Garmin")
        Column(Modifier.padding(horizontal = 16.dp)) { OtherDevicesCard() }
        SectionTitle("On your server")
        when {
            state.error != null && state.serverDevices.isEmpty() -> ErrorCard(state.error, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry)
            state.serverDevices.isEmpty() && !state.loading -> Hint("No data has reached your server yet.", Modifier.padding(horizontal = 20.dp))
            else -> state.serverDevices.forEachIndexed { i, device ->
                CardItem(i, state.serverDevices.size) {
                    ServerDeviceRow(device, now, onRename = { renaming = device }, onForget = { forgetting = device })
                }
            }
        }
    }
    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { actions.rename(device, name.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    forgetting?.let { device ->
        var deleteData by remember(device.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${device.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your server stops listing it. If it sends data again, it comes back.")
                    LabeledSwitch("Also delete what it recorded", deleteData, { deleteData = it }, subtitle = "${Format.number(device.samples)} readings, its sleep and workouts")
                }
            },
            confirmButton = {
                TextButton(onClick = { actions.forget(device, deleteData); forgetting = null }) {
                    Text(if (deleteData) "Forget and delete" else "Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/** How a source's last sync went, in a line. */
@Composable
private fun StatusLine(status: ConduitStatus?, running: Boolean, now: Long) {
    val (icon, color, text) = when {
        running -> Triple(Icons.Rounded.Sync, MaterialTheme.colorScheme.primary, "Syncing…")
        status?.lastSyncAt == null -> Triple(null, MaterialTheme.colorScheme.onSurfaceVariant, "Not synced yet")
        !status.ok -> Triple(Icons.Rounded.ErrorOutline, AppTheme.status.bad, status.message ?: "Sync failed")
        else -> {
            val counts = listOfNotNull(
                status.samples.takeIf { it > 0 }?.let { "${Format.number(it)} readings" },
                status.sleep.takeIf { it > 0 }?.let { "$it ${if (it == 1) "night" else "nights"}" },
                status.workouts.takeIf { it > 0 }?.let { "$it ${if (it == 1) "workout" else "workouts"}" },
            )
            val detail = status.message ?: if (counts.isEmpty()) "nothing new" else counts.joinToString(", ")
            Triple(Icons.Rounded.CheckCircle, AppTheme.status.good, "Synced ${Format.ago(status.lastSyncAt, now)} · $detail")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (status?.ok == false) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RingCard(state: DevicesState, actions: DevicesActions, now: Long) {
    val ring = state.ring
    InfoCard(null) {
        if (ring == null) {
            SourceHeader(Icons.Rounded.Radar, "Smart ring", "Colmi R02–R12, Colmi Ring 2 and other rings that use the QRing app")
            Spacer(Modifier.height(10.dp))
            Hint("The app becomes your ring's companion: it sets the ring's clock and brings in heart rate, steps, sleep, blood oxygen, HRV and stress over Bluetooth (and skin temperature, on rings that measure it). (If the QRing app is connected to the ring, close it first.)")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = actions.findRing) { Text("Find my ring") }
        } else {
            SourceHeader(Icons.Rounded.Radar, ring.name, listOfNotNull(ring.battery?.let { "$it% battery" }, ring.firmware?.let { "firmware $it" }, ring.address).joinToString(" · "))
            Spacer(Modifier.height(10.dp))
            StatusLine(state.statuses[ColmiConduit.ID], state.running == ColmiConduit.ID, now)
            Spacer(Modifier.height(12.dp))
            RingReadingsList(state.ringReadings.takeIf { it.address == ring.address }, now)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.sync(ColmiConduit.ID) }, enabled = state.running == null) { Text("Sync now") }
                OutlinedButton(onClick = actions.forgetRing) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun HealthConnectCard(state: DevicesState, actions: DevicesActions, now: Long) {
    InfoCard(null) {
        SourceHeader(Icons.Rounded.HealthAndSafety, "Health Connect", "Garmin Connect, Gadgetbridge, Fitbit, Samsung Health and more")
        Spacer(Modifier.height(10.dp))
        when (state.healthConnect) {
            HealthConnectState.NOT_INSTALLED, HealthConnectState.NEEDS_UPDATE -> {
                Hint(if (state.healthConnect == HealthConnectState.NOT_INSTALLED) "Health Connect isn't on this phone yet." else "Health Connect needs an update first.")
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = actions.installHealthConnect) { Text(if (state.healthConnect == HealthConnectState.NOT_INSTALLED) "Get Health Connect" else "Update Health Connect") }
            }
            HealthConnectState.OFF, HealthConnectState.NEEDS_PERMISSION -> {
                Hint("Brings in what your other apps share with Health Connect: steps, heart rate, sleep, workouts, weight. One device per app on your server.")
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = actions.connectHealthConnect) { Text("Connect") }
            }
            HealthConnectState.ON -> {
                StatusLine(state.statuses[HealthConnectConduit.ID], state.running == HealthConnectConduit.ID, now)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { actions.sync(HealthConnectConduit.ID) }, enabled = state.running == null) { Text("Sync now") }
                    OutlinedButton(onClick = actions.connectHealthConnect) { Text("Permissions") }
                    TextButton(onClick = actions.healthConnectOff) { Text("Turn off") }
                }
            }
        }
    }
}

@Composable
private fun GadgetbridgeCard(state: DevicesState, actions: DevicesActions, now: Long) {
    InfoCard(null) {
        SourceHeader(Icons.Rounded.Description, "Gadgetbridge", state.gadgetbridgeFile ?: "Its database export, for Pebble, Colmi, Garmin and more")
        Spacer(Modifier.height(10.dp))
        if (state.gadgetbridgeFile == null) {
            Hint("In Gadgetbridge: Settings › Automations › Auto export, and pick where it saves. Then choose that file here once; each sync reads what's new.")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = actions.chooseGadgetbridge) { Text("Choose export file") }
        } else {
            StatusLine(state.statuses[GadgetbridgeConduit.ID], state.running == GadgetbridgeConduit.ID, now)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.sync(GadgetbridgeConduit.ID) }, enabled = state.running == null) { Text("Sync now") }
                OutlinedButton(onClick = actions.chooseGadgetbridge) { Text("Change file") }
                TextButton(onClick = actions.removeGadgetbridge) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun OtherDevicesCard() {
    InfoCard(null, icon = Icons.Rounded.Watch) {
        Text("Pebble Time 2", style = MaterialTheme.typography.titleSmall)
        Hint("Pair it with Gadgetbridge, then bring its steps, heart rate and sleep in through Gadgetbridge's export or Health Connect (above). The SuperAwesomeMagicalHealth watch app (with each release) shows today's scores and logs mood, water, doses and more in one tap.")
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Garmin", style = MaterialTheme.typography.titleSmall)
        Hint("Turn on Health Connect in the Garmin Connect app and connect Health Connect above; or use Gadgetbridge; or upload .FIT files from your watch on your server's web page (Devices › Import).")
    }
}

@Composable
private fun ServerDeviceRow(device: Device, now: Long, onRename: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                device.kind.label,
                device.lastSyncAt?.let { "synced ${Format.ago(it, now)}" },
                device.battery?.let { "$it%" },
                device.samples.takeIf { it > 0 }?.let { "${Format.number(it)} readings" },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More about ${device.name}") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Forget…") }, onClick = { menu = false; onForget() })
            }
        }
    }
}
