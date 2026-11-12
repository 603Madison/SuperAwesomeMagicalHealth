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
