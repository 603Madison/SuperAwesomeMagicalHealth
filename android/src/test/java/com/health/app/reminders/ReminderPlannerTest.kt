package com.health.app.reminders

import com.health.api.ApiJson
import com.health.api.DoseLog
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.ScheduleKind
import com.health.app.net.ServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPlannerTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long): String = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDateTime().toString()

    private val estradiol = Medication("Estradiol", "2 mg", MedForm.PILL, MedSchedule(times = listOf("20:00", "08:00")), id = "e")
    private val spiro = Medication("Spironolactone", "50 mg", schedule = MedSchedule(times = listOf("09:00")), id = "s")
    private val shot = Medication(
        "Weekly shot", "5 mg", MedForm.INJECTION,
        MedSchedule(ScheduleKind.WEEKLY, listOf("19:30"), weekdays = listOf(4)), sites = listOf("Left thigh", "Right thigh"), id = "w",
    )
    private val patch = Medication(
        "Patch", "0.1 mg/day", MedForm.PATCH,
        MedSchedule(ScheduleKind.EVERY_N_DAYS, listOf("08:00"), everyDays = 3, startDate = "2026-11-10"), id = "p",
    )
    private val ibuprofen = Medication("Ibuprofen", "200 mg", schedule = MedSchedule(ScheduleKind.AS_NEEDED, emptyList()), id = "i")

    @Test
    fun `times are read leniently and written padded`() {
        assertEquals(LocalTime.of(8, 5), ReminderPlanner.parseTime("8:05"))
        assertEquals(LocalTime.of(20, 0), ReminderPlanner.parseTime(" 20:00 "))
        assertNull(ReminderPlanner.parseTime("25:00"))
        assertNull(ReminderPlanner.parseTime("noon"))
        assertEquals("08:05", ReminderPlanner.formatTime(LocalTime.of(8, 5)))
    }

    @Test
    fun `daily doses come at each time, earliest first`() {
        val times = ReminderPlanner.timesOn(estradiol, LocalDate.parse("2026-11-12"), zone).map(::local)
        assertEquals(listOf("2026-11-12T08:00", "2026-11-12T20:00"), times)
    }

    @Test
    fun `weekly doses come on their weekdays only`() {
        // 12 November 2026 is a Thursday.
        assertTrue(ReminderPlanner.isDueOn(shot.schedule, LocalDate.parse("2026-11-12")))
        assertFalse(ReminderPlanner.isDueOn(shot.schedule, LocalDate.parse("2026-11-13")))
        // Without weekdays, the start date's weekday is used.
        val fromStart = MedSchedule(ScheduleKind.WEEKLY, startDate = "2026-11-09")
        assertTrue(ReminderPlanner.isDueOn(fromStart, LocalDate.parse("2026-11-16")))
        assertFalse(ReminderPlanner.isDueOn(fromStart, LocalDate.parse("2026-11-12")))
    }

    @Test
    fun `every few days counts from the start date, and nothing is due before it`() {
        val due = (8..20).map { LocalDate.of(2026, 11, it) }.filter { ReminderPlanner.isDueOn(patch.schedule, it) }.map { it.dayOfMonth }
        assertEquals(listOf(10, 13, 16, 19), due)
    }

    @Test
    fun `as-needed and stopped medications have no reminders`() {
        val stopped = spiro.copy(active = false)
        val now = at("2026-11-12T00:00")
        val doses = ReminderPlanner.upcoming(listOf(ibuprofen, stopped), now, now + 7 * 86_400_000L, zone)
        assertTrue(doses.isEmpty())
    }

    @Test
    fun `medications with reminders off are left out of reminders but not of the plan`() {
        val quiet = spiro.copy(reminders = false)
        val now = at("2026-11-12T00:00")
        assertTrue(ReminderPlanner.upcoming(listOf(quiet), now, now + 86_400_000L, zone).isEmpty())
        assertEquals(1, ReminderPlanner.upcoming(listOf(quiet), now, now + 86_400_000L, zone, remindersOnly = false).size)
    }

    @Test
    fun `the next reminder groups doses due at the same moment`() {
        val both = listOf(estradiol, spiro, spiro.copy(id = "s2", name = "Vitamin D", schedule = MedSchedule(times = listOf("08:00"))))
        val (when_, doses) = ReminderPlanner.next(both, at("2026-11-12T07:00"), zone)!!
        assertEquals("2026-11-12T08:00", local(when_))
        assertEquals(listOf("Estradiol", "Vitamin D"), doses.map { it.name })
    }

    @Test
    fun `the next reminder is strictly after now`() {
        val (when_, _) = ReminderPlanner.next(listOf(spiro), at("2026-11-12T09:00"), zone)!!
        assertEquals("2026-11-13T09:00", local(when_))
    }

    @Test
    fun `the next weekly reminder can be days away`() {
        val (when_, doses) = ReminderPlanner.next(listOf(shot), at("2026-11-12T20:00"), zone)!!
        assertEquals("2026-11-19T19:30", local(when_))
        assertEquals(MedForm.INJECTION, doses.single().form)
    }

    @Test
    fun `no reminder when nothing is scheduled`() {
        assertNull(ReminderPlanner.next(listOf(ibuprofen), at("2026-11-12T09:00"), zone))
    }

    @Test
    fun `an alarm finds what's due at its moment`() {
        val meds = listOf(estradiol, spiro, patch)
        assertEquals(listOf("Estradiol", "Patch"), ReminderPlanner.dueAt(meds, at("2026-11-13T08:00"), zone).map { it.name })
        assertTrue(ReminderPlanner.dueAt(meds, at("2026-11-13T08:01"), zone).isEmpty())
    }

    @Test
    fun `local times hold across the clocks changing`() {
        // Clocks go back on 1 November 2026 in New York: 9:00 is still 9:00, an hour later in UTC.
        val doses = ReminderPlanner.upcoming(listOf(spiro), at("2026-10-31T00:00"), at("2026-11-02T00:00"), zone)
        assertEquals(listOf("2026-10-31T09:00", "2026-11-01T09:00"), doses.map { local(it.at) })
        assertEquals(25 * 3_600_000L, doses[1].at - doses[0].at)
        // Clocks go forward on 8 March 2026: 2:30 doesn't exist, so it comes at 3:30.
        val night = spiro.copy(schedule = MedSchedule(times = listOf("02:30")))
        val march = ReminderPlanner.timesOn(night, LocalDate.parse("2026-03-08"), zone).map(::local)
        assertEquals(listOf("2026-03-08T03:30"), march)
    }

    @Test
    fun `schedules read as plain words`() {
        assertEquals("Every day at 8:00, 20:00", ReminderPlanner.describe(estradiol.schedule))
        assertEquals("Thu at 19:30", ReminderPlanner.describe(shot.schedule))
        assertEquals("Every 3 days at 8:00", ReminderPlanner.describe(patch.schedule))
        assertEquals("As needed", ReminderPlanner.describe(ibuprofen.schedule))
    }

    private fun decode(json: String) = ApiJson.decodeFromString(DoseLog.serializer(), json)

    @get:Rule
    val folder = TemporaryFolder()

    private val me = "aaaa1111"

    @Test
    fun `doses taken offline wait in the outbox and go oldest first`() = runBlocking {
        val outbox = DoseOutbox(folder.newFolder("outbox"))
        outbox.add(me, DoseLog("e", 1_000, scheduledFor = 900))
        outbox.add(me, DoseLog("s", 2_000, scheduledFor = 1_900))
        outbox.add(me, DoseLog("w", 3_000, site = "Left thigh"))
        assertEquals(3, outbox.size(me))

        val sent = mutableListOf<String>()
        // The second send fails: the first is gone, the rest wait for next time.
        outbox.flush(me, { json -> val dose = decode(json); if (dose.medicationId == "s") throw ServerException(0, "offline") else sent += dose.medicationId })
        assertEquals(listOf("e"), sent)
        assertEquals(2, outbox.size(me))

        assertEquals(2, outbox.flush(me, { sent += decode(it).medicationId }))
        assertEquals(listOf("e", "s", "w"), sent)
        assertEquals(0, outbox.size(me))
    }

    @Test
    fun `a dose the server refuses is dropped, so it can't hold up the rest`() = runBlocking {
        val outbox = DoseOutbox(folder.newFolder("refused"))
        outbox.add(me, DoseLog("gone", 1_000))
        outbox.add(me, DoseLog("bad", 2_000))
        outbox.add(me, DoseLog("e", 3_000))
        val sent = mutableListOf<String>()
        val count = outbox.flush(me, { json ->
            when (decode(json).medicationId) {
                "gone" -> throw ServerException(404, "No such medication", fromServer = true)
                "bad" -> throw ServerException(400, "That request isn't right", fromServer = true)
                else -> sent += decode(json).medicationId
            }
        })
        assertEquals(1, count)
        assertEquals(listOf("e"), sent)
        assertEquals(0, outbox.size(me))
    }

    @Test
    fun `a proxy's or portal's answer keeps doses waiting`() = runBlocking {
        val outbox = DoseOutbox(folder.newFolder("proxy"))
        outbox.add(me, DoseLog("e", 1_000))
        // A 404 page from a proxy, a portal's 200, the sign-in no longer good: none is the server refusing the dose.
        listOf(ServerException(404, "The server answered HTTP 404"), ServerException(200, "The server's answer didn't make sense"), ServerException(401, "Sign in first", fromServer = true))
            .forEach { failure -> assertEquals(0, outbox.flush(me, { throw failure })) }
        assertEquals(1, outbox.size(me))
    }

    @Test
    fun `doses wait for the account that marked them, and only go to it`() = runBlocking {
        val outbox = DoseOutbox(folder.newFolder("accounts"))
        outbox.add(me, DoseLog("e", 1_000))
        val sent = mutableListOf<String>()
        assertEquals(0, outbox.flush("bbbb2222", { sent += decode(it).medicationId }))
        assertTrue(sent.isEmpty())
        assertEquals(0, outbox.size(null))
        assertEquals(1, outbox.flush(me, { sent += decode(it).medicationId }))
        assertEquals(listOf("e"), sent)
    }

    @Test
    fun `doses kept by an older version go to whoever is signed in`() = runBlocking {
        val dir = folder.newFolder("legacy")
        java.io.File(dir, "0000000001000-0000.json").writeText(ApiJson.encodeToString(DoseLog.serializer(), DoseLog("e", 1_000)))
        val outbox = DoseOutbox(dir)
        outbox.adopt(me)
        assertEquals(1, outbox.size(me))
        assertEquals(0, outbox.size("bbbb2222"))
    }
}
