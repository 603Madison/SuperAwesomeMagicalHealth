package com.health.app.ui.modules.photos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.health.app.data.DeviceChoices
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The activity a composable is drawn in. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Keeps the window out of screenshots, screen recordings and the recent-apps preview while it's shown. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Whether the photos are unlocked: until the app has been in the background for a couple of minutes. */
object PhotoUnlock {
    private val _unlocked = MutableStateFlow(false)
    val unlocked = _unlocked.asStateFlow()
    private var stoppedAt = 0L

    /** The app went out of sight (the screen lock, another app, or the photo picker for a moment). */
    fun stopped(now: Long = System.currentTimeMillis()) {
        stoppedAt = now
    }

    /** The app's back: locked again if it was away long enough to have been put down. */
    fun started(now: Long = System.currentTimeMillis()) {
        if (stoppedAt > 0 && now - stoppedAt > AWAY_MS) lock()
        stoppedAt = 0
    }

    private const val AWAY_MS = 2 * 60_000L

    private var open = 0
    private var leftAt = 0L

    /** A photo page opened: if photos were left a while ago, they lock again. */
    fun entered(now: Long = System.currentTimeMillis()) {
        if (open == 0 && leftAt > 0 && now - leftAt > LEFT_MS) lock()
        open++
    }

    /** A photo page closed (moving between photo pages keeps them open). */
    fun left(now: Long = System.currentTimeMillis()) {
        open = (open - 1).coerceAtLeast(0)
        if (open == 0) leftAt = now
    }

    private const val LEFT_MS = 30_000L

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
        PhotoMemory.clear()
    }

    /** What may unlock the photos: a fingerprint or face, or the phone's screen lock. */
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Whether this phone can ask (a screen lock is set). */
    fun available(context: Context): Boolean = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Asks for a fingerprint, face or the screen lock; [onDone] hears whether it worked. */
    fun ask(context: Context, onDone: (Boolean) -> Unit) {
        val activity = context.findActivity() as? FragmentActivity ?: return onDone(false)
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock your photos")
                .setSubtitle("Progress photos are private")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

/**
 * Every photo page goes through here: the window kept private, and (with the lock on, as set on
 * this phone) a fingerprint, face or the screen lock asked for before anything shows.
 */
@Composable
fun PhotoGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val choices = remember { DeviceChoices.of(context) }
    val lockOn by choices.photoLock.collectAsState()
    val unlocked by PhotoUnlock.unlocked.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> PhotoUnlock.stopped()
                Lifecycle.Event.ON_START -> PhotoUnlock.started()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        PhotoUnlock.entered()
        onDispose {
            lifecycle.removeObserver(observer)
            PhotoUnlock.left()
        }
    }
    if (!lockOn || unlocked) {
        content()
        return
    }
    LaunchedEffect(Unit) { PhotoUnlock.ask(context) { ok -> if (ok) PhotoUnlock.unlock() } }
    LockedPhotos(onBack) { PhotoUnlock.ask(context) { ok -> if (ok) PhotoUnlock.unlock() } }
}

@Composable
fun LockedPhotos(onBack: () -> Unit, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackBar(onBack, "Progress photos")
        EmptyState(Icons.Rounded.Lock, "Your photos are locked", "Unlock with your fingerprint, face or screen lock to see them.") {
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
