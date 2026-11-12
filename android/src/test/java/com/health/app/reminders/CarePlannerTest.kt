package com.health.app.reminders

import com.health.api.Appointment
import com.health.api.DigestPrefs
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.ReminderPrefs
import com.health.api.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CarePlannerTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long): String = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDateTime().toString()

    private val estradiol = Medication("Estradiol", "2 mg", schedule = MedSchedule(times = listOf("08:00", "20:00")), stock = 30, id = "e")
    private val meds = setOf(Module.MEDICATIONS)

    private fun inputs(
        prefs: ReminderPrefs = ReminderPrefs(quietFrom = null, quietTo = null),
        marked: Set<String> = emptySet(),
        modules: Set<Module> = meds,
        medsList: List<Medication> = listOf(estradiol),
        habits: List<HabitReminder> = emptyList(),
        appointments: List<Appointment> = emptyList(),
    ) = ReminderInputs(medsList, marked, prefs, modules, habits, appointments)

    @Test
    fun `a dose reminds at its time, then again while it isn't marked`() {
        val plan = CarePlanner.plan(inputs(), at("2026-11-12T07:00"), at("2026-11-12T12:00"), zone)
        assertEquals(
            listOf("2026-11-12T08:00" to AlarmKind.DOSE, "2026-11-12T08:30" to AlarmKind.DOSE_AGAIN, "2026-11-12T09:00" to AlarmKind.DOSE_AGAIN),
            plan.map { local(it.at) to it.kind },
        )
        // Every one of them is for the 8:00 dose, so Taken records the right one.
        assertTrue(plan.all { it.dose?.at == at("2026-11-12T08:00") })
        assertEquals("Still to take: Estradiol", plan[1].title)
        assertEquals("Still to take: your 8:00 medications", plan[1].privateTitle)
        assertFalse(plan[0].privateTitle.contains("Estradiol"))
    }

    @Test
    fun `marking a dose stops its reminders`() {
        val marked = setOf(CarePlanner.doseKey("e", at("2026-11-12T08:00")))
        val plan = CarePlanner.plan(inputs(marked = marked), at("2026-11-12T07:00"), at("2026-11-12T12:00"), zone)
        assertTrue(plan.isEmpty())
        // The evening one still comes.
        assertEquals(at("2026-11-12T20:00"), CarePlanner.next(inputs(marked = marked), at("2026-11-12T08:10"), zone)!!.first)
    }

    @Test
    fun `remind again follows the person's settings`() {
        val once = inputs(prefs = ReminderPrefs(remindAgainMinutes = 15, remindAgainTimes = 1, quietFrom = null, quietTo = null))
        assertEquals(listOf("2026-11-12T08:00", "2026-11-12T08:15"), CarePlanner.plan(once, at("2026-11-12T07:00"), at("2026-11-12T12:00"), zone).map { local(it.at) })
        val never = inputs(prefs = ReminderPrefs(remindAgainMinutes = 0, quietFrom = null, quietTo = null))
        assertEquals(listOf("2026-11-12T08:00"), CarePlanner.plan(never, at("2026-11-12T07:00"), at("2026-11-12T12:00"), zone).map { local(it.at) })
    }

    @Test
    fun `a reminder again can come after the window's dose is past`() {
        // At 8:20, the 8:00 dose's reminder at 8:30 is still ahead.
        val next = CarePlanner.next(inputs(), at("2026-11-12T08:20"), zone)!!
        assertEquals("2026-11-12T08:30", local(next.first))
        assertEquals(AlarmKind.DOSE_AGAIN, next.second.single().kind)
    }

    @Test
    fun `refills come at the set time when doses left run low`() {
        val prefs = ReminderPrefs(refillDays = 7, refillTime = "10:00", quietFrom = null, quietTo = null, remindAgainMinutes = 0)
        // 30 doses at 2 a day: 15 days, so no refill reminder yet.
        assertTrue(CarePlanner.plan(inputs(prefs = prefs), at("2026-11-12T09:00"), at("2026-11-12T11:00"), zone).none { it.kind == AlarmKind.REFILL })
        val low = estradiol.copy(stock = 10)
        val refill = CarePlanner.plan(inputs(prefs = prefs, medsList = listOf(low)), at("2026-11-12T09:00"), at("2026-11-12T11:00"), zone).single { it.kind == AlarmKind.REFILL }
        assertEquals("2026-11-12T10:00", local(refill.at))
        assertEquals("Running low on Estradiol", refill.title)
        assertEquals("About 5 days left. Time to refill.", refill.body)
        assertFalse(refill.privateTitle.contains("Estradiol"))
        val out = CarePlanner.plan(inputs(prefs = prefs, medsList = listOf(low.copy(stock = 0))), at("2026-11-12T09:00"), at("2026-11-12T11:00"), zone).single { it.kind == AlarmKind.REFILL }
        assertEquals("Out of Estradiol", out.title)
        // Off, or not counted: none.
        assertTrue(CarePlanner.plan(inputs(prefs = prefs.copy(refillDays = 0), medsList = listOf(low)), at("2026-11-12T09:00"), at("2026-11-12T11:00"), zone).none { it.kind == AlarmKind.REFILL })
        assertNull(CarePlanner.daysLeft(low.copy(stock = null), zone, LocalDate.of(2026, 11, 12)))
    }

    @Test
    fun `as-needed medications never run out on a schedule`() {
        val ibuprofen = Medication("Ibuprofen", schedule = MedSchedule(ScheduleKind.AS_NEEDED, emptyList()), stock = 2, id = "i")
        assertEquals(0.0, CarePlanner.perDay(ibuprofen, zone, LocalDate.of(2026, 11, 12)), 0.0)
        assertTrue(CarePlanner.plan(inputs(medsList = listOf(ibuprofen)), at("2026-11-12T00:00"), at("2026-11-13T00:00"), zone).isEmpty())
    }

    @Test
    fun `a habit not ticked by its time gets a nudge, and one that's done doesn't`() {
        val habits = listOf(
            HabitReminder("w", "Evening walk", "18:30", "2026-11-12", done = false),
            HabitReminder("s", "Stretch", "09:00", "2026-11-12", done = true),
        )
        val modules = setOf(Module.HABITS)
        val plan = CarePlanner.plan(inputs(modules = modules, habits = habits), at("2026-11-12T08:00"), at("2026-11-13T12:00"), zone)
        assertEquals(
            listOf("2026-11-12T18:30" to "Evening walk", "2026-11-13T09:00" to "Stretch"),
            plan.map { local(it.at) to it.title },
        )
        // Turned off in the settings: none.
        val off = inputs(prefs = ReminderPrefs(habits = false), modules = modules, habits = habits)
        assertTrue(CarePlanner.plan(off, at("2026-11-12T08:00"), at("2026-11-13T12:00"), zone).isEmpty())
    }

    @Test
    fun `appointments remind before, without names on a locked screen`() {
        val appointment = Appointment(at("2026-11-14T09:30"), "Check-up", with = "Dr Patel", place = "Health centre", remindBefore = listOf(1440, 60), id = "a")
        val plan = CarePlanner.plan(inputs(modules = setOf(Module.APPOINTMENTS), appointments = listOf(appointment)), at("2026-11-12T00:00"), at("2026-11-15T00:00"), zone)
        assertEquals(listOf("2026-11-13T09:30", "2026-11-14T08:30"), plan.map { local(it.at) })
        assertEquals("Check-up with Dr Patel tomorrow at 9:30", plan[0].title)
        assertEquals("Check-up with Dr Patel in 1 hour", plan[1].title)
        assertEquals("An appointment in 1 hour", plan[1].privateTitle)
        assertEquals("Health centre", plan[1].body)
    }

    @Test
    fun `quiet hours quiet everything but doses, past midnight too`() {
        val prefs = ReminderPrefs(quietFrom = "22:00", quietTo = "07:00")
        assertTrue(CarePlanner.quiet(prefs, at("2026-11-12T23:30"), zone))
        assertTrue(CarePlanner.quiet(prefs, at("2026-11-13T06:59"), zone))
        assertFalse(CarePlanner.quiet(prefs, at("2026-11-13T07:00"), zone))
        assertFalse(CarePlanner.quiet(ReminderPrefs(quietFrom = null, quietTo = null), at("2026-11-12T23:30"), zone))

        val late = estradiol.copy(schedule = MedSchedule(times = listOf("23:00")), stock = 1)
        val plan = CarePlanner.plan(
            inputs(prefs = prefs.copy(refillTime = "23:00", remindAgainMinutes = 0), medsList = listOf(late)),
            at("2026-11-12T22:00"), at("2026-11-12T23:30"), zone,
        )
        assertFalse(plan.single { it.kind == AlarmKind.DOSE }.silent)
        assertTrue(plan.single { it.kind == AlarmKind.REFILL }.silent)
    }

    @Test
    fun `nothing is planned for features that are off`() {
        assertTrue(CarePlanner.plan(inputs(modules = emptySet()), at("2026-11-12T00:00"), at("2026-11-13T00:00"), zone).isEmpty())
    }

    @Test
    fun `the digest's check comes just after the chosen time`() {
        val now = ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, zone) // a Thursday
        assertEquals(Duration.ofDays(3).plusHours(8).plusMinutes(20), com.health.app.reminders.DigestWorker.delayUntil(DigestPrefs(day = 7, hour = 18), now))
        // Later the same day.
        assertEquals(Duration.ofHours(8).plusMinutes(20), com.health.app.reminders.DigestWorker.delayUntil(DigestPrefs(day = 4, hour = 18), now))
        // Just past it: next week.
        assertEquals(Duration.ofDays(7).minusMinutes(40), com.health.app.reminders.DigestWorker.delayUntil(DigestPrefs(day = 4, hour = 9), now))
    }
}
