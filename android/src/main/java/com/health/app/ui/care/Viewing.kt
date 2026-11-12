package com.health.app.ui.care

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.health.api.Module
import com.health.app.ui.components.appContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * True while the person is looking at someone else's shared data: every screen hides the
 * controls that would change anything (and the app refuses such changes anyway).
 */
val LocalReadOnly = staticCompositionLocalOf { false }

/** The features to show now: the shared person's while viewing a share, else the person's own. */
@Composable
fun shownModules(): Set<Module> {
    val c = appContainer()
    val profile by c.profile.collectAsState()
    val viewing by c.viewing.collectAsState()
    val viewProfile by c.viewProfile.collectAsState()
    return viewing?.let { share -> viewProfile?.modules ?: share.modules } ?: profile?.modules ?: Module.DEFAULT
}

/** Across the top while viewing a share: whose data this is, and the way back. */
@Composable
fun ViewingBanner(name: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Viewing $name · read-only",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onBack) { Text("Back to mine") }
        }
    }
}
