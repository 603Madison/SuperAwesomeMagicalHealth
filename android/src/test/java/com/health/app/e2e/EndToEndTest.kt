package com.health.app.e2e

import com.health.api.CheckIn
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.FoodEntryInput
import com.health.api.IngestBatch
import com.health.api.MeasurementInput
import com.health.api.Meal
import com.health.api.Metric
import com.health.api.Nutrients
import com.health.api.Profile
import com.health.api.WaterEntry
import com.health.app.devices.colmi.ColmiMapping
import com.health.app.devices.colmi.ColmiProtocol
import com.health.app.devices.colmi.HeartRateLog
import com.health.app.devices.colmi.StepsParser
import com.health.app.net.ServerApi
import com.health.app.net.ServerException
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app against the real server, in-process: signing in, a ring's packets read by the app's
 * own parser and sent in, and the day the server makes of them; then food, water, weight and a
 * check-in, as the app sends them.
 */
class EndToEndTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var base: String
    private var token: String? = null
    private val api by lazy { ServerApi(OkHttpClient(), { base }, { token }) }
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun start() {
        val config = ServerConfig(dataDir = Files.createTempDirectory("samh-e2e").toFile(), adminPassword = "e2e-password")
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

    @Test
    fun `the app signs in, sends a ring's day and reads it back`() = runBlocking {
        try {
            api.login(base, "admin", "wrong-password", "Test phone")
            fail("A wrong password signs in")
        } catch (e: ServerException) {
            assertEquals(401, e.code)
        }
        val config = api.authConfig()
        assertTrue(config.localLogin)
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        assertEquals("admin", api.me().user.username)
        api.saveProfile(Profile(birthYear = 1992, heightCm = 170.0, timezone = zone.id))

        // Yesterday's steps as the ring sends them: a header (new calorie counting), then two quarter hours.
        val day = LocalDate.now(zone).minusDays(1)
        fun bcd(v: Int) = ColmiProtocol.toBcd(v)
        fun slot(quarter: Int, index: Int, calories: Int, steps: Int, metres: Int) = ColmiProtocol.packet(
            0x43,
            byteArrayOf(
                bcd(day.year % 100), bcd(day.monthValue), bcd(day.dayOfMonth), quarter.toByte(), index.toByte(), 2,
                (calories and 0xFF).toByte(), (calories shr 8).toByte(), (steps and 0xFF).toByte(), (steps shr 8).toByte(),
                (metres and 0xFF).toByte(), (metres shr 8).toByte(),
            ),
        )
        val parser = StepsParser()
        assertTrue(!parser.feed(ColmiProtocol.packet(0x43, byteArrayOf(0xF0.toByte(), 0x05, 0x01))))
        parser.feed(slot(40, 0, 4800, 1200, 900))
        assertTrue(parser.feed(slot(41, 1, 3200, 800, 600)))
        // The heart-rate log: a reading every 5 minutes, from midnight to 6 am.
        val log = HeartRateLog(5, List(72) { 58 + (it % 6) })
        val samples = ColmiMapping.activity(parser.slots, zone) + ColmiMapping.heartRates(day, log, zone, System.currentTimeMillis())
        val ring = DeviceInfo(DeviceKind.COLMI_RING, "E2:7C:11:A0:3B:5F", "Colmi R02", battery = 64)

        val sent = api.ingest(IngestBatch(ring, samples))
        assertEquals(samples.size, sent.samples)
        // Sending it again changes nothing.
        api.ingest(IngestBatch(ring, samples))

        val summary = api.day(day.toString())
        assertEquals(2000, summary.steps)
        assertEquals(1500.0, summary.distanceM, 0.1)
        assertEquals(80.0, summary.activeCalories, 0.1)
        assertEquals(2000, summary.hourlySteps[10]) // both quarter hours are 10:00–10:30
        assertEquals(58.0, summary.minHeartRate!!, 0.1)
        assertTrue(summary.heartRate.isNotEmpty())
        val device = api.devices().single()
        assertEquals("Colmi R02", device.name)
        assertEquals(64, device.battery)
        assertEquals(samples.size.toLong(), device.samples)

        // Logging, as the app's screens do.
        val today = LocalDate.now(zone).toString()
        api.addFood(FoodEntryInput(today, Meal.LUNCH, 1.0, name = "Burrito bowl", nutrients = Nutrients(640.0, 38.0, 70.0, 20.0)))
        api.addWater(WaterEntry(today, 500))
        val weight = api.addMeasurement(MeasurementInput(Metric.WEIGHT, 64.5))
        api.saveCheckIn(CheckIn(today, mood = 4, tags = listOf("travel")))
        val food = api.foodDay(today)
        assertEquals(640.0, food.totals.calories, 0.1)
        assertEquals(500, food.waterMl)
        assertEquals(4, api.checkIn(today).mood)
        val weights = api.measurements(Metric.WEIGHT, 30)
        assertEquals(64.5, weights.single().value, 0.01)
        api.deleteMeasurement(weight.id!!)
        assertTrue(api.measurements(Metric.WEIGHT, 30).isEmpty())
        val trends = api.trends(7)
        assertEquals(7, trends.days.size)
        assertEquals(2000, trends.days.first { it.date == day.toString() }.steps)
    }
}
