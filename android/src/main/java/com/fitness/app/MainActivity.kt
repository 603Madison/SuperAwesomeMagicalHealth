package com.fitness.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.fitness.app.ui.MainScaffold
import com.fitness.app.ui.signin.SignInRoute
import com.fitness.app.ui.theme.FitnessTheme
import com.fitness.app.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.launch

/** The scrims Android's default edge-to-edge puts behind three-button navigation. */
private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    private val finishing = mutableStateOf(false)
    private val signInError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val c = container
        setContent {
            val settings by c.settings.collectAsState()
            // The status and navigation bars' icons follow the app's theme, which may not be the phone's.
            val dark = isAppInDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            FitnessTheme(settings.themeMode, settings.wallpaperColors, settings.boldText, settings.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings.signedIn && !finishing.value) MainScaffold() else SignInRoute(finishing.value, signInError.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a good moment to bring in what the ring and watch recorded.
        val c = container
        if (c.settings.value.signedIn) c.syncNowQuietly()
    }

    /** The server's sign-in page sends the app back here with its one-time code. */
    private fun handle(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme != "samfitness" || link.host != "signed-in") return
        finishing.value = true
        signInError.value = null
        lifecycleScope.launch {
            try {
                container.signIn.finish(link)
            } catch (e: Exception) {
                signInError.value = e.message ?: "Couldn't finish signing in"
            }
            finishing.value = false
        }
    }
}
