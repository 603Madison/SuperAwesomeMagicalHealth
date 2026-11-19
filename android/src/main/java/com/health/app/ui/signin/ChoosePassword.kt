package com.health.app.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.appContainer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A password an admin set (a new account's, or a reset) is temporary: right after signing in with
 * one, the person chooses their own before anything else opens (the server answers nothing else).
 */
object ChoosePassword {
    const val TITLE = "Choose a new password"
    const val WHY = "An admin set the one you signed in with. Choose your own, so only you know it."
    const val MIN_LENGTH = 8

    /** How long Settings says an admin set the person's password. */
    const val SHOWN_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * "An admin set your password on 3 November 2026", for 90 days after an admin set it, so an
     * admin quietly signing in as someone gets noticed; else null.
     */
    fun adminSet(at: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        if (at == null || now - at > SHOWN_MS) return null
        val date = DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(Instant.ofEpochMilli(at).atZone(zone))
        return "An admin set your password on $date."
    }
}

@Composable
fun ChoosePasswordRoute() {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Backdrop {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(ChoosePassword.TITLE, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(ChoosePassword.WHY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                        OutlinedTextField(
                            current, { current = it },
                            label = { Text("The password you were given") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            next, { next = it },
                            label = { Text("New password") },
                            supportingText = { Text("At least ${ChoosePassword.MIN_LENGTH} characters") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = !busy && current.isNotEmpty() && next.length >= ChoosePassword.MIN_LENGTH,
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        c.api.changePassword(current, next)
                                        c.prefs.update { s -> s.copy(user = s.user?.copy(mustChangePassword = false)) }
                                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                        message = e.message ?: "Couldn't save your password"
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "Saving…" else "Save my password") }
                        TextButton(onClick = { scope.launch { c.signOut() } }) { Text("Sign out") }
                    }
                }
            }
        }
    }
}
