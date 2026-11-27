package com.health.app.ui.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.Device
import com.health.api.MeasurementInput
import com.health.api.Metric
import com.health.api.RecentReadings
import com.health.api.Sample
import com.health.api.Trends
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.ServerException
import com.health.app.net.compareDevices
import com.health.app.net.recent
import com.health.app.ui.devices.Compare
import com.health.app.ui.recent.Recent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BodyState(
    val span: BodySpan = BodySpan.DAY,
    /** The device chosen at 24 hours, or null for all of them. */
    val device: String? = null,
    /** The last 24 hours, as recorded. */
    val readings: RecentReadings? = null,
    /** Every device on the server: the chosen one's name and last sync, and whether Compare devices is offered. */
    val known: List<Device> = emptyList(),
    /** The last 30 days, for "your usual" at 24 hours (and today's resting heart rate). */
    val usual: Trends? = null,
    /** The week, month or year. */
    val trends: Trends? = null,
    val weights: List<Sample> = emptyList(),
    /** Today's readings more than one device measured, for "From 2 devices" under the charts. */
    val combined: List<CombinedValue> = emptyList(),
    val combinedSleep: CombinedSleep? = null,
    /** Compare devices' one line, once known. */
    val compare: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The empty 24 hours' Sync now is under way. */
    val syncing: Boolean = false,
    val error: String? = null,
    /** The chosen device isn't on the server any more. */
    val gone: Boolean = false,
)

/**
 * Body's data: the last 24 hours (the person's own only: never asked for while viewing a share,
 * which starts at the week) or the trends over the span chosen. [device] starts it on one device.
 */
class BodyViewModel(private val c: Container, device: String? = null, span: BodySpan? = null) : ViewModel() {
    private val _state = MutableStateFlow(
        BodyState(span = span ?: if (c.viewing.value != null) BodySpan.WEEK else BodySpan.DAY, device = device?.takeIf { it.isNotBlank() }),
    )
    val state: StateFlow<BodyState> = _state.asStateFlow()
    private var job: Job? = null
    private var extras = false

    init {
        load()
        // After a sync (here, from Devices or in the background) brings something in.
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val s = _state.value
        job?.cancel()
        if (s.span == BodySpan.DAY && c.viewing.value != null) return _state.update { it.copy(loading = false, refreshing = false) }
        val shown = if (s.span == BodySpan.DAY) s.readings != null else s.trends?.days?.size == s.span.days
        _state.update { it.copy(loading = !quiet || !shown, error = null) }
        job = viewModelScope.launch {
            try {
                if (s.span == BodySpan.DAY) loadDay(s.device) else loadTrends(s.span)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val gone = s.span == BodySpan.DAY && s.device != null && e is ServerException && e.code == 404 && e.fromServer
                _state.update {
                    if (it.span != s.span || it.device != s.device) it
                    else it.copy(loading = false, refreshing = false, gone = gone, error = if (gone) null else e.message ?: "Couldn't load your readings")
                }
            }
        }
    }

    private suspend fun loadDay(device: String?) {
        val readings = c.api.recent(Recent.HOURS, device)
        _state.update { if (it.span == BodySpan.DAY && it.device == device) it.copy(readings = readings, loading = false, refreshing = false, gone = false) else it }
        // What the cards compare with, and the rest of the page, beside it (never holding it up).
        val known = attempt { c.api.devices() }.getOrDefault(_state.value.known)
        val usual = attempt { c.ui.trends(30) }.getOrNull() ?: _state.value.usual
        _state.update { it.copy(known = known, usual = usual) }
        if (!extras) {
            extras = true
            if (Compare.offered(known)) {
                val line = attempt { BodyText.compareLine(c.api.compareDevices(), c.settings.value.units) }.getOrNull()
                _state.update { it.copy(compare = line) }
            }
        }
    }

    private suspend fun loadTrends(span: BodySpan) {
        val trends = c.ui.trends(span.days)
        // Oldest first (the server sends newest first): the chart reads left to right and the latest is the last.
        val weights = attempt { c.ui.measurements(Metric.WEIGHT, maxOf(span.days, 90)) }.getOrDefault(emptyList()).sortedBy { it.start }
        _state.update { if (it.span == span) it.copy(trends = trends, weights = weights, loading = false, refreshing = false) else it }
        // Combined devices: today's, for the charts' "From 2 devices" (ui/components/Sources.kt).
        attempt { c.ui.day(LocalDate.now(c.zone()).toString()) }.getOrNull()?.let { day ->
            _state.update { it.copy(combined = day.combined, combinedSleep = day.sleepSummary?.combined) }
        }
    }

    /** Shows [span]; the week, month and year each load their own days. */
    fun span(span: BodySpan) {
        if (span == _state.value.span) return
        _state.update { it.copy(span = span, error = null, trends = if (span == BodySpan.DAY) it.trends else it.trends?.takeIf { t -> t.days.size == span.days }) }
        load()
    }

    /** Shows [device] alone at 24 hours, or (null) all of them. */
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

    /** The empty 24 hours' Sync now, as Devices' (it says how it went); the page reloads when it's done. */
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

    fun logWeight(kg: Double) = viewModelScope.launch {
        attempt { c.ui.addMeasurement(MeasurementInput(Metric.WEIGHT, kg, System.currentTimeMillis())) }
            .onSuccess { c.tell("Weight saved"); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun deleteWeight(id: Long) = viewModelScope.launch {
        attempt { c.ui.deleteMeasurement(id) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't delete that") }
    }
}
