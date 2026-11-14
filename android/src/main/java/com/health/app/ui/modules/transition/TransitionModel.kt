package com.health.app.ui.modules.transition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.BodyChange
import com.health.api.GenderMoment
import com.health.api.HairRemovalSession
import com.health.api.MilestoneStatus
import com.health.api.MilestoneTemplate
import com.health.api.TransitionOverview
import com.health.api.VoiceSession
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.ServerApi
import com.health.app.net.bodyChanges
import com.health.app.net.genderMoments
import com.health.app.net.hairRemoval
import com.health.app.net.milestoneTemplates
import com.health.app.net.transitionOverview
import com.health.app.net.voiceProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Transition (Module.TRANSITION): whatever it means to the person, at their pace. Nothing here
 * assumes a direction, a goal or medical steps.
 */

data class TransitionState(
    val overview: TransitionOverview? = null,
    val templates: List<MilestoneTemplate> = emptyList(),
    val changes: List<BodyChange> = emptyList(),
    val hair: List<HairRemovalSession> = emptyList(),
    val moments: List<GenderMoment> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The voice trainer's progress (voice/), for its card; null until loaded or if it can't be. */
    val voiceProgress: com.health.api.VoiceProgress? = null,
) {
    val voice: List<VoiceSession> get() = overview?.voice.orEmpty()
}

class TransitionViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(TransitionState())
    val state: StateFlow<TransitionState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.overview == null, error = null) }
        viewModelScope.launch {
            try {
                val api = c.ui
                val overview = async { api.transitionOverview() }
                val templates = async { attempt { api.milestoneTemplates() }.getOrDefault(_state.value.templates) }
                val changes = async { api.bodyChanges() }
                val hair = async { api.hairRemoval(730) }
                val moments = async { api.genderMoments(90) }
                val voice = async { attempt { api.voiceProgress() }.getOrDefault(_state.value.voiceProgress) }
                _state.update {
                    it.copy(
                        overview = overview.await(), templates = templates.await(), changes = changes.await(), hair = hair.await(),
                        moments = moments.await(), loading = false, voiceProgress = voice.await(),
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load Transition") }
            }
        }
    }

    /** Saves on the app's scope, so it finishes even though the page closes straight after. */
    fun act(message: String?, block: suspend (ServerApi) -> Unit) {
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

/** Plain words for the 1–5s and the statuses. */
object TransitionWords {
    /** How the person feels about a change, 1 not good … 5 love it. */
    fun feeling(n: Int?): String? = when (n) {
        1 -> "Not good"
        2 -> "Mixed"
        3 -> "Neutral"
        4 -> "Good"
        5 -> "Love it"
        else -> null
    }

    /** The order milestones show in: under way first, then planned, ideas, and done. */
    val STATUS_ORDER = listOf(MilestoneStatus.IN_PROGRESS, MilestoneStatus.PLANNED, MilestoneStatus.IDEA, MilestoneStatus.DONE)

    val CHANGE_AREAS = listOf("chest", "skin", "hair", "body hair", "face", "voice", "fat distribution", "muscle", "emotions", "libido", "periods", "energy", "smell")
    val HAIR_AREAS = listOf("face", "neck", "chest", "back", "arms", "underarms", "legs", "bikini line")
    val MOMENT_CONTEXTS = listOf("mirror", "voice", "being gendered right", "being misgendered", "my name", "clothes", "body", "photos", "with friends", "at work")
    val VOICE_EXERCISES = listOf("warm-ups", "resonance", "pitch slides", "humming", "reading aloud", "intonation", "conversation")
    val GOALS = listOf("feel at home in my body", "a voice that feels like me", "my name on my documents", "come out to the people I love")
}
