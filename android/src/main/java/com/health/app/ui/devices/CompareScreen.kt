package com.health.app.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ComparedMetric
import com.health.api.ComparedNight
import com.health.api.CompareKey
import com.health.api.DeviceComparison
import com.health.api.RecentDevice
import com.health.api.SleepSession
import com.health.api.Units
import com.health.app.Container
import com.health.app.net.ServerException
import com.health.app.net.ServerVersion
import com.health.app.net.compareDevices
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.CHART_GUTTER
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.Hypnogram
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.StatusDot
import com.health.app.ui.components.TimeAxis
import com.health.app.ui.components.TimeAxisLabels
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
import java.time.ZoneId
import java.util.Locale

/*
 * Two devices side by side, night by night (Devices › Compare devices): which to compare against
 * ("a", a chest strap, say) and which is being checked ("b"), over 7, 14 or 30 nights; a card for
 * each thing both measured, in plain words; then each night's two hypnograms on one time axis with
 * that night's values. Arranged under android.compare. Only ever the person's own (the server never
 * answers it through a share): it isn't offered while viewing one.
 */

data class CompareState(
    /** The one compared against, and the one being checked; null lets the server choose. */
    val a: String? = null,
    val b: String? = null,
    val days: Int = Compare.DEFAULT_DAYS,
    val comparison: DeviceComparison? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    /** Two devices or nights chosen that the comparison shown isn't of yet (it's loading). */
    val changing: Boolean
        get() {
            val c = comparison ?: return false
            return error == null && (days != c.days || (a != null && a != c.a?.id) || (b != null && b != c.b?.id))
        }
}

class CompareViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(CompareState())
    val state: StateFlow<CompareState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        // After a sync brings something in.
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        // Never asked for someone else's: the server refuses it through a share anyway.
        if (c.viewing.value != null) return _state.update { it.copy(loading = false, refreshing = false) }
        val asked = _state.value
        job?.cancel()
        _state.update { it.copy(loading = !quiet || it.comparison == null, error = null) }
        job = viewModelScope.launch {
            try {
                val result = fetch(asked.a, asked.b, asked.days)
                _state.update {
                    if (it.a != asked.a || it.b != asked.b || it.days != asked.days) it
                    else it.copy(comparison = result, a = result.a?.id, b = result.b?.id, loading = false, refreshing = false)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update {
                    if (it.a != asked.a || it.b != asked.b || it.days != asked.days) it
                    else it.copy(loading = false, refreshing = false, error = message(e))
                }
            }
        }
    }

    /** The two asked for; a device forgotten since (the server doesn't know it) lets the server choose again. */
    private suspend fun fetch(a: String?, b: String?, days: Int): DeviceComparison = try {
        c.api.compareDevices(a, b, days)
    } catch (e: ServerException) {
        if ((a != null || b != null) && e.fromServer && (e.code == 404 || e.code == 400)) c.api.compareDevices(null, null, days) else throw e
    }

    private fun message(e: Exception): String = when {
        e is ServerException && ServerVersion.missing(e) -> ServerVersion.needsUpdate("to compare devices")
        else -> e.message ?: "Couldn't compare your devices"
    }

    /** Compares against [id]; the one being checked already, they swap. */
    fun chooseA(id: String) = choose(id, b = false)

    /** Checks [id]; the one compared against already, they swap. */
    fun chooseB(id: String) = choose(id, b = true)

    private fun choose(id: String, b: Boolean) {
        val s = _state.value
        val (a0, b0) = s.a to s.b
        val next = if (b) (if (id == a0) b0 to id else a0 to id) else (if (id == b0) id to a0 else id to b0)
        if (next == a0 to b0) return
        _state.update { it.copy(a = next.first, b = next.second) }
        load(quiet = true)
    }

    fun swap() {
        val s = _state.value
        if (s.a == null || s.b == null) return
        _state.update { it.copy(a = s.b, b = s.a) }
        load(quiet = true)
    }

    fun days(days: Int) {
        if (days == _state.value.days) return
        _state.update { it.copy(days = days) }
        load(quiet = true)
    }

    /** Pull to refresh: brings in the devices' latest, then compares again. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            com.health.app.attempt { c.sync.sync() }
            load(quiet = true)
        }
    }
}

/** What the page can do, as callbacks (so it can be drawn with sample data). */
class CompareActions(
    val back: () -> Unit = {},
    val chooseA: (String) -> Unit = {},
    val chooseB: (String) -> Unit = {},
    val swap: () -> Unit = {},
    val days: (Int) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun CompareRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { CompareViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    CompareScreen(
        state, settings.units, c.zone(),
        CompareActions(back = onBack, chooseA = vm::chooseA, chooseB = vm::chooseB, swap = vm::swap, days = vm::days, refresh = vm::refresh, retry = { vm.load() }),
    )
}

/** The theme's colours for the two devices, as the charts use them: a in the first, b in the third. */
private class DeviceColors(val a: Color, val b: Color)

@Composable
private fun deviceColors() = DeviceColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)

/**
 * The page: the two devices and how many nights, then the sections in the person's order
 * ([FeatureScreens.COMPARE]), each only with something in it. [today] is the morning "Last night" ended.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(state: CompareState, units: Units, zone: ZoneId, actions: CompareActions, today: LocalDate = LocalDate.now(zone)) {
    val readOnly = LocalReadOnly.current
    val comparison = state.comparison
    val sections = if (readOnly || comparison == null || Compare.empty(comparison) != null) emptyList() else Compare.sections(comparison)
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { if (!readOnly) actions.refresh() }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            BackBar(actions.back, "Compare devices", subtitle = "Two devices, night by night") {
                ArrangeAction(FeatureScreens.COMPARE, "Compare devices", sections)
            }
            when {
                readOnly -> EmptyState(Icons.Rounded.Lock, "Only for your own data", "Go back to your own data to compare your devices.")
                state.loading && comparison == null -> Loading()
                state.error != null && comparison == null -> ErrorCard(state.error, Modifier.padding(horizontal = 16.dp), actionLabel = "Try again", onAction = actions.retry)
                comparison != null -> {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                        Choices(comparison, state.days, actions)
                        // A new choice is on its way: what's below is still the last one.
                        if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                    }
                    val empty = Compare.empty(comparison)
                    if (empty != null) {
                        val (title, body) = Compare.emptyText(empty, comparison.days)
                        EmptyState(Icons.AutoMirrored.Rounded.CompareArrows, title, body)
                    } else {
                        Body(comparison, sections, units, zone, today)
                    }
                }
            }
        }
    }
}

/** Which two, which way round, and over how many nights. */
@Composable
private fun Choices(c: DeviceComparison, days: Int, actions: CompareActions) {
    val options = Compare.choices(c)
    InfoCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Picker("Compare against", c.a, options, deviceColors().a, actions.chooseA)
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Picker("Checking", c.b, options, deviceColors().b, actions.chooseB)
            }
            // In words: the Arrange button above already has the up-and-down arrows.
            TextButton(onClick = actions.swap, enabled = c.a != null && c.b != null, modifier = Modifier.semantics { contentDescription = "Swap them" }) { Text("Swap") }
        }
        Spacer(Modifier.height(12.dp))
        SegmentedChoice(Compare.SPANS, Compare.SPANS.firstOrNull { it == days } ?: Compare.DEFAULT_DAYS, { "$it nights" }, actions.days)
        Spacer(Modifier.height(8.dp))
        Hint(Compare.HOW)
    }
}

/** One of the two: what it's for, the device, and the others to choose from. */
@Composable
private fun Picker(label: String, chosen: RecentDevice?, options: List<RecentDevice>, color: Color, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choosable = options.any { it.id != chosen?.id }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(enabled = choosable, onClickLabel = "Choose a device") { open = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DeviceKinds.icon(chosen?.kind), null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(chosen?.name ?: "Choose a device", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                chosen?.kind?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            if (choosable) Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            d.kind?.let { Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    leadingIcon = { Icon(DeviceKinds.icon(d.kind), null) },
                    trailingIcon = if (d.id == chosen?.id) ({ Icon(Icons.Rounded.Check, "Chosen") }) else null,
                    onClick = { open = false; if (d.id != chosen?.id) onPick(d.id) },
                )
            }
        }
    }
}

@Composable
private fun Body(c: DeviceComparison, sections: List<com.health.app.ui.components.ArrangeItem>, units: Units, zone: ZoneId, today: LocalDate) {
    val a = c.a ?: return
    val b = c.b ?: return
    val names = Compare.names(a, b)
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (c.metrics.isEmpty()) InfoCard(null) { Text(Compare.NOTHING_AT_ONCE, style = MaterialTheme.typography.bodyMedium) }
        Sections(arrangedIn(FeatureScreens.COMPARE, sections)) { id ->
            if (id == Compare.NIGHTS) Nights(c, names, units, zone, today, locale)
            else Compare.metric(c, id)?.let { MetricCard(it, names, units, locale) }
        }
    }
}

/** Each thing's picture and colour, as on the last 24 hours. */
@Composable
private fun look(key: CompareKey): Pair<ImageVector, Color> = when (key) {
    CompareKey.ASLEEP, CompareKey.DEEP, CompareKey.LIGHT, CompareKey.REM, CompareKey.AWAKE, CompareKey.BEDTIME -> Icons.Rounded.Bedtime to AppTheme.data.rem
    CompareKey.WAKE -> Icons.Rounded.WbSunny to AppTheme.data.awake
    CompareKey.NIGHT_HEART_RATE, CompareKey.LOWEST_HEART_RATE, CompareKey.DAY_HEART_RATE -> Icons.Rounded.Favorite to AppTheme.data.heart
    CompareKey.HRV -> Icons.Rounded.MonitorHeart to MaterialTheme.colorScheme.primary
    CompareKey.SPO2 -> Icons.Rounded.Bloodtype to AppTheme.data.water
    CompareKey.RESPIRATORY_RATE -> Icons.Rounded.Air to MaterialTheme.colorScheme.tertiary
    CompareKey.SKIN_TEMPERATURE -> Icons.Rounded.Thermostat to MaterialTheme.colorScheme.secondary
    CompareKey.STEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk to MaterialTheme.colorScheme.tertiary
}

/** One thing both measured: each one's average, then how they differ, in a line. */
@Composable
private fun MetricCard(m: ComparedMetric, names: Compare.Names, units: Units, locale: Locale) {
    val (icon, tint) = look(m.key)
    val colors = deviceColors()
    InfoCard(m.key.label, icon = icon, iconTint = tint) {
        Row(Modifier.fillMaxWidth()) {
            Average(names.labelA, Compare.value(m.key, m.a, units, locale = locale), colors.a, Modifier.weight(1f))
            Average(names.labelB, Compare.value(m.key, m.b, units, locale = locale), colors.b, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(Compare.sentence(m, names, units, locale), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A device's average: its dot and name, then the figure. */
@Composable
private fun Average(name: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

/** Night by night, newest first. */
@Composable
private fun Nights(c: DeviceComparison, names: Compare.Names, units: Units, zone: ZoneId, today: LocalDate, locale: Locale) {
    val noneA = Compare.noNights(c, ofA = true)
    val noneB = Compare.noNights(c, ofA = false)
    val both = Compare.measuredByBoth(c)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Night by night", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        if (noneA) Hint(Compare.noNightsLine(names, ofA = true), Modifier.padding(horizontal = 4.dp))
        if (noneB) Hint(Compare.noNightsLine(names, ofA = false), Modifier.padding(horizontal = 4.dp))
        c.nights.forEach { night ->
            val date = com.health.app.attempt { LocalDate.parse(night.date) }.getOrNull() ?: return@forEach
            NightCard(night, date, names, units, zone, today, locale, showA = !noneA, showB = !noneB, both = both)
        }
    }
}

/** A night: both hypnograms on one time axis, then what each read. */
@Composable
private fun NightCard(
    night: ComparedNight, date: LocalDate, names: Compare.Names, units: Units, zone: ZoneId, today: LocalDate, locale: Locale,
    showA: Boolean, showB: Boolean, both: Set<CompareKey>,
) {
    val colors = deviceColors()
    InfoCard(Compare.nightTitle(date, today, locale), icon = Icons.Rounded.Bedtime, iconTint = AppTheme.data.rem) {
        val span = Compare.span(night)
        if (span != null) {
            val axis = TimeAxis.of(span.first, span.second, zone, everyHours = 2, locale = locale)
            if (showA) NightRow(names.labelA, names.within(names.a), colors.a, night.a, span, axis, zone)
            if (showA && showB) Spacer(Modifier.height(10.dp))
            if (showB) NightRow(names.labelB, names.within(names.b), colors.b, night.b, span, axis, zone)
            TimeAxisLabels(axis)
            Spacer(Modifier.height(12.dp))
        }
        val rows = Compare.rows(night, both, units, zone, locale)
        if (rows.isNotEmpty()) NightTable(rows, names, colors)
    }
}

/** One device's night: its name and hours, then its stages; or a line saying it had none. */
@Composable
private fun NightRow(label: String, within: String, color: Color, session: SleepSession?, span: Pair<Long, Long>, axis: TimeAxis, zone: ZoneId) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        StatusDot(color, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (session != null) {
            Spacer(Modifier.width(8.dp))
            Text(Compare.sessionLine(session, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    if (session == null) {
        Text("No night from $within", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Hypnogram(
        Recent.stagesIn(listOf(session), span.first, span.second), span.first, span.second,
        Modifier.padding(end = CHART_GUTTER).semantics { contentDescription = Compare.nightDescription(label, session) },
        height = 64.dp,
        ticks = axis.ticks.map { it.at },
    )
}

/** The night's values: what, then each device's, in its colour's column. */
@Composable
private fun NightTable(rows: List<Compare.Row>, names: Compare.Names, colors: DeviceColors) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1.4f))
            Header(names.labelA, colors.a, Modifier.weight(1f))
            Header(names.labelB, colors.b, Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {
                    contentDescription = "${r.label}: ${names.labelA} ${r.a}, ${names.labelB} ${r.b}"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
                Text(r.a, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1f))
                Text(r.b, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(name: String, color: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 8.dp)
        Spacer(Modifier.width(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
