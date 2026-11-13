package com.health.app.ui.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.health.api.Layouts
import com.health.api.Module
import com.health.api.NavPage
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.arrangedBy

@Composable
fun HubRoute(onOpen: (String) -> Unit, showDevices: Boolean) {
    HubScreen(com.health.app.ui.care.shownModules(), showDevices, showMeasurements = com.health.app.ui.modules.body.measurementsShown(), onOpen = onOpen)
}

/** One row of the Health tab: its id in [Layouts.NAV] (a [Module] or [NavPage] name), and where it goes. */
data class HubRow(val id: String, val icon: ImageVector, val title: String, val subtitle: String, val route: String)

/**
 * The Health tab's rows, in the default order: the opt-in features that are on, body measurements,
 * Devices (when it isn't a tab) and conditions and goals.
 */
fun hubRows(modules: Set<Module>, showDevices: Boolean, showMeasurements: Boolean): List<HubRow> = buildList {
    ModuleInfo.OPT_IN.filter { it in modules }.forEach { m ->
        ModuleInfo.route(m)?.let { add(HubRow(m.name, ModuleInfo.icon(m), m.label, ModuleInfo.description(m), it)) }
    }
    // Body measurements (ui/modules/body), with Body on and the fields that bring them.
    if (showMeasurements && Module.BODY in modules) add(HubRow(Module.BODY.name, Icons.Rounded.Straighten, "Body measurements", "Waist, hips, chest and more, over time", "measurements"))
    if (showDevices) add(HubRow(NavPage.DEVICES.name, Icons.Rounded.Watch, "Devices", "Where your data comes from", "devices"))
    add(HubRow(NavPage.CONDITIONS.name, Icons.Rounded.MedicalInformation, "Conditions and goals", "Tell the app what you live with, and it shows what fits", "settings/conditions"))
}

/**
 * The features that are on, one tap each, in the person's order ([Layouts.NAV], shared with the
 * web's sidebar). Hidden ones stay on, and open from Settings › Features.
 */
@Composable
fun HubScreen(modules: Set<Module>, showDevices: Boolean, showMeasurements: Boolean = false, onOpen: (String) -> Unit) {
    var arranging by remember { mutableStateOf(false) }
    val rows = hubRows(modules, showDevices, showMeasurements)
    val shown = arrangedBy(Layouts.NAV, rows) { it.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Health", "Everything you've turned on") { ArrangeButton { arranging = true } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shown.isNotEmpty()) {
                InfoCard(null) {
                    shown.forEach { r -> NavRow(r.icon, r.title, r.subtitle, onClick = { onOpen(r.route) }) }
                }
            }
            InfoCard(null) {
                NavRow(Icons.Rounded.Tune, "Choose features", "Turn parts of the app on or off", onClick = { onOpen("settings") })
            }
            if (modules.any { it.sensitive && it in ModuleInfo.OPT_IN }) {
                AppCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                        Hint("These are encrypted on your server, and AI connector links only see them if you allow it.")
                    }
                }
            }
        }
    }
    if (arranging) {
        ArrangeSheet(Layouts.NAV, "Health", rows.map { ArrangeItem(it.id, it.title) }, { arranging = false }, hiddenMeans = "Hidden here, still on")
    }
}
