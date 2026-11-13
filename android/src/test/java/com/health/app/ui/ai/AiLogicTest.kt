package com.health.app.ui.ai

import com.health.api.AiConsent
import com.health.api.AiLook
import com.health.api.AiStatus
import com.health.api.Module
import com.health.app.net.ServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLogicTest {
    private val on = setOf(Module.SLEEP, Module.ACTIVITY, Module.CONTINENCE, Module.MIND, Module.PHOTOS)

    @Test
    fun `photos are never offered, and private features are never ticked to start`() {
        assertEquals(listOf(Module.ACTIVITY, Module.SLEEP, Module.MIND, Module.CONTINENCE), AiText.offered(on))
        assertEquals(setOf(Module.ACTIVITY, Module.SLEEP), AiText.initial(null, on))
        // An earlier choice is kept as it was (less photos).
        assertEquals(setOf(Module.CONTINENCE), AiText.initial(AiConsent(setOf(Module.CONTINENCE, Module.PHOTOS)), on))
    }

    @Test
    fun `turning it on takes the warning read, and its own tick for private features`() {
        assertFalse(AiText.canAgree(false, setOf(Module.SLEEP), false))
        assertTrue(AiText.canAgree(true, setOf(Module.SLEEP), false))
        assertFalse(AiText.canAgree(true, setOf(Module.SLEEP, Module.CONTINENCE), false))
        assertTrue(AiText.canAgree(true, setOf(Module.SLEEP, Module.CONTINENCE), true))
    }

    @Test
    fun `logging is only asked for where the admin allows it`() {
        val off = AiStatus(available = true, loggingAllowed = false)
        val allowed = off.copy(loggingAllowed = true)
        assertFalse(AiText.consent(setOf(Module.SLEEP), true, false, true, off).allowLogging)
        assertTrue(AiText.consent(setOf(Module.SLEEP), true, false, true, allowed).allowLogging)
        val c = AiText.consent(setOf(Module.SLEEP, Module.PHOTOS), true, true, false, allowed)
        assertEquals(setOf(Module.SLEEP), c.modules)
        // The private tick only counts when a private feature is chosen.
        assertFalse(c.sensitiveAcknowledged)
        assertTrue(c.acknowledged)
    }

    @Test
    fun `usage and what it looked at, in words`() {
        assertEquals("3 of 40 questions today", AiText.usage(3, 40))
        assertEquals("1 question today", AiText.usage(1, 0))
        assertTrue(AiText.atLimit(AiStatus(true, usedToday = 40, dailyLimit = 40)))
        assertFalse(AiText.atLimit(AiStatus(true, usedToday = 39, dailyLimit = 40)))
        assertEquals(
            "Looked at: Sleep, last 7 nights · Bladder and bowel diary, 14 days",
            AiText.looked(listOf(AiLook("sleep", Module.SLEEP, "Sleep, last 7 nights"), AiLook("continence", Module.CONTINENCE, "Bladder and bowel diary, 14 days"), AiLook("sleep", Module.SLEEP, "Sleep, last 7 nights"))),
        )
        assertEquals("Looked at: nothing of your data, only the conversation", AiText.looked(emptyList()))
    }

    @Test
    fun `the conversation goes as it is, and errors are plain`() {
        val turns = listOf(ChatTurn(ChatTurn.USER, "How did I sleep?"), ChatTurn(ChatTurn.ASSISTANT, "Well."), ChatTurn(ChatTurn.USER, "And last week?"))
        assertEquals(listOf("user", "assistant", "user"), AiText.messages(turns).map { it.role })
        assertEquals("Can't reach your server right now. Check your connection and try again.", AiText.error(ServerException(0, "Can't reach the server (timeout)")))
        assertEquals("That's today's 40 questions; the assistant is back tomorrow", AiText.error(ServerException(429, "That's today's 40 questions; the assistant is back tomorrow")))
        assertEquals("The assistant isn't turned on on this server.", AiText.error(ServerException(409, "The assistant isn't turned on on this server")))
        assertEquals("Something went wrong. Try again in a moment.", AiText.error(IllegalStateException("boom")))
    }

    @Test
    fun `suggestions follow what it may look at`() {
        assertTrue(AiText.suggestions(setOf(Module.CONTINENCE)).first().contains("leaks"))
        assertEquals(listOf("What stands out from my last week?"), AiText.suggestions(emptySet()))
        assertTrue(AiText.suggestions(Module.entries.toSet()).size <= 4)
    }
}
