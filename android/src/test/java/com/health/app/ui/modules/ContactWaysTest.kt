package com.health.app.ui.modules

import com.health.app.ui.modules.mind.ContactWays
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
}
