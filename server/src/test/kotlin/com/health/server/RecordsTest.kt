package com.health.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.health.api.ApiJson
import com.health.api.DataPath
import com.health.api.RecordFlag
import com.health.api.RecordKind
import com.health.api.RecordOrganization
import com.health.api.RecordProvider
import com.health.server.auth.OidcClient
import com.health.server.care.SharedView
import com.health.server.db.Database
import com.health.server.db.Schema
import com.health.server.food.OpenFoodFacts
import com.health.server.records.Fhir
import com.health.server.records.RecordsService
import com.health.server.util.Days
import com.health.server.util.Passwords
import com.health.server.util.Random
import com.health.server.util.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Medical records: connecting a portal with SMART on FHIR (against a stand-in Epic and athenahealth),
 * reading the record into plain items and Labs, keeping it up to date, and who never sees it.
 */
class RecordsTest {
    private var now = Instant.parse("2026-11-18T15:00:00Z").toEpochMilli()

    /** The stand-in: Epic's list, an Epic organization's FHIR server and sign-in, and athenahealth's. */
    private inner class Fake : Dispatcher() {
        val asked = CopyOnWriteArrayList<RecordedRequest>()
        val tokenRequests = CopyOnWriteArrayList<Pair<Map<String, String>, String?>>()
        val revoked = CopyOnWriteArrayList<Map<String, String>>()
        val validTokens = mutableSetOf<String>()
        var refreshToken = "RT-epic-1"
        var issued = 0
        var expectedChallenge: String? = null
        var labsVersion = 1
        var accessGone = false

        lateinit var origin: String
        val mgb get() = "$origin/mgb/api/FHIR/R4"
        val athena get() = "$origin/athena/fhir/r4"

        override fun dispatch(request: RecordedRequest): MockResponse {
            asked += request
            val url = request.requestUrl!!
            val path = url.encodedPath
            return when {
                path == "/Endpoints/R4" -> json(directory())
                path == "/mgb/api/FHIR/R4/.well-known/smart-configuration" -> json(
                    """{"authorization_endpoint":"$origin/mgb/oauth2/authorize","token_endpoint":"$origin/mgb/oauth2/token",
                    "capabilities":["launch-standalone","client-public","client-confidential-symmetric","permission-offline"],"code_challenge_methods_supported":["S256"]}""",
                )
                // athenahealth's stand-in only says it in its CapabilityStatement.
                path == "/athena/fhir/r4/metadata" -> json(
                    """{"resourceType":"CapabilityStatement","rest":[{"mode":"server","security":{"extension":[{"url":"http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris",
                    "extension":[{"url":"authorize","valueUri":"$origin/athena/oauth2/authorize"},{"url":"token","valueUri":"$origin/athena/oauth2/token"},{"url":"revoke","valueUri":"$origin/athena/oauth2/revoke"}]}]}}]}""",
                    "application/fhir+json",
                )
                path.endsWith("/oauth2/token") -> token(form(request), request.getHeader("Authorization"), athena = path.startsWith("/athena"))
                path == "/athena/oauth2/revoke" -> { revoked += form(request); MockResponse().setResponseCode(200) }
                path.startsWith("/mgb/api/FHIR/R4/") -> fhir(request, path.removePrefix("/mgb/api/FHIR/R4/"), url, "eP123")
                path.startsWith("/athena/fhir/r4/") -> fhir(request, path.removePrefix("/athena/fhir/r4/"), url, "a-777")
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(body: String, type: String = "application/json") = MockResponse().setHeader("Content-Type", type).setBody(body)

        private fun form(request: RecordedRequest): Map<String, String> = request.body.readUtf8().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

        fun directory() = """{"resourceType":"Bundle","type":"collection","total":3,"entry":[
            {"resource":{"resourceType":"Endpoint","id":"e1","contained":[{"resourceType":"Organization","id":"o1","name":"Mass General Brigham"}],
              "status":"active","connectionType":{"system":"http://terminology.hl7.org/CodeSystem/endpoint-connection-type","code":"hl7-fhir-rest"},
              "name":"Mass General Brigham","managingOrganization":{"reference":"o1"},"payloadMimeType":["application/fhir+json"],"address":"$mgb/"}},
            {"resource":{"resourceType":"Endpoint","id":"e2","status":"active","name":"Brigham Valley Clinic","address":"https://fhir.brighamvalley.example/api/FHIR/R4/"}},
            {"resource":{"resourceType":"Endpoint","id":"e3","status":"active","name":"General Hospital of Elsewhere","address":"https://fhir.elsewhere.example/api/FHIR/R4/"}}
        ]}"""

        private fun token(form: Map<String, String>, auth: String?, athena: Boolean): MockResponse {
            tokenRequests += form to auth
            val bad = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"error":"invalid_grant","error_description":"secret-upstream-words"}""")
            return when (form["grant_type"]) {
                "authorization_code" -> {
                    if (form["code"] != "good-code" || !form["redirect_uri"].orEmpty().endsWith("/api/records/callback")) return bad
                    if (OidcClient.challengeOf(form["code_verifier"].orEmpty()) != expectedChallenge) return bad
                    val access = "AT-${if (athena) "athena" else "epic"}-${++issued}"
                    validTokens += access
                    if (athena) {
                        json("""{"access_token":"$access","token_type":"Bearer","expires_in":3600,"scope":"launch/patient patient/Patient.r","refresh_token":"RT-athena","patient":"a-777"}""")
                    } else {
                        refreshToken = "RT-epic-1"
                        json("""{"access_token":"$access","token_type":"Bearer","expires_in":3600,"scope":"launch/patient patient/*.read","refresh_token":"$refreshToken","patient":"eP123","id_token":"x.y.z"}""")
                    }
                }
                "refresh_token" -> {
                    if (form["refresh_token"] != refreshToken) return bad
                    val access = "AT-epic-${++issued}"
                    validTokens += access
                    // Epic may rotate the refresh token: the new one must be kept.
                    refreshToken = "RT-epic-$issued"
                    json("""{"access_token":"$access","token_type":"Bearer","expires_in":3600,"refresh_token":"$refreshToken"}""")
                }
                else -> bad
            }
        }

        private fun fhir(request: RecordedRequest, rest: String, url: HttpUrl, patient: String): MockResponse {
            val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
            if (accessGone || bearer !in validTokens) return MockResponse().setResponseCode(401)
            if (url.queryParameter("patient")?.let { it != patient } == true) return MockResponse().setResponseCode(403)
            val fhirJson = "application/fhir+json"
            if (patient == "a-777") {
                return when (rest) {
                    "Patient/a-777" -> json("""{"resourceType":"Patient","id":"a-777","managingOrganization":{"reference":"Organization/1","display":"Dover Family Practice"}}""", fhirJson)
                    "Immunization" -> MockResponse().setResponseCode(403).setBody("""{"resourceType":"OperationOutcome"}""")
                    "Condition" -> json(bundle(listOf(condition("a-c1", "Asthma", "active", "2010-01-01", null, "a-777"))), fhirJson)
                    else -> json(bundle(emptyList()), fhirJson)
                }
            }
            return when (rest) {
                "Patient/eP123" -> json("""{"resourceType":"Patient","id":"eP123","name":[{"text":"Someone"}]}""", fhirJson)
                "Observation" -> when (url.queryParameter("category")) {
                    "laboratory" -> json(if (url.queryParameter("page") == "2") labsPage2() else labsPage1(), fhirJson)
                    "vital-signs" -> json(bundle(vitals()), fhirJson)
                    else -> MockResponse().setResponseCode(400)
                }
                "MedicationRequest" -> json(bundle(medications()), fhirJson)
                "Condition" -> json(
                    bundle(listOf(condition("c1", "Hypothyroidism", "active", "2019-05-10", null, patient), condition("c2", "Migraine", "resolved", "2016-02-01", "2021-03-01", patient))),
                    fhirJson,
                )
                "AllergyIntolerance" -> json(
                    bundle(
                        listOf(
                            """{"resourceType":"AllergyIntolerance","id":"al1","clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical","code":"active"}]},
                            "code":{"text":"Penicillins","coding":[{"display":"PENICILLINS"}]},"patient":{"reference":"Patient/eP123"},"recordedDate":"2015-06-01T14:00:00Z",
                            "reaction":[{"manifestation":[{"text":"Hives"}],"severity":"severe"}]}""",
                        ),
                    ),
                    fhirJson,
                )
                "Immunization" -> json(
                    bundle(
                        listOf(
                            """{"resourceType":"Immunization","id":"im1","status":"completed","vaccineCode":{"text":"Influenza vaccine","coding":[{"system":"http://hl7.org/fhir/sid/cvx","code":"141"}]},
                            "patient":{"reference":"Patient/eP123"},"occurrenceDateTime":"2025-10-15"}""",
                        ),
                    ),
                    fhirJson,
                )
                // Not offered here: skipped quietly.
                "Procedure" -> MockResponse().setResponseCode(404).setHeader("Content-Type", fhirJson).setBody("""{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"not-supported"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun bundle(resources: List<String>, next: String? = null, outcome: Boolean = false) = """{"resourceType":"Bundle","type":"searchset","total":${resources.size},
            "link":[{"relation":"self","url":"$mgb/x"}${next?.let { """,{"relation":"next","url":"$it"}""" }.orEmpty()}],
            "entry":[${(resources.map { """{"fullUrl":"$mgb/x","resource":$it,"search":{"mode":"match"}}""" } +
            listOfNotNull(if (outcome) """{"resource":{"resourceType":"OperationOutcome","issue":[{"severity":"warning","code":"processing","diagnostics":"Some results hidden"}]},"search":{"mode":"outcome"}}""" else null)).joinToString(",")}]}"""

        private fun lab(id: String, name: String, value: Double?, unit: String?, low: Double?, high: Double?, at: String, extra: String = "", subject: String = "eP123", status: String = "final") =
            """{"resourceType":"Observation","id":"$id","status":"$status","category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory","display":"Laboratory"}],"text":"Laboratory"}],
            "code":{"coding":[{"system":"http://loinc.org","code":"0000-0","display":"${name.uppercase()}"}],"text":"$name"},"subject":{"reference":"Patient/$subject","display":"Someone"},
            "effectiveDateTime":"$at","issued":"$at"${value?.let { ""","valueQuantity":{"value":$it,"unit":"$unit","system":"http://unitsofmeasure.org","code":"$unit"}""" }.orEmpty()}
            ${if (low != null || high != null) ""","referenceRange":[{${listOfNotNull(low?.let { """"low":{"value":$it,"unit":"$unit"}""" }, high?.let { """"high":{"value":$it,"unit":"$unit"}""" }).joinToString(",")},"text":"x"}]""" else ""}$extra}"""

        fun labsPage1() = bundle(
            listOf(
                lab("obs-e2", "Estradiol", if (labsVersion == 1) 142.0 else 180.0, "pg/mL", 30.0, 400.0, "2026-10-02T13:20:00Z"),
                lab("obs-tsh", "TSH", 5.2, "mIU/L", 0.4, 4.0, "2026-10-02T13:20:00Z", extra = ""","interpretation":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation","code":"H","display":"High"}],"text":"High"}]"""),
                lab("obs-not-mine", "Ferritin", 20.0, "ng/mL", 15.0, 150.0, "2026-10-02T13:20:00Z", subject = "someone-else"),
            ),
            next = "$mgb/Observation?patient=eP123&category=laboratory&page=2", outcome = true,
        )

        fun labsPage2() = bundle(
            listOfNotNull(
                lab("obs-covid", "SARS-CoV-2 RNA", null, null, null, null, "2026-03-01T10:00:00-05:00", extra = ""","valueCodeableConcept":{"text":"Not detected"}"""),
                lab("obs-err", "Sodium", 139.0, "mmol/L", 135.0, 145.0, "2026-10-02T13:20:00Z", status = "entered-in-error"),
                if (labsVersion == 1) lab("obs-k", "Potassium", 4.1, "mmol/L", 3.5, 5.1, "2026-09-01") else null,
            ),
        )

        fun vitals() = listOf(
            """{"resourceType":"Observation","id":"v-bp","status":"final","category":[{"coding":[{"code":"vital-signs"}]}],"code":{"text":"Blood pressure","coding":[{"code":"85354-9"}]},
            "subject":{"reference":"Patient/eP123"},"effectiveDateTime":"2026-10-02T13:00:00Z",
            "component":[{"code":{"coding":[{"code":"8480-6"}]},"valueQuantity":{"value":118,"unit":"mm[Hg]"}},{"code":{"coding":[{"code":"8462-4"}]},"valueQuantity":{"value":76,"unit":"mm[Hg]"}}]}""",
            """{"resourceType":"Observation","id":"v-wt","status":"final","code":{"text":"Weight"},"subject":{"reference":"Patient/eP123"},"effectiveDateTime":"2026-10-02T13:00:00Z","valueQuantity":{"value":70.3,"unit":"kg"}}""",
        )

        fun medications() = listOf(
            """{"resourceType":"MedicationRequest","id":"mr1","status":"active","intent":"order","medicationReference":{"reference":"Medication/eMed1","display":"estradiol 2 MG tablet"},
            "subject":{"reference":"Patient/eP123"},"authoredOn":"2025-04-01","dosageInstruction":[{"text":"Take 1 tablet (2 mg total) by mouth daily.","doseAndRate":[{"doseQuantity":{"value":2,"unit":"mg"}}]}]}""",
            """{"resourceType":"MedicationRequest","id":"mr2","status":"stopped","intent":"order","contained":[{"resourceType":"Medication","id":"m1","code":{"text":"spironolactone 100 MG tablet"}}],
            "medicationReference":{"reference":"#m1"},"subject":{"reference":"Patient/eP123"},"authoredOn":"2024-01-10T09:00:00Z","dosageInstruction":[{"text":"Take 1 tablet by mouth twice daily."}]}""",
            """{"resourceType":"MedicationRequest","id":"mr3","status":"active","intent":"plan","medicationCodeableConcept":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","display":"Vitamin D3 50 MCG (2000 UT) capsule"}]},
            "subject":{"reference":"Patient/eP123"}}""",
        )

        fun condition(id: String, name: String, status: String, onset: String, abated: String?, patient: String) =
            """{"resourceType":"Condition","id":"$id","clinicalStatus":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-clinical","code":"$status"}]},
            "category":[{"coding":[{"code":"problem-list-item"}]}],"code":{"text":"$name"},"subject":{"reference":"Patient/$patient"},"onsetDateTime":"$onset","recordedDate":"$onset"${abated?.let { ""","abatementDateTime":"$it"""" }.orEmpty()}}"""
    }

    private val fake = Fake()
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
        fake.origin = url("/").toString().trimEnd('/')
    }

    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger).also { it.addAppender(logs) }

    @After fun stop() {
        runCatching { server.shutdown() }
        root.detachAppender(logs)
    }

    private fun services(httpsOnly: Boolean = false): Services {
        val db = Database.inMemory()
        Schema.migrate(db)
        return Services(
            ServerConfig(dataDir = Files.createTempDirectory("samh-test").toFile(), adminPassword = "break-glass-pw", outboundLoopback = true),
            db, SecretBox(Random.bytes(32)), passwords = Passwords(iterations = 1_000), clock = { now },
            foodDatabaseUrls = OpenFoodFacts.Urls("http://127.0.0.1:9/search", "http://127.0.0.1:9/product/"),
            recordsUrls = RecordsService.Urls("${fake.origin}/Endpoints/R4", fake.athena, httpsOnly),
        ).also { it.medicalRecords.firstImportWaitMs = 30_000 }
    }

    private suspend fun setUp(s: Services, epic: Boolean = true, athena: Boolean = true) {
        s.settings.update { it.copy(records = RecordsSettings(epic = PortalApp(epic, "epic-client-id"), athena = PortalApp(athena, "athena-client-id"))) }
    }

    /** Everything the apps were sent, to check no access ever is. */
    private val answers = CopyOnWriteArrayList<String>()

    private suspend fun HttpResponse.seen(): HttpResponse = also { answers += bodyAsText() + " " + headers.entries().joinToString() }

    private fun ApplicationTestBuilder.noRedirects(): HttpClient = createClient { install(HttpCookies); followRedirects = false }

    private suspend fun HttpClient.login(username: String, password: String) = post("/api/auth/login") { json("""{"username":"$username","password":"$password"}""") }

    /** Starts connecting; returns the portal's sign-in address. */
    private suspend fun HttpClient.connect(base: String, provider: RecordProvider = RecordProvider.EPIC, app: Boolean = false): HttpUrl {
        val r = postJson("/api/records/connect", """{"organization":"Anything","fhirBaseUrl":"$base","provider":"$provider","app":$app}""").seen()
        assertEquals(r.bodyAsText(), HttpStatusCode.OK, r.status)
        return r.obj().str("url").toHttpUrl().also { fake.expectedChallenge = it.queryParameter("code_challenge") }
    }

    private suspend fun HttpClient.callback(query: String): String {
        val r = get("/api/records/callback?$query").seen()
        assertEquals(HttpStatusCode.Found, r.status)
        return r.headers[HttpHeaders.Location]!!
    }

    private fun param(location: String, name: String): String? =
        location.substringAfter('?').split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let { URLDecoder.decode(it, Charsets.UTF_8) }

    // ---- Pure pieces

    @Test
    fun `Epic's list, searched by words, and the FHIR shapes in plain words`() {
        val text = Fake().also { it.origin = "https://fhir.mgb.example" }.directory()
        val all = RecordsService.parseDirectory(text)
        assertEquals(listOf("Brigham Valley Clinic", "General Hospital of Elsewhere", "Mass General Brigham"), all.map { it.name })
        assertEquals("https://fhir.mgb.example/mgb/api/FHIR/R4", all.last().fhirBaseUrl)
        // Plain http is never on the list for real.
        assertTrue(RecordsService.parseDirectory(text.replace("https://fhir.mgb.example", "http://fhir.mgb.example")).none { it.name == "Mass General Brigham" })
        assertEquals(listOf("Mass General Brigham"), RecordsService.search(all, "mass gen").map { it.name })
        // Names starting with the word first.
        assertEquals(listOf("Brigham Valley Clinic", "Mass General Brigham"), RecordsService.search(all, "BRIGHAM").map { it.name })
        assertEquals(listOf("General Hospital of Elsewhere", "Mass General Brigham"), RecordsService.search(all, "general").map { it.name })
        assertTrue(RecordsService.search(all, "  ").isEmpty())
        assertEquals(25, RecordsService.search((1..40).map { RecordOrganization("Clinic $it", "https://x$it.example") }, "clinic").size)

        val zone = Days(ZoneOffset.UTC)
        fun obj(s: String) = ApiJson.parseToJsonElement(s).jsonObject
        val f = Fake().also { it.origin = "https://x.example" }
        val e2 = Fhir.map(obj(f.labsPage1().let { ApiJson.parseToJsonElement(it).jsonObject["entry"]!!.jsonArray[0].jsonObject["resource"].toString() }), RecordKind.LAB, "MGB", zone, "eP123")!!
        assertEquals("Observation/obs-e2", e2.key)
        assertEquals("Estradiol", e2.item.title)
        assertEquals("142 pg/mL (usual 30–400)", e2.item.detail)
        assertEquals("2026-10-02", e2.item.date)
        assertNull(e2.item.flag)
        assertEquals(Fhir.LabValue(142.0, "pg/mL", Instant.parse("2026-10-02T13:20:00Z").toEpochMilli(), 30.0, 400.0), e2.lab)
        // Flags from the record, else from the range; a comparator isn't a number to chart.
        val low = obj("""{"resourceType":"Observation","id":"x","status":"final","code":{"text":"Ferritin"},"valueQuantity":{"value":9,"unit":"ng/mL"},"referenceRange":[{"low":{"value":15}}],"effectiveDateTime":"2026-01-01"}""")
        Fhir.map(low, RecordKind.LAB, "MGB", zone, null)!!.let {
            assertEquals(RecordFlag.LOW, it.item.flag)
            assertEquals("9 ng/mL (usual 15 or more)", it.item.detail)
            // A day alone is midday.
            assertEquals(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(), it.lab!!.at)
        }
        val under = obj("""{"resourceType":"Observation","id":"y","status":"final","code":{"coding":[{"display":"CRP"}]},"valueQuantity":{"value":0.5,"comparator":"<","unit":"mg/L"},"effectiveDateTime":"2026-01-01"}""")
        Fhir.map(under, RecordKind.LAB, "MGB", zone, null)!!.let {
            assertEquals("CRP", it.item.title)
            assertEquals("<0.5 mg/L", it.item.detail)
            assertNull(it.lab)
        }
        // Someone else's, entered in error, or nothing to call it: not shown.
        assertNull(Fhir.map(obj(low.toString().replace("\"id\":\"x\"", "\"id\":\"x\",\"subject\":{\"reference\":\"Patient/other\"}")), RecordKind.LAB, "MGB", zone, "eP123"))
        assertNull(Fhir.map(obj(low.toString().replace("final", "entered-in-error")), RecordKind.LAB, "MGB", zone, null))
        assertNull(Fhir.map(obj("""{"resourceType":"Observation","id":"z","status":"final"}"""), RecordKind.LAB, "MGB", zone, null))
        // A medication: its name and dose, offered as one of mine, not active when stopped.
        val meds = f.medications().map { Fhir.map(obj(it), RecordKind.MEDICATION, "MGB", zone, "eP123")!!.item }
        assertEquals(listOf("estradiol 2 MG tablet", "spironolactone 100 MG tablet", "Vitamin D3 50 MCG (2000 UT) capsule"), meds.map { it.title })
        assertEquals("Take 1 tablet (2 mg total) by mouth daily.", meds[0].detail)
        assertEquals("2 mg", meds[0].medication!!.dose)
        assertTrue(meds[0].medication!!.active)
        assertEquals("2025-04-01", meds[0].medication!!.startedOn)
        assertFalse(meds[1].medication!!.active)
        assertEquals("Take 1 tablet by mouth twice daily.", meds[1].medication!!.dose)
        assertEquals("stopped", meds[1].status)
    }

    // ---- Connecting, reading, keeping it up to date

    @Test
    fun `connect a portal, read the record into items and Labs, and keep it up to date`() = serverTest(services()) { s ->
        setUp(s)
        s.settings.setRecordSecret(RecordProvider.EPIC, "epic-client-secret")
        val me = noRedirects().also { it.login("admin", "break-glass-pw") }
        val meId = s.users.list().first { it.username == "admin" }.id

        val state = me.get("/api/records").seen().obj()
        assertEquals(listOf("EPIC", "ATHENA"), state["providers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(state["paused"]!!.jsonPrimitive.boolean)

        // Finding the organization: Epic's list (asked once, then kept), and athenahealth's one entry.
        val found = me.get("/api/records/organizations?q=mass%20general").seen().json().jsonArray.map { it.jsonObject }
        assertEquals(listOf("Mass General Brigham", RecordsService.ATHENA_NAME), found.map { it.str("name") })
        assertEquals(fake.mgb, found[0].str("fhirBaseUrl"))
        assertEquals(fake.athena, found[1].str("fhirBaseUrl"))
        me.get("/api/records/organizations?q=brigham").seen()
        assertEquals(1, fake.asked.count { it.requestUrl!!.encodedPath == "/Endpoints/R4" })

        // An address that isn't on the list is never asked (the server would otherwise fetch anything).
        val before = fake.asked.size
        for (bad in listOf("https://evil.example/fhir", "http://169.254.169.254/latest", "${fake.mgb}/../other", "${fake.origin}/athena/fhir/r4/x")) {
            val r = me.postJson("/api/records/connect", """{"organization":"Mass General Brigham","fhirBaseUrl":"$bad","provider":"EPIC"}""")
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
        // athenahealth's address only as athenahealth's.
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.athena}","provider":"EPIC"}""").status)
        assertEquals(before, fake.asked.size)

        // The sign-in address: SMART discovery, PKCE S256, a state bound to me, the audience.
        val authorize = me.connect(fake.mgb + "/")
        assertEquals("${fake.origin}/mgb/oauth2/authorize", authorize.newBuilder().query(null).build().toString())
        assertEquals("code", authorize.queryParameter("response_type"))
        assertEquals("epic-client-id", authorize.queryParameter("client_id"))
        assertEquals("http://localhost/api/records/callback", authorize.queryParameter("redirect_uri"))
        assertEquals("launch/patient openid fhirUser offline_access patient/*.read", authorize.queryParameter("scope"))
        assertEquals(fake.mgb, authorize.queryParameter("aud"))
        assertEquals("S256", authorize.queryParameter("code_challenge_method"))
        assertEquals(43, authorize.queryParameter("code_challenge")!!.length)
        val stateParam = authorize.queryParameter("state")!!
        assertEquals(43, stateParam.length)

        // Back from the portal: the code exchanged with the secret (HTTP Basic), the record read.
        val location = me.callback("code=good-code&state=$stateParam")
        val id = param(location, "connected")
        assertNotNull(location, id)
        assertTrue(location.startsWith("/records?connected="))
        val (exchange, auth) = fake.tokenRequests.single()
        assertEquals(Credentials.basic("epic-client-id", "epic-client-secret"), auth)
        assertNull(exchange["client_id"])
        assertEquals("authorization_code", exchange["grant_type"])
        // A state is used once.
        assertEquals(RecordsService.EXPIRED, param(me.callback("code=good-code&state=$stateParam"), "error"))

        val connection = me.get("/api/records").seen().obj()["connections"]!!.jsonArray.single().jsonObject
        assertEquals("Mass General Brigham", connection.str("organization"))
        assertTrue(connection["keepsUpdating"]!!.jsonPrimitive.boolean)
        assertNull(connection["problem"])
        assertEquals(now, connection["lastSyncAt"]!!.jsonPrimitive.content.toLong())
        val counts = connection["counts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
        assertEquals(mapOf("LAB" to 4, "VITAL" to 2, "MEDICATION" to 3, "CONDITION" to 2, "ALLERGY" to 1, "IMMUNIZATION" to 1), counts)
        // The lab search was paged: page two was asked for, with the access.
        assertTrue(fake.asked.any { it.requestUrl!!.queryParameter("page") == "2" && it.getHeader("Accept") == "application/fhir+json" })

        val items = me.get("/api/records/items").seen().json().jsonArray.map { it.jsonObject }
        fun item(title: String) = items.first { it.str("title") == title }
        assertEquals("142 pg/mL (usual 30–400)", item("Estradiol").str("detail"))
        assertEquals("HIGH", item("TSH").str("flag"))
        assertEquals("Not detected", item("SARS-CoV-2 RNA").str("detail"))
        assertEquals("118/76 mm[Hg]", item("Blood pressure").str("detail"))
        assertEquals("70.3 kg", item("Weight").str("detail"))
        assertEquals("Since 2019", item("Hypothyroidism").str("detail"))
        assertEquals("Until 2021", item("Migraine").str("detail"))
        assertEquals("resolved", item("Migraine").str("status"))
        assertEquals("Hives · severe", item("Penicillins").str("detail"))
        assertEquals("2025-10-15", item("Influenza vaccine").str("date"))
        assertEquals("estradiol 2 MG tablet", item("estradiol 2 MG tablet").jsonObject["medication"]!!.jsonObject.str("name"))
        assertTrue(items.all { it.str("organization") == "Mass General Brigham" && it.str("connectionId") == id })
        assertTrue(items.none { it.str("title") in setOf("Ferritin", "Sodium") })
        // Newest first.
        assertEquals(items.mapNotNull { it["date"]?.jsonPrimitive?.content }.sortedDescending(), items.mapNotNull { it["date"]?.jsonPrimitive?.content })
        assertEquals(listOf("Penicillins"), me.get("/api/records/items?kind=ALLERGY&connection=$id").seen().json().jsonArray.map { it.jsonObject.str("title") })
        assertEquals(HttpStatusCode.BadRequest, me.get("/api/records/items?kind=NOPE").status)

        // Labs with a number went into Labs, marked with where they came from.
        suspend fun recordLabs() = me.get("/api/labs").seen().json().jsonArray.map { it.jsonObject }.filter { it["ref"] != null }
        val labs = recordLabs()
        assertEquals(setOf("Estradiol", "TSH", "Potassium"), labs.map { it.str("name") }.toSet())
        val e2 = labs.first { it.str("name") == "Estradiol" }
        assertEquals("Mass General Brigham", e2.str("source"))
        assertEquals("record:$id:obs-e2", e2.str("ref"))
        assertEquals(142.0, e2["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(30.0, e2["low"]!!.jsonPrimitive.double, 0.0)
        assertEquals("2026-09-01", labs.first { it.str("name") == "Potassium" }.str("date"))
        // One entered here keeps no ref (that's the server's own mark).
        me.postJson("/api/labs", """{"name":"Ferritin","value":40,"unit":"ng/mL","at":$now,"ref":"record:$id:obs-forged","source":"Quest"}""")
        assertEquals("Quest", me.get("/api/labs").json().jsonArray.map { it.jsonObject }.first { it.str("name") == "Ferritin" }.str("source"))
        assertEquals(3, recordLabs().size)

        // Brought up to date again: a changed value changes the lab (never added twice); one gone from the record goes.
        fake.labsVersion = 2
        val synced = me.post("/api/records/$id/sync") { csrf() }.seen()
        assertEquals(HttpStatusCode.OK, synced.status)
        val after = recordLabs()
        assertEquals(setOf("Estradiol", "TSH"), after.map { it.str("name") }.toSet())
        assertEquals(e2.str("id"), after.first { it.str("name") == "Estradiol" }.str("id"))
        assertEquals(180.0, after.first { it.str("name") == "Estradiol" }["value"]!!.jsonPrimitive.double, 0.0)
        val itemsAfter = me.get("/api/records/items?kind=LAB").seen().json().jsonArray.map { it.jsonObject }
        assertEquals(setOf("Estradiol", "TSH", "SARS-CoV-2 RNA"), itemsAfter.map { it.str("title") }.toSet())
        assertEquals(item("Estradiol").str("id"), itemsAfter.first { it.str("title") == "Estradiol" }.str("id"))
        me.post("/api/records/$id/sync") { csrf() }
        assertEquals(2, recordLabs().size)

        // Access runs out: renewed a minute early, and a rotated refresh token is kept.
        now += 2 * 3_600_000L
        me.post("/api/records/$id/sync") { csrf() }.seen()
        now += 2 * 3_600_000L
        me.post("/api/records/$id/sync") { csrf() }.seen()
        val refreshes = fake.tokenRequests.map { it.first }.filter { it["grant_type"] == "refresh_token" }
        assertEquals(listOf("RT-epic-1", "RT-epic-2"), refreshes.map { it["refresh_token"] })
        assertEquals(now, me.get("/api/records").obj()["connections"]!!.jsonArray.single().jsonObject["lastSyncAt"]!!.jsonPrimitive.content.toLong())

        // Once a day, by itself, for connections with lasting access.
        now += 24 * 3_600_000L
        s.medicalRecords.syncDue(now)
        assertEquals(now, me.get("/api/records").obj()["connections"]!!.jsonArray.single().jsonObject["lastSyncAt"]!!.jsonPrimitive.content.toLong())
        // Not while it's paused.
        s.privacy.set(meId, DataPath.HEALTH_RECORDS, true)
        val asked = fake.asked.size
        now += 24 * 3_600_000L
        s.medicalRecords.syncDue(now)
        assertEquals(asked, fake.asked.size)
        s.privacy.set(meId, DataPath.HEALTH_RECORDS, false)

        // Access taken away at the portal: it says so, and keeps what it has.
        fake.accessGone = true
        now += 2 * 3_600_000L
        val gone = me.post("/api/records/$id/sync") { csrf() }.seen().obj()
        assertEquals(RecordsService.SIGN_IN_AGAIN, gone.str("problem"))
        assertEquals(itemsAfter.size, me.get("/api/records/items?kind=LAB").json().jsonArray.size)

        // Signing in again mends the same connection.
        fake.accessGone = false
        val again = me.callback("code=good-code&state=${me.connect(fake.mgb).queryParameter("state")}")
        assertEquals(id, param(again, "connected"))
        val mended = me.get("/api/records").obj()["connections"]!!.jsonArray.single().jsonObject
        assertNull(mended["problem"])

        // Disconnecting and keeping: what's here stays (labs with their source); its access is forgotten.
        assertEquals(HttpStatusCode.OK, me.delete("/api/records/$id?keep=true") { csrf() }.status)
        val kept = me.get("/api/records").obj()["connections"]!!.jsonArray.single().jsonObject
        assertEquals(RecordsService.DISCONNECTED, kept.str("problem"))
        assertFalse(kept["keepsUpdating"]!!.jsonPrimitive.boolean)
        assertEquals(2, recordLabs().size)
        assertTrue(me.get("/api/records/items").json().jsonArray.isNotEmpty())
        assertTrue(s.medicalRecords.hosts(meId).isEmpty())
        // And removing it all: items and the labs it brought, not the one entered here.
        assertEquals(HttpStatusCode.OK, me.delete("/api/records/$id") { csrf() }.status)
        assertTrue(me.get("/api/records").obj()["connections"]!!.jsonArray.isEmpty())
        assertTrue(me.get("/api/records/items").json().jsonArray.isEmpty())
        assertTrue(recordLabs().isEmpty())
        assertEquals(listOf("Ferritin"), me.get("/api/labs").json().jsonArray.map { it.jsonObject.str("name") })
        assertEquals(HttpStatusCode.NotFound, me.delete("/api/records/$id") { csrf() }.status)

        // No access token, refresh token, code or upstream words ever reached an app or the log.
        val secrets = listOf("AT-epic", "RT-epic", "good-code", "epic-client-secret", "secret-upstream-words")
        val logged = logs.list.map { it.formattedMessage + " " + it.throwableProxy?.message } + com.health.server.util.RecentLog.latest()
        for (text in answers + logged) secrets.forEach { assertFalse("$it in: $text", text.contains(it)) }
        // Nor are they in the database in the clear.
        val stored = s.db.tx { query("SELECT data FROM record_connections UNION ALL SELECT data FROM record_items UNION ALL SELECT data FROM record_pending") { it.string("data") } }
        assertTrue(stored.none { d -> secrets.any { d.contains(it) } || d.contains("Estradiol") || d.contains("Mass General") })
    }

    @Test
    fun `athenahealth, found through its CapabilityStatement, a public client, its practice named, access ended on disconnect`() = serverTest(services()) { s ->
        setUp(s)
        val me = noRedirects().also { it.login("admin", "break-glass-pw") }
        val authorize = me.connect(fake.athena, RecordProvider.ATHENA, app = true)
        assertEquals("${fake.origin}/athena/oauth2/authorize", authorize.newBuilder().query(null).build().toString())
        assertEquals(RecordsService.SCOPES[RecordProvider.ATHENA], authorize.queryParameter("scope"))
        assertTrue(authorize.queryParameter("scope")!!.contains("patient/Observation.rs"))
        assertFalse(authorize.queryParameter("scope")!!.contains("*"))
        assertEquals(fake.athena, authorize.queryParameter("aud"))

        // The phone app's sign-in comes back to it; no secret: the client ID and PKCE alone.
        val location = me.callback("code=good-code&state=${authorize.queryParameter("state")}")
        assertTrue(location, location.startsWith("samhealth://records?connected="))
        val id = param(location, "connected")!!
        val (exchange, auth) = fake.tokenRequests.single()
        assertNull(auth)
        assertEquals("athena-client-id", exchange["client_id"])
        assertNotNull(exchange["code_verifier"])

        val connection = me.get("/api/records").obj()["connections"]!!.jsonArray.single().jsonObject
        assertEquals("Dover Family Practice", connection.str("organization"))
        assertEquals("ATHENA", connection.str("provider"))
        // Immunizations weren't allowed (403): skipped quietly.
        assertNull(connection["problem"])
        assertEquals(mapOf("CONDITION" to 1), connection["counts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() })
        assertEquals("Dover Family Practice", me.get("/api/records/items").json().jsonArray.single().jsonObject.str("organization"))

        assertEquals(HttpStatusCode.OK, me.delete("/api/records/$id") { csrf() }.status)
        val revoked = fake.revoked.single()
        assertEquals("RT-athena", revoked["token"])
        assertEquals("refresh_token", revoked["token_type_hint"])
        assertEquals("athena-client-id", revoked["client_id"])
    }

    @Test
    fun `sign-ins that expired, were refused, or are someone else's connect nothing`() = serverTest(services()) { s ->
        setUp(s)
        val me = noRedirects().also { it.login("admin", "break-glass-pw") }
        s.users.create("sam", "Sam", password = "sam-password-123")
        val sam = noRedirects().also { it.login("sam", "sam-password-123") }

        // Ten minutes, no more.
        val late = me.connect(fake.mgb).queryParameter("state")
        now += 11 * 60_000L
        assertEquals(RecordsService.EXPIRED, param(me.callback("code=good-code&state=$late"), "error"))
        // Unknown, missing.
        assertEquals(RecordsService.EXPIRED, param(me.callback("code=good-code&state=made-up-state-made-up-state"), "error"))
        assertEquals(RecordsService.EXPIRED, param(me.callback("code=good-code"), "error"))
        // Someone else signed in on this browser can't take my sign-in.
        val mine = me.connect(fake.mgb).queryParameter("state")
        assertEquals(RecordsService.SOMEONE_ELSE, param(sam.callback("code=good-code&state=$mine"), "error"))
        // Not allowed at the portal: said kindly, nothing upstream passed on.
        val denied = me.connect(fake.mgb).queryParameter("state")
        assertEquals("You didn't allow it, so nothing was connected", param(me.callback("error=access_denied&error_description=%3Cscript%3E&state=$denied"), "error"))
        // A code the portal won't take.
        val wrong = me.connect(fake.mgb).queryParameter("state")
        assertEquals(RecordsService.EXCHANGE_FAILED, param(me.callback("code=bad-code&state=$wrong"), "error"))
        assertTrue(me.get("/api/records").obj()["connections"]!!.jsonArray.isEmpty())
        assertTrue(sam.get("/api/records").obj()["connections"]!!.jsonArray.isEmpty())

        // Mine, connected: Sam can't see, bring up to date or remove it.
        val ok = param(me.callback("code=good-code&state=${me.connect(fake.mgb).queryParameter("state")}"), "connected")!!
        assertTrue(sam.get("/api/records/items").json().jsonArray.isEmpty())
        assertEquals(HttpStatusCode.NotFound, sam.post("/api/records/$ok/sync") { csrf() }.status)
        assertEquals(HttpStatusCode.NotFound, sam.delete("/api/records/$ok") { csrf() }.status)
        assertEquals(1, me.get("/api/records").obj()["connections"]!!.jsonArray.size)

        // Never through a share, even one of everything.
        val meId = s.users.list().first { it.username == "admin" }.id
        me.postJson("/api/shares", """{"username":"sam","modules":["MEDICATIONS","HORMONES","VITALS"]}""")
        assertEquals(SharedView.Need.Never, SharedView.requirement("/api/records", null))
        for (path in listOf("/api/records", "/api/records/items", "/api/records/organizations?q=mass")) {
            val r = sam.get(path) { header(SharedView.HEADER, meId) }
            assertEquals(path, HttpStatusCode.Forbidden, r.status)
        }
        // Without signing in, only the portal's way back answers.
        val nobody = noRedirects()
        assertEquals(HttpStatusCode.Unauthorized, nobody.get("/api/records").status)
        assertEquals(HttpStatusCode.Unauthorized, nobody.get("/api/records/items").status)
        assertEquals(HttpStatusCode.Unauthorized, nobody.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.mgb}"}""").status)
        assertEquals(HttpStatusCode.Found, nobody.get("/api/records/callback?state=x").status)
    }

    @Test
    fun `paused, or not set up, and nothing is asked`() = serverTest(services()) { s ->
        val me = noRedirects().also { it.login("admin", "break-glass-pw") }
        val meId = s.users.list().first { it.username == "admin" }.id
        // Not set up: nothing to connect to.
        assertTrue(me.get("/api/records").obj()["providers"]!!.jsonArray.isEmpty())
        assertTrue(me.get("/api/records/organizations?q=mass").json().jsonArray.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.mgb}"}""").status)
        setUp(s, athena = false)
        // athenahealth isn't offered when only Epic is set up.
        assertEquals(listOf("Mass General Brigham"), me.get("/api/records/organizations?q=mass").json().jsonArray.map { it.jsonObject.str("name") })
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.athena}","provider":"ATHENA"}""").status)
        val id = param(me.callback("code=good-code&state=${me.connect(fake.mgb).queryParameter("state")}"), "connected")!!

        s.privacy.set(meId, DataPath.HEALTH_RECORDS, true)
        val asked = fake.asked.size
        assertTrue(me.get("/api/records").obj()["paused"]!!.jsonPrimitive.boolean)
        val refused = me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.mgb}"}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("Medical records is paused in Settings › Where your data goes", refused.obj().str("error"))
        assertEquals(HttpStatusCode.Conflict, me.post("/api/records/$id/sync") { csrf() }.status)
        assertEquals(HttpStatusCode.Conflict, me.get("/api/records/organizations?q=mass").status)
        // What's here can still be read.
        assertTrue(me.get("/api/records/items").json().jsonArray.isNotEmpty())
        assertEquals(asked, fake.asked.size)

        // A sign-in started before the pause doesn't finish during it.
        s.privacy.set(meId, DataPath.HEALTH_RECORDS, false)
        val state = me.connect(fake.mgb).queryParameter("state")
        s.privacy.set(meId, DataPath.HEALTH_RECORDS, true)
        val tokens = fake.tokenRequests.size
        assertEquals(RecordsService.PAUSED, param(me.callback("code=good-code&state=$state"), "error"))
        assertEquals(tokens, fake.tokenRequests.size)
        s.privacy.set(meId, DataPath.HEALTH_RECORDS, false)

        // Turned off on the server: bringing up to date is refused too.
        setUp(s, epic = false, athena = false)
        assertEquals(HttpStatusCode.Conflict, me.post("/api/records/$id/sync") { csrf() }.status)

        // Deleting the account deletes its records.
        val sam = s.users.create("sam", "Sam", password = "sam-password-123")
        s.db.tx { update("UPDATE record_connections SET user_id = ? WHERE id = ?", sam.id, id); update("UPDATE record_items SET user_id = ?", sam.id) }
        s.users.delete(sam.id)
        assertEquals(0L, s.db.tx { long("SELECT COUNT(*) FROM record_connections") + long("SELECT COUNT(*) FROM record_items") })
    }

    @Test
    fun `with https required, a plain http address is never connected`() = serverTest(services(httpsOnly = true)) { s ->
        setUp(s)
        val me = noRedirects().also { it.login("admin", "break-glass-pw") }
        // The stand-in's own address is http, so it isn't on the list.
        assertTrue(me.get("/api/records/organizations?q=mass").json().jsonArray.none { it.jsonObject.str("name") == "Mass General Brigham" })
        val before = fake.asked.size
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.mgb}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/records/connect", """{"organization":"x","fhirBaseUrl":"${fake.athena}","provider":"ATHENA"}""").status)
        assertEquals(before, fake.asked.size)
    }
}
