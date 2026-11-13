package com.health.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.health.api.Module
import com.health.api.Score
import com.health.api.TodaySection
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.CardAction
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Ring
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.theme.AppTheme

/*
 * Today's own parts: the scores strip (or its one quiet line), Up next, Also today, and the one
 * headline style every feature card uses ([Figure]).
 */

/**
 * A card's headline when it's words ("Feeling good", "Breakfast, lunch logged"), and at most one
 * line under it: the same size on every card, a step under [Figure]'s numbers. Read out as one.
 */
@Composable
fun Headline(text: String, supporting: String? = null, supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = supportingColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * A card's one headline number, and at most one line under it: [value] ("5,120", "7h 12m") in
 * the same size on every card, [unit] after it in the quiet colour ("of 8,000 steps"),
 * [supporting] beneath. Read out as one.
 */
@Composable
fun Figure(
    value: String,
    unit: String? = null,
    supporting: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val unitStyle = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = color)) { append(value) }
                if (unit != null) {
                    append(" ")
                    withStyle(SpanStyle(color = quiet, fontSize = unitStyle.fontSize, fontWeight = unitStyle.fontWeight, fontFamily = unitStyle.fontFamily)) { append(unit) }
                }
            },
            style = MaterialTheme.typography.titleLarge,
        )
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = supportingColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---- Scores ----

/**
 * Readiness, sleep and activity as three small rings in one slim card (a tap shows what made
 * each); with none, one quiet line instead, with the way to Devices ([onDevices], none while
 * viewing a share).
 */
@Composable
fun ScoresStrip(readiness: Score?, sleep: Score?, activity: Score?, isToday: Boolean, readOnly: Boolean, onScore: (String, Score) -> Unit, onDevices: () -> Unit) {
    val scores = listOf("Readiness" to readiness, "Sleep" to sleep, "Activity" to activity)
    if (scores.all { it.second == null }) {
        NoScoresLine(isToday, readOnly, onDevices)
        return
    }
    AppCard(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            scores.forEach { (name, s) -> MiniScore(name, s) { s?.let { onScore(name, it) } } }
        }
    }
}

@Composable
private fun MiniScore(name: String, score: Score?, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = score != null, role = Role.Button, onClickLabel = "See what made it", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name ${score?.score ?: "not measured"}${score?.label?.let { ", $it" } ?: ""}" }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ring(score?.let { it.score / 100f }, size = 52.dp, stroke = 5.dp) {
            Text(score?.score?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** No scores: one quiet line, not a card. */
@Composable
private fun NoScoresLine(isToday: Boolean, readOnly: Boolean, onDevices: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Watch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                readOnly -> if (isToday) "No scores yet today" else "No scores for this day"
                isToday -> "Scores appear after a night with your ring or watch"
                else -> "No scores for this day"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (!readOnly) {
            TextButton(onClick = onDevices, contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
                Text("Devices")
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
    }
}

// ---- Up next ----

/** What Up next's rows can do; each row offers one ([TodayPlan.Item]), a dose two (Take and Skip). */
class UpNextActions(
    val take: (TodayPlan.Item) -> Unit = {},
    val skip: (TodayPlan.Item) -> Unit = {},
    val trip: (TodayPlan.Item) -> Unit = {},
    val open: (String) -> Unit = {},
)

/**
 * Up next: what needs doing now, most urgent first ([TodayPlan.upNext]), the first [shown] and
 * "Show N more". Nothing when there's nothing to do. Viewing a share, the rows without buttons.
 */
@Composable
fun UpNextCard(items: List<TodayPlan.Item>, readOnly: Boolean, actions: UpNextActions, shown: Int = 4) {
    if (items.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    val rows = if (all || items.size <= shown + 1) items else items.take(shown)
    InfoCard("Up next", icon = Icons.Rounded.Schedule) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Take and Skip go under the words when two buttons beside them wouldn't leave room (a small phone, large text).
            val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale
            Column {
                rows.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    UpNextRow(item, readOnly, actions, stacked)
                }
                if (items.size > rows.size || all) {
                    TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show ${items.size - rows.size} more") }
                }
            }
        }
    }
}

@Composable
private fun UpNextRow(item: TodayPlan.Item, readOnly: Boolean, actions: UpNextActions, stacked: Boolean) {
    val tone = when {
        item.late || item.kind == TodayPlan.Kind.RESTOCK -> AppTheme.status.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val buttons: @Composable () -> Unit = {
        when (item.kind) {
            TodayPlan.Kind.DOSE -> {
                SkipButton(item, actions)
                FilledTonalButton(onClick = { actions.take(item) }, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.semantics { contentDescription = "Take ${item.title}" }) { Text("Take") }
            }
            TodayPlan.Kind.BLADDER -> RowButton("Done", "Log a toilet trip") { actions.trip(item) }
            TodayPlan.Kind.APPOINTMENT -> RowButton("Open", "Open ${item.title}") { item.route?.let(actions.open) }
            TodayPlan.Kind.ROUTINE -> RowButton("Start", "Start ${item.title}") { item.route?.let(actions.open) }
            TodayPlan.Kind.RESTOCK -> RowButton("Restock", "Restock ${item.title.removeSuffix(" running low")}") { item.route?.let(actions.open) }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = if (stacked && item.kind == TodayPlan.Kind.DOSE) Alignment.Top else Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(upNextIcon(item.kind), null, tint = tone, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = if (item.late) AppTheme.status.warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!readOnly && stacked && item.kind == TodayPlan.Kind.DOSE) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Take first: it's the one most reach for.
                    FilledTonalButton(onClick = { actions.take(item) }, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.semantics { contentDescription = "Take ${item.title}" }) { Text("Take") }
                    SkipButton(item, actions)
                }
            }
        }
        if (!readOnly && !(stacked && item.kind == TodayPlan.Kind.DOSE)) {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    }
}

/** Skip, quieter than Take beside it. */
@Composable
private fun SkipButton(item: TodayPlan.Item, actions: UpNextActions) {
    TextButton(
        onClick = { actions.skip(item) },
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.semantics { contentDescription = "Skip ${item.title}" },
    ) { Text("Skip") }
}

@Composable
private fun RowButton(text: String, spoken: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.semantics { contentDescription = spoken }) { Text(text, maxLines = 1) }
}

private fun upNextIcon(kind: TodayPlan.Kind): ImageVector = when (kind) {
    TodayPlan.Kind.DOSE -> Icons.Rounded.Medication
    TodayPlan.Kind.APPOINTMENT -> Icons.Rounded.Event
    TodayPlan.Kind.BLADDER -> Icons.Rounded.Wc
    TodayPlan.Kind.ROUTINE -> Icons.Rounded.Checklist
    TodayPlan.Kind.RESTOCK -> Icons.Rounded.Inventory2
}

// ---- Also today ----

/** A feature with nothing yet today, as Also today's row: its [action] logs (none while viewing a share). */
data class AlsoRow(val section: TodaySection, val name: String, val status: String, val action: CardAction?)

/** Every feature that's on with nothing logged yet today, as one card of short rows. */
@Composable
fun AlsoTodayCard(rows: List<AlsoRow>, modules: Set<Module>, isToday: Boolean) {
    if (rows.isEmpty()) return
    val nameStyle = MaterialTheme.typography.bodyLarge
    val statusStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    InfoCard(if (isToday) "Also today" else "Also on this day", icon = Icons.AutoMirrored.Rounded.PlaylistAdd) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One way for every row: each status at the end of its line when they all fit there (beside the icon and "+"), else under each name.
            val room = maxWidth - 44.dp - (if (rows.any { it.action != null }) 48.dp else 0.dp) - 12.dp
            val inline = remember(rows, room, nameStyle, statusStyle, density) {
                rows.all { r ->
                    with(density) { (measurer.measure(r.name, nameStyle, maxLines = 1).size.width + measurer.measure(r.status, statusStyle, maxLines = 1).size.width).toDp() } <= room
                }
            }
            Column {
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(TodayIcons.of(row.section, modules), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        val name: @Composable () -> Unit = { Text(row.name, style = nameStyle) }
                        val status: @Composable () -> Unit = { Text(row.status, style = statusStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        val words = Modifier.weight(1f).padding(vertical = 6.dp).semantics(mergeDescendants = true) {}
                        if (inline) {
                            Row(words, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { name() }
                                Spacer(Modifier.width(12.dp))
                                status()
                            }
                        } else {
                            Column(words) { name(); status() }
                        }
                        row.action?.let { AddButton(it) }
                    }
                }
            }
        }
    }
}

/** A row's "+" (or its short menu, for things logged more than one way). */
@Composable
private fun AddButton(action: CardAction) {
    if (action.menu.isEmpty()) {
        IconButton(onClick = action.onClick) { Icon(action.icon, action.label, tint = MaterialTheme.colorScheme.primary) }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(action.icon, action.label, tint = MaterialTheme.colorScheme.primary) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            action.menu.forEach { (label, go) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; go() }) }
        }
    }
}

/** Each of Today's sections' icon: the feature's own where it has one. */
object TodayIcons {
    fun of(section: TodaySection, modules: Set<Module>): ImageVector = when (section) {
        TodaySection.MEDS -> ModuleInfo.icon(Module.MEDICATIONS)
        TodaySection.MIND -> ModuleInfo.icon(Module.MIND)
        TodaySection.CYCLE, TodaySection.LIFE_STAGE -> ModuleInfo.icon(Module.CYCLE)
        TodaySection.CONTINENCE -> ModuleInfo.icon(Module.CONTINENCE)
        TodaySection.SUPPLIES -> Icons.Rounded.Inventory2
        TodaySection.NEURO -> ModuleInfo.icon(if (Module.NEURO in modules) Module.NEURO else Module.MIND)
        TodaySection.TRANSITION -> ModuleInfo.icon(Module.TRANSITION)
        TodaySection.VITALS -> ModuleInfo.icon(Module.VITALS)
        TodaySection.DRINKS -> Icons.Rounded.LocalCafe
        TodaySection.SYMPTOMS -> ModuleInfo.icon(Module.SYMPTOMS)
        TodaySection.HABITS -> ModuleInfo.icon(Module.HABITS)
        TodaySection.HORMONES -> ModuleInfo.icon(Module.HORMONES)
        TodaySection.APPOINTMENTS -> Icons.Rounded.Event
        TodaySection.ACTIVITY -> Icons.AutoMirrored.Rounded.DirectionsWalk
        TodaySection.SLEEP -> Icons.Rounded.Bedtime
        TodaySection.HEART -> Icons.Rounded.Favorite
        TodaySection.STRESS -> Icons.Rounded.SelfImprovement
        TodaySection.FOOD -> Icons.Rounded.Restaurant
        TodaySection.WATER -> Icons.Rounded.WaterDrop
        TodaySection.WORKOUTS -> Icons.Rounded.FitnessCenter
        TodaySection.BODY -> Icons.Rounded.MonitorWeight
        TodaySection.SCORES -> Icons.Rounded.Watch
        TodaySection.UP_NEXT -> Icons.Rounded.Schedule
        TodaySection.INSIGHTS, TodaySection.QUICK -> Icons.Rounded.Add
        TodaySection.ALSO -> Icons.AutoMirrored.Rounded.PlaylistAdd
    }
}
