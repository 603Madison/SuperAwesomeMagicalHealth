package com.health.server.tracking

import com.health.api.Place
import com.health.server.SettingsStore
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.util.HttpException
import com.health.server.util.OutboundDns
import com.health.server.util.fetchString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Air pressure from the US National Weather Service (api.weather.gov: free, no key), for comparing
 * symptoms with the weather. Only a rough place goes out (to a tenth of a degree, about 10 km), and
 * only when the admin allows it (settings' `weather`). The NWS keeps about a week of observations,
 * so each day's mean is kept here as it's fetched: once a day for everyone with a place, and when
 * symptom patterns are worked out. Outside the US there's nothing to fetch.
 */
class Weather(
    private val db: Database,
    http: OkHttpClient,
    private val settings: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    loopback: () -> Boolean = { false },
) {
    /** Where the NWS is (a test server in tests). */
    data class Urls(val api: String = "https://api.weather.gov")

    @Volatile
    var urls = Urls()

    /** What's kept for a place: each day's mean pressure (hPa), whether the NWS covers it (null: not known yet), and the first day kept. */
    data class History(val days: Map<LocalDate, Double>, val covered: Boolean?, val since: LocalDate?)

    private class Station(val id: String, val zone: ZoneId)

    private val log = LoggerFactory.getLogger("samh.weather")
    private val json = Json { ignoreUnknownKeys = true }

    // A public service: never a private address (but this machine in tests), never a redirect.
    private val client = http.newBuilder().dns(OutboundDns(loopback, loopback)).followRedirects(false).followSslRedirects(false).build()

    /** Each place's nearest stations (empty: the NWS doesn't cover it). */
    private val stations = ConcurrentHashMap<String, List<Station>>()

    /** When each place's day was last asked for, so a day without data isn't asked for again within the hour. */
    private val tried = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastRound = AtomicReference<LocalDate?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun enabled(): Boolean = settings.get().weather

    /** The place for a US ZIP code, from the Census Bureau's list kept with the server: nothing is sent anywhere. */
    fun zip(code: String): Place? = Zips.find(code)

    /** What's kept for [place] from [from] to [to], after fetching the days of the last week it's missing (when [fetch]). */
    suspend fun pressure(place: Place, from: LocalDate, to: LocalDate, fetch: Boolean = true): History {
        if (!enabled()) return History(emptyMap(), null, null)
        val key = key(place)
        if (fetch) refresh(place)
        val (days, since) = db.tx {
            query("SELECT date, hpa FROM air_pressure WHERE place = ? AND date >= ? AND date <= ?", key, from.toString(), to.toString()) {
                LocalDate.parse(it.string("date")) to it.double("hpa")
            }.toMap() to one("SELECT MIN(date) AS first FROM air_pressure WHERE place = ?", key) { it.stringOrNull("first") }
        }
        val first = since?.let(LocalDate::parse)
        return History(days, if (first != null) true else stations[key]?.isNotEmpty(), first)
    }

    /** Fetches the days of the last week that [place] doesn't have whole yet. */
    suspend fun refresh(place: Place) {
        if (!enabled()) return
        val key = key(place)
        locks.getOrPut(key) { Mutex() }.withLock {
            val nearest = stationsFor(place) ?: return
            if (nearest.isEmpty()) return
            val zone = nearest.first().zone
            val now = clock()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val whole = db.tx {
                query("SELECT date FROM air_pressure WHERE place = ? AND complete = 1 AND date >= ?", key, today.minusDays(DAYS_KEPT - 1).toString()) { it.string("date") }
            }.toSet()
            for (back in DAYS_KEPT - 1 downTo 0) {
                val date = today.minusDays(back)
                if (date.toString() in whole) continue
                val asked = "$key $date"
                if (now - (tried[asked] ?: 0L) < HOUR) continue
                tried[asked] = now
                val mean = dayMean(nearest, date, zone) ?: continue
                val over = now >= date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                db.tx {
                    update(
                        "INSERT OR REPLACE INTO air_pressure (place, date, hpa, complete, fetched_at) VALUES (?, ?, ?, ?, ?)",
                        key, date.toString(), mean, if (over) 1 else 0, now,
                    )
                }
            }
            tried.entries.removeIf { now - it.value > 2 * 86_400_000L }
        }
    }

    /**
     * Once a day (from noon UTC, when yesterday is over across the US), the last week for every
     * place in [places], in the background.
     */
    fun startDailyRound(now: Long, places: suspend () -> List<Place>) {
        val at = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
        if (at.hour < 12) return
        val day = at.toLocalDate()
        val last = lastRound.get()
        if (last == day || !lastRound.compareAndSet(last, day)) return
        scope.launch { dailyRound(places) }
    }

    suspend fun dailyRound(places: suspend () -> List<Place>) {
        if (!enabled()) return
        for (place in places().distinctBy(::key)) {
            try {
                refresh(place)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The day's air pressure didn't come: {}", e.message)
            }
        }
    }

    /** The nearest few stations for [place], or null when the NWS couldn't be reached. */
    private suspend fun stationsFor(place: Place): List<Station>? {
        val key = key(place)
        stations[key]?.let { return it }
        val base = urls.api.trimEnd('/')
        val point = try {
            fetch("$base/points/$key".toHttpUrl())
        } catch (e: HttpException) {
            // The NWS answers 404 for a point it doesn't cover (outside the US).
            if (e.code == 404) return emptyList<Station>().also { stations[key] = it }
            log.warn("The National Weather Service answered {}", e.code)
            return null
        } ?: return null
        val listUrl = ((point["properties"] as? JsonObject)?.get("observationStations") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith("$base/") }?.toHttpUrlOrNull() ?: return emptyList<Station>().also { stations[key] = it }
        val list = try { fetch(listUrl.newBuilder().setQueryParameter("limit", "3").build()) } catch (e: HttpException) { null } ?: return null
        val found = (list["features"] as? JsonArray).orEmpty().mapNotNull { f ->
            val p = (f as? JsonObject)?.get("properties") as? JsonObject ?: return@mapNotNull null
            val id = (p["stationIdentifier"] as? JsonPrimitive)?.contentOrNull?.takeIf { STATION.matches(it) } ?: return@mapNotNull null
            val zone = (p["timeZone"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
            Station(id, zone)
        }.take(3)
        stations[key] = found
        return found
    }

    /** The day's mean air pressure (hPa) at the nearest station that measured it. */
    private suspend fun dayMean(nearest: List<Station>, date: LocalDate, zone: ZoneId): Double? {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        for (station in nearest) {
            val url = urls.api.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegment("stations").addPathSegment(station.id).addPathSegment("observations")
                .addQueryParameter("start", start.toString())
                .addQueryParameter("end", minOf(end, Instant.ofEpochMilli(clock())).toString())
                .build()
            val body = try { fetch(url) } catch (e: HttpException) { null } ?: continue
            val readings = (body["features"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("properties") as? JsonObject }
            fun pascals(field: String) = readings.mapNotNull { ((it[field] as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull }
                .filter { it in 85_000.0..110_000.0 }
            // Barometric pressure where the station gives it; sea-level pressure otherwise (both in Pa).
            val values = pascals("barometricPressure").takeIf { it.size >= MIN_READINGS } ?: pascals("seaLevelPressure")
            if (values.size >= MIN_READINGS) return Math.round(values.average() / 10.0) / 10.0
        }
        return null
    }

    /** The answer at [url], or null when the NWS couldn't be reached; an [HttpException] for an error answer. */
    private suspend fun fetch(url: HttpUrl): JsonObject? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "SuperAwesomeMagicalHealth/${Services.version} (self-hosted)")
            .header("Accept", "application/geo+json")
            .build()
        return try {
            json.parseToJsonElement(client.fetchString(request)).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't reach the National Weather Service: {}", e.message)
            null
        }
    }

    companion object {
        /** About a week: what the NWS keeps. */
        private const val DAYS_KEPT = 7L
        private const val HOUR = 3_600_000L
        private const val MIN_READINGS = 6
        private val STATION = Regex("[A-Za-z0-9]{3,8}")

        /** The rough place that's sent and kept: a tenth of a degree, about 10 km. */
        fun key(place: Place): String = String.format(Locale.ROOT, "%.1f,%.1f", place.latitude, place.longitude)
    }
}

/**
 * US ZIP codes' centres, from the Census Bureau's Gazetteer ZCTA file (public domain), kept with the
 * server in `zip-centroids.csv` (zip, latitude, longitude, to two decimals).
 */
object Zips {
    private val all: Map<String, Pair<Double, Double>> by lazy {
        val stream = Zips::class.java.getResourceAsStream("/zip-centroids.csv") ?: return@lazy emptyMap()
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.split(',')
                val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
                parts[0] to (lat to lon)
            }.toMap(HashMap(40_000))
        }
    }

    /** The place for [code] (five digits; a ZIP+4 is fine), or null if there's no such ZIP code. */
    fun find(code: String): Place? {
        val zip = code.trim().takeWhile { it != '-' }.takeIf { it.length == 5 && it.all(Char::isDigit) } ?: return null
        val (lat, lon) = all[zip] ?: return null
        return Place("ZIP $zip", lat, lon)
    }
}
