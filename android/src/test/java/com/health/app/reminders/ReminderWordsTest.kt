package com.health.app.reminders

import com.health.api.Appointment
import com.health.api.FocusKind
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.ReminderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * With names off (the default), a reminder's own words never name a medication, habit, routine,
 * focus task or appointment: Android's default lock screen shows a notification's full content,
 * so the discreet words have to be the content itself, not only a lock-screen version.
 */
class ReminderWordsTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val shot = Medication("Testosterone", "50 mg", form = MedForm.INJECTION, schedule = MedSchedule(times = listOf("08:00")), id = "t")
    private val appointment = Appointment(at("2026-11-14T09:30"), "Gender clinic", with = "Dr Patel", place = "Riverside", remindBefore = listOf(60), id = "a")

    private val alarms: List<PlannedAlarm> by lazy {
        val inputs = ReminderInputs(
            meds = listOf(shot), prefs = ReminderPrefs(quietFrom = null, quietTo = null, refillDays = 0),
            modules = setOf(Module.MEDICATIONS, Module.HABITS, Module.APPOINTMENTS),
            habits = listOf(HabitReminder("h", "Pelvic floor exercises", "12:00", "2026-11-14", false)),
            appointments = listOf(appointment),
        )
        CarePlanner.plan(inputs, at("2026-11-14T00:00"), at("2026-11-15T00:00"), zone)
    }

    private val names = listOf("Testosterone", "50 mg", "Left thigh", "Gender clinic", "Dr Patel", "Riverside", "Pelvic floor")

    @Test
    fun `with names off, nothing in a reminder names what it's for`() {
        listOf(AlarmKind.DOSE, AlarmKind.DOSE_AGAIN, AlarmKind.HABIT, AlarmKind.APPOINTMENT).forEach { kind ->
            val alarm = alarms.first { it.kind == kind }
            val (title, body) = Reminders.words(alarm, site = "Left thigh", showNames = false)
            names.forEach { name -> assertFalse("$kind: $title / $body", "$title $body".contains(name)) }
        }
        val dose = alarms.first { it.kind == AlarmKind.DOSE }
        assertEquals("Time for your 8:00 medications" to "Open the app to see what's due.", Reminders.words(dose, "Left thigh", showNames = false))
    }

    @Test
    fun `with names on, a reminder says what it's for`() {
        val dose = alarms.first { it.kind == AlarmKind.DOSE }
        assertEquals("Time for Testosterone" to "50 mg · due at 8:00 · next site: Left thigh", Reminders.words(dose, "Left thigh", showNames = true))
        val visit = alarms.first { it.kind == AlarmKind.APPOINTMENT }
        assertEquals("Gender clinic with Dr Patel in 1 hour" to "Riverside", Reminders.words(visit, null, showNames = true))
        assertEquals("An appointment in 1 hour" to "Open the app for the details.", Reminders.words(visit, null, showNames = false))
    }

    @Test
    fun `routines are named only with names on`() {
        val routine = RoutineAlarm("r", "Evening meds and wind down", "21:00", steps = 3, minutes = 20)
        assertEquals("A routine reminder" to "One of your routines is ready when you are.", RoutinePlanner.words(routine, showNames = false))
        assertEquals("Time for: Evening meds and wind down" to "3 steps, about 20 minutes. One step at a time.", RoutinePlanner.words(routine, showNames = true))
    }

    @Test
    fun `the focus timer names its task only with names on`() {
        val focus = RunningFocus(start = 0, kind = FocusKind.FOCUS, task = "Call the clinic", estimateMinutes = 25)
        assertEquals("Focus timer" to "you guessed 25 min", FocusAlerts.words(focus, showNames = false))
        assertEquals("Call the clinic" to "Focus timer · you guessed 25 min", FocusAlerts.words(focus, showNames = true))
        assertEquals("Body doubling" to "Tap to finish when you're ready.", FocusAlerts.words(RunningFocus(start = 0, kind = FocusKind.BODY_DOUBLING), showNames = true))
    }
}
