package com.health.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.health.app.BuildConfig
import com.health.app.attempt
import com.health.app.net.ServerVersion
import kotlinx.coroutines.launch

/** The server's version against the app's, when the server is older and the notice hasn't been put away. */
data class ServerNoticeState(val server: String, val app: String, val dismiss: () -> Unit = {})

/** The notice to show, from the app's container (routes only): null while the server is up to date, or its version isn't known. */
@Composable
fun serverNotice(): ServerNoticeState? {
    val c = appContainer()
    val version by c.serverVersion.collectAsState()
    val settings by c.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val server = version ?: return null
    val app = BuildConfig.VERSION_NAME
    if (!ServerVersion.isOlder(server, app) || settings.versionNoticeDismissed == ServerVersion.key(server, app)) return null
    return ServerNoticeState(server, app) { scope.launch { c.prefs.dismissVersionNotice(ServerVersion.key(server, app)) } }
}

/**
 * A gentle note that the server is older than the app, with how to update it (the README's
 * "Updating") and a way to put it away.
 */
@Composable
fun ServerUpdateNotice(state: ServerNoticeState, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.SystemUpdate, null, Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Time to update your server", style = MaterialTheme.typography.titleSmall)
                Text(ServerVersion.notice(state.server, state.app), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { attempt { uri.openUri(ServerVersion.HELP_URL) } }, modifier = Modifier.padding(start = 0.dp)) {
                    Text("How to update")
                }
            }
            IconButton(onClick = state.dismiss) { Icon(Icons.Rounded.Close, "Put this away") }
        }
    }
}
