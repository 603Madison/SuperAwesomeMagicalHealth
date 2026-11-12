package com.health.app.devices.vitals

import com.health.api.GlucoseContext
import com.health.app.devices.healthconnect.HealthConnectMapping
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Readings as monitors send them, laid out as the Bluetooth SIG's profiles describe. */
class VitalsProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `SFLOATs read with their exponent and sign, and special values read as nothing`() {
        assertEquals(120.0, VitalsProtocol.sfloat(0x78, 0x00)!!, 1e-9)
        assertEquals(5.43, VitalsProtocol.sfloat(0x1F, 0xE2)!!, 1e-9) // 543 × 10^-2
        assertEquals(-5.0, VitalsProtocol.sfloat(0xFB, 0x0F)!!, 1e-9)
        assertEquals(16.0, VitalsProtocol.sfloat(0xA0, 0xF0)!!, 1e-9) // 160 × 10^-1
        assertNull(VitalsProtocol.sfloat(0xFF, 0x07)) // NaN
        assertNull(VitalsProtocol.sfloat(0x00, 0x08)) // not at this resolution
        assertNull(VitalsProtocol.sfloat(0xFE, 0x07)) // +infinity
        assertNull(VitalsProtocol.sfloat(0x02, 0x08)) // -infinity
    }

    @Test
    fun `a blood pressure reading with every optional field`() {
        // mmHg; time stamp, pulse, user and status present.
        val reading = VitalsProtocol.bloodPressure(
            bytes(0x1E, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0xEA, 0x07, 0x0B, 0x0C, 0x08, 0x1E, 0x0F, 0x40, 0x00, 0x01, 0x00, 0x00),
        )!!
        assertEquals(120, reading.systolic)
        assertEquals(80, reading.diastolic)
        assertEquals(64, reading.pulse)
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 30, 15), reading.time)
        assertEquals(1, reading.user)
    }

    @Test
    fun `kilopascals are turned into mmHg, and missing fields are left out`() {
        val reading = VitalsProtocol.bloodPressure(bytes(0x01, 0xA0, 0xF0, 0x6B, 0xF0, 0x7C, 0xF0))!!
        assertEquals(120, reading.systolic) // 16.0 kPa
        assertEquals(80, reading.diastolic) // 10.7 kPa
        assertNull(reading.pulse)
        assertNull(reading.time)
        assertNull(reading.user)
    }

    @Test
    fun `nonsense and unknown values aren't readings`() {
        assertNull(VitalsProtocol.bloodPressure(bytes(0x00, 0x78, 0x00)))
        assertNull(VitalsProtocol.bloodPressure(bytes(0x00, 0xFF, 0x07, 0x50, 0x00, 0x5D, 0x00)))
        assertNull(VitalsProtocol.bloodPressure(bytes(0x00, 0x05, 0x00, 0x03, 0x00, 0x04, 0x00)))
        // A time stamp of year 0 means the monitor doesn't know.
        val unknown = VitalsProtocol.bloodPressure(bytes(0x02, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))!!
        assertNull(unknown.time)
    }

    @Test
    fun `a glucose record in kg per litre, with a time offset and context to follow`() {
        val record = VitalsProtocol.glucose(
            bytes(0x13, 0x07, 0x00, 0xEA, 0x07, 0x0B, 0x0C, 0x07, 0x05, 0x00, 0x0A, 0x00, 0x62, 0xB0, 0x11),
        )!!
        assertEquals(7, record.sequence)
        assertEquals(LocalDateTime.of(2026, 11, 12, 7, 15, 0), record.time)
        assertEquals(98 / 18.0182, record.mmolL!!, 0.001) // 98 mg/dL
        assertTrue(record.hasContext)
    }

    @Test
    fun `a glucose record in mol per litre, and one without a value`() {
        val record = VitalsProtocol.glucose(bytes(0x06, 0x01, 0x00, 0xEA, 0x07, 0x0B, 0x0C, 0x07, 0x05, 0x00, 0x38, 0xC0, 0x11))!!
        assertEquals(5.6, record.mmolL!!, 1e-9)
        assertFalse(record.hasContext)
        val empty = VitalsProtocol.glucose(bytes(0x00, 0x02, 0x00, 0xEA, 0x07, 0x0B, 0x0C, 0x07, 0x05, 0x00))!!
        assertNull(empty.mmolL)
    }

    @Test
    fun `a glucose context says when it was taken, past the fields before it`() {
        assertEquals(7 to GlucoseContext.FASTING, VitalsProtocol.glucoseContext(bytes(0x02, 0x07, 0x00, 0x03)))
        // With a carbohydrate (id and an SFLOAT) before the meal.
        assertEquals(7 to GlucoseContext.BEFORE_MEAL, VitalsProtocol.glucoseContext(bytes(0x03, 0x07, 0x00, 0x01, 0x32, 0xD0, 0x01)))
        assertEquals(9 to null, VitalsProtocol.glucoseContext(bytes(0x00, 0x09, 0x00)))
    }

    @Test
    fun `the record access control point asks for everything once, then only what's new`() {
        assertArrayEquals(bytes(0x01, 0x01), VitalsProtocol.reportRecords(null))
        assertArrayEquals(bytes(0x01, 0x03, 0x01, 0x2A, 0x00), VitalsProtocol.reportRecords(41))
        assertEquals(true, VitalsProtocol.racpDone(bytes(0x06, 0x00, 0x01, 0x01)))
        assertEquals(true, VitalsProtocol.racpDone(bytes(0x06, 0x00, 0x01, 0x06))) // no records
        assertEquals(false, VitalsProtocol.racpDone(bytes(0x06, 0x00, 0x01, 0x02)))
        assertNull(VitalsProtocol.racpDone(bytes(0x05, 0x00, 0x03, 0x00)))
    }

    @Test
    fun `a monitor's clock in the future doesn't put a reading there`() {
        val zone = ZoneId.of("Europe/London")
        val now = LocalDateTime.of(2026, 11, 12, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(now - 3_600_000, VitalsProtocol.epoch(LocalDateTime.of(2026, 11, 12, 8, 0), zone, now))
        assertEquals(now, VitalsProtocol.epoch(LocalDateTime.of(2027, 1, 1, 8, 0), zone, now))
        assertEquals(now, VitalsProtocol.epoch(null, zone, now))
    }

    @Test
    fun `Health Connect's arms and meal times read as ours`() {
        assertEquals("left", HealthConnectMapping.arm(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM))
        assertEquals("right", HealthConnectMapping.arm(BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST))
        assertNull(HealthConnectMapping.arm(BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN))
        assertEquals(GlucoseContext.FASTING, HealthConnectMapping.glucoseContext(BloodGlucoseRecord.RELATION_TO_MEAL_FASTING))
        assertEquals(GlucoseContext.AFTER_MEAL, HealthConnectMapping.glucoseContext(BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL))
        assertEquals(GlucoseContext.OTHER, HealthConnectMapping.glucoseContext(BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN))
    }
}
