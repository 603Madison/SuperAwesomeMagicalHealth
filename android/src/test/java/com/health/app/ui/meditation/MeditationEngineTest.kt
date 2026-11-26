package com.health.app.ui.meditation

import com.health.api.BreathPacing
import com.health.api.Meditation
import com.health.api.MeditationKind
import com.health.api.MeditationStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playing a meditation, on a virtual clock: words, pacer and quiet in turn; pausing, stopping and text only. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeditationEngineTest {
    private val meditation = Meditation(
        "breathing-5", "Slow breathing", "Easy breaths.", 5, MeditationKind.BREATHING,
        listOf(
            // 5 words: 2 seconds at the calm pace.
            MeditationStep("We'll breathe slowly together now.", pauseSeconds = 4),
            MeditationStep("In for four, out for six.", pauseSeconds = 2, breath = BreathPacing(4, 0, 6, 2)),
            MeditationStep("Rest here.", pauseSeconds = 3),
        ),
    )

    /** A voice that takes [secondsEach] to read anything, and keeps what it read. */
    private class FakeVoice(private val secondsEach: Long) : Speaker {
        val said = mutableListOf<String>()
        var stops = 0
        private var reading: CompletableDeferred<Boolean>? = null

        override suspend fun say(text: String): Boolean {
            val done = CompletableDeferred<Boolean>().also { reading = it }
            said += text
            return withTimeoutOrNull(secondsEach * 1000) { done.await() } ?: true
        }

        override fun stop() {
            stops++
            reading?.complete(false)
        }
    }

    private class Counter : Chimes {
        var count = 0
        override suspend fun chime() {
            count++
        }
    }

    private fun TestScope.engine(voice: Speaker?, mode: VoiceMode = VoiceMode.VOICE, fadeOut: Boolean = false, chimes: Chimes = Counter(), ended: MutableList<PlayerState> = mutableListOf()) =
        MeditationEngine(meditation, voice, chimes, { currentTime }, mode, fadeOut, onEnd = { ended += it })

    /** A voice that takes the words and never reads them, or never says it has (some phones' engines). */
    private class SilentVoice : Speaker {
        var stops = 0
        private var reading: CompletableDeferred<Boolean>? = null
        override suspend fun say(text: String): Boolean = CompletableDeferred<Boolean>().also { reading = it }.await()
        override fun stop() {
            stops++
            reading?.complete(false)
        }
    }

    @Test
    fun `a voice that never reads doesn't hold it up, it carries on with the words shown`() = runTest {
        val voice = SilentVoice()
        val e = engine(voice)
        e.start(this)
        runCurrent()
        assertEquals(0, e.state.value.step)
        // Twice the 2 s the words take, and 8 s more: then it gives up on the voice.
        advanceTimeBy(12_100)
        assertEquals(VoiceMode.VOICE_FAILED, e.state.value.voice)
        assertTrue(voice.stops > 0)
        // Then the step's quiet (4 s), and the next step, shown without the voice.
        advanceTimeBy(4_100)
        assertEquals(1, e.state.value.step)
        e.stop()
    }

    @Test
    fun `text only shows each step for as long as it would take to read, then the pacer, then the quiet`() = runTest {
        val ended = mutableListOf<PlayerState>()
        val chimes = Counter()
        val e = engine(null, VoiceMode.TEXT_ONLY, chimes = chimes, ended = ended)
        e.start(this)
        runCurrent()
        assertEquals(1, chimes.count)
        assertEquals("We'll breathe slowly together now.", e.state.value.say)
        assertEquals(VoiceMode.TEXT_ONLY, e.state.value.voice)
        // Words (2 s) and quiet (4 s): the second step's words then.
        advanceTimeBy(6_100)
        assertEquals(1, e.state.value.step)
        assertNull(e.state.value.breath)
        // Its words take 2.4 s; the pacer follows.
        advanceTimeBy(2_500)
        val breath = e.state.value.breath
        assertNotNull(breath)
        assertEquals(BreathPhase.IN, breath!!.phase)
        assertEquals(1, breath.round)
        advanceTimeBy(5_000)
        assertEquals(BreathPhase.OUT, e.state.value.breath?.phase)
        advanceTimeBy(10_000)
        assertEquals(2, e.state.value.breath?.round)
        advanceUntilIdle()
        val end = ended.single()
        assertEquals(PlayerPhase.ENDED, end.phase)
        assertTrue(end.finished)
        // 2 + 4 + 2.4 + 20 + 2 + 1 + 3 seconds.
        assertEquals(34_400.0, end.activeMs.toDouble(), 300.0)
        assertEquals(2, chimes.count)
        assertEquals(0.0, e.state.value.secondsLeft, 1e-9)
    }

    @Test
    fun `aloud, each step is read and then its quiet follows`() = runTest {
        val voice = FakeVoice(secondsEach = 5)
        val ended = mutableListOf<PlayerState>()
        val e = engine(voice, ended = ended)
        e.start(this)
        advanceUntilIdle()
        assertEquals(meditation.steps.map { it.say }, voice.said)
        // 5 + 4 + 5 + 20 + 2 + 5 + 3 seconds.
        assertEquals(44_000.0, ended.single().activeMs.toDouble(), 300.0)
    }

    @Test
    fun `a pause holds everything, and the words are read again on resuming`() = runTest {
        val voice = FakeVoice(secondsEach = 5)
        val ended = mutableListOf<PlayerState>()
        val e = engine(voice, ended = ended)
        e.start(this)
        advanceTimeBy(2_000)
        e.pause()
        runCurrent()
        assertEquals(PlayerPhase.PAUSED, e.state.value.phase)
        assertTrue(voice.stops >= 1)
        val left = e.state.value.secondsLeft
        advanceTimeBy(60_000)
        assertEquals(0, e.state.value.step)
        assertEquals(left, e.state.value.secondsLeft, 1e-9)
        e.resume()
        advanceUntilIdle()
        assertEquals(meditation.steps[0].say, voice.said[0])
        assertEquals(meditation.steps[0].say, voice.said[1])
        assertEquals(4, voice.said.size)
        // The minute paused doesn't count.
        assertEquals(46_000.0, ended.single().activeMs.toDouble(), 300.0)
    }

    @Test
    fun `stopping ends it there, not finished, with the time actually spent`() = runTest {
        val ended = mutableListOf<PlayerState>()
        val chimes = Counter()
        val e = engine(FakeVoice(5), chimes = chimes, ended = ended)
        e.start(this)
        advanceTimeBy(12_000)
        e.stop()
        advanceUntilIdle()
        val end = ended.single()
        assertFalse(end.finished)
        assertEquals(PlayerPhase.ENDED, end.phase)
        assertEquals(12_000L, end.activeMs)
        assertEquals(1, chimes.count)
        // Under a minute: nothing's kept.
        assertFalse(MeditationPlayback.keeps(end))
        assertTrue(MeditationPlayback.keeps(end.copy(activeMs = 60_000)))
        assertTrue(MeditationPlayback.keeps(end.copy(finished = true, activeMs = 5_000)))
        val session = MeditationPlayback.session(end.copy(activeMs = 90_000))
        assertEquals(1.5, session.minutes, 1e-9)
        assertEquals("breathing-5", session.meditationId)
        assertFalse(session.finished)
        // Stopping again changes nothing.
        e.stop()
        assertEquals(1, ended.size)
    }

    @Test
    fun `fading out leaves the last chime out`() = runTest {
        val chimes = Counter()
        val e = engine(null, VoiceMode.TEXT_ONLY, fadeOut = true, chimes = chimes)
        e.start(this)
        advanceUntilIdle()
        assertEquals(1, chimes.count)
        assertTrue(e.state.value.finished)
        assertTrue(e.state.value.fadeOut)
    }

    @Test
    fun `without an offline voice it's text only, and saying so, and turning the voice off stops it mid-sentence`() = runTest {
        val none = engine(null)
        assertEquals(VoiceMode.NO_OFFLINE_VOICE, none.state.value.voice)

        val voice = FakeVoice(secondsEach = 30)
        val ended = mutableListOf<PlayerState>()
        val e = engine(voice, ended = ended)
        e.start(this)
        advanceTimeBy(1_000)
        e.setVoice(false)
        assertEquals(VoiceMode.TEXT_ONLY, e.state.value.voice)
        advanceUntilIdle()
        // Only the first step was begun aloud; the rest were shown.
        assertEquals(1, voice.said.size)
        assertTrue(ended.single().finished)
        assertEquals(34_400.0, ended.single().activeMs.toDouble(), 300.0)
    }
}
