package com.health.app.ui.modules.meds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.LabResult
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.app.Container
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class MedsTab(val label: String) { TODAY("Today"), ALL("Medications"), LABS("Labs") }

data class MedsState(
    val date: LocalDate,
    val tab: MedsTab = MedsTab.TODAY,
    val day: MedsDay? = null,
    val meds: List<Medication> = emptyList(),
    val labs: List<LabResult> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
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
        viewModelScope.launch {
            try {
                val day = async { c.api.medsDay(date.toString()) }
                val meds = async { c.api.medications(all = true) }
                val labs = async { c.api.labs() }
                val loaded = day.await()
                c.medsCache.saveSites(loaded)
                _state.update { it.copy(day = loaded, meds = meds.await(), labs = labs.await().sortedByDescending { l -> l.at }, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your medications") }
            }
        }
    }

    private fun act(message: String?, after: suspend () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
                after()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun take(due: DueDose, site: String?) = act("${due.name} taken") {
        c.api.logDose(DoseLog(due.medicationId, System.currentTimeMillis(), DoseStatus.TAKEN, scheduledFor = due.scheduledFor, site = site))
    }

    fun skip(due: DueDose) = act("Skipped") {
        c.api.logDose(DoseLog(due.medicationId, System.currentTimeMillis(), DoseStatus.SKIPPED, scheduledFor = due.scheduledFor))
    }

    fun tookOne(med: Medication) = act("${med.name} noted") { c.api.logDose(DoseLog(med.id!!, System.currentTimeMillis())) }

    fun undo(log: DoseLog) = act(null) { log.id?.let { c.api.deleteDose(it) } }

    fun save(med: Medication) = act("Saved", after = { c.refreshReminders() }) {
        if (med.id == null) c.api.addMedication(med) else c.api.updateMedication(med)
    }

    fun delete(id: String) = act("Medication removed", after = { c.refreshReminders() }) { c.api.deleteMedication(id) }

    fun addLab(lab: LabResult) = act("Result saved") { c.api.addLab(lab) }

    fun deleteLab(id: String) = act(null) { c.api.deleteLab(id) }
}
