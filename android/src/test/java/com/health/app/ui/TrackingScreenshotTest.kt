package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.health.api.BloodPressure
import com.health.api.BpCategory
import com.health.api.Contributor
import com.health.api.Correlation
import com.health.api.Count
import com.health.api.DaySummary
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.FoodDay
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.Goals
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitStatus
import com.health.api.Insight
import com.health.api.Migraine
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Place
import com.health.api.Profile
import com.health.api.Resilience
import com.health.api.ResilienceLevel
import com.health.api.Role
import com.health.api.Score
import com.health.api.StressDay
import com.health.api.StressSpan
import com.health.api.StressState
import com.health.api.SymptomEntry
import com.health.api.SymptomsDay
import com.health.api.SymptomsSummary
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.api.Units
import com.health.api.User
import com.health.api.VitalsSummary
import com.health.api.VitalsTargets
import com.health.api.VitalsToday
import com.health.api.WaterEntry
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.devices.vitals.FoundMonitor
import com.health.app.devices.vitals.MonitorKind
import com.health.app.devices.vitals.PairedMonitor
import com.health.app.devices.vitals.VitalsConduit
import com.health.app.sync.ConduitStatus
import com.health.app.ui.devices.DevicesActions
import com.health.app.ui.devices.DevicesScreen
import com.health.app.ui.devices.DevicesState
import com.health.app.ui.devices.HealthConnectState
import com.health.app.ui.food.FoodActions
import com.health.app.ui.food.FoodScreen
import com.health.app.ui.food.FoodState
import com.health.app.ui.modules.HubScreen
import com.health.app.ui.settings.SettingsActions
import com.health.app.ui.settings.SettingsScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import com.health.app.ui.tracking.HabitForm
import com.health.app.ui.tracking.HabitsActions
import com.health.app.ui.tracking.HabitsScreen
import com.health.app.ui.tracking.HabitsState
import com.health.app.ui.tracking.MonitorPairScreen
import com.health.app.ui.tracking.MonitorsUi
import com.health.app.ui.tracking.SymptomForm
import com.health.app.ui.tracking.SymptomsActions
import com.health.app.ui.tracking.SymptomsScreen
import com.health.app.ui.tracking.SymptomsState
import com.health.app.ui.tracking.TrackingTodayActions
import com.health.app.ui.tracking.VitalsActions
import com.health.app.ui.tracking.VitalsScreen
import com.health.app.ui.tracking.VitalsState
import com.health.app.ui.trends.TrendsActions
import com.health.app.ui.trends.TrendsScreen
import com.health.app.ui.trends.TrendsState
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
import kotlin.math.sin

/**
 * Blood pressure and glucose, drinks, symptoms, stress and habits, drawn with realistic data to
 * PNGs in android/build/outputs/screenshots, for a look (as ScreenshotTest does for the rest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class TrackingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 14, 20)
    private val all = Module.entries.toSet()

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() } }
    }

    @Composable
    private fun Frame(tab: String, content: @Composable () -> Unit) {
        AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(all), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Sample data ----

    private val targets = VitalsTargets()

    private val vitals: VitalsSummary by lazy {
        val bp = (0 until 30).filter { it % 2 == 0 || it > 24 }.map { i ->
            val day = today.minusDays(29L - i)
            BloodPressure(at(day, 7, 40), (124 + 8 * sin(i / 3.0)).toInt(), (79 + 5 * sin(i / 4.0)).toInt(), pulse = 64 + i % 6, arm = "left", id = "b$i")
        } + BloodPressure(at(today, 8, 5), 138, 88, pulse = 72, arm = "left", source = "Omron M7", id = "bt")
        val glucose = (0 until 30).map { i ->
            val day = today.minusDays(29L - i)
            GlucoseReading(at(day, 7, 30), 5.2 + 0.6 * sin(i / 2.5), GlucoseContext.FASTING, id = "g$i")
        } + GlucoseReading(at(today, 13, 10), 8.9, GlucoseContext.AFTER_MEAL, id = "gt")
        VitalsSummary(
            days = 30, bloodPressure = bp, glucose = glucose, systolicAverage = 126.4, diastolicAverage = 80.2, category = BpCategory.STAGE_1,
            glucoseAverage = 5.6, fastingAverage = 5.3, timeBelow = 2, timeInRange = 91, timeAbove = 7, targets = targets,
            patterns = listOf(Insight("info", "Higher in the mornings", "Your morning readings average 131/83, against 122/78 in the evening.", "heart", Module.VITALS)),
        )
    }

    private val stressDay = StressDay(
        today.toString(), stressedMinutes = 95, engagedMinutes = 140, relaxedMinutes = 120, restoredMinutes = 65, summary = "Normal",
        spans = listOf(
            StressState.RESTORED to 45, StressState.RELAXED to 60, StressState.ENGAGED to 90, StressState.STRESSED to 50, StressState.ENGAGED to 50,
            StressState.STRESSED to 45, StressState.RELAXED to 60, StressState.RESTORED to 20,
        ).let { parts ->
            var t = at(today, 7, 0)
            parts.map { (s, m) -> StressSpan(t, t + m * 60_000L, s).also { t += m * 60_000L } }
        },
    )

    private val resilience = Resilience(
        ResilienceLevel.SOLID,
        listOf(Contributor("sleep", "Sleep recovery", 78, "Good nights most of the week"), Contributor("day", "Daytime recovery", 64, "47 min restored a day"), Contributor("load", "Stress load", 70, "Steady")),
        trend = "up",
    )

    private fun habit(name: String, icon: String, done: Boolean, weeks: Int, days: Int, weekDone: Int, source: HabitSource = HabitSource.MANUAL, perDay: Int = 1, count: Int = if (done) 1 else 0, timesPerWeek: Int = 7) =
        HabitStatus(
            Habit(name, icon, source, timesPerWeek, perDay, id = name), today.toString(), count, done, weekDone, weeks, days,
            recent = (0 until 14).map { i -> if (i < 2) null else (i * 7 + name.length) % 5 != 0 },
        )

    private val habits = listOf(
        habit("Walk after lunch", "footprints", true, 6, 23, 4, timesPerWeek = 5),
        habit("Take all my medications", "pill", false, 3, 18, 3, HabitSource.MEDS_TAKEN),
        habit("Glasses of water", "droplet", false, 2, 9, 2, perDay = 3, count = 2),
        habit("Stretch before bed", "leaf", false, 0, 1, 1, timesPerWeek = 4),
    )

    private val drinks = DrinksDay(
        today.toString(),
        drinks = listOf(
            Drink(at(today, 7, 30), DrinkKind.COFFEE, 240, caffeineMg = 96.0, name = "Flat white", id = "d1"),
            Drink(at(today, 11, 0), DrinkKind.TEA, 240, caffeineMg = 48.0, id = "d2"),
            Drink(at(today, 13, 45), DrinkKind.COFFEE, 240, caffeineMg = 96.0, id = "d3"),
        ),
        caffeineMg = 240.0, alcoholG = 0.0, standardDrinks = 0.0, lastCaffeineAt = at(today, 13, 45), caffeineAtBedtimeMg = 61.0, fluidMl = 720,
    )

    private val symptomsDay = SymptomsDay(today.toString(), listOf(SymptomEntry(at(today, 10, 15), "headache", 5, listOf("head"), triggers = listOf("poor sleep"), id = "s1")), worst = 5)

    private val summary = DaySummary(
        date = today.toString(),
        readiness = Score(78, "Good", emptyList()), sleep = Score(81, "Good", emptyList()), activity = Score(72, "Good", emptyList()),
        steps = 6204, activeCalories = 340.0, restingHeartRate = 54.0, hrv = 44.0, hasData = true,
        stressDay = stressDay, resilience = resilience, drinks = drinks,
        nutrition = Nutrients(1180.0, 70.0, 120.0, 40.0), waterMl = 1220, goals = Goals(),
        modules = ModulesToday(
            vitals = VitalsToday(BloodPressure(at(today, 8, 5), 138, 88, pulse = 72), BpCategory.STAGE_2, GlucoseReading(at(today, 13, 10), 8.9, GlucoseContext.AFTER_MEAL), 91, targets),
            habits = habits,
            symptoms = symptomsDay,
        ),
    )

    private val trackingModules = Module.DEFAULT + setOf(Module.VITALS, Module.HABITS, Module.SYMPTOMS)

    // ---- Screens ----

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun vitalsLight() {
        render { Frame("health") { VitalsScreen(VitalsState(summary = vitals, loading = false), targets, zone, VitalsActions(), now) } }
        save("vitals_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun vitalsDarkMgdl() {
        render(dark = true) { Frame("health") { VitalsScreen(VitalsState(summary = vitals.copy(targets = targets.copy(glucoseUnit = GlucoseUnit.MG_DL)), loading = false), targets.copy(glucoseUnit = GlucoseUnit.MG_DL), zone, VitalsActions(), now) } }
        save("vitals_dark_mgdl")
    }

    @Test
    fun habitsLight() {
        render { Frame("health") { HabitsScreen(HabitsState(habits, loading = false), HabitsActions()) } }
        save("habits_light")
    }

    @Test
    fun habitsDark() {
        render(dark = true) { Frame("health") { HabitsScreen(HabitsState(habits, loading = false), HabitsActions()) } }
        save("habits_dark")
    }

    @Test
    fun habitsEmpty() {
        render { Frame("health") { HabitsScreen(HabitsState(loading = false), HabitsActions()) } }
        save("habits_empty")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun habitEditor() {
        render { HabitForm(Habit("Walk after lunch", "footprints", timesPerWeek = 5, reminderTime = "12:30"), {}, {}) }
        save("habit_editor")
    }

    private val symptomsState = SymptomsState(
        entries = listOf(
            SymptomEntry(at(today, 10, 15), "headache", 5, listOf("head"), triggers = listOf("poor sleep"), id = "s1"),
            SymptomEntry(at(today.minusDays(1), 18, 40), "back pain", 3, listOf("lower back"), relief = listOf("heat"), reliefHelped = 2, id = "s2"),
            SymptomEntry(at(today.minusDays(3), 9, 0), "fatigue", 6, id = "s3"),
        ),
        migraines = listOf(
            Migraine(at(today, 12, 50), severity = 7, aura = true, id = "m1"),
            Migraine(at(today.minusDays(6), 15, 0), at(today.minusDays(6), 21, 30), 8, symptoms = listOf("nausea", "light sensitivity"), id = "m2"),
        ),
        summary = SymptomsSummary(
            days = (0 until 90).filter { it % 7 == 1 || it % 11 == 3 }.map { i ->
                SymptomsDay(today.minusDays(i.toLong()).toString(), listOf(SymptomEntry(at(today.minusDays(i.toLong()), 9), "headache", 4)), worst = 4)
            },
            counts = listOf(Count("headache", 9), Count("fatigue", 6), Count("back pain", 4)),
            migraineDays = 4,
            triggers = listOf(Count("poor sleep", 7), Count("stress", 5), Count("skipped a meal", 2)),
            correlations = listOf(
                Correlation("short sleep", "Headaches came on 3 in 5 days after under 6 hours of sleep, against 1 in 5 otherwise.", 0.6, 0.2, 21),
                Correlation("pressure", "Migraines were twice as likely on days the air pressure fell by 5 hPa or more.", 0.4, 0.2, 30),
            ),
            weather = true,
        ),
        place = Place("Leeds", 53.8, -1.55),
        loading = false,
    )

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun symptomsLight() {
        render { Frame("health") { SymptomsScreen(symptomsState, zone, SymptomsActions(), now) } }
        save("symptoms_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun symptomsDark() {
        render(dark = true) { Frame("health") { SymptomsScreen(symptomsState.copy(migraines = symptomsState.migraines.drop(1), place = null), zone, SymptomsActions(), now) } }
        save("symptoms_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun symptomForm() {
        render { SymptomForm(SymptomEntry(now, "headache", 6, listOf("head", "neck"), triggers = listOf("screens"), relief = listOf("water")), {}, null) }
        save("symptom_form")
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun todayTrackingLight() {
        render {
            Frame("today") {
                TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(tracking = TrackingTodayActions()), modules = trackingModules)
            }
        }
        save("today_tracking_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun todayTrackingDark() {
        render(dark = true) {
            Frame("today") {
                TodayScreen(TodayState(today, today, summary, loading = false), Units.METRIC, zone, TodayActions(tracking = TrackingTodayActions(GlucoseUnit.MG_DL)), modules = trackingModules)
            }
        }
        save("today_tracking_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun foodDrinks() {
        val food = FoodDay(
            today.toString(), entries = emptyList(), water = listOf(WaterEntry(today.toString(), 500, "w1")), totals = Nutrients(1180.0, 70.0, 120.0, 40.0), waterMl = 500,
            target = NutritionTarget(1950, 120, 210, 65, 2000, "About 2,200 kcal burned"),
        )
        render { Frame("food") { FoodScreen(FoodState(today, today, food, loading = false, drinks = drinks), Units.METRIC, FoodActions(), zone) } }
        save("food_drinks")
    }

    private fun trend(i: Int): TrendDay {
        val day = today.minusDays(29L - i)
        return TrendDay(
            day.toString(), readiness = 70 + (10 * sin(i / 3.0)).toInt(), sleepScore = 75, activityScore = 80, steps = 7000 + i * 50,
            systolic = 124 + 8 * sin(i / 3.0), diastolic = 79 + 5 * sin(i / 4.0), glucose = 5.4 + 0.5 * sin(i / 2.0),
            caffeineMg = 150.0 + 90 * sin(i / 2.0), standardDrinks = if (i % 6 == 5) 2.0 else 0.0,
            symptomWorst = if (i % 4 == 0) 3 + i % 5 else null, migraine = i % 9 == 0,
            habitsDone = 2 + i % 3, habitsDue = 4, stressedMinutes = 60 + (40 * sin(i / 2.0)).toInt(), restoredMinutes = 40 + (20 * sin(i / 3.0)).toInt(),
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h4400dp-xxhdpi")
    fun trendsTracking() {
        val state = TrendsState(30, Trends(today.minusDays(29).toString(), today.toString(), (0 until 30).map(::trend)), loading = false)
        render { Frame("trends") { TrendsScreen(state, Units.METRIC, Goals(), zone, TrendsActions(), modules = trackingModules) } }
        save("trends_tracking")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun devicesMonitors() {
        val state = DevicesState(healthConnect = HealthConnectState.ON, statuses = mapOf(VitalsConduit.ID to ConduitStatus(now - 3 * 60_000, true, null, samples = 2)), loading = false)
        val monitors = MonitorsUi(listOf(PairedMonitor(MonitorKind.BLOOD_PRESSURE, "C4:7C:8D:6A:01:02", "Omron M7")))
        render { Frame("devices") { DevicesScreen(state, DevicesActions(), now, monitors) } }
        save("devices_monitors")
    }

    @Test
    fun monitorPair() {
        val found = listOf(
            FoundMonitor("C4:7C:8D:6A:01:02", "BLESmart_0000021F", MonitorKind.BLOOD_PRESSURE, -58),
            FoundMonitor("D0:3E:7D:11:42:9A", "Contour7830H", MonitorKind.GLUCOSE, -71),
        )
        render(dark = true) { Frame("devices") { MonitorPairScreen(found, searching = false, error = null, onBack = {}, onSearchAgain = {}) {} } }
        save("monitor_pair_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun settingsAllFeatures() {
        val settings = Settings(server = "https://health.home.arpa", token = "t", serverName = "Health at home", user = User("u1", "maddie", "Maddie", Role.ADMIN, createdAt = 0))
        render { Frame("settings") { SettingsScreen(settings, Profile(timezone = "America/New_York", modules = all), SettingsActions()) } }
        save("settings_all_features")
    }

    @Test
    fun hubAllFeatures() {
        render { Frame("health") { HubScreen(all, showDevices = false) {} } }
        save("hub_all_features")
    }
}
