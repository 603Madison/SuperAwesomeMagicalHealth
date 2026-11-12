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
import com.health.api.CyclePhase
import com.health.api.Flow
import com.health.api.Mucus
import com.health.api.OvulationTest
import com.health.api.Units
import com.health.app.Container
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
                val overview = c.api.cycle(12)
                _state.update { it.copy(overview = overview, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your cycle") }
            }
        }
    }

    fun month(month: YearMonth) = _state.update { it.copy(month = month) }

    fun save(day: CycleDay) = viewModelScope.launch {
        runCatching { c.api.saveCycleDay(day) }
            .onSuccess { c.tell("Saved"); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun clear(date: LocalDate) = viewModelScope.launch {
        runCatching { c.api.deleteCycleDay(date.toString()) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't clear that day") }
    }
}

class CycleActions(
    val month: (YearMonth) -> Unit = {},
    val save: (CycleDay) -> Unit = {},
    val clear: (LocalDate) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun CycleRoute(logDate: LocalDate? = null) {
    val c = appContainer()
    val vm = viewModel { CycleViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    CycleScreen(state, settings.units, CycleActions(vm::month, { vm.save(it) }, { vm.clear(it) }, { vm.load() }), openDay = logDate)
}

@Composable
fun CycleScreen(state: CycleState, units: Units, actions: CycleActions, openDay: LocalDate? = null) {
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(openDay, state.overview != null) { if (openDay != null && state.overview != null) editing = openDay }
    val overview = state.overview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Cycle", "Periods, symptoms and what's likely next")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && overview == null -> Loading()
                state.error != null && overview == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                overview != null -> {
                    TodayCard(overview, onLog = { editing = state.today })
                    CalendarCard(state, overview, actions.month) { editing = it }
                    AveragesCard(overview)
                    if (overview.temperatures.size >= 3) TemperatureCard(overview, units)
                    if (overview.cycles.isNotEmpty()) CyclesCard(overview)
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
private fun TodayCard(overview: CycleOverview, onLog: () -> Unit) {
    AppCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(overview.cycleDay?.let { "Day $it" } ?: "No cycle yet", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                if (overview.phase != CyclePhase.UNKNOWN) Pill(overview.phase.label, phaseColor(overview.phase))
                periodText(overview.daysUntilPeriod)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
                if (overview.cycleDay == null) Hint("Log your next period and the calendar fills in from there.")
            }
            FilledTonalButton(onClick = onLog) { Text("Log today") }
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
    val a = from?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return emptySet()
    val b = to?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: a
    if (b.isBefore(a) || ChronoUnit.DAYS.between(a, b) > 20) return setOf(a)
    return generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.toSet()
}

@Composable
private fun CalendarCard(state: CycleState, overview: CycleOverview, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val month = state.month
    val logged = overview.days.associateBy { it.date }
    val prediction = overview.prediction
    val predictedPeriod = prediction?.let {
        val length = (overview.averagePeriod ?: 5.0).roundToInt().coerceIn(1, 10)
        val start = LocalDate.parse(it.nextPeriod)
        dates(it.nextPeriod, start.plusDays(length - 1L).toString())
    }.orEmpty()
    val fertile = dates(prediction?.fertileStart, prediction?.fertileEnd)
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
            LegendItem(green.copy(alpha = 0.4f), "Fertile window (predicted)")
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
private fun TemperatureCard(overview: CycleOverview, units: Units) {
    val temps = overview.temperatures
    InfoCard("Temperature", icon = Icons.Rounded.Thermostat, iconTint = AppTheme.data.heart) {
        val shown = temps.map { if (units == Units.IMPERIAL) it.value * 9 / 5 + 32 else it.value }
        LineChart(shown, color = AppTheme.data.heart, height = 80.dp)
        AxisLabels(Format.shortDay(LocalDate.parse(temps.first().date)), null, Format.shortDay(LocalDate.parse(temps.last().date)))
        Spacer(Modifier.height(6.dp))
        Hint("A small rise that lasts (about 0.2–0.5 °C) usually comes after ovulation.")
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

/** Logging a day: flow, symptoms, pain, signs and a note. */
@Composable
fun DayForm(date: LocalDate, existing: CycleDay?, units: Units, onSave: (CycleDay) -> Unit, onClear: () -> Unit) {
    var flow by remember { mutableStateOf(existing?.flow) }
    var symptoms by remember { mutableStateOf(existing?.symptoms.orEmpty().toSet()) }
    var pain by remember { mutableStateOf(existing?.pain) }
    var mucus by remember { mutableStateOf(existing?.mucus) }
    var test by remember { mutableStateOf(existing?.ovulationTest) }
    var temp by remember {
        mutableStateOf(existing?.temperature?.let { Format.upTo2(if (units == Units.IMPERIAL) it * 9 / 5 + 32 else it) }.orEmpty())
    }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(Format.longDay(date), style = MaterialTheme.typography.titleLarge)
        Text("Bleeding", style = MaterialTheme.typography.titleSmall)
        SegmentedChoice(listOf<Flow?>(null) + Flow.entries, flow, { it?.label ?: "None" }, { flow = it })
        Text("Symptoms", style = MaterialTheme.typography.titleSmall)
        ChipPicker(SYMPTOMS, symptoms, { symptoms = it }, addLabel = "Another symptom")
        Column {
            Row {
                Text("Pain", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(pain?.let { if (it == 0) "None" else "$it of 10" } ?: "Not noted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = (pain ?: 0).toFloat(), onValueChange = { pain = it.roundToInt() }, valueRange = 0f..10f, steps = 9)
        }
        Text("Cervical mucus", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mucus.entries.forEach { m -> FilterChip(selected = mucus == m, onClick = { mucus = if (mucus == m) null else m }, label = { Text(m.label) }) }
        }
        Text("Ovulation test", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OvulationTest.entries.forEach { t -> FilterChip(selected = test == t, onClick = { test = if (test == t) null else t }, label = { Text(t.label) }) }
        }
        NumberField(temp, { temp = it }, "Temperature on waking", suffix = if (units == Units.IMPERIAL) "°F" else "°C", supporting = "Optional: your ring's nightly temperature is used too")
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
        val celsius = Format.parse(temp)?.let { if (units == Units.IMPERIAL) (it - 32) * 5 / 9 else it }?.takeIf { it in 34.0..40.0 }
        Button(
            onClick = {
                onSave(CycleDay(date.toString(), flow, symptoms.toList(), pain, mucus, test, celsius, note.trim().ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (existing != null) TextButton(onClick = onClear, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Clear this day") }
    }
}
