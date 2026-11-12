package com.health.server

import com.health.api.ApiJson
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.DigestPrefs
import com.health.api.DoseLog
import com.health.api.IngestBatch
import com.health.api.LabResult
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Metric
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Profile
import com.health.api.PushMessage
import com.health.api.PushSubscriptionInput
import com.health.api.ReminderPrefs
import com.health.api.Sample
import com.health.api.Webhook
import com.health.api.WebhookEvent
import com.health.server.platform.Mailer
import com.health.server.platform.Scheduler
import com.health.server.platform.SmtpSettings
import com.health.server.platform.WebPushCrypto
import com.health.server.platform.WebhookService
import com.health.server.util.Random
import com.health.server.util.base64Url
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.zip.ZipInputStream

class PlatformTest {
    private val web = MockWebServer()
    private val pushed = mutableListOf<RecordedRequest>()
    private val hooked = mutableListOf<RecordedRequest>()
    /** What the hook answers, in turn (then 200). */
    private val hookAnswers = ArrayDeque<Int>()

    @Before
    fun start() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@PlatformTest) {
                when {
                    request.path!!.startsWith("/push") -> { pushed += request; MockResponse().setResponseCode(201) }
                    request.path!!.startsWith("/hook") -> { hooked += request; MockResponse().setResponseCode(hookAnswers.removeFirstOrNull() ?: 200) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        web.start()
    }

    @After
    fun stop() = web.shutdown()

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private class Browser(val p256dh: ByteArray, val private: ByteArray, val auth: ByteArray)

    private fun newBrowser(): Browser {
        val keys = WebPushCrypto.newKeyPair()
        return Browser(WebPushCrypto.publicBytes(keys.public as ECPublicKey), WebPushCrypto.privateBytes(keys.private as java.security.interfaces.ECPrivateKey), Random.bytes(16))
    }

    private fun open(request: RecordedRequest, browser: Browser): PushMessage =
        ApiJson.decodeFromString(PushMessage.serializer(), String(WebPushCrypto.decrypt(request.body.readByteArray(), browser.private, browser.p256dh, browser.auth)))

    /** Services on a clock the test moves, with the admin's id, set up in UTC. */
    private fun setUp(now: () -> Long, modules: Set<Module> = Module.DEFAULT + Module.MEDICATIONS, prefs: ReminderPrefs = ReminderPrefs()): Pair<Services, String> {
        val services = testServices(clock = now)
        runBlocking { services.bootstrap() }
        val userId = runBlocking { services.users.byUsername("admin")!!.id }
        runBlocking { services.profiles.put(userId, Profile(timezone = "UTC", modules = modules, reminders = prefs)) }
        services.platform.scheduler.webhooksInBackground = false
        services.platform.webhooks.backoff = { 0L }
        return services to userId
    }

    @Test
    fun `doses are reminded once, again while unmarked, and missed goes only to webhooks`() = runBlocking {
        var now = at("2026-11-12T07:59:00Z")
        val (services, userId) = setUp({ now })
        val platform = services.platform
        val med = services.modules.meds.save(userId, Medication("Vitamin D", dose = "1000 IU", schedule = MedSchedule(times = listOf("08:00")), stock = 30))
        val browser = newBrowser()
        platform.push.subscribe(userId, PushSubscriptionInput(web.url("/push/1").toString(), browser.p256dh.base64Url(), browser.auth.base64Url(), "Firefox"))
        val hook = platform.webhooks.create(userId, Webhook("Home Assistant", web.url("/hook").toString(), setOf(WebhookEvent.DOSE_DUE, WebhookEvent.DOSE_MISSED, WebhookEvent.DOSE_TAKEN)))

        platform.scheduler.tick(now)
        assertEquals(0, pushed.size)

        now = at("2026-11-12T08:00:00Z")
        platform.scheduler.tick(now)
        platform.scheduler.tick(now + 20_000) // the same minute again: nothing new
        assertEquals(1, pushed.size)
        assertEquals(1, hooked.size)
        val first = open(pushed[0], browser)
        // Names stay off a locked screen unless the person says otherwise.
        assertEquals("Time for your 08:00 medication", first.title)
        assertEquals("dose:${med.id}:${at("2026-11-12T08:00:00Z")}", first.tag)
        assertEquals(med.id, first.medicationId)
        assertEquals(listOf("taken", "skip"), first.actions.map { it.action })
        assertEquals("high", pushed[0].getHeader("Urgency"))
        assertEquals("aes128gcm", pushed[0].getHeader("Content-Encoding"))
        assertTrue(pushed[0].getHeader("Authorization")!!.startsWith("vapid t="))

        // A webhook without details gets times, never the medication's name; it's signed.
        val body = hooked[0].body.readUtf8()
        assertFalse(body.contains("Vitamin D"))
        assertTrue(body.contains("\"event\":\"dose.due\""))
        assertEquals("sha256=" + WebhookService.signature(hook.secret!!, body), hooked[0].getHeader("X-SAMH-Signature"))

        // Again after 30 minutes, twice, with the same tag so it replaces the first.
        now = at("2026-11-12T08:30:00Z"); platform.scheduler.tick(now)
        now = at("2026-11-12T09:00:00Z"); platform.scheduler.tick(now)
        now = at("2026-11-12T09:30:00Z"); platform.scheduler.tick(now)
        assertEquals(3, pushed.size)
        val again = open(pushed[1], browser)
        assertEquals("A 08:00 medication is still to take", again.title)
        assertEquals(first.tag, again.tag)

        // Missed after two hours: the webhook hears, the browser doesn't.
        now = at("2026-11-12T10:00:00Z"); platform.scheduler.tick(now)
        assertEquals(3, pushed.size)
        assertEquals(4, hooked.size)
        assertTrue(hooked[3].body.readUtf8().contains("\"event\":\"dose.missed\""))

        // Marking it taken tells the webhook too.
        services.modules.meds.logDose(userId, DoseLog(med.id!!, at("2026-11-12T10:05:00Z"), scheduledFor = at("2026-11-12T08:00:00Z")))
        now = at("2026-11-12T10:06:00Z"); platform.scheduler.tick(now)
        assertEquals(5, hooked.size)
        assertTrue(hooked[4].body.readUtf8().contains("\"event\":\"dose.taken\""))

        // With names allowed, the notification says which.
        services.profiles.put(userId, services.profiles.get(userId).copy(reminders = ReminderPrefs(showNames = true)))
        now = at("2026-11-13T08:00:00Z"); platform.scheduler.tick(now)
        assertEquals("Time for Vitamin D", open(pushed.last(), browser).title)
    }

    @Test
    fun `refills come once a day, and quiet hours hold back everything but doses`() = runBlocking {
        var now = at("2026-11-12T10:00:00Z")
        val (services, userId) = setUp({ now }, prefs = ReminderPrefs(quietFrom = "09:30", quietTo = "11:00", refillTime = "10:00"))
        val platform = services.platform
        services.modules.meds.save(userId, Medication("Magnesium", schedule = MedSchedule(times = listOf("21:00")), stock = 4))
        services.modules.meds.save(userId, Medication("Iron", schedule = MedSchedule(times = listOf("10:00")), stock = 60))
        val browser = newBrowser()
        platform.push.subscribe(userId, PushSubscriptionInput(web.url("/push/1").toString(), browser.p256dh.base64Url(), browser.auth.base64Url()))
        platform.webhooks.create(userId, Webhook("Lights", web.url("/hook").toString(), setOf(WebhookEvent.REFILL)))

        platform.scheduler.tick(now)
        // The 10:00 dose still sounds in quiet hours; the refill reminder waits for the webhook only.
        assertEquals(1, pushed.size)
        assertEquals("Time for your 10:00 medication", open(pushed[0], browser).title)
        assertEquals(1, hooked.size)
        assertTrue(hooked[0].body.readUtf8().contains("\"event\":\"refill\""))
        now = at("2026-11-12T10:05:00Z"); platform.scheduler.tick(now)
        assertEquals(1, hooked.size)

        // Quiet hours that wrap past midnight.
        val prefs = ReminderPrefs(quietFrom = "22:00", quietTo = "07:00")
        assertTrue(Scheduler.quiet(prefs, 23 * 60))
        assertTrue(Scheduler.quiet(prefs, 6 * 60 + 59))
        assertFalse(Scheduler.quiet(prefs, 7 * 60))
        assertFalse(Scheduler.quiet(ReminderPrefs(quietFrom = null), 23 * 60))
    }

    @Test
    fun `push encryption matches RFC 8291 and VAPID tokens verify`() {
        // RFC 8291, Appendix A.
        val d = WebPushCrypto::decode
        val asKeys = WebPushCrypto.keyPair(d("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"), d("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
        val uaPublic = d("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        val uaPrivate = d("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
        val auth = d("BTBZMqHH6r4Tts7J_aSIgg")
        val salt = d("DGv6ra1nlYgDCS1FRnbzlw")
        val plain = "When I grow up, I want to be a watermelon"
        val expected = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        val body = WebPushCrypto.encrypt(plain.toByteArray(), uaPublic, auth, asKeys, salt)
        assertEquals(expected, body.base64Url())
        assertEquals(plain, String(WebPushCrypto.decrypt(d(expected), uaPrivate, uaPublic, auth)))

        // A fresh round trip.
        val browser = newBrowser()
        val sealed = WebPushCrypto.encrypt("hello".toByteArray(), browser.p256dh, browser.auth)
        assertEquals("hello", String(WebPushCrypto.decrypt(sealed, browser.private, browser.p256dh, browser.auth)))

        val keys = WebPushCrypto.newKeyPair()
        val header = WebPushCrypto.vapid("https://push.example.net/send/abc", keys, "mailto:admin@localhost", at("2026-11-12T08:00:00Z"))
        val jwt = SignedJWT.parse(header.substringAfter("t=").substringBefore(","))
        assertTrue(jwt.verify(ECDSAVerifier(keys.public as ECPublicKey)))
        assertEquals(listOf("https://push.example.net"), jwt.jwtClaimsSet.audience)
        assertEquals((keys.public as ECPublicKey).let { WebPushCrypto.publicBytes(it).base64Url() }, header.substringAfter("k=").trim())
    }

    @Test
    fun `webhooks retry server errors, log each try, and private networks can be turned off`() = runBlocking {
        val (services, userId) = setUp({ System.currentTimeMillis() })
        val platform = services.platform
        val hook = platform.webhooks.create(userId, Webhook("Flaky", web.url("/hook").toString(), setOf(WebhookEvent.MORNING)))
        hookAnswers.addAll(listOf(500, 503))
        val payload = WebhookService.payload(WebhookEvent.TEST, 1L, "admin", "A test", com.health.server.platform.json("x" to 1))
        val result = platform.webhooks.deliver(userId, hook, WebhookEvent.TEST, payload)
        assertTrue(result.ok)
        assertEquals(3, hooked.size)
        val tries = platform.webhooks.deliveries(userId)
        assertEquals(listOf(true, false, false), tries.map { it.ok })
        assertEquals(503, tries[1].status)
        assertEquals(true, platform.webhooks.list(userId).single().lastDelivery?.ok)

        // A 4xx isn't tried again.
        hookAnswers.add(404)
        assertFalse(platform.webhooks.deliver(userId, hook, WebhookEvent.TEST, payload).ok)
        assertEquals(4, hooked.size)

        services.settings.update { it.copy(webhooksPrivateNetworks = false) }
        val refused = platform.webhooks.deliver(userId, hook, WebhookEvent.TEST, payload)
        assertFalse(refused.ok)
        assertTrue(refused.error!!.contains("private addresses are turned off"))
        assertEquals(4, hooked.size)
    }

    @Test
    fun `webhook addresses are checked, and the test button calls it`() = serverTest { services ->
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/webhooks", """{"name":"x","url":"ftp://example.com","events":["MORNING"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/webhooks", """{"name":"x","url":"${web.url("/hook")}","events":[]}""").status)
        val made = admin.postJson("/api/webhooks", """{"name":"HA","url":"${web.url("/hook")}","events":["MORNING","DOSE_DUE"]}""").obj()
        assertTrue(made.str("secret").startsWith("whsec_"))
        val listed = admin.get("/api/webhooks").json().jsonArray.single().jsonObject
        assertNull(listed["secret"])
        services.platform.webhooks.backoff = { 0L }
        val test = admin.post("/api/webhooks/${made.str("id")}/test") { csrf() }.obj()
        assertEquals("true", test.str("ok"))
        assertEquals("test", hooked.single().getHeader("X-SAMH-Event"))
    }

    @Test
    fun `Home Assistant and watch keys open only their own endpoints`() = serverTest { services ->
        val admin = adminBrowser()
        val ha = admin.postJson("/api/integrations/keys", """{"name":"Home Assistant"}""").obj()
        assertTrue(ha.str("url").endsWith("/api/integrations/state"))
        val watch = admin.postJson("/api/watch/keys", "{}").obj()
        val client = createClient { followRedirects = false }

        val state = client.get("/api/integrations/state") { bearerAuth(ha.str("token")) }
        assertEquals(HttpStatusCode.OK, state.status)
        assertNotNull(state.obj()["steps"])
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(ha.str("token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/day/today") { bearerAuth(ha.str("token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/watch/today") { bearerAuth(ha.str("token")) }.status)

        assertEquals(HttpStatusCode.OK, client.get("/api/watch/today") { bearerAuth(watch.str("token")) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/watch/log") { bearerAuth(watch.str("token")); json("""{"kind":"WATER","value":250}""") }.status)
        assertEquals(250, services.days.summary(services.users.byUsername("admin")!!.id, "today").waterMl)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/watch/log") { bearerAuth(watch.str("token")); json("""{"kind":"HABIT","ref":"x"}""") }.status)
        val batch = """{"device":{"kind":"PEBBLE","identifier":"pebble-1","name":"Pebble Time 2"},"samples":[{"metric":"STEPS","start":1794470400000,"end":1794471300000,"value":120}]}"""
        assertEquals(HttpStatusCode.OK, client.post("/api/ingest") { bearerAuth(watch.str("token")); json(batch) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(watch.str("token")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/integrations/state") { bearerAuth(watch.str("token")) }.status)

        // The watch app's settings page only ever hands its key back to the watch app.
        assertEquals(HttpStatusCode.BadRequest, client.get("/pebble/config?return_to=https://example.com/steal").status)
        val ok = client.get("/pebble/config?return_to=pebblejs://close%23")
        assertEquals(HttpStatusCode.Found, ok.status)
        assertEquals("/watch-setup?return_to=pebblejs%3A%2F%2Fclose%23", ok.headers[HttpHeaders.Location])

        // Revoking the Home Assistant key stops it.
        assertEquals(1, admin.get("/api/integrations/keys").json().jsonArray.size)
        admin.delete("/api/integrations/keys/${ha.str("id")}") { csrf() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/integrations/state") { bearerAuth(ha.str("token")) }.status)
    }

    private fun steps(services: Services, userId: String, day: String, count: Int) = runBlocking {
        val start = at("${day}T12:00:00Z")
        services.ingest.ingest(userId, IngestBatch(DeviceInfo(DeviceKind.OTHER, "test", "Test band"), listOf(Sample(Metric.STEPS, start, start + 3_600_000, count.toDouble()))))
    }

    @Test
    fun `the digest is made at the person's time, kept sealed, and emailed without features they kept out`() = runBlocking {
        var now = at("2026-11-15T17:59:00Z") // a Sunday
        val (services, userId) = setUp({ now }, modules = Module.DEFAULT + Module.MIND)
        val platform = services.platform
        val mails = mutableListOf<Triple<String, String, String>>()
        platform.mailer = Mailer { to, subject, text, _ -> mails += Triple(to, subject, text) }
        services.settings.update { it.copy(smtp = SmtpSettings(enabled = true, host = "smtp.example.com", from = "health@example.com")) }
        services.profiles.put(userId, services.profiles.get(userId).copy(digest = DigestPrefs(email = "me@example.com", emailEnabled = true)))
        for (day in 9..15) steps(services, userId, "2026-11-%02d".format(day), 9000)
        for (day in 2..8) steps(services, userId, "2026-11-%02d".format(day), 6000)
        services.modules.mind.saveMood(userId, MoodEntry(at = at("2026-11-14T09:00:00Z"), mood = 4))

        platform.scheduler.tick(now)
        assertNull(platform.digests.latest(userId))
        now = at("2026-11-15T18:00:00Z")
        platform.scheduler.tick(now)
        platform.scheduler.tick(now + 60_000)
        val digest = platform.digests.latest(userId)!!
        assertEquals("2026-11-09", digest.weekStart)
        assertEquals("2026-11-15", digest.weekEnd)
        assertTrue(digest.headline.contains("9,000 steps a day"))
        val activity = digest.sections.first { it.module == Module.ACTIVITY }
        assertTrue(activity.lines.first(), activity.lines.first().contains("up 3,000"))
        assertTrue(digest.sections.any { it.module == Module.MIND })
        // Kept sealed.
        val stored = services.db.tx { one("SELECT data FROM digests WHERE user_id = ?", userId) { it.string("data") } }!!
        assertTrue(stored.startsWith("v1:"))
        assertFalse(stored.contains("steps"))
        // Emailed once, without Mind (a sensitive feature not listed for email).
        assertEquals(1, mails.size)
        assertEquals("me@example.com", mails[0].first)
        assertTrue(mails[0].third.contains("Activity"))
        assertFalse(mails[0].third.contains("Mood"))
        val user = services.users.byId(userId)!!
        val withMind = platform.digests.emailContent(user, services.profiles.get(userId).copy(digest = DigestPrefs(emailModules = setOf(Module.MIND))), digest)
        assertTrue(withMind.second.contains("Mood averaged 4.0"))
    }

    @Test
    fun `the report and export hold what was recorded`() = serverTest { services ->
        val userId = services.users.byUsername("admin")!!.id
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        services.profiles.put(userId, Profile(timezone = "UTC", modules = Module.DEFAULT + Module.MEDICATIONS + Module.MIND))
        for (back in 1..10) steps(services, userId, today.minusDays(back.toLong()).toString(), 7000 + back * 100)
        services.modules.meds.save(userId, Medication("=Vitamin D", dose = "1000 IU"))
        services.modules.meds.saveLab(userId, LabResult("Ferritin", 42.0, "µg/L", at("${today.minusDays(5)}T09:00:00Z"), low = 30.0, high = 200.0))
        services.modules.meds.saveLab(userId, LabResult("Ferritin", 55.0, "µg/L", at("${today.minusDays(2)}T09:00:00Z"), low = 30.0, high = 200.0))
        val admin = adminBrowser()

        val response = admin.get("/api/report.pdf?from=${today.minusDays(14)}&to=$today")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/pdf", response.headers[HttpHeaders.ContentType])
        val text = Loader.loadPDF(response.bodyAsBytes()).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Health report"))
        assertTrue(text.contains("Administrator"))
        assertTrue(text.contains("Activity"))
        assertTrue(text.contains("Medications"))
        assertTrue(text.contains("Ferritin"))
        assertTrue(text.contains("Page 1 of"))
        val onlyActivity = admin.get("/api/report.pdf?sections=ACTIVITY").bodyAsBytes()
        val small = Loader.loadPDF(onlyActivity).use { PDFTextStripper().getText(it) }
        assertTrue(small.contains("Steps"))
        assertFalse(small.contains("Ferritin"))

        val zip = admin.get("/api/export.zip?from=${today.minusDays(30)}&to=$today").bodyAsBytes()
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { files[it.name] = String(z.readBytes()) } }
        assertTrue(files.keys.containsAll(listOf("README.txt", "daily.csv", "samples.csv", "workouts.csv", "medications.csv", "labs.csv", "moods.csv", "fhir.json")))
        assertTrue(files["daily.csv"]!!.lines().first().startsWith("date,readiness,sleep_score"))
        // Something a spreadsheet would run stays text.
        assertTrue(files["medications.csv"]!!.contains("'=Vitamin D"))
        val bundle = Json.parseToJsonElement(files["fhir.json"]!!).jsonObject
        assertEquals("Bundle", bundle["resourceType"]!!.jsonPrimitive.content)
        assertEquals("collection", bundle["type"]!!.jsonPrimitive.content)
        val resources = bundle["entry"]!!.jsonArray.map { it.jsonObject["resource"]!!.jsonObject }
        assertEquals("Patient", resources.first()["resourceType"]!!.jsonPrimitive.content)
        assertTrue(resources.any { it["resourceType"]!!.jsonPrimitive.content == "MedicationStatement" })
        assertTrue(resources.any { it["code"]?.jsonObject?.get("coding")?.toString()?.contains("41950-7") == true })
        assertTrue(resources.any { it["code"]?.jsonObject?.get("text")?.jsonPrimitive?.content == "Ferritin" && it["referenceRange"] != null })
        val fhir = admin.get("/api/export/fhir")
        assertEquals("application/fhir+json", fhir.headers[HttpHeaders.ContentType]!!.substringBefore(';'))
    }

    @Test
    fun `upcoming reminders, push subscriptions and the connector's status`() = serverTest { services ->
        val userId = services.users.byUsername("admin")!!.id
        services.profiles.put(userId, Profile(timezone = "UTC", modules = Module.DEFAULT + Module.MEDICATIONS))
        services.modules.meds.save(userId, Medication("Vitamin D", schedule = MedSchedule(times = listOf("00:00", "06:00", "12:00", "18:00"))))
        val admin = adminBrowser()
        val upcoming = admin.get("/api/reminders/upcoming?hours=24").json().jsonArray
        assertTrue(upcoming.size >= 4)
        assertNotNull(upcoming.first().jsonObject["at"])

        val key = admin.get("/api/push/key").obj().str("publicKey")
        assertEquals(65, WebPushCrypto.decode(key).size)
        assertEquals(key, admin.get("/api/push/key").obj().str("publicKey"))
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/push/test", "{}").status)
        val browser = newBrowser()
        val sub = admin.postJson("/api/push/subscriptions", """{"endpoint":"${web.url("/push/9")}","p256dh":"${browser.p256dh.base64Url()}","auth":"${browser.auth.base64Url()}","name":"Laptop"}""")
        assertEquals(HttpStatusCode.OK, sub.status)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/push/subscriptions", """{"endpoint":"https://push.example.com/x","p256dh":"AAAA","auth":"${browser.auth.base64Url()}"}""").status)
        assertEquals("1", admin.postJson("/api/push/test", "{}").obj().str("sent"))
        assertEquals("Notifications are on", open(pushed.single(), browser).title)

        val link = admin.postJson("/api/me/connectors", "{}").obj().str("token")
        val reply = createClient { }.post("/mcp/$link") {
            json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"notifications_status","arguments":{}}}""")
        }.bodyAsText()
        assertTrue(reply, reply.contains("Browsers with notifications: 1"))
        assertTrue(reply.contains("Laptop"))
    }

    @Test
    fun `admins set up mail, and its password is kept apart`() = serverTest { services ->
        val admin = adminBrowser()
        val sent = mutableListOf<String>()
        services.platform.mailer = Mailer { to, _, _, _ -> sent += to }
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/admin/smtp", """{"settings":{"enabled":true,"host":"","from":""}}""").status)
        val saved = admin.putJson("/api/admin/smtp", """{"settings":{"enabled":true,"host":"smtp.example.com","port":587,"security":"STARTTLS","username":"me","from":"Health <health@example.com>"},"password":"hunter22"}""").obj()
        assertEquals("true", saved.str("passwordSet"))
        assertFalse(admin.get("/api/admin/settings").bodyAsText().contains("hunter22"))
        val stored = services.db.tx { one("SELECT value FROM settings WHERE key = 'smtp.password'") { it.string("value") } }!!
        assertFalse(stored.contains("hunter22"))
        assertEquals("true", admin.get("/api/digest/smtp").obj().str("configured"))
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/admin/smtp/test", """{"to":"me@example.com"}""").status)
        assertEquals(listOf("me@example.com"), sent)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/admin/smtp/test", """{"to":"nope"}""").status)
    }
}
