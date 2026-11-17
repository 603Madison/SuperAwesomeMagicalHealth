package com.health.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.AiStatus
import com.health.api.Device
import com.health.app.BuildConfig
import com.health.app.attempt
import com.health.app.net.aiStatus
import com.health.app.ui.components.MenuGroup
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.ServerNoticeState
import com.health.app.ui.components.ServerUpdateNotice
import com.health.app.ui.components.appContainer
import com.health.app.ui.devices.Compare
import com.health.app.ui.modules.NavRow
import com.health.app.ui.settings.SettingsPage

/*
 * More, the fifth tab: what isn't a day, a feature or a chart, one tap each. Your records (Health
 * alerts, Your week, Reports & export), Devices, the Assistant, and Settings in short pages.
 */

/** What More shows besides its fixed rows. */
data class MoreState(
    /** Health alerts not seen yet: the badge on its row. */
    val unseenAlerts: Int = 0,
    /** Two devices or more record readings: Compare devices is offered. */
    val compare: Boolean = false,
    /** The assistant is on this server for this person (never while viewing a share). */
    val assistant: Boolean = false,
    val serverNotice: ServerNoticeState? = null,
    val serverVersion: String? = null,
)

@Composable
fun MoreRoute(onOpen: (String) -> Unit, unseenAlerts: Int = 0) {
    val c = appContainer()
    val viewing by c.viewing.collectAsState()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var ai by remember { mutableStateOf<AiStatus?>(null) }
    // Each time More shows: devices may have come or gone, the assistant been turned on, alerts come.
    LaunchedEffect(viewing) {
        if (viewing == null) {
            c.refreshAlerts()
            devices = attempt { c.api.devices() }.getOrDefault(devices)
            ai = attempt { c.api.aiStatus() }.getOrNull()
        }
    }
    val own = viewing == null
    MoreScreen(
        MoreState(
            unseenAlerts = unseenAlerts,
            compare = own && Compare.offered(devices),
            assistant = own && ai?.available == true,
            serverNotice = com.health.app.ui.components.serverNotice(),
            serverVersion = c.serverVersion.collectAsState().value,
        ),
        onOpen,
    )
}

/** More's groups, each row one tap to its page. */
@Composable
fun MoreScreen(state: MoreState, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("More")
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            state.serverNotice?.let { ServerUpdateNotice(it) }
            MenuGroup("Your records") {
                NavRow(Icons.Rounded.NotificationsActive, "Health alerts", "Readings that might need a look", onClick = { onOpen("alerts") }, badge = state.unseenAlerts)
                NavRow(Icons.Rounded.Summarize, "Your week", "This week against the one before", onClick = { onOpen("digest") })
                NavRow(Icons.Rounded.Description, "Reports & export", "A PDF for your doctor, or all your data", onClick = { onOpen("reports") })
            }
            MenuGroup("Devices") {
                NavRow(Icons.Rounded.Watch, "Devices", "Where your data comes from", onClick = { onOpen("devices") })
                if (state.compare) {
                    NavRow(Icons.AutoMirrored.Rounded.CompareArrows, "Compare devices", "How two of them read on the same nights", onClick = { onOpen("devices/compare") })
                }
            }
            if (state.assistant) {
                MenuGroup(null) {
                    NavRow(Icons.Rounded.AutoAwesome, "Assistant", "Ask about your own data", onClick = { onOpen("assistant") })
                }
            }
            MenuGroup("Settings") {
                SettingsPage.entries.forEach { page -> NavRow(page.icon, page.title, page.about, onClick = { onOpen(page.route) }) }
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
}
