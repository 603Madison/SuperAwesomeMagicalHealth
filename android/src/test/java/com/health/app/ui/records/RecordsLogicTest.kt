package com.health.app.ui.records

import com.health.api.DataDestination
import com.health.api.DataPath
import com.health.api.DataPathState
import com.health.api.HealthRecords
import com.health.api.LabResult
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordFlag
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordProvider
import com.health.api.ScheduleKind
import com.health.app.ui.settings.WhereDataGoes
import com.health.app.ui.tabOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Medical records' words and order, worked out without drawing anything: the same as the web's (web/test/records.test.ts). */
class RecordsLogicTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDate.of(2026, 11, 18).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L
    private val mgb = RecordConnection(
        "c1", "Mass General Brigham", "https://fhir.mgb.example/R4", RecordProvider.EPIC, connectedAt = now - 48 * h, lastSyncAt = now - 2 * h,
        keepsUpdating = true, counts = mapOf(RecordKind.LAB to 12, RecordKind.MEDICATION to 3, RecordKind.ALLERGY to 1),
    )

    private fun item(title: String, kind: RecordKind = RecordKind.LAB, date: String? = null, at: Long? = null) =
        RecordItem(title, "c1", kind, title, date = date, at = at, organization = "Mass General Brigham")

    @Test
    fun `how long ago, in plain words`() {
        assertEquals("just now", RecordsLogic.ago(now - 20_000, now, zone))
        assertEquals("5 min ago", RecordsLogic.ago(now - 5 * 60_000, now, zone))
        assertEquals("2 h ago", RecordsLogic.ago(now - 2 * h, now, zone))
        assertEquals("yesterday", RecordsLogic.ago(now - 30 * h, now, zone))
        assertEquals("3 days ago", RecordsLogic.ago(now - 72 * h, now, zone))
        assertEquals("on 4 Nov", RecordsLogic.ago(LocalDate.of(2026, 11, 4).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), now, zone))
        assertEquals("on 4 Mar 2025", RecordsLogic.ago(LocalDate.of(2025, 3, 4).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), now, zone))
        assertEquals("just now", RecordsLogic.ago(now + 5_000, now, zone))
    }

    @Test
    fun `a connection's lines`() {
        assertEquals("Updated 2 h ago", RecordsLogic.updatedText(mgb, now, zone))
        assertEquals("Not updated yet", RecordsLogic.updatedText(mgb.copy(lastSyncAt = null), now, zone))
        assertEquals("Updates itself daily", RecordsLogic.keepsText(mgb))
        assertEquals("Sign in again to update", RecordsLogic.keepsText(mgb.copy(keepsUpdating = false)))
    }

    @Test
    fun `counts in a line, in the page's order`() {
        assertEquals("12 lab results · 3 medications · 1 allergy", RecordsLogic.countLine(mgb.counts))
        assertEquals("1 lab result · 1 immunization · 2 procedures", RecordsLogic.countLine(mapOf(RecordKind.PROCEDURE to 2, RecordKind.LAB to 1, RecordKind.VITAL to 0, RecordKind.IMMUNIZATION to 1)))
        assertNull(RecordsLogic.countLine(emptyMap()))
    }

    @Test
    fun `lab flags are words`() {
        assertEquals("High", RecordsLogic.flagWord(RecordFlag.HIGH))
        assertEquals("Low", RecordsLogic.flagWord(RecordFlag.LOW))
        assertEquals("Outside range", RecordsLogic.flagWord(RecordFlag.ABNORMAL))
    }

    @Test
    fun `the record by kind, newest first, undated last`() {
        val grouped = RecordsLogic.byKind(
            listOf(
                item("Old", date = "2025-01-02"), item("Undated"), item("New", at = now - 8 * 24 * h), item("Middle", date = "2026-03-01"),
                item("Penicillin", RecordKind.ALLERGY),
            ),
        )
        assertEquals(listOf("New", "Middle", "Old", "Undated"), grouped.getValue(RecordKind.LAB).map { it.title })
        assertEquals(listOf("Penicillin"), grouped.getValue(RecordKind.ALLERGY).map { it.title })
        assertFalse(RecordKind.VITAL in grouped)
    }

    @Test
    fun `sections share the web's ids`() {
        assertEquals(
            listOf("connections", "labs", "vitals", "medications", "conditions", "allergies", "immunizations", "procedures"),
            RecordsLogic.sections(connected = true).map { it.id },
        )
        assertEquals(listOf("connections"), RecordsLogic.sections(connected = false).map { it.id })
        RecordKind.entries.forEach { assertEquals(it, RecordsLogic.kindOf(RecordsLogic.sectionId(it))) }
        assertNull(RecordsLogic.kindOf("connections"))
    }

    @Test
    fun `an item's line`() {
        assertEquals("4 Nov 2026", RecordsLogic.dayText("2026-11-04"))
        assertEquals(
            "142 pg/mL (usual 30–400) · 4 Nov 2026",
            RecordsLogic.itemLine(RecordItem("x", "c1", RecordKind.LAB, "Estradiol", "142 pg/mL (usual 30–400)", "2026-11-04", status = "final", organization = "o")),
        )
        assertEquals("Since 2019 · Resolved", RecordsLogic.itemLine(RecordItem("y", "c1", RecordKind.CONDITION, "Migraine", "Since 2019", status = "resolved", organization = "o")))
    }

    @Test
    fun `You's line says where it stands now`() {
        val records = HealthRecords(listOf(RecordProvider.EPIC))
        assertEquals("Your hospital or clinic's record", RecordsLogic.youLine(null, now, zone))
        assertEquals("Not set up on this server yet", RecordsLogic.youLine(HealthRecords(), now, zone))
        assertEquals("Connect your patient portal", RecordsLogic.youLine(records, now, zone))
        assertEquals("Mass General Brigham · updated 2 h ago", RecordsLogic.youLine(records.copy(connections = listOf(mgb)), now, zone))
        assertEquals("Mass General Brigham · not updated yet", RecordsLogic.youLine(records.copy(connections = listOf(mgb.copy(lastSyncAt = null))), now, zone))
        assertEquals(
            "Mass General Brigham and 1 more · updated 1 h ago",
            RecordsLogic.youLine(records.copy(connections = listOf(mgb, mgb.copy(id = "c2", organization = "Wentworth-Douglass", lastSyncAt = now - h))), now, zone),
        )
        assertEquals("Mass General Brigham · needs you to sign in again", RecordsLogic.youLine(records.copy(connections = listOf(mgb.copy(problem = "Sign in again"))), now, zone))
        assertEquals("Mass General Brigham · paused", RecordsLogic.youLine(records.copy(paused = true, connections = listOf(mgb)), now, zone))
    }

    @Test
    fun `disconnected but kept, as the server says it`() {
        val gone = mgb.copy(id = "c3", organization = "Wentworth-Douglass", problem = "Disconnected: what it brought in is kept", keepsUpdating = false)
        assertTrue(RecordsLogic.isDisconnected(gone))
        assertFalse(RecordsLogic.isDisconnected(mgb.copy(problem = "Sign in again to bring it up to date")))
        assertFalse(RecordsLogic.isDisconnected(mgb))
        val records = HealthRecords(listOf(RecordProvider.EPIC))
        // It doesn't count while another is connected; on its own, it says so.
        assertEquals("Mass General Brigham · updated 2 h ago", RecordsLogic.youLine(records.copy(connections = listOf(gone, mgb)), now, zone))
        assertEquals("Wentworth-Douglass · disconnected", RecordsLogic.youLine(records.copy(connections = listOf(gone)), now, zone))
    }

    @Test
    fun `signing in again goes to the same portal, from the phone`() {
        assertEquals(RecordConnect("Mass General Brigham", "https://fhir.mgb.example/R4", RecordProvider.EPIC, app = true), RecordsLogic.reconnect(mgb))
    }

    @Test
    fun `labs from a record say where they came from`() {
        val lab = LabResult("Estradiol", 142.0, "pg/mL", at = now, source = "Mass General Brigham", ref = "record:c1:obs-1")
        assertTrue(RecordsLogic.fromRecord(lab))
        assertTrue(RecordsLogic.fromRecord(lab.copy(source = null)))
        assertFalse(RecordsLogic.fromRecord(lab.copy(source = null, ref = null)))
        assertEquals("From Mass General Brigham", RecordsLogic.labSource(lab))
        assertNull(RecordsLogic.labSource(lab.copy(source = null)))
    }

    @Test
    fun `a record's medication becomes a new one, never an edit`() {
        val draft = RecordsLogic.draft(Medication("Levothyroxine", "50 mcg", id = "server-id", schedule = MedSchedule(times = emptyList())))
        assertNull(draft.id)
        assertEquals(listOf("09:00"), draft.schedule.times)
        assertEquals(emptyList<String>(), RecordsLogic.draft(Medication("Ibuprofen", schedule = MedSchedule(ScheduleKind.AS_NEEDED, times = emptyList()))).schedule.times)
        assertTrue(RecordsLogic.alreadyHas(Medication("levothyroxine "), setOf("Levothyroxine")))
        assertFalse(RecordsLogic.alreadyHas(Medication("Iron"), setOf("Levothyroxine")))
    }

    @Test
    fun `it sits under You, and Where your data goes leads there`() {
        assertEquals("you", tabOf(RecordsLogic.ROUTE))
        assertEquals(RecordsLogic.ROUTE, WhereDataGoes.link("records")?.route)
        assertEquals(RecordsLogic.ROUTE, WhereDataGoes.openLink("records")?.route)
        assertNull(WhereDataGoes.openLink("settings.sharing"))
        val way = DataDestination(DataPath.HEALTH_RECORDS, "Medical records", "Your portal", what = "", whenSent = "", state = DataPathState.ON, count = 2, setUp = "records")
        assertEquals("On · 2 portals", WhereDataGoes.stateLine(way))
    }

    @Test
    fun `the portal's return says only its own sentences, never a link's words`() {
        assertEquals("You didn't allow it, so nothing was connected", RecordsLogic.returnError("denied"))
        assertEquals(RecordsLogic.RETURN_FAILED, RecordsLogic.returnError("Call this number to fix your account"))
        assertEquals(null, RecordsLogic.returnError(null))
    }
}
