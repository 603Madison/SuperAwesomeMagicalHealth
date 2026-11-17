package com.health.app.ui.recent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Device
import com.health.api.Metric
import com.health.api.RecentReadings
import com.health.api.RecentSeries
import com.health.api.SleepStage
import com.health.api.Units
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.ServerException
import com.health.app.net.recent
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CHART_GUTTER
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hypnogram
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Loading
import com.health.app.ui.components.TimeAxis
import com.health.app.ui.components.TimeAxisLabels
import com.health.app.ui.components.TimeBars
import com.health.app.ui.components.TimeChart
import com.health.app.ui.components.TimeChartStyle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.stageColor
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * The last 24 hours as the person's devices recorded them: the night's stages, then heart rate,
 * blood oxygen, HRV, stress, breathing, skin temperature and steps, every chart on one time axis
 * with the night shaded behind, from all devices or one. Arranged under android.recent. Only ever
 * the person's own (GET /api/recent never answers a share): it isn't offered while viewing one.
 */

data class RecentState(
    /** The device chosen, or null for all of them. */
    val device: String? = null,
    val readings: RecentReadings? = null,
    /** Every device on the server, for when the chosen one last synced (loaded when nothing was recorded). */
    val known: List<Device> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The empty page's Sync now is under way. */
    val syncing: Boolean = false,
    val error: String? = null,
    /** The chosen device isn't on the server any more. */
    val gone: Boolean = false,
)

class RecentViewModel(private val c: Container, device: String?) : ViewModel() {
    private val _state = MutableStateFlow(RecentState(device = device?.takeIf { it.isNotBlank() }))
    val state: StateFlow<RecentState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        // After a sync (here, from Devices or in the background) brings something in.
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        // Never asked for someone else's: the server refuses it through a share anyway.
        if (c.viewing.value != null) return _state.update { it.copy(loading = false, refreshing = false) }
        val device = _state.value.device
        job?.cancel()
        _state.update { it.copy(loading = !quiet || it.readings == null, error = null) }
        job = viewModelScope.launch {
            try {
                val readings = c.api.recent(Recent.HOURS, device)
                // With nothing recorded, the page says when the devices last synced.
                val known = if (Recent.hasData(readings)) _state.value.known else attempt { c.api.devices() }.getOrDefault(_state.value.known)
                _state.update { if (it.device == device) it.copy(readings = readings, known = known, loading = false, refreshing = false, gone = false) else it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val gone = device != null && e is ServerException && e.code == 404 && e.fromServer
                _state.update {
                    if (it.device != device) it
                    else it.copy(loading = false, refreshing = false, gone = gone, error = if (gone) null else e.message ?: "Couldn't load the last 24 hours")
                }
            }
        }
    }

    /** Shows [device] alone, or (null) all of them. */
    fun choose(device: String?) {
        if (device == _state.value.device) return
        _state.update { it.copy(device = device, gone = false) }
        load(quiet = true)
    }

    /** Pull to refresh: brings in the devices' latest, then reloads. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            attempt { c.sync.sync() }
            load(quiet = true)
        }
    }

    /**
     * The empty page's Sync now, as Devices' (it says how it went, and carries on if the page
     * closes); the page reloads when it's done, whether or not it brought anything new.
     */
    fun syncNow() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        val sync = c.syncNow()
        viewModelScope.launch {
            sync.join()
            _state.update { it.copy(syncing = false) }
            load(quiet = true)
        }
    }
}

/** What the page can do, as callbacks (so it can be drawn with sample data). */
class RecentActions(
    val back: () -> Unit = {},
    val choose: (String?) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val syncNow: () -> Unit = {},
)

@Composable
fun RecentRoute(device: String?, onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel(key = "recent:${device.orEmpty()}") { RecentViewModel(c, device) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    RecentScreen(
        state, settings.units, c.zone(),
        RecentActions(back = onBack, choose = vm::choose, refresh = vm::refresh, retry = { vm.load() }, syncNow = vm::syncNow),
    )
}

/**
 * The page: device chips when there's more than one, then the sections in the person's order
 * ([FeatureScreens.RECENT]), each only with something in it. [now] is when "Last synced" counts from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(state: RecentState, units: Units, zone: ZoneId, actions: RecentActions, now: Long = System.currentTimeMillis()) {
    val readOnly = LocalReadOnly.current
    val readings = state.readings
    val sections = if (readOnly || readings == null) emptyList() else Recent.sections(readings)
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { if (!readOnly) actions.refresh() }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            BackBar(actions.back, "Last 24 hours", subtitle = "As your devices recorded it") {
                ArrangeAction(FeatureScreens.RECENT, "Last 24 hours", sections)
            }
            when {
                readOnly -> EmptyState(Icons.Rounded.Lock, "Only for your own data", "Go back to your own data to see the last 24 hours.")
                state.loading && readings == null -> Loading()
                state.gone -> ErrorCard(
                    "That device isn't on your server any more.", Modifier.padding(horizontal = 16.dp),
                    actionLabel = "Show all", onAction = { actions.choose(null) },
                )
                state.error != null && readings == null -> ErrorCard(state.error, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry)
                readings != null -> {
                    state.error?.let { ErrorCard(it, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry) }
                    DeviceChips(Recent.chips(readings, state.device, state.known), state.device, actions.choose)
                    if (!Recent.hasData(readings)) {
                        Nothing(Recent.lastSyncLine(Recent.lastSync(readings, state.known), now), state.syncing, actions.syncNow)
                    } else {
                        Readings(readings, sections, units, zone)
                    }
                }
            }
        }
    }
}

/** "All devices" and each device, wrapping onto a second line rather than off the edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(chips: List<Recent.Chip>, chosen: String?, onChoose: (String?) -> Unit) {
    if (chips.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            FilterChip(
                selected = chosen == chip.id,
                onClick = { onChoose(chip.id) },
                label = { Text(chip.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Nothing in the window: when the devices last synced, and a way to sync now. */
@Composable
private fun Nothing(lastSync: String, syncing: Boolean, onSync: () -> Unit) {
    EmptyState(Icons.Rounded.History, Recent.NOTHING, lastSync) {
        FilledTonalButton(onClick = onSync, enabled = !syncing) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Syncing…")
            } else {
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun Readings(readings: RecentReadings, sections: List<com.health.app.ui.components.ArrangeItem>, units: Units, zone: ZoneId) {
    val axis = TimeAxis.of(readings.from, readings.to, zone)
    val shade = Recent.shade(readings)
    val series = Recent.series(readings)
    val chart = ChartContext(axis, shade, units, zone)
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Sections(arrangedIn(FeatureScreens.RECENT, sections)) { id ->
            when (id) {
                Recent.SLEEP -> SleepCard(readings, axis, zone)
                Recent.HEART -> series[Metric.HEART_RATE]?.let { ReadingCard("Heart rate", Icons.Rounded.Favorite, AppTheme.data.heart, it, chart) }
                Recent.SPO2 -> series[Metric.SPO2]?.let { ReadingCard("Blood oxygen", Icons.Rounded.Bloodtype, AppTheme.data.water, it, chart, TimeChartStyle.DOTS) }
                Recent.HRV -> series[Metric.HRV]?.let { ReadingCard("Heart rate variability", Icons.Rounded.MonitorHeart, MaterialTheme.colorScheme.primary, it, chart, TimeChartStyle.DOTS) }
                Recent.STRESS -> series[Metric.STRESS]?.let { ReadingCard("Stress", Icons.Rounded.SelfImprovement, AppTheme.status.warn, it, chart, style(it)) }
                Recent.BREATHING -> series[Metric.RESPIRATORY_RATE]?.let { ReadingCard("Breathing rate", Icons.Rounded.Air, MaterialTheme.colorScheme.tertiary, it, chart, style(it)) }
                Recent.TEMPERATURE -> TemperatureCard(series[Metric.SKIN_TEMPERATURE], series[Metric.TEMPERATURE_DEVIATION], chart)
                Recent.STEPS -> series[Metric.STEPS]?.let { StepsCard(it, chart) }
            }
        }
    }
}

/** What every chart on the page shares. */
private class ChartContext(val axis: TimeAxis, val shade: List<Pair<Long, Long>>, val units: Units, val zone: ZoneId)

/** A line when the readings come every few minutes, dots when they come now and then. */
private fun style(series: RecentSeries): TimeChartStyle = if (series.points.size >= 24) TimeChartStyle.LINE else TimeChartStyle.DOTS

/** A card's main figure: "64 bpm  latest at 2:05 PM". */
@Composable
private fun Headline(value: String, caption: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** The figures under a chart, in three even columns so they line up from card to card. */
@Composable
private fun Stats(stats: List<Recent.Stat>) {
    if (stats.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        stats.forEach { s ->
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(s.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(s.value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        repeat(3 - stats.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
    }
}

/** The night (or nights and naps): how long asleep, when, each night's stages across its own hours, and each stage's time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepCard(readings: RecentReadings, axis: TimeAxis, zone: ZoneId) {
    InfoCard("Sleep", icon = Icons.Rounded.Bedtime, iconTint = AppTheme.data.rem) {
        if (readings.sleep.isEmpty()) {
            Text(Recent.NO_SLEEP, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@InfoCard
        }
        val nights = Recent.nights(readings.sleep)
        val totals = Recent.totals(nights)
        Headline(Format.minutes(totals.asleep), "asleep")
        val named = Recent.namesDevices(readings)
        readings.sleep.sortedBy { it.start }.forEach { s ->
            Text(Recent.sessionLine(s, zone, named), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        // Each night on its own time span, so its stages are wide enough to read (the charts below
        // share the day's axis, with the night shaded).
        nights.forEach { night ->
            val span = TimeAxis.of(night.start, night.end, zone, everyHours = 2)
            Spacer(Modifier.height(12.dp))
            Hypnogram(
                Recent.stagesIn(listOf(night), night.start, night.end), night.start, night.end,
                Modifier.padding(end = CHART_GUTTER).semantics { contentDescription = Recent.sleepDescription(Recent.totals(listOf(night))) },
                height = 96.dp,
                ticks = span.ticks.map { it.at },
            )
            TimeAxisLabels(span)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (totals.staged) {
                LegendItem(stageColor(SleepStage.DEEP), "Deep", value = Format.minutes(totals.deep))
                LegendItem(stageColor(SleepStage.REM), "REM", value = Format.minutes(totals.rem))
                LegendItem(stageColor(SleepStage.LIGHT), "Light", value = Format.minutes(totals.light))
                LegendItem(stageColor(SleepStage.AWAKE), "Awake", value = Format.minutes(totals.awake))
            } else {
                LegendItem(stageColor(SleepStage.ASLEEP), "Asleep", value = Format.minutes(totals.asleep))
                if (totals.awake > 0) LegendItem(stageColor(SleepStage.AWAKE), "Awake", value = Format.minutes(totals.awake))
            }
        }
    }
}

/** One kind of reading: its latest, its chart on the page's axis, and its figures. */
@Composable
private fun ReadingCard(title: String, icon: ImageVector, color: Color, series: RecentSeries, chart: ChartContext, style: TimeChartStyle = TimeChartStyle.LINE) {
    InfoCard(title, icon = icon, iconTint = color) {
        ReadingChart(title, color, series, chart, style)
    }
}

@Composable
private fun ReadingChart(title: String, color: Color, series: RecentSeries, chart: ChartContext, style: TimeChartStyle, reference: Double? = null) {
    val metric = series.metric ?: return
    Recent.latest(series, chart.units, chart.zone)?.let { (value, caption) -> Headline(value, caption) }
    Spacer(Modifier.height(10.dp))
    val points = Recent.points(series, chart.units)
    TimeChart(
        points, chart.axis, Recent.scale(metric, points, chart.units), Recent.description(title, series, chart.units),
        color = color, style = style, shaded = chart.shade, reference = reference,
    )
    TimeAxisLabels(chart.axis)
    Stats(Recent.stats(series, chart.units))
}

/** Skin temperature as measured, and how far it is from the person's usual, where the device says. */
@Composable
private fun TemperatureCard(skin: RecentSeries?, change: RecentSeries?, chart: ChartContext) {
    if (skin == null && change == null) return
    val color = MaterialTheme.colorScheme.secondary
    InfoCard("Skin temperature", icon = Icons.Rounded.Thermostat, iconTint = color) {
        skin?.let { ReadingChart("Skin temperature", color, it, chart, TimeChartStyle.LINE) }
        change?.let {
            if (skin != null) {
                Spacer(Modifier.height(16.dp))
                Text("Compared with your usual", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            ReadingChart("Temperature compared with your usual", color, it, chart, style(it), reference = 0.0)
        }
    }
}

/** Steps an hour at a time, with the total. */
@Composable
private fun StepsCard(series: RecentSeries, chart: ChartContext) {
    val color = MaterialTheme.colorScheme.tertiary
    InfoCard("Steps", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, iconTint = color) {
        Headline(Format.number(series.total ?: series.points.sumOf { it.value }), "steps in all")
        Spacer(Modifier.height(10.dp))
        val points = Recent.points(series, chart.units)
        TimeBars(
            points, (series.bucketMinutes ?: 60) * 60_000L, chart.axis, Recent.scale(Metric.STEPS, points),
            Recent.description("Steps", series, chart.units), color = color, shaded = chart.shade,
        )
        TimeAxisLabels(chart.axis)
    }
}
