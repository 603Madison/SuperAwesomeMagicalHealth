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
                val summary = async { c.api.mindSummary(30) }
                val moods = async { c.api.moods(14) }
                val journal = async { c.api.journal(365) }
                val screeners = async { runCatching { c.api.screeners() }.getOrDefault(_state.value.screeners) }
                val results = async { c.api.screenerResults(365) }
                val sessions = async { c.api.mindSessions(30) }
                val resources = async { runCatching { c.api.crisisResources() }.getOrDefault(_state.value.resources) }
                _state.update {
                    it.copy(
                        summary = summary.await(), moods = moods.await().sortedByDescending { m -> m.at },
                        journal = journal.await().sortedByDescending { j -> j.at }, screeners = screeners.await(),
                        results = results.await(), sessions = sessions.await(), resources = resources.await(), loading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load Mind") }
            }
        }
    }

    /** Makes sure the support list is there (it's also kept from the last load, for when offline). */
    fun loadResources() {
        if (_state.value.resources.isNotEmpty()) return
        viewModelScope.launch { runCatching { c.api.crisisResources() }.onSuccess { r -> _state.update { it.copy(resources = r) } } }
    }

    private fun act(message: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun logMood(entry: MoodEntry) = act("Mood saved") { c.api.addMood(entry) }

    fun deleteMood(id: String) = act(null) { c.api.deleteMood(id) }

    fun saveJournal(entry: JournalEntry) = act("Saved to your journal") {
        if (entry.id == null) c.api.addJournal(entry) else c.api.updateJournal(entry)
    }

    fun deleteJournal(id: String) = act("Entry deleted") { c.api.deleteJournal(id) }

    fun logSession(session: MindfulnessSession) = act("Nicely done") { c.api.addMindSession(session) }

    fun submitScreener(result: ScreenerResult) {
        viewModelScope.launch {
            try {
                val scored = c.api.submitScreener(result)
                _state.update { it.copy(justScored = scored, results = it.results + scored) }
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }

    fun clearScored() = _state.update { it.copy(justScored = null) }
}
