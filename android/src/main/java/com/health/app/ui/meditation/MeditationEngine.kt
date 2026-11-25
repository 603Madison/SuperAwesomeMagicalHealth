package com.health.app.ui.meditation

import com.health.api.Meditation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Playing a meditation: for each step, its words (read aloud, or shown for as long as reading them
 * would take), then its breathing pacer, then its quiet; a chime at the start and (unless it's to
 * fade out) at the end. Pausing holds everything where it is; words cut off by a pause are read
 * again from the start of the step. Kept apart from Android (the voice and the chime come in as
 * [Speaker] and [Chimes]) so it's tested with a virtual clock.
 */

/** Reads words aloud. */
interface Speaker {
    /** Reads [text]; true once it's been read to the end, false if it was stopped or couldn't be. */
    suspend fun say(text: String): Boolean

    /** Stops reading at once ([say] then answers false). */
    fun stop()
}

/** The start and end chime. */
fun interface Chimes {
    suspend fun chime()
}

/** How it's being read: aloud, as text only (the person's choice), or text only for want of an offline voice. */
enum class VoiceMode { VOICE, TEXT_ONLY, NO_OFFLINE_VOICE }

enum class PlayerPhase { PLAYING, PAUSED, ENDED }

/** What the player shows. */
data class PlayerState(
    val meditationId: String,
    val title: String,
    val minutes: Int,
    val phase: PlayerPhase = PlayerPhase.PLAYING,
    /** The step on (0-based), and its words. */
    val step: Int = 0,
    val say: String = "",
    /** The pacer, while a step's breathing runs. */
    val breath: PacerPoint? = null,
    val secondsLeft: Double = 0.0,
    val startedAt: Long = 0L,
    /** How long it's gone on, pauses left out. */
    val activeMs: Long = 0L,
    /** Heard to the end (false when stopped). */
    val finished: Boolean = false,
    val voice: VoiceMode = VoiceMode.VOICE,
    val fadeOut: Boolean = false,
) {
    val minutesSpent: Double get() = activeMs / 60_000.0
}

class MeditationEngine(
    private val meditation: Meditation,
    private val speaker: Speaker?,
    private val chimes: Chimes,
    private val clock: () -> Long,
    voice: VoiceMode,
    fadeOut: Boolean,
    /** Told once, when it ends or is stopped. */
    private val onEnd: (PlayerState) -> Unit = {},
) {
    private val timeline = MeditationPlan.timeline(meditation.steps)
    private val _state = MutableStateFlow(
        PlayerState(
            meditation.id, meditation.title, meditation.minutes,
            say = MeditationPlan.sayAt(meditation.steps, 0),
            secondsLeft = MeditationPlan.total(timeline),
            voice = if (speaker == null && voice == VoiceMode.VOICE) VoiceMode.NO_OFFLINE_VOICE else voice,
            fadeOut = fadeOut,
        ),
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pausedSince = 0L
    private var index = 0
    private var segmentStart = 0L
    private var job: Job? = null
    private var ended = false

    /** Time gone on, pauses left out. */
    private fun active(): Long {
        val now = clock()
        return now - startedAt - pausedTotal - if (paused.value) now - pausedSince else 0L
    }

    /** Starts it in [scope]. */
    fun start(scope: CoroutineScope): Job {
        startedAt = clock()
        _state.update { it.copy(startedAt = startedAt) }
        return scope.launch {
            val ticker = launch { tick() }
            chimes.chime()
            timeline.forEachIndexed { i, segment ->
                index = i
                segmentStart = active()
                show()
                when (segment) {
                    is Segment.Speak -> speak(segment)
                    is Segment.Breathe, is Segment.Pause -> wait((segment.seconds * 1000).toLong())
                }
            }
            index = timeline.size
            ticker.cancel()
            _state.update { it.copy(breath = null, secondsLeft = 0.0) }
            if (!_state.value.fadeOut) chimes.chime()
            end(finished = true)
        }.also { job = it }
    }

    fun pause() {
        if (paused.value || ended) return
        pausedSince = clock()
        paused.value = true
        speaker?.stop()
        _state.update { it.copy(phase = PlayerPhase.PAUSED, activeMs = active()) }
    }

    fun resume() {
        if (!paused.value || ended) return
        pausedTotal += clock() - pausedSince
        paused.value = false
        _state.update { it.copy(phase = PlayerPhase.PLAYING) }
    }

    /** Stops it where it is. */
    fun stop() {
        if (ended) return
        if (paused.value) resume()
        speaker?.stop()
        val running = job
        end(finished = false)
        running?.cancel()
    }

    /** Read aloud, or text only: from the words on now. */
    fun setVoice(on: Boolean) {
        val mode = when {
            !on -> VoiceMode.TEXT_ONLY
            speaker == null -> VoiceMode.NO_OFFLINE_VOICE
            else -> VoiceMode.VOICE
        }
        if (mode == _state.value.voice) return
        _state.update { it.copy(voice = mode) }
        if (mode != VoiceMode.VOICE) speaker?.stop()
    }

    fun setFadeOut(on: Boolean) = _state.update { it.copy(fadeOut = on) }

    private fun end(finished: Boolean) {
        if (ended) return
        ended = true
        _state.update { it.copy(phase = PlayerPhase.ENDED, finished = finished, activeMs = active(), breath = null) }
        onEnd(_state.value)
    }

    /** Seconds into the segment on. */
    private fun into(): Double = (active() - segmentStart) / 1000.0

    private fun show() {
        val segment = timeline.getOrNull(index) ?: return
        val into = into()
        _state.update {
            it.copy(
                step = segment.step,
                say = MeditationPlan.sayAt(meditation.steps, segment.step),
                breath = (segment as? Segment.Breathe)?.let { b -> Pacer.at(b.pacing, into) },
                secondsLeft = MeditationPlan.secondsLeft(timeline, index, into),
                activeMs = active(),
            )
        }
    }

    /** Keeps the time left (and the pacer) current: often while the pacer's moving, else once a second. */
    private suspend fun tick() {
        while (true) {
            if (!paused.value) show()
            delay(if (timeline.getOrNull(index) is Segment.Breathe) BREATH_TICK_MS else TICK_MS)
        }
    }

    /** Waits [ms] of time that isn't paused. */
    private suspend fun wait(ms: Long) {
        val until = active() + ms
        while (true) {
            if (paused.value) paused.first { !it }
            val left = until - active()
            if (left <= 0) return
            delay(left.coerceAtMost(WAIT_STEP_MS))
        }
    }

    /** Reads a step's words, or (text only) shows them for as long as they'd take to read. */
    private suspend fun speak(segment: Segment.Speak) {
        val estimate = (segment.seconds * 1000).toLong()
        while (true) {
            if (paused.value) paused.first { !it }
            val voice = speaker
            if (voice == null || _state.value.voice != VoiceMode.VOICE) {
                wait((estimate - (active() - segmentStart)).coerceAtLeast(0))
                return
            }
            val done = coroutineScope {
                val watch = launch { paused.first { it }; voice.stop() }
                val read = voice.say(segment.text)
                watch.cancel()
                read
            }
            if (done) return
            // Paused (read again on resuming), turned to text only (shown for the rest), or the voice failed (shown).
            if (!paused.value && _state.value.voice == VoiceMode.VOICE) {
                wait((estimate - (active() - segmentStart)).coerceAtLeast(0))
                return
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val BREATH_TICK_MS = 100L
        const val WAIT_STEP_MS = 250L
    }
}
