package com.health.app.ui.modules.neuro

import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.FocusSession
import com.health.api.NeuroProfile
import com.health.api.Wording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuroMathTest {
    private val day = EnergyDay(
        "2026-11-12", startWith = 12,
        spends = listOf(EnergySpend("work", 5), EnergySpend("phone call", 1), EnergySpend("stimming", -1), EnergySpend("special interest", -2)),
    )

    @Test
    fun `the energy budget counts what was used, what came back, and what's left`() {
        assertEquals(6, EnergyMath.used(day))
        assertEquals(3, EnergyMath.gained(day))
        assertEquals(9, EnergyMath.left(day))
        assertEquals(0.75f, EnergyMath.fraction(day)!!, 0.001f)
        assertEquals("9 of 12 left", EnergyMath.leftText(day))
        assertFalse(EnergyMath.low(day))
    }

    @Test
    fun `running low is a sixth or less, and going over is said plainly`() {
        val tired = day.copy(spends = day.spends + EnergySpend("appointment", 7))
        assertEquals(2, EnergyMath.left(tired))
        assertTrue(EnergyMath.low(tired))
        val over = day.copy(spends = listOf(EnergySpend("a lot", 15)))
        assertEquals("3 over", EnergyMath.leftText(over))
        assertEquals(0f, EnergyMath.fraction(over)!!, 0.001f)
        // Without a starting number there's nothing to be left of.
        assertNull(EnergyMath.left(day.copy(startWith = null)))
        assertNull(EnergyMath.fraction(day.copy(startWith = null)))
    }

    private fun session(guess: Int?, took: Int?) = FocusSession(start = 0, end = took?.let { it * 60_000L }, estimateMinutes = guess)

    @Test
    fun `time estimates compare what was guessed with what it took`() {
        assertEquals(1.5, FocusMath.ratio(session(20, 30))!!, 0.001)
        assertNull(FocusMath.ratio(session(null, 30)))
        assertNull(FocusMath.ratio(session(20, null)))
        // Three sessions with a guess are needed, as on the server.
        assertNull(FocusMath.averageRatio(listOf(session(20, 30), session(10, 20))))
        assertEquals(1.7, FocusMath.averageRatio(listOf(session(20, 30), session(10, 20), session(30, 48)))!!, 0.001)
        assertEquals("Guessed 20 min · took 30 min", FocusMath.line(session(20, 30)))
        assertEquals("1 h 15 min", FocusMath.mins(75))
    }

    @Test
    fun `a guess can allow for how things usually go`() {
        assertEquals(30, FocusMath.allowFor(20, 1.5))
        assertNull(FocusMath.allowFor(20, 1.05))
        assertNull(FocusMath.allowFor(20, null))
        assertTrue(FocusMath.describe(1.5).startsWith("Things took about 1.5× as long"))
        assertEquals("Things took about as long as you guessed.", FocusMath.describe(1.0))
    }

    @Test
    fun `about me says it the way the person prefers`() {
        val p = NeuroProfile(identities = listOf("Autistic", "ADHD"))
        assertEquals("I'm autistic and ADHD.", NeuroWords.describe(p))
        assertEquals("I'm a person with autism and ADHD.", NeuroWords.describe(p.copy(wording = Wording.PERSON_FIRST)))
        assertEquals("I'm autistic and ADHD.", NeuroWords.describe(NeuroProfile(identities = listOf("AuDHD"))))
        assertEquals("In my own words: neurospicy.", NeuroWords.describe(NeuroProfile(identities = listOf("neurospicy"))))
        assertNull(NeuroWords.describe(NeuroProfile()))
        assertEquals("Avoid strongly", NeuroWords.response(-2))
        assertEquals("Seek strongly", NeuroWords.response(2))
    }
}
