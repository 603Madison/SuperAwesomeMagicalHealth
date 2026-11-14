@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Module
import com.health.api.Share
import com.health.api.ShareChange
import com.health.api.ShareRequest
import com.health.api.Shares
import com.health.app.Container
import com.health.app.net.changeShare
import com.health.app.net.deleteShare
import com.health.app.net.share
import com.health.app.net.shares
import com.health.app.ui.Format
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.TitleAction
import androidx.compose.material.icons.rounded.PersonAdd
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharingState(val shares: Shares? = null, val loading: Boolean = true, val error: String? = null, val now: Long = System.currentTimeMillis())

class SharingViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(SharingState())
    val state: StateFlow<SharingState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.shares == null, error = null, now = System.currentTimeMillis()) }
        viewModelScope.launch {
            try {
                _state.update { it.copy(shares = c.api.shares(), loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load sharing") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            message?.let(c::tell)
            load()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            c.tell(e.message ?: "That didn't work")
        }
    }

    fun share(username: String, modules: Set<Module>) = act("Shared with $username") { c.api.share(ShareRequest(username.trim(), modules)) }

    fun change(id: String, modules: Set<Module>) = act("Saved") { c.api.changeShare(id, ShareChange(modules)) }

    fun stop(share: Share, mine: Boolean) = act(if (mine) "Stopped sharing with ${share.viewerName}" else "You no longer see ${share.ownerName}'s") { c.api.deleteShare(share.id) }
}

class SharingActions(
    val share: (String, Set<Module>) -> Unit = { _, _ -> },
    val change: (String, Set<Module>) -> Unit = { _, _ -> },
    val stop: (Share, Boolean) -> Unit = { _, _ -> },
    val view: (Share) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun SharingRoute(onBack: () -> Unit, onViewing: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { SharingViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    val scope = rememberCoroutineScope()
    SharingScreen(
        state, profile?.modules ?: Module.DEFAULT, onBack,
        SharingActions(
            share = { u, m -> vm.share(u, m) },
            change = { id, m -> vm.change(id, m) },
            stop = { s, mine -> vm.stop(s, mine) },
            view = { s -> scope.launch { c.view(s); onViewing() } },
            retry = { vm.load() },
        ),
    )
}

/** Sharing's sections, in the default order (the sections of [FeatureScreens.SHARING]). */
val SHARING_SECTIONS = listOf(ArrangeItem("given", "Who sees yours"), ArrangeItem("received", "Shared with you"))

@Composable
fun SharingScreen(state: SharingState, myModules: Set<Module>, onBack: () -> Unit, actions: SharingActions) {
    var editing by remember { mutableStateOf<Share?>(null) }
    var adding by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf<Pair<Share, Boolean>?>(null) }
    val shares = state.shares
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Sharing") { ArrangeAction(FeatureScreens.SHARING, "Sharing", SHARING_SECTIONS) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(
                "Let someone with an account here, like a partner, parent or carer, see the features you pick. " +
                    "They can only look, and you can change or stop it any time.",
                Modifier.padding(horizontal = 4.dp),
            )
            when {
                state.loading && shares == null -> Loading()
                state.error != null && shares == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                shares != null -> Sections(arrangedIn(FeatureScreens.SHARING, SHARING_SECTIONS)) { id ->
                    when (id) {
                        "given" -> InfoCard("Who sees yours", icon = Icons.Rounded.Group, trailing = { TitleAction(Icons.Rounded.PersonAdd, "Share with someone") { adding = true } }) {
                            if (shares.given.isEmpty()) Hint("No one yet.")
                            shares.given.forEachIndexed { i, s ->
                                if (i > 0) Spacer(Modifier.height(10.dp))
                                Text(s.viewerName, style = MaterialTheme.typography.titleMedium)
                                Hint(featureList(s.modules))
                                Hint(s.lastViewedAt?.let { "Last looked ${Format.ago(it, state.now)}" } ?: "Hasn't looked yet")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editing = s }) { Text("Change") }
                                    TextButton(onClick = { stopping = s to true }) { Text("Stop sharing", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                        "received" -> InfoCard("Shared with you", icon = Icons.Rounded.Visibility) {
                            if (shares.received.isEmpty()) Hint("When someone shares with you, it shows here.")
                            shares.received.forEachIndexed { i, s ->
                                if (i > 0) Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.ownerName, style = MaterialTheme.typography.titleMedium)
                                        Hint(featureList(s.modules))
                                    }
                                    FilledTonalButton(onClick = { actions.view(s) }) { Text("View") }
                                }
                                TextButton(onClick = { stopping = s to false }) { Text("Stop seeing theirs") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        ShareDialog(null, myModules, onDismiss = { adding = false }) { user, modules -> actions.share(user, modules); adding = false }
    }
    editing?.let { s ->
        ShareDialog(s, myModules, onDismiss = { editing = null }) { _, modules -> actions.change(s.id, modules); editing = null }
    }
    stopping?.let { (s, mine) ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(if (mine) "Stop sharing with ${s.viewerName}?" else "Stop seeing ${s.ownerName}'s?") },
            text = { Text(if (mine) "They won't see your data any more. You can share again later." else "${s.ownerName} can share again if you'd like.") },
            confirmButton = { TextButton(onClick = { actions.stop(s, mine); stopping = null }) { Text("Stop", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text("Cancel") } },
        )
    }
}

/** "Activity, Sleep and Medications". */
fun featureList(modules: Set<Module>): String {
    val labels = Module.entries.filter { it in modules }.map { it.label }
    return when (labels.size) {
        0 -> "Nothing"
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
}

@Composable
private fun ShareDialog(existing: Share?, myModules: Set<Module>, onDismiss: () -> Unit, onSave: (String, Set<Module>) -> Unit) {
    var username by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(existing?.modules ?: emptySet()) }
    val offered = Module.entries.filter { it in myModules || it in chosen }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Share with someone" else "What ${existing.viewerName} sees") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    OutlinedTextField(username, { username = it }, label = { Text("Their username on this server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text("They'll see", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    offered.filter { !it.sensitive }.forEach { m ->
                        FilterChip(selected = m in chosen, onClick = { chosen = if (m in chosen) chosen - m else chosen + m }, label = { Text(m.label) })
                    }
                }
                if (offered.any { it.sensitive }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(" Private features: only if you choose them", style = MaterialTheme.typography.labelLarge)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        offered.filter { it.sensitive }.forEach { m ->
                            FilterChip(selected = m in chosen, onClick = { chosen = if (m in chosen) chosen - m else chosen + m }, label = { Text(m.label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = chosen.isNotEmpty() && (existing != null || username.isNotBlank()), onClick = { onSave(username, chosen) }) {
                Text(if (existing == null) "Share" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
