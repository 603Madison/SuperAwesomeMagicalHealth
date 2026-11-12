package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.Contraction
import com.health.api.DaySummary
import com.health.api.Digest
import com.health.api.DigestPrefs
import com.health.api.DigestSection
import com.health.api.DoseChange
import com.health.api.Feed
import com.health.api.FeedKind
import com.health.api.HormoneMed
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.HormonesToday
import com.health.api.HotFlash
import com.health.api.KickSession
import com.health.api.LabResult
import com.health.api.LabSeries
import com.health.api.LabTarget
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.LifeStageOverview
import com.health.api.LifeStageToday
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.PregnancyStatus
import com.health.api.Profile
import com.health.api.ReminderPrefs
import com.health.api.Score
import com.health.api.Share
import com.health.api.Shares
import com.health.api.SuggestedQuestion
import com.health.api.Units
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.care.AppointmentEditor
import com.health.app.ui.care.AppointmentsScreen
import com.health.app.ui.care.AppointmentsState
import com.health.app.ui.care.DigestScreen
import com.health.app.ui.care.HormonesActions
import com.health.app.ui.care.HormonesScreen
import com.health.app.ui.care.HormonesState
import com.health.app.ui.care.IntegrationsScreen
import com.health.app.ui.care.KickProgress
import com.health.app.ui.care.LifeStageActions
import com.health.app.ui.care.LifeStageSection
import com.health.app.ui.care.LifeStageState
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.care.RemindersScreen
import com.health.app.ui.care.ReportsScreen
import com.health.app.ui.care.SharingActions
import com.health.app.ui.care.SharingScreen
import com.health.app.ui.care.SharingState
import com.health.app.ui.care.ViewingBanner
import com.health.app.ui.care.WatchScreen
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.modules.meds.MedEditorScreen
import com.health.app.ui.modules.mind.ScreenerScreen
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayActions
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayState
import com.health.server.modules.MindService
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
 * The care and platform screens (hormone therapy, life stages, appointments, sharing, reminders,
 * the digest, reports, the watch and integrations) drawn with realistic data, light and dark, to
 * PNGs in android/build/outputs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CareScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 14, 20)
    private val allModules = Module.entries.toSet()

    private fun render(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) { content() } }
    }

    @Composable
    private fun Frame(tab: String, readOnly: Boolean = false, content: @Composable () -> Unit) {
        val tabs = if (tab == "settings") tabsFor(Module.DEFAULT) else tabsFor(allModules)
        CompositionLocalProvider(LocalReadOnly provides readOnly) {
            AppFrame(selected = tab, snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabs, content = content)
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

    // ---- Sample data ----

    private val estradiol = Medication(
        "Estradiol valerate", "5 mg (0.25 ml)", MedForm.INJECTION, MedSchedule(com.health.api.ScheduleKind.WEEKLY, listOf("19:30"), weekdays = listOf(4)),
        id = "ev", hormone = true, startedOn = "2025-08-04",
    )
    private val spiro = Medication("Spironolactone", "100 mg", schedule = MedSchedule(times = listOf("09:00")), id = "sp", hormone = true, startedOn = "2025-08-04")

    private val hormones = HormoneOverview(
        medications = listOf(
            HormoneMed(
                estradiol, "2025-08-04",
                listOf(
                    DoseChange("ev", at(LocalDate.of(2025, 8, 4), 12), null, "3 mg (0.15 ml)"),
                    DoseChange("ev", at(LocalDate.of(2026, 1, 20), 12), "3 mg (0.15 ml)", "4 mg (0.2 ml)", "After January's labs"),
                    DoseChange("ev", at(LocalDate.of(2026, 6, 2), 12), "4 mg (0.2 ml)", "5 mg (0.25 ml)"),
                ),
            ),
            HormoneMed(spiro, "2025-08-04", listOf(DoseChange("sp", at(LocalDate.of(2025, 8, 4), 12), null, "100 mg"))),
        ),
        labs = listOf(
            LabSeries(
                "Estradiol", "pg/mL", LabTarget("Estradiol", "pg/mL", 100.0, 200.0),
                listOf(62.0, 118.0, 141.0, 228.0, 176.0).mapIndexed { i, v -> LabResult("Estradiol", v, "pg/mL", at(LocalDate.of(2025, 10, 1).plusMonths(i * 3L), 9)) },
            ),
            LabSeries(
                "Testosterone", "ng/dL", LabTarget("Testosterone", "ng/dL", null, 50.0),
                listOf(310.0, 64.0, 38.0, 31.0, 27.0).mapIndexed { i, v -> LabResult("Testosterone", v, "ng/dL", at(LocalDate.of(2025, 10, 1).plusMonths(i * 3L), 9)) },
            ),
        ),
        settings = HormoneSettings(12, listOf(LabTarget("Estradiol", "pg/mL", 100.0, 200.0), LabTarget("Testosterone", "ng/dL", null, 50.0))),
        lastLabs = "2026-10-01",
        nextLabsDue = "2026-12-24",
    )

    private fun pregnant(counting: Boolean = false) = LifeStageState(
        today,
        LifeStageOverview(
            info = LifeStageInfo(LifeStage.PREGNANT, dueDate = "2027-02-27"),
            pregnancy = PregnancyStatus(28, 3, 3, "2027-02-27", 107),
            kicks = listOf(KickSession(at(today, 9, 5), at(today, 9, 31), 10), KickSession(at(today.minusDays(1), 20, 10), at(today.minusDays(1), 20, 28), 10)),
            contractions = listOf(Contraction(at(today, 13, 50), at(today, 13, 50) + 42_000), Contraction(at(today, 14, 2), at(today, 14, 2) + 48_000)),
            tips = listOf(com.health.api.Insight("info", "Movements matter", "If they slow down or change, contact your maternity unit straight away.", "heart", Module.CYCLE)),
        ),
        counting = if (counting) KickProgress(now - 4 * 60_000, 6) else null,
    )

    private val postpartum = LifeStageState(
        today,
        LifeStageOverview(
            info = LifeStageInfo(LifeStage.POSTPARTUM, birthDate = "2026-10-01"),
            weeksSinceBirth = 6,
            feeds = listOf(
                Feed(at(today, 6, 10), at(today, 6, 32), FeedKind.LEFT),
                Feed(at(today, 9, 0), at(today, 9, 20), FeedKind.RIGHT),
                Feed(at(today, 11, 45), at(today, 11, 55), FeedKind.BOTTLE, amountMl = 90),
            ),
        ),
        feed = Feed(now - 7 * 60_000, kind = FeedKind.LEFT, id = "f"),
    )

    private val menopause = LifeStageState(
        today,
        LifeStageOverview(
            info = LifeStageInfo(LifeStage.PERIMENOPAUSE),
            monthsSinceLastPeriod = 7,
            hotFlashes = listOf(HotFlash(at(today, 3, 10), 3, night = true), HotFlash(at(today, 11, 40), 2), HotFlash(at(today.minusDays(1), 16, 5), 1)),
            hotFlashesPerDay = 3.4,
        ),
    )

    private val appointments = listOf(
        Appointment(
            at(today.plusDays(2), 9, 30), "Check-up", 30, "Dr Patel", "Riverside Health Centre",
            listOf(AppointmentQuestion("Can we look at my estradiol dose?"), AppointmentQuestion("Is the new spironolactone dose okay with my blood pressure?", suggested = true)),
            id = "a1",
        ),
        Appointment(at(today.plusDays(9), 15, 0), "Pelvic health physio", 45, "Sam", "Physio clinic", listOf(AppointmentQuestion("Exercises for leaks when I run")), id = "a2"),
        Appointment(at(today.minusDays(21), 10, 0), "Blood test", 15, null, "Lab on Main St", notes = "All done. Results in a week.", id = "a3"),
    )

    private val suggestions = listOf(
        SuggestedQuestion("My estradiol was 228 pg/mL in July, above my range. Should the dose change?", "From your labs since your last appointment", Module.HORMONES),
        SuggestedQuestion("I've had 3 migraines this month. Is that worth a look?", "From your symptoms", Module.SYMPTOMS),
        SuggestedQuestion("I missed 4 doses of spironolactone. Does that matter?", "From your medications", Module.MEDICATIONS),
    )

    private val shares = Shares(
        given = listOf(Share("s1", "me", "Alex", "jo", "Jo", setOf(Module.ACTIVITY, Module.SLEEP, Module.MEDICATIONS), at(today.minusDays(30), 10), at(today, 8, 2))),
        received = listOf(Share("s2", "mum", "Mum", "me", "Alex", setOf(Module.ACTIVITY, Module.VITALS, Module.MEDICATIONS), at(today.minusDays(60), 10))),
    )

    private val profile = Profile(
        birthYear = 1994, heightCm = 170.0, timezone = "America/New_York", modules = allModules,
        reminders = ReminderPrefs(remindAgainMinutes = 30, remindAgainTimes = 2, refillDays = 7),
        digest = DigestPrefs(enabled = true, day = 7, hour = 18, email = "alex@example.com", emailEnabled = true, emailModules = setOf(Module.MEDICATIONS)),
    )

    private val digest = Digest(
        "2026-11-02", "2026-11-08", now, "A steadier week: more sleep, and every dose on time.",
        listOf(
            DigestSection("Sleep", Module.SLEEP, "good", listOf("7h 24m a night, 32 minutes more than the week before", "Sleep score 84 on average (up 5)")),
            DigestSection("Activity", Module.ACTIVITY, "info", listOf("8,940 steps a day", "3 workouts, 2h 15m in all")),
            DigestSection("Medications", Module.MEDICATIONS, "good", listOf("All 15 doses taken", "Spironolactone runs low in about 6 days")),
            DigestSection("Blood pressure", Module.VITALS, "warn", listOf("128/84 on average, a little higher than last week")),
        ),
    )

    private fun summaryWithCare() = DaySummary(
        date = today.toString(), steps = 6420, hasData = true,
        readiness = Score(84, "Good", emptyList()), sleep = Score(86, "Good", emptyList()), activity = Score(71, "Fair", emptyList()),
        modules = ModulesToday(
            hormones = HormonesToday(2, "2026-12-24", listOf("Estradiol")),
            lifeStage = LifeStageToday(LifeStage.PERIMENOPAUSE, "7 months since your last period", hotFlashesToday = 2),
            appointments = appointments.take(1),
        ),
    )

    // ---- Screens ----

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun hormones() {
        render { Frame("health") { HormonesScreen(HormonesState(today, hormones, loading = false), zone, HormonesActions()) } }
        save("hormones_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun hormonesDark() {
        render(dark = true) { Frame("health") { HormonesScreen(HormonesState(today, hormones, loading = false), zone, HormonesActions()) } }
        save("hormones_dark")
    }

    @Test
    fun hormonesEmpty() {
        render { Frame("health") { HormonesScreen(HormonesState(today, HormoneOverview(emptyList(), emptyList(), HormoneSettings()), loading = false), zone, HormonesActions()) } }
        save("hormones_empty")
    }

    @Composable
    private fun CyclePage(state: LifeStageState) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ScreenTitle("Cycle", "Periods, symptoms and what's likely next")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LifeStageSection(state, zone, LifeStageActions(), now = { now })
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun pregnancy() {
        render { Frame("health") { CyclePage(pregnant()) } }
        save("cycle_pregnant_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    fun pregnancyCountingDark() {
        render(dark = true) { Frame("health") { CyclePage(pregnant(counting = true)) } }
        save("cycle_pregnant_counting_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun afterABirth() {
        render { Frame("health") { CyclePage(postpartum) } }
        save("cycle_postpartum_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun perimenopauseDark() {
        render(dark = true) { Frame("health") { CyclePage(menopause) } }
        save("cycle_perimenopause_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun epds() {
        render { Frame("health") { ScreenerScreen(MindService.EPDS, emptyList(), null, emptyList(), zone, {}, {}, {}) } }
        save("epds_light")
    }

    @Test
    fun appointmentsList() {
        render { Frame("health") { AppointmentsScreen(AppointmentsState(now, appointments, loading = false), zone, onOpen = {}) } }
        save("appointments_light")
    }

    @Test
    fun appointmentsListDark() {
        render(dark = true) { Frame("health") { AppointmentsScreen(AppointmentsState(now, appointments, loading = false), zone, onOpen = {}) } }
        save("appointments_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun appointmentEditor() {
        render { Frame("health") { AppointmentEditor(appointments[0], suggestions, zone, {}, {}, {}) } }
        save("appointment_editor_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun appointmentEditorDark() {
        render(dark = true) { Frame("health") { AppointmentEditor(appointments[0], suggestions, zone, {}, {}, {}) } }
        save("appointment_editor_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun sharing() {
        render { Frame("settings") { SharingScreen(SharingState(shares, loading = false, now = now), allModules, {}, SharingActions()) } }
        save("sharing_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun sharingDark() {
        render(dark = true) { Frame("settings") { SharingScreen(SharingState(shares, loading = false, now = now), allModules, {}, SharingActions()) } }
        save("sharing_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun reminders() {
        render { Frame("settings") { RemindersScreen(profile, {}, onSave = {}) } }
        save("reminders_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun remindersDark() {
        render(dark = true) { Frame("settings") { RemindersScreen(profile, {}, canNotify = false, onSave = {}) } }
        save("reminders_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun digest() {
        render { Frame("settings") { DigestScreen(digest, false, null, profile, {}) } }
        save("digest_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun digestDark() {
        render(dark = true) { Frame("settings") { DigestScreen(digest, false, null, profile, {}) } }
        save("digest_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun reports() {
        render { Frame("settings") { ReportsScreen(allModules, today, null, {}, { _, _, _ -> }, { _, _ -> }) } }
        save("reports_light")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun reportsDark() {
        render(dark = true) { Frame("settings") { ReportsScreen(allModules, today, "pdf", {}, { _, _, _ -> }, { _, _ -> }) } }
        save("reports_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun watch() {
        render { Frame("settings") { WatchScreen("https://health.example.com", {}, {}) } }
        save("watch_light")
    }

    @Test
    fun integrationsDark() {
        render(dark = true) { Frame("settings") { IntegrationsScreen({}, {}) } }
        save("integrations_dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun medEditorHormones() {
        render { Frame("health") { MedEditorScreen(estradiol, {}, {}, {}, hormones = true) } }
        save("med_editor_hormones")
    }

    private fun today(dark: Boolean, readOnly: Boolean, name: String) {
        render(dark) {
            Frame("today", readOnly) {
                Column {
                    if (readOnly) ViewingBanner("Mum", {})
                    TodayScreen(TodayState(today, today, summaryWithCare(), loading = false), Units.METRIC, zone, TodayActions(), allModules)
                }
            }
        }
        save(name)
    }

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun todayCare() = today(dark = false, readOnly = false, name = "today_care_light")

    @Test
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    fun todayCareDark() = today(dark = true, readOnly = false, name = "today_care_dark")

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun todayViewingShare() = today(dark = false, readOnly = true, name = "today_viewing_share")
}
