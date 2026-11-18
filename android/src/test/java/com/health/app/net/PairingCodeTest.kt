package com.health.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app sign-in's pairing code, the same as the web page works it out (web/test/pairing.test.ts). */
class PairingCodeTest {
    @Test
    fun `six digits from the challenge, the same as the page's`() {
        val challenge = SignIn.challenge("a-verifier-for-the-test-only-a-verifier-for-the-test")
        val code = SignIn.pairingCode(challenge)
        assertTrue(code, Regex("""\d{3} \d{3}""").matches(code))
        assertEquals(code, SignIn.pairingCode(challenge))
        assertNotEquals(code, SignIn.pairingCode(SignIn.challenge("another verifier, another sign-in")))
        // The vector the web's test checks too.
        assertEquals("249 228", SignIn.pairingCode("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
    }
}
