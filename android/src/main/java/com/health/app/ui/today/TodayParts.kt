package com.health.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.FoodSuggestion
import com.health.api.HealthAlert
import com.health.api.Insight
import com.health.api.Module
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.Score
import com.health.api.StageSpan
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.app.ui.Format
import com.health.app.ui.alerts.Alerts
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.CARD_CORNER
import com.health.app.ui.components.PillButton
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Ring
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.Tile
import com.health.app.ui.components.stageColor
import com.health.app.ui.components.titleStyle
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId

/*
 * Today's parts, as the redesign draws them: the header (the day, a greeting and the bell), the
 * health alert's card, the scores, Right now, Your day and For you.
 */

// ---- The header ----

/**
 * The day as a button (another day), the greeting (or, on a day gone by, which day it is and the
 * way back to today), and the bell with the unseen health alerts ([alerts]; none while viewing a share).
 */
@Composable
fun TodayHeader(date: LocalDate, today: LocalDate, hour: Int, alerts: Int, bell: Boolean, onPickDay: () -> Unit, onToday: () -> Unit, onBell: () -> Unit) {
    val isToday = date == today
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Choose another day", role = Role.Button, onClick = onPickDay)
                    .heightIn(min = 48.dp)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Format.longDay(date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (isToday) TodayRows.greeting(hour) else Format.day(date, today),
                style = titleStyle, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (!isToday) {
                TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text("Back to today") }
            }
        }
        if (bell) Bell(alerts, onBell)
    }
}

/** The bell: a round white button, with how many health alerts are new. */
@Composable
private fun Bell(count: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.padding(top = 4.dp).size(48.dp),
    ) {
        if (count > 0) {
            BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text(if (count > 9) "9+" else "$count") } }) {
                Icon(Icons.Rounded.Notifications, Alerts.countText(count))
            }
        } else {
            Icon(Icons.Rounded.Notifications, "Health alerts")
        }
    }
}

// ---- The health alert ----

/**
 * The most serious active health alert, always first. One that could need care now is in the
 * danger colours and says when to call emergency services (its advice's first sentence); the
 * rest a gentle tint. Opens Health alerts.
 */
@Composable
fun AlertCard(alert: HealthAlert, onOpen: () -> Unit) {
    val urgent = alert.level == AlertLevel.URGENT
    val scheme = MaterialTheme.colorScheme
    val warn = AppTheme.status.warn
    val background = if (urgent) scheme.errorContainer else warn.copy(alpha = 0.10f).compositeOver(scheme.surfaceContainer)
    val strong = if (urgent) scheme.onErrorContainer else scheme.onSurface
    val quiet = if (urgent) scheme.onErrorContainer else scheme.onSurfaceVariant
    val level = Alerts.levelText(alert.level)
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(CARD_CORNER), color = background, contentColor = strong,
        modifier = Modifier.fillMaxWidth().semantics { onClick(label = "Open Health alerts") { onOpen(); true } },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                alertIcon(alert.kind),
                container = if (urgent) scheme.error else warn.copy(alpha = 0.20f).compositeOver(background),
                content = if (urgent) scheme.onError else warn,
                round = true,
            )
            Column(Modifier.weight(1f)) {
                Text(level, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = quiet)
                Text(alert.title, style = MaterialTheme.typography.titleSmall, color = strong)
                Text(Alerts.firstSentence(alert.body), style = MaterialTheme.typography.bodyMedium, color = quiet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (urgent && alert.advice.isNotBlank()) {
                    Text(Alerts.firstSentence(alert.advice), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = strong, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = quiet)
        }
    }
}

private fun alertIcon(kind: AlertKind): ImageVector = when (kind) {
    AlertKind.LOW_OXYGEN, AlertKind.OXYGEN_DIPS -> Icons.Rounded.Air
    AlertKind.GETTING_SICK -> Icons.Rounded.Thermostat
    else -> Icons.Rounded.MonitorHeart
}

// ---- The scores ----

/**
 * Readiness, sleep and activity as three rings in one card, with one plain sentence under them
 * ([line]); the whole card opens Body. With no scores, one quiet line and the way to Devices.
 */
@Composable
fun ScoresCard(readiness: Score?, sleep: Score?, activity: Score?, line: String?, isToday: Boolean, readOnly: Boolean, onOpen: () -> Unit, onDevices: () -> Unit) {
    val scores = listOf("Readiness" to readiness, "Sleep" to sleep, "Activity" to activity)
    if (scores.all { it.second == null }) {
        NoScoresLine(isToday, readOnly, onDevices)
        return
    }
    val spoken = scores.joinToString(", ") { (name, s) -> "$name ${s?.score ?: "not measured"}" } + (line?.let { ". $it" } ?: "")
    AppCard(
        padding = 0.dp, onClick = onOpen,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
            onClick("Open Body") { onOpen(); true }
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                scores.forEachIndexed { i, (name, s) ->
                    val color = if (i == 2) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Ring(s?.let { it.score / 100f }, size = 64.dp, stroke = 6.dp, colors = listOf(color)) {
                            Text(s?.score?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
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

// ---- Right now ----

/**
 * Right now: each live thing from the feed with its one button (none while viewing a share), the
 * server's words as they come. One that could need care now keeps the danger colours.
 */
@Composable
fun RightNowCard(items: List<NowItem>, readOnly: Boolean, onAction: (NowItem) -> Unit) {
    AppCard(padding = 0.dp) {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            NowRowView(item, readOnly, onAction)
        }
    }
}

@Composable
private fun NowRowView(item: NowItem, readOnly: Boolean, onAction: (NowItem) -> Unit) {
    val urgent = TodayRows.urgent(item)
    val (container, content) = nowColors(item)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (urgent) Modifier.background(MaterialTheme.colorScheme.errorContainer) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tile(nowIcon(item.kind), container, content, round = item.kind == NowKind.HEALTH_ALERT)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            item.progress?.let { ProgressBar(it.toFloat(), color = if (item.kind == NowKind.FOOD_TODAY) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, height = 6.dp) }
            item.detail?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = when {
                        urgent -> MaterialTheme.colorScheme.onErrorContainer
                        item.kind == NowKind.DOSE_OVERDUE -> AppTheme.status.warn
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        val action = item.action
        if (!readOnly && action != null) PillButton(action.label, { onAction(item) }, container, content, spoken = TodayRows.spoken(item))
    }
}

/**
 * A Right now row's tile and button colours: food in the theme's second colour, a health alert
 * in the danger colours when it could need care now (else a gentle warm tint), the rest in the first.
 */
@Composable
fun nowColors(item: NowItem): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when {
        TodayRows.urgent(item) -> scheme.error to scheme.onError
        item.kind == NowKind.HEALTH_ALERT -> AppTheme.status.warn.copy(alpha = 0.18f).compositeOver(scheme.surfaceContainer) to scheme.onSurface
        item.kind == NowKind.FOOD_TODAY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
}

/** Each kind's icon. */
fun nowIcon(kind: NowKind): ImageVector = when (kind) {
    NowKind.WEARING -> ModuleInfo.icon(Module.CONTINENCE)
    NowKind.DOSE_DUE, NowKind.DOSE_OVERDUE -> Icons.Rounded.Medication
    NowKind.FOOD_TODAY -> Icons.Rounded.Restaurant
    NowKind.APPOINTMENT_SOON -> Icons.Rounded.Event
    NowKind.ENERGY_TODAY -> Icons.Rounded.Bolt
    NowKind.VOICE_PRACTICE_DUE -> Icons.Rounded.Mic
    NowKind.HEALTH_ALERT -> Icons.Rounded.MonitorHeart
    NowKind.WIND_DOWN -> Icons.Rounded.Bedtime
    NowKind.OTHER -> Icons.Rounded.Schedule
}

// ---- Your day ----

/**
 * Your day as one timeline card: a "Now" line on top for today ([nowLine]), then each of the
 * feed's items with its time, a dot in its kind's colour, its title and one line; a tap opens it
 * ([routeOf], [onOpen]). A night carries its stages ([stagesOf]). Without the feed ([error]), a
 * quiet line and a way to try again.
 */
@Composable
fun DayCard(
    items: List<TimelineItem>,
    nowLine: String?,
    isToday: Boolean,
    zone: ZoneId,
    routeOf: (TimelineItem) -> String?,
    onOpen: (String) -> Unit,
    stagesOf: (TimelineItem) -> List<StageSpan> = { emptyList() },
    error: String? = null,
    onRetry: () -> Unit = {},
) {
    AppCard(padding = 0.dp) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (error != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                return@Column
            }
            if (items.isEmpty() && (nowLine == null || !isToday)) {
                Text(
                    if (isToday) "Nothing yet today. What you log, and what your devices record, shows up here." else "Nothing was logged or recorded on this day.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                return@Column
            }
            var first = true
            if (isToday && nowLine != null) {
                TimelineRow("Now", MaterialTheme.colorScheme.primary, null, nowLine, null, timeColor = MaterialTheme.colorScheme.primary)
                first = false
            }
            items.forEach { item ->
                if (!first) RowDivider()
                first = false
                TimelineRow(
                    if (item.allDay) "All day" else Format.timeOfDay(item.at, zone),
                    dayColor(item.kind), item.title, item.detail, routeOf(item)?.let { r -> { onOpen(r) } },
                    stages = stagesOf(item),
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    time: String,
    dot: Color,
    title: String?,
    detail: String?,
    onClick: (() -> Unit)?,
    timeColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    stages: List<StageSpan> = emptyList(),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).heightIn(min = 48.dp) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(time, style = MaterialTheme.typography.labelMedium, fontWeight = if (title == null) FontWeight.SemiBold else null, color = timeColor, modifier = Modifier.width(72.dp).padding(top = 2.dp))
        Box(Modifier.width(20.dp).padding(top = 5.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stages.isNotEmpty()) StageStrip(stages, Modifier.padding(top = 6.dp))
        }
    }
}

/** A night's stages in a thin strip, in their colours, start to end. */
@Composable
fun StageStrip(stages: List<StageSpan>, modifier: Modifier = Modifier) {
    val start = stages.minOf { it.start }
    val end = stages.maxOf { it.end }
    val colors = com.health.api.SleepStage.entries.associateWith { stageColor(it) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        val total = (end - start).coerceAtLeast(1).toFloat()
        stages.forEach { s ->
            val x0 = (s.start - start) / total * size.width
            val x1 = (s.end - start) / total * size.width
            drawRoundRect(colors.getValue(s.stage), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height), CornerRadius(0f, 0f))
        }
    }
}

/**
 * A timeline item's dot: sleep quiet, food and drinks in the second colour, movement warm, blood
 * pressure and glucose the heart's colour, symptoms and the body's numbers the third, a health
 * alert the danger colour, the rest the first.
 */
@Composable
fun dayColor(kind: TimelineKind): Color = when (kind) {
    TimelineKind.SLEEP, TimelineKind.NAP, TimelineKind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    TimelineKind.MEAL, TimelineKind.DRINK -> MaterialTheme.colorScheme.tertiary
    TimelineKind.WORKOUT -> AppTheme.status.warn
    TimelineKind.BLOOD_PRESSURE, TimelineKind.GLUCOSE -> AppTheme.data.heart
    TimelineKind.SYMPTOM, TimelineKind.WEIGHT, TimelineKind.MEASUREMENT -> MaterialTheme.colorScheme.secondary
    TimelineKind.HEALTH_ALERT -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

// ---- For you ----

/** An insight as a card of its own (the features' pages use these; Today has For you). */
@Composable
fun InsightCard(insight: Insight) {
    val tone = when (insight.tone) {
        "good" -> AppTheme.status.good
        "warn" -> AppTheme.status.warn
        else -> MaterialTheme.colorScheme.primary
    }
    AppCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(com.health.app.ui.AppIcons.insight(insight.icon), tone.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surfaceContainer), tone, size = 36.dp, round = true)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(insight.title, style = MaterialTheme.typography.titleSmall)
                Text(insight.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** For you: at most two insights, each a row that opens where it's about. */
@Composable
fun ForYouCard(
    insights: List<Insight>,
    targetOf: (Insight) -> InsightLinks.Target?,
    onOpen: (InsightLinks.Target) -> Unit,
    readOnly: Boolean = false,
    onAdd: ((FoodSuggestion) -> Unit)? = null,
    showNumbers: Boolean = true,
) {
    if (insights.isEmpty()) return
    AppCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            insights.forEachIndexed { i, insight ->
                if (i > 0) RowDivider(inset = 4.dp)
                InsightRow(insight, targetOf(insight), onOpen, readOnly = readOnly, onAdd = onAdd, showNumbers = showNumbers)
            }
        }
    }
}
