package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.ContinenceSummary
import com.health.api.ContinenceWearing
import com.health.api.Count
import com.health.api.DaySummary
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.LayoutPrefs
import com.health.api.LeakSize
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.ProductStats
import com.health.api.ProgressMetric
import com.health.api.ProgressWeek
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.Units
import com.health.api.Verdict
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.LocalLayoutStore
import com.health.app.ui.components.MemoryLayoutStore
import com.health.app.ui.modules.continence.ChangeForm
import com.health.app.ui.modules.continence.ContinenceActions
import com.health.app.ui.modules.continence.ContinenceScreen
import com.health.app.ui.modules.continence.ContinenceState
import com.health.app.ui.modules.continence.DiarySettingsScreen
import com.health.app.ui.modules.continence.DiarySettingsState
import com.health.app.ui.modules.continence.FormContext
import com.health.app.ui.modules.continence.ProgressScreen
import com.health.app.ui.modules.continence.ProgressState
import com.health.app.ui.modules.continence.SuppliesActions
import com.health.app.ui.modules.continence.SuppliesScreen
import com.health.app.ui.modules.continence.SuppliesState
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.modules.continence.WearText
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What's being worn and a change as one step, drawn with realistic data to PNGs in
 * android/build/outputs/screenshots, light and dark: the diary's Wearing now card and list of
 * changes, the Change sheet, Today with what's on (and Up next's "Time to change"), how each
 * product goes on Supplies and How it's going, the change reminder's settings, "What are you
 * wearing?", nothing on, and someone viewing a share (no Change).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WearingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(17, 30)
    private val all = Module.entries.toSet()
    private val fields = Fields.DEFAULTS + Fields.CONTINENCE_PROTECTION + Fields.CONTINENCE_PAD_WEIGHT + Fields.CONTINENCE_CHANGES +
        Fields.CONTINENCE_SKIN + Fields.CONTINENCE_SUPPLIES + Fields.CONTINENCE_LEAK_SIZE + Fields.CONTINENCE_TRIGGERS

    private fun render(dark: Boolean = false, readOnly: Boolean = false, tab: String = "health", content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalLayoutStore provides remember { MemoryLayoutStore(LayoutPrefs()) }, LocalReadOnly provides readOnly) {
                    AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(all), content = content)
                }
            }
        }
    }

    /** A sheet as its bottom sheet draws it. */
    private fun sheet(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- The data: night briefs overnight, day pants since, and two weeks of changes ----

    private val night = Supply("Night briefs", SupplyKind.BRIEF, brand = "Tena", size = "M", absorbency = "Maxi night", onHand = 44, perPack = 14, id = "n")
    private val pants = Supply("Day pants", SupplyKind.PULL_UP, size = "M", absorbency = "Super", onHand = 46, perPack = 18, id = "d")
    private val bedPads = Supply("Bed pads", SupplyKind.BED_PAD, onHand = 9, lowAt = 10, id = "b")
    private val liners = Supply("Liners", SupplyKind.LINER, onHand = 40, id = "l")
    private val statuses = listOf(
        SupplyStatus(night, perDay = 1.0, daysLeft = 44),
        SupplyStatus(pants, perDay = 2.9, daysLeft = 15),
        SupplyStatus(bedPads, low = true),
        SupplyStatus(liners),
    )
    private val settings = ContinenceSettings(defaultSupplyId = "d", changeReminderHours = 5)
    private val wearing = ContinenceWearing("d", "Day pants", SupplyKind.PULL_UP, at(11, 45), 345, typicalMinutes = 330, changeDueAt = at(16, 45))
    private val products = listOf(
        ProductStats("d", "Day pants", 43, avgWornMinutes = 330, typicalFullness = Fullness.WET, leakedThroughPct = 0, nightShare = 0.0, leakedThrough = 0, kind = SupplyKind.PULL_UP),
        ProductStats("n", "Night briefs", 15, avgWornMinutes = 450, typicalFullness = Fullness.FULL, leakedThroughPct = 7, nightShare = 1.0, leakedThrough = 1, kind = SupplyKind.BRIEF),
    )

    private val entries = listOf(
        ContinenceEntry(at(23, 20, today.minusDays(1)), ContinenceKind.PAD_CHANGE, supplyId = "d", putOnSupplyId = "n", fullness = Fullness.DAMP, wornMinutes = 350, night = true, id = "y1"),
        ContinenceEntry(at(3, 10), ContinenceKind.URINATION, urgency = 2, night = true, id = "c1"),
        ContinenceEntry(at(6, 30), ContinenceKind.PAD_CHANGE, supplyId = "n", putOnSupplyId = "d", fullness = Fullness.FULL, skin = 1, wornMinutes = 430, id = "c2"),
        ContinenceEntry(at(7, 5), ContinenceKind.URINATION, volumeMl = 320, id = "c3"),
        ContinenceEntry(at(9, 40), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough", "lifting"), supplyId = "d", fullness = Fullness.DAMP, id = "c4"),
        ContinenceEntry(at(11, 45), ContinenceKind.PAD_CHANGE, supplyId = "d", putOnSupplyId = "d", fullness = Fullness.WET, wornMinutes = 315, id = "c5"),
        ContinenceEntry(at(13, 20), ContinenceKind.URINATION, id = "c6"),
    ).map { it.copy(date = java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toString()) }

    private val progress = ContinenceProgress(
        Verdict.STEADY, "About the same as the weeks before.", 4,
        listOf(ProgressMetric("leaks", "Leaks a day", 0.5, 0.4, verdict = Verdict.STEADY), ProgressMetric("products", "Products a day", 3.1, 3.0, verdict = Verdict.STEADY)),
        (0 until 8).map { i -> ProgressWeek(today.minusWeeks(7L - i).with(java.time.DayOfWeek.MONDAY).toString(), 7, voidsPerDay = 4.0, leaksPerDay = 0.5 - i * 0.02, productsPerDay = 3.0) },
    )

    private fun diary(wearing: ContinenceWearing? = this.wearing) = ContinenceState(
        today, entries,
        ContinenceSummary(
            days = (0 until 14).map { i -> ContinenceDay(today.minusDays(13L - i).toString(), voids = 4, nightVoids = 1, leaks = i % 3 / 2, products = 3, fluidMl = 1500) },
            averageVoids = 3.9, averageNightVoids = 1.0, averageLeaks = 0.4, triggers = listOf(Count("cough", 5), Count("lifting", 1)),
        ),
        loading = false, settings = settings, supplies = statuses, progress = progress, wearing = wearing, products = products,
    )

    // ---- The diary: Wearing now, the list of changes, how the products go ----

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun diaryLight() {
        render { ContinenceScreen(diary(), zone, ContinenceActions(), fields = fields, now = now) }
        assertTrue(compose.onAllNodesWithText("Night briefs → Day pants").fetchSemanticsNodes().isNotEmpty())
        save("wearing_diary_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun diaryDark() {
        render(dark = true) { ContinenceScreen(diary(), zone, ContinenceActions(), fields = fields, now = now) }
        save("wearing_diary_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun askWhatsOn() {
        render { ContinenceScreen(diary(ContinenceWearing()), zone, ContinenceActions(), fields = fields, now = now) }
        assertTrue(compose.onAllNodesWithText("What are you wearing?").fetchSemanticsNodes().isNotEmpty())
        save("wearing_ask_light")
    }

    @Test
    @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun nothingOnDark() {
        render(dark = true) { ContinenceScreen(diary(ContinenceWearing(since = at(16, 40), minutes = 50, nothingOn = true)), zone, ContinenceActions(), fields = fields, now = now) }
        save("wearing_nothing_on_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun viewingAShare() {
        render(readOnly = true) { ContinenceScreen(diary(), zone, ContinenceActions(), fields = fields, now = now) }
        assertTrue("no Change while viewing", compose.onAllNodesWithText("Change").fetchSemanticsNodes().isEmpty())
        save("wearing_share_view_light")
    }

    // ---- The Change sheet ----

    private val draft get() = WearText.changeDraft(wearing, settings, statuses.map { it.supply }, listOf("d", "n"), now).copy(fullness = Fullness.WET)

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun changeSheetLight() {
        sheet { ChangeForm(draft, FormContext(fields, settings, statuses.map { it.supply }), listOf("d", "n"), {}, null) }
        save("wearing_change_sheet_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun changeSheetDark() {
        sheet(dark = true) { ChangeForm(draft, FormContext(fields, settings, statuses.map { it.supply }), listOf("d", "n"), {}, null) }
        save("wearing_change_sheet_dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun changeSheetDefaultsOnly() {
        // Only the default fields: what came off, what goes on, and when.
        sheet { ChangeForm(draft.copy(fullness = null), FormContext(Fields.DEFAULTS, settings, statuses.map { it.supply }), listOf("d"), {}, null) }
        save("wearing_change_sheet_defaults")
    }

    // ---- Today ----

    private val day = DaySummary(
        date = today.toString(),
        modules = ModulesToday(
            continence = ContinenceDay(today.toString(), voids = 3, nightVoids = 1, leaks = 1, pads = 2, products = 2, wearing = wearing),
            lowSupplies = listOf(SupplyStatus(bedPads, low = true)),
        ),
    )

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun todayLight() {
        render(tab = "today") { TodayScreen(TodayState(today, today, day, loading = false), Units.METRIC, zone, TodayActions(), modules = Module.DEFAULT + Module.CONTINENCE, now = now) }
        assertTrue(compose.onAllNodesWithText("Time to change: Day pants,\u00A06\u00A0h").fetchSemanticsNodes().isNotEmpty())
        save("wearing_today_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun todayDark() {
        render(dark = true, tab = "today") { TodayScreen(TodayState(today, today, day, loading = false), Units.METRIC, zone, TodayActions(), modules = Module.DEFAULT + Module.CONTINENCE, now = now) }
        save("wearing_today_dark")
    }

    // ---- How each product goes ----

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun suppliesLight() {
        render { SuppliesScreen(SupplyArea.CONTINENCE, SuppliesState(statuses, products = products, loading = false), zone, {}, SuppliesActions()) }
        save("wearing_supplies_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun suppliesDark() {
        render(dark = true) { SuppliesScreen(SupplyArea.CONTINENCE, SuppliesState(statuses, products = products, loading = false), zone, {}, SuppliesActions()) }
        save("wearing_supplies_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun progressDark() {
        render(dark = true) { ProgressScreen(ProgressState(progress = progress, loading = false, products = products), {}) }
        save("wearing_progress_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun reminderSettings() {
        render { DiarySettingsScreen(DiarySettingsState(settings, statuses.map { it.supply }, loading = false), fields, {}, today = today) }
        save("wearing_settings_light")
    }
}
