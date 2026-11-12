package com.health.app.e2e

import com.health.api.Appointment
import com.health.api.HormoneSettings
import com.health.api.KickSession
import com.health.api.LabResult
import com.health.api.LabTarget
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Role
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.ShareRequest
import com.health.app.net.ServerApi
import com.health.app.net.ServerException
import com.health.app.net.appointmentSuggestions
import com.health.app.net.exportZip
import com.health.app.net.hormones
import com.health.app.net.latestDigest
import com.health.app.net.lifeStageOverview
import com.health.app.net.reportPdf
import com.health.app.net.saveAppointment
import com.health.app.net.saveHormoneSettings
import com.health.app.net.saveKicks
import com.health.app.net.saveLifeStage
import com.health.app.net.share
import com.health.app.net.shares
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's care and platform features against the real server, in-process: hormone therapy's
 * dose history, a pregnancy with a movement count and the EPDS, an appointment with a suggested
 * question, a share viewed read-only (and a change refused), the digest, and a report and export.
 */
class CareEndToEndTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var services: Services
    private lateinit var base: String
    private var token: String? = null
    private var viewerToken: String? = null
    private var viewing: String? = null
    private val api by lazy { ServerApi(OkHttpClient(), { base }, { token }) }
    private val viewer by lazy { ServerApi(OkHttpClient(), { base }, { viewerToken }) }
    private val viewerLooking by lazy { ServerApi(OkHttpClient(), { base }, { viewerToken }, viewAs = { viewing }) }
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun start() {
        val config = ServerConfig(dataDir = Files.createTempDirectory("samh-care-e2e").toFile(), adminPassword = "e2e-password")
        val db = Database.inMemory()
        Schema.migrate(db)
        services = Services(config, db, SecretBox(ByteArray(32) { it.toByte() }), passwords = Passwords(iterations = 1_000))
        runBlocking {
            services.bootstrap()
            services.users.create("sam", "Sam", Role.USER, "sam-password-123")
        }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start()
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        base = "http://127.0.0.1:$port"
    }

    @After
    fun stop() = server.stop(100, 500)

    @Test
    fun `hormone therapy, a pregnancy, an appointment, a share, the digest and a report`() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        api.saveProfile(Profile(timezone = zone.id, modules = Module.entries.toSet()))
        val today = LocalDate.now(zone)

        // Hormone therapy: editing the dose is noted in its history.
        val estradiol = api.addMedication(Medication("Estradiol", "2 mg", schedule = MedSchedule(times = listOf("08:00")), hormone = true, startedOn = today.minusMonths(14).toString()))
        api.updateMedication(estradiol.copy(dose = "4 mg"))
        api.saveHormoneSettings(HormoneSettings(12, listOf(LabTarget("Estradiol", "pg/mL", 100.0, 200.0))))
        api.addLab(LabResult("Estradiol", 240.0, "pg/mL", System.currentTimeMillis() - 86_400_000))
        val hormones = api.hormones()
        val med = hormones.medications.single()
        assertEquals("4 mg", med.medication.dose)
        // Each change says the dose and its schedule: "2 mg · daily at 08:00" → "4 mg · daily at 08:00".
        assertTrue(med.changes.toString(), med.changes.any { it.from?.startsWith("2 mg") == true && it.to.startsWith("4 mg") })
        assertEquals(LabTarget("Estradiol", "pg/mL", 100.0, 200.0), hormones.labs.single().target)
        assertNotNull(hormones.nextLabsDue)
        assertEquals(listOf("Estradiol"), api.day(today.toString()).modules.hormones!!.outOfRange)

        // A pregnancy: the week, a movement count, and the EPDS offered and scored.
        api.saveLifeStage(LifeStageInfo(LifeStage.PREGNANT, dueDate = today.plusDays(100).toString()))
        val now = System.currentTimeMillis()
        api.saveKicks(KickSession(now - 20 * 60_000, now, 10))
        val stage = api.lifeStageOverview(30)
        assertEquals(LifeStage.PREGNANT, stage.info.stage)
        assertTrue(stage.pregnancy!!.week in 25..27)
        assertEquals(10, stage.kicks.single().kicks)
        val epds = api.screeners().single { it.id == Screener.EPDS }
        assertEquals(10, epds.itemOptions!!.size)
        // The app sends the chosen answer's position; the server scores reversed questions itself.
        val scored = api.submitScreener(ScreenerResult(Screener.EPDS, now, listOf(0, 0, 3, 0, 3, 3, 3, 3, 3, 3)))
        assertEquals(0, scored.score)

        // An appointment: questions suggested from what's been logged.
        val appointment = api.saveAppointment(Appointment(now + 3 * 86_400_000, "Check-up", with = "Dr Patel"))
        val suggestions = api.appointmentSuggestions(appointment.id!!)
        assertTrue(suggestions.joinToString { it.text }, suggestions.any { it.module == Module.HORMONES && "Estradiol" in it.text })
        assertEquals("Check-up", api.day(today.toString()).modules.appointments!!.single().title)

        // A share: Sam sees the medications, read-only.
        val me = api.me().user
        api.share(ShareRequest("sam", setOf(Module.ACTIVITY, Module.MEDICATIONS)))
        viewerToken = viewer.login(base, "sam", "sam-password-123", "Sam's phone").token
        val received = viewer.shares().received.single()
        assertEquals(me.id, received.ownerId)
        viewing = received.ownerId
        assertEquals("Estradiol", viewerLooking.medications().single().name)
        assertEquals(setOf(Module.ACTIVITY, Module.MEDICATIONS), viewerLooking.profile().modules)
        // Changes never leave the phone while viewing…
        try {
            viewerLooking.addMedication(Medication("Something"))
            fail("A change went out while viewing")
        } catch (e: ServerException) {
            assertEquals(ServerApi.VIEWING_MESSAGE, e.message)
        }
        // …and what isn't shared is refused by the server.
        try {
            viewerLooking.moods(7)
            fail("Saw a feature that isn't shared")
        } catch (e: ServerException) {
            assertEquals(403, e.code)
        }
        // Sam's own data is still Sam's.
        assertTrue(viewer.medications().isEmpty())
        assertEquals(1, api.medications().size)

        // The weekly digest, a report for the doctor, and everything exported.
        assertTrue(api.latestDigest().weekStart.isNotBlank())
        val pdf = api.reportPdf(today.minusDays(30), today, null)
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))
        val zip = api.exportZip(today.minusDays(30), today)
        assertEquals("PK", String(zip.copyOfRange(0, 2)))
    }
}
