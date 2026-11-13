@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.QuickAction
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.Hint

/** How much a tap on the Water quick action adds. */
const val QUICK_WATER_ML = 250

/** Each quick action's icon, and the words on its button. */
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
}

/** One quick action's button. */
@Composable
fun QuickChip(icon: ImageVector, label: String, onClick: () -> Unit, outlined: Boolean = false) {
    val padding = PaddingValues(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
    val inside: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
    if (outlined) OutlinedButton(onClick = onClick, contentPadding = padding) { inside() }
    else FilledTonalButton(onClick = onClick, contentPadding = padding) { inside() }
}

/** Today's quick actions: those shown, wrapping onto a second line rather than scrolling, then "More". */
@Composable
fun QuickActionsRow(row: TodayLayout.QuickRow, units: Units, onAction: (QuickAction) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    if (row.shown.isEmpty() && row.more.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        row.shown.forEach { a -> QuickChip(QuickActions.icon(a), QuickActions.label(a, units), { onAction(a) }) }
        if (row.more.isNotEmpty()) QuickChip(Icons.Rounded.MoreHoriz, "More", onMore, outlined = true)
    }
}

/** What "More" opens: the rest of the quick actions, and the way to choose which show. */
@Composable
fun QuickMoreContent(more: List<QuickAction>, units: Units, onAction: (QuickAction) -> Unit, onArrange: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        Text("More quick actions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        if (more.isEmpty()) {
            Hint("They're all on Today.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                more.forEach { a -> QuickChip(QuickActions.icon(a), QuickActions.label(a, units), { onAction(a) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onArrange, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose which show on Today")
        }
    }
}
