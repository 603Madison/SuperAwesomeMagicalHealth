@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.MealieStatus
import com.health.app.Container
import com.health.app.net.connectMealie
import com.health.app.net.disconnectMealie
import com.health.app.net.mealieStatus
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.food.MealieEdit
import com.health.app.ui.food.MealieText
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Settings › Mealie: connecting the person's Mealie with its address and an API token, so its
 * recipes and meal plan come into Food. The token is typed here, sent to the server once, and kept
 * only in memory while the page is open: never saved on the phone, and cleared once it's connected.
 */

data class MealieState(
    val status: MealieStatus? = null,
    val loading: Boolean = true,
    /** Why the status couldn't be read (an older server, or no answer). */
    val error: String? = null,
    val edit: MealieEdit = MealieEdit.NONE,
    val url: String = "",
    /** In memory only, while the page is open; cleared once connected. */
    val token: String = "",
    val working: Boolean = false,
    /** Why connecting didn't work, in the server's words ("Mealie didn't accept the token…"). */
    val problem: String? = null,
)

class MealieViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(MealieState())
    val state: StateFlow<MealieState> = _state.asStateFlow()

    /** Mealie is only ever the person's own: never asked about while viewing a share. */
    fun load() {
        if (c.viewing.value != null) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val status = c.api.mealieStatus()
                _state.update { it.copy(status = status, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't reach your server") }
            }
        }
    }

    fun url(text: String) = _state.update { it.copy(url = text, problem = null) }

    fun token(text: String) = _state.update { it.copy(token = text, problem = null) }

    /** Changing the address (it starts as it is) or the token (it starts empty), or neither. */
    fun edit(edit: MealieEdit) = _state.update {
        it.copy(edit = edit, url = if (edit == MealieEdit.ADDRESS) it.status?.url.orEmpty() else "", token = "", problem = null)
    }

    fun connect() {
        val s = _state.value
        val request = MealieText.request(s.status, s.edit, s.url, s.token) ?: return
        if (s.working || c.viewing.value != null) return
        _state.update { it.copy(working = true, problem = null) }
        viewModelScope.launch {
            try {
                val status = c.api.connectMealie(request)
                // The token leaves the screen (and the phone's memory) once it's connected.
                _state.update { it.copy(status = status, working = false, edit = MealieEdit.NONE, url = "", token = "", problem = null) }
                c.tell(if (s.status?.connected == true) "Saved" else "Mealie is connected")
                c.dataChanged()
            } catch (e: Exception) {
                _state.update { it.copy(working = false, problem = e.message ?: "Couldn't connect to Mealie") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                c.api.disconnectMealie()
                _state.update { MealieState(status = MealieStatus(), loading = false) }
                c.tell("Mealie is disconnected")
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't disconnect Mealie")
            }
        }
    }
}

class MealieActions(
    val url: (String) -> Unit = {},
    val token: (String) -> Unit = {},
    val connect: () -> Unit = {},
    val edit: (MealieEdit) -> Unit = {},
    val disconnect: () -> Unit = {},
    val reload: () -> Unit = {},
)

@Composable
fun MealieRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MealieViewModel(c) }
    val state by vm.state.collectAsState()
    val readOnly = LocalReadOnly.current
    LaunchedEffect(readOnly) { if (!readOnly) vm.load() }
    if (readOnly) {
        Column(Modifier.fillMaxSize()) {
            BackBar(onBack, "Mealie")
            EmptyState(Icons.Rounded.Lock, "Only for your own data", "Go back to your own data to connect Mealie.")
        }
        return
    }
    MealieScreen(
        state,
        MealieActions(url = vm::url, token = vm::token, connect = vm::connect, edit = vm::edit, disconnect = vm::disconnect, reload = vm::load),
        onBack,
    )
}

/**
 * Not connected: what it brings, then its address and an API token, and Connect. Connected: where
 * and as whom, with a new address or token, or disconnecting (after asking).
 */
@Composable
fun MealieScreen(state: MealieState, actions: MealieActions, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val status = state.status
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Mealie")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                status == null && state.loading -> Loading()
                status == null -> ErrorCard(state.error ?: "Couldn't reach your server", actionLabel = "Try again", onAction = actions.reload)
                !status.connected -> {
                    InfoCard("Recipes and meal plans", icon = Icons.AutoMirrored.Rounded.MenuBook) {
                        Text(MealieText.INTRO, style = MaterialTheme.typography.bodyMedium)
                        Hint("Nothing is changed in Mealie.", Modifier.padding(top = 6.dp))
                    }
                    ConnectForm("Connect your Mealie", state, actions, address = true, token = true)
                }
                else -> {
                    InfoCard("Your Mealie", icon = Icons.Rounded.CheckCircle, iconTint = AppTheme.status.good) {
                        Text(MealieText.connected(status), style = MaterialTheme.typography.bodyMedium)
                        MealieText.version(status)?.let { Hint(it, Modifier.padding(top = 2.dp)) }
                        Hint("Your recipes show up when you add food, and each day's plan is on the Food tab.", Modifier.padding(top = 6.dp))
                        if (state.edit == MealieEdit.NONE) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { actions.edit(MealieEdit.ADDRESS) }) { Text("Change address") }
                                FilledTonalButton(onClick = { actions.edit(MealieEdit.TOKEN) }) { Text("New token") }
                                OutlinedButton(onClick = { confirm = true }) { Text("Disconnect") }
                            }
                        }
                    }
                    when (state.edit) {
                        MealieEdit.ADDRESS -> ConnectForm("Change the address", state, actions, address = true, token = false, note = "Your token stays as it is.")
                        MealieEdit.TOKEN -> ConnectForm("A new token", state, actions, address = false, token = true)
                        MealieEdit.NONE -> Unit
                    }
                }
            }
            state.problem?.let { ErrorCard(it) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect Mealie?") },
            text = { Text("Its recipes and meal plan leave Food. What you've logged stays, and nothing changes in Mealie.") },
            confirmButton = { TextButton(onClick = { confirm = false; actions.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** The address and token to send (either or both), with Connect (or Save) showing progress while the server checks with Mealie. */
@Composable
private fun ConnectForm(title: String, state: MealieState, actions: MealieActions, address: Boolean, token: Boolean, note: String? = null) {
    val ready = MealieText.request(state.status, state.edit, state.url, state.token) != null && !state.working
    val first = state.status?.connected != true
    InfoCard(title, icon = Icons.Rounded.Link) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            note?.let { Hint(it) }
            if (address) {
                OutlinedTextField(
                    state.url, actions.url,
                    label = { Text("Address") },
                    placeholder = { Text(MealieText.ADDRESS_HINT) },
                    singleLine = true,
                    enabled = !state.working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (token) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ready) actions.connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (token) {
                OutlinedTextField(
                    state.token, actions.token,
                    label = { Text("API token") },
                    supportingText = { Text(MealieText.TOKEN_HINT) },
                    singleLine = true,
                    enabled = !state.working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ready) actions.connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = ready, onClick = actions.connect, modifier = Modifier.weight(1f)) {
                    if (state.working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking…")
                    } else {
                        Text(if (first) "Connect" else "Save")
                    }
                }
                if (!first) OutlinedButton(enabled = !state.working, onClick = { actions.edit(MealieEdit.NONE) }) { Text("Cancel") }
            }
        }
    }
}
