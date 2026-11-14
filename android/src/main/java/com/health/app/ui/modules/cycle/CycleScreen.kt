@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.cycle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.health.app.attempt
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.Sections
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.CycleSettings
import com.health.api.Fields
import com.health.api.SupplyStatus
import com.health.api.CyclePhase
import com.health.api.Flow
import com.health.api.Mucus
import com.health.api.OvulationTest
import com.health.api.Units
import com.health.app.Container
import com.health.app.net.cycleSettings
import com.health.app.net.supplyStatus
import com.health.app.ui.components.shownFields
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

val SYMPTOMS = listOf(
    "cramps", "headache", "bloating", "breast tenderness", "acne", "back pain", "fatigue", "nausea", "cravings",
    "trouble sleeping", "mood swings", "irritability", "anxiety", "low mood", "hot flashes", "night sweats",
)

data class CycleState(
    val today: LocalDate,
    val month: YearMonth = YearMonth.from(today),
    val overview: CycleOverview? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The person's own symptoms, moods, fields, lengths and wording. */
    val settings: CycleSettings = CycleSettings(),
    /** Supplies with how long they'll last (period products are picked from these). */
    val supplies: List<SupplyStatus> = emptyList(),
)

class CycleViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(CycleState(LocalDate.now(c.zone())))
    val state: StateFlow<CycleState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.overview == null, error = null, today = LocalDate.now(c.zone())) }
        viewModelScope.launch {
            try {
                val overview = async { c.ui.cycle(12) }
                // The detail: each can be missing without spoiling the calendar.
                val settings = async { attempt { c.ui.cycleSettings() }.getOrNull() }
                val supplies = async { attempt { c.ui.supplyStatus() }.getOrNull() }
                _state.update {
                    it.copy(overview = overview.await(), loading = false, settings = settings.await() ?: it.settings, supplies = supplies.await() ?: it.supplies)
                }
                if (c.viewing.value == null) settings.await()?.let { CycleWording.neutral.value = it.neutralWording }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your cycle") }
            }
        }
    }

    fun month(month: YearMonth) = _state.update { it.copy(month = month) }

    fun save(day: CycleDay) = viewModelScope.launch {
        attempt { c.ui.saveCycleDay(day) }
            .onSuccess { c.tell("Saved"); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun clear(date: LocalDate) = viewModelScope.launch {
        attempt { c.ui.deleteCycleDay(date.toString()) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't clear that day") }
    }
}

class CycleActions(
    val month: (YearMonth) -> Unit = {},
    val save: (CycleDay) -> Unit = {},
    val clear: (LocalDate) -> Unit = {},
    val retry: () -> Unit = {},
    /** Opens "cycle/settings" or "supplies/cycle". */
    val open: (String) -> Unit = {},
)

@Composable
fun CycleRoute(logDate: LocalDate? = null, onOpen: (String) -> Unit = {}) {
    val c = appContainer()
    val vm = viewModel { CycleViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    // Life stages (pregnancy, after a birth, perimenopause and after) live alongside the cycle.
    val stages = viewModel { com.health.app.ui.care.LifeStageViewModel(c) }
    val stage by stages.state.collectAsState()
    CycleScreen(
        state, settings.units, CycleActions(vm::month, { vm.save(it) }, { vm.clear(it) }, { vm.load() }, onOpen), openDay = logDate,
        fields = shownFields(),
        lifeStage = LifeStageSlot(com.health.app.ui.care.lifeStageSections(stage.overview?.info?.stage)) { id ->
            com.health.app.ui.care.LifeStagePart(
                id, stage, c.zone(),
                com.health.app.ui.care.LifeStageActions(
                    saveInfo = { stages.saveInfo(it) }, startKicks = stages::startKicks, kick = stages::kick, finishKicks = stages::finishKicks,
                    cancelKicks = stages::cancelKicks, toggleContraction = stages::toggleContraction, startFeed = { stages.startFeed(it) },
                    stopFeed = stages::stopFeed, hotFlash = { stages.hotFlash(it) },
                ),
            )
        },
    )
}

/** The life stage's cards on the Cycle screen: those that apply now, and how to draw each by its id. */
class LifeStageSlot(val sections: List<ArrangeItem>, val part: @Composable (String) -> Unit)

/**
 * Cycle's sections in the default order (the sections of [FeatureScreens.CYCLE]), with the life
 * stage's cards after today's, and period products and temperature when their fields are on.
 */
fun cycleSections(fields: Set<String> = Fields.DEFAULTS, lifeStage: List<ArrangeItem> = emptyList()): List<ArrangeItem> = buildList {
    add(ArrangeItem("today", "Today"))
    addAll(lifeStage)
    add(ArrangeItem("calendar", "Calendar"))
    if (Fields.CYCLE_PRODUCTS in fields) add(ArrangeItem("lowsupplies", "Products running low"))
    add(ArrangeItem("averages", "Your averages"))
    if (Fields.CYCLE_BBT in fields) add(ArrangeItem("temperature", "Temperature"))
    add(ArrangeItem("cycles", "Past cycles"))
    if (Fields.CYCLE_PRODUCTS in fields) add(ArrangeItem("products", "Period products"))
}

@Composable
fun CycleScreen(
    state: CycleState,
    units: Units,
    actions: CycleActions,
    openDay: LocalDate? = null,
    lifeStage: LifeStageSlot? = null,
    fields: Set<String> = Fields.DEFAULTS,
) {
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    // Without fertility words for people who'd rather not see them, as the server does.
    val plain = CycleText.plain(state.settings, fields)
    LaunchedEffect(openDay, state.overview != null) { if (openDay != null && state.overview != null) editing = openDay }
    val overview = state.overview
    val sections = cycleSections(fields, lifeStage?.sections.orEmpty())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Cycle", "Periods, symptoms and what's likely next") {
            if (!com.health.app.ui.care.LocalReadOnly.current) {
                IconButton(onClick = { actions.open("cycle/settings") }) { Icon(Icons.Rounded.Settings, "Cycle settings") }
            }
            ArrangeAction(FeatureScreens.CYCLE, "Cycle", sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && overview == null -> Loading()
                state.error != null && overview == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                overview != null -> {
                    // Someone viewing a share looks at the calendar but doesn't open days to change them.
                    val readOnly = com.health.app.ui.care.LocalReadOnly.current
                    Sections(arrangedIn(FeatureScreens.CYCLE, sections)) { id ->
                        when (id) {
                            "today" -> TodayCard(overview, plain, onLog = { if (!readOnly) editing = state.today })
                            "calendar" -> CalendarCard(state, overview, plain, actions.month) { if (!readOnly) editing = it }
                            "lowsupplies" -> {
                                val low = state.supplies.filter { it.low && it.supply.kind.category == com.health.api.SupplyCategory.PERIOD }
                                if (low.isNotEmpty()) com.health.app.ui.modules.continence.LowSuppliesCard(low) { actions.open("supplies/cycle") }
                            }
                            "averages" -> AveragesCard(overview)
                            "temperature" -> if (overview.temperatures.size >= 3) TemperatureCard(overview, units, plain)
                            "cycles" -> if (overview.cycles.isNotEmpty()) CyclesCard(overview)
                            "products" -> InfoCard(null) {
                                com.health.app.ui.modules.NavRow(
                                    Icons.Rounded.Inventory2, "Period products", "What's on hand, and when to restock",
                                    onClick = { actions.open("supplies/cycle") },
                                )
                            }
                            else -> lifeStage?.part?.invoke(id)
                        }
                    }
                    Hint(overview.caveat, Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
    val day = editing
    if (day != null && overview != null) {
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            DayForm(
                date = day,
                existing = overview.days.firstOrNull { it.date == day.toString() },
                units = units,
                onSave = { actions.save(it); editing = null },
                onClear = { actions.clear(day); editing = null },
                fields = fields,
                settings = state.settings,
                supplies = state.supplies.map { it.supply },
            )
        }
    }
}

/** "Period in 5 days", "Period expected today", "Period is 3 days later than expected". */
fun periodText(daysUntil: Int?): String? = when {
    daysUntil == null -> null
    daysUntil > 1 -> "Period in $daysUntil days"
    daysUntil == 1 -> "Period likely tomorrow"
    daysUntil == 0 -> "Period expected today"
    else -> "Period ${-daysUntil} ${if (daysUntil == -1) "day" else "days"} later than expected"
}

@Composable
private fun TodayCard(overview: CycleOverview, plain: Boolean, onLog: () -> Unit) {
    AppCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(overview.cycleDay?.let { "Day $it" } ?: "No cycle yet", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                if (overview.phase != CyclePhase.UNKNOWN) Pill(CycleText.phase(overview.phase, plain), phaseColor(overview.phase))
                periodText(overview.daysUntilPeriod)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                if (overview.cycleDay == null) Hint("Log your next period and the calendar fills in from there.")
            }
            if (!com.health.app.ui.care.LocalReadOnly.current) FilledTonalButton(onClick = onLog) { Text("Log today") }
        }
    }
}

@Composable
fun phaseColor(phase: CyclePhase): Color = when (phase) {
    CyclePhase.PERIOD -> periodColor()
    CyclePhase.FERTILE -> MaterialTheme.colorScheme.tertiary
    CyclePhase.LUTEAL -> MaterialTheme.colorScheme.primary
    CyclePhase.FOLLICULAR -> AppTheme.data.fat
    CyclePhase.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun periodColor(): Color = AppTheme.data.protein

private fun flowStrength(flow: Flow): Float = when (flow) {
    Flow.SPOTTING -> 0.25f
    Flow.LIGHT -> 0.45f
    Flow.MEDIUM -> 0.7f
    Flow.HEAVY -> 1f
}

private fun dates(from: String?, to: String?): Set<LocalDate> {
    val a = from?.let { attempt { LocalDate.parse(it) }.getOrNull() } ?: return emptySet()
    val b = to?.let { attempt { LocalDate.parse(it) }.getOrNull() } ?: a
    if (b.isBefore(a) || ChronoUnit.DAYS.between(a, b) > 20) return setOf(a)
    return generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.toSet()
}

@Composable
private fun CalendarCard(state: CycleState, overview: CycleOverview, plain: Boolean, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val month = state.month
    val logged = overview.days.associateBy { it.date }
    val prediction = overview.prediction
    val predictedPeriod = prediction?.let {
        val length = (overview.averagePeriod ?: 5.0).roundToInt().coerceIn(1, 10)
        val start = LocalDate.parse(it.nextPeriod)
        dates(it.nextPeriod, start.plusDays(length - 1L).toString())
    }.orEmpty()
    val fertile = if (plain) emptySet() else dates(prediction?.fertileStart, prediction?.fertileEnd)
    val rose = periodColor()
    val green = MaterialTheme.colorScheme.tertiary
    InfoCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Month before") }
            Text(DateTimeFormatter.ofPattern("MMMM yyyy").format(month), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Month after") }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                Text(d.name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        (0 until rows).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val index = row * 7 + col - lead
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (index in 0 until month.lengthOfMonth()) {
                            val date = month.atDay(index + 1)
                            val day = logged[date.toString()]
                            val flow = day?.flow
                            val isToday = date == state.today
                            val predicted = flow == null && date in predictedPeriod && !date.isBefore(state.today)
                            val inFertile = flow == null && date in fertile && !date.isBefore(state.today)
                            val description = buildString {
                                append(Format.shortDay(date))
                                flow?.let { append(", ${it.label.lowercase()} flow") }
                                if (predicted) append(", period predicted")
                                if (inFertile) append(", fertile window predicted")
                                if (day?.symptoms?.isNotEmpty() == true) append(", ${day.symptoms.size} symptoms")
                            }
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape)
                                    .background(
                                        when {
                                            flow != null -> rose.copy(alpha = flowStrength(flow))
                                            inFertile -> green.copy(alpha = 0.16f)
                                            else -> Color.Transparent
                                        },
                                    )
                                    .then(if (predicted) Modifier.border(1.5.dp, rose.copy(alpha = 0.7f), CircleShape) else Modifier)
                                    .then(if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                    .clickable { onDay(date) }
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${date.dayOfMonth}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isToday) FontWeight.Bold else null,
                                        color = if (flow != null && flowStrength(flow) >= 0.7f) Color.White else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (day != null && (day.symptoms.isNotEmpty() || day.note != null || day.pain != null)) {
                                        Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendItem(rose, "Period")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).border(1.5.dp, rose, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("Predicted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!plain) LegendItem(green.copy(alpha = 0.4f), "Fertile window (predicted)")
            LegendItem(MaterialTheme.colorScheme.primary, "Symptoms or notes")
        }
        Spacer(Modifier.height(6.dp))
        Hint("Tap a day to log it.")
    }
}

@Composable
private fun AveragesCard(overview: CycleOverview) {
    InfoCard("Your averages", icon = Icons.Rounded.CalendarMonth, iconTint = periodColor()) {
        Row(Modifier.fillMaxWidth()) {
            Stat(overview.averageCycle?.let { Format.compact(it) } ?: "—", "cycle length", Modifier.weight(1f), unit = "days")
            Stat(overview.averagePeriod?.let { Format.compact(it) } ?: "—", "period length", Modifier.weight(1f), unit = "days")
            Stat(overview.variation?.let { "±${Format.compact(it)}" } ?: "—", "variation", Modifier.weight(1f), unit = "days")
        }
        overview.prediction?.let { p ->
            Spacer(Modifier.height(10.dp))
            Text(
                "Next period around ${Format.shortDay(LocalDate.parse(p.nextPeriod))} (between ${Format.shortDay(LocalDate.parse(p.earliest))} and ${Format.shortDay(LocalDate.parse(p.latest))}).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint(
                when (p.confidence) {
                    "high" -> "Your cycles have been regular, so this is a fairly confident guess."
                    "medium" -> "A reasonable guess, from your last few cycles."
                    else -> "An early guess: it gets better after three cycles."
                },
            )
        }
    }
}

@Composable
private fun TemperatureCard(overview: CycleOverview, units: Units, plain: Boolean) {
    val temps = overview.temperatures
    InfoCard("Temperature", icon = Icons.Rounded.Thermostat, iconTint = AppTheme.data.heart) {
        val shown = temps.map { if (units == Units.IMPERIAL) it.value * 9 / 5 + 32 else it.value }
        LineChart(shown, color = AppTheme.data.heart, height = 80.dp)
        AxisLabels(Format.shortDay(LocalDate.parse(temps.first().date)), null, Format.shortDay(LocalDate.parse(temps.last().date)))
        Spacer(Modifier.height(6.dp))
        Hint(if (plain) "A small rise that lasts (about 0.2–0.5 °C) usually comes in the second half of a cycle." else "A small rise that lasts (about 0.2–0.5 °C) usually comes after ovulation.")
    }
}

@Composable
private fun CyclesCard(overview: CycleOverview) {
    InfoCard("Past cycles") {
        overview.cycles.sortedByDescending { it.start }.take(8).forEachIndexed { i, cycle ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("From ${Format.shortDay(LocalDate.parse(cycle.start))}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            "${cycle.periodLength}-day period",
                            cycle.ovulation?.let { "temperature rise ${Format.shortDay(LocalDate.parse(it))}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(cycle.length?.let { "$it days" } ?: "now", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
