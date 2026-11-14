package com.health.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.health.app.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in to the server. With single sign-on (Authentik…) the server's own page does it, in a
 * browser tab: the person signs in there (or already is), agrees to sign the app in, and the
 * page hands back a one-time code at `samhealth://signed-in`, which only works together with the
 * secret this app made for that sign-in (PKCE), so another app catching the link gets nothing.
 */
class SignIn(private val context: Context, private val api: ServerApi, private val prefs: Prefs) {
    private val pending = context.getSharedPreferences("sign-in", Context.MODE_PRIVATE)

    private val _finishing = MutableStateFlow(false)

    /** Whether a browser sign-in is being finished (kept here, so turning the phone doesn't lose it). */
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last browser sign-in couldn't be finished, if it couldn't. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Finishes a browser sign-in from [link] in [scope] (the app's, so turning the phone or leaving
     * the screen can't cut the code exchange short); [finishing] and [error] say how it went.
     */
    fun finishInBackground(link: Uri, scope: CoroutineScope) {
        if (_finishing.value) return
        _finishing.value = true
        _error.value = null
        scope.launch {
            try {
                finish(link)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't finish signing in"
            } finally {
                _finishing.value = false
            }
        }
    }

    suspend fun signInWithPassword(server: String, username: String, password: String) {
        val config = api.authConfig(server)
        val signedIn = api.login(server, username, password, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
    }

    /** Opens the server's sign-in page in a browser tab. */
    fun startBrowserSignIn(server: String) {
        val verifier = token(48)
        val state = token(24)
        pending.edit {
            putString("server", server)
            putString("verifier", verifier)
            putString("state", state)
        }
        val url = "$server/connect-app".toUri().buildUpon()
            .appendQueryParameter("challenge", challenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", deviceName())
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url)
    }

    /**
     * Finishes a browser sign-in from the link the server's page opened. The secret and state it
     * was started with are forgotten only once it has worked, so one that's cut short or fails
     * doesn't leave the next try with nothing to finish with.
     */
    suspend fun finish(link: Uri) {
        val server = pending.getString("server", null) ?: throw ServerException(0, "That sign-in wasn't started here; try again")
        val verifier = pending.getString("verifier", null) ?: throw ServerException(0, "That sign-in expired; try again")
        val expected = pending.getString("state", null)
        val code = link.getQueryParameter("code") ?: throw ServerException(0, "The server sent no sign-in code")
        if (expected == null || link.getQueryParameter("state") != expected) throw ServerException(0, "That sign-in link wasn't for this sign-in")
        val config = api.authConfig(server)
        val signedIn = api.exchangeCode(server, code, verifier, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
        pending.edit { clear() }
    }

    companion object {
        private val random = SecureRandom()

        fun deviceName(): String = listOf(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }.distinct().joinToString(" ").take(60).ifBlank { "Android phone" }

        fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        /** The PKCE S256 challenge for [verifier]. */
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
