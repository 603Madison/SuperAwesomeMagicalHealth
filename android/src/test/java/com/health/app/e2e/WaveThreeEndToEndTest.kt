package com.health.app.e2e

import com.health.api.ChecklistItem
import com.health.api.MeasurementInput
import com.health.api.Metric
import com.health.api.Milestone
import com.health.api.MilestoneStatus
import com.health.api.Module
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.api.Photo
import com.health.api.Pose
import com.health.api.Profile
import com.health.api.Sense
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.app.net.ServerApi
import com.health.app.net.fitExport
import com.health.app.net.milestoneTemplates
import com.health.app.net.neuroSummary
import com.health.app.net.overloads
import com.health.app.net.photoAlbums
import com.health.app.net.photoImage
import com.health.app.net.photoThumb
import com.health.app.net.photos
import com.health.app.net.saveMilestone
import com.health.app.net.saveOverload
import com.health.app.net.transitionOverview
import com.health.app.net.uploadPhoto
import com.health.server.ServerConfig
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.db.Schema
import com.health.server.module
import com.health.server.util.Passwords
import com.health.server.util.SecretBox
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * Autism & ADHD, transition, progress photos and the FIT export against the real server,
 * in-process, through the app's own calls.
 */
class WaveThreeEndToEndTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var base: String
    private var token: String? = null
    private val api by lazy { ServerApi(OkHttpClient(), { base }, { token }) }
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun start() {
        val config = ServerConfig(dataDir = Files.createTempDirectory("samh-e2e-wave3").toFile(), adminPassword = "e2e-password")
        val db = Database.inMemory()
        Schema.migrate(db)
        val services = Services(config, db, SecretBox(ByteArray(32) { it.toByte() }), passwords = Passwords(iterations = 1_000))
        runBlocking { services.bootstrap() }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        base = "http://127.0.0.1:$port"
    }

    @After
    fun stop() = server.stop(100, 500)

    private fun signIn() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        api.saveProfile(Profile(timezone = zone.id, modules = Module.DEFAULT + setOf(Module.NEURO, Module.TRANSITION, Module.PHOTOS)))
    }

    private fun earlier(ms: Long): Long {
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return maxOf(start + 60_000, System.currentTimeMillis() - ms)
    }

    @Test
    fun `a time things got too much is logged, then summarised`() = runBlocking {
        signIn()
        api.saveOverload(
            OverloadEvent(
                start = earlier(3_600_000), end = earlier(1_800_000), kind = OverloadKind.SHUTDOWN, intensity = 4,
                senses = listOf(Sense.SOUND), triggers = listOf("noise", "too many demands"), whatHelped = listOf("headphones", "dark room"), recoveryMinutes = 60,
            ),
        )
        val logged = api.overloads(7).single()
        assertEquals(OverloadKind.SHUTDOWN, logged.kind)
        assertEquals(listOf(Sense.SOUND), logged.senses)
        assertNotNull(logged.id)
        val summary = api.neuroSummary(30)
        assertEquals(1, summary.overloads)
        assertEquals("Shutdown", summary.byKind.single().name)
        assertTrue(summary.triggers.any { it.name == "noise" })
        assertTrue(summary.whatHelped.any { it.name == "headphones" })
    }

    @Test
    fun `a milestone starts from a template and counts its steps`() = runBlocking {
        signIn()
        val template = api.milestoneTemplates().first { it.title == "Update my passport" }
        val saved = api.saveMilestone(
            Milestone(template.title, template.area, MilestoneStatus.IN_PROGRESS, checklist = template.checklist.map { ChecklistItem(it) }),
        )
        api.saveMilestone(saved.copy(checklist = saved.checklist.mapIndexed { i, c -> if (i == 0) c.copy(done = true) else c }))
        val overview = api.transitionOverview()
        val milestone = overview.milestones.single()
        assertEquals("Update my passport", milestone.title)
        assertEquals(1, milestone.checklist.count { it.done })
        assertTrue(overview.highlights.toString(), overview.highlights.any { it.startsWith("Update my passport: 1 of ${template.checklist.size} steps done") })
    }

    @Test
    fun `a photo goes up without its location, and comes back with a thumbnail`() = runBlocking {
        signIn()
        val jpeg = withGps(JPEG)
        assertTrue(jpeg.containsBytes("Exif".toByteArray()))
        val saved = api.uploadPhoto(jpeg, "image/jpeg", Photo(album = "Transition", pose = Pose.SIDE, at = earlier(60_000), note = "Kitchen light"))
        assertNotNull(saved.id)
        assertEquals(64, saved.width)

        val listed = api.photos(album = "Transition").single()
        assertEquals("Kitchen light", listed.note)
        assertEquals(Pose.SIDE, listed.pose)
        assertEquals(1, api.photoAlbums().single { it.name == "Transition" }.count)

        val image = api.photoImage(saved.id!!)
        assertFalse("The location and camera details are gone", image.containsBytes("Exif".toByteArray()))
        assertEquals(64 to 48, size(image))
        val thumb = api.photoThumb(saved.id!!)
        assertTrue(thumb.size > 100)
        assertTrue(size(thumb).let { maxOf(it.first, it.second) <= 400 })
    }

    @Test
    fun `the FIT export downloads as a zip of FIT files, and measurements show in transition`() = runBlocking {
        signIn()
        val start = earlier(7_200_000)
        api.addWorkout(Workout(WorkoutType.RUN, start, start + 1_800_000, name = "Run", distanceM = 5000.0))
        api.addMeasurement(MeasurementInput(Metric.WEIGHT, 66.4))
        api.addMeasurement(MeasurementInput(Metric.WAIST, 81.0))
        val today = LocalDate.now(zone)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(api.fitExport(today.minusDays(30), today))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                names += entry.name
                // A FIT file says ".FIT" at bytes 8–11.
                if (entry.name.endsWith(".fit")) assertEquals(".FIT", String(bytes.copyOfRange(8, 12)))
            }
        }
        // The run, and the weight.
        assertTrue(names.toString(), names.count { it.endsWith(".fit") } >= 2 && "weight.fit" in names)
        assertEquals(81.0, api.transitionOverview().measurements.last().values.getValue(Metric.WAIST), 0.01)
    }

    /** A 64×48 JPEG (a white circle on violet), as a camera would give, before any EXIF. */
    private val JPEG: ByteArray = java.util.Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PT" +
        "gyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAw" +
        "AEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhBy" +
        "JxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT" +
        "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAA" +
        "AAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRom" +
        "JygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExc" +
        "bHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDnKKKK+8OgKKltra4vJ1gtYJZ5nztjiQsxwMnAHPQV2dt8KvEM" +
        "8CySPY27nOYpZSWXnvtUj34NZVcRSpfxJJCbSOHorupfhP4gjid1n0+RlUkIkrZY+gyoGT7kCuP1DTb3SrtrW/tpbeYfwyLjIyRkHoRkHkcGlS" +
        "xNKq7QkmCaZVooorYYUUUUAe6eAfDVlo+hW1+g8y8vYElkmYcqrAMEX0A4z6kZ9AOurnvBmu22ueHbUx3DS3NvEkVyJDlw4XBY+oOCQe/1BA6G" +
        "visU5utL2m9zB76hWN4l8NWXibTTa3Q2SpkwTqMtE39Qe47+xAI2aoazrNloOmyX9/LsiTgAcs7dlUdyf/rnABNRSc1NOnv0BeR82UUUV9wbhR" +
        "RRQBd0vVr7Rb0XmnXLQThSu4AEEHqCDwR9e4B7V39t8YLhYFF1o0UswzueK4KKeeMKVYjj3NeZ0Vz1sJRru9SNxNJ7np0vxhkMTiLRFWQqdjPd" +
        "bgD2JG0ZHtkfWuF1zxDqXiK7E+oz79mRHGo2pGCc4A/Lk5JwMk4rLopUcHQovmpxs/67gopBRRRXSM//2Q==",
    )

    /** A JPEG's width and height, from its start-of-frame marker. */
    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        var i = 2
        while (i + 9 < jpeg.size) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xC2) {
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + length
        }
        error("Not a JPEG")
    }

    /** [jpeg] with an EXIF block giving a GPS latitude (51° 30′ N), as a phone's camera writes it. */
    private fun withGps(jpeg: ByteArray): ByteArray {
        val tiff = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('I'.code.toByte()); put('I'.code.toByte()); putShort(42); putInt(8)
            // IFD0: one entry, the GPS IFD's offset.
            putShort(1); putShort(0x8825.toShort()); putShort(4); putInt(1); putInt(26); putInt(0)
            // The GPS IFD: latitude ref and latitude.
            putShort(2)
            putShort(1); putShort(2); putInt(2); put('N'.code.toByte()); put(0); put(0); put(0)
            putShort(2); putShort(5); putInt(3); putInt(56)
            putInt(0)
            putInt(51); putInt(1); putInt(30); putInt(1); putInt(0); putInt(1)
        }.array()
        val segment = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            val length = 2 + 6 + tiff.size
            write(length shr 8); write(length and 0xFF)
            write("Exif".toByteArray()); write(0); write(0)
            write(tiff)
        }.toByteArray()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
}
