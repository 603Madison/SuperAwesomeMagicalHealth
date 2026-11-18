package com.health.api

import kotlinx.serialization.Serializable

/*
 * The health data the server keeps, as it goes over the wire. Everything is metric (kg, cm,
 * metres, kcal, ml, °C); the apps convert for display. Instants are epoch milliseconds; days are
 * the person's local dates as "YYYY-MM-DD" (in the time zone of their profile).
 */

/** How a metric's readings combine over a day. */
@Serializable
enum class Aggregation { SUM, AVERAGE, LATEST }

/** A kind of reading a device (or a person) records. */
@Serializable
enum class Metric(val unit: String, val aggregation: Aggregation, val label: String) {
    HEART_RATE("bpm", Aggregation.AVERAGE, "Heart rate"),
    RESTING_HEART_RATE("bpm", Aggregation.AVERAGE, "Resting heart rate"),
    /** Heart rate variability, RMSSD. */
    HRV("ms", Aggregation.AVERAGE, "Heart rate variability"),
    SPO2("%", Aggregation.AVERAGE, "Blood oxygen"),
    RESPIRATORY_RATE("br/min", Aggregation.AVERAGE, "Respiratory rate"),
    /** Skin temperature as measured (a ring's or watch's sensor). */
    SKIN_TEMPERATURE("°C", Aggregation.AVERAGE, "Skin temperature"),
    /** Skin temperature relative to the person's own baseline, where the device reports it so. */
    TEMPERATURE_DEVIATION("°C", Aggregation.AVERAGE, "Temperature deviation"),
    /** 0–100, as the device rates it. */
    STRESS("", Aggregation.AVERAGE, "Stress"),
    /** Steps taken over [Sample.start]..[Sample.end]. */
    STEPS("steps", Aggregation.SUM, "Steps"),
    DISTANCE("m", Aggregation.SUM, "Distance"),
    ACTIVE_CALORIES("kcal", Aggregation.SUM, "Active calories"),
    FLOORS("floors", Aggregation.SUM, "Floors climbed"),
    WEIGHT("kg", Aggregation.LATEST, "Weight"),
    BODY_FAT("%", Aggregation.LATEST, "Body fat"),
    BLOOD_PRESSURE_SYSTOLIC("mmHg", Aggregation.LATEST, "Systolic blood pressure"),
    BLOOD_PRESSURE_DIASTOLIC("mmHg", Aggregation.LATEST, "Diastolic blood pressure"),
    BLOOD_GLUCOSE("mmol/L", Aggregation.LATEST, "Blood glucose"),
    // Body measurements, in cm (for transition, and for weight and fitness goals).
    WAIST("cm", Aggregation.LATEST, "Waist"),
    HIPS("cm", Aggregation.LATEST, "Hips"),
    CHEST("cm", Aggregation.LATEST, "Chest"),
    BUST("cm", Aggregation.LATEST, "Bust"),
    UNDERBUST("cm", Aggregation.LATEST, "Underbust"),
    SHOULDERS("cm", Aggregation.LATEST, "Shoulders"),
    NECK("cm", Aggregation.LATEST, "Neck"),
    UPPER_ARM("cm", Aggregation.LATEST, "Upper arm"),
    THIGH("cm", Aggregation.LATEST, "Thigh"),
    CALF("cm", Aggregation.LATEST, "Calf"),
    /** From a chest strap's beat-to-beat timing: the share (0–100) of a five-minute window's 128-beat stretches that looked irregular (possibly atrial fibrillation; also extra beats, a loose strap or movement). */
    IRREGULAR_RHYTHM("%", Aggregation.AVERAGE, "Irregular rhythm"),
}

/** One reading: at [start], or (for sums like steps) over [start]..[end]. */
@Serializable
data class Sample(
    val metric: Metric,
    val start: Long,
    val end: Long? = null,
    val value: Double,
    /** Set by the server: lets a person's own entries (a weight) be deleted. */
    val id: Long? = null,
    /** Set by the server: the device's name. */
    val source: String? = null,
)

@Serializable
enum class SleepStage { AWAKE, LIGHT, DEEP, REM, /** Asleep, stage unknown. */ ASLEEP }

@Serializable
data class StageSpan(val stage: SleepStage, val start: Long, val end: Long)

/** A night (or a nap): in bed from [start] to [end], with the stages when the device knows them. */
@Serializable
data class SleepSession(
    val start: Long,
    val end: Long,
    val stages: List<StageSpan> = emptyList(),
    val id: String? = null,
    val deviceId: String? = null,
    val source: String? = null,
    /** Entered by the person rather than recorded. */
    val manual: Boolean = false,
    val note: String? = null,
)

@Serializable
enum class WorkoutType(val label: String) {
    WALK("Walk"), RUN("Run"), CYCLE("Ride"), SWIM("Swim"), STRENGTH("Strength"), HIIT("HIIT"),
    YOGA("Yoga"), PILATES("Pilates"), HIKE("Hike"), ROW("Row"), ELLIPTICAL("Elliptical"),
    DANCE("Dance"), SPORT("Sport"), CLIMB("Climb"), SKI("Ski"), OTHER("Workout"),
}

@Serializable
data class Workout(
    val type: WorkoutType,
    val start: Long,
    val end: Long,
    val name: String? = null,
    val distanceM: Double? = null,
    val activeCalories: Double? = null,
    val avgHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val steps: Int? = null,
    /** How hard it felt, 1–10 (rate of perceived exertion). */
    val effort: Int? = null,
    val note: String? = null,
    val id: String? = null,
    val deviceId: String? = null,
    val source: String? = null,
    val manual: Boolean = false,
)

/** Where data comes from. */
@Serializable
enum class DeviceKind(val label: String) {
    COLMI_RING("Colmi ring"),
    PEBBLE("Pebble"),
    GARMIN("Garmin"),
    HEALTH_CONNECT("Health Connect"),
    GADGETBRIDGE("Gadgetbridge"),
    FIT_FILE("FIT files"),
    PHONE("Phone"),
    /** Any standard Bluetooth heart rate strap (Heart Rate Service), read by the app itself. */
    HEART_RATE_STRAP("Heart rate strap"),
    BP_MONITOR("Blood pressure monitor"),
    GLUCOSE_METER("Glucose meter"),
    MANUAL("Entered by hand"),
    OTHER("Other device"),
}

/** A device as the uploader describes it; [identifier] tells it apart (a MAC address, a serial). */
@Serializable
data class DeviceInfo(
    val kind: DeviceKind,
    val identifier: String,
    val name: String,
    val model: String? = null,
    val firmware: String? = null,
    /** Battery, 0–100. */
    val battery: Int? = null,
)

/** A device the server has had data from. */
@Serializable
data class Device(
    val id: String,
    val kind: DeviceKind,
    val identifier: String,
    val name: String,
    val model: String? = null,
    val firmware: String? = null,
    val battery: Int? = null,
    val lastSyncAt: Long? = null,
    val createdAt: Long,
    /** Readings kept from it. */
    val samples: Long = 0,
)

/**
 * Data from one device, sent by the phone app (or a watch app, or a script with an upload key).
 * Sending the same readings again is harmless: they replace what's there.
 */
@Serializable
data class IngestBatch(
    val device: DeviceInfo,
    val samples: List<Sample> = emptyList(),
    val sleep: List<SleepSession> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val bloodPressure: List<BloodPressure> = emptyList(),
    val glucose: List<GlucoseReading> = emptyList(),
)

@Serializable
data class IngestResult(val deviceId: String, val samples: Int, val sleep: Int, val workouts: Int, val readings: Int = 0)

/** A key that can only upload data, for a watch app or a script. Shown once. */
@Serializable
data class UploadKey(val id: String, val name: String, val token: String, val url: String)

@Serializable
data class UploadKeyRequest(val name: String = "Upload key")

/** A reading entered by hand (a weight, a blood pressure). */
@Serializable
data class MeasurementInput(val metric: Metric, val value: Double, val at: Long? = null)

/** How the person felt: each 1–5 where given, with tags ("alcohol", "late meal") and a note. */
@Serializable
data class CheckIn(
    val date: String,
    val mood: Int? = null,
    val energy: Int? = null,
    val stress: Int? = null,
    val soreness: Int? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
data class Point(val t: Long, val v: Double)

@Serializable
data class FitImportResult(val workouts: Int, val samples: Int, val sleep: Int, val message: String)

// ---------------------------------------------------------------- The last 24 hours

/** One reading in [RecentSeries]: at a time, or (for counts like steps) the total from [at] for its bucket. */
@Serializable
data class RecentPoint(val at: Long, val value: Double)

/**
 * One kind of reading over the window (GET /api/recent): its points, oldest first, and a summary.
 * [metric] is null for a kind this version doesn't know (a newer server's): skip it.
 * Counts (steps) come as totals per [bucketMinutes], with [total] for the window; the rest come as
 * recorded (thinned to a few minutes apart when there are very many), with [min], [max],
 * [average] and the [latest].
 */
@Serializable
data class RecentSeries(
    val metric: Metric? = null,
    val points: List<RecentPoint> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val average: Double? = null,
    val latest: RecentPoint? = null,
    val total: Double? = null,
    val bucketMinutes: Int? = null,
)

/** A device with something recorded in the window (for choosing one). */
@Serializable
data class RecentDevice(val id: String, val name: String, val kind: DeviceKind? = null, val lastSyncAt: Long? = null)

/**
 * The last [hours] as recorded (GET /api/recent?hours=24&device=): the nights and naps in it with
 * their stages, and each kind of reading, from [device] alone or (null) every device: heart rate
 * merged in time, steps never counted twice. [devices] are every device with something in it.
 */
@Serializable
data class RecentReadings(
    val from: Long,
    val to: Long,
    val hours: Int = 24,
    val device: String? = null,
    val devices: List<RecentDevice> = emptyList(),
    val sleep: List<SleepSession> = emptyList(),
    val series: List<RecentSeries> = emptyList(),
)

/** What two devices are compared on (GET /api/devices/compare). */
@Serializable
enum class CompareKey(val label: String, val unit: String) {
    ASLEEP("Time asleep", "min"),
    DEEP("Deep sleep", "min"),
    LIGHT("Light sleep", "min"),
    REM("REM sleep", "min"),
    AWAKE("Awake in the night", "min"),
    /** Minutes after 6 pm the evening before (see [ComparedValue]). */
    BEDTIME("Fell asleep", "clock"),
    WAKE("Woke up", "clock"),
    NIGHT_HEART_RATE("Heart rate overnight", "bpm"),
    LOWEST_HEART_RATE("Lowest heart rate overnight", "bpm"),
    HRV("HRV overnight", "ms"),
    SPO2("Blood oxygen overnight", "%"),
    RESPIRATORY_RATE("Breathing rate overnight", "/min"),
    SKIN_TEMPERATURE("Skin temperature overnight", "°C"),
    DAY_HEART_RATE("Heart rate in the day", "bpm"),
    STEPS("Steps", "steps"),
}

/**
 * Two devices' values for one thing on one night (or day). Readings are compared only over the
 * time both measured: five minutes at a time for heart rate, half an hour for the rest, and for
 * steps the hours both were counting, [pairs] being how many such spells there were. Sleep is each
 * device's own (pairs 0). For
 * [CompareKey.BEDTIME] and [CompareKey.WAKE], minutes after 6 pm the evening before.
 */
@Serializable
data class ComparedValue(val key: CompareKey, val a: Double? = null, val b: Double? = null, val pairs: Int = 0)

/** One night: each device's own sleep (null without one) and the values both had. [date] is the morning it ended. */
@Serializable
data class ComparedNight(
    val date: String,
    val a: SleepSession? = null,
    val b: SleepSession? = null,
    val values: List<ComparedValue> = emptyList(),
)

/** Over every night both measured: their averages and how far apart they were. */
@Serializable
data class ComparedMetric(
    val key: CompareKey,
    /** Nights (for steps, days) both had it. */
    val count: Int,
    val a: Double,
    val b: Double,
    /** [b] minus [a], on average: how much higher (or, below 0, lower) b reads. */
    val difference: Double,
    /** How far apart they were on average, either way. */
    val apart: Double,
    /** The smallest and largest difference on a night: how steady it is. */
    val lowest: Double,
    val highest: Double,
    /** The spells both measured in, over all of them: for steps, the hours both were counting. */
    val pairs: Int = 0,
)

/**
 * Two devices side by side (GET /api/devices/compare?a=&b=&days=14): each night, and over them
 * all how far apart they read. [a] is the one to compare against (a chest strap, say), [b] the one
 * being checked. Without a and b, the server picks: a strap first, then the devices with the most
 * readings. [devices] are every device with readings in the time, for choosing.
 */
@Serializable
data class DeviceComparison(
    val days: Int,
    val from: String,
    val to: String,
    val a: RecentDevice? = null,
    val b: RecentDevice? = null,
    val devices: List<RecentDevice> = emptyList(),
    val metrics: List<ComparedMetric> = emptyList(),
    /** Newest first; only nights either device recorded something in. */
    val nights: List<ComparedNight> = emptyList(),
) {
    companion object {
        /** Nights (or days) before the page says how two devices usually differ; fewer, and it says it's early. */
        const val ENOUGH = 3
    }
}
