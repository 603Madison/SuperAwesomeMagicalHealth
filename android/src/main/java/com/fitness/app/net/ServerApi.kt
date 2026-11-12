package com.fitness.app.net

import com.fitness.api.ApiJson
import com.fitness.api.AppTokenRequest
import com.fitness.api.AuthConfig
import com.fitness.api.CheckIn
import com.fitness.api.DaySummary
import com.fitness.api.Device
import com.fitness.api.ErrorBody
import com.fitness.api.Food
import com.fitness.api.FoodDay
import com.fitness.api.FoodEntry
import com.fitness.api.FoodEntryChange
import com.fitness.api.FoodEntryInput
import com.fitness.api.FoodSearch
import com.fitness.api.IngestBatch
import com.fitness.api.IngestResult
import com.fitness.api.LoginRequest
import com.fitness.api.Meal
import com.fitness.api.MeasurementInput
import com.fitness.api.Me
import com.fitness.api.Metric
import com.fitness.api.Profile
import com.fitness.api.Sample
import com.fitness.api.SignedIn
import com.fitness.api.SleepSession
import com.fitness.api.SleepSummary
import com.fitness.api.Trends
import com.fitness.api.WaterEntry
import com.fitness.api.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** An error from the server, with a message meant for people; [code] 0 means it wasn't reached. */
class ServerException(val code: Int, message: String) : IOException(message)

/**
 * The server's API (docs/API.md), as the app uses it. [base] is the server's address and [token]
 * the app's sign-in; [onSignedOut] is told when the server says the sign-in is no longer good.
 */
class ServerApi(
    private val http: OkHttpClient,
    private val base: () -> String,
    private val token: () -> String?,
    private val onSignedOut: () -> Unit = {},
) {
    // ---- Accounts ----

    suspend fun authConfig(server: String = base()): AuthConfig = get("$server/api/auth/config", AuthConfig.serializer(), auth = false)

    suspend fun login(server: String, username: String, password: String, deviceName: String): SignedIn = send(
        "POST", "$server/api/auth/login",
        ApiJson.encodeToString(LoginRequest.serializer(), LoginRequest(username, password, app = true, deviceName = deviceName)),
        SignedIn.serializer(), auth = false,
    )

    suspend fun exchangeCode(server: String, code: String, verifier: String, deviceName: String): SignedIn = send(
        "POST", "$server/api/auth/app/token",
        ApiJson.encodeToString(AppTokenRequest.serializer(), AppTokenRequest(code, verifier, deviceName)),
        SignedIn.serializer(), auth = false,
    )

    suspend fun logout() = runCatching { send("POST", "${base()}/api/auth/logout", "{}", Unit.serializer()) }

    suspend fun me(): Me = get("${base()}/api/me", Me.serializer())

    // ---- Profile and days ----

    suspend fun profile(): Profile = get("${base()}/api/profile", Profile.serializer())

    suspend fun saveProfile(profile: Profile): Profile =
        send("PUT", "${base()}/api/profile", ApiJson.encodeToString(Profile.serializer(), profile), Profile.serializer())

    suspend fun day(date: String): DaySummary = get("${base()}/api/day/${enc(date)}", DaySummary.serializer())

    suspend fun trends(days: Int): Trends = get("${base()}/api/trends?days=$days", Trends.serializer())

    // ---- Food ----

    suspend fun foodDay(date: String): FoodDay = get("${base()}/api/food/${enc(date)}", FoodDay.serializer())

    suspend fun addFood(input: FoodEntryInput): FoodEntry =
        send("POST", "${base()}/api/food/entries", ApiJson.encodeToString(FoodEntryInput.serializer(), input), FoodEntry.serializer())

    suspend fun changeFood(id: String, change: FoodEntryChange): FoodEntry =
        send("PATCH", "${base()}/api/food/entries/${enc(id)}", ApiJson.encodeToString(FoodEntryChange.serializer(), change), FoodEntry.serializer())

    suspend fun deleteFood(id: String) = send("DELETE", "${base()}/api/food/entries/${enc(id)}", null, Unit.serializer())

    suspend fun copyFood(from: String, to: String, meal: Meal?) =
        send("POST", "${base()}/api/food/copy", ApiJson.encodeToString(CopyRequest.serializer(), CopyRequest(from, to, meal)), Unit.serializer())

    suspend fun searchFoods(query: String): FoodSearch = get("${base()}/api/foods/search?q=${enc(query)}", FoodSearch.serializer())

    /** The food with [code] on its packaging, or null when nobody knows it. */
    suspend fun barcode(code: String): Food? = try {
        get("${base()}/api/foods/barcode/${enc(code)}", Food.serializer())
    } catch (e: ServerException) {
        if (e.code == 404) null else throw e
    }

    suspend fun recentFoods(): List<Food> = get("${base()}/api/foods/recent", ListSerializer(Food.serializer()))

    suspend fun createFood(food: Food): Food =
        send("POST", "${base()}/api/foods", ApiJson.encodeToString(Food.serializer(), food), Food.serializer())

    suspend fun deleteSavedFood(id: String) = send("DELETE", "${base()}/api/foods/${enc(id)}", null, Unit.serializer())

    suspend fun addWater(entry: WaterEntry): WaterEntry =
        send("POST", "${base()}/api/water", ApiJson.encodeToString(WaterEntry.serializer(), entry), WaterEntry.serializer())

    suspend fun deleteWater(id: String) = send("DELETE", "${base()}/api/water/${enc(id)}", null, Unit.serializer())

    // ---- Body, workouts, sleep, check-ins ----

    suspend fun measurements(metric: Metric, days: Int): List<Sample> =
        get("${base()}/api/measurements?metric=${metric.name}&days=$days", ListSerializer(Sample.serializer()))

    suspend fun addMeasurement(input: MeasurementInput): Sample =
        send("POST", "${base()}/api/measurements", ApiJson.encodeToString(MeasurementInput.serializer(), input), Sample.serializer())

    suspend fun deleteMeasurement(id: Long) = send("DELETE", "${base()}/api/measurements/$id", null, Unit.serializer())

    suspend fun workouts(days: Int): List<Workout> = get("${base()}/api/workouts?days=$days", ListSerializer(Workout.serializer()))

    suspend fun addWorkout(workout: Workout): Workout =
        send("POST", "${base()}/api/workouts", ApiJson.encodeToString(Workout.serializer(), workout), Workout.serializer())

    suspend fun deleteWorkout(id: String) = send("DELETE", "${base()}/api/workouts/${enc(id)}", null, Unit.serializer())

    suspend fun sleep(days: Int): List<SleepSummary> = get("${base()}/api/sleep?days=$days", ListSerializer(SleepSummary.serializer()))

    suspend fun addSleep(session: SleepSession): SleepSession =
        send("POST", "${base()}/api/sleep", ApiJson.encodeToString(SleepSession.serializer(), session), SleepSession.serializer())

    suspend fun deleteSleep(id: String) = send("DELETE", "${base()}/api/sleep/${enc(id)}", null, Unit.serializer())

    suspend fun checkIn(date: String): CheckIn = get("${base()}/api/checkins/${enc(date)}", CheckIn.serializer())

    suspend fun saveCheckIn(checkIn: CheckIn): CheckIn =
        send("PUT", "${base()}/api/checkins/${enc(checkIn.date)}", ApiJson.encodeToString(CheckIn.serializer(), checkIn), CheckIn.serializer())

    // ---- Devices and data in ----

    suspend fun devices(): List<Device> = get("${base()}/api/devices", ListSerializer(Device.serializer()))

    suspend fun renameDevice(id: String, name: String): Device =
        send("PATCH", "${base()}/api/devices/${enc(id)}", ApiJson.encodeToString(NameBody.serializer(), NameBody(name)), Device.serializer())

    suspend fun forgetDevice(id: String, deleteData: Boolean) =
        send("DELETE", "${base()}/api/devices/${enc(id)}?data=$deleteData", null, Unit.serializer())

    suspend fun ingest(batch: IngestBatch): IngestResult = ingestJson(ApiJson.encodeToString(IngestBatch.serializer(), batch))

    /** Sends a batch already turned into JSON (from the outbox). */
    suspend fun ingestJson(json: String): IngestResult = send("POST", "${base()}/api/ingest", json, IngestResult.serializer())

    // ---- Plumbing ----

    private suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    private suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true): T =
        withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request(method, url, body, auth)).execute()
            } catch (e: IOException) {
                throw ServerException(0, "Can't reach the server (${e.message ?: e.javaClass.simpleName})")
            }
            response.use { parse(it, serializer, auth) }
        }

    private fun request(method: String, url: String, body: String?, auth: Boolean): Request {
        val requestBody = body?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
        return Request.Builder().url(url).method(method, requestBody).apply {
            if (auth) token()?.let { header("Authorization", "Bearer $it") }
        }.build()
    }

    private fun <T> parse(response: okhttp3.Response, serializer: KSerializer<T>, auth: Boolean): T {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (response.code == 401 && auth && token() != null) onSignedOut()
            val message = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ServerException(response.code, message ?: "The server answered HTTP ${response.code}")
        }
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return try {
            ApiJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw ServerException(response.code, "The server's answer didn't make sense (${e.message?.substringBefore('\n')})")
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable
    private data class NameBody(val name: String)

    @Serializable
    private data class CopyRequest(val from: String, val to: String, val meal: Meal? = null)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A server address as typed ("fitness.local", "http://10.0.0.5:8080/") made whole, or null. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()?.let { withScheme }
        }

        /** Adds the app's sign-in to requests for the server (and nothing else). */
        fun authInterceptor(base: () -> String, token: () -> String?) = Interceptor { chain ->
            val request = chain.request()
            val server = base().toHttpUrlOrNull()
            val sameServer = server != null && request.url.host == server.host && request.url.port == server.port
            val tokenValue = token()
            if (sameServer && tokenValue != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $tokenValue").build())
            } else {
                chain.proceed(request)
            }
        }
    }
}
