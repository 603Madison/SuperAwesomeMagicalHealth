package com.health.server

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.WeightScaleMesgListener
import com.health.api.CommunicationCard
import com.health.api.Medication
import com.health.api.Module
import com.health.api.MomentKind
import com.health.api.NeuroProfile
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.RoutineStep
import com.health.api.Wording
import com.health.api.WorkoutType
import com.health.server.health.FitImporter
import com.health.server.neuro.NeuroService
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/** Autism & ADHD, transition, progress photos, body measurements and the FIT export. */
class NeuroTransitionPhotosTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private val now = at(today, 15)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun services(modules: Set<Module> = Module.entries.toSet()) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = modules))
        }
    }

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    // ---- Autism & ADHD ----

    @Test
    fun `about me is sealed, and cards start with kind defaults until the person makes their own`() = runBlocking {
        val s = services()
        val id = s.admin()
        s.neuro.saveProfile(id, NeuroProfile(identities = listOf("Autistic", "ADHD"), whatHelps = listOf("noise-cancelling headphones"), aboutMe = "A private note about me"))
        val raw = s.db.tx { query("SELECT data FROM user_settings") { it.string("data") } }.joinToString()
        assertFalse(raw.contains("headphones"))
        assertFalse(raw.contains("private note"))
        assertEquals(listOf("Autistic", "ADHD"), s.neuro.profile(id).identities)
        assertTrue(s.neuro.cards(id).first().text.contains("quiet minute"))
        s.neuro.saveCards(id, listOf(CommunicationCard("Please text me")))
        assertEquals("Please text me", s.neuro.cards(id).single().text)
    }

    @Test
    fun `how the person is described follows the wording they chose`() {
        assertEquals("Autistic and ADHD (in their own words).", NeuroService.describe(NeuroProfile(identities = listOf("Autistic", "ADHD"))))
        assertEquals("A person with autism and ADHD (in their own words).", NeuroService.describe(NeuroProfile(identities = listOf("autism", "ADHD"), wording = Wording.PERSON_FIRST)))
        assertEquals("In their own words: Neurodivergent, Synaesthete.", NeuroService.describe(NeuroProfile(identities = listOf("Neurodivergent", "Synaesthete"))))
    }

    @Test
    fun `the API logs overwhelm, energy, check-ins, focus and routines, and Today shows them`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val event = admin.postJson("/api/neuro/events", """{"start":${at(today, 11)},"end":${at(today, 11, 40)},"kind":"SHUTDOWN","intensity":4,"triggers":["noise"],"whatHelped":["quiet room"]}""")
        assertEquals(HttpStatusCode.OK, event.status)
        assertEquals("2026-03-20", event.obj().str("date"))
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/neuro/events", """{"start":${now + 86_400_000},"kind":"MELTDOWN"}""").status)
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/neuro/energy/2026-03-20", """{"date":"2026-03-20","startWith":12,"spends":[{"what":"work","amount":8},{"what":"nap","amount":-1}],"socialBattery":3}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/neuro/check-ins", """{"at":${at(today, 9)},"masking":5,"focus":2,"medEffect":2,"woreOffAt":"13:30"}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/neuro/check-ins", """{"at":${at(today, 9)},"woreOffAt":"half one"}""").status)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/neuro/focus", """{"start":${at(today, 13)},"end":${at(today, 13, 50)},"kind":"BODY_DOUBLING","task":"emails","estimateMinutes":25}""").status)
        val routine = admin.postJson("/api/neuro/routines", """{"name":"Morning","steps":[{"text":"Water","minutes":1},{"text":"Meds","minutes":1},{"text":"Breakfast","minutes":15}],"reminder":"08:00"}""").obj()
        val routineId = routine.str("id")
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/neuro/routines/$routineId/runs/2026-03-20", """{"routineId":"$routineId","date":"2026-03-20","done":[0,1,9]}""").status)
        assertEquals(listOf(0, 1), admin.get("/api/neuro/routines/runs?days=3").json().jsonArray.single().jsonObject["done"]!!.jsonArray.map { it.jsonPrimitive.int })

        val day = s.days.summary(s.admin(), "2026-03-20")
        val neuro = day.modules.neuro!!
        assertEquals(1, neuro.overloadsToday)
        assertEquals(5, neuro.energyLeft)
        assertEquals(3, neuro.socialBattery)
        assertEquals(50, neuro.focusMinutes)
        assertEquals(listOf("Morning"), neuro.routinesDue)
        assertTrue(day.insights.any { it.module == Module.NEURO && it.title == "Go gently today" })

        // The routine reminds at 08:00 the next day unless it's done.
        val hooks = s.hooks.first { it === s.neuro }
        val tomorrow = today.plusDays(1)
        assertEquals("routine:$routineId:$tomorrow", hooks.reminders(s.admin(), s.profiles.get(s.admin()), at(tomorrow, 7, 55), at(tomorrow, 8, 5)).single().key)
        s.neuro.saveRun(s.admin(), routineId, tomorrow.toString(), RoutineRun(routineId, tomorrow.toString(), listOf(0, 1, 2)))
        assertTrue(hooks.reminders(s.admin(), s.profiles.get(s.admin()), at(tomorrow, 7, 55), at(tomorrow, 8, 5)).isEmpty())

        // Turned off, it leaves Today.
        s.profiles.put(s.admin(), s.profiles.get(s.admin()).copy(modules = Module.DEFAULT))
        assertNull(s.days.summary(s.admin(), "2026-03-20").modules.neuro)
    }

    @Test
    fun `the summary finds what goes with hard days, and notices burnout building gently`() = runBlocking {
        val s = services()
        val id = s.admin()
        for (back in 0L until 28L) {
            val d = today.minusDays(back)
            val masked = back % 2 == 0L
            s.neuro.saveCheckIn(id, com.health.api.NeuroCheckIn(at(d, 9), masking = if (masked) 5 else 1, burnout = if (back < 21) 4 else 2))
            if (masked && back % 4 == 0L) s.neuro.saveEvent(id, OverloadEvent(at(d, 18), kind = OverloadKind.MELTDOWN, triggers = listOf("change of plans"), whatHelped = listOf("weighted blanket")))
            if (back < 21) s.neuro.saveEnergy(id, d.toString(), com.health.api.EnergyDay(d.toString(), startWith = 10, spends = listOf(com.health.api.EnergySpend("work", 9)), socialBattery = 2))
        }
        val summary = s.neuro.summary(id, 28)
        assertEquals(7, summary.overloads)
        assertEquals("weighted blanket", summary.whatHelped.first().name)
        assertTrue(summary.patterns.toString(), summary.patterns.any { it.title == "Masking and hard days" })
        assertTrue(summary.patterns.any { it.title == "Changes of plan" })
        assertNotNull(summary.burnoutRisk)
        assertFalse(summary.burnoutRisk!!.contains("!"))
        // And the appointment questions pick it up.
        val questions = s.neuro.questions(id, s.profiles.get(id), today.minusDays(28), today)
        assertTrue(questions.any { it.text.contains("burnout") })
    }

    // ---- Transition ----

    @Test
    fun `transition keeps milestones, changes, voice, hair removal and moments, sealed, with an overview`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val id = s.admin()
        s.modules.meds.save(id, Medication("Estradiol", "2 mg", hormone = true, startedOn = "2025-01-10"))
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/transition/profile", """{"pronouns":"she/her","startedOn":"2024-06","goals":["Feel at home in my voice"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/transition/profile", """{"startedOn":"last spring"}""").status)
        val templates = admin.get("/api/transition/templates").json().jsonArray
        assertTrue(templates.any { it.jsonObject.str("title") == "Update my passport" })
        val passport = admin.postJson("/api/transition/milestones", """{"title":"Update my passport","area":"LEGAL","status":"IN_PROGRESS","checklist":[{"text":"Photo","done":true},{"text":"Apply"}]}""").obj()
        admin.postJson("/api/transition/milestones", """{"title":"Voice lesson","area":"VOICE","status":"PLANNED","date":"2026-03-22"}""")
        admin.postJson("/api/transition/changes", """{"date":"2026-03-01","area":"skin","note":"Softer","feeling":5}""")
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/transition/voice", """{"at":${at(today, 8)},"minutes":20,"exercises":["resonance"],"pitchAvgHz":185}""").status)
        admin.postJson("/api/transition/hair-removal", """{"at":${at(today.minusDays(5), 10)},"method":"LASER","area":"face","pain":6}""")
        for (h in listOf(9, 12, 14)) admin.postJson("/api/transition/moments", """{"at":${at(today, h)},"kind":"EUPHORIA","intensity":4,"context":["mirror"]}""")
        admin.postJson("/api/measurements", """{"metric":"WAIST","value":78.5,"at":${at(today, 7)}}""")
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/measurements", """{"metric":"WAIST","value":780,"at":${at(today, 7)}}""").status)

        val o = admin.get("/api/transition").obj()
        assertEquals("1 year, 9 months", o.str("sinceStart"))
        assertEquals("1 year, 2 months", o.str("onHormones"))
        assertEquals(3, o["euphoria"]!!.jsonPrimitive.int)
        assertTrue(o["highlights"]!!.jsonArray.any { it.jsonPrimitive.content == "Update my passport: 1 of 2 steps done." })
        assertEquals(78.5, o["measurements"]!!.jsonArray.single().jsonObject["values"]!!.jsonObject["WAIST"]!!.jsonPrimitive.content.toDouble(), 0.01)
        assertEquals(passport.str("id"), admin.get("/api/transition/milestones").json().jsonArray.first().jsonObject.str("id"))

        val day = s.days.summary(id, "2026-03-20")
        assertEquals(3, day.modules.transition!!.euphoriaThisWeek)
        assertEquals(listOf("Voice lesson"), day.modules.transition!!.milestonesDue)
        assertTrue(day.insights.any { it.module == Module.TRANSITION && it.title == "Moments of euphoria" })
        val raw = s.db.tx { query("SELECT data FROM entries") { it.string("data") } }.joinToString()
        assertFalse(raw.contains("passport") || raw.contains("Softer"))

        val report = s.platform.reports.sections(id, s.profiles.get(id), today.minusDays(30), today, listOf(ReportSectionKey.TRANSITION, ReportSectionKey.BODY))
        assertTrue(report.any { it.key == ReportSectionKey.TRANSITION && it.summary.any { l -> l.contains("Pronouns: she/her") } })
        assertTrue(report.any { it.key == ReportSectionKey.BODY && it.tables.any { t -> t.title == "Measurements (cm)" } })
        assertEquals(HttpStatusCode.OK, admin.delete("/api/transition/moments/${admin.get("/api/transition/moments").json().jsonArray.first().jsonObject.str("id")}") { csrf() }.status)
    }

    // ---- Progress photos ----

    /** A 40×20 JPEG, red on the left and blue on the right, with EXIF saying "turn 90° clockwise" and a GPS block. */
    private fun cameraJpeg(): ByteArray {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED; g.fillRect(0, 0, 20, 20)
        g.color = Color.BLUE; g.fillRect(20, 0, 20, 20)
        g.dispose()
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        // Drop the JFIF APP0 segment, as camera files have EXIF first.
        var rest = plain.copyOfRange(2, plain.size)
        if (rest[1] == 0xE0.toByte()) {
            val len = ((rest[2].toInt() and 0xFF) shl 8) or (rest[3].toInt() and 0xFF)
            rest = rest.copyOfRange(2 + len, rest.size)
        }
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v shr 8); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write("MM".toByteArray()); u16(42); u32(8)
        u16(2) // IFD0: orientation, and where the GPS block is
        u16(0x0112); u16(3); u32(1); u16(6); u16(0)
        u16(0x8825); u16(4); u32(1); u32(38)
        u32(0)
        u16(1) // GPS IFD: a map datum naming the place
        u16(0x0012); u16(2); u32(12); u32(56)
        u32(0)
        tiff.write("SECRETPLACE\u0000".toByteArray())
        val exif = "Exif\u0000\u0000".toByteArray() + tiff.toByteArray()
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((exif.size + 2) shr 8).toByte(), ((exif.size + 2) and 0xFF).toByte()) + exif
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + rest
    }

    private suspend fun io.ktor.client.HttpClient.upload(bytes: ByteArray, json: String = """{"album":"Transition","pose":"FRONT","at":$now}""", type: String = "image/jpeg"): HttpResponse =
        submitFormWithBinaryData("/api/photos", formData {
            append("photo", json)
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, type)
                append(HttpHeaders.ContentDisposition, "filename=\"photo\"")
            })
        }) { csrf() }

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    @Test
    fun `a photo is turned upright, loses its location and camera details, and is sealed on disk`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val original = cameraJpeg()
        assertTrue(original.has("Exif") && original.has("SECRETPLACE"))
        assertEquals(6, com.health.server.photos.Images.exifOrientation(original))
        admin.postJson("/api/measurements", """{"metric":"WEIGHT","value":64.2,"at":${now - 3_600_000}}""")
        val made = admin.upload(original)
        assertEquals(HttpStatusCode.OK, made.status)
        val photo = made.obj()
        assertEquals(20, photo["width"]!!.jsonPrimitive.int)
        assertEquals(40, photo["height"]!!.jsonPrimitive.int)
        assertEquals(64.2, photo["weightKg"]!!.jsonPrimitive.content.toDouble(), 0.01)
        val id = photo.str("id")

        val image = admin.get("/api/photos/$id/image")
        assertEquals("private, no-store", image.headers[HttpHeaders.CacheControl])
        val bytes = image.readRawBytes()
        assertFalse("EXIF is gone", bytes.has("Exif"))
        assertFalse("the place is gone", bytes.has("SECRETPLACE"))
        val back = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(20, back.width)
        assertEquals(40, back.height)
        val top = Color(back.getRGB(10, 3))
        val bottom = Color(back.getRGB(10, 36))
        assertTrue("the red half ends up on top", top.red > top.blue && bottom.blue > bottom.red)
        assertTrue(ImageIO.read(ByteArrayInputStream(admin.get("/api/photos/$id/thumb").readRawBytes())) != null)

        val folder = File(File(s.config.dataDir, "photos"), s.admin())
        val files = folder.listFiles()!!.toList()
        assertEquals(2, files.size)
        files.forEach { f ->
            val stored = f.readBytes()
            assertFalse(stored.size >= 2 && stored[0] == 0xFF.toByte() && stored[1] == 0xD8.toByte())
            assertNull(ImageIO.read(f))
        }
        val raw = s.db.tx { query("SELECT data FROM photos") { it.string("data") } }.single()
        assertFalse(raw.contains("Transition"))

        assertEquals("Transition", admin.get("/api/photos/albums").json().jsonArray.single().jsonObject.str("name"))
        assertEquals(HttpStatusCode.OK, admin.putJson("/api/photos/$id", """{"album":"Weight","pose":"SIDE","at":$now,"note":"Week 12"}""").status)
        assertEquals("Week 12", admin.get("/api/photos?album=weight").json().jsonArray.single().jsonObject.str("note"))
        assertEquals(HttpStatusCode.OK, admin.delete("/api/photos/$id") { csrf() }.status)
        assertTrue(folder.listFiles()!!.isEmpty())
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/photos/$id/image").status)
    }

    @Test
    fun `PNG and WebP are taken, anything else isn't`() = serverTest(services()) { _ ->
        val admin = adminBrowser()
        val img = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        assertEquals(HttpStatusCode.OK, admin.upload(png, type = "image/png").status)
        val webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
        val made = admin.upload(webp, type = "image/webp")
        assertEquals(made.bodyAsTextOrEmpty(), HttpStatusCode.OK, made.status)
        assertEquals(1, made.obj()["width"]!!.jsonPrimitive.int)
        // Named a JPEG, but it isn't a picture at all.
        assertEquals(HttpStatusCode.BadRequest, admin.upload("not a picture".toByteArray()).status)
        val gif = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", it) }.toByteArray()
        assertEquals(HttpStatusCode.BadRequest, admin.upload(gif, type = "image/gif").status)
    }

    private suspend fun HttpResponse.bodyAsTextOrEmpty() = runCatching { String(readRawBytes()) }.getOrDefault("")

    @Test
    fun `someone viewing a share sees photos only if photos are shared, and a deleted account's photos go too`() = serverTest(services()) { s ->
        val ownerId = s.admin()
        val sam = s.users.create("sam", "Sam", password = "sam-password-123")
        val owner = adminBrowser()
        assertEquals(HttpStatusCode.OK, owner.upload(cameraJpeg()).status)
        val share = owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY"]}""").obj().str("id")
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        assertEquals(HttpStatusCode.Forbidden, viewer.get("/api/photos") { header("X-SAMH-View", ownerId) }.status)
        assertEquals(HttpStatusCode.OK, owner.putJson("/api/shares/$share", """{"modules":["ACTIVITY","PHOTOS"]}""").status)
        assertEquals(1, viewer.get("/api/photos") { header("X-SAMH-View", ownerId) }.json().jsonArray.size)

        assertEquals(HttpStatusCode.OK, viewer.upload(cameraJpeg()).status)
        val samFolder = File(File(s.config.dataDir, "photos"), sam.id)
        assertTrue(samFolder.isDirectory)
        assertEquals(HttpStatusCode.OK, owner.delete("/api/admin/users/${sam.id}") { csrf() }.status)
        assertFalse(samFolder.exists())
    }

    // ---- The AI connector ----

    @Test
    fun `the connector's tools for these features need them in the link's scope, and photos are never offered`() = runBlocking {
        val s = services()
        val user = s.users.byUsername("admin")!!
        s.neuro.saveEvent(user.id, OverloadEvent(at(today, 10), kind = OverloadKind.OVERWHELM, triggers = listOf("crowds")))
        s.transition.saveMoment(user.id, com.health.api.GenderMoment(at(today, 9), MomentKind.EUPHORIA, 5, listOf("voice")))
        val mcp = com.health.server.mcp.McpServer(s)
        suspend fun call(method: String, scope: Set<Module>, canLog: Boolean = false, params: kotlinx.serialization.json.JsonObject? = null): kotlinx.serialization.json.JsonObject {
            val request = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0")); put("id", JsonPrimitive(1)); put("method", JsonPrimitive(method))
                params?.let { put("params", it) }
            }
            return mcp.handle(com.health.server.mcp.McpServer.Caller(user, false, canLog, scope), request)!!
        }
        suspend fun tools(scope: Set<Module>, canLog: Boolean = false) = call("tools/list", scope, canLog)["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject.str("name") }
        assertTrue(tools(emptySet(), true).none { it in setOf("neuro", "transition", "log_overload", "log_energy", "log_moment") })
        assertTrue(tools(Module.entries.toSet(), true).containsAll(listOf("neuro", "transition", "log_overload", "log_energy", "log_moment")))
        assertTrue(tools(Module.entries.toSet(), true).none { it.contains("photo") })
        val neuro = call("tools/call", setOf(Module.NEURO), params = buildJsonObject { put("name", JsonPrimitive("neuro")); put("arguments", buildJsonObject {}) }).toString()
        assertTrue(neuro, neuro.contains("crowds"))
        val transition = call("tools/call", setOf(Module.TRANSITION), params = buildJsonObject { put("name", JsonPrimitive("transition")); put("arguments", buildJsonObject {}) }).toString()
        assertTrue(transition, transition.contains("1 moment of euphoria"))
        val logged = call("tools/call", setOf(Module.NEURO), canLog = true, params = buildJsonObject {
            put("name", JsonPrimitive("log_overload"))
            put("arguments", buildJsonObject { put("kind", JsonPrimitive("SHUTDOWN")); put("intensity", JsonPrimitive(2)) })
        }).toString()
        assertTrue(logged, logged.contains("Logged shutdown"))
        assertEquals(2, s.neuro.events(user.id, 1).size)
    }

    // ---- The FIT export ----

    @Test
    fun `workouts and weight export as FIT files that read back`() = serverTest(services()) { s ->
        val admin = adminBrowser()
        val start = at(today.minusDays(2), 7)
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/workouts", """{"type":"RUN","start":$start,"end":${start + 40 * 60_000},"distanceM":6500,"activeCalories":420,"avgHeartRate":152,"maxHeartRate":178}""").status)
        admin.postJson("/api/workouts", """{"type":"YOGA","start":${start + 86_400_000},"end":${start + 86_400_000 + 30 * 60_000}}""")
        admin.postJson("/api/measurements", """{"metric":"WEIGHT","value":64.5,"at":${start - 3_600_000}}""")
        admin.postJson("/api/measurements", """{"metric":"BODY_FAT","value":24.5,"at":${start - 3_600_000}}""")

        val response = admin.get("/api/export/fit?from=2026-03-01&to=2026-03-20")
        assertEquals(HttpStatusCode.OK, response.status)
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(response.readRawBytes())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        assertEquals(setOf("README.txt", "weight.fit"), files.keys.filter { !it.startsWith("workout-") }.toSet())
        val runs = files.filterKeys { it.startsWith("workout-") }.values.mapNotNull { FitImporter.parse(it)?.workouts?.single() }
        assertEquals(2, runs.size)
        val run = runs.first { it.type == WorkoutType.RUN }
        assertEquals(start, run.start)
        assertEquals(40 * 60_000L, run.end - run.start)
        assertEquals(6500.0, run.distanceM!!, 1.0)
        assertEquals(152.0, run.avgHeartRate!!, 0.5)
        assertTrue(runs.any { it.type == WorkoutType.YOGA })

        val weights = mutableListOf<Pair<Float?, Float?>>()
        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(WeightScaleMesgListener { m -> weights += m.weight to m.percentFat })
        decode.read(ByteArrayInputStream(files.getValue("weight.fit")), broadcaster)
        assertEquals(64.5f, weights.single().first!!, 0.05f)
        assertEquals(24.5f, weights.single().second!!, 0.05f)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/export/fit?from=2026-03-20&to=2026-03-01").status)
    }

    @Test
    fun `a routine needs a name and steps`() = runBlocking {
        val s = services()
        val e = runCatching { s.neuro.saveRoutine(s.admin(), Routine("Evening", listOf(RoutineStep("  ")))) }.exceptionOrNull()
        assertTrue(e is ApiError)
    }
}
