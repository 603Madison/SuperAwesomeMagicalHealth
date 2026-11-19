package com.health.server

import com.health.server.food.UsdaKind
import com.health.server.food.UsdaLocal
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * USDA's food list kept on this server: found on USDA's download page, downloaded, read as it
 * streams into its own file, swapped in whole, searched with nothing leaving the server, and kept
 * up to date (by the admin, or by itself once a month). The files are tiny real ones: a few foods of
 * each kind, cut from USDA's October 2026 downloads.
 */
class UsdaLocalTest {
    private fun fixture(name: String) = UsdaLocalTest::class.java.getResource("/usda/bulk/$name")!!.readText()

    private fun zip(entry: String, json: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry(entry))
            z.write(json.toByteArray())
            z.closeEntry()
        }
    }.toByteArray()

    /** The release USDA's page lists (each kind's newest file), and how its packaged foods' file answers. */
    @Volatile private var release = "2026-10-29"
    @Volatile private var brandedPolicy: (MockResponse) -> MockResponse = { it }
    private val asked = CopyOnWriteArrayList<String>()

    private val kinds = mapOf("foundation_food" to "foundation.json", "sr_legacy_food" to "sr_legacy.json", "survey_food" to "survey.json", "branded_food" to "branded.json")

    /** Each kind's file in [date]'s release; a newer release renames the granola, to tell the copies apart. */
    private fun file(kind: String, date: String): ByteArray {
        val name = "FoodData_Central_${kind}_json_$date"
        var json = fixture(kinds.getValue(kind))
        if (date > "2026-10-29") json = json.replace("GRANOLA, CINNAMON, RAISIN, CINNAMON, RAISIN", "GRANOLA, HONEY ALMOND")
        return zip("$name.json", json)
    }

    private val usdaSite = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                asked += "${request.method} $path"
                return when {
                    path == "/download-datasets" -> MockResponse().setResponseCode(302).setHeader("Location", "/download-datasets/")
                    path == "/download-datasets/" -> MockResponse().setBody(
                        // Older releases too, and a name with a space in it, as on the real page: each kind's newest is taken.
                        kinds.keys.joinToString("\n") { k ->
                            """<li><a href="/fdc-datasets/FoodData_Central_${k}_json_2025-04-24.zip">old</a></li>
                               <li><a href="/fdc-datasets/FoodData_Central_${k}_json_%202019-04-02.zip">older</a></li>
                               <li><a href="/fdc-datasets/FoodData_Central_${k}_csv_$release.zip">CSV</a></li>
                               <li><a href="/fdc-datasets/FoodData_Central_${k}_json_$release.zip">JSON</a></li>"""
                        },
                    )
                    path.startsWith("/fdc-datasets/") -> {
                        val m = Regex("""FoodData_Central_(\w+?)_json_([\d-]+)\.zip""").find(path) ?: return MockResponse().setResponseCode(404)
                        val bytes = file(m.groupValues[1], m.groupValues[2])
                        if (request.method == "HEAD") return MockResponse().setHeader("Content-Length", bytes.size)
                        val response = MockResponse().setBody(Buffer().write(bytes))
                        if (m.groupValues[1] == "branded_food") brandedPolicy(response) else response
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    /** USDA's online service: it must never be asked while the copy on this server answers. */
    private val usdaApi = CopyOnWriteArrayList<String>()
    private val api = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                usdaApi += request.path.orEmpty()
                return MockResponse().setBody("""{"foods":[{"fdcId":1,"description":"Banana, from the online service","dataType":"SR Legacy","foodNutrients":[{"nutrientNumber":"208","unitName":"KCAL","value":89}]}]}""")
            }
        }
        start()
    }

    @After
    fun stop() {
        usdaSite.shutdown()
        api.shutdown()
    }

    private var now = 1_800_000_000_000L
    @Volatile private var free = 50_000_000_000L
    private val dataDir: File = Files.createTempDirectory("samh-usda").toFile()

    private fun services(packaged: Boolean = true, autoUpdate: Boolean = false, key: String? = null) = testServices(
        config = ServerConfig(dataDir = dataDir, adminPassword = "break-glass-pw", outboundLoopback = true),
        clock = { now },
        usdaUrl = api.url("/fdc/v1/foods/search").toString(),
        usdaDownloadsUrl = usdaSite.url("/download-datasets").toString(),
        usdaFreeSpace = { free },
    ).also { s ->
        runBlocking {
            s.settings.update { it.copy(usdaLocal = it.usdaLocal.copy(use = true, packaged = packaged, autoUpdate = autoUpdate)) }
            key?.let { s.settings.setUsdaApiKey(it) }
        }
    }

    private fun JsonObject.names() = this["online"]!!.jsonArray.map { it.jsonObject.str("name") }
    private fun zipsAsked() = asked.count { it.startsWith("GET /fdc-datasets/") }

    @Test
    fun `downloaded, read into its own file, and searched with nothing leaving the server`() = serverTest(services(key = "k")) { s ->
        val admin = adminBrowser()
        val before = admin.get("/api/admin/usda-local").obj()
        assertEquals("false", before.str("ready"))
        assertTrue(before.str("allDisk").toLong() > before.str("everydayDisk").toLong())
        // Not here yet: the online service answers meanwhile, and search says why.
        val meanwhile = admin.get("/api/foods/search?q=banana").obj()
        assertEquals("Banana, from the online service", meanwhile.names().first())
        assertEquals("USDA's food list isn't on this server yet, so these come from elsewhere for now.", meanwhile.str("onlineError"))

        assertEquals(HttpStatusCode.OK, admin.post("/api/admin/usda-local/download") { csrf() }.status)
        s.usdaLocal.awaitDownload()
        val status = admin.get("/api/admin/usda-local").obj()
        assertEquals("true", status.str("ready"))
        assertEquals("2026-10-29", status.str("release"))
        assertEquals("true", status.str("packaged"))
        assertEquals("9", status.str("foods"))
        assertTrue(status.str("bytes").toLong() > 0)
        assertEquals(now.toString(), status.str("lastUpdated"))
        // Only the newest JSON of each kind was fetched (after following the page's same-site redirect), and nothing is left behind.
        assertEquals(4, zipsAsked())
        assertTrue(asked.none { "2025-04-24" in it || "_csv_" in it })
        assertFalse(File(dataDir, "usda-download").exists())
        assertTrue(File(dataDir, "usda-foods.db").isFile)

        // Searches and scans: answered here, everyday foods first. Neither USDA's site nor its online service is asked.
        val site = asked.size
        usdaApi.clear()
        val banana = admin.get("/api/foods/search?q=banana").obj()
        assertTrue(banana["onlineError"].toString() == "null")
        val names = banana.names()
        assertEquals(setOf("Banana, raw", "Bananas, raw", "Bananas, ripe and slightly ripe, raw", "Banana chips", "Bananas, dehydrated, or banana powder"), names.toSet())
        val raw = banana["online"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == "Banana, raw" }
        assertEquals("1 banana (126 g)", raw.str("serving"))
        assertEquals("USDA", raw.str("source"))
        assertEquals(122.2, raw["nutrients"]!!.jsonObject.str("calories").toDouble(), 0.05)
        assertEquals(411.0, raw["nutrients"]!!.jsonObject["micros"]!!.jsonObject.str("POTASSIUM").toDouble(), 0.5)
        // The best match first: every word, in a short name.
        assertTrue(admin.get("/api/foods/search?q=raw%20banana").obj().names().first().contains("raw"))
        val granola = admin.get("/api/foods/search?q=granola").obj()["online"]!!.jsonArray.single().jsonObject
        assertEquals("Granola, Cinnamon, Raisin, Cinnamon, Raisin", granola.str("name"))
        assertEquals("Michele's", granola.str("brand"))
        assertEquals("0.25 cup (28 g)", granola.str("serving"))

        // A barcode, scanned without its leading zero (or with two), matches exactly.
        val sauce = admin.get("/api/foods/barcode/76014101088").obj()
        assertEquals("076014101088", sauce.str("barcode"))
        assertEquals("Cookies Food Products Inc.", sauce.str("brand"))
        assertEquals("1.07 onz (30 g)", sauce.str("serving"))
        assertEquals("076014101088", admin.get("/api/foods/barcode/0076014101088").obj().str("barcode"))
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/foods/barcode/076014101089").status)
        assertEquals(site, asked.size)
        assertTrue(usdaApi.isEmpty())

        // Where your data goes leaves it out: nothing goes anywhere.
        assertTrue(admin.get("/api/privacy").obj()["destinations"]!!.jsonArray.none { it.jsonObject.str("path") == "USDA_FOODS" })

        // Removed: gone, and the online service answers again.
        assertEquals("false", admin.delete("/api/admin/usda-local") { csrf() }.obj().str("ready"))
        assertFalse(File(dataDir, "usda-foods.db").exists())
        assertEquals("Banana, from the online service", admin.get("/api/foods/search?q=banana").obj().names().first())
    }

    @Test
    fun `everyday foods only, small, with no barcodes, and no key needed`() = serverTest(services(packaged = false)) { s ->
        val admin = adminBrowser()
        assertTrue(s.usdaLocal.update(packaged = false))
        assertEquals(3, zipsAsked())
        assertEquals("false", admin.get("/api/admin/usda-local").obj().str("packaged"))
        assertTrue(admin.get("/api/foods/search?q=banana").obj().names().isNotEmpty())
        assertTrue(admin.get("/api/foods/search?q=granola").obj().names().isEmpty())
        val scan = admin.get("/api/foods/barcode/076014101088")
        assertEquals(HttpStatusCode.Conflict, scan.status)
        assertTrue(scan.bodyAsText().contains("needs USDA's packaged foods on this server"))
    }

    @Test
    fun `an update is swapped in whole, and searches keep using the old copy until then`() = serverTest(services()) { s ->
        assertTrue(s.usdaLocal.update(packaged = true))
        release = "2027-04-30"
        // The packaged foods arrive slowly: meanwhile, searches answer from the copy in use.
        brandedPolicy = { it.throttleBody(256, 100, TimeUnit.MILLISECONDS) }
        assertTrue(s.usdaLocal.download(packaged = true))
        assertFalse(s.usdaLocal.download(packaged = true)) // one at a time
        var sawWorking = false
        repeat(40) {
            if (s.usdaLocal.status().phase == "Downloading packaged foods") {
                sawWorking = true
                assertEquals("Granola, Cinnamon, Raisin, Cinnamon, Raisin", s.usdaLocal.search("granola", UsdaKind.PACKAGED, 5).single().name)
            }
            delay(25)
        }
        assertTrue(sawWorking)
        s.usdaLocal.awaitDownload()
        assertEquals("Granola, Honey Almond", s.usdaLocal.search("granola", UsdaKind.PACKAGED, 5).single().name)
        assertEquals("2027-04-30", s.usdaLocal.status().release)
        assertFalse(File(dataDir, "usda-download").exists())
    }

    @Test
    fun `a download that fails part way, or a short disk, leaves the copy in use as it was`() = serverTest(services()) { s ->
        assertTrue(s.usdaLocal.update(packaged = true))
        val file = File(dataDir, "usda-foods.db")
        val before = file.readBytes()
        release = "2027-04-30"

        brandedPolicy = { it.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        assertFalse(s.usdaLocal.update(packaged = true))
        var status = s.usdaLocal.status()
        assertTrue(status.ready)
        assertEquals("2026-10-29", status.release)
        assertNotNull(status.error)
        assertTrue(before.contentEquals(file.readBytes()))
        assertFalse(File(dataDir, "usda-download").exists())
        assertEquals("Granola, Cinnamon, Raisin, Cinnamon, Raisin", s.usdaLocal.search("granola", UsdaKind.PACKAGED, 5).single().name)

        brandedPolicy = { it }
        free = 100_000_000
        val zips = zipsAsked()
        assertFalse(s.usdaLocal.update(packaged = true))
        status = s.usdaLocal.status()
        assertTrue(status.error!!.startsWith("Not enough free space in the data folder: it needs about"))
        assertEquals(zips, zipsAsked()) // nothing downloaded
        assertTrue(before.contentEquals(file.readBytes()))

        // With room again, it goes, and the reason is cleared.
        free = 50_000_000_000L
        assertTrue(s.usdaLocal.update(packaged = true))
        status = s.usdaLocal.status()
        assertNull(status.error)
        assertEquals("2027-04-30", status.release)
    }

    @Test
    fun `each month it looks for a newer release, and with automatic updates on, takes it`() = serverTest(services(autoUpdate = true)) { s ->
        assertTrue(s.usdaLocal.update(packaged = true))
        // The first check finds the release in use: nothing to do.
        assertFalse(s.usdaLocal.tick(now))
        var status = s.usdaLocal.status()
        assertEquals(now, status.lastChecked)
        assertEquals(now + UsdaLocal.CHECK_EVERY_MS, status.nextCheck)
        assertNull(status.newer)

        // A newer release, but the month isn't up: not even the page is asked.
        release = "2027-04-30"
        val asked = asked.size
        now += 10L * 86_400_000
        assertFalse(s.usdaLocal.tick(now))
        assertEquals(asked, this@UsdaLocalTest.asked.size)

        // The month is up, but the disk is short: the copy in use stays, Admin says why, and says there's a newer one.
        now += 21L * 86_400_000
        free = 1_000
        assertFalse(s.usdaLocal.tick(now))
        status = s.usdaLocal.status()
        assertEquals("2026-10-29", status.release)
        assertEquals("2027-04-30", status.newer)
        assertTrue(status.error!!.startsWith("Not enough free space"))

        // The next check, with room: downloaded and swapped in by itself.
        free = 50_000_000_000L
        now += 31L * 86_400_000
        assertTrue(s.usdaLocal.tick(now))
        status = s.usdaLocal.status()
        assertEquals("2027-04-30", status.release)
        assertNull(status.newer)
        assertNull(status.error)
        assertEquals(now, status.lastUpdated)
    }

    @Test
    fun `without automatic updates, a newer release is only shown`() = serverTest(services(autoUpdate = false)) { s ->
        assertTrue(s.usdaLocal.update(packaged = true))
        release = "2027-04-30"
        val zips = zipsAsked()
        now += 31L * 86_400_000
        assertFalse(s.usdaLocal.tick(now))
        assertEquals(zips, zipsAsked())
        val status = adminBrowser().get("/api/admin/usda-local").obj()
        assertEquals("2027-04-30", status.str("newer"))
        assertEquals("2026-10-29", status.str("release"))
        // Admins only.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/usda-local").status)
    }

    @Test
    fun `it doesn't follow USDA's download anywhere else`() = serverTest(services()) { s ->
        val moved = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(301).setHeader("Location", "http://example.com/download-datasets/")
            }
            start()
        }
        val elsewhere = testServices(
            config = ServerConfig(dataDir = Files.createTempDirectory("samh-usda").toFile(), adminPassword = "x", outboundLoopback = true),
            usdaDownloadsUrl = moved.url("/download-datasets/").toString(),
        )
        assertFalse(elsewhere.usdaLocal.update(packaged = false))
        assertEquals("USDA's download moved somewhere this server doesn't follow", elsewhere.usdaLocal.status().error)
        moved.shutdown()
        assertFalse(s.usdaLocal.ready)
    }
}
