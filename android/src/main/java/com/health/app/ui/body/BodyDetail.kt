@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Goals
import com.health.api.Metric
import com.health.api.VitalsTargets
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.recent
import com.health.app.net.series
import com.health.app.net.trends
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Loading
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.TimeAxisLabels
import com.health.app.ui.components.TimeBars
import com.health.app.ui.components.TimeChartStyle
import com.health.app.ui.components.TimeLines
import com.health.app.ui.components.TitledCard
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.recent.Recent
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * A card on Body, opened (body/detail/{id}): its name, a switch for 24 hours, the week, month or
 * year (starting where Body was; 24 hours only for what the devices recorded, and never while
 * viewing a share), then the latest, a large chart, the average, lowest and highest, and each
 * reading or day, arranged under android.bodyDetail. BodyDetailLogic.kt works out what it says.
 */

/** Loads what a detail page shows over its span: [device] is the one Body showed at 24 hours. */
class BodyDetailViewModel(private val c: Container, private val id: String, span: BodySpan, private val device: String? = null) : ViewModel() {
    private val _state = MutableStateFlow(BodyDetailState(span = span))
    val state: StateFlow<BodyDetailState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        // After a sync or a change (a weigh-in deleted) brings something new.
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val span = _state.value.span
        job?.cancel()
        // The 24 hours are only ever the person's own.
        if (span == BodySpan.DAY && c.viewing.value != null) return _state.update { it.copy(loading = false, refreshing = false) }
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                when {
                    span == BodySpan.DAY -> {
                        val readings = c.api.recent(Recent.HOURS, device)
                        _state.update { if (it.span == span) it.copy(readings = readings, loading = false, refreshing = false, error = null) else it }
                    }
                    BodyDetail.extra(id) == BodyDetail.Extra.WEIGHTS -> {
                        val weights = c.ui.measurements(Metric.WEIGHT, span.days)
                        _state.update { if (it.span == span) it.copy(weights = weights, loading = false, refreshing = false, error = null) else it }
                    }
                    else -> {
                        val trends = c.ui.trends(span.days)
                        val more = extras(span, trends.from)
                        _state.update { if (it.span == span) more(it.copy(trends = trends, loading = false, refreshing = false, error = null)) else it }
                    }
                }
                // The person's usual: the 30 days before the span (none for one device alone).
                if (BodyDetail.usesUsual(id) && _state.value.usual == null && !(span == BodySpan.DAY && device != null)) {
                    val (first, last) = BodyDetail.usualWindow(span, LocalDate.now(c.zone()))
                    attempt { c.ui.trends(first, last) }.getOrNull()?.let { usual -> _state.update { if (it.span == span) it.copy(usual = usual) else it } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.span != span) it else it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load these readings") }
            }
        }
    }

    /** The workouts, nights or breathing a detail lists beside the days (each never holding the page up). */
    private suspend fun extras(span: BodySpan, from: String): (BodyDetailState) -> BodyDetailState = when (BodyDetail.extra(id)) {
        BodyDetail.Extra.WORKOUTS -> attempt { c.ui.workouts(span.days) }.getOrDefault(emptyList()).let { w -> { s: BodyDetailState -> s.copy(workouts = w) } }
        BodyDetail.Extra.NIGHTS -> attempt { c.ui.sleep(span.days) }.getOrDefault(emptyList()).let { n -> { s: BodyDetailState -> s.copy(nights = n) } }
        BodyDetail.Extra.BREATHING -> {
            val zone = c.zone()
            val start = attempt { LocalDate.parse(from) }.getOrElse { LocalDate.now(zone).minusDays(span.days - 1L) }.atStartOfDay(zone).toInstant().toEpochMilli()
            attempt { c.ui.series(Metric.RESPIRATORY_RATE, start, System.currentTimeMillis()) }.getOrDefault(emptyList()).let { b -> { s: BodyDetailState -> s.copy(breathing = b) } }
        }
        else -> { s -> s }
    }

    /** Shows [span]: what the last one loaded is put away, so nothing shows under the wrong span. */
    fun span(span: BodySpan) {
        if (span == _state.value.span) return
        _state.update { BodyDetailState(span = span, readings = it.readings) }
        load()
    }

    /** Pull to refresh: brings in the devices' latest, then reloads. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            attempt { c.sync.sync() }
            load(quiet = true)
        }
    }

    fun deleteWeight(weighIn: Long) = viewModelScope.launch {
        attempt { c.ui.deleteMeasurement(weighIn) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't delete that") }
    }
}

/** What the page can do, as callbacks (so it can be drawn with sample data). */
class BodyDetailActions(
    val back: () -> Unit = {},
    val span: (BodySpan) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val deleteWeight: (Long) -> Unit = {},
)

/** The details of Body's card [id], starting at [span] (Body's), for [device] at 24 hours. */
@Composable
fun BodyDetailRoute(id: String, span: BodySpan?, device: String?, onBack: () -> Unit) {
    val c = appContainer()
    val readOnly = LocalReadOnly.current
    val vm = viewModel(key = "bodyDetail:$id:${device.orEmpty()}") { BodyDetailViewModel(c, id, BodyDetail.startSpan(id, span, readOnly), device?.takeIf { it.isNotBlank() }) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    val zone = c.zone()
    BodyDetailScreen(
        id, state,
        BodyDetail.Ctx(settings.units, zone, LocalDate.now(zone), profile?.goals ?: Goals(), profile?.vitalsTargets ?: VitalsTargets()),
        BodyDetailActions(back = onBack, span = vm::span, refresh = vm::refresh, retry = { vm.load() }, deleteWeight = { vm.deleteWeight(it) }),
    )
}

/** The page, drawn from [state] in the person's settings ([ctx]). */
@Composable
fun BodyDetailScreen(id: String, state: BodyDetailState, ctx: BodyDetail.Ctx, actions: BodyDetailActions) {
    val readOnly = LocalReadOnly.current
    val page = remember(id, state, ctx) { BodyDetail.page(id, state, ctx) }
    val readings = state.readings
    // At 24 hours from one device's own page, its name.
    val device = readings?.device?.takeIf { state.span == BodySpan.DAY }?.let { d -> readings.devices.firstOrNull { it.id == d }?.name }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            BackBar(actions.back, page.title, subtitle = device) {
                ArrangeAction(FeatureScreens.BODY_DETAIL, page.title, BodyDetail.SECTIONS)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SpanSwitch(state.span, actions.span, BodyDetail.spans(id, readOnly))
                when {
                    state.loading -> Loading()
                    state.error != null && page.empty -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                    page.empty -> EmptyState(Icons.Rounded.History, BodyDetail.EMPTY, null)
                    else -> {
                        state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                        Sections(arrangedIn(FeatureScreens.BODY_DETAIL, BodyDetail.SECTIONS)) { section ->
                            when (section) {
                                BodyDetail.HEADLINE -> page.headline?.let { Headline(it, page.headlineWhen) }
                                BodyDetail.CHART -> ChartSection(page, state, ctx)
                                BodyDetail.STATS -> if (page.stats.isNotEmpty() || page.against != null) Stats(page)
                                BodyDetail.LIST -> if (page.rows.isNotEmpty()) Rows(page, state.span, ctx, readOnly, actions.deleteWeight)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The latest (or today's) value, large, and when. */
@Composable
private fun Headline(value: String, whenText: String?) {
    AppCard {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            whenText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The span's chart, large, in the chart colours (or, for a night at 24 hours, its stages). */
@Composable
private fun ChartSection(page: BodyDetail.Page, state: BodyDetailState, ctx: BodyDetail.Ctx) {
    val night = page.night
    val chart = page.chart
    if (night == null && chart == null) return
    TitledCard(if (night != null) "Last night" else BodyDetail.spanTitle(state.span)) {
        if (night != null) {
            NightStages(night, ctx.zone, height = 150.dp)
            return@TitledCard
        }
        chart ?: return@TitledCard
        val colors = listOf(AppTheme.chart.c1, AppTheme.chart.c2, AppTheme.chart.c3)
        // The nights shaded behind the 24 hours, as on Body.
        val shade = state.readings?.takeIf { state.span == BodySpan.DAY }?.let { Recent.shade(it) }.orEmpty()
        Column {
            if (chart.bars) {
                TimeBars(
                    chart.lines.first(), chart.bucket, chart.axis, chart.scale, chart.description,
                    color = colors[0], shaded = shade, height = 190.dp, reference = chart.reference,
                )
            } else {
                TimeLines(
                    chart.lines.take(3).mapIndexed { i, points -> points to colors[i] }, chart.axis, chart.scale, chart.description,
                    style = if (chart.lines.sumOf { it.size } <= 1) TimeChartStyle.DOTS else TimeChartStyle.LINE,
                    shaded = shade, gap = chart.gap, height = 190.dp, reference = chart.reference,
                )
            }
            TimeAxisLabels(chart.axis)
        }
        if (chart.names.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                chart.names.take(3).forEachIndexed { i, name -> LegendItem(colors[i], name) }
            }
        }
        chart.referenceText?.let { Hint(it) }
    }
}

/** The average, lowest and highest (or what fits the reading), and how it sits against the person's usual. */
@Composable
private fun Stats(page: BodyDetail.Page) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val perRow = if (page.stats.size == 4) 4 else 3
            page.stats.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { stat ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(stat.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stat.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                            stat.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            page.against?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Each reading or day, newest first: the first few, then the rest on "Show all". A weigh-in entered by hand can be deleted. */
@Composable
private fun Rows(page: BodyDetail.Page, span: BodySpan, ctx: BodyDetail.Ctx, readOnly: Boolean, onDelete: (Long) -> Unit) {
    var all by rememberSaveable(span) { mutableStateOf(false) }
    val shown = if (all) page.rows else page.rows.take(BodyDetail.FEW)
    TitledCard(page.listTitle) {
        Column {
            shown.forEachIndexed { i, row ->
                if (i > 0) RowDivider(inset = 0.dp)
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium)
                            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(row.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        val weighIn = row.id
                        if (weighIn != null && !readOnly) {
                            IconButton(onClick = { onDelete(weighIn) }) { Icon(Icons.Rounded.Close, "Delete the weigh-in on ${row.label}", Modifier.size(18.dp)) }
                        }
                    }
                    row.night?.let { NightStages(it, ctx.zone, height = 40.dp, labels = false) }
                }
            }
        }
        if (!all && page.rows.size > BodyDetail.FEW) {
            TextButton(onClick = { all = true }) { Text("Show all ${page.rows.size}") }
        }
    }
}
