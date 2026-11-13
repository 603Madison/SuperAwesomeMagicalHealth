package com.health.app.reminders

import com.health.api.BladderTraining
import com.health.api.Module
import com.health.api.ReminderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BladderTrainingPlanTest {
    private val zone = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 11, 12)
    private fun at(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString()
    private val training = BladderTraining(enabled = true, intervalMinutes = 60, from = "08:00", to = "12:00", stepMinutes = 15, stepEveryDays = 7, goalMinutes = 90, startedOn = "2026-11-01")

    @Test
    fun `the gap grows a step every so many days, up to the goal`() {
        assertEquals(60, BladderTrainingPlan.interval(training, LocalDate.of(2026, 11, 1)))
        assertEquals(60, BladderTrainingPlan.interval(training, LocalDate.of(2026, 11, 7)))
        assertEquals(75, BladderTrainingPlan.interval(training, LocalDate.of(2026, 11, 8)))
        assertEquals(90, BladderTrainingPlan.interval(training, LocalDate.of(2026, 11, 15)))
        assertEquals(90, BladderTrainingPlan.interval(training, LocalDate.of(2027, 1, 1)))
        // Not started yet counts as the first day.
        assertEquals(60, BladderTrainingPlan.interval(training.copy(startedOn = null), day))
    }

    @Test
    fun `reminders come one gap after the start of the day, then every gap until its end`() {
        val times = BladderTrainingPlan.times(training, at(day, 0), at(day.plusDays(1), 0), zone)
        assertEquals(listOf("09:15", "10:30", "11:45"), times.map { clock(it.first) })
        assertTrue(times.all { it.second == 75 })
        assertTrue(BladderTrainingPlan.times(training.copy(enabled = false), at(day, 0), at(day.plusDays(1), 0), zone).isEmpty())
    }

    @Test
    fun `on a locked screen they say nothing about the bladder`() {
        val alarms = BladderTrainingPlan.alarms(training, at(day, 0), at(day.plusDays(1), 0), zone) { false }
        assertEquals(3, alarms.size)
        val a = alarms.first()
        assertEquals(AlarmKind.BLADDER, a.kind)
        assertEquals("Time for a toilet trip", a.title)
        assertTrue(a.body.contains("every 1 h 15 min"))
        assertEquals("Reminder", a.privateTitle)
        assertEquals("Time for your planned break.", a.privateBody)
    }

    @Test
    fun `the phone's planner includes them only with the diary on, and quiet hours keep them silent`() {
        val inputs = ReminderInputs(modules = setOf(Module.CONTINENCE), bladder = training, prefs = ReminderPrefs(quietFrom = "11:00", quietTo = "13:00"))
        val planned = CarePlanner.plan(inputs, at(day, 0), at(day.plusDays(1), 0), zone)
        assertEquals(listOf(false, false, true), planned.map { it.silent })
        assertTrue(CarePlanner.plan(inputs.copy(modules = emptySet()), at(day, 0), at(day.plusDays(1), 0), zone).isEmpty())
        assertEquals(at(day, 9, 15), CarePlanner.next(inputs, at(day, 8), zone)!!.first)
    }

    @Test
    fun `gaps are said as the server says them`() {
        assertEquals("hour", BladderTrainingPlan.say(60))
        assertEquals("2 hours", BladderTrainingPlan.say(120))
        assertEquals("1 h 15 min", BladderTrainingPlan.say(75))
        assertEquals("45 minutes", BladderTrainingPlan.say(45))
        assertEquals("Every 1 h 15 min today, 08:00 to 12:00", BladderTrainingPlan.today(training, day))
    }

    @Test
    fun `a change reminder comes when it's due, in the same discreet words, never naming the product`() {
        val since = at(day, 8)
        val due = at(day, 14)
        val inputs = ReminderInputs(modules = setOf(Module.CONTINENCE), changeDueAt = due, wornSince = since)
        val planned = CarePlanner.plan(inputs, at(day, 0), at(day.plusDays(1), 0), zone).single()
        assertEquals(AlarmKind.CHANGE, planned.kind)
        assertEquals(due, planned.at)
        assertEquals("Time for a check", planned.title)
        assertEquals("About 6 hours since your last change.", planned.body)
        assertEquals(planned.title, planned.privateTitle)
        assertEquals(planned.body, planned.privateBody)
        assertEquals("continence", Reminders.routeOf(AlarmKind.CHANGE))
        // None without the diary, none once it's passed.
        assertTrue(CarePlanner.plan(inputs.copy(modules = emptySet()), at(day, 0), at(day.plusDays(1), 0), zone).isEmpty())
        assertTrue(CarePlanner.plan(inputs, due + 60_000, at(day.plusDays(1), 0), zone).isEmpty())
    }
}
