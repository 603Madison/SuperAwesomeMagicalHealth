package com.health.app.devices.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.health.api.DeviceKind
import com.health.api.SleepStage
import com.health.api.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectMappingTest {
    @Test
    fun `apps get names people know, and Garmin and Pebble their own kinds`() {
        assertEquals("Garmin Connect", HealthConnectMapping.friendlyName("com.garmin.android.apps.connectmobile"))
        assertEquals(DeviceKind.GARMIN, HealthConnectMapping.kind("com.garmin.android.apps.connectmobile"))
        assertEquals("Gadgetbridge", HealthConnectMapping.friendlyName("nodomain.freeyourgadget.gadgetbridge"))
        assertEquals("Pebble", HealthConnectMapping.friendlyName("coredevices.coreapp"))
        assertEquals(DeviceKind.PEBBLE, HealthConnectMapping.kind("coredevices.coreapp"))
        assertEquals(DeviceKind.HEALTH_CONNECT, HealthConnectMapping.kind("com.fitbit.FitbitMobile"))
        assertEquals("Fitbit", HealthConnectMapping.friendlyName("com.fitbit.FitbitMobile"))
        assertEquals("Mystery", HealthConnectMapping.friendlyName("org.example.mystery"))
    }

    @Test
    fun `exercise types become workouts`() {
        assertEquals(WorkoutType.RUN, HealthConnectMapping.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        assertEquals(WorkoutType.CYCLE, HealthConnectMapping.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals(WorkoutType.STRENGTH, HealthConnectMapping.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
        assertEquals(WorkoutType.SPORT, HealthConnectMapping.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_TENNIS))
        assertEquals(WorkoutType.OTHER, HealthConnectMapping.workoutType(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
    }

    @Test
    fun `sleep stages carry over, out of bed doesn't`() {
        assertEquals(SleepStage.DEEP, HealthConnectMapping.sleepStage(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals(SleepStage.AWAKE, HealthConnectMapping.sleepStage(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        assertEquals(SleepStage.ASLEEP, HealthConnectMapping.sleepStage(SleepSessionRecord.STAGE_TYPE_SLEEPING))
        assertNull(HealthConnectMapping.sleepStage(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
    }
}
