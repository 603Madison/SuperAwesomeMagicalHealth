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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.Module
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer

@Composable
fun HubRoute(onOpen: (String) -> Unit, showDevices: Boolean) {
    val c = appContainer()
    val profile by c.profile.collectAsState()
    HubScreen(com.health.app.ui.care.shownModules(), showDevices, onOpen)
}

/** The opt-in modules that are on, one tap each, and the way to the rest. */
@Composable
fun HubScreen(modules: Set<Module>, showDevices: Boolean, onOpen: (String) -> Unit) {
    val on = ModuleInfo.OPT_IN.filter { it in modules }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Health", "Everything you've turned on")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (on.isNotEmpty()) {
                InfoCard(null) {
                    on.forEach { m -> NavRow(ModuleInfo.icon(m), m.label, ModuleInfo.description(m), onClick = { ModuleInfo.route(m)?.let(onOpen) }) }
                }
            }
            if (showDevices) {
                InfoCard(null) {
                    NavRow(Icons.Rounded.Watch, "Devices", "Where your data comes from", onClick = { onOpen("devices") })
                }
            }
            InfoCard(null) {
                NavRow(Icons.Rounded.Tune, "Choose features", "Turn parts of the app on or off", onClick = { onOpen("settings") })
            }
            if (on.any { it.sensitive }) {
                AppCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
                        Hint("These are encrypted on your server, and AI connector links only see them if you allow it.")
                    }
                }
            }
        }
    }
}
