package com.health.app.e2e

import com.health.api.AiChatRequest
import com.health.api.AiMessage
import com.health.api.BladderTraining
import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.CycleDay
import com.health.api.CycleSettings
import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Flow
import com.health.api.Fullness
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.ProductUse
import com.health.api.Profile
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyKind
import com.health.api.SupplyReason
import com.health.api.Verdict
import com.health.app.net.ServerApi
import com.health.app.net.ServerException
import com.health.app.net.addContinenceCheckIn
import com.health.app.net.addSupply
import com.health.app.net.adjustSupply
import com.health.app.net.aiChat
import com.health.app.net.aiStatus
import com.health.app.net.conditionCatalog
import com.health.app.net.conditions
import com.health.app.net.continenceCheckIns
import com.health.app.net.continenceProgress
import com.health.app.net.continenceSettings
import com.health.app.net.cycleSettings
import com.health.app.net.fields
import com.health.app.net.saveConditions
import com.health.app.net.saveContinenceSettings
import com.health.app.net.saveCycleSettings
import com.health.app.net.saveFieldChoices
import com.health.app.net.supplies
import com.health.app.net.supplyLog
import com.health.app.net.supplyStatus
import com.health.app.ui.ai.AiText
import com.health.app.ui.conditions.ConditionsLogic
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * Conditions and fields, the deeper bladder and bowel diary, supplies, cycle settings and the AI
 * assistant's status, through the app's own calls against the real server, in-process.
 */
class DiaryEndToEndTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var base: String
    private var token: String? = null
    private val api by lazy { ServerApi(OkHttpClient(), { base }, { token }) }
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun start() {
        val config = ServerConfig(dataDir = Files.createTempDirectory("samh-diary-e2e").toFile(), adminPassword = "e2e-password")
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

    private suspend fun signIn() {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        api.saveProfile(Profile(timezone = zone.id))
    }

    @Test
    fun `picking a condition turns on its feature and brings its fields, which the person can still change`() = runBlocking {
        signIn()
        assertFalse(Module.CONTINENCE in api.profile().modules)
        val catalog = api.conditionCatalog()
        val oab = ConditionsLogic.search(catalog, "OAB").first()
        assertEquals("overactive-bladder", oab.id)

        val mine = ConditionsLogic.put(ConditionsLogic.toggle(MyConditions(), oab), MyCondition(oab.id, oab.name, since = "2024-05"))
        api.saveConditions(mine)
        assertEquals("2024-05", api.conditions().conditions.single().since)
        assertTrue(Module.CONTINENCE in api.profile().modules)

        val layout = api.fields()
        assertTrue(Fields.CONTINENCE_BLADDER_TRAINING in layout.effective)
        assertEquals("Because of Overactive bladder", ConditionsLogic.reason(layout, Fields.CONTINENCE_BLADDER_TRAINING))

        // Turning a condition's field off, then on again, as the Fields page does.
        val brought = ConditionsLogic.brought(api.conditions(), catalog)
        val off = ConditionsLogic.choose(FieldChoices(layout.show, layout.hide), Fields.CONTINENCE_BLADDER_TRAINING, false, brought)
        assertFalse(Fields.CONTINENCE_BLADDER_TRAINING in api.saveFieldChoices(off).effective)
        val on = ConditionsLogic.choose(off, Fields.CONTINENCE_BLADDER_TRAINING, true, brought)
        val again = api.saveFieldChoices(on)
        assertTrue(Fields.CONTINENCE_BLADDER_TRAINING in again.effective)
        assertEquals("Overactive bladder", again.because[Fields.CONTINENCE_BLADDER_TRAINING])
    }

    @Test
    fun `a change logged with a supply counts it down, restocks count it up, and the diary's detail comes back`() = runBlocking {
        signIn()
        api.saveProfile(api.profile().copy(modules = api.profile().modules + Module.CONTINENCE))
        val pads = api.addSupply(Supply("Night pads", SupplyKind.PAD, absorbency = "Maxi", dryWeightG = 20.0, onHand = 10, lowAt = 9))
        val now = System.currentTimeMillis()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val earlier = maxOf(start + 60_000, now - 600_000)

        val entry = api.addContinence(
            ContinenceEntry(earlier, ContinenceKind.PAD_CHANGE, supplyId = pads.id, count = 2, fullness = Fullness.SOAKED, padWeightG = 85.0, leakedThrough = true, skin = 1),
        )
        assertEquals(65.0, entry.lossG!!, 0.01)
        assertEquals(8, api.supplies().single().onHand)
        val status = api.supplyStatus().single()
        assertTrue(status.low)
        assertNotNull(status.perDay)

        // Bought a pack; the history has both.
        assertEquals(32, api.adjustSupply(pads.id!!, SupplyAdjustment(now, 24, SupplyReason.RESTOCKED, "Pharmacy")).onHand)
        val log = api.supplyLog(pads.id!!)
        assertEquals(setOf(SupplyReason.USED, SupplyReason.RESTOCKED), log.map { it.reason }.toSet())
        assertEquals(-2, log.single { it.reason == SupplyReason.USED }.delta)

        // Deleting the entry gives the two back.
        api.deleteContinence(entry.id!!)
        assertEquals(34, api.supplies().single().onHand)

        // A bowel accident, with its detail, read back from the diary.
        api.addContinence(ContinenceEntry(earlier, ContinenceKind.BOWEL_LEAK, contents = com.health.api.LeakContents.STOOL, warningSeconds = 30, location = "Out", utiSigns = listOf("Burning")))
        val back = api.continence(1).single()
        assertEquals("Out", back.location)
        assertEquals(listOf("burning"), back.utiSigns)
        assertEquals(30, back.warningSeconds)
        assertTrue(api.continenceSummary(7).patterns.any { it.tone == "warn" && it.body.contains("doctor or nurse") })
    }

    @Test
    fun `diary and cycle settings, check-ins and progress round trip`() = runBlocking {
        signIn()
        val settings = api.saveContinenceSettings(
            api.continenceSettings().copy(quickButtons = listOf(ContinenceKind.URINATION, ContinenceKind.BEDWETTING), triggers = listOf("Cold Hands"), bladderTraining = BladderTraining(enabled = true, intervalMinutes = 45)),
        )
        assertEquals(listOf("cold hands"), settings.triggers)
        assertNotNull(settings.bladderTraining.startedOn)
        assertEquals(listOf(ContinenceKind.URINATION, ContinenceKind.BEDWETTING), api.continenceSettings().quickButtons)

        val today = LocalDate.now(zone).toString()
        api.addContinenceCheckIn(ContinenceCheckIn(today, 6, compared = 1, impact = listOf("sleep")))
        assertEquals(6, api.continenceCheckIns().single().bother)
        val progress = api.continenceProgress(8)
        assertEquals(Verdict.NOT_ENOUGH, progress.verdict)
        assertEquals(8, progress.weeks.size)

        val cycle = api.saveCycleSettings(CycleSettings(symptoms = listOf("Jaw pain"), neutralWording = true, cycleLength = 30, dailyReminder = "20:30"))
        assertEquals(listOf("jaw pain"), cycle.symptoms)
        assertTrue(api.cycleSettings().neutralWording)

        // A period product from a supply comes off its count.
        val tampons = api.addSupply(Supply("Tampons", SupplyKind.TAMPON, onHand = 20))
        api.saveCycleDay(CycleDay(today, Flow.MEDIUM, products = listOf(ProductUse(SupplyKind.TAMPON, 3, Fullness.FULL, tampons.id)), painAreas = listOf("lower back"), symptomSeverity = mapOf("cramps" to 2)))
        assertEquals(17, api.supplies().single { it.id == tampons.id }.onHand)
        assertEquals(listOf("lower back"), api.cycle(1).days.single().painAreas)
    }

    @Test
    fun `without an API key the assistant says it isn't available, and refuses questions`() = runBlocking {
        signIn()
        val status = api.aiStatus()
        assertFalse(status.available)
        assertFalse(status.loggingAllowed)
        assertTrue(status.dataNotice.contains("Anthropic"))
        try {
            api.aiChat(AiChatRequest(listOf(AiMessage("user", "How did I sleep?"))))
            fail("The assistant answered without a key")
        } catch (e: ServerException) {
            assertEquals(409, e.code)
            assertEquals("The assistant isn't turned on on this server.", AiText.error(e))
        }
    }
}
