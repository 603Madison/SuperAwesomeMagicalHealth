package com.health.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.theme.HealthTheme

/** What the app does with health data: shown from Health Connect's permission screens. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent {
            val settings by c.settings.collectAsState()
            HealthTheme(settings.themeMode, settings.wallpaperColors, settings.boldText, settings.palette) {
                PrivacyScreen(onClose = ::finish)
            }
        }
    }
}

@Composable
fun PrivacyScreen(onClose: () -> Unit) {
    Backdrop {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
            ScreenTitle("Your health data")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppCard {
                    Text("Where it goes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "SuperAwesomeMagicalHealth reads what you allow from Health Connect (steps, heart rate, sleep, workouts, " +
                            "weight and the like) and sends it to your own SuperAwesomeMagicalHealth server, the one you signed in to. " +
                            "Nothing goes anywhere else: no ads, no analytics, no third parties.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                AppCard {
                    Text("What it's for", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your server turns it into your readiness, sleep and activity scores, trends over weeks and months, and how " +
                            "your eating lines up with what you burn. The app never writes to Health Connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                AppCard {
                    Text("Changing your mind", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Take permissions back in Health Connect at any time, or turn Health Connect off in the app's Devices tab. " +
                            "Data already on your server can be deleted there, device by device.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
    }
}
