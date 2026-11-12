package com.health.server.tracking

import com.health.api.Place
import com.health.server.ApiError
import com.health.server.SettingsStore
import com.health.server.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Air pressure from Open-Meteo (free, no key), for comparing symptoms with the weather. Only a
 * rough place goes out (to a tenth of a degree, about 10 km), and only when the admin allows it
 * (settings' `weather`). Each day's pressure is kept, so a day is asked for once.
 */
class Weather(
    private val db: Database,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where Open-Meteo is (a test server in tests). */
    data class Urls(
        val forecast: String = "https://api.open-meteo.com/v1/forecast",
        val archive: String = "https://archive-api.open-meteo.com/v1/archive",
        val geocoding: String = "https://geocoding-api.open-meteo.com/v1/search",
    )

    @Volatile
    var urls = Urls()

    private val log = LoggerFactory.getLogger("samh.weather")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enabled(): Boolean = settings.get().weather

    /** Places matching [query], for the profile. */
    suspend fun places(query: String): List<Place> {
        if (!enabled()) throw ApiError.badRequest("Weather is turned off on this server")
        val q = query.trim().take(100)
        if (q.length < 2) return emptyList()
        val url = urls.geocoding.toHttpUrl().newBuilder().addQueryParameter("name", q).addQueryParameter("count", "6")
            .addQueryParameter("language", "en").addQueryParameter("format", "json").build()
        val body = fetch(url.toString()) ?: throw ApiError.upstream("Couldn't reach the place search just now")
        val results = (body["results"] as? JsonArray).orEmpty()
        return results.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val name = listOfNotNull("name", "admin1", "country").mapNotNull { (o[it] as? JsonPrimitive)?.contentOrNull }.distinct().joinToString(", ")
            Place(name, round(lat, 2), round(lon, 2))
        }
    }

    /** Each day's mean sea-level air pressure (hPa) at [place] from [from] to [to]; days it can't get are missing. */
    suspend fun pressure(place: Place, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        if (!enabled()) return emptyMap()
        val key = key(place)
        val kept = db.tx {
            query("SELECT date, data, fetched_at FROM weather WHERE place = ? AND date >= ? AND date <= ?", key, from.toString(), to.toString()) {
                Triple(LocalDate.parse(it.string("date")), it.string("data").toDoubleOrNull(), it.long("fetched_at"))
            }
        }
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        // Recent days are fetched again for a while, as forecasts settle into what happened.
        val fresh = kept.filter { (date, value, at) -> value != null && (date.isBefore(today.minusDays(2)) || clock() - at < 6 * 3_600_000L) }
            .associate { it.first to it.second!! }
        val missing = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.filter { it !in fresh }.toList()
        if (missing.isEmpty()) return fresh
        val got = mutableMapOf<LocalDate, Double>()
        // The forecast API covers about the last three months; the archive the rest (a few days behind).
        val recentFrom = today.minusDays(85)
        val old = missing.filter { it.isBefore(recentFrom) }
        val recent = missing.filter { !it.isBefore(recentFrom) }
        if (old.isNotEmpty()) got += fetchDaily(urls.archive, place, old.min(), minOf(old.max(), today.minusDays(5)))
        if (recent.isNotEmpty()) got += fetchDaily(urls.forecast, place, recent.min(), minOf(recent.max(), today.plusDays(1)))
        if (got.isNotEmpty()) {
            db.tx {
                got.forEach { (date, value) ->
                    update("INSERT OR REPLACE INTO weather (place, date, data, fetched_at) VALUES (?, ?, ?, ?)", key, date.toString(), value.toString(), clock())
                }
            }
        }
        return fresh + got.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
    }

    private suspend fun fetchDaily(base: String, place: Place, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        if (to.isBefore(from)) return emptyMap()
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", String.format(Locale.ROOT, "%.1f", place.latitude))
            .addQueryParameter("longitude", String.format(Locale.ROOT, "%.1f", place.longitude))
            .addQueryParameter("hourly", "pressure_msl")
            .addQueryParameter("start_date", from.toString())
            .addQueryParameter("end_date", to.toString())
            .addQueryParameter("timezone", "auto")
            .build()
        val body = fetch(url.toString()) ?: return emptyMap()
        val hourly = body["hourly"] as? JsonObject ?: return emptyMap()
        val times = (hourly["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
        val values = (hourly["pressure_msl"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()
        return times.zip(values).mapNotNull { (t, v) -> v?.let { runCatching { LocalDate.parse(t.take(10)) }.getOrNull()?.to(it) } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 12 }
            .mapValues { (_, list) -> round(list.average(), 1) }
    }

    private suspend fun fetch(url: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "SuperAwesomeMagicalHealth").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    log.warn("Open-Meteo answered {} for {}", response.code, url.substringBefore('?'))
                    null
                } else {
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }
            }
        } catch (e: Exception) {
            log.warn("Couldn't reach Open-Meteo: {}", e.message)
            null
        }
    }

    companion object {
        fun key(place: Place) = String.format(Locale.ROOT, "%.1f,%.1f", place.latitude, place.longitude)
        private fun round(v: Double, digits: Int): Double {
            val f = Math.pow(10.0, digits.toDouble())
            return (v * f).roundToInt() / f
        }
    }
}
