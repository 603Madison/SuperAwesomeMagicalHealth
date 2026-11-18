package com.health.server

import com.health.api.CheckTag
import com.health.api.EnergyCheck
import com.health.api.EnergyFocusSettings
import com.health.api.FactorKind
import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Medication
import com.health.api.DoseLog
import com.health.api.Module
import com.health.api.NowKind
import com.health.api.TimelineKind
import com.health.server.neuro.EnergyPatterns
import com.health.server.util.Days
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Energy and focus: quick check-ins, and the person's own patterns from them. */
class EnergyFocusTest {
    private val zone = Days(ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 17)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = zone.at(d, LocalTime.of(h, m))

    /** Two weeks: good focus in the morning after the medication, lower without it; less energy after short nights. */
    private fun weeks(): EnergyPatterns.Input {
        val checks = mutableListOf<EnergyCheck>()
        val doses = mutableListOf<Long>()
        val sleep = mutableMapOf<LocalDate, Int>()
        val meals = mutableListOf<Long>()
        for (k in 0 until 14) {
            val d = today.minusDays(k.toLong())
            val dosed = k < 10
            val shortNight = k % 2 == 1
            if (dosed) doses += at(d, 8, 30)
            sleep[d] = if (shortNight) 300 else 480
            meals += at(d, 12, 30)
            listOf(10 to (4 to 4), 13 to (3 to 3), 16 to (2 to 2)).forEach { (h, base) ->
                val energy = (base.first - if (shortNight) 1 else 0).coerceAtLeast(1)
                val focus = if (dosed) base.second else (base.second - 2).coerceAtLeast(1)
                checks += EnergyCheck(at(d, h), energy, focus, tags = if (h == 16 && k < 7) setOf(CheckTag.STRESSED) else emptySet())
            }
        }
        return EnergyPatterns.Input(checks, zone, doses, "Vyvanse", sleep, meals)
    }

    @Test
    fun `patterns only once there's enough, and saying how much more`() {
        val few = (0 until 5).map { EnergyCheck(at(today.minusDays((it % 2).toLong()), 9 + it), energy = 3, focus = 3) }
        val r = EnergyPatterns.analyse(EnergyPatterns.Input(few, zone))
        assertFalse(r.enough)
        assertEquals("7 more check-ins over 3 more days and your patterns start to show.", r.needed)
        assertTrue(r.summary.isEmpty() && r.factors.isEmpty() && r.byHour.isEmpty())
    }

    @Test
    fun `best hours, the medication's curve, and what goes with better energy and focus`() {
        val r = EnergyPatterns.analyse(weeks())
        assertTrue(r.enough)
        assertEquals(9 to 12, r.bestFocus!!.from to r.bestFocus!!.to)
        assertEquals("Your focus is usually best 9 AM – noon: a good time for whatever needs it most.", r.summary.first())
        assertTrue(r.summary.toString(), r.summary.any { it == "Your energy tends to dip 3 – 6 PM." })
        // Hours since the first dose: best soon after, lower later.
        assertEquals(listOf(1 to 3, 3 to 5, 7 to 9), r.sinceDose.map { it.from to it.to })
        assertTrue(r.summary.toString(), r.summary.any { it.startsWith("Your focus is usually highest 1–3 hours after your Vyvanse") && it.contains("lower again from about 3 hours") })
        val factors = r.factors.associateBy { it.kind }
        val sleep = factors.getValue(FactorKind.SLEEP)
        assertEquals("energy", sleep.measure)
        assertTrue(sleep.with > sleep.without)
        assertEquals(7 to 7, sleep.withCount to sleep.withoutCount)
        assertTrue(sleep.sentence, sleep.sentence.startsWith("After 7 hours' sleep or more, your energy averaged"))
        val med = factors.getValue(FactorKind.MEDICATION)
        assertEquals(10 to 4, med.withCount to med.withoutCount)
        assertTrue(med.sentence, med.sentence.startsWith("On days you took Vyvanse, your focus averaged 3.0, against 1.3"))
        // Every sentence gives the counts behind it, and none claims a cause.
        r.factors.forEach { f ->
            assertTrue(f.sentence, Regex("""\(\d+ and \d+ (days|check-ins)\)""").containsMatchIn(f.sentence))
            assertFalse(f.sentence, Regex("causes|because|makes you", RegexOption.IGNORE_CASE).containsMatchIn(f.sentence))
        }
        // Small differences aren't patterns.
        assertTrue(r.factors.all { kotlin.math.abs(it.with - it.without) >= EnergyPatterns.DIFFERENCE })
    }

    @Test
    fun `hours read like a clock`() {
        assertEquals("9 AM – noon", EnergyPatterns.range(9, 12))
        assertEquals("3 – 6 PM", EnergyPatterns.range(15, 18))
        assertEquals("noon – 3 PM", EnergyPatterns.range(12, 15))
        assertEquals("6 – 9 AM", EnergyPatterns.range(6, 9))
        assertEquals("9 PM – midnight", EnergyPatterns.range(21, 24))
        assertEquals("midnight – 6 AM", EnergyPatterns.range(0, 6))
    }

    @Test
    fun `check-ins through the API, the ADHD medication found by name, kept private and switchable`() {
        var clock = at(today, 10)
        serverTest(testServices(clock = { clock })) { s ->
            val me = runBlocking { s.users.byUsername("admin")!!.id }
            runBlocking {
                val p = s.profiles.get(me)
                s.profiles.put(me, p.copy(timezone = "UTC", modules = p.modules + Module.NEURO + Module.MEDICATIONS))
                val med = s.modules.meds.save(me, Medication("Vyvanse", "30 mg"))
                s.modules.meds.logDose(me, DoseLog(med.id!!, at(today, 8, 30)))
                s.modules.meds.save(me, Medication("Vitamin D", "1000 IU"))
            }
            val admin = adminBrowser()
            assertEquals(HttpStatusCode.OK, admin.postJson("/api/neuro/checks", """{"at":${at(today, 9, 45)},"energy":4,"focus":2,"tags":["STRESSED"],"note":"noisy office"}""").status)
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/neuro/checks", """{"at":${at(today, 9, 50)}}""").status)
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/neuro/checks", """{"at":${at(today, 23)},"energy":3}""").status)
            val ef = admin.get("/api/neuro/energy-focus").obj()
            assertEquals("Vyvanse", ef["medication"]!!.jsonPrimitive.content)
            assertEquals(1, ef["today"]!!.jsonArray.size)
            assertEquals(1, ef["dosesToday"]!!.jsonArray.size)
            assertEquals("false", ef["enough"]!!.jsonPrimitive.content)
            assertTrue(ef["needed"]!!.jsonPrimitive.content.endsWith("and your patterns start to show."))
            assertTrue(ef["about"]!!.jsonPrimitive.content.contains("not proof of what causes what"))
            assertEquals(5, ef["sources"]!!.jsonArray.size)

            // Settings: reminder times kept tidy; only the person's own medications.
            val saved = admin.putJson("/api/neuro/energy-focus/settings", """{"reminders":["14:00","10:00","25:00","10:00"]}""").obj()
            assertEquals(listOf("10:00", "14:00"), saved["reminders"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/neuro/energy-focus/settings", """{"medicationId":"nope"}""").status)

            runBlocking {
                // The check-in is in the day; Today has it, and the old energy budget is gone.
                val profile = s.profiles.get(me)
                val item = s.neuro.timeline(me, profile, today, today).single { it.kind == TimelineKind.ENERGY }
                assertEquals("Energy 4 · focus 2", item.title)
                assertEquals("Stressed · noisy office", item.detail)
                assertEquals(2, s.neuro.today(me, profile, today).first.lastCheck!!.focus)
                assertNull(s.neuro.today(me, profile, today).first.energyLeft)

                // A reminder that's passed with no check-in since: "Right now" offers one; after one, it doesn't.
                clock = at(today, 14, 30)
                assertEquals(listOf(NowKind.ENERGY_TODAY), s.neuro.now(me, profile, clock).map { it.kind })
                assertEquals("energy-check:$today:14:00", s.neuro.reminders(me, profile, at(today, 13, 55), at(today, 14, 5)).single().key)
                s.neuro.saveCheck(me, EnergyCheck(at(today, 14, 20), energy = 3, focus = 3))
                assertTrue(s.neuro.now(me, profile, clock).isEmpty())

                // Turned off: no check-ins in the day, on Today or in reminders.
                s.conditions.putChoices(me, FieldChoices(hide = setOf(Fields.NEURO_ENERGY)))
                assertTrue(s.neuro.timeline(me, profile, today, today).none { it.kind == TimelineKind.ENERGY })
                assertNull(s.neuro.today(me, profile, today).first.lastCheck)
                assertTrue(s.neuro.reminders(me, profile, at(today, 13, 55), at(today, 14, 5)).isEmpty())
                s.conditions.putChoices(me, FieldChoices())
            }

            // Someone shown Autism & ADHD but not medications sees nothing about the medication.
            runBlocking { s.users.create("sam", "Sam", password = "sam-password-123") }
            assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["NEURO"]}""").status)
            val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
            val seen = viewer.get("/api/neuro/energy-focus") { header("X-SAMH-View", me) }.obj()
            assertTrue(seen["medication"] == null || seen["medication"].toString() == "null")
            assertTrue(seen["dosesToday"]!!.jsonArray.isEmpty())
            assertTrue(seen["settings"]!!.jsonObject["reminders"]!!.jsonArray.isEmpty())
        }
    }
}
