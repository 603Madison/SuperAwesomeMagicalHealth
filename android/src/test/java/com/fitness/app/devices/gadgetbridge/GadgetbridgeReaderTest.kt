package com.fitness.app.devices.gadgetbridge

import android.database.sqlite.SQLiteDatabase
import com.fitness.api.DeviceKind
import com.fitness.api.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** A small database shaped like Gadgetbridge's, read end to end. */
@RunWith(RobolectricTestRunner::class)
class GadgetbridgeReaderTest {
    private val minute = 60_000L
    private val t0 = 1_762_905_600_000L

    private fun fixture(): File {
        val file = File(Files.createTempDirectory("gb").toFile(), "Gadgetbridge.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE DEVICE (_id INTEGER PRIMARY KEY, NAME TEXT, MANUFACTURER TEXT, IDENTIFIER TEXT, TYPE INTEGER, MODEL TEXT)")
            db.execSQL("INSERT INTO DEVICE VALUES (1, 'Pebble Time 2', 'Core Devices', 'AA:BB:CC:00:11:22', 1, NULL)")
            db.execSQL("INSERT INTO DEVICE VALUES (2, 'R02_6C1A', 'Colmi', 'DD:EE:FF:33:44:55', 2, 'R02')")
            // Pebble: times in seconds, a row a minute.
            db.execSQL("CREATE TABLE PEBBLE_HEALTH_ACTIVITY_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, RAW_INTENSITY INTEGER, STEPS INTEGER, RAW_KIND INTEGER, HEART_RATE INTEGER, PRIMARY KEY (TIMESTAMP, DEVICE_ID))")
            for (m in 0 until 30) {
                db.execSQL("INSERT INTO PEBBLE_HEALTH_ACTIVITY_SAMPLE VALUES (?, 1, 1, 0, 20, 0, ?)", arrayOf((t0 + m * minute) / 1000, if (m % 2 == 0) 70 else 0))
            }
            db.execSQL("CREATE TABLE PEBBLE_HEALTH_ACTIVITY_OVERLAY (TIMESTAMP_FROM INTEGER, TIMESTAMP_TO INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, RAW_KIND INTEGER)")
            db.execSQL("INSERT INTO PEBBLE_HEALTH_ACTIVITY_OVERLAY VALUES (?, ?, 1, 1, 1)", arrayOf((t0 - 480 * minute) / 1000, t0 / 1000))
            // Colmi: times in milliseconds.
            db.execSQL("CREATE TABLE COLMI_HEART_RATE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, HEART_RATE INTEGER)")
            db.execSQL("INSERT INTO COLMI_HEART_RATE_SAMPLE VALUES (?, 2, 1, 58)", arrayOf(t0))
            db.execSQL("INSERT INTO COLMI_HEART_RATE_SAMPLE VALUES (?, 2, 1, 61)", arrayOf(t0 + 5 * minute))
            db.execSQL("CREATE TABLE COLMI_SPO2_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, SPO2 INTEGER)")
            db.execSQL("INSERT INTO COLMI_SPO2_SAMPLE VALUES (?, 2, 1, 97)", arrayOf(t0))
            db.execSQL("CREATE TABLE COLMI_SLEEP_STAGE_SAMPLE (TIMESTAMP INTEGER, DEVICE_ID INTEGER, USER_ID INTEGER, DURATION INTEGER, STAGE INTEGER)")
            db.execSQL("INSERT INTO COLMI_SLEEP_STAGE_SAMPLE VALUES (?, 2, 1, 90, 2)", arrayOf(t0 - 400 * minute))
            db.execSQL("INSERT INTO COLMI_SLEEP_STAGE_SAMPLE VALUES (?, 2, 1, 60, 3)", arrayOf(t0 - 310 * minute))
            // Something unrelated, ignored.
            db.execSQL("CREATE TABLE NOTIFICATION_FILTER (_id INTEGER PRIMARY KEY, APP TEXT)")
        }
        return file
    }

    @Test
    fun `every recognised table is read, one batch per device`() {
        val result = GadgetbridgeReader(fixture()).read(t0 - 24 * 60 * minute)
        assertEquals(2, result.batches.size)

        val pebble = result.batches.first { it.device.kind == DeviceKind.PEBBLE }
        assertEquals("AA:BB:CC:00:11:22", pebble.device.identifier)
        assertEquals(listOf(300.0, 300.0), pebble.samples.filter { it.metric == Metric.STEPS }.map { it.value })
        assertTrue(pebble.samples.filter { it.metric == Metric.HEART_RATE }.all { it.value == 70.0 })
        assertEquals(1, pebble.sleep.size)

        val ring = result.batches.first { it.device.kind == DeviceKind.COLMI_RING }
        assertEquals(listOf(58.0, 61.0), ring.samples.filter { it.metric == Metric.HEART_RATE }.map { it.value })
        assertEquals(listOf(97.0), ring.samples.filter { it.metric == Metric.SPO2 }.map { it.value })
        assertEquals(150 * minute, ring.sleep.single().let { it.end - it.start })
        assertEquals("R02", ring.device.model)

        assertEquals(t0 + 29 * minute, result.latest)
    }

    @Test
    fun `only what's newer than the cursor is read`() {
        val result = GadgetbridgeReader(fixture()).read(t0 + 20 * minute)
        val pebble = result.batches.single()
        assertEquals(listOf(180.0), pebble.samples.filter { it.metric == Metric.STEPS }.map { it.value })
        assertTrue(pebble.sleep.isEmpty())
    }
}
