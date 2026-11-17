package com.health.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.DayFeed
import com.health.api.DaySummary
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.Meal
import com.health.api.MedForm
import com.health.api.Module
import com.health.api.toInput
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.feed
import com.health.app.net.mealiePlan
import com.health.app.net.mealieStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /** The day's scores, insights and numbers. */
    val summary: DaySummary? = null,
    /** Right now and Your day (GET /api/feed). */
    val feed: DayFeed? = null,
    /** The feed couldn't be had: Right now and Your day say so quietly, with a way to try again. */
    val feedError: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Tonight's meal as planned in Mealie and not logged yet (today, the person's own, with Mealie connected): the + sheet's suggestion. */
    val plan: String? = null,
)

class TodayViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LocalDate.now(c.zone()).let { TodayState(it, it) })
    val state: StateFlow<TodayState> = _state.asStateFlow()
    private var job: Job? = null

    /** The day tonight's plan was last looked up for: once a day is enough. */
    private var planFor: LocalDate? = null

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
        viewModelScope.launch { c.profile.drop(1).collect { load(quiet = true) } }
    }

    /** The day and its feed side by side; the feed failing never takes the day with it. */
    fun load(quiet: Boolean = false) {
        val today = LocalDate.now(c.zone())
        val date = _state.value.date.coerceAtMost(today)
        job?.cancel()
        _state.update { it.copy(today = today, date = date, loading = !quiet || it.summary == null, error = null) }
        job = viewModelScope.launch {
            val feed = async { attempt { c.ui.feed(date.toString()) } }
            try {
                val summary = c.ui.day(date.toString())
                val got = feed.await()
                _state.update {
                    if (it.date != date) it
                    else it.copy(
                        summary = summary, loading = false, refreshing = false,
                        feed = got.getOrNull() ?: it.feed?.takeIf { f -> f.date == date.toString() },
                        feedError = got.exceptionOrNull()?.let { e -> e.message?.takeIf { m -> m.startsWith("Your server needs updating") } ?: "Couldn't load your day" },
                    )
                }
                if (date == today && planFor != today) loadPlan(today)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                feed.cancel()
                _state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load the day") }
            }
        }
    }

    /** Tonight's dinner as planned in Mealie, not logged yet: the person's own, never while viewing a share. */
    private suspend fun loadPlan(today: LocalDate) {
        if (c.viewing.value != null || Module.NUTRITION !in c.modules()) return
        planFor = today
        val status = attempt { c.api.mealieStatus() }.getOrNull()
        val plan = if (status?.connected == true && !status.paused) {
            attempt { c.api.mealiePlan(today.toString()) }.getOrNull()?.entries?.firstOrNull { it.meal == Meal.DINNER && !it.logged }?.title
        } else null
        _state.update { if (it.date == today) it.copy(plan = plan) else it }
    }

    fun go(date: LocalDate) {
        _state.update { it.copy(date = date, summary = if (it.date == date) it.summary else null, feed = if (it.date == date) it.feed else null, feedError = null) }
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

    private fun act(message: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "That didn't work")
            }
        }
    }

    /**
     * Logs a food idea from the protein insight: each of its items today, at its meal (as the Food
     * tab logs a past entry again), then offers Undo. Half-logged, it takes back what it added. In
     * the app's scope, so leaving Today can't stop it halfway.
     */
    fun addIdea(idea: FoodSuggestion) {
        c.scope.launch {
            val date = _state.value.today.toString()
            val added = mutableListOf<FoodEntry>()
            try {
                idea.items.forEach { added += c.ui.addFood(it.toInput(date, idea.meal)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                added.forEach { a -> a.id?.let { id -> attempt { c.ui.deleteFood(id) } } }
                c.tell(e.message ?: "Couldn't add that")
                return@launch
            }
            c.dataChanged()
            c.tell("Added to ${InsightLinks.mealWord(idea.meal)}: ${idea.name}", "Undo") {
                c.scope.launch {
                    attempt { added.forEach { a -> a.id?.let { id -> c.ui.deleteFood(id) } } }
                        .onSuccess { c.tell("Taken back off") }
                        .onFailure { c.tell(it.message ?: "Couldn't undo that") }
                    c.dataChanged()
                }
            }
        }
    }

    /**
     * Takes the dose of [medicationId] due at [scheduledFor] (Right now's Take); an injection or
     * patch goes to the site next in rotation, as the day's doses say it.
     */
    fun takeDose(medicationId: String, scheduledFor: Long?, name: String) = act("$name taken") {
        val due = _state.value.summary?.modules?.meds?.due?.firstOrNull { it.medicationId == medicationId && (scheduledFor == null || it.scheduledFor == scheduledFor) }
        val rotates = due?.form == MedForm.INJECTION || due?.form == MedForm.PATCH
        c.ui.logDose(DoseLog(medicationId, System.currentTimeMillis(), DoseStatus.TAKEN, scheduledFor = scheduledFor ?: due?.scheduledFor, site = due?.nextSite.takeIf { rotates }))
    }
}
