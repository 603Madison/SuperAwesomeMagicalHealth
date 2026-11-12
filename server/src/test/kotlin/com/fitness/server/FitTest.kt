package com.fitness.server

import com.fitness.api.WorkoutType
import com.fitness.server.health.FitImporter
import com.garmin.fit.DateTime
import com.garmin.fit.Fit
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FitTest {
    /** A 30-minute run as a Garmin watch would write it. */
    private fun run(startMs: Long): ByteArray {
        val file = Files.createTempFile("run", ".fit").toFile()
        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        val start = DateTime((startMs / 1000) - DateTime.OFFSET / 1000)
        encoder.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY
            manufacturer = Manufacturer.GARMIN
            garminProduct = GarminProduct.FENIX7
            serialNumber = 3_456_789_012L
            timeCreated = start
        })
        for (second in 0 until 1800 step 5) {
            encoder.write(RecordMesg().apply {
                timestamp = DateTime(start.timestamp + second)
                heartRate = (140 + second / 60).toShort()
            })
        }
        encoder.write(SessionMesg().apply {
            timestamp = DateTime(start.timestamp + 1800)
            startTime = start
            sport = Sport.RUNNING
            totalElapsedTime = 1800f
            totalTimerTime = 1800f
            totalDistance = 5000f
            totalCalories = 350
            avgHeartRate = 155.toShort()
            maxHeartRate = 172.toShort()
        })
        encoder.close()
        return file.readBytes().also { file.delete() }
    }

    private val start = 1_767_258_000_000L // 2026-01-01 09:00 UTC

    @Test
    fun `a Garmin run becomes a workout with its heart rate`() {
        val batch = FitImporter.parse(run(start))!!
        assertEquals("Garmin Fenix 7", batch.device.name)
        val workout = batch.workouts.single()
        assertEquals(WorkoutType.RUN, workout.type)
        assertEquals(start, workout.start)
        assertEquals(start + 1_800_000, workout.end)
        assertEquals(5000.0, workout.distanceM!!, 0.1)
        assertEquals(350.0, workout.activeCalories!!, 0.1)
        // A reading every 5 seconds, thinned to one every 30.
        assertEquals(60, batch.samples.size)
    }

    @Test
    fun `FIT files upload through the API`() = serverTest { services ->
        val browser = adminBrowser()
        val response = browser.submitFormWithBinaryData(
            "/api/import/fit",
            formData {
                append("file", run(start), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"run.fit\"")
                })
                append("file", "not a fit file".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"notes.txt\"")
                })
            },
        ) { csrf() }
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.obj()
        assertEquals("1", result.str("workouts"))
        assertTrue(result.str("message"), result.str("message").contains("notes.txt isn't a FIT file"))
        val user = services.users.byUsername("admin")!!
        runBlocking { assertEquals("Garmin Fenix 7", services.devices.list(user.id).single().name) }
    }
}
