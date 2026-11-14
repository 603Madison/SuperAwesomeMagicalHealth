package com.health.app.reminders

import com.health.api.BladderTraining
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.ReminderPrefs
import com.health.api.Routine
import com.health.api.RoutineStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reminders when the app starts without the server (an alarm, a restart, the background sync):
 * nothing is forgotten, they're found in the zone they were planned in, and an alarm Android held
 * back still brings what came due meanwhile.
 */
class ReminderRefreshTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long): String = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDateTime().toString()

    // ---- Refreshing before the profile has loaded ----

    @Test
    fun `without the profile, nothing is forgotten`() {
        val plan = ReminderRefresh.of(profile = null, kept = setOf(Module.MEDICATIONS, Module.ACTIVITY))!!
        assertEquals(setOf(Module.MEDICATIONS, Module.ACTIVITY), plan.modules)
        assertTrue(plan.any)
        assertFalse(plan.forget)
        // Not even what the kept features say is off.
        Module.entries.forEach { assertFalse(it.name, plan.forgets(it)) }
    }

    @Test
    fun `with neither the profile nor kept features, the reminders kept carry on as they are`() {
        assertNull(ReminderRefresh.of(profile = null, kept = emptySet()))
    }

    @Test
    fun `only the profile itself can turn reminders off`() {
        val plan = ReminderRefresh.of(profile = setOf(Module.ACTIVITY, Module.HABITS), kept = setOf(Module.MEDICATIONS))!!
        assertEquals(setOf(Module.ACTIVITY, Module.HABITS), plan.modules)
        assertTrue(plan.forgets(Module.MEDICATIONS))
        assertFalse(plan.forgets(Module.HABITS))
        val none = ReminderRefresh.of(profile = Module.DEFAULT, kept = setOf(Module.MEDICATIONS))!!
        assertFalse(none.any)
        assertTrue(none.forget)
    }

    // ---- The time zone reminders are planned in ----

    @Test
    fun `the zone is the profile's, then the one kept, then the phone's`() {
        val phone = ZoneId.of("Asia/Tokyo")
        assertEquals(ZoneId.of("Europe/London"), ReminderRefresh.zone("Europe/London", "America/New_York", phone))
        // A traveller's phone before the profile loads: the zone the reminders were planned in.
        assertEquals(ZoneId.of("America/New_York"), ReminderRefresh.zone(null, "America/New_York", phone))
        assertEquals(ZoneId.of("America/New_York"), ReminderRefresh.zone("", "America/New_York", phone))
        assertEquals(ZoneId.of("America/New_York"), ReminderRefresh.zone("Not/AZone", "America/New_York", phone))
        assertEquals(phone, ReminderRefresh.zone(null, null, phone))
        assertEquals(phone, ReminderRefresh.zone(null, "Nowhere", phone))
    }

    // ---- Routines ----

    @Test
    fun `routines stay put until the profile has loaded`() {
        assertEquals(RoutineAction.KEEP, RoutinePlanner.action(signedIn = true, viewing = false, modules = null))
        assertEquals(RoutineAction.KEEP, RoutinePlanner.action(signedIn = false, viewing = false, modules = setOf(Module.NEURO)))
        assertEquals(RoutineAction.KEEP, RoutinePlanner.action(signedIn = true, viewing = true, modules = emptySet()))
        assertEquals(RoutineAction.CLEAR, RoutinePlanner.action(signedIn = true, viewing = false, modules = Module.DEFAULT))
        assertEquals(RoutineAction.REFRESH, RoutinePlanner.action(signedIn = true, viewing = false, modules = setOf(Module.NEURO)))
    }

    @Test
    fun `a late routine alarm brings the routines due meanwhile, once each`() {
        val alarms = RoutinePlanner.alarmsOf(
            listOf(
                Routine("Getting ready", listOf(RoutineStep("Shower")), reminder = "07:30", id = "r1"),
                Routine("Leave the house", listOf(RoutineStep("Keys")), reminder = "08:15", id = "r2"),
                Routine("Lunch", listOf(RoutineStep("Eat")), reminder = "12:00", id = "r3"),
            ),
        )
        // Set for 7:30, going off at 8:40: both morning routines, not lunch.
        val from = CarePlanner.catchUpFrom(null, at("2026-11-12T07:30"), at("2026-11-12T08:40"))
        assertEquals(listOf("r1", "r2"), RoutinePlanner.dueBetween(alarms, emptyMap(), from, at("2026-11-12T08:40") + 1, zone).map { it.id })
        // One already done today is left out.
        val done = mapOf("2026-11-12" to setOf("r1"))
        assertEquals(listOf("r2"), RoutinePlanner.dueBetween(alarms, done, from, at("2026-11-12T08:40") + 1, zone).map { it.id })
    }

    // ---- Catching up after a late alarm ----

    private val estradiol = Medication("Estradiol", "2 mg", schedule = MedSchedule(times = listOf("08:00", "20:00")), id = "e")

    private fun inputs(
        modules: Set<Module> = setOf(Module.MEDICATIONS, Module.HABITS),
        marked: Set<String> = emptySet(),
        habits: List<HabitReminder> = listOf(HabitReminder("h", "Stretch", "09:00", "2026-11-12", false)),
        bladder: BladderTraining? = null,
    ) = ReminderInputs(
        meds = listOf(estradiol), marked = marked, prefs = ReminderPrefs(quietFrom = null, quietTo = null), modules = modules,
        habits = habits, bladder = bladder,
    )

    @Test
    fun `on time, an alarm shows only what's due then`() {
        val shown = CarePlanner.catchUp(inputs(), lastFired = at("2026-11-11T20:30"), at = at("2026-11-12T08:00"), now = at("2026-11-12T08:00"), zone = zone)
        assertEquals(listOf("2026-11-12T08:00" to AlarmKind.DOSE), shown.map { local(it.at) to it.kind })
    }

    @Test
    fun `a late alarm brings what came due meanwhile`() {
        // Set for the 8:00 dose, held back by the phone until 9:10.
        val shown = CarePlanner.catchUp(inputs(), lastFired = at("2026-11-11T20:30"), at = at("2026-11-12T08:00"), now = at("2026-11-12T09:10"), zone = zone)
        // The dose once (its latest reminder, which replaces the first anyway) and the 9:00 habit.
        assertEquals(listOf("2026-11-12T09:00" to AlarmKind.DOSE_AGAIN, "2026-11-12T09:00" to AlarmKind.HABIT), shown.map { local(it.at) to it.kind })
        assertEquals(at("2026-11-12T08:00"), shown.first().dose!!.at)
    }

    @Test
    fun `what already went off isn't shown again`() {
        val shown = CarePlanner.catchUp(inputs(), lastFired = at("2026-11-12T08:00"), at = at("2026-11-12T08:30"), now = at("2026-11-12T08:30"), zone = zone)
        assertEquals(listOf("2026-11-12T08:30" to AlarmKind.DOSE_AGAIN), shown.map { local(it.at) to it.kind })
        // A dose marked meanwhile has nothing to catch up.
        val marked = setOf(CarePlanner.doseKey("e", at("2026-11-12T08:00")))
        assertTrue(CarePlanner.catchUp(inputs(marked = marked, habits = emptyList()), at("2026-11-12T08:00"), at("2026-11-12T08:30"), at("2026-11-12T08:45"), zone).isEmpty())
    }

    @Test
    fun `a phone that was off overnight brings only the last two hours`() {
        val habits = listOf(HabitReminder("h", "Stretch", "21:00", "2026-11-11", false))
        val shown = CarePlanner.catchUp(inputs(habits = habits), lastFired = at("2026-11-11T20:30"), at = at("2026-11-11T21:00"), now = at("2026-11-12T09:10"), zone = zone)
        assertTrue(shown.all { it.at >= at("2026-11-12T07:10") })
        assertEquals(listOf("2026-11-12T09:00" to AlarmKind.DOSE_AGAIN), shown.map { local(it.at) to it.kind })
        assertEquals(at("2026-11-12T07:10"), CarePlanner.catchUpFrom(at("2026-11-11T20:30"), at("2026-11-11T21:00"), at("2026-11-12T09:10")))
    }

    @Test
    fun `bladder training's missed trips come as one, the latest`() {
        val training = BladderTraining(enabled = true, intervalMinutes = 30, from = "08:00", to = "21:00", stepMinutes = 0)
        val shown = CarePlanner.catchUp(
            inputs(modules = setOf(Module.CONTINENCE), habits = emptyList(), bladder = training),
            lastFired = at("2026-11-12T08:00"), at = at("2026-11-12T08:30"), now = at("2026-11-12T09:40"), zone = zone,
        )
        assertEquals(listOf("2026-11-12T09:30" to AlarmKind.BLADDER), shown.map { local(it.at) to it.kind })
    }

    // ---- Snoozed doses ----

    @Test
    fun `a snoozed dose marked meanwhile doesn't come back`() {
        val dose = PlannedDose("e", "Estradiol", "2 mg", com.health.api.MedForm.PILL, at("2026-11-12T08:00"))
        assertTrue(CarePlanner.stillDue(dose, inputs()))
        assertFalse(CarePlanner.stillDue(dose, inputs(marked = setOf(CarePlanner.doseKey("e", dose.at)))))
        // Nor once Medications is off, or the medication's gone or paused.
        assertFalse(CarePlanner.stillDue(dose, inputs(modules = setOf(Module.HABITS))))
        assertFalse(CarePlanner.stillDue(dose.copy(medicationId = "gone"), inputs()))
        assertFalse(CarePlanner.stillDue(dose, inputs().copy(meds = listOf(estradiol.copy(active = false)))))
    }

    @Test
    fun `a snoozed dose is found again from its key`() {
        val dose = Reminders.doseOfKey(CarePlanner.doseKey("med@home", 1_700_000_000_000))
        assertNotNull(dose)
        assertEquals("med@home", dose!!.medicationId)
        assertEquals(1_700_000_000_000, dose.at)
        assertNull(Reminders.doseOfKey("no time"))
    }
}
