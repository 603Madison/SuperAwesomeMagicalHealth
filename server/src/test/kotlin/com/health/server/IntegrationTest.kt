package com.health.server

import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.Habit
import com.health.api.Module
import com.health.api.ReportSectionKey
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The features built side by side, working together once merged. */
class IntegrationTest {
    private fun everything(services: Services) = runBlocking {
        if (services.users.byUsername("admin") == null) services.bootstrap()
        val id = services.users.byUsername("admin")!!.id
        services.profiles.put(id, services.profiles.get(id).copy(modules = Module.entries.toSet(), timezone = "UTC"))
        id
    }

    @Test
    fun `the watch logs coffee, habits, hot flashes and symptoms through their features`() = serverTest { s ->
        val id = everything(s)
        val habit = s.tracking.habits.save(id, Habit("Stretch"))
        val watch = adminBrowser().postJson("/api/watch/keys", "{}").obj().str("token")
        val client = createClient { }
        suspend fun tap(body: String) = client.post("/api/watch/log") { bearerAuth(watch); json(body) }.status

        assertEquals(HttpStatusCode.OK, tap("""{"kind":"COFFEE","value":240}"""))
        assertEquals(HttpStatusCode.OK, tap("""{"kind":"HABIT","value":1,"ref":"${habit.id}"}"""))
        assertEquals(HttpStatusCode.OK, tap("""{"kind":"HOT_FLASH","value":3}"""))
        assertEquals(HttpStatusCode.OK, tap("""{"kind":"SYMPTOM","value":6,"ref":"headache"}"""))
        assertEquals(HttpStatusCode.BadRequest, tap("""{"kind":"SYMPTOM","value":11,"ref":"headache"}"""))

        val day = s.days.summary(id, "today")
        assertEquals(96.0, day.drinks!!.caffeineMg, 0.5)
        assertTrue(day.modules.habits!!.single().done)
        assertEquals(1, day.modules.lifeStage?.hotFlashesToday ?: s.lifeStages.overview(id, 1).hotFlashes.size)
        assertEquals(6, day.modules.symptoms!!.worst)
        val today = client.get("/api/watch/today") { bearerAuth(watch) }.obj()
        assertTrue(today["habits"]!!.jsonArray.single().jsonObject["done"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `someone viewing a share can't reach reports, exports, digests, webhooks or keys`() = serverTest { s ->
        val ownerId = everything(s)
        s.users.create("sam", "Sam", password = "sam-password-123")
        assertEquals(HttpStatusCode.OK, adminBrowser().postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY","VITALS"]}""").status)
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        suspend fun look(path: String) = viewer.get(path) { header("X-SAMH-View", ownerId) }.status

        assertEquals(HttpStatusCode.OK, look("/api/vitals/bp"))
        for (path in listOf(
            "/api/report.pdf", "/api/export.zip", "/api/export/fhir", "/api/digest/latest", "/api/webhooks", "/api/webhooks/deliveries",
            "/api/push/subscriptions", "/api/integrations/keys", "/api/reminders/upcoming", "/api/watch/today", "/api/shares",
        )) {
            assertEquals(path, HttpStatusCode.Forbidden, look(path))
        }
    }

    @Test
    fun `the report puts food and drinks, and the cycle and life stages, together`() = runBlocking {
        val s = testServices()
        val id = everything(s)
        s.tracking.drinks.save(id, Drink(s.clock(), DrinkKind.COFFEE, 240, caffeineMg = 96.0))
        val today = LocalDate.now()
        val parts = s.hooks.mapNotNull { it.report(id, s.profiles.get(id), today.minusDays(7), today) }
        val sections = s.platform.reports.sections(id, s.profiles.get(id), today.minusDays(7), today, listOf(ReportSectionKey.NUTRITION, ReportSectionKey.CYCLE))
        // One section per key, holding every feature's part of it.
        assertEquals(sections.map { it.key }.distinct(), sections.map { it.key })
        val nutrition = sections.single { it.key == ReportSectionKey.NUTRITION }
        assertEquals(parts.filter { it.key == ReportSectionKey.NUTRITION }.sumOf { it.summary.size }, nutrition.summary.size)
        assertTrue("food and drinks both report", parts.count { it.key == ReportSectionKey.NUTRITION } >= 2)
    }

    @Test
    fun `every FHIR resource in the export names the person`() = runBlocking {
        val s = testServices()
        val id = everything(s)
        s.tracking.vitals.saveBloodPressure(id, com.health.api.BloodPressure(s.clock() - 60_000, 128, 82))
        val today = LocalDate.now()
        val bundle = s.platform.exports.fhir(id, today.minusDays(7), today)
        val resources = bundle["entry"]!!.jsonArray.map { it.jsonObject["resource"]!!.jsonObject }
        assertTrue(resources.any { it["resourceType"]!!.jsonPrimitive.content == "Observation" })
        resources.filter { it["resourceType"]!!.jsonPrimitive.content != "Patient" }.forEach {
            assertEquals("Patient/$id", it["subject"]!!.jsonObject["reference"]!!.jsonPrimitive.content)
        }
    }
}
