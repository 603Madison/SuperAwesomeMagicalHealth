package com.health.app.e2e

import com.health.api.BloodPressure
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.DrinkKind
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.Habit
import com.health.api.HabitTick
import com.health.api.IngestBatch
import com.health.api.Migraine
import com.health.api.Module
import com.health.api.Profile
import com.health.api.SymptomEntry
import com.health.app.net.ServerApi
import com.health.app.net.addBloodPressure
import com.health.app.net.addDrink
import com.health.app.net.addGlucose
import com.health.app.net.addHabit
import com.health.app.net.addMigraine
import com.health.app.net.addSymptom
import com.health.app.net.bloodPressure
import com.health.app.net.deleteDrink
import com.health.app.net.drinksDay
import com.health.app.net.glucose
import com.health.app.net.habitsToday
import com.health.app.net.migraines
import com.health.app.net.symptoms
import com.health.app.net.symptomsSummary
import com.health.app.net.tickHabit
import com.health.app.net.updateMigraine
import com.health.app.net.vitalsSummary
import com.health.app.ui.tracking.quickDrink
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's blood pressure and glucose, drinks, symptoms and habits against the real server,
 * in-process: logged and read through the app's own calls, readings from a monitor sent in as
 * the phone sends them, and the day the server makes of it all.
 */
class TrackingEndToEndTest {
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
    fun `the app logs blood pressure, glucose, drinks, symptoms and habits, and sees them on Today`() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        api.saveProfile(Profile(timezone = zone.id, modules = Module.DEFAULT + setOf(Module.VITALS, Module.HABITS, Module.SYMPTOMS)))
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        // Times earlier today, however early in the day the test runs.
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        fun earlier(ms: Long) = maxOf(start + 60_000, now - ms)

        // Blood pressure by hand, and from a monitor as the phone sends it (twice: nothing doubles).
        api.addBloodPressure(BloodPressure(earlier(3_600_000), 128, 82, pulse = 66, arm = "left"))
        val monitor = DeviceInfo(DeviceKind.BP_MONITOR, "C4:7C:8D:6A:01:02", "BP7000")
        val batch = IngestBatch(monitor, bloodPressure = listOf(BloodPressure(earlier(1_800_000), 136, 86, pulse = 70)))
        assertEquals(1, api.ingest(batch).readings)
        api.ingest(batch)
        val readings = api.bloodPressure(7)
        assertEquals(2, readings.size)
        assertEquals("BP7000", readings.single { it.systolic == 136 }.source)
        assertEquals(132.0, api.vitalsSummary(7).systolicAverage!!, 0.5)

        // Glucose, in mmol/L on the wire.
        api.addGlucose(GlucoseReading(earlier(1_200_000), 5.4, GlucoseContext.FASTING))
        assertEquals(GlucoseContext.FASTING, api.glucose(7).single().context)

        // A coffee: caffeine counted, and it counts towards water.
        val coffee = api.addDrink(quickDrink(DrinkKind.COFFEE, earlier(600_000)))
        val drinks = api.drinksDay(today.toString())
        assertEquals(96.0, drinks.caffeineMg, 0.5)
        assertEquals(240, drinks.fluidMl)

        // A symptom, and a migraine started then ended.
        api.addSymptom(SymptomEntry(earlier(900_000), "headache", 5, triggers = listOf("stress")))
        assertEquals(listOf("stress"), api.symptoms(7).single().triggers)
        val migraine = api.addMigraine(Migraine(earlier(2_400_000), severity = 7, aura = true))
        assertNull(api.migraines(7).single().end)
        api.updateMigraine(migraine.copy(end = earlier(300_000)))
        assertNotNull(api.migraines(7).single().end)
        assertTrue(api.symptomsSummary(30).counts.any { it.name == "headache" })

        // A habit, ticked.
        val habit = api.addHabit(Habit("Stretch", timesPerWeek = 5))
        api.tickHabit(habit.id!!, HabitTick(today.toString(), 1))
        val status = api.habitsToday().single()
        assertTrue(status.done)
        assertEquals(1, status.weekDone)

        // Today has it all; turning a feature off takes it away, and keeps what was logged.
        val day = api.day(today.toString())
        assertNotNull(day.modules.vitals?.bloodPressure)
        assertEquals(5.4, day.modules.vitals!!.glucose!!.mmolL, 0.01)
        assertTrue(day.modules.habits!!.single().done)
        assertTrue((day.modules.symptoms!!.worst ?: 0) >= 5)
        assertEquals(96.0, day.drinks!!.caffeineMg, 0.5)
        assertTrue(day.waterMl >= 240)

        api.deleteDrink(coffee.id!!)
        assertEquals(0.0, api.drinksDay(today.toString()).caffeineMg, 0.0)

        api.saveProfile(api.profile().copy(modules = api.profile().modules - Module.VITALS))
        assertNull(api.day(today.toString()).modules.vitals)
        assertEquals(2, api.bloodPressure(7).size)
    }
}
