package com.health.app.ui.modules.transition.voice

import com.health.api.VoiceSession
import com.health.app.net.ServerException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Voice sessions waiting to be sent: every page of the trainer asks to send them when data
 * changes, so sending takes turns, nothing goes twice, and nothing kept meanwhile is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class VoiceSendingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val store = VoiceStore(context)
    private val me = "aaaa1111"
    private val lock = Mutex()

    private fun session(at: Long) = VoiceSession(at = at, minutes = 10)

    @Before
    fun clean() = store.forget(context, dropUnsentOf = me)

    @Test
    fun `pages sending at once send each session once`() = runBlocking {
        store.savePending(me, listOf(session(1), session(2)))
        val sent = mutableListOf<Long>()
        val counts = (1..3).map {
            async { sendWaiting(store, me, lock) { s -> delay(20); sent += s.at } }
        }.awaitAll()
        assertEquals(listOf(1L, 2L), sent.sorted())
        assertEquals(2, counts.sum())
        assertTrue(store.pending(me).isEmpty())
    }

    @Test
    fun `a session kept while others are sent isn't lost`() = runBlocking {
        store.savePending(me, listOf(session(1)))
        val sending = async { sendWaiting(store, me, lock) { delay(50) } }
        yield()
        // Finished while the first one goes: it waits its turn, and stays to be sent.
        keepWaiting(store, me, lock, session(2))
        assertEquals(1, sending.await())
        assertEquals(listOf(2L), store.pending(me).map { it.at })
    }

    @Test
    fun `a session written meanwhile without the lock isn't overwritten`() = runBlocking {
        store.savePending(me, listOf(session(1)))
        sendWaiting(store, me, lock) { store.savePending(me, store.pending(me) + session(3)) }
        assertEquals(listOf(3L), store.pending(me).map { it.at })
    }

    @Test
    fun `offline, sessions wait, and one refused as wrong is dropped`() = runBlocking {
        store.savePending(me, listOf(session(1), session(2)))
        assertEquals(0, sendWaiting(store, me, lock) { throw ServerException(0, "offline") })
        assertEquals(2, store.pending(me).size)
        sendWaiting(store, me, lock) { s -> if (s.at == 1L) throw ServerException(400, "That request isn't right", fromServer = true) else throw ServerException(503, "busy") }
        assertEquals(listOf(2L), store.pending(me).map { it.at })
    }

    @Test
    fun `sessions wait under their own account`() {
        store.savePending(me, listOf(session(1)))
        assertTrue(store.pending("bbbb2222").isEmpty())
        assertTrue(store.pending(null).isEmpty())
        // Signing out without asking keeps them; asking forgets that account's.
        store.forget(context)
        assertEquals(1, store.pending(me).size)
        store.forget(context, dropUnsentOf = me)
        assertTrue(store.pending(me).isEmpty())
    }
}
