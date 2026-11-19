package com.health.app.ui.signin

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChoosePasswordTest {
    @Test
    fun `an admin-set password is said for 90 days, then not`() {
        val at = 1_793_707_200_000L // 3 November 2026, 12:00 UTC
        assertEquals("An admin set your password on 3 November 2026.", ChoosePassword.adminSet(at, at + 1000, ZoneOffset.UTC, Locale.UK))
        assertEquals("An admin set your password on 3 November 2026.", ChoosePassword.adminSet(at, at + ChoosePassword.SHOWN_MS, ZoneOffset.UTC, Locale.UK))
        assertNull(ChoosePassword.adminSet(at, at + ChoosePassword.SHOWN_MS + 1, ZoneOffset.UTC, Locale.UK))
        assertNull(ChoosePassword.adminSet(null, at))
    }
}
