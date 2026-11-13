package com.health.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.CheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.MedForm
import com.health.api.MoodEntry
import com.health.api.DaySummary
import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.toInput
import com.health.api.MeasurementInput
import com.health.api.Metric
import com.health.api.SleepSession
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.app.Container
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
    /** The meals with food logged, loaded only when amounts of food don't show (the Food card names them instead). */
    val meals: List<com.health.api.Meal>? = null,
)

class TodayViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LocalDate.now(c.zone()).let { TodayState(it, it) })
    val state: StateFlow<TodayState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
        viewModelScope.launch { c.profile.drop(1).collect { load(quiet = true) } }
        // Whether amounts of food show decides whether the meals are loaded too.
        viewModelScope.launch { c.fields.map { it?.foodNumbers }.distinctUntilChanged().drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val today = LocalDate.now(c.zone())
        val date = _state.value.date.coerceAtMost(today)
        job?.cancel()
        _state.update { it.copy(today = today, date = date, loading = !quiet || it.summary == null, error = null) }
        job = viewModelScope.launch {
            try {
                val summary = c.ui.day(date.toString())
                // Without amounts of food, the Food card says which meals are logged.
                val meals = if (c.fields.value?.foodNumbers == false) {
                    runCatching { c.ui.foodDay(date.toString()).entries.map { it.meal }.distinct() }.getOrNull()
                } else null
                _state.update { if (it.date == date) it.copy(summary = summary, meals = meals, loading = false, refreshing = false) else it }
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

    fun addWater(ml: Int) = act("Added ${ml} ml of water") { c.ui.addWater(WaterEntry(_state.value.date.toString(), ml)) }

    fun logWeight(kg: Double) = act("Weight saved") {
        val now = System.currentTimeMillis()
        val date = _state.value.date
        val at = if (date == _state.value.today) now else date.atTime(8, 0).atZone(c.zone()).toInstant().toEpochMilli()
        c.ui.addMeasurement(MeasurementInput(Metric.WEIGHT, kg, at))
    }

    fun logWorkout(workout: Workout) = act("Workout saved") { c.ui.addWorkout(workout) }

    fun logSleep(session: SleepSession) = act("Sleep saved") { c.ui.addSleep(session) }

    fun saveCheckIn(checkIn: CheckIn) = act("Thanks for checking in") { c.ui.saveCheckIn(checkIn) }

    /** A face tapped on Today: the mood, now. */
    fun quickMood(mood: Int) = act("Mood saved") { c.ui.addMood(MoodEntry(System.currentTimeMillis(), mood)) }

    fun logContinence(kind: ContinenceKind) = act("${kind.label} noted") {
        c.ui.addContinence(ContinenceEntry(System.currentTimeMillis(), kind))
    }

    /**
     * Logs a food idea from the protein insight: each of its items today, at its meal (as the Food
     * tab logs a past entry again), then offers Undo. Half-logged, it takes back what it added.
     */
    fun addIdea(idea: FoodSuggestion) {
        viewModelScope.launch {
            val date = _state.value.today.toString()
            val added = mutableListOf<FoodEntry>()
            try {
                idea.items.forEach { added += c.ui.addFood(it.toInput(date, idea.meal)) }
            } catch (e: Exception) {
                added.forEach { a -> a.id?.let { id -> runCatching { c.ui.deleteFood(id) } } }
                c.tell(e.message ?: "Couldn't add that")
                return@launch
            }
            c.dataChanged()
            c.tell("Added to ${InsightLinks.mealWord(idea.meal)}: ${idea.name}", "Undo") {
                c.scope.launch {
                    runCatching { added.forEach { a -> a.id?.let { id -> c.ui.deleteFood(id) } } }
                        .onSuccess { c.tell("Taken back off") }
                        .onFailure { c.tell(it.message ?: "Couldn't undo that") }
                    c.dataChanged()
                }
            }
        }
    }

    /** Takes a scheduled dose; an injection or patch goes to the chosen site, or the one next in rotation. */
    fun takeDose(due: DueDose, site: String?) = act("${due.name} taken") {
        val rotates = due.form == MedForm.INJECTION || due.form == MedForm.PATCH
        c.ui.logDose(
            DoseLog(due.medicationId, System.currentTimeMillis(), DoseStatus.TAKEN, scheduledFor = due.scheduledFor, site = site ?: due.nextSite.takeIf { rotates }),
        )
    }

    /** Marks a scheduled dose as skipped (from Up next). */
    fun skipDose(due: DueDose) = act("${due.name} skipped") {
        c.ui.logDose(DoseLog(due.medicationId, System.currentTimeMillis(), DoseStatus.SKIPPED, scheduledFor = due.scheduledFor))
    }
}
