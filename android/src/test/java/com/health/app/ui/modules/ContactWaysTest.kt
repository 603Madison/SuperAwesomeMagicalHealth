package com.health.app.ui.modules

import com.health.app.ui.modules.mind.ContactWays
import com.health.server.modules.MindService
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactWaysTest {
    @Test
    fun `call or text`() {
        assertEquals(ContactWays("988", call = true, text = true, textBody = null), ContactWays.of("Call or text 988"))
    }

    @Test
    fun `text only, with the word to send`() {
        assertEquals(ContactWays("741741", call = false, text = true, textBody = "HOME"), ContactWays.of("Text HOME to 741741"))
    }

    @Test
    fun `a bare number is called`() {
        assertEquals(ContactWays("116123", call = true, text = false, textBody = null), ContactWays.of("Samaritans: 116 123"))
        assertEquals(ContactWays("8775658860", call = true, text = false, textBody = null), ContactWays.of("Call 877-565-8860"))
    }

    @Test
    fun `no number, no buttons`() {
        assertEquals(ContactWays(null, call = false, text = false, textBody = null), ContactWays.of("Chat on the website"))
    }

    @Test
    fun `every support line the server starts with gets the right buttons`() {
        val expected = mapOf(
            "United States|988 Suicide & Crisis Lifeline" to ContactWays("988", call = true, text = true, textBody = null),
            "United States|Trans Lifeline" to ContactWays("8775658860", call = true, text = false, textBody = null),
            "Canada|Trans Lifeline" to ContactWays("8773306366", call = true, text = false, textBody = null),
            "United States|Crisis Text Line" to ContactWays("741741", call = false, text = true, textBody = "HOME"),
            "Canada|9-8-8 Suicide Crisis Helpline" to ContactWays("988", call = true, text = true, textBody = null),
            "United Kingdom and Ireland|Samaritans" to ContactWays("116123", call = true, text = false, textBody = null),
            "Australia|Lifeline" to ContactWays("131114", call = true, text = false, textBody = null),
            "Everywhere else|Find a Helpline" to ContactWays(null, call = false, text = false, textBody = null),
        )
        val actual = MindService.DEFAULT_RESOURCES.associate { "${it.region}|${it.name}" to ContactWays.of(it.contact) }
        assertEquals(expected, actual)
    }
}
