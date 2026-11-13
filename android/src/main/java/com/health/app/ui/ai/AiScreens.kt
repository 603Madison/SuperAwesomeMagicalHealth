@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.AiAuditEntry
import com.health.api.AiChatRequest
import com.health.api.AiConsent
import com.health.api.AiStatus
import com.health.api.Module
import com.health.app.Container
import com.health.app.net.aiAudit
import com.health.app.net.aiChat
import com.health.app.net.aiStatus
import com.health.app.net.deleteAiConsent
import com.health.app.net.saveAiConsent
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.appContainer
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

data class AiState(
    val status: AiStatus? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The conversation, kept in memory on this phone only. */
    val turns: List<ChatTurn> = emptyList(),
    val sending: Boolean = false,
    val chatError: String? = null,
    /** Changing what it may look at, with a choice already made. */
    val choosing: Boolean = false,
    val audit: List<AiAuditEntry>? = null,
)

class AiViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = it.status == null, error = null) }
        viewModelScope.launch {
            try {
                // The assistant is only ever the person's own: never through a share.
                _state.update { it.copy(status = c.api.aiStatus(), loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = AiText.error(e)) }
            }
        }
    }

    fun choose(on: Boolean) = _state.update { it.copy(choosing = on) }

    fun agree(consent: AiConsent) = viewModelScope.launch {
        runCatching { c.api.saveAiConsent(consent) }
            .onSuccess { saved -> _state.update { it.copy(status = it.status?.copy(consent = saved), choosing = false) }; c.tell("Saved") }
            .onFailure { c.tell(AiText.error(it)) }
    }

    /** Stops the assistant for this person and forgets their choice (and the conversation here). */
    fun forget() = viewModelScope.launch {
        runCatching { c.api.deleteAiConsent() }
            .onSuccess { _state.update { it.copy(status = it.status?.copy(consent = null), turns = emptyList(), choosing = false, chatError = null) }; c.tell("Stopped. Your choice is forgotten.") }
            .onFailure { c.tell(AiText.error(it)) }
    }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.sending) return
        _state.update { it.copy(turns = it.turns + ChatTurn(ChatTurn.USER, question), chatError = null) }
        send()
    }

    /** Sends the conversation so far (again, after an error). */
    fun send() {
        val turns = _state.value.turns
        if (turns.isEmpty() || turns.last().role != ChatTurn.USER) return
        _state.update { it.copy(sending = true, chatError = null) }
        viewModelScope.launch {
            try {
                val reply = c.api.aiChat(AiChatRequest(AiText.messages(turns)))
                _state.update {
                    it.copy(
                        turns = it.turns + ChatTurn(ChatTurn.ASSISTANT, reply.text, reply.looked), sending = false,
                        status = it.status?.copy(usedToday = reply.usedToday),
                    )
                }
                // Logging something for the person changes what the screens show.
                if (reply.looked.any { it.tool.startsWith("log_") || it.tool == "check_in" || it.tool == "tick_habit" }) c.dataChanged()
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, chatError = AiText.error(e)) }
            }
        }
    }

    fun newChat() = _state.update { it.copy(turns = emptyList(), chatError = null) }

    fun loadAudit() = viewModelScope.launch {
        runCatching { c.api.aiAudit(30) }.onSuccess { list -> _state.update { it.copy(audit = list) } }.onFailure { c.tell(AiText.error(it)) }
    }
}

@Composable
fun AiRoute(onBack: () -> Unit, onAudit: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { AiViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    // Each time the page shows: the choice may have changed on the log's page or elsewhere.
    LaunchedEffect(Unit) { vm.load() }
    if (LocalReadOnly.current) {
        NotWhileViewing(onBack)
        return
    }
    AiScreen(
        state, profile?.modules ?: c.modules(), onBack,
        AiActions(agree = { vm.agree(it) }, forget = { vm.forget() }, ask = vm::ask, retry = vm::send, newChat = vm::newChat, choose = vm::choose, audit = onAudit, reload = vm::load),
    )
}

@Composable
fun AiAuditRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { AiViewModel(c) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load(); vm.loadAudit() }
    if (LocalReadOnly.current) {
        NotWhileViewing(onBack)
        return
    }
    AiAuditScreen(state.audit, c.zone(), onBack, onForget = { vm.forget(); onBack() }, hasConsent = state.status?.consent != null)
}

@Composable
private fun NotWhileViewing(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, "AI assistant")
        EmptyState(Icons.Rounded.Lock, "Only for your own data", "Go back to your own data to use the assistant.")
    }
}

class AiActions(
    val agree: (AiConsent) -> Unit = {},
    val forget: () -> Unit = {},
    val ask: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val newChat: () -> Unit = {},
    val choose: (Boolean) -> Unit = {},
    val audit: () -> Unit = {},
    val reload: () -> Unit = {},
)

/** The warning until the person chooses; then the conversation. */
@Composable
fun AiScreen(state: AiState, on: Set<Module>, onBack: () -> Unit, actions: AiActions) {
    val status = state.status
    when {
        status == null && state.loading -> Column(Modifier.fillMaxSize()) { BackBar(onBack, "AI assistant"); Loading() }
        status == null -> Column(Modifier.fillMaxSize()) {
            BackBar(onBack, "AI assistant")
            ErrorCard(state.error ?: "Couldn't reach your server", Modifier.padding(16.dp), actionLabel = "Try again", onAction = actions.reload)
        }
        !status.available -> Column(Modifier.fillMaxSize()) {
            BackBar(onBack, "AI assistant")
            EmptyState(Icons.Rounded.AutoAwesome, "Not on this server", "The AI assistant isn't turned on on this server.")
        }
        status.consent == null || state.choosing -> AiConsentScreen(
            status, on,
            onBack = { if (state.choosing) actions.choose(false) else onBack() },
            onAgree = actions.agree,
            onForget = if (status.consent != null) actions.forget else null,
        )
        else -> AiChatScreen(state, status, onBack, actions)
    }
}

/**
 * Before the assistant is turned on: what it means, plainly. What it looks at goes to Anthropic
 * (the server's own notice, with their terms and privacy policy); it never sees progress photos
 * (a food photo goes only when the person snaps one, from Food); it can
 * be wrong and isn't medical advice; the person picks what it may look at (private features
 * each need ticking, and their own tick); logging for them is a separate choice.
 */
@Composable
fun AiConsentScreen(status: AiStatus, on: Set<Module>, onBack: () -> Unit, onAgree: (AiConsent) -> Unit, onForget: (() -> Unit)? = null) {
    val existing = status.consent
    val offered = AiText.offered(on)
    var modules by remember { mutableStateOf(AiText.initial(existing, on)) }
    var sensitiveAck by remember { mutableStateOf(existing?.sensitiveAcknowledged ?: false) }
    var logging by remember { mutableStateOf(existing?.allowLogging ?: false) }
    var acknowledged by remember { mutableStateOf(existing?.acknowledged ?: false) }
    var confirmForget by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, if (existing == null) "Before you turn it on" else "What it can look at")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "An optional helper that answers questions about your own health data. Here's what turning it on means.",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Point(Icons.Rounded.CloudUpload, "Where it goes") {
                Text(status.dataNotice, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { runCatching { uri.openUri(status.termsUrl) } }) {
                        Text("${status.provider}'s commercial terms")
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp))
                    }
                    TextButton(onClick = { runCatching { uri.openUri(status.privacyUrl) } }) {
                        Text("${status.provider}'s privacy policy")
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp))
                    }
                }
            }
            Point(Icons.Rounded.HideImage, "What it never sees") {
                Text("Your progress photos, and anyone else's data.", style = MaterialTheme.typography.bodyMedium)
                Hint("With Food allowed, you can snap a photo of a meal to estimate what's in it. That photo goes to ${status.provider} only when you take one, and isn't kept on your server.")
            }
            Point(Icons.Rounded.WarningAmber, "It can be wrong") {
                Text(
                    "Answers can be mistaken or miss things, and they aren't medical advice. Check anything important with a doctor, nurse or pharmacist.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            InfoCard("What it may look at", icon = Icons.Rounded.Visibility) {
                val everyday = offered.filter { !it.sensitive }
                val private = offered.filter { it.sensitive }
                if (offered.isEmpty()) Hint("Turn on some features first.")
                everyday.forEach { m -> Tick(m.label, m in modules) { modules = if (it) modules + m else modules - m } }
                if (private.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Private features", style = MaterialTheme.typography.titleSmall)
                    Hint("None of these is chosen for you. Tick any you'd like it to look at.")
                    private.forEach { m -> Tick(m.label, m in modules) { modules = if (it) modules + m else modules - m } }
                    if (modules.any { it.sensitive }) {
                        Spacer(Modifier.size(4.dp))
                        Tick("I understand that what these private features hold will be sent to ${status.provider} when it looks at them.", sensitiveAck) { sensitiveAck = it }
                    }
                }
            }
            if (status.loggingAllowed) {
                InfoCard("Logging for you") {
                    LabeledSwitch(
                        "Let it log things for me", logging, { logging = it },
                        subtitle = "Only when you ask, like \"log a toilet trip at 3 pm\". Off unless you turn it on.",
                    )
                }
            }
            AppCard(padding = 12.dp) {
                Tick("I've read this, and I understand that what the assistant looks at is sent to ${status.provider}.", acknowledged) { acknowledged = it }
            }
            val ready = AiText.canAgree(acknowledged, modules, sensitiveAck)
            Button(
                enabled = ready,
                onClick = { onAgree(AiText.consent(modules, acknowledged, sensitiveAck, logging, status)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existing == null) "Turn it on" else "Save my choices") }
            if (!ready) {
                Hint(
                    if (!acknowledged) "Tick that you've read this to carry on." else "Tick that you understand about the private features, or untick them.",
                    Modifier.padding(horizontal = 4.dp),
                )
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(if (existing == null) "Not now" else "Cancel") }
            if (onForget != null) {
                TextButton(onClick = { confirmForget = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Stop and forget my choice") }
            }
        }
    }
    if (confirmForget && onForget != null) ForgetDialog({ confirmForget = false }) { confirmForget = false; onForget() }
}

@Composable
private fun ForgetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop the assistant?") },
        text = { Text("It stops looking at anything, and your choice is forgotten. You can turn it on again later.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop and forget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Point(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    InfoCard(title, icon = icon) { Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }
}

@Composable
private fun Tick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** The conversation: kept on this phone only, sent with each question; under each reply, what it looked at. */
@Composable
fun AiChatScreen(state: AiState, status: AiStatus, onBack: () -> Unit, actions: AiActions) {
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(state.turns.size, state.sending) { scroll.animateScrollTo(scroll.maxValue) }
    val consent = status.consent
    val atLimit = AiText.atLimit(status)
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(onBack, "AI assistant") {
            if (state.turns.isNotEmpty()) IconButton(onClick = actions.newChat) { Icon(Icons.Rounded.AddComment, "Start a new conversation") }
            IconButton(onClick = { actions.choose(true) }) { Icon(Icons.Rounded.Tune, "Change what it can look at") }
            IconButton(onClick = actions.audit) { Icon(Icons.Rounded.History, "What it looked at, and when") }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint(
                "Answers can be wrong and aren't medical advice. It can look at: " +
                    (consent?.modules.orEmpty().sortedBy { it.ordinal }.joinToString(", ") { it.label }.ifEmpty { "nothing yet" }) +
                    ". This conversation stays on this phone, and is gone when you leave.",
                Modifier.padding(horizontal = 4.dp),
            )
            if (state.turns.isEmpty()) {
                Text("Ask about your own data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiText.suggestions(consent?.modules.orEmpty()).forEach { q ->
                        SuggestionChip(onClick = { if (!atLimit) actions.ask(q) }, label = { Text(q) })
                    }
                }
            }
            state.turns.forEach { Bubble(it) }
            if (state.sending) {
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Hint("Looking…")
                }
            }
            state.chatError?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(AiText.usage(status.usedToday, status.dailyLimit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            if (status.dailyLimit > 0) ProgressBar(status.usedToday.toFloat() / status.dailyLimit, Modifier.padding(vertical = 4.dp), color = if (atLimit) AppTheme.status.warn else MaterialTheme.colorScheme.primary, height = 4.dp)
            if (atLimit) Hint("That's today's questions. The assistant is back tomorrow.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it.take(4000) },
                    placeholder = { Text("Ask something") },
                    enabled = !atLimit,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = text.isNotBlank() && !state.sending && !atLimit, onClick = { actions.ask(text); text = "" }) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val mine = turn.role == ChatTurn.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            if (!mine) {
                Row(Modifier.padding(top = 4.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Visibility, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(AiText.looked(turn.looked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Each question asked: when, and which features it looked at. Never what was asked or said. */
@Composable
fun AiAuditScreen(audit: List<AiAuditEntry>?, zone: ZoneId, onBack: () -> Unit, onForget: () -> Unit = {}, hasConsent: Boolean = true) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "What it looked at")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(
                "Each time you asked in the last 30 days: when, and which features it looked at. This never keeps what you asked or what it said.",
                Modifier.padding(horizontal = 4.dp),
            )
            when {
                audit == null -> Loading()
                audit.isEmpty() -> EmptyState(Icons.Rounded.History, "Nothing yet", "Questions you ask show up here.")
                else -> InfoCard(null) {
                    audit.sortedByDescending { it.at }.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.semantics(mergeDescendants = true) {}) {
                            Text(
                                CareFormat.dayTime(a.at, zone) + " · " + when (a.kind) {
                                    "chat" -> "A question"
                                    "food_photo" -> "A food photo"
                                    "breakdown" -> "Breaking down a task"
                                    else -> a.kind.replaceFirstChar { it.uppercase() }
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Hint(
                                (if (a.modules.isEmpty()) "Looked at nothing of your health data" else "Looked at " + a.modules.sortedBy { it.ordinal }.joinToString(", ") { it.label }) +
                                    if (a.tools.isNotEmpty()) " · ${a.tools.size} ${if (a.tools.size == 1) "look" else "looks"}" else "",
                            )
                        }
                    }
                }
            }
            if (hasConsent) {
                OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) { Text("Stop and forget my choice") }
            }
        }
    }
    if (confirmForget) ForgetDialog({ confirmForget = false }) { confirmForget = false; onForget() }
}
