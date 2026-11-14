package com.health.app.ui.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.AuthConfig
import com.health.app.R
import com.health.app.net.ServerApi
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What signing in needs from the rest of the app (so it can be drawn on its own). */
class SignInActions(
    val checkServer: suspend (String) -> AuthConfig,
    val browserSignIn: (String) -> Unit,
    val passwordSignIn: suspend (server: String, username: String, password: String) -> Unit,
)

@Composable
fun SignInRoute(finishing: Boolean, error: String?) {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    SignInScreen(
        initialServer = settings.server,
        finishing = finishing,
        error = error,
        actions = SignInActions(
            checkServer = { c.api.authConfig(it) },
            browserSignIn = { c.signIn.startBrowserSignIn(it) },
            passwordSignIn = { s, u, p -> c.signIn.signInWithPassword(s, u, p) },
        ),
    )
}

/** Sign-in: the server's address, then single sign-on in a browser tab, or a password. */
@Composable
fun SignInScreen(
    initialServer: String,
    finishing: Boolean,
    error: String?,
    actions: SignInActions,
    initialConfig: Pair<String, AuthConfig>? = null,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initialServer.removePrefix("https://")) }
    var config by remember { mutableStateOf(initialConfig) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(error) { mutableStateOf(error) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    fun check() {
        val server = ServerApi.normalize(address)
        if (server == null) {
            message = "That isn't a web address"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                config = server to actions.checkServer(server)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = "Couldn't reach a SuperAwesomeMagicalHealth server there (${e.message ?: e.javaClass.simpleName})"
            }
            busy = false
        }
    }

    fun signInWithPassword(server: String) {
        busy = true
        message = null
        scope.launch {
            try {
                actions.passwordSignIn(server, username.trim(), password)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = e.message ?: "Couldn't sign in"
            }
            busy = false
        }
    }

    Backdrop {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // The launcher icon is adaptive (painterResource can't draw those): its parts, drawn here.
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Text(
                    config?.second?.serverName ?: "SuperAwesomeMagicalHealth",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (config == null) "Sign in to your server to see your sleep, activity and food, and to bring in your ring and watch."
                    else "Sign in to keep going",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        message?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                        val current = config
                        when {
                            finishing -> {
                                CircularProgressIndicator()
                                Text("Finishing signing in…")
                            }
                            current == null -> {
                                OutlinedTextField(
                                    address, { address = it },
                                    label = { Text("Server address") },
                                    placeholder = { Text("health.example.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { if (!busy && address.isNotBlank()) check() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(enabled = !busy && address.isNotBlank(), onClick = ::check, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (busy) "Checking…" else "Continue")
                                }
                            }
                            else -> {
                                val (server, auth) = current
                                auth.oidc?.let { oidc ->
                                    Button(onClick = { actions.browserSignIn(server) }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Rounded.Login, null, Modifier.size(18.dp))
                                        Text(oidc.label, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                if (auth.localLogin || showPassword || auth.oidc == null) {
                                    OutlinedTextField(
                                        username, { username = it },
                                        label = { Text("Username") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        password, { password = it },
                                        label = { Text("Password") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { if (!busy && username.isNotBlank() && password.isNotEmpty()) signInWithPassword(server) }),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    val enabled = !busy && username.isNotBlank() && password.isNotEmpty()
                                    if (auth.oidc != null) {
                                        FilledTonalButton(enabled = enabled, onClick = { signInWithPassword(server) }, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (busy) "Signing in…" else "Sign in with password")
                                        }
                                    } else {
                                        Button(enabled = enabled, onClick = { signInWithPassword(server) }, modifier = Modifier.fillMaxWidth()) {
                                            Text(if (busy) "Signing in…" else "Sign in")
                                        }
                                    }
                                } else {
                                    TextButton(onClick = { showPassword = true }) {
                                        Icon(Icons.Rounded.Key, null, Modifier.size(16.dp))
                                        Text("Sign in with a password (admins)", modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                TextButton(
                                    onClick = { config = null },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                ) { Text("Use another server") }
                            }
                        }
                    }
                }
                config?.second?.let {
                    Text("Server ${it.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
