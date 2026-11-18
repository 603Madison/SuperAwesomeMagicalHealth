package com.health.app.ui.modules.neuro

import com.health.api.CheckTag
import com.health.api.EnergyCheck
import com.health.api.EnergyFactor
import com.health.api.EnergyFocus
import com.health.api.EnergyFocusSettings
import com.health.api.FactorKind
import com.health.api.Fields
import com.health.api.Medication
import com.health.api.Module
import com.health.api.TimeBlock
import com.health.app.ui.modules.neuro.EnergyFocusMath.MarkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Energy and focus without a phone: the words, today's strip, the patterns' sums and the settings. */
class EnergyFocusMathTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 11, 17)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun String.plain() = replace('\u202F', ' ').replace('\u00A0', ' ')
    private val clock: (Long) -> String = { DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(java.time.Instant.ofEpochMilli(it).atZone(zone)) }

    @Test
    fun `the strip runs from 6 AM to midnight`() {
        assertEquals(0f, EnergyFocusMath.stripX(at(6), today, zone)!!, 0.0001f)
        assertEquals(1f / 3, EnergyFocusMath.stripX(at(12), today, zone)!!, 0.0001f)
        assertEquals(2f / 3, EnergyFocusMath.stripX(at(18), today, zone)!!, 0.0001f)
        assertEquals(0.5f + 0.25f / 18, EnergyFocusMath.stripX(at(15, 15), today, zone)!!, 0.0001f)
        assertEquals(0.999f, EnergyFocusMath.stripX(at(23, 59), today, zone)!!, 0.001f)
        // The small hours, and other days, aren't on it.
        assertNull(EnergyFocusMath.stripX(at(5, 59), today, zone))
        assertNull(EnergyFocusMath.stripX(at(9, day = today.minusDays(1)), today, zone))
        // Higher is more: 5 at the top, 1 at the bottom.
        assertEquals(0f, EnergyFocusMath.stripY(5), 0f)
        assertEquals(0.5f, EnergyFocusMath.stripY(3), 0f)
        assertEquals(1f, EnergyFocusMath.stripY(1), 0f)
        assertEquals(1f, EnergyFocusMath.stripY(0), 0f)
    }

    @Test
    fun `today's marks are each check-in's energy and focus, and each dose`() {
        val checks = listOf(
            EnergyCheck(at(14), energy = 2),
            EnergyCheck(at(9, 45), energy = 4, focus = 2),
            EnergyCheck(at(2), focus = 1),
        )
        val marks = EnergyFocusMath.marks(checks, listOf(at(8)), today, zone)
        assertEquals(listOf(MarkKind.DOSE, MarkKind.ENERGY, MarkKind.FOCUS, MarkKind.ENERGY), marks.map { it.kind })
        assertEquals(listOf(null, 0.25f, 0.75f, 0.75f), marks.map { it.y })
        assertEquals(listOf(null, 4, 2, 2), marks.map { it.value })
        // A dose has no height: it's a line across the strip.
        assertEquals(2f / 18, marks.first().x, 0.0001f)
        // The 2 AM check-in is before the strip: said in words instead.
        assertEquals(1, EnergyFocusMath.beforeStrip(checks, today, zone))
    }

    @Test
    fun `a check-in in words`() {
        val c = EnergyCheck(at(9, 45), energy = 4, focus = 2, tags = setOf(CheckTag.HYPERFOCUS, CheckTag.STRESSED), note = " Big meeting ")
        assertEquals("Energy 4 · focus 2", EnergyFocusMath.ratings(c))
        assertEquals("Energy 4 · focus 2 at 9:45 AM", EnergyFocusMath.checkLine(c, clock))
        assertEquals("Focus 3 at 9:45 AM", EnergyFocusMath.checkLine(EnergyCheck(at(9, 45), focus = 3), clock))
        assertNull(EnergyFocusMath.checkLine(EnergyCheck(at(9, 45)), clock))
        assertEquals("Stressed, in a hyperfocus · Big meeting", EnergyFocusMath.detail(c))
        assertNull(EnergyFocusMath.detail(EnergyCheck(at(9))))
        assertEquals("9:45 AM: energy 4 of 5, focus 2 of 5, stressed, in a hyperfocus", EnergyFocusMath.spoken(c, clock))
        // Each circle says its number and, at the ends, the plain words.
        assertEquals("Energy 1 of 5, running on empty", EnergyFocusMath.optionSpoken("Energy", 1, EnergyFocusMath.ENERGY_LOW, EnergyFocusMath.ENERGY_HIGH))
        assertEquals("Focus 5 of 5, locked in", EnergyFocusMath.optionSpoken("Focus", 5, EnergyFocusMath.FOCUS_LOW, EnergyFocusMath.FOCUS_HIGH))
        assertEquals("Focus 3 of 5", EnergyFocusMath.optionSpoken("Focus", 3, EnergyFocusMath.FOCUS_LOW, EnergyFocusMath.FOCUS_HIGH))
        // Either one is enough to save.
        assertTrue(EnergyFocusMath.canSave(4, null))
        assertTrue(EnergyFocusMath.canSave(null, 2))
        assertFalse(EnergyFocusMath.canSave(null, null))
    }

    @Test
    fun `the words are plain, kind and never a cause`() {
        val words = listOf(
            EnergyFocusMath.ENERGY_LOW, EnergyFocusMath.ENERGY_HIGH, EnergyFocusMath.FOCUS_LOW, EnergyFocusMath.FOCUS_HIGH, EnergyFocusMath.CAPTION,
            EnergyFocusMath.soFar(0, 0), EnergyFocusMath.soFar(5, 3), EnergyFocusMath.medicationLine(EnergyFocus()),
        ) + CheckTag.entries.map { it.label }
        assertEquals(listOf("Running on empty", "Plenty", "All over the place", "Locked in"), words.take(4))
        assertEquals("Patterns in your own check-ins, not proof of cause.", EnergyFocusMath.CAPTION)
        words.forEach { w ->
            val lower = w.lowercase()
            listOf("spoon", "because", "causes", "symptom", "disorder", " she ", " he ", " her ", " his ").forEach { assertFalse("$w says $it", it in " $lower ") }
        }
    }

    @Test
    fun `patterns show progress until there's enough`() {
        assertEquals(0f, EnergyFocusMath.progress(0, 0), 0f)
        assertEquals(0.5f * 6 / 12 + 0.5f * 2 / 5, EnergyFocusMath.progress(6, 2), 0.0001f)
        assertEquals(1f, EnergyFocusMath.progress(40, 9), 0f)
        assertEquals("5 check-ins over 3 days so far", EnergyFocusMath.soFar(5, 3))
        assertEquals("1 check-in over 1 day so far", EnergyFocusMath.soFar(1, 1))
        assertEquals("From 34 check-ins over 12 days.", EnergyFocusMath.basedOn(34, 12))
    }

    @Test
    fun `the summary leaves out what the factors say themselves`() {
        val sleep = EnergyFactor(FactorKind.SLEEP, "Sleep", "After 7 hours' sleep or more, your energy averaged 3.6, against 2.4 after less than 6 (9 and 5 days).", "energy", 3.6, 2.4, 9, 5)
        val ef = EnergyFocus(enough = true, summary = listOf("Your focus is usually best 9 AM – noon: a good time for whatever needs it most.", sleep.sentence), factors = listOf(sleep))
        assertEquals(listOf("Your focus is usually best 9 AM – noon: a good time for whatever needs it most."), EnergyFocusMath.summaryLines(ef))
    }

    @Test
    fun `hours are said as the server says them`() {
        assertEquals("9 AM – noon", EnergyFocusMath.range(9, 12))
        assertEquals("3 – 6 PM", EnergyFocusMath.range(15, 18))
        assertEquals("6 – 9 AM", EnergyFocusMath.range(6, 9))
        assertEquals("noon – 3 PM", EnergyFocusMath.range(12, 15))
        assertEquals("9 PM – midnight", EnergyFocusMath.range(21, 24))
        assertEquals("midnight – 6 AM", EnergyFocusMath.range(0, 6))
        assertEquals("Midnight – 6 AM", EnergyFocusMath.dayBlock(TimeBlock(0, 6)))
        assertEquals("Noon – 3 PM", EnergyFocusMath.dayBlock(TimeBlock(12, 15)))
        assertEquals("Up to 1 hour", EnergyFocusMath.doseBlock(TimeBlock(0, 1)))
        assertEquals("1–3 hours", EnergyFocusMath.doseBlock(TimeBlock(1, 3)))
        assertEquals("Since your Ritalin", EnergyFocusMath.sinceTitle("Ritalin"))
        assertEquals("Since your ADHD medication", EnergyFocusMath.sinceTitle(null))
        assertEquals("9 AM – noon: energy 3.6, focus 4.1, from 8 check-ins", EnergyFocusMath.blockSpoken("9 AM – noon", TimeBlock(9, 12, 3.6, 4.1, 8)))
        assertEquals("3 – 6 PM: focus 2.0, from 1 check-in", EnergyFocusMath.blockSpoken("3 – 6 PM", TimeBlock(15, 18, null, 2.0, 1)))
        assertEquals(0f, EnergyFocusMath.scaleX(1.0), 0f)
        assertEquals(0.65f, EnergyFocusMath.scaleX(3.6), 0.0001f)
        assertEquals(1f, EnergyFocusMath.scaleX(7.0), 0f)
    }

    @Test
    fun `the ADHD medication is chosen from the person's own`() {
        val meds = listOf(
            Medication("Vitamin D", id = "d"),
            Medication("Ritalin", "10 mg", id = "r"),
            Medication("Concerta", active = false, id = "c"),
            Medication("No id"),
        )
        // Those in use, by name; a stopped one only when it's the chosen one.
        assertEquals(listOf("r", "d"), EnergyFocusMath.choices(meds, null).map { it.id })
        assertEquals(listOf("c", "r", "d"), EnergyFocusMath.choices(meds, "c").map { it.id })
        // Found by its name: that's the one picked out; chosen: that one; neither: none.
        val found = EnergyFocus(medication = "Ritalin")
        assertEquals("r", EnergyFocusMath.selected(EnergyFocusMath.choices(meds, null), found))
        assertEquals("Ritalin, found by its name", EnergyFocusMath.medicationLine(found))
        val chosen = EnergyFocus(settings = EnergyFocusSettings(medicationId = "d"), medication = "Vitamin D")
        assertEquals("d", EnergyFocusMath.selected(EnergyFocusMath.choices(meds, "d"), chosen))
        assertEquals("Vitamin D", EnergyFocusMath.medicationLine(chosen))
        assertNull(EnergyFocusMath.selected(EnergyFocusMath.choices(meds, null), EnergyFocus()))
        assertEquals("None chosen", EnergyFocusMath.medicationLine(EnergyFocus()))
        // "None of these" sticks: nothing is picked out, even a name the server would otherwise find.
        val none = EnergyFocus(settings = EnergyFocusSettings(noMedication = true))
        assertNull(EnergyFocusMath.selected(EnergyFocusMath.choices(meds, null), none.copy(medication = "Ritalin")))
        assertEquals("None of these", EnergyFocusMath.medicationLine(none))
        // Choosing saves the one chosen; "None of these" saves none, and says so.
        val reminders = EnergyFocusSettings(noMedication = true, reminders = listOf("10:00"))
        assertEquals(EnergyFocusSettings(medicationId = "r", noMedication = false, reminders = listOf("10:00")), EnergyFocusMath.choose(reminders, "r"))
        assertEquals(EnergyFocusSettings(medicationId = null, noMedication = true, reminders = listOf("10:00")), EnergyFocusMath.choose(reminders.copy(medicationId = "r", noMedication = false), null))
    }

    @Test
    fun `reminders are optional times, in order, at most six`() {
        assertEquals(listOf("10:00"), EnergyFocusMath.withReminder(emptyList(), LocalTime.of(10, 0)))
        assertEquals(listOf("09:30", "10:00"), EnergyFocusMath.withReminder(listOf("10:00"), LocalTime.of(9, 30)))
        assertEquals(listOf("10:00"), EnergyFocusMath.withReminder(listOf("10:00"), LocalTime.of(10, 0)))
        val six = (8..13).map { "%02d:00".format(it) }
        assertEquals(six, EnergyFocusMath.withReminder(six, LocalTime.of(20, 0)))
        assertEquals(listOf("09:30"), EnergyFocusMath.withoutReminder(listOf("09:30", "10:00"), "10:00"))
        assertEquals("10:00 AM", EnergyFocusMath.reminderText("10:00").plain())
        assertEquals("3:30 PM", EnergyFocusMath.reminderText("15:30").plain())
        assertEquals("soon", EnergyFocusMath.reminderText("soon"))
    }

    @Test
    fun `logging energy is the quick check-in only with autism and ADHD's check-ins, and mind's budget only without autism and ADHD`() {
        val fields = setOf(Fields.NEURO_ENERGY, Fields.MIND_ENERGY_BUDGET)
        assertTrue(quickCheckIn(setOf(Module.NEURO, Module.MIND), fields))
        assertFalse(quickCheckIn(setOf(Module.NEURO), emptySet()))
        assertFalse(quickCheckIn(setOf(Module.MIND), fields))
        assertTrue(mindBudget(setOf(Module.MIND), fields))
        assertFalse(mindBudget(setOf(Module.MIND, Module.NEURO), fields))
        assertFalse(mindBudget(setOf(Module.MIND), setOf(Fields.NEURO_ENERGY)))
        assertFalse(mindBudget(emptySet(), fields))
    }
}
