package com.health.app.ui.modules.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Fields
import com.health.api.MeasurementInput
import com.health.api.Module
import com.health.api.Metric
import com.health.api.Point
import com.health.api.Sample
import com.health.api.Units
import com.health.app.Container
import com.health.app.net.series
import com.health.app.ui.Format
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.log.NumberField
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Body measurements: the tape-measure ones, kept in cm on the server and shown in the person's units. */
val BODY_MEASUREMENTS = listOf(Metric.WAIST, Metric.HIPS, Metric.CHEST, Metric.BUST, Metric.UNDERBUST, Metric.SHOULDERS, Metric.NECK, Metric.UPPER_ARM, Metric.THIGH, Metric.CALF)

object MeasurementFormat {
    fun unit(units: Units) = if (units == Units.IMPERIAL) "in" else "cm"

    fun value(cm: Double, units: Units): Double = if (units == Units.IMPERIAL) cm / Format.CM_PER_INCH else cm

    fun toCm(value: Double, units: Units): Double = if (units == Units.IMPERIAL) value * Format.CM_PER_INCH else value

    /** "82.5 cm", "32.5 in". */
    fun length(cm: Double, units: Units): String = "${Format.compact(value(cm, units))} ${unit(units)}"

    /** "down 2 cm", "up 0.5 in", or null for under half a unit's change. */
    fun change(fromCm: Double, toCm: Double, units: Units): String? {
        val delta = value(toCm, units) - value(fromCm, units)
        if (abs(delta) < 0.5) return null
        return "${if (delta > 0) "up" else "down"} ${Format.compact(abs(delta))} ${unit(units)}"
    }

    /** What the form sends: one reading per value given, all at the same moment so they read as one set. */
    fun inputs(values: Map<Metric, String>, units: Units, at: Long): List<MeasurementInput> = values.mapNotNull { (metric, text) ->
        Format.parse(text)?.takeIf { it > 0 && it < 1000 }?.let { MeasurementInput(metric, Math.round(toCm(it, units) * 10) / 10.0, at) }
    }
}

data class MeasurementsState(
    val latest: Map<Metric, Sample> = emptyMap(),
    val series: Map<Metric, List<Point>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)

class MeasurementsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(MeasurementsState())
    val state: StateFlow<MeasurementsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val api = c.ui
                val latest = BODY_MEASUREMENTS.map { m -> async { m to api.measurements(m, 3650).maxByOrNull { it.start } } }.awaitAll()
                    .mapNotNull { (m, s) -> s?.let { m to it } }.toMap()
                val now = System.currentTimeMillis()
                val from = now - 365L * 86_400_000
                val series = latest.filterValues { it.start >= from }.keys.map { m -> async { m to runCatching { api.series(m, from, now) }.getOrDefault(emptyList()) } }
                    .awaitAll().toMap()
                _state.update { it.copy(latest = latest, series = series, loading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load measurements") }
            }
        }
    }

    fun save(inputs: List<MeasurementInput>) {
        c.scope.launch {
            try {
                inputs.forEach { c.ui.addMeasurement(it) }
                c.tell(if (inputs.size == 1) "Measurement saved" else "${inputs.size} measurements saved")
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }
}

/** Whether body measurements are offered: Body on, and a field that brings them (Body's own, or transition's). */
@Composable
fun measurementsShown(): Boolean {
    val fields = shownFields()
    val modules = com.health.app.ui.care.shownModules()
    return Module.BODY in modules && (Fields.BODY_MEASUREMENTS in fields || (Module.TRANSITION in modules && Fields.TRANSITION_MEASUREMENTS in fields))
}

@Composable
fun MeasurementsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MeasurementsViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    val viewProfile by c.viewProfile.collectAsState()
    val viewing by c.viewing.collectAsState()
    val units = (if (viewing != null) viewProfile?.units else profile?.units) ?: Units.METRIC
    MeasurementsScreen(state, units, c.zone(), onBack, onRetry = vm::load) { values -> vm.save(MeasurementFormat.inputs(values, units, System.currentTimeMillis())) }
}

@Composable
fun MeasurementsScreen(state: MeasurementsState, units: Units, zone: ZoneId, onBack: () -> Unit, onRetry: () -> Unit = {}, onSave: (Map<Metric, String>) -> Unit) {
    val readOnly = LocalReadOnly.current
    val typed = remember { mutableStateMapOf<Metric, String>() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Body measurements")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && state.latest.isEmpty() -> Loading()
                state.error != null && state.latest.isEmpty() -> ErrorCard(state.error, actionLabel = "Try again", onAction = onRetry)
                state.latest.isNotEmpty() -> InfoCard("Latest", icon = Icons.Rounded.Straighten) {
                    BODY_MEASUREMENTS.forEach { m ->
                        val s = state.latest[m] ?: return@forEach
                        val points = state.series[m].orEmpty()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    CareFormat.date(Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (points.size >= 2) {
                                LineChart(
                                    points.map { MeasurementFormat.value(it.v, units) }, height = 36.dp, dots = false,
                                    color = com.health.app.ui.theme.AppTheme.data.fat,
                                    modifier = Modifier.width(80.dp).semantics { contentDescription = "${m.label} over the last year" },
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                                Text(MeasurementFormat.length(s.value, units), style = MaterialTheme.typography.titleMedium)
                                points.takeIf { it.size >= 2 }?.let { p -> MeasurementFormat.change(p.first().v, p.last().v, units) }?.let {
                                    Text("$it in a year", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                else -> Hint("Nothing measured yet. Measurements show here, and with your weight in reports and exports.", Modifier.padding(horizontal = 4.dp))
            }
            if (!readOnly) {
                InfoCard("Measure now") {
                    Hint("Fill in any you like. Measure at the same spot each time, relaxed, with the tape snug but not tight.")
                    BODY_MEASUREMENTS.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            pair.forEach { m ->
                                NumberField(typed[m].orEmpty(), { typed[m] = it.take(6) }, m.label, Modifier.weight(1f), suffix = MeasurementFormat.unit(units))
                            }
                        }
                    }
                    val ready = MeasurementFormat.inputs(typed, units, 0).isNotEmpty()
                    Button(enabled = ready, onClick = { onSave(typed.toMap()); typed.clear() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Save") }
                }
            }
        }
    }
}
