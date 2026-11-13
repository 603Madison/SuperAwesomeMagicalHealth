package com.health.app.devices.healthconnect

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import com.health.api.BloodPressure
import com.health.api.Device
import com.health.api.DeviceKind
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.FoodEntry
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.Meal
import com.health.api.Metric
import com.health.api.Nutrients
import com.health.api.Sample
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.api.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HealthConnectExportTest {
    private val zone = ZoneId.of("America/New_York")
    private val at = 1_762_960_000_000L
    private val own = "app.superawesomemagical.health"

    @Test
    fun `weight goes to Health Connect in kilograms, with a stable client record id`() {
        val out = HealthConnectExportMapping.weight(Sample(Metric.WEIGHT, at, value = 66.4, id = 42), zone)
        assertEquals("weight:42", out.clientId)
        val record = out.record(7) as WeightRecord
        assertEquals(66.4, record.weight.inKilograms, 0.001)
        assertEquals(Instant.ofEpochMilli(at), record.time)
        assertEquals("samh:weight:42", record.metadata.clientRecordId)
        assertEquals(7, record.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
        // The same reading gives the same id and fingerprint, so writing again updates rather than copies.
        val again = HealthConnectExportMapping.weight(Sample(Metric.WEIGHT, at, value = 66.4, id = 42), zone)
        assertEquals(out.clientId to out.fingerprint, again.clientId to again.fingerprint)
    }

    @Test
    fun `blood pressure, glucose and workouts map to their records`() {
        val bp = HealthConnectExportMapping.bloodPressure(BloodPressure(at, 128, 82, arm = "left", id = "b1"), zone).record(1) as BloodPressureRecord
        assertEquals(128.0, bp.systolic.inMillimetersOfMercury, 0.01)
        assertEquals(82.0, bp.diastolic.inMillimetersOfMercury, 0.01)
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, bp.measurementLocation)

        val glucose = HealthConnectExportMapping.glucose(GlucoseReading(at, 5.6, GlucoseContext.FASTING, id = "g1"), zone).record(1) as BloodGlucoseRecord
        assertEquals(5.6, glucose.level.inMillimolesPerLiter, 0.001)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_FASTING, glucose.relationToMeal)

        val run = HealthConnectExportMapping.workout(Workout(WorkoutType.RUN, at, at + 1_800_000, name = "Morning run", id = "w1"), zone)!!.record(1) as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, run.exerciseType)
        assertEquals("Morning run", run.title)
        assertEquals("samh:workout:w1", run.metadata.clientRecordId)
        assertNull(HealthConnectExportMapping.workout(Workout(WorkoutType.RUN, at, at, id = "w2"), zone))
    }

    @Test
    fun `water and drinks without alcohol are hydration, and food is nutrition at its meal`() {
        val water = HealthConnectExportMapping.water(WaterEntry("2025-11-12", 250, id = "h1", at = at), zone)!!.record(1) as HydrationRecord
        assertEquals(250.0, water.volume.inMilliliters, 0.01)
        assertNull(HealthConnectExportMapping.water(WaterEntry("2025-11-12", 250), zone))
        assertNull(HealthConnectExportMapping.drink(Drink(at, DrinkKind.entries.first(), 330, alcoholG = 14.0, id = "d1"), zone))
        assertEquals("drink:d2", HealthConnectExportMapping.drink(Drink(at, DrinkKind.entries.first(), 330, id = "d2"), zone)!!.clientId)

        val lunch = FoodEntry("2025-11-12", Meal.LUNCH, "Soup", Nutrients(320.0, 12.0, 40.0, 9.0, fiber = 6.0, sodiumMg = 800.0), id = "f1")
        val out = HealthConnectExportMapping.food(lunch, zone)!!
        assertEquals(java.time.LocalDate.parse("2025-11-12").atTime(12, 30).atZone(zone).toInstant().toEpochMilli(), out.at)
        val record = out.record(1) as NutritionRecord
        assertEquals(320.0, record.energy!!.inKilocalories, 0.01)
        assertEquals(Mass.grams(12.0), record.protein)
        assertEquals(0.8, record.sodium!!.inGrams, 0.001)
        assertEquals(MealType.MEAL_TYPE_LUNCH, record.mealType)
        assertEquals("Soup", record.name)
    }

    @Test
    fun `readings that came from Health Connect aren't written back to it`() {
        val devices = listOf(
            Device("1", DeviceKind.GARMIN, "com.garmin.android.apps.connectmobile", "Garmin Connect", createdAt = 0),
            Device("2", DeviceKind.HEALTH_CONNECT, "com.sec.android.app.shealth", "Samsung Health", createdAt = 0),
            Device("3", DeviceKind.BP_MONITOR, "C4:7C:8D:6A:01:02", "BP7000", createdAt = 0),
            Device("4", DeviceKind.GARMIN, "3391827364", "Forerunner 265", createdAt = 0),
        )
        val sources = HealthConnectExportMapping.healthConnectSources(devices)
        assertEquals(setOf("Garmin Connect", "Samsung Health"), sources)
        assertFalse(HealthConnectExportMapping.mayWrite("Garmin Connect", sources))
        assertTrue(HealthConnectExportMapping.mayWrite("BP7000", sources))
        assertTrue(HealthConnectExportMapping.mayWrite("Forerunner 265", sources))
        assertTrue(HealthConnectExportMapping.mayWrite(null, sources))
    }

    @Test
    fun `only new or changed records are written, and ones gone from the server are taken away`() {
        val a = HealthConnectExportMapping.weight(Sample(Metric.WEIGHT, at, value = 66.4, id = 1), zone)
        val b = HealthConnectExportMapping.weight(Sample(Metric.WEIGHT, at + 86_400_000, value = 66.1, id = 2), zone)
        val written = mapOf(
            a.clientId to Written("WEIGHT", a.at, a.fingerprint),
            b.clientId to Written("WEIGHT", b.at, "old"),
            "weight:3" to Written("WEIGHT", at, "x"),
            "weight:0" to Written("WEIGHT", at - 90L * 86_400_000, "x"),
            "bp:9" to Written("BLOOD_PRESSURE", at, "x"),
        )
        val (changed, gone) = HealthConnectExportMapping.plan(listOf(a, b), written, setOf(ExportKind.WEIGHT), at - 30L * 86_400_000)
        assertEquals(listOf(b.clientId), changed.map { it.clientId })
        // Outside the window, or another kind: left alone.
        assertEquals(listOf("weight:3"), gone)
    }

    @Test
    fun `the conduit never reads back what this app wrote`() {
        // Health Connect sets a record's origin itself; its constructor for that is internal, so it's reached here by reflection.
        val constructor = Metadata::class.java.getConstructor(
            Int::class.javaPrimitiveType, String::class.java, DataOrigin::class.java, Instant::class.java, String::class.java,
            Long::class.javaPrimitiveType, androidx.health.connect.client.records.metadata.Device::class.java,
        )
        fun weight(origin: String, clientId: String? = null) = WeightRecord(
            Instant.ofEpochMilli(at), null, Mass.kilograms(70.0),
            constructor.newInstance(Metadata.RECORDING_METHOD_MANUAL_ENTRY, "id", DataOrigin(origin), Instant.ofEpochMilli(at), clientId, 1L, null),
        )
        assertFalse(HealthConnectMapping.fromOtherApp(weight(own), own))
        // Even from another install's package, a record with this app's client id is its own.
        assertFalse(HealthConnectMapping.fromOtherApp(weight("app.superawesomemagical.health.debug", "samh:weight:1"), own))
        assertTrue(HealthConnectMapping.fromOtherApp(weight("com.garmin.android.apps.connectmobile"), own))
        assertTrue(HealthConnectMapping.fromOtherApp(weight("com.sec.android.app.shealth", "their-own-id"), own))
    }
}
