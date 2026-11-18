package com.health.app.ui.care

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.health.api.Appointment
import com.health.api.AppointmentQuestion
import com.health.api.ContinenceDay
import com.health.api.ContinenceSummary
import com.health.api.ContinenceWear
import com.health.api.ContinenceWearing
import com.health.api.DaySummary
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.Fields
import com.health.api.HormoneOverview
import com.health.api.HormoneSettings
import com.health.api.HormonesToday
import com.health.api.LabTarget
import com.health.api.Meal
import com.health.api.MealiePlan
import com.health.api.MealiePlanEntry
import com.health.api.MeasurementSet
import com.health.api.MedForm
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.api.Metric
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.NeuroToday
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Routine
import com.health.api.ScheduleKind
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.TransitionOverview
import com.health.api.TransitionProfile
import com.health.api.TransitionToday
import com.health.api.VoicePlan
import com.health.api.VoiceProgress
import com.health.api.VoiceSession
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.ui.Format
import com.health.app.ui.components.Backdrop
import com.health.app.ui.modules.meds.MedsActions
import com.health.app.ui.modules.meds.MedsScreen
import com.health.app.ui.modules.meds.MedsState
import com.health.app.ui.theme.HealthTheme
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
 * The Care tab drawn with a realistic day, to PNGs in android/build/outputs/screenshots: a person
 * with medications and hormone therapy, a bladder and bowel diary with a product on, transition,
 * food, appointments and Autism & ADHD (Aurora light and dark, Ocean, and Violet with Bold text);
 * someone viewing a share; a part that couldn't load; nothing tracked yet; and the Medications
 * page's way to Hormone therapy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w390dp-h1900dp-xxhdpi")
class CareScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 17)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 18, 10)

    private val modules = setOf(
        Module.ACTIVITY, Module.SLEEP, Module.NUTRITION, Module.BODY, Module.MEDICATIONS, Module.HORMONES, Module.CONTINENCE,
        Module.TRANSITION, Module.PHOTOS, Module.APPOINTMENTS, Module.NEURO,
    )
    private val fields = Fields.DEFAULTS + setOf(Fields.CONTINENCE_PROTECTION, Fields.CONTINENCE_SUPPLIES, Fields.TRANSITION_VOICE, Fields.TRANSITION_MEASUREMENTS)

    // ---- A day

    private val estradiol = Medication(
        "Estradiol valerate", "5 mg (0.25 ml)", MedForm.INJECTION, MedSchedule(ScheduleKind.WEEKLY, listOf("20:00"), weekdays = listOf(2)),
        sites = listOf("left thigh", "right thigh"), stock = 3, id = "ev", hormone = true,
    )
    private val spiro = Medication("Spironolactone", "100 mg", schedule = MedSchedule(times = listOf("09:00")), stock = 26, id = "sp", hormone = true)

    private val wearing = ContinenceWearing(
        supplyId = "nb", name = "Night briefs", kind = SupplyKind.BRIEF, since = at(today, 11, 5), changeDueAt = at(today, 20, 30),
        wear = ContinenceWear("nb", "Night briefs", SupplyKind.BRIEF, current = true),
    )

    private val diary = ContinenceSummary(
        (13 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            ContinenceDay(d.toString(), voids = listOf(6, 7, 5, 6, 8, 6, 7, 6, 7, 5, 6, 8, 6, 5)[13 - back], leaks = if (back in setOf(8, 9, 11, 12, 4)) 1 else 0)
        },
    )

    private val day = DaySummary(
        today.toString(), hasData = true,
        nutrition = Nutrients(calories = 1240.0, protein = 62.0),
        nutritionTarget = NutritionTarget(1900, 90, 200, 60, 2000, "Your goal"),
        modules = ModulesToday(
            meds = MedsDay(
                today.toString(),
                listOf(
                    DueDose("sp", "Spironolactone", "100 mg", MedForm.PILL, at(today, 9), DoseLog("sp", at(today, 9, 12), DoseStatus.TAKEN, at(today, 9))),
                    DueDose("ev", "Estradiol valerate", "5 mg (0.25 ml)", MedForm.INJECTION, at(today, 20), nextSite = "left thigh"),
                ),
            ),
            continence = ContinenceDay(today.toString(), voids = 5, wearing = wearing),
            hormones = HormonesToday(2, "2027-01-12"),
            appointments = emptyList(),
            neuro = NeuroToday(
                routinesDue = listOf("Evening routine"),
                lastCheck = com.health.api.EnergyCheck(at(today, 9, 45), energy = 4, focus = 2, id = "e1"),
                bestFocus = "9 AM – noon",
            ),
            transition = TransitionToday("1 year, 3 months since you began", milestonesDue = listOf("Name change")),
        ),
    )

    private val data = CareData(
        day = day,
        meds = listOf(estradiol, spiro),
        hormones = HormoneOverview(emptyList(), emptyList(), HormoneSettings(12, listOf(LabTarget("Estradiol", "pg/mL", 100.0, 200.0))), lastLabs = "2026-10-12", nextLabsDue = "2027-01-12"),
        wearing = wearing,
        continence = diary,
        supplies = listOf(SupplyStatus(Supply("Night briefs", SupplyKind.BRIEF, onHand = 14, id = "nb"), perDay = 2.0, daysLeft = 7)),
        appointments = listOf(
            Appointment(
                at(LocalDate.of(2026, 11, 19), 14, 30), "Check-up", 30, "Dr Patel", "Riverside Health Centre",
                listOf(AppointmentQuestion("Can we look at my estradiol dose?"), AppointmentQuestion("Is my blood pressure okay with spironolactone?", suggested = true)),
                id = "a1",
            ),
        ),
        plan = MealiePlan(today.toString(), listOf(MealiePlanEntry(Meal.DINNER, "Chili"))),
        transition = TransitionOverview(
            TransitionProfile(startedOn = "2025-08-04"), sinceStart = "1 year, 3 months",
            voice = listOf(VoiceSession(at(today, 8), 10)),
            measurements = listOf(MeasurementSet(at(LocalDate.of(2026, 11, 3), 9), mapOf(Metric.WAIST to 81.0, Metric.HIPS to 99.0))),
        ),
        voice = VoiceProgress(VoicePlan(targetLowHz = 165.0, targetHighHz = 220.0, reminder = "19:30"), nextLessonId = "l3", streakDays = 4),
        photos = 12,
        routines = listOf(Routine("Evening routine", emptyList(), reminder = "21:00", id = "r1")),
    )

    private fun ctx(modules: Set<Module> = this.modules, readOnly: Boolean = false, viewing: Boolean = false, locked: Boolean = false) = CareContext(
        now = now, zone = zone, today = today, modules = modules, fields = fields, readOnly = readOnly,
        photosLocked = locked, viewing = viewing, clock = { Format.timeOfDay(it, zone) },
    )

    // ---- Drawing

    private fun render(
        name: String,
        state: CareState,
        ctx: CareContext,
        dark: Boolean = false,
        palette: Palette = Palette.AURORA,
        bold: Boolean = false,
        banner: Boolean = false,
    ) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = palette, bold = bold) {
                Frame(ctx.readOnly) {
                    if (banner) ViewingBanner("Sam", onBack = {})
                    CareScreen(state, ctx, CareActions())
                }
            }
        }
        save(name)
    }

    @Composable
    private fun Frame(readOnly: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReadOnly provides readOnly) {
            Backdrop { Column(Modifier.fillMaxSize()) { content() } }
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

    private val loaded = CareState(today, data)

    // ---- The tab

    @Test
    fun care() = render("care-tab", loaded, ctx())

    @Test
    fun careDark() = render("care-tab-dark", loaded, ctx(), dark = true)

    @Test
    fun careOcean() = render("care-tab-ocean", loaded, ctx(), palette = Palette.OCEAN)

    @Test
    fun careVioletBoldDark() = render("care-tab-violet-bold-dark", loaded, ctx(locked = true), dark = true, palette = Palette.VIOLET, bold = true)

    /** Viewing someone's share: only what's shared, nothing to do, and no choosing. */
    @Test
    fun careViewing() = render(
        "care-tab-viewing", loaded,
        ctx(modules = setOf(Module.MEDICATIONS, Module.APPOINTMENTS, Module.CONTINENCE), readOnly = true, viewing = true),
        banner = true,
    )

    /** One part couldn't load: its card says so, and the rest carry on. */
    @Test
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    fun carePartly() = render(
        "care-tab-partly",
        CareState(
            today,
            data.copy(transition = null, voice = null, photos = null, day = day.copy(modules = day.modules.copy(transition = null))),
            failed = setOf(CarePart.TRANSITION, CarePart.VOICE),
        ),
        ctx(modules = setOf(Module.NUTRITION, Module.MEDICATIONS, Module.TRANSITION)),
    )

    /** Hormone therapy is part of Medications: its page links there. */
    @Test
    @Config(qualifiers = "w390dp-h1100dp-xxhdpi")
    fun medsLinksToHormoneTherapy() {
        compose.setContent {
            HealthTheme(mode = ThemeMode.LIGHT) {
                Frame(readOnly = false) {
                    MedsScreen(MedsState(today, day = day.modules.meds, meds = listOf(estradiol, spiro), loading = false), zone, MedsActions(hormones = {}))
                }
            }
        }
        save("care-tab-meds-hormones-link")
    }

    @Test
    @Config(qualifiers = "w390dp-h860dp-xxhdpi")
    fun careEmpty() = render("care-tab-empty", CareState(today, CareData(day = DaySummary(today.toString()))), ctx(modules = setOf(Module.ACTIVITY, Module.SLEEP, Module.BODY)))

    @Test
    @Config(qualifiers = "w390dp-h860dp-xxhdpi")
    fun careEmptyDark() = render("care-tab-empty-dark", CareState(today, CareData(day = DaySummary(today.toString()))), ctx(modules = setOf(Module.ACTIVITY, Module.SLEEP, Module.BODY)), dark = true)
}
