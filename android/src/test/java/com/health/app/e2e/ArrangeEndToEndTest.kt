package com.health.app.e2e

import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Layouts
import com.health.api.Module
import com.health.api.Profile
import com.health.api.QuickAction
import com.health.api.ScreenLayout
import com.health.api.TodaySection
import com.health.app.devices.healthconnect.ExportKind
import com.health.app.devices.healthconnect.HealthConnectExportMapping
import com.health.app.net.ServerApi
import com.health.app.net.fields
import com.health.app.net.layout
import com.health.app.net.saveFieldChoices
import com.health.app.net.saveScreenLayout
import com.health.app.ui.components.mergeOrder
import com.health.app.ui.food.FoodSections
import com.health.app.ui.settings.WaterSetting
import com.health.app.ui.today.TodayLayout
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId

/**
 * Arranging screens and turning water off, against the real server in-process: what the app saves
 * comes back after signing in again (as on another phone), and water off takes water away from
 * Today, Food and the Health Connect export while keeping the person's other field choices.
 */
class ArrangeEndToEndTest {
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
    fun `a Today layout saved from the app comes back after reloading`() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        val modules = api.saveProfile(Profile(timezone = zone.id, modules = Module.DEFAULT + Module.MIND)).modules

        // Food moved to the top and insights hidden, saved the way the Arrange sheet saves it.
        val sections = TodayLayout.sections(modules)
        val moved = listOf(TodaySection.FOOD) + (sections - TodaySection.FOOD)
        api.saveScreenLayout(Layouts.TODAY, ScreenLayout(mergeOrder(emptyList(), moved.map { it.name }), setOf(TodaySection.INSIGHTS.name)))
        // Check in first among the quick actions, and water under More.
        val actions = TodayLayout.quickActions(modules)
        val quickOrder = listOf(QuickAction.CHECK_IN) + (actions - QuickAction.CHECK_IN)
        api.saveScreenLayout(Layouts.QUICK, ScreenLayout(mergeOrder(emptyList(), quickOrder.map { it.name }), setOf(QuickAction.WATER.name)))
        // The Food tab, the phone's own.
        api.saveScreenLayout(FoodSections.KEY, ScreenLayout(listOf(FoodSections.DRINKS, FoodSections.TOTALS)))

        // Signed in again, as after reinstalling or on another phone: it all comes back.
        token = api.login(base, "admin", "e2e-password", "Another phone").token
        val prefs = api.layout()
        val today = TodayLayout.visible(modules, water = true, prefs.screens[Layouts.TODAY])
        assertEquals(TodaySection.FOOD, today.first())
        assertFalse(TodaySection.INSIGHTS in today)
        assertEquals(sections.size - 1, today.size)
        val quick = TodayLayout.quickRow(TodayLayout.quickActions(modules), prefs.screens[Layouts.QUICK])
        assertEquals(QuickAction.CHECK_IN, quick.shown.first())
        assertTrue(QuickAction.WATER in quick.more)
        assertTrue(QuickAction.MOOD in quick.shown)
        assertEquals(listOf(FoodSections.DRINKS, FoodSections.TOTALS), prefs.screens[FoodSections.KEY]?.order)

        // Mind turned off, then Today arranged again on the phone: Mind keeps its place for when it's back.
        val withoutMind = TodayLayout.sections(Module.DEFAULT).map { it.name }
        val saved = prefs.screens[Layouts.TODAY]!!
        val again = mergeOrder(saved.order, withoutMind.reversed())
        api.saveScreenLayout(Layouts.TODAY, saved.copy(order = again))
        val back = api.layout().screens[Layouts.TODAY]!!
        assertEquals(saved.order.indexOf(TodaySection.MIND.name), back.order.indexOf(TodaySection.MIND.name))
        // Reversed: what was last by default (Also today) is first.
        assertEquals(TodaySection.ALSO, TodayLayout.visible(Module.DEFAULT, water = true, back).first())
        assertEquals(TodaySection.BODY, TodayLayout.visible(Module.DEFAULT, water = true, back)[1])

        // An empty layout puts the screen back as it was.
        api.saveScreenLayout(Layouts.TODAY, ScreenLayout())
        assertEquals(sections, TodayLayout.visible(modules, water = true, api.layout().screens[Layouts.TODAY]))
    }

    @Test
    fun `water off hides the water field, keeps the person's other choices, and takes water off Today`() = runBlocking {
        token = api.login(base, "admin", "e2e-password", "Test phone").token
        val modules = api.saveProfile(Profile(timezone = zone.id, modules = Module.DEFAULT + Module.CONTINENCE + Module.CYCLE)).modules
        // Some choices of their own first: one field shown, one hidden.
        api.saveFieldChoices(FieldChoices(show = setOf(Fields.CONTINENCE_ACTIVITY), hide = setOf(Fields.CYCLE_BBT)))
        val before = api.fields()
        assertTrue(WaterSetting.isOn(before))
        assertTrue(TodaySection.WATER in TodayLayout.sections(modules, WaterSetting.isOn(before)))

        // Settings › Features › Water, off.
        api.saveFieldChoices(WaterSetting.choices(api.fields(), on = false))
        val off = api.fields()
        assertFalse(Fields.NUTRITION_WATER in off.effective)
        assertFalse(WaterSetting.isOn(off))
        assertTrue(Fields.CONTINENCE_ACTIVITY in off.effective)
        assertFalse(Fields.CYCLE_BBT in off.effective)
        val water = WaterSetting.isOn(off)
        assertFalse(TodaySection.WATER in TodayLayout.sections(modules, water))
        assertFalse(QuickAction.WATER in TodayLayout.quickActions(modules, water))
        assertFalse(FoodSections.items(water).any { it.id == FoodSections.WATER })
        assertEquals(setOf(ExportKind.WEIGHT), HealthConnectExportMapping.writable(setOf(ExportKind.WEIGHT, ExportKind.HYDRATION), off.effective))

        // And on again: back as it was, other choices still kept.
        api.saveFieldChoices(WaterSetting.choices(api.fields(), on = true))
        val on = api.fields()
        assertTrue(WaterSetting.isOn(on))
        assertEquals(before.show, on.show)
        assertEquals(before.hide, on.hide)
        assertTrue(QuickAction.WATER in TodayLayout.quickActions(modules, WaterSetting.isOn(on)))
    }
}
