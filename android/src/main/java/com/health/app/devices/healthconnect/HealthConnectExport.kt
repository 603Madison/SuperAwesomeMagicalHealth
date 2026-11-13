package com.health.app.devices.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Volume
import com.health.api.ApiJson
import com.health.api.BloodPressure
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Drink
import com.health.api.Fields
import com.health.api.FoodEntry
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.Meal
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.app.data.DeviceChoices
import com.health.app.net.ServerApi
import com.health.app.net.bloodPressure
import com.health.app.net.drinksDay
import com.health.app.net.fields
import com.health.app.net.glucose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.reflect.KClass

/*
 * Writing to Health Connect, so other apps get the person's data: Samsung Health, Google Fit,
 * MyFitnessPal, MyNetDiary and the rest read from it. Each kind of data is the person's choice
 * (Settings › Export), with Health Connect's permission asked for that kind only. Every record
 * carries a stable client record id ("samh:…"), so writing again updates rather than duplicates,
 * and the Health Connect conduit never reads them back. Readings that came from Health Connect in
 * the first place (from Garmin Connect, say) aren't written back to it.
 */

/** The kinds of data that can go to Health Connect, each with its record type. */
enum class ExportKind(val label: String, val about: String, val record: KClass<out Record>) {
    WEIGHT("Weight", "Read by Samsung Health, Google Fit, MyFitnessPal, MyNetDiary and more", WeightRecord::class),
    BODY_FAT("Body fat", "Read by Samsung Health, Google Fit and more", BodyFatRecord::class),
    BLOOD_PRESSURE("Blood pressure", "Read by Samsung Health and others", BloodPressureRecord::class),
    GLUCOSE("Blood glucose", "Read by Samsung Health, MyNetDiary and others", BloodGlucoseRecord::class),
    HYDRATION("Water and drinks", "Read by Samsung Health, Google Fit, MyFitnessPal and more", HydrationRecord::class),
    NUTRITION("Food and nutrition", "Read by Samsung Health, MyFitnessPal, MyNetDiary and more", NutritionRecord::class),
    WORKOUTS("Workouts", "Read by Samsung Health, Google Fit, Strava and more", ExerciseSessionRecord::class),
    ;

    val permission: String get() = HealthPermission.getWritePermission(record)
}

/** A record to write, with what identifies it and a fingerprint of its content (to skip what's unchanged). */
data class Outgoing(val kind: ExportKind, val clientId: String, val at: Long, val fingerprint: String, val record: (version: Long) -> Record)

/** How the app's data becomes Health Connect records. Pure, so it can be tested. */
object HealthConnectExportMapping {
    /** Every record this app writes has a client record id starting with this. */
    const val PREFIX = "samh:"

    private fun meta(id: String, version: Long) = Metadata.manualEntry(clientRecordId = PREFIX + id, clientRecordVersion = version)

    private fun zone(at: Long, zone: ZoneId) = zone.rules.getOffset(Instant.ofEpochMilli(at))

    /**
     * The names of the devices whose data came through Health Connect: the conduit names them after
     * the app that wrote them, with the app's package as their identifier.
     */
    fun healthConnectSources(devices: List<Device>): Set<String> = devices.filter { d ->
        d.kind == DeviceKind.HEALTH_CONNECT || ((d.kind == DeviceKind.GARMIN || d.kind == DeviceKind.PEBBLE) && PACKAGE.matches(d.identifier))
    }.map { it.name }.toSet()

    private val PACKAGE = Regex("[a-zA-Z][\\w]*(\\.[a-zA-Z_][\\w]*)+")

    /** Whether a reading from [source] should go to Health Connect: not if it came from there. */
    fun mayWrite(source: String?, fromHealthConnect: Set<String>): Boolean = source == null || source !in fromHealthConnect

    /**
     * The chosen [kinds] to write, given the person's shown [fields]: water and drinks only while
     * water is shown ([Fields.NUTRITION_WATER]). Fields not known (null): not written this time.
     */
    fun writable(kinds: Set<ExportKind>, fields: Set<String>?): Set<ExportKind> =
        if (fields != null && Fields.NUTRITION_WATER in fields) kinds else kinds - ExportKind.HYDRATION

    fun weight(s: Sample, zone: ZoneId) = Outgoing(
        ExportKind.WEIGHT, "weight:${s.id ?: s.start}", s.start, "${s.start}:${s.value}",
    ) { v -> WeightRecord(Instant.ofEpochMilli(s.start), zone(s.start, zone), Mass.kilograms(s.value), meta("weight:${s.id ?: s.start}", v)) }

    fun bodyFat(s: Sample, zone: ZoneId) = Outgoing(
        ExportKind.BODY_FAT, "bodyfat:${s.id ?: s.start}", s.start, "${s.start}:${s.value}",
    ) { v -> BodyFatRecord(Instant.ofEpochMilli(s.start), zone(s.start, zone), Percentage(s.value.coerceIn(0.0, 100.0)), meta("bodyfat:${s.id ?: s.start}", v)) }

    fun bloodPressure(b: BloodPressure, zone: ZoneId): Outgoing {
        val id = "bp:${b.id ?: b.at}"
        return Outgoing(ExportKind.BLOOD_PRESSURE, id, b.at, "${b.at}:${b.systolic}/${b.diastolic}:${b.arm}") { v ->
            BloodPressureRecord(
                time = Instant.ofEpochMilli(b.at), zoneOffset = zone(b.at, zone), metadata = meta(id, v),
                systolic = Pressure.millimetersOfMercury(b.systolic.toDouble().coerceIn(20.0, 200.0)),
                diastolic = Pressure.millimetersOfMercury(b.diastolic.toDouble().coerceIn(10.0, 180.0)),
                measurementLocation = when (b.arm) {
                    "left" -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM
                    "right" -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM
                    else -> BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
                },
            )
        }
    }

    fun glucose(g: GlucoseReading, zone: ZoneId): Outgoing {
        val id = "glucose:${g.id ?: g.at}"
        return Outgoing(ExportKind.GLUCOSE, id, g.at, "${g.at}:${g.mmolL}:${g.context}") { v ->
            BloodGlucoseRecord(
                time = Instant.ofEpochMilli(g.at), zoneOffset = zone(g.at, zone), metadata = meta(id, v),
                level = BloodGlucose.millimolesPerLiter(g.mmolL.coerceIn(0.0, 50.0)),
                specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN,
                mealType = MealType.MEAL_TYPE_UNKNOWN,
                relationToMeal = relationToMeal(g.context),
            )
        }
    }

    fun relationToMeal(context: GlucoseContext): Int = when (context) {
        GlucoseContext.FASTING -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        GlucoseContext.BEFORE_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
        GlucoseContext.AFTER_MEAL -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
        else -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
    }

    /** Water logged with food: a minute's drink at the time logged (or midday). */
    fun water(w: WaterEntry, zone: ZoneId): Outgoing? {
        val id = "water:${w.id ?: return null}"
        val at = w.at ?: LocalDate.parse(w.date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return Outgoing(ExportKind.HYDRATION, id, at, "$at:${w.ml}") { v ->
            HydrationRecord(Instant.ofEpochMilli(at), zone(at, zone), Instant.ofEpochMilli(at + 60_000), zone(at, zone), Volume.milliliters(w.ml.toDouble().coerceIn(1.0, 99_000.0)), meta(id, v))
        }
    }

    /** A drink without alcohol counts as fluid, as on the server. */
    fun drink(d: Drink, zone: ZoneId): Outgoing? {
        if (d.alcoholG > 0.0 || d.volumeMl <= 0) return null
        val id = "drink:${d.id ?: return null}"
        return Outgoing(ExportKind.HYDRATION, id, d.at, "${d.at}:${d.volumeMl}") { v ->
            HydrationRecord(Instant.ofEpochMilli(d.at), zone(d.at, zone), Instant.ofEpochMilli(d.at + 60_000), zone(d.at, zone), Volume.milliliters(d.volumeMl.toDouble()), meta(id, v))
        }
    }

    /** When a meal's food is put if it wasn't logged at a time. */
    fun mealTime(meal: Meal): LocalTime = when (meal) {
        Meal.BREAKFAST -> LocalTime.of(8, 0)
        Meal.LUNCH -> LocalTime.of(12, 30)
        Meal.DINNER -> LocalTime.of(18, 30)
        else -> LocalTime.of(15, 30)
    }

    fun mealType(meal: Meal): Int = when (meal) {
        Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
        Meal.DINNER -> MealType.MEAL_TYPE_DINNER
        else -> MealType.MEAL_TYPE_SNACK
    }

    fun food(f: FoodEntry, zone: ZoneId): Outgoing? {
        val id = "food:${f.id ?: return null}"
        val at = f.loggedAt ?: LocalDate.parse(f.date).atTime(mealTime(f.meal)).atZone(zone).toInstant().toEpochMilli()
        val n = f.nutrients
        return Outgoing(ExportKind.NUTRITION, id, at, "$at:${f.name}:${f.meal}:$n") { v ->
            NutritionRecord(
                startTime = Instant.ofEpochMilli(at), startZoneOffset = zone(at, zone),
                endTime = Instant.ofEpochMilli(at + 60_000), endZoneOffset = zone(at, zone),
                metadata = meta(id, v),
                energy = Energy.kilocalories(n.calories.coerceIn(0.0, 99_999.0)),
                protein = Mass.grams(n.protein.coerceIn(0.0, 99_999.0)),
                totalCarbohydrate = Mass.grams(n.carbs.coerceIn(0.0, 99_999.0)),
                totalFat = Mass.grams(n.fat.coerceIn(0.0, 99_999.0)),
                dietaryFiber = n.fiber?.let { Mass.grams(it.coerceIn(0.0, 99_999.0)) },
                sugar = n.sugar?.let { Mass.grams(it.coerceIn(0.0, 99_999.0)) },
                saturatedFat = n.saturatedFat?.let { Mass.grams(it.coerceIn(0.0, 99_999.0)) },
                sodium = n.sodiumMg?.let { Mass.milligrams(it.coerceIn(0.0, 99_999_000.0)) },
                name = f.name.take(100),
                mealType = mealType(f.meal),
            )
        }
    }

    fun workout(w: Workout, zone: ZoneId): Outgoing? {
        if (w.end <= w.start) return null
        val id = "workout:${w.id ?: w.start}"
        return Outgoing(ExportKind.WORKOUTS, id, w.start, "${w.start}:${w.end}:${w.type}:${w.name}:${w.note}") { v ->
            ExerciseSessionRecord(
                startTime = Instant.ofEpochMilli(w.start), startZoneOffset = zone(w.start, zone),
                endTime = Instant.ofEpochMilli(w.end), endZoneOffset = zone(w.end, zone),
                metadata = meta(id, v),
                exerciseType = exerciseType(w.type),
                title = w.name ?: w.type.label,
                notes = w.note,
            )
        }
    }

    fun exerciseType(type: WorkoutType): Int = when (type) {
        WorkoutType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        WorkoutType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        WorkoutType.CYCLE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        WorkoutType.SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        WorkoutType.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        WorkoutType.HIIT -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        WorkoutType.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        WorkoutType.PILATES -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        WorkoutType.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        WorkoutType.ROW -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        WorkoutType.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        WorkoutType.DANCE -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        WorkoutType.CLIMB -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        WorkoutType.SKI -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        WorkoutType.SPORT, WorkoutType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    }

    /** What's new or changed since [written] (client id to fingerprint), and what's gone (written in the window, no longer there). */
    fun plan(outgoing: List<Outgoing>, written: Map<String, Written>, kinds: Set<ExportKind>, windowStart: Long): Pair<List<Outgoing>, List<String>> {
        val changed = outgoing.filter { written[it.clientId]?.fingerprint != it.fingerprint }
        val ids = outgoing.map { it.clientId }.toSet()
        val gone = written.filter { (id, w) -> id !in ids && w.at >= windowStart && w.kind in kinds.map { it.name } }.keys.toList()
        return changed to gone
    }
}

/** What was written: its kind, when it's for, and its content's fingerprint. */
@Serializable
data class Written(val kind: String, val at: Long, val fingerprint: String)

/**
 * Writes the chosen kinds of the last [WINDOW_DAYS] days to Health Connect after each sync.
 * Remembers what it wrote (on the phone, in a small file) so unchanged records aren't written again
 * and ones deleted on the server go from Health Connect too.
 */
class HealthConnectWriter(
    private val context: Context,
    private val api: ServerApi,
    private val choices: DeviceChoices,
    private val zone: () -> ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val ledgerFile = File(context.filesDir, "health-connect-written.json")
    private val ledgerSerializer = MapSerializer(String.serializer(), Written.serializer())

    fun available(): Boolean = runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    private fun client(): HealthConnectClient? = if (available()) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    fun chosen(): Set<ExportKind> = choices.healthConnectExport.value.mapNotNull { n -> ExportKind.entries.firstOrNull { it.name == n } }.toSet()

    /** The write permissions for [kinds], and nothing else. */
    fun permissions(kinds: Set<ExportKind>): Set<String> = kinds.map { it.permission }.toSet()

    suspend fun granted(): Set<String> = client()?.let { runCatching { it.permissionController.getGrantedPermissions() }.getOrNull() }.orEmpty()

    /** Writes what's chosen and allowed; a line for people, or null when nothing's chosen. */
    suspend fun run(): String? = mutex.withLock {
        val kinds = chosen()
        if (kinds.isEmpty()) return null
        val client = client() ?: return "Health Connect isn't available on this phone".also(choices::setHealthConnectNote)
        val granted = granted()
        // Water and drinks only while the person shows water (Settings › Features › Water).
        val fields = if (ExportKind.HYDRATION in kinds) runCatching { api.fields().effective }.getOrNull() else null
        val wanted = HealthConnectExportMapping.writable(kinds, fields)
        if (wanted.isEmpty()) return "Water is hidden, so nothing's written".also(choices::setHealthConnectNote)
        val allowed = wanted.filter { it.permission in granted }.toSet()
        if (allowed.isEmpty()) return "Health Connect hasn't allowed the app to write yet".also(choices::setHealthConnectNote)
        val now = clock()
        val windowStart = now - WINDOW_DAYS * 86_400_000L
        val problems = mutableListOf<String>()
        val outgoing = mutableListOf<Outgoing>()
        val fetched = mutableSetOf<ExportKind>()
        val z = zone()
        val sources = runCatching { HealthConnectExportMapping.healthConnectSources(api.devices()) }.getOrElse { return "Couldn't reach the server".also(choices::setHealthConnectNote) }
        suspend fun fetch(kind: ExportKind, block: suspend () -> List<Outgoing>) {
            if (kind !in allowed) return
            try {
                outgoing += block()
                fetched += kind
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't fetch ${kind.label}", e)
                problems += kind.label.lowercase()
            }
        }
        fetch(ExportKind.WEIGHT) { api.measurements(Metric.WEIGHT, WINDOW_DAYS).filter { HealthConnectExportMapping.mayWrite(it.source, sources) }.map { HealthConnectExportMapping.weight(it, z) } }
        fetch(ExportKind.BODY_FAT) { api.measurements(Metric.BODY_FAT, WINDOW_DAYS).filter { HealthConnectExportMapping.mayWrite(it.source, sources) }.map { HealthConnectExportMapping.bodyFat(it, z) } }
        fetch(ExportKind.BLOOD_PRESSURE) { api.bloodPressure(WINDOW_DAYS).filter { HealthConnectExportMapping.mayWrite(it.source, sources) }.map { HealthConnectExportMapping.bloodPressure(it, z) } }
        fetch(ExportKind.GLUCOSE) { api.glucose(WINDOW_DAYS).filter { HealthConnectExportMapping.mayWrite(it.source, sources) }.map { HealthConnectExportMapping.glucose(it, z) } }
        fetch(ExportKind.WORKOUTS) { api.workouts(WINDOW_DAYS).filter { HealthConnectExportMapping.mayWrite(it.source, sources) }.mapNotNull { HealthConnectExportMapping.workout(it, z) } }
        // Food and drink are logged by hand, a day at a time: the last week.
        val days = (0 until FOOD_DAYS).map { LocalDate.now(z).minusDays(it.toLong()).toString() }
        fetch(ExportKind.HYDRATION) {
            days.flatMap { d ->
                api.foodDay(d).water.mapNotNull { HealthConnectExportMapping.water(it, z) } + api.drinksDay(d).drinks.mapNotNull { HealthConnectExportMapping.drink(it, z) }
            }
        }
        fetch(ExportKind.NUTRITION) { days.flatMap { d -> api.foodDay(d).entries.mapNotNull { HealthConnectExportMapping.food(it, z) } } }

        val ledger = readLedger().filterValues { it.at >= now - KEEP_DAYS * 86_400_000L }.toMutableMap()
        // Food and drink only cover a week, so only a week of them can be known to be gone.
        val foodStart = LocalDate.now(z).minusDays(FOOD_DAYS - 1L).atStartOfDay(z).toInstant().toEpochMilli()
        var written = 0
        var removed = 0
        for (kind in fetched) {
            val (changed, gone) = HealthConnectExportMapping.plan(
                outgoing.filter { it.kind == kind }, ledger, setOf(kind),
                if (kind == ExportKind.HYDRATION || kind == ExportKind.NUTRITION) foodStart else windowStart,
            )
            try {
                changed.chunked(500).forEach { part ->
                    client.insertRecords(part.map { it.record(now) })
                    part.forEach { ledger[it.clientId] = Written(kind.name, it.at, it.fingerprint) }
                    written += part.size
                }
                if (gone.isNotEmpty()) {
                    client.deleteRecords(kind.record, recordIdsList = emptyList(), clientRecordIdsList = gone.map { HealthConnectExportMapping.PREFIX + it })
                    gone.forEach { ledger.remove(it) }
                    removed += gone.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't write ${kind.label}", e)
                problems += kind.label.lowercase()
            }
        }
        writeLedger(ledger)
        val note = when {
            problems.isNotEmpty() -> "Couldn't write ${problems.distinct().joinToString(", ")}"
            written + removed == 0 -> "Up to date"
            else -> "Wrote $written to Health Connect" + (if (removed > 0) ", took away $removed" else "")
        }
        choices.setHealthConnectNote(note)
        note
    }

    /** Signing out: what was written is forgotten here (it stays in Health Connect, which the person controls). */
    fun forget() {
        runCatching { ledgerFile.delete() }
    }

    private suspend fun readLedger(): Map<String, Written> = withContext(Dispatchers.IO) {
        runCatching { ApiJson.decodeFromString(ledgerSerializer, ledgerFile.readText()) }.getOrDefault(emptyMap())
    }

    private suspend fun writeLedger(ledger: Map<String, Written>) = withContext(Dispatchers.IO) {
        runCatching { ledgerFile.writeText(ApiJson.encodeToString(ledgerSerializer, ledger)) }
    }

    companion object {
        private const val TAG = "HealthConnectWrite"
        const val WINDOW_DAYS = 30
        const val FOOD_DAYS = 7
        private const val KEEP_DAYS = 60
    }
}
