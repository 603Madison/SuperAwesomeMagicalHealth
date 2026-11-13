package com.health.app.ui.conditions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.health.api.AiStatus
import com.health.app.Container
import com.health.app.HealthApp
import com.health.app.net.aiStatus
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Hint
import com.health.app.ui.components.SectionCard
import com.health.app.ui.modules.NavRow

/** The app's parts where there are any (not in previews and screenshot tests, which have none). */
@Composable
internal fun containerOrNull(): Container? = (LocalContext.current.applicationContext as? HealthApp)?.container

/**
 * Settings' section for what the app shows: conditions and goals, the fields they bring, and the
 * AI assistant (one line when the server doesn't offer it). Hidden while viewing someone's share.
 */
@Composable
fun YourHealthSettings(onOpen: (String) -> Unit) {
    if (LocalReadOnly.current) return
    val c = containerOrNull()
    var ai by remember { mutableStateOf<AiStatus?>(null) }
    LaunchedEffect(c) { if (c != null) ai = runCatching { c.api.aiStatus() }.getOrNull() }
    SectionCard("What you see") {
        NavRow(Icons.Rounded.MedicalInformation, "Conditions and goals", "What you live with or want to keep track of", onClick = { onOpen("settings/conditions") })
        NavRow(Icons.Rounded.EditNote, "Fields", "The details each form asks for", onClick = { onOpen("settings/fields") })
        val status = ai
        when {
            status == null -> Unit
            !status.available -> Hint("The AI assistant isn't turned on on this server.")
            else -> NavRow(
                Icons.Rounded.AutoAwesome, "AI assistant",
                if (status.consent != null) "On: it can look at what you chose" else "Optional. Read what it means before turning it on",
                onClick = { onOpen("settings/ai") },
            )
        }
    }
}

/** The hub's way to conditions and goals. */
@Composable
fun ConditionsHubRow(onOpen: (String) -> Unit) {
    NavRow(Icons.Rounded.MedicalInformation, "Conditions and goals", "Tell the app what you live with, and it shows what fits", onClick = { onOpen("settings/conditions") })
}
