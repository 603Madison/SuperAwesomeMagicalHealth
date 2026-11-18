package com.health.app.ui.modules.meds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.LabResult
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.MedsHistory
import com.health.app.attempt
import com.health.app.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class MedsTab(val label: String) { TODAY("Today"), ALL("Medications"), LABS("Labs") }

data class MedsState(
    val date: LocalDate,
    val tab: MedsTab = MedsTab.TODAY,
    val day: MedsDay? = null,
    val meds: List<Medication> = emptyList(),
    val labs: List<LabResult> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The history's month, what happened each day of it, and the day picked there with its doses. */
    val month: YearMonth = YearMonth.from(date),
    val history: MedsHistory? = null,
    val historyError: String? = null,
    val picked: LocalDate? = null,
    val pickedDay: MedsDay? = null,
)

class MedsViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(MedsState(LocalDate.now(c.zone())))
    val state: StateFlow<MedsState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun tab(tab: MedsTab) = _state.update { it.copy(tab = tab) }

    fun load(quiet: Boolean = false) {
        val date = LocalDate.now(c.zone())
        _state.update { it.copy(loading = !quiet || it.day == null, error = null, date = date) }
        loadHistory()
        loadPicked()
        viewModelScope.launch {
            try {
                val day = async { c.ui.medsDay(date.toString()) }
                val meds = async { c.ui.medications(all = true) }
                val labs = async { c.ui.labs() }
                val loaded = day.await()
                c.medsCache.saveSites(loaded)
                _state.update { it.copy(day = loaded, meds = meds.await(), labs = labs.await().sortedByDescending { l -> l.at }, loading = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your medications") }
            }
        }
    }

    // ---- The history

    /** The month [by] months on (never past this one). */
    fun month(by: Int) {
        _state.update {
            val next = it.month.plusMonths(by.toLong())
            if (next.isAfter(YearMonth.from(LocalDate.now(c.zone())))) it else it.copy(month = next, history = null, historyError = null, picked = null, pickedDay = null)
        }
        loadHistory()
    }

    /** Shows [date]'s doses under the calendar; picked again, hides them. */
    fun pick(date: LocalDate) {
        _state.update { it.copy(picked = if (it.picked == date) null else date, pickedDay = null) }
        loadPicked()
    }

    private fun loadHistory() {
        val month = _state.value.month
        viewModelScope.launch {
            attempt { c.ui.medsHistory(month.atDay(1).toString(), month.atEndOfMonth().toString()) }
                .onSuccess { h -> _state.update { if (it.month == month) it.copy(history = h, historyError = null) else it } }
                .onFailure { e -> _state.update { if (it.month == month) it.copy(historyError = e.message ?: "Couldn't load the history") else it } }
        }
    }

    private fun loadPicked() {
        val date = _state.value.picked ?: return
        viewModelScope.launch {
            attempt { c.ui.medsDay(date.toString()) }
                .onSuccess { d -> _state.update { if (it.picked == date) it.copy(pickedDay = d) else it } }
                .onFailure { e -> if (_state.value.picked == date) c.tell(e.message ?: "Couldn't load that day") }
        }
    }

    private fun act(message: String?, after: suspend () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
                after()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun take(due: DueDose, site: String?) = mark(due, DoseStatus.TAKEN, site)

    fun skip(due: DueDose) = mark(due, DoseStatus.SKIPPED, null)

    /** A day that's over is marked for the time the dose was due (when it was really taken isn't known). */
    private fun mark(due: DueDose, status: DoseStatus, site: String?) {
        val zone = c.zone()
        val past = Instant.ofEpochMilli(due.scheduledFor).atZone(zone).toLocalDate().isBefore(LocalDate.now(zone))
        val at = if (past) due.scheduledFor else System.currentTimeMillis()
        val said = if (status == DoseStatus.TAKEN) "taken" else "skipped"
        Doses.mark(c, DoseLog(due.medicationId, at, status, scheduledFor = due.scheduledFor, site = site), "${due.name} $said")
    }

    fun tookOne(med: Medication) = Doses.mark(c, DoseLog(med.id!!, System.currentTimeMillis()), "${med.name} noted")

    fun undo(log: DoseLog) = log.id?.let { Doses.undo(c, it) }

    /** Takes a dose off the history (one taken as needed, say). */
    fun remove(log: DoseLog) = log.id?.let { Doses.undo(c, it, "Removed") }

    fun save(med: Medication) = act("Saved", after = { c.refreshReminders() }) {
        if (med.id == null) c.ui.addMedication(med) else c.ui.updateMedication(med)
    }

    fun delete(id: String) = act("Medication removed", after = { c.refreshReminders() }) { c.ui.deleteMedication(id) }

    fun addLab(lab: LabResult) = act("Result saved") { c.ui.addLab(lab) }

    fun deleteLab(id: String) = act(null) { c.ui.deleteLab(id) }
}
