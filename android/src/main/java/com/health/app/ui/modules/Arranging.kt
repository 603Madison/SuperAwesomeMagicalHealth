package com.health.app.ui.modules

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.health.api.Fields
import com.health.api.LayoutPrefs
import com.health.api.Layouts
import com.health.app.HealthApp
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.arranged

/*
 * Arranging the feature screens (docs/API.md, "Arranging screens"). Each screen lists its sections
 * in the default order as ArrangeItems from a plain function (so the list can be tested), only those
 * that apply now; draws them with [arrangedIn], in the person's order, less those hidden; and puts
 * [ArrangeAction] in its title or back bar. The keys ([FeatureScreens]) and section ids are saved on
 * the server: never rename them.
 */

/** The feature screens' keys, `android.<screen>`. */
object FeatureScreens {
    val MIND = key("mind")
    val NEURO = key("neuro")
    /** Cycle, with the life stage's cards among its sections. */
    val CYCLE = key("cycle")
    val CONTINENCE = key("continence")
    val CONTINENCE_PROGRESS = key("continence.progress")
    /** Both areas' supplies (bladder and bowel, period products): one section a supply. */
    val SUPPLIES = key("supplies")
    val MEDS_TODAY = key("meds.today")
    val MEDS_LABS = key("meds.labs")
    val VITALS = key("vitals")
    val SYMPTOMS = key("symptoms")
    val HABITS = key("habits")
    val HORMONES = key("hormones")
    val APPOINTMENTS = key("appointments")
    val REPORTS = key("reports")
    val SHARING = key("sharing")
    val TRANSITION = key("transition")
    val PHOTOS = key("photos")
    val MEASUREMENTS = key("measurements")
    val TRENDS = key("trends")

    val ALL: List<String> = listOf(
        MIND, NEURO, CYCLE, CONTINENCE, CONTINENCE_PROGRESS, SUPPLIES, MEDS_TODAY, MEDS_LABS, VITALS, SYMPTOMS, HABITS,
        HORMONES, APPOINTMENTS, REPORTS, SHARING, TRANSITION, PHOTOS, MEASUREMENTS, TRENDS,
    )

    private fun key(screen: String) = Layouts.screen(Layouts.ANDROID, screen)
}

/**
 * A section id for something the person named or the server numbered (a habit, a supply, a lab
 * test): "[prefix].[slug]", in the letters, digits, dots, dashes and underscores the server takes.
 */
fun sectionId(prefix: String, name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "x" }
    return "$prefix.$slug".take(Layouts.MAX_ID)
}

/** Arrangements given directly where there's no app to read them from (screenshots and previews). */
val LocalArrangements = staticCompositionLocalOf<LayoutPrefs?> { null }

/** Fields given directly where there's no app to read them from (screenshots and previews). */
val LocalShownFields = staticCompositionLocalOf<Set<String>?> { null }

/**
 * The ids of [items] to show on the screen [key], in the person's order, less those hidden: the
 * app's [arranged], or [LocalArrangements] (else the default order) where there's no app.
 */
@Composable
fun arrangedIn(key: String, items: List<ArrangeItem>): List<String> {
    val given = LocalArrangements.current
    return when {
        given != null -> Layouts.visible(items, { it.id }, given.screens[key]).map { it.id }
        LocalContext.current.applicationContext !is HealthApp -> items.map { it.id }
        else -> arranged(key, items)
    }
}

/** Draws each of [ids] in order, keyed by its id so what it remembers follows it when it moves. */
@Composable
fun Sections(ids: List<String>, content: @Composable (String) -> Unit) {
    ids.forEach { id -> key(id) { content(id) } }
}

/** Whether forms and cards show [field]: the app's fields, [LocalShownFields], or the defaults where there's neither. */
@Composable
fun fieldShown(field: String): Boolean {
    LocalShownFields.current?.let { return field in it }
    val app = LocalContext.current.applicationContext as? HealthApp ?: return field in Fields.DEFAULTS
    val layout by app.container.fields.collectAsState()
    return field in (layout?.effective ?: Fields.DEFAULTS)
}

/** Whether water is tracked ([Fields.NUTRITION_WATER]): hidden, drinks, habits and charts leave it out. */
@Composable
fun waterShown(): Boolean = fieldShown(Fields.NUTRITION_WATER)

/**
 * The title or back bar's Arrange button, and the sheet it opens: for a screen with something to
 * arrange, and never while viewing someone's share.
 */
@Composable
fun ArrangeAction(key: String, title: String, items: List<ArrangeItem>) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    if (items.size < 2) return
    ArrangeButton { open = true }
    if (open) ArrangeSheet(key, title, items, onDismiss = { open = false })
}

/*
 * Card title rows are the same everywhere: at most one primary action as an icon ([TitleAction]),
 * then a chevron that opens the feature's page ([OpenChevron]), both in InfoCard's trailing slot in
 * that order. They draw as InfoCard's own `action` (a CardAction) and `onOpen` do.
 */

/** A card's way to its page, on the card's title row. */
@Composable
fun OpenChevron(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, description, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** A card's one primary action, as an icon on its title row. */
@Composable
fun TitleAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, description, tint = MaterialTheme.colorScheme.primary) }
}

/** Nothing here yet: one line, and at most one thing to do about it. */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    AppCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onAction) { Text(action, maxLines = 1) }
            }
        }
    }
}
