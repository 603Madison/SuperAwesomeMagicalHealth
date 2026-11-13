@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.BloodPressure
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.VitalsSummary
import com.health.api.VitalsTargets
import com.health.app.Container
import com.health.app.net.addBloodPressure
import com.health.app.net.addGlucose
import com.health.app.net.deleteBloodPressure
import com.health.app.net.deleteGlucose
import com.health.app.net.vitalsSummary
import com.health.app.ui.Format
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LegendItem
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.log.NumberField
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class VitalsState(
    val days: Int = 30,
    val summary: VitalsSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class VitalsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(VitalsState())
    val state: StateFlow<VitalsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun range(days: Int) {
        _state.update { it.copy(days = days) }
        load()
    }

    fun load(quiet: Boolean = false) {
        val days = _state.value.days
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null) }
        viewModelScope.launch {
            try {
                val summary = c.ui.vitalsSummary(days)
                _state.update { if (it.days == days) it.copy(summary = summary, loading = false) else it }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your readings") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { message?.let(c::tell); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "That didn't work") }
    }

    fun addBloodPressure(bp: BloodPressure) = act("Blood pressure saved") { c.ui.addBloodPressure(bp) }

    fun addGlucose(g: GlucoseReading) = act("Glucose saved") { c.ui.addGlucose(g) }

    fun deleteBloodPressure(id: String) = act(null) { c.ui.deleteBloodPressure(id) }

    fun deleteGlucose(id: String) = act(null) { c.ui.deleteGlucose(id) }

    fun saveTargets(targets: VitalsTargets) = act("Targets saved") {
        val profile = c.profile.value ?: c.loadProfile() ?: return@act
        c.saveProfile(profile.copy(vitalsTargets = targets))
    }
}

class VitalsActions(
    val range: (Int) -> Unit = {},
    val retry: () -> Unit = {},
    val addBloodPressure: (BloodPressure) -> Unit = {},
    val addGlucose: (GlucoseReading) -> Unit = {},
    val deleteBloodPressure: (String) -> Unit = {},
    val deleteGlucose: (String) -> Unit = {},
    val saveTargets: (VitalsTargets) -> Unit = {},
)

@Composable
fun VitalsRoute() {
    val c = appContainer()
    val vm = viewModel { VitalsViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    VitalsScreen(
        state, profile?.vitalsTargets ?: VitalsTargets(), c.zone(),
        VitalsActions(
            range = vm::range, retry = { vm.load() }, addBloodPressure = { vm.addBloodPressure(it) }, addGlucose = { vm.addGlucose(it) },
            deleteBloodPressure = { vm.deleteBloodPressure(it) }, deleteGlucose = { vm.deleteGlucose(it) }, saveTargets = { vm.saveTargets(it) },
        ),
    )
}

private enum class VitalsDialog { BP, GLUCOSE, TARGETS }

/** The sections of blood pressure and glucose (the sections of [FeatureScreens.VITALS]), in the default order. */
val VITALS_SECTIONS = listOf(
    ArrangeItem("insights", "What we've noticed"),
    ArrangeItem("bp", "Blood pressure"),
    ArrangeItem("glucose", "Glucose"),
)

@Composable
fun VitalsScreen(state: VitalsState, targets: VitalsTargets, zone: ZoneId, actions: VitalsActions, now: Long = System.currentTimeMillis()) {
    var dialog by remember { mutableStateOf<VitalsDialog?>(null) }
    var removing by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("BP & glucose", "Against your own targets") {
            IconButton(onClick = { dialog = VitalsDialog.TARGETS }) { Icon(Icons.Rounded.Tune, "Your targets") }
            ArrangeAction(FeatureScreens.VITALS, "Blood pressure & glucose", VITALS_SECTIONS)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { dialog = VitalsDialog.BP }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.MonitorHeart, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Blood pressure")
                }
                FilledTonalButton(onClick = { dialog = VitalsDialog.GLUCOSE }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.Bloodtype, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Glucose")
                }
            }
            SegmentedChoice(listOf(7, 30, 90), state.days, { "$it days" }, actions.range)
            val summary = state.summary
            when {
                state.loading && summary == null -> Loading()
                state.error != null && summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                summary != null -> Sections(arrangedIn(FeatureScreens.VITALS, VITALS_SECTIONS)) { id ->
                    when (id) {
                        "insights" -> summary.patterns.forEach { InsightCard(it) }
                        "bp" -> BloodPressureCard(summary, zone, now) { bp -> removing = "the reading ${bp.systolic}/${bp.diastolic}" to { bp.id?.let(actions.deleteBloodPressure) } }
                        "glucose" -> GlucoseCard(summary, targets.glucoseUnit, zone, now) { g -> removing = "the reading ${Glucose.text(g.mmolL, targets.glucoseUnit)}" to { g.id?.let(actions.deleteGlucose) } }
                    }
                }
            }
            Hint(
                "Readings also come from monitors paired in Devices, and from Health Connect. They help you see how you're doing; " +
                    "they aren't a diagnosis. If one worries you, or you feel unwell, talk to your doctor or pharmacist.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    when (dialog) {
        VitalsDialog.BP -> BloodPressureDialog(onDismiss = { dialog = null }) { actions.addBloodPressure(it); dialog = null }
        VitalsDialog.GLUCOSE -> GlucoseDialog(targets.glucoseUnit, onDismiss = { dialog = null }) { actions.addGlucose(it); dialog = null }
        VitalsDialog.TARGETS -> TargetsDialog(targets, onDismiss = { dialog = null }) { actions.saveTargets(it); dialog = null }
        null -> Unit
    }
    removing?.let { (what, remove) ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $what?") },
            confirmButton = { TextButton(onClick = { remove(); removing = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Readings by day, oldest first, for charts: the day's average where there are several. */
private fun <T> byDay(items: List<T>, days: Int, now: Long, zone: ZoneId, at: (T) -> Long, value: (T) -> Double): List<Double?> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val grouped = items.groupBy { Instant.ofEpochMilli(at(it)).atZone(zone).toLocalDate() }
    return (days - 1 downTo 0).map { back -> grouped[today.minusDays(back.toLong())]?.map(value)?.average() }
}

private fun dateOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

@Composable
private fun BloodPressureCard(summary: VitalsSummary, zone: ZoneId, now: Long, onRemove: (BloodPressure) -> Unit) {
    InfoCard("Blood pressure", icon = Icons.Rounded.MonitorHeart, iconTint = AppTheme.data.heart) {
        val readings = summary.bloodPressure.sortedBy { it.at }
        if (readings.isEmpty()) {
            Hint("No readings in these days.")
            return@InfoCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sys = summary.systolicAverage?.roundToInt()
            val dia = summary.diastolicAverage?.roundToInt()
            Stat(if (sys != null && dia != null) "$sys/$dia" else "—", "average, mmHg", Modifier.weight(1f))
            summary.category?.let { Pill(it.label, bpColor(it)) }
        }
        Spacer(Modifier.height(12.dp))
        val t = summary.targets
        val sysColor = AppTheme.data.heart
        val diaColor = MaterialTheme.colorScheme.primary
        BandChart(
            listOf(
                ChartSeries(byDay(readings, summary.days, now, zone, { it.at }, { it.systolic.toDouble() }), sysColor),
                ChartSeries(byDay(readings, summary.days, now, zone, { it.at }, { it.diastolic.toDouble() }), diaColor),
            ),
            description = "Blood pressure each day; your targets are under ${t.systolicHigh} over ${t.diastolicHigh}",
        )
        val today = dateOf(now, zone)
        AxisLabels(Format.shortDay(today.minusDays(summary.days - 1L)), null, Format.shortDay(today))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(sysColor, "Top (systolic)")
            LegendItem(diaColor, "Bottom (diastolic)")
        }
        Hint("Your targets: under ${t.systolicHigh}/${t.diastolicHigh}.", Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        readings.reversed().take(6).forEachIndexed { i, bp ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadingRow(
                "${bp.systolic}/${bp.diastolic}" + (bp.pulse?.let { " · pulse $it" } ?: ""),
                listOfNotNull(Format.day(dateOf(bp.at, zone), dateOf(now, zone)) + ", " + Format.timeOfDay(bp.at, zone), bp.arm?.let { "$it arm" }, bp.source).joinToString(" · "),
                bpCategory(bp.systolic, bp.diastolic).let { it.label to bpColor(it) },
            ) { onRemove(bp) }
        }
    }
}

@Composable
private fun GlucoseCard(summary: VitalsSummary, unit: GlucoseUnit, zone: ZoneId, now: Long, onRemove: (GlucoseReading) -> Unit) {
    InfoCard("Glucose", icon = Icons.Rounded.Bloodtype, iconTint = MaterialTheme.colorScheme.tertiary) {
        val readings = summary.glucose.sortedBy { it.at }
        if (readings.isEmpty()) {
            Hint("No readings in these days.")
            return@InfoCard
        }
        Row(Modifier.fillMaxWidth()) {
            Stat(summary.glucoseAverage?.let { Glucose.number(it, unit) } ?: "—", "average, ${unit.label}", Modifier.weight(1f))
            Stat(summary.timeInRange?.let { "$it%" } ?: "—", "in your range", Modifier.weight(1f), color = AppTheme.status.good)
            summary.gmi?.let { Stat("${Format.compact(it)}%", "A1c estimate", Modifier.weight(1f)) }
        }
        if (summary.timeInRange != null) {
            Spacer(Modifier.height(10.dp))
            com.health.app.ui.components.StackedBar(
                listOf(
                    AppTheme.status.bad to (summary.timeBelow ?: 0).toDouble(),
                    AppTheme.status.good to (summary.timeInRange ?: 0).toDouble(),
                    AppTheme.status.warn to (summary.timeAbove ?: 0).toDouble(),
                ),
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendItem(AppTheme.status.bad, "Below", value = "${summary.timeBelow ?: 0}%")
                LegendItem(AppTheme.status.good, "In range", value = "${summary.timeInRange ?: 0}%")
                LegendItem(AppTheme.status.warn, "Above", value = "${summary.timeAbove ?: 0}%")
            }
        }
        Spacer(Modifier.height(12.dp))
        val t = summary.targets
        BandChart(
            listOf(ChartSeries(byDay(readings, summary.days, now, zone, { it.at }, { Glucose.value(it.mmolL, unit) }), MaterialTheme.colorScheme.tertiary)),
            band = Glucose.value(t.glucoseLow, unit)..Glucose.value(t.glucoseHigh, unit),
            description = "Glucose each day against your range, ${Glucose.number(t.glucoseLow, unit)} to ${Glucose.text(t.glucoseHigh, unit)}",
        )
        val today = dateOf(now, zone)
        AxisLabels(Format.shortDay(today.minusDays(summary.days - 1L)), null, Format.shortDay(today))
        Hint("Your range: ${Glucose.number(t.glucoseLow, unit)} to ${Glucose.text(t.glucoseHigh, unit)}, shaded.", Modifier.padding(top = 4.dp))
        summary.fastingAverage?.let { Hint("Fasting, on average: ${Glucose.text(it, unit)}.") }
        Spacer(Modifier.height(8.dp))
        readings.reversed().filter { it.context != GlucoseContext.CGM }.take(6).forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val tone = when {
                g.mmolL < t.glucoseLow -> "Low" to AppTheme.status.bad
                g.mmolL > t.glucoseHigh -> "High" to AppTheme.status.warn
                else -> "In range" to AppTheme.status.good
            }
            ReadingRow(
                Glucose.text(g.mmolL, unit),
                listOfNotNull(Format.day(dateOf(g.at, zone), dateOf(now, zone)) + ", " + Format.timeOfDay(g.at, zone), g.context.label.lowercase(), g.source).joinToString(" · "),
                tone,
            ) { onRemove(g) }
        }
    }
}

@Composable
private fun ReadingRow(title: String, subtitle: String, tone: Pair<String, androidx.compose.ui.graphics.Color>, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Remove", onClick = onLongPress).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Pill(tone.first, tone.second)
    }
}

@Composable
fun BloodPressureDialog(onDismiss: () -> Unit, onSave: (BloodPressure) -> Unit) {
    var systolic by remember { mutableStateOf("") }
    var diastolic by remember { mutableStateOf("") }
    var pulse by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf<String?>(null) }
    val sys = systolic.toIntOrNull()?.takeIf { it in 40..300 }
    val dia = diastolic.toIntOrNull()?.takeIf { it in 20..250 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blood pressure") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(systolic, { systolic = it }, "Top", Modifier.weight(1f), suffix = "mmHg", decimals = false)
                    NumberField(diastolic, { diastolic = it }, "Bottom", Modifier.weight(1f), suffix = "mmHg", decimals = false)
                }
                NumberField(pulse, { pulse = it }, "Pulse (if shown)", suffix = "bpm", decimals = false)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("left", "right").forEach { a ->
                        FilterChip(arm == a, { arm = if (arm == a) null else a }, label = { Text("${a.replaceFirstChar { it.uppercase() }} arm") })
                    }
                }
                if (sys != null && dia != null) {
                    val category = bpCategory(sys, dia)
                    Pill(category.label, bpColor(category))
                    if (category == com.health.api.BpCategory.CRISIS) {
                        Hint("That's very high. Sit quietly for five minutes and measure again. If it's still this high, or you have chest pain, shortness of breath, or trouble seeing or speaking, call your local emergency number.")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sys != null && dia != null, onClick = { onSave(BloodPressure(System.currentTimeMillis(), sys!!, dia!!, pulse.toIntOrNull(), arm)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun GlucoseDialog(unit: GlucoseUnit, onDismiss: () -> Unit, onSave: (GlucoseReading) -> Unit) {
    var value by remember { mutableStateOf("") }
    var context by remember { mutableStateOf(GlucoseContext.OTHER) }
    val parsed = Format.parse(value)?.let { Glucose.toMmol(it, unit) }?.takeIf { it in 0.5..50.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Glucose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(value, { value = it }, "Reading", suffix = unit.label, decimals = unit == GlucoseUnit.MMOL_L)
                Text("When", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlucoseContext.entries.filter { it != GlucoseContext.CGM }.forEach { cx ->
                        FilterChip(context == cx, { context = cx }, label = { Text(cx.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onSave(GlucoseReading(System.currentTimeMillis(), Math.round(parsed!! * 10) / 10.0, context)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TargetsDialog(targets: VitalsTargets, onDismiss: () -> Unit, onSave: (VitalsTargets) -> Unit) {
    var unit by remember { mutableStateOf(targets.glucoseUnit) }
    var sys by remember { mutableStateOf(targets.systolicHigh.toString()) }
    var dia by remember { mutableStateOf(targets.diastolicHigh.toString()) }
    var low by remember { mutableStateOf(Glucose.number(targets.glucoseLow, targets.glucoseUnit)) }
    var high by remember { mutableStateOf(Glucose.number(targets.glucoseHigh, targets.glucoseUnit)) }
    fun switch(to: GlucoseUnit) {
        Format.parse(low)?.let { low = Glucose.number(Glucose.toMmol(it, unit), to) }
        Format.parse(high)?.let { high = Glucose.number(Glucose.toMmol(it, unit), to) }
        unit = to
    }
    val lowMmol = Format.parse(low)?.let { Glucose.toMmol(it, unit) }
    val highMmol = Format.parse(high)?.let { Glucose.toMmol(it, unit) }
    val ok = sys.toIntOrNull() in 90..200 && dia.toIntOrNull() in 50..130 && lowMmol != null && highMmol != null && lowMmol < highMmol
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your targets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Use the ones your doctor or nurse gave you; these start at the usual ones.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(sys, { sys = it }, "Top under", Modifier.weight(1f), suffix = "mmHg", decimals = false)
                    NumberField(dia, { dia = it }, "Bottom under", Modifier.weight(1f), suffix = "mmHg", decimals = false)
                }
                SegmentedChoice(GlucoseUnit.entries, unit, { it.label }, ::switch)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(low, { low = it }, "Glucose from", Modifier.weight(1f), suffix = unit.label, decimals = unit == GlucoseUnit.MMOL_L)
                    NumberField(high, { high = it }, "to", Modifier.weight(1f), suffix = unit.label, decimals = unit == GlucoseUnit.MMOL_L)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                onSave(VitalsTargets(sys.toInt(), dia.toInt(), Math.round(lowMmol!! * 10) / 10.0, Math.round(highMmol!! * 10) / 10.0, unit))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
