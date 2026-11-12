package com.health.app.net

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.CycleDay
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.Flow
import com.health.api.JournalEntry
import com.health.api.JournalKind
import com.health.api.LabResult
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MoodEntry
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.ScheduleKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The health modules' endpoints, as docs/API.md lists them. */
class ModulesApiTest {
    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" }, {})
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `moods are listed by days and posted as they are`() = runBlocking {
        server.enqueue(json("""[{"at":1,"mood":4,"feelings":["calm"],"id":"m1","date":"2026-11-12"}]"""))
        assertEquals("calm", api.moods(30).single().feelings.single())
        assertEquals("/api/mind/moods?days=30", server.takeRequest().path)

        server.enqueue(json("""{"at":2,"mood":2,"anxiety":4,"id":"m2"}"""))
        val saved = api.addMood(MoodEntry(2, 2, anxiety = 4, context = listOf("work")))
        assertEquals("m2", saved.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mind/moods", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"anxiety\":4") && body.contains("\"context\":[\"work\"]"))
    }

    @Test
    fun `journal entries are updated by id and deleted`() = runBlocking {
        server.enqueue(json("""{"at":1,"kind":"GRATITUDE","gratitude":["tea"],"id":"j 1"}"""))
        api.updateJournal(JournalEntry(1, JournalKind.GRATITUDE, gratitude = listOf("tea"), id = "j 1"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/mind/journal/j%201", put.path)

        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteJournal("j 1")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/mind/journal/j%201", delete.path)
    }

    @Test
    fun `a check-in is posted with its answers and comes back scored`() = runBlocking {
        server.enqueue(json("""{"screener":"PHQ9","at":5,"answers":[3,3,3,3,3,3,3,3,1],"score":25,"severity":"Severe","flagged":true,"id":"r1"}"""))
        val result = api.submitScreener(ScreenerResult(Screener.PHQ9, 5, List(8) { 3 } + 1))
        assertEquals(25, result.score)
        assertTrue(result.flagged)
        val request = server.takeRequest()
        assertEquals("/api/mind/screeners/results", request.path)
        assertTrue(request.body.readUtf8().contains("\"answers\":[3,3,3,3,3,3,3,3,1]"))
    }

    @Test
    fun `the support list comes from the server`() = runBlocking {
        server.enqueue(json("""[{"name":"988 Suicide & Crisis Lifeline","contact":"Call or text 988","url":"https://988lifeline.org","region":"US"}]"""))
        assertEquals("Call or text 988", api.crisisResources().single().contact)
        assertEquals("/api/mind/resources", server.takeRequest().path)
    }

    @Test
    fun `a cycle day is put on its date, and the overview asks for months`() = runBlocking {
        server.enqueue(json("""{"days":[],"cycles":[],"cycleDay":3,"phase":"PERIOD","daysUntilPeriod":25}"""))
        assertEquals(3, api.cycle().cycleDay)
        assertEquals("/api/cycle?months=12", server.takeRequest().path)

        server.enqueue(json("""{"date":"2026-11-12","flow":"HEAVY","symptoms":["cramps"],"pain":6}"""))
        api.saveCycleDay(CycleDay("2026-11-12", Flow.HEAVY, listOf("cramps"), pain = 6))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/cycle/days/2026-11-12", put.path)
        assertTrue(put.body.readUtf8().contains("\"flow\":\"HEAVY\""))

        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteCycleDay("2026-11-12")
        assertEquals("DELETE /api/cycle/days/2026-11-12", server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `bladder and bowel entries and the summary`() = runBlocking {
        server.enqueue(json("""{"at":1,"kind":"LEAK","leak":"SMALL","triggers":["cough"],"id":"c1","night":false}"""))
        api.addContinence(ContinenceEntry(1, ContinenceKind.LEAK, triggers = listOf("cough")))
        val post = server.takeRequest()
        assertEquals("POST /api/continence/entries", "${post.method} ${post.path}")

        server.enqueue(json("""{"days":[{"date":"2026-11-12","voids":7,"leaks":1}],"averageVoids":7.0}"""))
        assertEquals(7, api.continenceSummary(14).days.single().voids)
        assertEquals("/api/continence/summary?days=14", server.takeRequest().path)

        server.enqueue(json("""[]"""))
        api.continence(3)
        assertEquals("/api/continence/entries?days=3", server.takeRequest().path)
    }

    @Test
    fun `medications, their day, and doses marked against a scheduled time`() = runBlocking {
        server.enqueue(json("""[{"name":"Estradiol","form":"PATCH","id":"e1","active":false}]"""))
        assertEquals(MedForm.PATCH, api.medications(all = true).single().form)
        assertEquals("/api/meds?all=true", server.takeRequest().path)

        server.enqueue(json("""{"name":"Estradiol","id":"e1","schedule":{"kind":"EVERY_N_DAYS","times":["08:00"],"everyDays":3}}"""))
        api.updateMedication(Medication("Estradiol", schedule = MedSchedule(ScheduleKind.EVERY_N_DAYS, listOf("08:00"), everyDays = 3), id = "e1"))
        assertEquals("PUT /api/meds/e1", server.takeRequest().let { "${it.method} ${it.path}" })

        server.enqueue(
            json(
                """{"date":"2026-11-12","due":[{"medicationId":"e1","name":"Estradiol","form":"INJECTION","scheduledFor":1000,"nextSite":"Left thigh"}],"adherence7d":86}""",
            ),
        )
        val day = api.medsDay("2026-11-12")
        assertEquals("Left thigh", day.due.single().nextSite)
        assertEquals(86, day.adherence7d)
        assertEquals("/api/meds/day/2026-11-12", server.takeRequest().path)

        server.enqueue(json("""{"medicationId":"e1","at":1100,"status":"TAKEN","scheduledFor":1000,"site":"Left thigh","id":"d1"}"""))
        api.logDose(DoseLog("e1", 1100, DoseStatus.TAKEN, scheduledFor = 1000, site = "Left thigh"))
        val dose = server.takeRequest()
        assertEquals("POST /api/meds/doses", "${dose.method} ${dose.path}")
        val body = dose.body.readUtf8()
        assertTrue(body, body.contains("\"scheduledFor\":1000") && body.contains("\"site\":\"Left thigh\""))
    }

    @Test
    fun `lab results`() = runBlocking {
        server.enqueue(json("""[{"name":"Estradiol","value":180.0,"unit":"pg/mL","at":1,"low":100.0,"high":200.0,"id":"l1"}]"""))
        assertEquals(180.0, api.labs().single().value, 0.0)
        assertEquals("/api/labs?days=3650", server.takeRequest().path)

        server.enqueue(json("""{"name":"Ferritin","value":40.0,"unit":"ng/mL","at":2,"id":"l2"}"""))
        api.addLab(LabResult("Ferritin", 40.0, "ng/mL", 2))
        assertEquals("POST /api/labs", server.takeRequest().let { "${it.method} ${it.path}" })
    }
}
