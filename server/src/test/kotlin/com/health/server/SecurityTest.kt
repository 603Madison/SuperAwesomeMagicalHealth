package com.health.server

import com.health.api.Module
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.server.auth.LoginThrottle
import com.health.server.auth.safeReturn
import com.health.server.util.OutboundDns
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.GZIPOutputStream

/** The fixes from the security review: what reaches whom, and what the server won't do. */
class SecurityTest {
    @Test
    fun `a compressed request isn't unpacked, and a request bigger than the server takes is refused`() = serverTest {
        // A gzip body could unpack to gigabytes: it's taken as it is, so it's simply not JSON.
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(" ".repeat(5_000_000).toByteArray()) } }.toByteArray()
        val bomb = browser().post("/api/auth/login") {
            header(HttpHeaders.ContentEncoding, "gzip")
            contentType(ContentType.Application.Json)
            setBody(zipped)
        }
        assertEquals(HttpStatusCode.BadRequest, bomb.status)
        val admin = adminBrowser()
        val big = admin.postJson("/api/food/entries", """{"date":"2026-01-01","meal":"LUNCH","name":"${"x".repeat(5_000_000)}","nutrients":{"calories":1}}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertEquals(4L * 1024 * 1024, bodyLimitFor("/api/food/entries"))
        assertTrue(bodyLimitFor("/api/photos") > 20L * 1024 * 1024)
    }

    @Test
    fun `the server only calls addresses it should`() {
        fun ip(text: String) = InetAddress.getByName(text)
        // Never: cloud metadata, any-address; the server itself only in tests.
        assertTrue(OutboundDns.refusal(ip("169.254.169.254"), private = true) != null)
        assertTrue(OutboundDns.refusal(ip("0.0.0.0"), private = true) != null)
        assertTrue(OutboundDns.refusal(ip("127.0.0.1"), private = true) != null)
        assertNull(OutboundDns.refusal(ip("127.0.0.1"), private = true, loopback = true))
        assertTrue(OutboundDns.refusal(ip("127.0.0.1"), private = false, loopback = true) != null)
        // A home network only when the admin allows it; Podman's host address counts as one.
        assertTrue(OutboundDns.refusal(ip("192.168.1.20"), private = false) != null)
        assertNull(OutboundDns.refusal(ip("192.168.1.20"), private = true))
        assertNull(OutboundDns.refusal(ip("169.254.1.2"), private = true))
        assertTrue(OutboundDns.refusal(ip("64:ff9b::a00:5"), private = false) != null)
        assertTrue(OutboundDns.refusal(ip("100.100.100.200"), private = true) != null)
        assertNull(OutboundDns.refusal(ip("93.184.216.34"), private = false))
    }

    @Test
    fun `after signing in, only a path on this site is gone back to`() {
        assertEquals("/", safeReturn("/\t/evil.example"))
        assertEquals("/", safeReturn("//evil.example"))
        assertEquals("/", safeReturn("/\\evil.example"))
        assertEquals("/", safeReturn("https://evil.example"))
        assertEquals("/", safeReturn("/a b"))
        assertEquals("/food?date=2026-01-01", safeReturn("/food?date=2026-01-01"))
    }

    @Test
    fun `wrong passwords from one place don't lock the account for everyone else`() {
        val throttle = LoginThrottle()
        repeat(10) { throttle.failed("ip:1.1.1.1", "user:admin") }
        assertEquals(429, runCatching { throttle.check("ip:1.1.1.1", "user:admin") }.exceptionOrNull()?.let { (it as ApiError).status.value })
        throttle.check("ip:2.2.2.2", "user:admin")
        // Guessing from everywhere still stops.
        repeat(200) { n -> throttle.failed("ip:9.9.${n / 250}.${n % 250}", "user:admin") }
        assertTrue(runCatching { throttle.check("ip:3.3.3.3", "user:admin") }.isFailure)
    }

    @Test
    fun `someone viewing a share sees only what's shared in supplies, labs, goals and the calorie formula`() = serverTest { s ->
        val me = runBlocking { s.users.byUsername("admin")!!.id }
        val admin = adminBrowser()
        runBlocking {
            s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 10))
            s.supplies.save(me, Supply("Pads", SupplyKind.PERIOD_PAD, onHand = 10))
            s.modules.meds.saveLab(me, com.health.api.LabResult("Estradiol", 400.0, "pmol/L", s.clock()))
            s.modules.meds.saveLab(me, com.health.api.LabResult("HIV viral load", 20.0, "copies/mL", s.clock()))
            val p = s.profiles.get(me)
            s.profiles.put(
                me,
                p.copy(
                    energyProfile = com.health.api.EnergyProfile.FEMALE, goals = p.goals.copy(targetWeightKg = 70.0, calories = 1800),
                    modules = p.modules + setOf(Module.CYCLE, Module.HORMONES, Module.MEDICATIONS, Module.CONTINENCE),
                ),
            )
        }
        s.users.create("sam", "Sam", password = "sam-password-123")
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/shares", """{"username":"sam","modules":["CYCLE","HORMONES","SLEEP"]}""").status)
        val sam = browser().also { it.postJson("/api/auth/login", """{"username":"sam","password":"sam-password-123"}""") }
        val supplies = sam.get("/api/supplies") { header("X-SAMH-View", me) }.json().jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Pads"), supplies)
        val labs = sam.get("/api/labs") { header("X-SAMH-View", me) }.json().jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Estradiol"), labs)
        val profile = sam.get("/api/profile") { header("X-SAMH-View", me) }.obj()
        assertEquals("AVERAGE", profile["energyProfile"]!!.jsonPrimitive.content)
        val goals = profile["goals"]!!.jsonObject
        assertTrue(goals["targetWeightKg"] == null || goals["targetWeightKg"] is kotlinx.serialization.json.JsonNull)
        assertTrue(goals["calories"] == null || goals["calories"] is kotlinx.serialization.json.JsonNull)
        // The owner still sees everything.
        assertEquals(2, admin.get("/api/labs").json().jsonArray.size)
    }

    @Test
    fun `the session cookie is Secure at the https address, and not on the home network's http one`() = serverTest { s ->
        runBlocking { s.settings.update { it.copy(publicUrl = "https://health.example.org") } }
        val public = browser().post("/api/auth/login") {
            header(HttpHeaders.Host, "health.example.org")
            header("X-SAMH", "1")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"break-glass-pw"}""")
        }
        assertEquals(HttpStatusCode.OK, public.status)
        assertTrue(public.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.contains("Secure") })
        assertTrue(public.headers[HttpHeaders.StrictTransportSecurity] != null)
        val home = browser().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"break-glass-pw"}""")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.headers.getAll(HttpHeaders.SetCookie).orEmpty().none { it.contains("Secure") })
    }
}
