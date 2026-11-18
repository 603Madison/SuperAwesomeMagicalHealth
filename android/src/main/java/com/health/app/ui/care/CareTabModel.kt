package com.health.app.ui.care

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Fields
import com.health.app.Container
import com.health.app.attempt
import com.health.app.data.DeviceChoices
import com.health.app.net.ServerApi
import com.health.app.net.appointments
import com.health.app.net.continenceWearing
import com.health.app.net.hormones
import com.health.app.net.mealiePlan
import com.health.app.net.mealieStatus
import com.health.app.net.photoAlbums
import com.health.app.net.routines
import com.health.app.net.supplyStatus
import com.health.app.net.transitionOverview
import com.health.app.net.voiceProgress
import com.health.app.ui.modules.meds.Doses
import com.health.app.ui.modules.photos.PhotoUnlock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Care tab's data: what's loaded ([data]), the parts still coming ([pending]) and those that
 * couldn't load ([failed]). Each part comes on its own, so a slow or failing one leaves the rest.
 */
data class CareState(
    val today: LocalDate,
    val data: CareData = CareData(),
    val pending: Set<CarePart> = emptySet(),
    val failed: Set<CarePart> = emptySet(),
) {
    /** Nothing at all to show yet: the first load, before the day has come (or failed). */
    val starting: Boolean get() = data.day == null && CarePart.DAY in pending

    /** Something the person would notice is missing couldn't load. */
    val problem: Boolean get() = (failed - CarePart.QUIET).isNotEmpty()
}

/**
 * Loads what the cards say, all at once and each on its own, from the same endpoints the feature
 * pages use (the shared person's, read-only, while viewing a share; Mealie only ever the person's
 * own). Reloads when data changes, the features or fields change, or the photos lock or unlock.
 */
class CareViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(CareState(LocalDate.now(c.zone())))
    val state: StateFlow<CareState> = _state.asStateFlow()
    private var job: Job? = null

    private val choices = DeviceChoices.of(c.context)

    /** Progress photos are locked on this phone (the lock on, and not unlocked): their number isn't even asked for. */
    private var photosLocked = choices.photoLock.value && !PhotoUnlock.unlocked.value

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load() } }
        viewModelScope.launch { c.profile.map { it?.modules }.distinctUntilChanged().drop(1).collect { load() } }
        viewModelScope.launch { c.fields.map { it?.effective to it?.foodNumbers }.distinctUntilChanged().drop(1).collect { load() } }
        viewModelScope.launch {
            combine(choices.photoLock, PhotoUnlock.unlocked) { on, open -> on && !open }.distinctUntilChanged().drop(1).collect(::photosLocked)
        }
    }

    /** The photos lock changed: locked, their number is forgotten; unlocked, it comes. */
    private fun photosLocked(locked: Boolean) {
        photosLocked = locked
        if (locked) {
            _state.update { it.copy(data = it.data.copy(photos = null), pending = it.pending - CarePart.PHOTOS) }
            return
        }
        val today = _state.value.today
        if (CarePart.PHOTOS !in CareParts.needed(context(today))) return
        _state.update { it.copy(pending = it.pending + CarePart.PHOTOS) }
        val api = c.ui
        viewModelScope.launch { loadPart(CarePart.PHOTOS, api, today) }
    }

    /** What decides the parts to load: what's on (the shared person's while viewing). No words are made from it. */
    private fun context(today: LocalDate): CareContext = CareContext(
        now = System.currentTimeMillis(),
        zone = c.zone(),
        today = today,
        modules = c.shownModules(),
        fields = c.fields.value?.effective ?: Fields.DEFAULTS,
        foodNumbers = c.fields.value?.foodNumbers ?: true,
        viewing = c.viewing.value != null,
        photosLocked = photosLocked,
        clock = { "" },
    )

    /** Loads every part the cards need, each on its own; what's there stays until its fresh copy comes. */
    fun load() {
        val today = LocalDate.now(c.zone())
        val parts = CareParts.needed(context(today))
        val api = c.ui
        job?.cancel()
        _state.update { s ->
            // A new day: yesterday's numbers aren't today's.
            val data = if (s.today == today) s.data else CareData()
            s.copy(today = today, data = data, pending = parts, failed = s.failed intersect parts)
        }
        job = viewModelScope.launch {
            parts.forEach { part -> launch { loadPart(part, api, today) } }
        }
    }

    private suspend fun loadPart(part: CarePart, api: ServerApi, today: LocalDate) {
        val date = today.toString()
        val result: Result<(CareData) -> CareData> = attempt {
            when (part) {
                CarePart.DAY -> api.day(date).let { v -> { d: CareData -> d.copy(day = v) } }
                CarePart.MEDS -> api.medications().let { v -> { d: CareData -> d.copy(meds = v) } }
                CarePart.LABS -> api.labs(365).let { v -> { d: CareData -> d.copy(labs = v) } }
                CarePart.HORMONES -> api.hormones().let { v -> { d: CareData -> d.copy(hormones = v) } }
                CarePart.WEARING -> api.continenceWearing().let { v -> { d: CareData -> d.copy(wearing = v) } }
                CarePart.CONTINENCE -> api.continenceSummary(14).let { v -> { d: CareData -> d.copy(continence = v) } }
                CarePart.SUPPLIES -> api.supplyStatus().let { v -> { d: CareData -> d.copy(supplies = v) } }
                CarePart.APPOINTMENTS -> api.appointments(days = 365, upcoming = true).let { v -> { d: CareData -> d.copy(appointments = v) } }
                CarePart.MEALS -> api.foodDay(date).entries.map { it.meal }.distinct().let { v -> { d: CareData -> d.copy(meals = v) } }
                CarePart.PLAN -> mealiePlan(date).let { v -> { d: CareData -> d.copy(plan = v) } }
                CarePart.TRANSITION -> api.transitionOverview().let { v -> { d: CareData -> d.copy(transition = v) } }
                CarePart.VOICE -> api.voiceProgress().let { v -> { d: CareData -> d.copy(voice = v) } }
                CarePart.PHOTOS -> api.photoAlbums().sumOf { it.count }.let { v -> { d: CareData -> d.copy(photos = v.takeUnless { photosLocked }) } }
                CarePart.ROUTINES -> api.routines().let { v -> { d: CareData -> d.copy(routines = v) } }
            }
        }
        _state.update { s ->
            if (s.today != today) return@update s
            result.fold(
                onSuccess = { put -> s.copy(data = put(s.data), pending = s.pending - part, failed = s.failed - part) },
                onFailure = { s.copy(pending = s.pending - part, failed = s.failed + part) },
            )
        }
    }

    /**
     * Tonight's plan from the person's own Mealie, when it's connected and not paused in Where your
     * data goes (never through a share). A problem with Mealie only means no plan here: the Food
     * page says what went wrong.
     */
    private suspend fun mealiePlan(date: String): com.health.api.MealiePlan? {
        if (c.viewing.value != null) return null
        val status = attempt { c.api.mealieStatus() }.getOrNull() ?: return null
        if (!status.connected || status.paused) return null
        return attempt { c.api.mealiePlan(date) }.getOrNull()
    }

    /**
     * Takes a dose, the way the Medications page does: now, for its scheduled time, at the site
     * chosen, with Undo (see [Doses]).
     */
    fun take(due: DueDose, site: String?) =
        Doses.mark(c, DoseLog(due.medicationId, System.currentTimeMillis(), DoseStatus.TAKEN, scheduledFor = due.scheduledFor, site = site), "${due.name} taken")
}
