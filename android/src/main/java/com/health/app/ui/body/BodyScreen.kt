@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Fields
import com.health.api.Goals
import com.health.api.HealthAlerts
import com.health.api.Metric
import com.health.api.Module
import com.health.api.RecentReadings
import com.health.api.Units
import com.health.api.VitalsTargets
import com.health.app.ui.Format
import com.health.app.ui.alerts.Alerts
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BigFigure
import com.health.app.ui.components.BigTitle
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hypnogram
import com.health.app.ui.components.LinkRow
import com.health.app.ui.components.Loading
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.TimeAxis
import com.health.app.ui.components.TimeAxisLabels
import com.health.app.ui.components.TimeBars
import com.health.app.ui.components.TimeChart
import com.health.app.ui.components.TimeChartStyle
import com.health.app.ui.components.TitledCard
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.opensDetails
import com.health.app.ui.components.showsField
import com.health.app.ui.devices.Compare
import com.health.app.ui.log.WeightDialog
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.recent.Recent
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.trends.TrendSections
import com.health.app.ui.trends.trendsSections
import java.time.LocalDate
import java.time.ZoneId

/*
 * Body: everything the ring, the strap and the rest measured. A device filter and a switch: 24
 * hours (the night, heart rate with the night shaded, small tiles for HRV, blood oxygen, breathing
 * and temperature, then steps; arranged under android.recent) or the week, month or year (the
 * trends' charts, arranged under android.trends); every card opens its details (BodyDetail.kt).
 * At the bottom, Health alerts and Compare devices.
 */

/** What Body can do, as callbacks (so it can be drawn with sample data). */
class BodyActions(
    /** Back, on a device's own 24 hours from Devices; none on the tab. */
    val back: (() -> Unit)? = null,
    val span: (BodySpan) -> Unit = {},
    val choose: (String?) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val open: (String) -> Unit = {},
    val logWeight: (Double) -> Unit = {},
    val deleteWeight: (Long) -> Unit = {},
)

/**
 * Body, on its tab or (with [device] and [onBack]) one device's own from Devices. [ask] is a span
 * a link asked for ("recent" opens the 24 hours, "trends" the week), done once [onAsked].
 */
@Composable
fun BodyRoute(open: (String) -> Unit, device: String? = null, onBack: (() -> Unit)? = null, ask: BodySpan? = null, onAsked: () -> Unit = {}) {
    val c = appContainer()
    val vm = viewModel(key = "body:${device.orEmpty()}") { BodyViewModel(c, device, ask) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    val unseen by c.unseenAlerts.collectAsState()
    val alerts by c.alerts.collectAsState()
    LaunchedEffect(ask) {
        if (ask != null) {
            vm.span(ask)
            onAsked()
        }
    }
    BodyScreen(
        state, settings.units, c.zone(),
        BodyActions(
            back = onBack, span = vm::span, choose = vm::choose, refresh = vm::refresh, retry = { vm.load() }, syncNow = vm::syncNow, open = open,
            logWeight = { vm.logWeight(it) }, deleteWeight = { vm.deleteWeight(it) },
        ),
        goals = profile?.goals ?: Goals(),
        modules = com.health.app.ui.care.shownModules(),
        vitalsTargets = profile?.vitalsTargets ?: VitalsTargets(),
        water = showsField(Fields.NUTRITION_WATER),
        micros = com.health.app.ui.food.showsMicros(),
        unseenAlerts = unseen,
        alerts = alerts,
    )
}

/**
 * The page. [unseenAlerts] and [alerts] are for the Health alerts row; [now] is when "Last synced"
 * counts from. While viewing a share, the 24 hours (only ever the person's own) and the rows at the
 * bottom aren't there.
 */
@Composable
fun BodyScreen(
    state: BodyState,
    units: Units,
    zone: ZoneId,
    actions: BodyActions,
    goals: Goals = Goals(),
    modules: Set<Module> = Module.DEFAULT,
    vitalsTargets: VitalsTargets = VitalsTargets(),
    water: Boolean = true,
    micros: Boolean = false,
    unseenAlerts: Int = 0,
    alerts: HealthAlerts? = null,
    now: Long = System.currentTimeMillis(),
) {
    val readOnly = LocalReadOnly.current
    var weighing by remember { mutableStateOf(false) }
    val readings = state.readings
    val day = state.span == BodySpan.DAY
    val recentSections = if (readOnly || readings == null) emptyList() else Recent.sections(readings)
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state, readings, actions, readOnly) {
                if (day) ArrangeAction(FeatureScreens.RECENT, "Body at 24 hours", recentSections)
                else ArrangeAction(FeatureScreens.TRENDS, "Body over time", trendsSections(modules, water, micros))
            }
            SpanSwitch(state.span, actions.span)
            when {
                day && readOnly -> EmptyState(Icons.Rounded.Lock, "Only for your own data", "The last 24 hours are only ever your own. Choose Week, Month or Year to see what's shared.")
                day -> Day(state, readings, recentSections, units, zone, actions, now)
                else -> {
                    val trends = state.trends
                    when {
                        state.loading && trends == null -> Loading()
                        state.error != null && trends == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                        trends != null -> {
                            state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                            TrendSections(
                                trends, state.weights, units, goals, zone, modules, vitalsTargets, water, micros, state.combined, state.combinedSleep,
                                onDetails = { id -> actions.open(BodyDetail.route(id, state.span)) },
                                onWeighIn = { weighing = true },
                                onDeleteWeight = actions.deleteWeight,
                            )
                        }
                    }
                }
            }
            if (!readOnly) Links(state, unseenAlerts, alerts, actions.open)
        }
    }
    if (weighing) WeightDialog(units, state.weights.lastOrNull()?.value, { weighing = false }) { actions.logWeight(it); weighing = false }
}

/** "Body" (or, from Devices, the way back), the device filter at 24 hours, and Arrange. */
@Composable
private fun Header(state: BodyState, readings: RecentReadings?, actions: BodyActions, readOnly: Boolean, arrange: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        actions.back?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
        BigTitle("Body", Modifier.weight(1f))
        val chips = if (state.span == BodySpan.DAY && !readOnly && readings != null) Recent.chips(readings, state.device, state.known) else emptyList()
        if (chips.isNotEmpty()) DeviceFilter(chips, state.device, actions.choose)
        arrange()
    }
}

/** "All devices ▾": a white pill that opens the list of devices. */
@Composable
private fun DeviceFilter(chips: List<Recent.Chip>, chosen: String?, onChoose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = chips.firstOrNull { it.id == chosen }?.name ?: Recent.ALL_DEVICES
    Box {
        Surface(
            onClick = { open = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Showing $name. Choose a device" },
        ) {
            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            chips.forEach { chip -> DropdownMenuItem(text = { Text(chip.name) }, onClick = { open = false; onChoose(chip.id) }) }
        }
    }
}

/** 24 hours · Week · Month · Year (or those of them in [spans]), as one rounded switch. */
@Composable
fun SpanSwitch(span: BodySpan, onSpan: (BodySpan) -> Unit, spans: List<BodySpan> = BodySpan.entries) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        spans.forEach { s ->
            val on = s == span
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSpan(s) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

// ---- 24 hours ----

@Composable
private fun Day(state: BodyState, readings: RecentReadings?, sections: List<com.health.app.ui.components.ArrangeItem>, units: Units, zone: ZoneId, actions: BodyActions, now: Long) {
    when {
        state.loading && readings == null -> Loading()
        state.gone -> ErrorCard("That device isn't on your server any more.", actionLabel = "Show all", onAction = { actions.choose(null) })
        state.error != null && readings == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
        readings != null -> {
            state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
            if (!Recent.hasData(readings)) {
                EmptyState(Icons.Rounded.History, Recent.NOTHING, Recent.lastSyncLine(Recent.lastSync(readings, state.known), now)) {
                    FilledTonalButton(onClick = actions.syncNow, enabled = !state.syncing) {
                        if (state.syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Text("Sync now")
                        }
                    }
                }
            } else {
                DayCards(readings, state, sections, units, zone) { id -> actions.open(BodyDetail.route(id, BodySpan.DAY, state.device)) }
            }
        }
    }
}

/** The 24 hours' cards in the person's order, the small tiles two to a row; each opens its details ([details], given its id). */
@Composable
private fun DayCards(readings: RecentReadings, state: BodyState, sections: List<com.health.app.ui.components.ArrangeItem>, units: Units, zone: ZoneId, details: (String) -> Unit) {
    val axis = TimeAxis.of(readings.from, readings.to, zone)
    val shade = Recent.shade(readings)
    val series = Recent.series(readings)
    val today = LocalDate.now(zone).toString()
    val usualDays = state.usual?.days.orEmpty()
    val rows = BodyText.rows(arrangedIn(FeatureScreens.RECENT, sections))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Sections(rows.map { it.joinToString("+") }) { key ->
            val ids = key.split("+")
            if (ids.size == 2 || ids[0] in BodyText.TILES) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ids.forEach { id -> Box(Modifier.weight(1f).fillMaxHeight()) { ReadingTile(id, series, usualDays, today, units, zone) { details(id) } } }
                    if (ids.size == 1) Spacer(Modifier.weight(1f))
                }
            } else when (ids[0]) {
                Recent.SLEEP -> NightCard(readings, zone) { details(Recent.SLEEP) }
                Recent.HEART -> series[Metric.HEART_RATE]?.let { hr ->
                    val resting = usualDays.lastOrNull { it.date == today }?.restingHeartRate
                    HeartCard(hr, resting, BodyText.sources(readings), axis, shade, units) { details(Recent.HEART) }
                }
                Recent.STEPS -> series[Metric.STEPS]?.let { StepsCard(it, axis, shade, units) { details(Recent.STEPS) } }
            }
        }
    }
}

/** Last night: how long, when, the stages across the night with their names, and each stage's time. */
@Composable
private fun NightCard(readings: RecentReadings, zone: ZoneId, onDetails: () -> Unit) {
    val night = BodyText.mainNight(readings)
    val card = Modifier.opensDetails("Last night", onDetails)
    if (night == null) {
        TitledCard("Last night", card) { Text(Recent.NO_SLEEP, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val totals = Recent.totals(listOf(night))
    TitledCard("Last night", card, note = "${Format.timeOfDay(night.start, zone)} – ${Format.timeOfDay(night.end, zone)}") {
        Text(Format.minutes(totals.asleep), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "Asleep ${Format.minutes(totals.asleep)}" })
        NightStages(night, zone)
        StageTiles(totals)
        // Naps, and a second night, under it.
        Recent.nights(readings.sleep).filter { it != night }.forEach { s ->
            Text(
                "Also asleep ${Recent.sessionLine(s, zone, false)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A night's stages across it, with their names on the left and the hours under it (Last night, and its details). */
@Composable
internal fun NightStages(night: com.health.api.SleepSession, zone: ZoneId, height: androidx.compose.ui.unit.Dp = 104.dp, labels: Boolean = true) {
    val totals = Recent.totals(listOf(night))
    val span = TimeAxis.of(night.start, night.end, zone, everyHours = 2)
    val gutter = if (labels) 52.dp else 0.dp
    Column {
        Row(Modifier.fillMaxWidth()) {
            if (labels) {
                Column(Modifier.width(gutter).height(height)) {
                    listOf("Awake", "REM", "Light", "Deep").forEach { name ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Hypnogram(
                Recent.stagesIn(listOf(night), night.start, night.end), night.start, night.end,
                Modifier.weight(1f).semantics { contentDescription = Recent.sleepDescription(totals) },
                height = height, ticks = span.ticks.map { it.at },
            )
        }
        if (labels) TimeAxisLabels(span, Modifier.padding(start = gutter), gutter = 0.dp)
    }
}

/** Each stage's time as a small tile: deep, REM, light and awake (or asleep and awake). */
@Composable
internal fun StageTiles(totals: Recent.SleepTotals) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText.stageTiles(totals).forEach { (name, time) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp).semantics(mergeDescendants = true) {},
            ) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

/** Heart rate: resting (or lowest), average and highest, then the 24 hours with the night shaded. */
@Composable
private fun HeartCard(series: com.health.api.RecentSeries, resting: Double?, sources: String?, axis: TimeAxis, shade: List<Pair<Long, Long>>, units: Units, onDetails: () -> Unit) {
    TitledCard("Heart rate", Modifier.opensDetails("Heart rate", onDetails)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val low = resting ?: series.min
            low?.let { BigFigure(it.toInt().toString(), caption = if (resting != null) "resting" else "lowest") }
            series.average?.let { BigFigure(Math.round(it).toString(), caption = "average") }
            series.max?.let { BigFigure(Math.round(it).toString(), caption = "highest") }
        }
        Column {
            val points = Recent.points(series, units)
            TimeChart(
                points, axis, Recent.scale(Metric.HEART_RATE, points, units), Recent.description("Heart rate", series, units),
                color = MaterialTheme.colorScheme.primary, style = if (series.points.size >= 24) TimeChartStyle.LINE else TimeChartStyle.DOTS, shaded = shade, height = 120.dp,
            )
            TimeAxisLabels(axis)
        }
        sources?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Steps an hour at a time, with the total. */
@Composable
private fun StepsCard(series: com.health.api.RecentSeries, axis: TimeAxis, shade: List<Pair<Long, Long>>, units: Units, onDetails: () -> Unit) {
    val total = series.total ?: series.points.sumOf { it.value }
    TitledCard("Steps", Modifier.opensDetails("Steps", onDetails), trailing = { Text(Format.number(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }) {
        Column {
            val points = Recent.points(series, units)
            TimeBars(
                points, (series.bucketMinutes ?: 60) * 60_000L, axis, Recent.scale(Metric.STEPS, points),
                Recent.description("Steps", series, units), color = MaterialTheme.colorScheme.tertiary, shaded = shade, height = 72.dp,
            )
            TimeAxisLabels(axis)
        }
    }
}

/** A small tile: a reading's name, its figure, and a line against the person's usual (or its range). */
@Composable
private fun ReadingTile(id: String, series: Map<Metric, com.health.api.RecentSeries>, usualDays: List<com.health.api.TrendDay>, today: String, units: Units, zone: ZoneId, onDetails: () -> Unit) {
    val warn = AppTheme.status.warn
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    var label = ""
    var value: String? = null
    var line: String? = null
    var lineColor = quiet
    when (id) {
        Recent.HRV -> series[Metric.HRV]?.let { s ->
            label = "HRV"
            value = BodyText.average(s, units)
            val avg = s.average ?: s.points.map { it.value }.average()
            val usual = BodyText.usual(usualDays, today) { it.hrv }
            line = usual?.let { BodyText.againstUsual(avg, it, " ms") } ?: BodyText.range(s, units)
            if (usual != null && BodyText.wellBelow(avg, usual)) lineColor = warn
        }
        Recent.SPO2 -> series[Metric.SPO2]?.let { s ->
            label = "Blood oxygen"
            value = BodyText.average(s, units)
            line = BodyText.lowestAt(s, units, zone)
        }
        Recent.BREATHING -> series[Metric.RESPIRATORY_RATE]?.let { s ->
            label = "Breathing"
            value = BodyText.breaths(s)
            line = BodyText.range(s, units)
        }
        Recent.STRESS -> series[Metric.STRESS]?.let { s ->
            label = "Stress"
            value = BodyText.average(s, units)
            line = s.max?.let { "highest ${it.toInt()}" }
        }
        Recent.TEMPERATURE -> {
            val change = series[Metric.TEMPERATURE_DEVIATION]
            val skin = series[Metric.SKIN_TEMPERATURE]
            label = "Temperature"
            if (change != null) {
                val v = change.latest?.value ?: change.average ?: change.points.last().value
                value = Recent.value(Metric.TEMPERATURE_DEVIATION, v, units)
                line = BodyText.warmth(v)
                if (v >= 0.3) lineColor = warn
            } else if (skin != null) {
                value = BodyText.average(skin, units)
                line = "skin, on average"
            }
        }
    }
    val shown = value ?: return
    AppCard(Modifier.fillMaxHeight().opensDetails(label, onDetails), padding = 16.dp) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = quiet)
            Text(shown, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = lineColor) }
        }
    }
}

// ---- The rows at the bottom ----

/** Health alerts (how many are new, and what it's watching) and, with two devices or more, Compare devices. */
@Composable
private fun Links(state: BodyState, unseen: Int, alerts: HealthAlerts?, open: (String) -> Unit) {
    AppCard(padding = 0.dp) {
        LinkRow("Health alerts", Alerts.bodyLine(unseen, alerts), onClick = { open("alerts") })
        if (Compare.offered(state.known)) {
            RowDivider()
            LinkRow("Compare devices", state.compare ?: "How two of them read on the same nights", onClick = { open("devices/compare") })
        }
    }
}
