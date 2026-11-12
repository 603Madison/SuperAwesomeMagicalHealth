package com.fitness.app.devices.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.fitness.api.DeviceKind
import com.fitness.api.SleepStage
import com.fitness.api.WorkoutType

/** How Health Connect's apps, exercise types and sleep stages read in this app's terms. */
object HealthConnectMapping {
    private val NAMES = mapOf(
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "nodomain.freeyourgadget.gadgetbridge" to "Gadgetbridge",
        "nodomain.freeyourgadget.gadgetbridge.nightly" to "Gadgetbridge",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.ouraring.oura" to "Oura",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.withings.wiscale2" to "Withings",
        "com.strava" to "Strava",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.mi.health" to "Mi Fitness",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.polar.polarflow" to "Polar Flow",
        "com.suunto.android" to "Suunto",
        "com.qcwireless.qring" to "QRing",
        "com.whoop.android" to "WHOOP",
        "com.ultrahuman.android" to "Ultrahuman",
    )

    /** A name people recognise for the app that wrote some data. */
    fun friendlyName(packageName: String): String = NAMES[packageName] ?: when {
        isPebble(packageName) -> "Pebble"
        packageName.startsWith("nodomain.freeyourgadget.gadgetbridge") -> "Gadgetbridge"
        packageName.contains("garmin", ignoreCase = true) -> "Garmin"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun isPebble(packageName: String) =
        packageName.startsWith("coredevices.") || packageName.contains("pebble", ignoreCase = true)

    fun kind(packageName: String): DeviceKind = when {
        packageName.contains("garmin", ignoreCase = true) -> DeviceKind.GARMIN
        isPebble(packageName) -> DeviceKind.PEBBLE
        else -> DeviceKind.HEALTH_CONNECT
    }

    fun workoutType(exerciseType: Int): WorkoutType = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WorkoutType.WALK
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> WorkoutType.RUN
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> WorkoutType.CYCLE
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> WorkoutType.SWIM
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> WorkoutType.STRENGTH
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> WorkoutType.HIIT
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> WorkoutType.YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> WorkoutType.PILATES
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutType.HIKE
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> WorkoutType.ROW
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> WorkoutType.ELLIPTICAL
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> WorkoutType.DANCE
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> WorkoutType.CLIMB
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING, ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> WorkoutType.SKI
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL, ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS,
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL,
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF, ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL,
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL, ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY, ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN,
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN, ExerciseSessionRecord.EXERCISE_TYPE_RUGBY,
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET, ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL,
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC, ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> WorkoutType.SPORT
        else -> WorkoutType.OTHER
    }

    /** A Health Connect sleep stage; null for "out of bed" (not part of the night's stages). */
    fun sleepStage(stage: Int): SleepStage? = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStage.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
        SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_UNKNOWN -> SleepStage.ASLEEP
        else -> null
    }
}
