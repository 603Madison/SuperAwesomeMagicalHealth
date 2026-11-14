package com.health.app.ui.modules.neuro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.CommunicationCard
import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.FocusSession
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.NeuroSummary
import com.health.api.OverloadEvent
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.Wording
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.breakdownStatus
import com.health.app.net.communicationCards
import com.health.app.net.energyDays
import com.health.app.net.focusSessions
import com.health.app.net.neuroCheckIns
import com.health.app.net.neuroProfile
import com.health.app.net.neuroSummary
import com.health.app.net.overloads
import com.health.app.net.routineRuns
import com.health.app.net.routines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Autism & ADHD (Module.NEURO, shown in Mind): what the screens show, and the plain maths behind
 * the energy budget and time estimates. Neurodiversity-affirming throughout: experiences, not
 * symptoms; overwhelm, not behaviour; stims and special interests are things that help.
 */

data class NeuroState(
    val profile: NeuroProfile = NeuroProfile(),
    val summary: NeuroSummary? = null,
    val events: List<OverloadEvent> = emptyList(),
    val energy: List<EnergyDay> = emptyList(),
    val checkIns: List<NeuroCheckIn> = emptyList(),
    val focus: List<FocusSession> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val runs: List<RoutineRun> = emptyList(),
    val cards: List<CommunicationCard> = emptyList(),
    /** Whether "break it into steps" can be offered: the assistant is on and the person said yes. */
    val canBreakDown: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun energyOn(date: LocalDate): EnergyDay? = energy.firstOrNull { it.date == date.toString() }

    fun runOf(routineId: String?, date: LocalDate): RoutineRun? = runs.firstOrNull { it.routineId == routineId && it.date == date.toString() }

    /** Whether the cards are still the server's starting set. */
    val defaultCards: Boolean get() = cards.isNotEmpty() && cards.all { it.id?.startsWith("default-") == true }
}

/** Everything the Autism & ADHD pages show, and saving what's logged there. */
class NeuroViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(NeuroState())
    val state: StateFlow<NeuroState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null) }
        viewModelScope.launch {
            try {
                val api = c.ui
                val profile = async { api.neuroProfile() }
                val summary = async { api.neuroSummary(30) }
                val events = async { api.overloads(30) }
                val energy = async { api.energyDays(14) }
                val checkIns = async { api.neuroCheckIns(14) }
                val focus = async { api.focusSessions(60) }
                val routines = async { api.routines() }
                val runs = async { api.routineRuns(7) }
                val cards = async { api.communicationCards() }
                // Only the person's own: someone viewing a share never gets the assistant.
                val ai = async {
                    if (c.viewing.value != null) false
                    else attempt { api.breakdownStatus().let { s -> s.available && s.consent?.acknowledged == true } }.getOrDefault(false)
                }
                _state.update {
                    it.copy(
                        profile = profile.await(), summary = summary.await(), events = events.await(), energy = energy.await(),
                        checkIns = checkIns.await(), focus = focus.await(), routines = routines.await(), runs = runs.await(),
                        cards = cards.await(), canBreakDown = ai.await(), loading = false,
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load Autism & ADHD") }
            }
        }
    }

    /**
     * Runs [block] against the server, then tells the person [message] and reloads everything. On
     * the app's scope, so it finishes even though the page closes straight after.
     */
    fun act(message: String?, block: suspend (com.health.app.net.ServerApi) -> Unit) {
        c.scope.launch {
            try {
                block(c.ui)
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }
}

/** The energy budget: spoons (or any unit) to start with, what used them, and what gave some back. */
object EnergyMath {
    /** Spent on things that used energy. */
    fun used(day: EnergyDay): Int = day.spends.filter { it.amount > 0 }.sumOf { it.amount }

    /** Given back by rest, stims, interests… (spends below zero). */
    fun gained(day: EnergyDay): Int = -day.spends.filter { it.amount < 0 }.sumOf { it.amount }

    /** What's left, or null without a starting budget. Can go below zero: that's worth knowing, not a failure. */
    fun left(day: EnergyDay): Int? = day.startWith?.let { it - day.spends.sumOf { s -> s.amount } }

    /** How full the budget is, 0–1, for a bar. */
    fun fraction(day: EnergyDay): Float? {
        val start = day.startWith?.takeIf { it > 0 } ?: return null
        return ((left(day) ?: return null).toFloat() / start).coerceIn(0f, 1f)
    }

    /** Running low: a sixth or less left, as the server says on Today. */
    fun low(day: EnergyDay): Boolean {
        val start = day.startWith ?: return false
        val left = left(day) ?: return false
        return left <= maxOf(1, start / 6)
    }

    fun spend(day: EnergyDay, what: String, amount: Int, at: Long?): EnergyDay = day.copy(spends = day.spends + EnergySpend(what.trim(), amount, at))

    /** "3 of 12 left", "2 over". */
    fun leftText(day: EnergyDay): String? {
        val left = left(day) ?: return null
        return if (left >= 0) "$left of ${day.startWith} left" else "${-left} over"
    }
}

/** How long things really take against the guess. */
object FocusMath {
    /** Minutes a finished session lasted, or null while it runs. */
    fun minutes(s: FocusSession): Int? = s.end?.let { ((it - s.start) / 60_000).toInt() }

    /** How long it took against the guess (1.5 = half as long again), or null without both. */
    fun ratio(s: FocusSession): Double? {
        val took = s.end?.let { (it - s.start) / 60_000.0 } ?: return null
        val guess = s.estimateMinutes?.takeIf { it > 0 } ?: return null
        return took / guess
    }

    /** The average of [sessions]' ratios to one decimal, once there are three to go on (as the server does). */
    fun averageRatio(sessions: List<FocusSession>): Double? =
        sessions.mapNotNull(::ratio).takeIf { it.size >= 3 }?.average()?.let { (it * 10).roundToInt() / 10.0 }

    /** A guess allowing for how things usually go: 20 minutes at 1.5× is 30. */
    fun allowFor(guessMinutes: Int, ratio: Double?): Int? {
        val r = ratio?.takeIf { abs(it - 1.0) >= 0.15 } ?: return null
        return (guessMinutes * r / 5.0).roundToInt().coerceAtLeast(1) * 5
    }

    /** "Things took about 1.5× your guess", kindly. */
    fun describe(ratio: Double): String = when {
        ratio >= 1.15 -> "Things took about ${trim(ratio)}× as long as you guessed. Very common, and worth planning for."
        ratio <= 0.85 -> "Things took about ${trim(ratio)}× your guess: quicker than you thought."
        else -> "Things took about as long as you guessed."
    }

    /** "Guessed 20 min · took 32 min". */
    fun line(s: FocusSession): String = listOfNotNull(
        s.estimateMinutes?.let { "guessed ${mins(it)}" },
        minutes(s)?.let { "took ${mins(it)}" },
    ).joinToString(" · ").replaceFirstChar { it.uppercase() }

    fun mins(m: Int): String = if (m < 60) "$m min" else "${m / 60} h" + (m % 60).let { if (it > 0) " $it min" else "" }

    private fun trim(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
}

/** The person's words for themselves, as they prefer them. */
object NeuroWords {
    /** First person, for "About me": "I'm autistic and ADHD." or "I'm a person with autism and ADHD.", else their own words. */
    fun describe(p: NeuroProfile): String? {
        if (p.identities.isEmpty()) return null
        val adjectives = mutableListOf<String>()
        val nouns = mutableListOf<String>()
        for (id in p.identities.map { it.trim().lowercase(Locale.ROOT) }) when {
            id.startsWith("audhd") -> { adjectives += listOf("autistic", "ADHD"); nouns += listOf("autism", "ADHD") }
            id.startsWith("autis") -> { adjectives += "autistic"; nouns += "autism" }
            id == "adhd" || id == "add" -> { adjectives += "ADHD"; nouns += "ADHD" }
            id.startsWith("dyspra") -> { adjectives += "dyspraxic"; nouns += "dyspraxia" }
            id.startsWith("dyslex") -> { adjectives += "dyslexic"; nouns += "dyslexia" }
            else -> return "In my own words: ${p.identities.joinToString(", ")}."
        }
        fun join(words: List<String>) = words.distinct().let { w -> if (w.size <= 1) w.joinToString() else w.dropLast(1).joinToString(", ") + " and " + w.last() }
        return when (p.wording) {
            Wording.IDENTITY_FIRST -> "I'm ${join(adjectives)}."
            Wording.PERSON_FIRST -> "I'm a person with ${join(nouns)}."
        }
    }

    /** A sense's −2…+2 in words. */
    fun response(value: Int): String = when {
        value <= -2 -> "Avoid strongly"
        value == -1 -> "Tend to avoid"
        value == 0 -> "Neutral"
        value == 1 -> "Tend to seek"
        else -> "Seek strongly"
    }
}

/** Suggestions offered first in the pickers; people add their own. */
object NeuroSuggestions {
    val identities = listOf("Autistic", "ADHD", "AuDHD", "Dyspraxic", "Dyslexic", "Neurodivergent")
    val triggers = listOf("change of plans", "too many demands", "social", "noise", "bright light", "crowds", "hunger", "tired", "waiting", "uncertainty", "being rushed")
    val warningSigns = listOf("going quiet", "irritable", "stimming more", "can't find words", "covering ears", "pacing", "heart racing", "tearful")
    val whatHelped = listOf("quiet space", "headphones", "dark room", "weighted blanket", "stimming", "time alone", "a walk", "special interest", "deep pressure", "water or food")
    val communication = listOf("text over calls", "direct language", "time to process", "written instructions", "advance notice", "no small talk")
    val accommodations = listOf("noise-cancelling headphones", "flexible start times", "written agendas", "quiet workspace", "breaks when needed", "cameras off on calls")
    val gives = listOf("rest" to -1, "stimming" to -1, "special interest" to -2, "quiet time" to -2, "a nap" to -2)
    val uses = listOf("work" to 3, "social plans" to 2, "phone call" to 1, "shower" to 1, "commute" to 2, "chores" to 1, "appointment" to 2)
}
