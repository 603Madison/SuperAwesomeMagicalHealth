package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSummary
import com.health.api.Count
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.Goals
import com.health.api.Insight
import com.health.api.JournalEntry
import com.health.api.JournalKind
import com.health.api.LayoutPrefs
import com.health.api.LeakSize
import com.health.api.MindDay
import com.health.api.MindSummary
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.ScreenLayout
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.app.HealthApp
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.LocalArrangements
import com.health.app.ui.modules.continence.ContinenceActions
import com.health.app.ui.modules.continence.ContinenceScreen
import com.health.app.ui.modules.continence.ContinenceState
import com.health.app.ui.modules.mind.MindActions
import com.health.app.ui.modules.mind.MindHome
import com.health.app.ui.modules.mind.MindState
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.tracking.DrinksCard
import com.health.app.ui.trends.TrendsActions
import com.health.app.ui.trends.TrendsScreen
import com.health.app.ui.trends.TrendsState
import com.health.app.ui.trends.trendsSections
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.math.PI
import kotlin.math.sin

/** Sample data for the arranged feature screens. */
private object Arranged {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val today: LocalDate = LocalDate.of(2026, 11, 12)
    fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    val goals = Goals(steps = 8000, sleepMinutes = 480, waterMl = 2000, targetWeightKg = 65.0)
    val modules = Module.DEFAULT + Module.MIND

    private fun trend(i: Int): TrendDay {
        val d = today.minusDays(29L - i)
        val wobble = sin(i * PI / 5)
        return TrendDay(
            date = d.toString(),
            readiness = (78 + 8 * wobble).toInt(), sleepScore = (80 + 6 * sin(i / 2.0)).toInt(), activityScore = (85 + 7 * sin(i / 3.0)).toInt(),
            steps = (7500 + 2500 * sin(i / 2.5)).toInt(), restingHeartRate = 53 - 2 * wobble, hrv = 45 + 6 * wobble,
            sleepMinutes = (430 + 40 * sin(i / 1.7)).toInt(), calories = 1900 + 200 * sin(i / 1.3),
            waterMl = (1700 + 400 * sin(i / 2.2)).toInt(), mood = 3.4 + 0.8 * sin(i / 3.0),
            workouts = if (i % 3 == 0) 1 else 0, workoutMinutes = if (i % 3 == 0) 40 else 0,
        )
    }

    val trends = TrendsState(
        days = 30,
        trends = Trends(today.minusDays(29).toString(), today.toString(), (0 until 30).map(::trend)),
        weights = (0 until 10).map { i -> Sample(Metric.WEIGHT, at(today.minusDays(27L - i * 3), 7), value = 67.3 - i * 0.1 + 0.1 * sin(i.toDouble()), id = i.toLong(), source = "Entered by hand") },
        loading = false,
    )

    /** Trends with weight and sleep first, and the scores and heart charts hidden. */
    val trendsLayout = ScreenLayout(order = listOf("weight", "sleep", "mood", "steps"), hidden = setOf("scores", "hrv", "heart", "calories"))

    val mind: MindState by lazy {
        val moods = (0 until 30).map { 3.6 + 0.6 * sin(it / 3.0) }
        val days = moods.mapIndexed { i, m -> MindDay(today.minusDays(29L - i).toString(), mood = m, entries = 1) }
        val phq = ScreenerResult(Screener.PHQ9, at(today.minusDays(14), 20), List(9) { if (it < 4) 1 else 0 }, score = 4, severity = "Minimal", id = "r1")
        MindState(
            summary = MindSummary(
                days, averageMood = moods.average(), feelings = listOf(Count("calm", 11), Count("tired", 8), Count("hopeful", 6)),
                patterns = listOf(Insight("good", "Sleep and mood", "On days after 7 hours or more of sleep, your mood averaged 4.0, against 3.1.", "sleep")),
                latest = listOf(phq),
            ),
            moods = listOf(MoodEntry(at(today, 9, 10), 4, feelings = listOf("calm", "hopeful"), id = "m1")),
            journal = listOf(
                JournalEntry(at(today.minusDays(1), 21, 30), JournalKind.GRATITUDE, gratitude = listOf("A long walk in the cold sun", "Soup with Sam"), id = "j1"),
                JournalEntry(at(today.minusDays(3), 22, 0), JournalKind.FREE, title = "Busy week", text = "Lots on, but I made time to stretch every morning and it helped.", id = "j2"),
            ),
            results = listOf(phq),
            loading = false,
        )
    }

    /** Mind with the journal first, and the calm tools and insights hidden. */
    val mindLayout = ScreenLayout(order = listOf("journal", "mood", "feeling", "checkins"), hidden = setOf("tools", "insights"))

    val continence: ContinenceState by lazy {
        val entries = listOf(
            ContinenceEntry(at(today, 7, 5), ContinenceKind.URINATION, volumeMl = 380, urgency = 2, id = "c2"),
            ContinenceEntry(at(today, 8, 30), ContinenceKind.BOWEL, bristol = 4, id = "c3"),
            ContinenceEntry(at(today, 10, 15), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough", "sneeze"), id = "c4"),
            ContinenceEntry(at(today, 13, 50), ContinenceKind.URINATION, urgency = 3, id = "c7"),
        ).map { it.copy(date = today.toString()) }
        val leaks = listOf(2, 1, 0, 1, 3, 1, 0, 0, 1, 2, 0, 1, 0, 1)
        ContinenceState(
            today, entries,
            ContinenceSummary(
                days = leaks.mapIndexed { i, l -> ContinenceDay(today.minusDays(13L - i).toString(), voids = 6 + (i % 3), nightVoids = i % 2 + 1, leaks = l, fluidMl = 1600) },
                averageVoids = 7.0, averageNightVoids = 1.5, averageLeaks = 0.9,
                triggers = listOf(Count("cough", 6), Count("sneeze", 4), Count("lifting", 2)),
                averageInterval = 140,
                patterns = listOf(Insight("info", "Coughs and sneezes", "Most leaks came with a cough or sneeze. Squeezing your pelvic floor just before can help.", "water")),
            ),
            loading = false,
        )
    }

    /** The diary with the last two weeks first, and its links and insights hidden. */
    val continenceLayout = ScreenLayout(order = listOf("summary", "today", "log"), hidden = setOf("more", "insights"))

    val drinks = DrinksDay(
        today.toString(),
        drinks = listOf(
            Drink(at(today, 7, 30), DrinkKind.COFFEE, 240, caffeineMg = 96.0, name = "Flat white", id = "d1"),
            Drink(at(today, 11, 0), DrinkKind.TEA, 240, caffeineMg = 48.0, id = "d2"),
            Drink(at(today, 18, 45), DrinkKind.WINE, 150, alcoholG = 14.2, id = "d3"),
        ),
        caffeineMg = 144.0, alcoholG = 14.2, standardDrinks = 1.0, lastCaffeineAt = at(today, 11, 0), caffeineAtBedtimeMg = 38.0, fluidMl = 480,
    )

    val prefs = LayoutPrefs(
        mapOf(FeatureScreens.TRENDS to trendsLayout, FeatureScreens.MIND to mindLayout, FeatureScreens.CONTINENCE to continenceLayout),
    )
}

/** Writes what's on screen to build/outputs/screenshots: every window, so sheets and dialogs show over the page. */
private fun ComponentActivity.saveScreenshot(name: String, runOnUi: (() -> Unit) -> Unit) {
    val decor = window.decorView
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    runOnUi {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>).toList()
        (views.ifEmpty { listOf(decor) }).forEach { v ->
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            canvas.save()
            canvas.translate(at[0].toFloat(), at[1].toFloat())
            v.draw(canvas)
            canvas.restore()
        }
    }
    val dir = File("build/outputs/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** Feature screens in an order the person chose, with sections hidden; and drinks with water not tracked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FeatureArrangeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                CompositionLocalProvider(LocalArrangements provides Arranged.prefs) { content() }
            }
        }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.entries.toSet()), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        compose.activity.saveScreenshot(name) { compose.runOnUiThread(it) }
    }

    private fun trends(dark: Boolean, name: String) {
        render(dark) {
            Frame("trends") { TrendsScreen(Arranged.trends, Units.METRIC, Arranged.goals, Arranged.zone, TrendsActions(), modules = Arranged.modules) }
        }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun trendsArranged() = trends(false, "arranged_trends_light")

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun trendsArrangedDark() = trends(true, "arranged_trends_dark")

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun trendsWaterOff() {
        render {
            Frame("trends") { TrendsScreen(Arranged.trends, Units.METRIC, Arranged.goals, Arranged.zone, TrendsActions(), modules = Arranged.modules, water = false) }
        }
        save("arranged_trends_water_off")
    }

    private fun mind(dark: Boolean, name: String) {
        render(dark) { Frame("health") { MindHome(Arranged.mind, Arranged.zone, MindActions()) } }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun mindArranged() = mind(false, "arranged_mind_light")

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun mindArrangedDark() = mind(true, "arranged_mind_dark")

    private fun continence(dark: Boolean, name: String) {
        render(dark) { Frame("health") { ContinenceScreen(Arranged.continence, Arranged.zone, ContinenceActions()) } }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun continenceArranged() = continence(false, "arranged_continence_light")

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun continenceArrangedDark() = continence(true, "arranged_continence_dark")

    private fun drinks(dark: Boolean, name: String) {
        render(dark) {
            Frame("food") {
                Column(Modifier.fillMaxSize()) {
                    ScreenTitle("Food", "Water isn't tracked")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DrinksCard(Arranged.drinks, Units.METRIC, Arranged.zone, {}, {}, {}, water = false)
                        DrinksCard(null, Units.METRIC, Arranged.zone, {}, {}, {}, water = false)
                    }
                }
            }
        }
        save(name)
    }

    @Test
    fun drinksWaterOff() = drinks(false, "drinks_water_off_light")

    @Test
    fun drinksWaterOffDark() = drinks(true, "drinks_water_off_dark")
}

/**
 * The Arrange sheet over Trends, as the app draws it: from the app's own container (the sheet saves
 * through it), with Trends arranged as above.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HealthApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FeatureArrangeSheetScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private fun sheet(dark: Boolean, name: String) {
        // What the server would have sent: the person's saved arrangement of Trends.
        val app = compose.activity.application as HealthApp
        @Suppress("UNCHECKED_CAST")
        val layout = app.container.javaClass.getDeclaredField("_layout").apply { isAccessible = true }.get(app.container) as MutableStateFlow<LayoutPrefs>
        // Signed out, the container clears the layout once it starts: wait for that before putting the person's in.
        layout.value = LayoutPrefs(mapOf("waiting" to ScreenLayout(listOf("x"))))
        val until = System.currentTimeMillis() + 5_000
        while ("waiting" in layout.value.screens && System.currentTimeMillis() < until) Thread.sleep(20)
        layout.value = Arranged.prefs
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                AppFrame(selected = "trends", snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.entries.toSet())) {
                    TrendsScreen(Arranged.trends, Units.METRIC, Arranged.goals, Arranged.zone, TrendsActions(), modules = Arranged.modules)
                }
                ArrangeSheet(FeatureScreens.TRENDS, "Trends", trendsSections(Arranged.modules), onDismiss = {})
            }
        }
        compose.waitForIdle()
        compose.activity.saveScreenshot(name) { compose.runOnUiThread(it) }
    }

    @Test
    fun arrangeSheet() = sheet(false, "arrange_sheet_trends_light")

    @Test
    fun arrangeSheetDark() = sheet(true, "arrange_sheet_trends_dark")
}
