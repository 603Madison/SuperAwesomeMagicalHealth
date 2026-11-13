package com.health.app.ui.modules.body

import com.health.api.Metric
import com.health.api.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MeasurementFormatTest {
    init {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `measurements are sent in cm, one per value given, at one moment`() {
        val inputs = MeasurementFormat.inputs(mapOf(Metric.WAIST to "32", Metric.HIPS to "", Metric.NECK to "nope", Metric.CALF to "14,5"), Units.IMPERIAL, 1_000)
        assertEquals(listOf(Metric.WAIST, Metric.CALF), inputs.map { it.metric })
        assertEquals(81.3, inputs[0].value, 0.001)
        assertEquals(36.8, inputs[1].value, 0.001)
        assertEquals(setOf(1_000L), inputs.map { it.at }.toSet())
        assertEquals(82.5, MeasurementFormat.inputs(mapOf(Metric.WAIST to "82.5"), Units.METRIC, 0).single().value, 0.001)
    }

    @Test
    fun `lengths and changes read in the person's units`() {
        assertEquals("82.5 cm", MeasurementFormat.length(82.5, Units.METRIC))
        assertEquals("32 in", MeasurementFormat.length(81.28, Units.IMPERIAL))
        assertEquals("down 2 cm", MeasurementFormat.change(84.0, 82.0, Units.METRIC))
        assertNull(MeasurementFormat.change(84.0, 83.8, Units.METRIC))
    }
}
