package com.health.app.ui.theme

import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.health.app.data.DeviceChoices

/*
 * Calm mode (Autism & ADHD, Fields.NEURO_CALM, kept on the phone): the same app with less going
 * on. The aurora glow goes and colours soften towards the slate neutrals; the app's own animations
 * (the focus ring, the pitch trace) ask [reduceMotion] and keep still.
 */

/** Whether calm mode is on here. */
val LocalCalm = staticCompositionLocalOf { false }

/** Whether to keep things still: calm mode, or the phone's "remove animations". */
@Composable
fun reduceMotion(): Boolean {
    val calm = LocalCalm.current
    val context = LocalContext.current
    val phone = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    return calm || phone
}

/** Calm mode as the person set it on this phone, around [content]. */
@Composable
fun CalmMode(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val choices = remember(context) { DeviceChoices.of(context) }
    val calm by choices.calm.collectAsState()
    CalmLayer(calm, content)
}

/** The theme as it is, or (with [calm]) softened and without the glow. */
@Composable
fun CalmLayer(calm: Boolean, content: @Composable () -> Unit) {
    if (!calm) {
        CompositionLocalProvider(LocalCalm provides false, content = content)
        return
    }
    val backdrop = LocalBackdrop.current
    CompositionLocalProvider(
        LocalCalm provides true,
        LocalBackdrop provides backdrop.copy(glowA = Color.Transparent, glowB = Color.Transparent),
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.calmed(),
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}

/** The theme's colours drawn a third of the way to the slate neutrals: still themed, quieter. */
fun ColorScheme.calmed(): ColorScheme {
    fun soft(c: Color, toward: Color, by: Float) = lerp(c, toward, by)
    return copy(
        primary = soft(primary, onSurfaceVariant, 0.35f),
        secondary = soft(secondary, onSurfaceVariant, 0.35f),
        tertiary = soft(tertiary, onSurfaceVariant, 0.35f),
        primaryContainer = soft(primaryContainer, surfaceVariant, 0.5f),
        secondaryContainer = soft(secondaryContainer, surfaceVariant, 0.5f),
        tertiaryContainer = soft(tertiaryContainer, surfaceVariant, 0.5f),
        onPrimaryContainer = soft(onPrimaryContainer, onSurface, 0.35f),
        onSecondaryContainer = soft(onSecondaryContainer, onSurface, 0.35f),
        onTertiaryContainer = soft(onTertiaryContainer, onSurface, 0.35f),
    )
}
