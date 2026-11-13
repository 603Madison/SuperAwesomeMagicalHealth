package com.health.app.reminders

import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.RoutineStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RoutinePlannerTest {
    private val zone = ZoneId.of("Europe/London")
    private val thursday = LocalDate.of(2026, 11, 12)
    private fun at(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val morning = Routine("Getting ready", listOf(RoutineStep("Shower", 10), RoutineStep("Dress", 5)), reminder = "07:30", id = "r1")
    private val weekdays = Routine("Leave the house", listOf(RoutineStep("Keys")), reminder = "08:15", days = listOf(1, 2, 3, 4, 5), id = "r2")
    private val paused = Routine("Wind down", listOf(RoutineStep("Lights low")), reminder = "21:00", active = false, id = "r3")
    private val quiet = Routine("No reminder", listOf(RoutineStep("Tea")), id = "r4")
    private val alarms = RoutinePlanner.alarmsOf(listOf(morning, weekdays, paused, quiet))

    @Test
    fun `only active routines with a time remind`() {
        assertEquals(listOf("r1", "r2"), alarms.map { it.id })
        assertEquals(15, alarms.first().minutes)
        assertEquals("2 steps, about 15 minutes. One step at a time.", RoutinePlanner.body(alarms.first()))
    }

    @Test
    fun `the next reminder is the soonest not yet done`() {
        val (next, due) = RoutinePlanner.next(alarms, emptyMap(), at(thursday, 6), zone)!!
        assertEquals(at(thursday, 7, 30), next)
        assertEquals(listOf("r1"), due.map { it.id })
        // Done already today: that one's skipped.
        val done = mapOf(thursday.toString() to setOf("r1"))
        assertEquals(at(thursday, 8, 15), RoutinePlanner.next(alarms, done, at(thursday, 6), zone)!!.first)
    }

    @Test
    fun `weekday routines wait for Monday at the weekend`() {
        val saturday = thursday.plusDays(2)
        val after = at(saturday, 7, 45)
        val (next, due) = RoutinePlanner.next(alarms, emptyMap(), after, zone)!!
        // Sunday's getting ready comes first; leaving the house waits for Monday.
        assertEquals(at(saturday.plusDays(1), 7, 30), next)
        assertEquals(listOf("r1"), due.map { it.id })
        assertTrue(RoutinePlanner.dueAt(alarms, emptyMap(), at(saturday, 8, 15), zone).isEmpty())
        assertEquals(listOf("r2"), RoutinePlanner.dueAt(alarms, emptyMap(), at(saturday.plusDays(2), 8, 15), zone).map { it.id })
    }

    @Test
    fun `a routine is done when every step is`() {
        val runs = listOf(RoutineRun("r1", thursday.toString(), listOf(0, 1)), RoutineRun("r2", thursday.toString(), emptyList()), RoutineRun("r1", thursday.minusDays(1).toString(), listOf(0)))
        val done = RoutinePlanner.doneOf(listOf(morning, weekdays), runs)
        assertEquals(mapOf(thursday.toString() to setOf("r1")), done)
        assertNull(RoutinePlanner.next(emptyList(), emptyMap(), at(thursday, 6), zone))
    }

    @Test
    fun `a running focus session chimes on the minute it's due`() {
        val focus = RunningFocus(start = 0, chimeEveryMinutes = 25)
        assertEquals(25 * 60_000L, focus.nextChime(60_000))
        assertEquals(50 * 60_000L, focus.nextChime(25 * 60_000L))
        assertNull(focus.copy(chimeEveryMinutes = null).nextChime(0))
    }
}
