package com.health.app.net

import com.health.api.CrisisResource
import com.health.api.CycleDay
import com.health.api.CycleOverview
import com.health.api.ContinenceEntry
import com.health.api.ContinenceSummary
import com.health.api.DoseLog
import com.health.api.JournalEntry
import com.health.api.LabResult
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.MindSummary
import com.health.api.MindfulnessSession
import com.health.api.MoodEntry
import com.health.api.ScreenerDefinition
import com.health.api.ScreenerResult
import com.health.api.ApiJson
import com.health.api.AppTokenRequest
import com.health.api.AuthConfig
import com.health.api.CheckIn
import com.health.api.DaySummary
import com.health.api.Device
import com.health.api.ErrorBody
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntry
import com.health.api.FoodEntryChange
import com.health.api.FoodEntryInput
import com.health.api.FoodSearch
import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.api.LoginRequest
import com.health.api.Meal
import com.health.api.MeasurementInput
import com.health.api.Me
import com.health.api.Metric
import com.health.api.Profile
import com.health.api.Sample
import com.health.api.SignedIn
import com.health.api.SleepSession
import com.health.api.SleepSummary
import com.health.api.Trends
import com.health.api.WaterEntry
import com.health.api.Workout
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
    internal val base: () -> String,
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

    // ---- Mind ----

    suspend fun moods(days: Int): List<MoodEntry> = get("${base()}/api/mind/moods?days=$days", ListSerializer(MoodEntry.serializer()))

    suspend fun addMood(entry: MoodEntry): MoodEntry = post("/api/mind/moods", entry, MoodEntry.serializer())

    suspend fun deleteMood(id: String) = delete("/api/mind/moods/${enc(id)}")

    suspend fun journal(days: Int): List<JournalEntry> = get("${base()}/api/mind/journal?days=$days", ListSerializer(JournalEntry.serializer()))

    suspend fun addJournal(entry: JournalEntry): JournalEntry = post("/api/mind/journal", entry, JournalEntry.serializer())

    suspend fun updateJournal(entry: JournalEntry): JournalEntry =
        send("PUT", "${base()}/api/mind/journal/${enc(entry.id!!)}", ApiJson.encodeToString(JournalEntry.serializer(), entry), JournalEntry.serializer())

    suspend fun deleteJournal(id: String) = delete("/api/mind/journal/${enc(id)}")

    suspend fun screeners(): List<ScreenerDefinition> = get("${base()}/api/mind/screeners", ListSerializer(ScreenerDefinition.serializer()))

    suspend fun screenerResults(days: Int): List<ScreenerResult> =
        get("${base()}/api/mind/screeners/results?days=$days", ListSerializer(ScreenerResult.serializer()))

    suspend fun submitScreener(result: ScreenerResult): ScreenerResult = post("/api/mind/screeners/results", result, ScreenerResult.serializer())

    suspend fun deleteScreener(id: String) = delete("/api/mind/screeners/results/${enc(id)}")

    suspend fun mindSessions(days: Int): List<MindfulnessSession> =
        get("${base()}/api/mind/sessions?days=$days", ListSerializer(MindfulnessSession.serializer()))

    suspend fun addMindSession(session: MindfulnessSession): MindfulnessSession = post("/api/mind/sessions", session, MindfulnessSession.serializer())

    suspend fun mindSummary(days: Int): MindSummary = get("${base()}/api/mind/summary?days=$days", MindSummary.serializer())

    suspend fun crisisResources(): List<CrisisResource> = get("${base()}/api/mind/resources", ListSerializer(CrisisResource.serializer()))

    // ---- Cycle ----

    suspend fun cycle(months: Int = 12): CycleOverview = get("${base()}/api/cycle?months=$months", CycleOverview.serializer())

    suspend fun saveCycleDay(day: CycleDay): CycleDay =
        send("PUT", "${base()}/api/cycle/days/${enc(day.date)}", ApiJson.encodeToString(CycleDay.serializer(), day), CycleDay.serializer())

    suspend fun deleteCycleDay(date: String) = delete("/api/cycle/days/${enc(date)}")

    // ---- Bladder and bowel ----

    suspend fun continence(days: Int): List<ContinenceEntry> =
        get("${base()}/api/continence/entries?days=$days", ListSerializer(ContinenceEntry.serializer()))

    suspend fun addContinence(entry: ContinenceEntry): ContinenceEntry = post("/api/continence/entries", entry, ContinenceEntry.serializer())

    suspend fun updateContinence(entry: ContinenceEntry): ContinenceEntry =
        send("PUT", "${base()}/api/continence/entries/${enc(entry.id!!)}", ApiJson.encodeToString(ContinenceEntry.serializer(), entry), ContinenceEntry.serializer())

    suspend fun deleteContinence(id: String) = delete("/api/continence/entries/${enc(id)}")

    suspend fun continenceSummary(days: Int): ContinenceSummary = get("${base()}/api/continence/summary?days=$days", ContinenceSummary.serializer())

    // ---- Medications ----

    suspend fun medications(all: Boolean = false): List<Medication> =
        get("${base()}/api/meds${if (all) "?all=true" else ""}", ListSerializer(Medication.serializer()))

    suspend fun addMedication(med: Medication): Medication = post("/api/meds", med, Medication.serializer())

    suspend fun updateMedication(med: Medication): Medication =
        send("PUT", "${base()}/api/meds/${enc(med.id!!)}", ApiJson.encodeToString(Medication.serializer(), med), Medication.serializer())

    suspend fun deleteMedication(id: String) = delete("/api/meds/${enc(id)}")

    suspend fun medsDay(date: String): MedsDay = get("${base()}/api/meds/day/${enc(date)}", MedsDay.serializer())

    suspend fun doses(days: Int): List<DoseLog> = get("${base()}/api/meds/doses?days=$days", ListSerializer(DoseLog.serializer()))

    suspend fun logDose(dose: DoseLog): DoseLog = post("/api/meds/doses", dose, DoseLog.serializer())

    /** Sends a dose already turned into JSON (from the reminders' outbox). */
    suspend fun logDoseJson(json: String): DoseLog = send("POST", "${base()}/api/meds/doses", json, DoseLog.serializer())

    suspend fun deleteDose(id: String) = delete("/api/meds/doses/${enc(id)}")

    suspend fun labs(days: Int = 3650): List<LabResult> = get("${base()}/api/labs?days=$days", ListSerializer(LabResult.serializer()))

    suspend fun addLab(lab: LabResult): LabResult = post("/api/labs", lab, LabResult.serializer())

    suspend fun deleteLab(id: String) = delete("/api/labs/${enc(id)}")

    // ---- Plumbing (internal, so each feature's calls can live in its own file as extensions) ----

    internal suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    internal suspend fun <T> post(path: String, body: T, serializer: KSerializer<T>): T =
        send("POST", "${base()}$path", ApiJson.encodeToString(serializer, body), serializer)

    internal suspend fun delete(path: String) = send("DELETE", "${base()}$path", null, Unit.serializer())

    internal suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true): T =
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

    internal fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable
    private data class NameBody(val name: String)

    @Serializable
    private data class CopyRequest(val from: String, val to: String, val meal: Meal? = null)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A server address as typed ("health.local", "http://10.0.0.5:8080/") made whole, or null. */
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
