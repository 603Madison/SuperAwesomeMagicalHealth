@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.Units
import com.health.app.ui.Format

/** How much a tap on the Water quick action adds. */
const val QUICK_WATER_ML = 250

/** Each quick action's icon, and its words: in full on the + Log sheet, short under a round button. */
object QuickActions {
    fun icon(action: QuickAction): ImageVector = when (action) {
        QuickAction.FOOD -> Icons.Rounded.Restaurant
        QuickAction.WATER -> Icons.Rounded.WaterDrop
        QuickAction.MOOD -> Icons.Rounded.SentimentSatisfied
        QuickAction.PERIOD -> Icons.Rounded.CalendarMonth
        QuickAction.BLADDER -> Icons.Rounded.Wc
        QuickAction.DOSE -> Icons.Rounded.Medication
        QuickAction.WEIGHT -> Icons.Rounded.MonitorWeight
        QuickAction.COFFEE -> Icons.Rounded.LocalCafe
        QuickAction.DRINK -> Icons.Rounded.LocalBar
        QuickAction.WORKOUT -> Icons.Rounded.FitnessCenter
        QuickAction.SLEEP -> Icons.Rounded.Bedtime
        QuickAction.CHECK_IN -> Icons.Rounded.Mood
        QuickAction.SYMPTOM -> Icons.Rounded.Healing
        QuickAction.BLOOD_PRESSURE -> Icons.Rounded.MonitorHeart
        QuickAction.GLUCOSE -> Icons.Rounded.Bloodtype
        QuickAction.HABIT -> Icons.Rounded.Checklist
        QuickAction.ENERGY -> Icons.Rounded.Bolt
        QuickAction.OVERLOAD -> Icons.Rounded.Waves
        QuickAction.CALM -> Icons.Rounded.SelfImprovement
        QuickAction.MOMENT -> Icons.Rounded.AutoAwesome
        QuickAction.PHOTO -> Icons.Rounded.PhotoCamera
        QuickAction.MEASUREMENTS -> Icons.Rounded.Straighten
        QuickAction.APPOINTMENT -> Icons.AutoMirrored.Rounded.EventNote
    }

    /** Water says what a tap adds ("+250 ml"); the rest are their names. */
    fun label(action: QuickAction, units: Units): String =
        if (action == QuickAction.WATER) "+${Format.water(QUICK_WATER_ML, units)}" else action.label

    /** The word under a round button in the Quick log row: short, so six fit across a small phone. */
    fun short(action: QuickAction, units: Units): String = when (action) {
        QuickAction.FOOD -> "Food"
        QuickAction.WATER -> "+${Format.water(QUICK_WATER_ML, units)}"
        QuickAction.BLADDER -> "Bladder"
        QuickAction.DOSE -> "Meds"
        QuickAction.DRINK -> "Drink"
        QuickAction.SLEEP -> "Sleep"
        QuickAction.SYMPTOM -> "Symptom"
        QuickAction.BLOOD_PRESSURE -> "BP"
        QuickAction.HABIT -> "Habits"
        QuickAction.ENERGY -> "Energy"
        QuickAction.OVERLOAD -> "Too much"
        QuickAction.MEASUREMENTS -> "Measure"
        QuickAction.APPOINTMENT -> "Visit"
        else -> action.label
    }

    /** What a screen reader says for a button: "Log food", "Add 250 ml of water", "Log your mood". */
    fun spoken(action: QuickAction, units: Units): String = when (action) {
        QuickAction.WATER -> "Add ${Format.water(QUICK_WATER_ML, units)} of water"
        QuickAction.COFFEE -> "Add a coffee"
        QuickAction.MOOD -> "Log your mood"
        QuickAction.PERIOD -> "Log your period"
        QuickAction.BLADDER -> "Log bladder and bowel"
        QuickAction.DOSE -> "Take a dose"
        QuickAction.WEIGHT -> "Log your weight"
        QuickAction.DRINK -> "Add a drink"
        QuickAction.WORKOUT -> "Log a workout"
        QuickAction.SLEEP -> "Add a night's sleep"
        QuickAction.SYMPTOM -> "Log a symptom"
        QuickAction.BLOOD_PRESSURE -> "Log blood pressure"
        QuickAction.GLUCOSE -> "Log glucose"
        QuickAction.HABIT -> "Tick a habit"
        QuickAction.ENERGY -> "Log energy and focus"
        QuickAction.OVERLOAD -> "When it's too much"
        QuickAction.CALM -> "Something calming"
        QuickAction.MOMENT -> "Note a moment of euphoria"
        QuickAction.PHOTO -> "Take a progress photo"
        QuickAction.MEASUREMENTS -> "Log measurements"
        QuickAction.APPOINTMENT -> "Add an appointment"
        else -> action.label
    }

    /** A group's heading on the + Log sheet: the feature's name ("How you feel" for Check in, which is everyone's). */
    fun group(module: Module?): String = module?.label ?: "How you feel"
}

/** One action as a chip, on the + Log sheet. */
@Composable
fun QuickChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

/** Buttons in the Quick log row: the person's first few, then More. */
private const val SLOTS = QuickAction.SHOWN + 1

/**
 * Today's Quick log: one row of round buttons with short words under them, the person's first
 * [QuickAction.SHOWN] and "More" (the + Log sheet, [onMore]). It never wraps or scrolls: on a
 * narrow screen with large text the words get a step smaller, and if they still don't fit they
 * go, leaving each button's name for screen readers.
 */
@Composable
fun QuickLogRow(row: TodayLayout.QuickRow, units: Units, onAction: (QuickAction) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    if (row.shown.isEmpty() && row.more.isEmpty()) return
    val words = row.shown.map { QuickActions.short(it, units) } + "More"
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().semantics { heading(); contentDescription = "Quick log" }) {
        val slot = maxWidth / SLOTS
        val small = MaterialTheme.typography.labelSmall
        // A step smaller at a time (still scaled with the person's text size), tighter spacing last.
        val styles = listOf(
            MaterialTheme.typography.labelMedium, small,
            small.copy(fontSize = 10.sp, letterSpacing = 0.sp), small.copy(fontSize = 9.sp, letterSpacing = 0.sp),
        )
        // The first size every word fits at (a little room each side), or none: then no words.
        val style: TextStyle? = remember(words, slot, styles, density) {
            styles.firstOrNull { s -> words.all { w -> with(density) { measurer.measure(w, s, maxLines = 1).size.width.toDp() } <= slot - 2.dp } }
        }
        Row(Modifier.fillMaxWidth()) {
            row.shown.forEach { a ->
                QuickLogButton(QuickActions.icon(a), QuickActions.short(a, units), QuickActions.spoken(a, units), style, Modifier.weight(1f)) { onAction(a) }
            }
            // Keeps "More" at the end even with fewer actions.
            repeat(QuickAction.SHOWN - row.shown.size) { Spacer(Modifier.weight(1f)) }
            QuickLogButton(Icons.Rounded.MoreHoriz, "More", "More ways to log", style, Modifier.weight(1f), quiet = true, onClick = onMore)
        }
    }
}

/** A round button with its word under it; the whole column is the touch target (at least 48 dp). */
@Composable
private fun QuickLogButton(icon: ImageVector, word: String, spoken: String, style: TextStyle?, modifier: Modifier = Modifier, quiet: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(spoken) { onClick(); true }
            }
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (quiet) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
        }
        if (style != null) {
            Spacer(Modifier.height(6.dp))
            Text(word, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
        }
    }
}

/**
 * What + Log opens: every way to log, for the features that are on, grouped by feature
 * ([TodayLayout.logGroups]), and the way to choose what's in Quick log.
 */
@Composable
fun LogSheetContent(groups: List<Pair<Module?, List<QuickAction>>>, units: Units, onAction: (QuickAction) -> Unit, onArrange: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        Text("Log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        groups.forEach { (module, actions) ->
            Spacer(Modifier.height(16.dp))
            Text(
                QuickActions.group(module), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { a -> QuickChip(QuickActions.icon(a), QuickActions.label(a, units), { onAction(a) }) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onArrange, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose what's in Quick log")
        }
    }
}
