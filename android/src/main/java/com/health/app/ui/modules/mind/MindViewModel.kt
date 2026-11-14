package com.health.app.ui.modules.mind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.CrisisResource
import com.health.api.JournalEntry
import com.health.api.MindSummary
import com.health.api.MindfulnessSession
import com.health.api.MoodEntry
import com.health.api.Screener
import com.health.api.ScreenerDefinition
import com.health.api.ScreenerResult
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.lifeStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MindState(
    val summary: MindSummary? = null,
    val moods: List<MoodEntry> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val screeners: List<ScreenerDefinition> = emptyList(),
    val results: List<ScreenerResult> = emptyList(),
    val sessions: List<MindfulnessSession> = emptyList(),
    val resources: List<CrisisResource> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The latest check-in result, to show once it's been scored. */
    val justScored: ScreenerResult? = null,
    /** The person's life stage (with the cycle), for offering the EPDS. */
    val stage: com.health.api.LifeStage? = null,
) {
    fun latest(screener: Screener): ScreenerResult? = results.filter { it.screener == screener }.maxByOrNull { it.at }
}

/** Everything Mind shows, and saving what's logged there. */
class MindViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(MindState())
    val state: StateFlow<MindState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null) }
        viewModelScope.launch {
            try {
                // c.ui: someone viewing a share sees the shared person's.
                val summary = async { c.ui.mindSummary(30) }
                val moods = async { c.ui.moods(14) }
                val journal = async { c.ui.journal(365) }
                val screeners = async { attempt { c.ui.screeners() }.getOrDefault(_state.value.screeners) }
                val results = async { c.ui.screenerResults(365) }
                val sessions = async { c.ui.mindSessions(30) }
                val resources = async { attempt { c.ui.crisisResources() }.getOrDefault(_state.value.resources) }
                // While pregnant or after a birth, the EPDS is offered too.
                val stage = async {
                    if (com.health.api.Module.CYCLE in c.shownModules()) attempt { c.ui.lifeStage().stage }.getOrNull() else null
                }
                _state.update {
                    it.copy(
                        summary = summary.await(), moods = moods.await().sortedByDescending { m -> m.at },
                        journal = journal.await().sortedByDescending { j -> j.at }, screeners = screeners.await(),
                        results = results.await(), sessions = sessions.await(), resources = resources.await(), loading = false,
                        stage = stage.await(),
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load Mind") }
            }
        }
    }

    /** Makes sure the support list is there (it's also kept from the last load, for when offline). */
    fun loadResources() {
        if (_state.value.resources.isNotEmpty()) return
        viewModelScope.launch { attempt { c.ui.crisisResources() }.onSuccess { r -> _state.update { it.copy(resources = r) } } }
    }

    private fun act(message: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun logMood(entry: MoodEntry) = act("Mood saved") { c.ui.addMood(entry) }

    fun deleteMood(id: String) = act(null) { c.ui.deleteMood(id) }

    fun saveJournal(entry: JournalEntry) = act("Saved to your journal") {
        if (entry.id == null) c.ui.addJournal(entry) else c.ui.updateJournal(entry)
    }

    fun deleteJournal(id: String) = act("Entry deleted") { c.ui.deleteJournal(id) }

    fun logSession(session: MindfulnessSession) = act("Nicely done") { c.ui.addMindSession(session) }

    fun submitScreener(result: ScreenerResult) {
        viewModelScope.launch {
            try {
                val scored = c.ui.submitScreener(result)
                _state.update { it.copy(justScored = scored, results = it.results + scored) }
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun clearScored() = _state.update { it.copy(justScored = null) }
}
