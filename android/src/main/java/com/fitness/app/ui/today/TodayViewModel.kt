package com.fitness.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fitness.api.CheckIn
import com.fitness.api.DaySummary
import com.fitness.api.MeasurementInput
import com.fitness.api.Metric
import com.fitness.api.SleepSession
import com.fitness.api.WaterEntry
import com.fitness.api.Workout
import com.fitness.app.Container
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayState(
    val date: LocalDate,
    val today: LocalDate,
    val summary: DaySummary? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class TodayViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LocalDate.now(c.zone()).let { TodayState(it, it) })
    val state: StateFlow<TodayState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
        viewModelScope.launch { c.profile.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val today = LocalDate.now(c.zone())
        val date = _state.value.date.coerceAtMost(today)
        job?.cancel()
        _state.update { it.copy(today = today, date = date, loading = !quiet || it.summary == null, error = null) }
        job = viewModelScope.launch {
            try {
                val summary = c.api.day(date.toString())
                _state.update { if (it.date == date) it.copy(summary = summary, loading = false, refreshing = false) else it }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load the day") }
            }
        }
    }

    fun go(date: LocalDate) {
        _state.update { it.copy(date = date, summary = if (it.date == date) it.summary else null) }
        load()
    }

    /** Pull to refresh: brings in the devices' latest, then reloads. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            runCatching { c.sync.sync() }
            load(quiet = true)
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't work")
            }
        }
    }

    fun addWater(ml: Int) = act("Added ${ml} ml of water") { c.api.addWater(WaterEntry(_state.value.date.toString(), ml)) }

    fun logWeight(kg: Double) = act("Weight saved") {
        val now = System.currentTimeMillis()
        val date = _state.value.date
        val at = if (date == _state.value.today) now else date.atTime(8, 0).atZone(c.zone()).toInstant().toEpochMilli()
        c.api.addMeasurement(MeasurementInput(Metric.WEIGHT, kg, at))
    }

    fun logWorkout(workout: Workout) = act("Workout saved") { c.api.addWorkout(workout) }

    fun logSleep(session: SleepSession) = act("Sleep saved") { c.api.addSleep(session) }

    fun saveCheckIn(checkIn: CheckIn) = act("Thanks for checking in") { c.api.saveCheckIn(checkIn) }
}
