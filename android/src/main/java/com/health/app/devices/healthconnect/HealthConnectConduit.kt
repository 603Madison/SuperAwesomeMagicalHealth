package com.health.app.devices.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.health.api.BloodPressure
import com.health.api.DeviceInfo
import com.health.api.GlucoseReading
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.StageSpan
import com.health.api.Workout
import com.health.app.sync.Collected
import com.health.app.sync.Conduit
import com.health.app.sync.SyncStore
import kotlinx.coroutines.CancellationException
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Health Connect, Android's shared health store: what Garmin Connect, Gadgetbridge, the Pebble
 * app, Fitbit, Samsung Health and others write there comes to the server, one device per app.
 */
class HealthConnectConduit(
    private val context: Context,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Conduit {
    override val id = ID
    override val title = "Health Connect"

    /** Whether Health Connect is on this phone (built in from Android 14; an app before). */
    fun availability(): Int = runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    private fun client(): HealthConnectClient? =
        if (availability() == HealthConnectClient.SDK_AVAILABLE) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    /** The permissions to ask for: reading each kind of record, in the background, and the past. */
    fun permissions(): Set<String> {
        val reads = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toMutableSet()
        val client = client()
        if (client != null) {
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                reads += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            }
            if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                reads += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
            }
        }
        return reads
    }

    suspend fun granted(): Set<String> = client()?.let { runCatching { it.permissionController.getGrantedPermissions() }.getOrNull() }.orEmpty()

    override suspend fun ready(): Boolean = store.healthConnectOn.value && granted().any { it.contains("READ_") }

    override suspend fun collect(since: Long?): Collected {
        val client = client() ?: throw IllegalStateException("Health Connect isn't available on this phone")
        val granted = granted()
        val now = clock()
        // Readings can arrive late (a watch syncing hours after), so each sync looks back a little.
        val start = since?.let { it - OVERLAP_MS } ?: (now - FIRST_SYNC_MS)
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(now))
        val own = context.packageName
        val builders = LinkedHashMap<String, Builder>()
        fun builder(record: Record): Builder? {
            val origin = record.metadata.dataOrigin.packageName
            if (origin == own) return null
            return builders.getOrPut(origin) { Builder(origin) }
        }
        val problems = ArrayList<String>()

        suspend fun <T : Record> each(type: KClass<T>, label: String, take: (T) -> Unit) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            try {
                var page: String? = null
                do {
                    val response = client.readRecords(ReadRecordsRequest(type, range, pageSize = 2_000, pageToken = page))
                    response.records.forEach(take)
                    page = response.pageToken
                } while (!page.isNullOrEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading $label failed", e)
                problems += label
            }
        }

        each(StepsRecord::class, "steps") { r ->
            builder(r)?.samples?.add(Sample(Metric.STEPS, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.count.toDouble()))
        }
        each(DistanceRecord::class, "distance") { r ->
            builder(r)?.samples?.add(Sample(Metric.DISTANCE, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.distance.inMeters))
        }
        each(ActiveCaloriesBurnedRecord::class, "active calories") { r ->
            builder(r)?.samples?.add(Sample(Metric.ACTIVE_CALORIES, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.energy.inKilocalories))
        }
        each(FloorsClimbedRecord::class, "floors") { r ->
            builder(r)?.samples?.add(Sample(Metric.FLOORS, r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.floors))
        }
        each(HeartRateRecord::class, "heart rate") { r ->
            val b = builder(r) ?: return@each
            r.samples.forEach { s -> b.samples.add(Sample(Metric.HEART_RATE, s.time.toEpochMilli(), value = s.beatsPerMinute.toDouble())) }
        }
        each(RestingHeartRateRecord::class, "resting heart rate") { r ->
            builder(r)?.samples?.add(Sample(Metric.RESTING_HEART_RATE, r.time.toEpochMilli(), value = r.beatsPerMinute.toDouble()))
        }
        each(HeartRateVariabilityRmssdRecord::class, "heart rate variability") { r ->
            builder(r)?.samples?.add(Sample(Metric.HRV, r.time.toEpochMilli(), value = r.heartRateVariabilityMillis))
        }
        each(OxygenSaturationRecord::class, "blood oxygen") { r ->
            builder(r)?.samples?.add(Sample(Metric.SPO2, r.time.toEpochMilli(), value = r.percentage.value))
        }
        each(RespiratoryRateRecord::class, "respiratory rate") { r ->
            builder(r)?.samples?.add(Sample(Metric.RESPIRATORY_RATE, r.time.toEpochMilli(), value = r.rate))
        }
        each(WeightRecord::class, "weight") { r ->
            builder(r)?.samples?.add(Sample(Metric.WEIGHT, r.time.toEpochMilli(), value = r.weight.inKilograms))
        }
        each(BodyFatRecord::class, "body fat") { r ->
            builder(r)?.samples?.add(Sample(Metric.BODY_FAT, r.time.toEpochMilli(), value = r.percentage.value))
        }
        each(BloodPressureRecord::class, "blood pressure") { r ->
            builder(r)?.bloodPressure?.add(
                BloodPressure(
                    r.time.toEpochMilli(), r.systolic.inMillimetersOfMercury.roundToInt(), r.diastolic.inMillimetersOfMercury.roundToInt(),
                    arm = HealthConnectMapping.arm(r.measurementLocation),
                ),
            )
        }
        each(BloodGlucoseRecord::class, "glucose") { r ->
            builder(r)?.glucose?.add(
                GlucoseReading(r.time.toEpochMilli(), Math.round(r.level.inMillimolesPerLiter * 10) / 10.0, HealthConnectMapping.glucoseContext(r.relationToMeal)),
            )
        }
        each(SleepSessionRecord::class, "sleep") { r ->
            val b = builder(r) ?: return@each
            val stages = r.stages.mapNotNull { s ->
                HealthConnectMapping.sleepStage(s.stage)?.let { StageSpan(it, s.startTime.toEpochMilli(), s.endTime.toEpochMilli()) }
            }
            b.sleep.add(SleepSession(start = r.startTime.toEpochMilli(), end = r.endTime.toEpochMilli(), stages = stages, note = r.title))
        }
        each(ExerciseSessionRecord::class, "workouts") { r ->
            val b = builder(r) ?: return@each
            b.workouts.add(
                Workout(
                    type = HealthConnectMapping.workoutType(r.exerciseType),
                    start = r.startTime.toEpochMilli(),
                    end = r.endTime.toEpochMilli(),
                    name = r.title?.ifBlank { null },
                    note = r.notes?.ifBlank { null },
                ),
            )
        }

        val batches = builders.values.map { it.build() }
        val note = if (problems.isEmpty()) null else "Couldn't read ${problems.joinToString(", ")}"
        return Collected(batches, now, note)
    }

    private class Builder(val origin: String) {
        val samples = ArrayList<Sample>()
        val sleep = ArrayList<SleepSession>()
        val workouts = ArrayList<Workout>()
        val bloodPressure = ArrayList<BloodPressure>()
        val glucose = ArrayList<GlucoseReading>()

        fun build() = IngestBatch(
            device = DeviceInfo(
                kind = HealthConnectMapping.kind(origin),
                identifier = origin,
                name = HealthConnectMapping.friendlyName(origin),
            ),
            samples = samples,
            sleep = sleep,
            workouts = workouts,
            bloodPressure = bloodPressure,
            glucose = glucose,
        )
    }

    companion object {
        const val ID = "health_connect"
        private const val TAG = "HealthConnect"
        private const val OVERLAP_MS = 6 * 3_600_000L
        private const val FIRST_SYNC_MS = 30 * 86_400_000L

        val RECORD_TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
            OxygenSaturationRecord::class, RespiratoryRateRecord::class, SleepSessionRecord::class,
            ExerciseSessionRecord::class, WeightRecord::class, BodyFatRecord::class, BloodPressureRecord::class,
            BloodGlucoseRecord::class,
        )
    }
}
