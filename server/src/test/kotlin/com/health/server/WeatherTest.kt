package com.health.server

import com.health.api.Migraine
import com.health.api.Module
import com.health.api.Place
import com.health.api.Profile
import com.health.api.SymptomEntry
import com.health.server.tracking.Weather
import com.health.server.tracking.Zips
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class WeatherTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private var now = at(today, 21)
    private fun at(date: LocalDate, hour: Int) = LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private val dover = Place("ZIP 03820", 43.19, -70.88)
    private val leeds = Place("Leeds", 53.7997, -1.5492)

    /** Pressure (hPa) drops by 8 every fourth day. */
    private val start = today.minusDays(29)
    private val pressure = (0L..40L).associate { i -> start.minusDays(8).plusDays(i) to if ((i - 8) % 4 == 0L) 1004.0 else 1012.0 }

    private val requests = mutableListOf<RecordedRequest>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val url = request.requestUrl!!
                val base = url.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
                return when {
                    url.encodedPath == "/points/43.2,-70.9" -> json(points(base))
                    url.encodedPath.startsWith("/points/") -> MockResponse().setResponseCode(404).setHeader("Content-Type", "application/problem+json")
                        .setBody("""{"title":"Data Unavailable For Requested Point","type":"https://api.weather.gov/problems/InvalidPoint","status":404,"detail":"Unable to provide data for requested point ${url.encodedPath.removePrefix("/points/")}"}""")
                    url.encodedPath == "/gridpoints/GYX/55,33/stations" -> json(STATIONS)
                    url.encodedPath == "/stations/KDAW/observations" -> json(observations(url.queryParameter("start")!!, url.queryParameter("end")!!, sparse = true))
                    url.encodedPath == "/stations/KPSM/observations" -> json(observations(url.queryParameter("start")!!, url.queryParameter("end")!!, sparse = false))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/geo+json").setBody(body)

    private fun points(base: String) = """
        {"@context":["https://geojson.org/geojson-ld/geojson-context.jsonld"],"id":"$base/points/43.2,-70.9","type":"Feature",
         "geometry":{"type":"Point","coordinates":[-70.9,43.2]},
         "properties":{"@id":"$base/points/43.2,-70.9","@type":"wx:Point","cwa":"GYX","gridId":"GYX","gridX":55,"gridY":33,
          "forecast":"$base/gridpoints/GYX/55,33/forecast","observationStations":"$base/gridpoints/GYX/55,33/stations",
          "relativeLocation":{"type":"Feature","geometry":{"type":"Point","coordinates":[-70.88,43.19]},"properties":{"city":"Dover","state":"NH"}},
          "timeZone":"America/New_York"}}
    """.trimIndent()

    /** Hourly readings between [from] and [to]: KDAW (when [sparse]) gives none for the 10th day before today. */
    private fun observations(from: String, to: String, sparse: Boolean): String {
        val a = Instant.parse(from)
        val b = Instant.parse(to)
        val date = a.atZone(zone).toLocalDate()
        if (sparse && date == today.minusDays(10)) return """{"type":"FeatureCollection","features":[]}"""
        val hours = generateSequence(a.truncatedTo(ChronoUnit.HOURS)) { it.plusSeconds(3600) }.takeWhile { it.isBefore(b) }.toList()
        val features = hours.map { t ->
            val pa = (pressure[t.atZone(zone).toLocalDate()] ?: 1010.0) * 100
            """{"id":"x","type":"Feature","properties":{"timestamp":"$t","barometricPressure":{"unitCode":"wmoUnit:Pa","value":$pa,"qualityControl":"V"},"seaLevelPressure":{"unitCode":"wmoUnit:Pa","value":null,"qualityControl":"Z"}}}"""
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun services(place: Place?) = testServices(clock = { now }).also { s ->
        runBlocking {
            s.bootstrap()
            s.profiles.put(s.users.byUsername("admin")!!.id, Profile(timezone = zone.id, modules = Module.entries.toSet(), place = place))
        }
        s.weather.urls = Weather.Urls(server.url("/").toString().trimEnd('/'))
    }

    @Test
    fun `ZIP codes are looked up on the server, from the Census Bureau's list`() {
        assertEquals(Place("ZIP 03820", 43.19, -70.88), Zips.find("03820"))
        assertEquals(Place("ZIP 02114", 42.36, -71.07), Zips.find(" 02114 "))
        assertEquals(Place("ZIP 90210", 34.10, -118.41), Zips.find("90210-1234"))
        assertNull(Zips.find("00000"))
        assertNull(Zips.find("0382"))
        assertNull(Zips.find("Leeds"))
    }

    @Test
    fun `air pressure comes from the weather service, is kept each day, and goes with symptoms`() = runBlocking {
        val s = services(dover)
        val id = s.users.byUsername("admin")!!.id
        // The daily round, from a week before the month until today (the service keeps only a week).
        for (i in -1L..29L) {
            now = at(start.plusDays(i), 20)
            s.weather.dailyRound { listOf(dover) }
        }
        now = at(today, 21)
        for (i in 0L..29L) {
            val d = start.plusDays(i)
            if (pressure[d]!! < 1010 && pressure[d.minusDays(1)]!! > 1010) s.tracking.symptoms.saveMigraine(id, Migraine(at(d, 10), at(d, 16), severity = 7))
            else if (i % 7 == 3L) s.tracking.symptoms.save(id, SymptomEntry(at(d, 10), "headache", 4))
        }
        val summary = s.tracking.symptoms.summary(id, 30)
        assertTrue(summary.weather)
        val drop = summary.correlations.first { it.factor == "pressure_drop" }
        assertEquals(1.0, drop.withRate, 0.01)
        assertTrue(drop.withoutRate < 0.3)
        val note = summary.weatherNote!!
        assertTrue(note, note.contains("National Weather Service") && note.contains("37 days so far"))
        // A day the nearest station missed came from the next one.
        val kept = s.weather.pressure(dover, today.minusDays(10), today.minusDays(10), fetch = false).days
        assertEquals(pressure[today.minusDays(10)]!!, kept[today.minusDays(10)]!!, 0.01)

        // Only a rough place went out, once; every call said who's asking, without anyone's details.
        val all = synchronized(requests) { requests.toList() }
        assertEquals(1, all.count { it.path!!.startsWith("/points/") })
        assertTrue(all.filter { it.path!!.startsWith("/points/") }.all { it.path == "/points/43.2,-70.9" })
        assertTrue(all.all { it.getHeader("User-Agent")!!.matches(Regex("SuperAwesomeMagicalHealth/\\S+ \\(self-hosted\\)")) })
        assertTrue(all.all { it.getHeader("Accept") == "application/geo+json" })
        // Whole days are kept: asking again fetches nothing more.
        val before = all.size
        s.tracking.symptoms.summary(id, 30)
        assertEquals(before, synchronized(requests) { requests.size })

        // Weather off on the server: nothing goes out, and nothing is said.
        s.settings.update { it.copy(weather = false) }
        now = at(today.plusDays(1), 20)
        s.weather.dailyRound { listOf(dover) }
        assertEquals(before, synchronized(requests) { requests.size })
        val off = s.tracking.symptoms.summary(id, 30)
        assertFalse(off.weather)
        assertNull(off.weatherNote)
    }

    @Test
    fun `outside the US there's no air pressure, said plainly`() = runBlocking {
        val s = services(leeds)
        val id = s.users.byUsername("admin")!!.id
        s.tracking.symptoms.save(id, SymptomEntry(at(today, 10), "headache", 4))
        val summary = s.tracking.symptoms.summary(id, 30)
        assertFalse(summary.weather)
        assertEquals("The National Weather Service only covers the US, so there's no air pressure for Leeds.", summary.weatherNote)
        // The answer is remembered: the place isn't asked about again.
        s.tracking.symptoms.summary(id, 30)
        assertEquals(1, synchronized(requests) { requests.count { it.path == "/points/53.8,-1.5" } })
    }

    companion object {
        /** The nearest stations, nearest first (trimmed from a real answer for Dover, NH). */
        private val STATIONS = """
            {"type":"FeatureCollection","features":[
             {"id":"https://api.weather.gov/stations/KDAW","type":"Feature","properties":{"@id":"https://api.weather.gov/stations/KDAW","@type":"wx:ObservationStation",
              "elevation":{"unitCode":"wmoUnit:m","value":93.8784},"stationIdentifier":"KDAW","name":"Rochester - Skyhaven Airport","timeZone":"America/New_York",
              "distance":{"unitCode":"wmoUnit:m","value":9280.1}}},
             {"id":"https://api.weather.gov/stations/KPSM","type":"Feature","properties":{"@id":"https://api.weather.gov/stations/KPSM","@type":"wx:ObservationStation",
              "stationIdentifier":"KPSM","name":"Portsmouth, Pease International Tradeport","timeZone":"America/New_York"}}],
             "observationStations":["https://api.weather.gov/stations/KDAW","https://api.weather.gov/stations/KPSM"]}
        """.trimIndent()
    }
}
