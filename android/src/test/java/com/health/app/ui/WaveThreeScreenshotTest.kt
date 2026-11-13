package com.health.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.health.api.ChecklistItem
import com.health.api.CommunicationCard
import com.health.api.Count
import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.Fields
import com.health.api.FocusKind
import com.health.api.FocusSession
import com.health.api.GenderMoment
import com.health.api.HairRemovalMethod
import com.health.api.HairRemovalSession
import com.health.api.Insight
import com.health.api.MeasurementSet
import com.health.api.Metric
import com.health.api.Milestone
import com.health.api.MilestoneArea
import com.health.api.MilestoneStatus
import com.health.api.Module
import com.health.api.MomentKind
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.NeuroSummary
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.api.Point
import com.health.api.Pose
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.RoutineStep
import com.health.api.Sample
import com.health.api.Sense
import com.health.api.SensePreference
import com.health.api.TransitionOverview
import com.health.api.TransitionProfile
import com.health.api.BodyChange
import com.health.api.Units
import com.health.api.VoiceSession
import com.health.app.data.Palette
import com.health.app.data.ThemeMode
import com.health.app.devices.healthconnect.ExportKind
import com.health.app.reminders.RunningFocus
import com.health.app.ui.modules.HubScreen
import com.health.app.ui.modules.body.MeasurementsScreen
import com.health.app.ui.modules.body.MeasurementsState
import com.health.app.ui.modules.neuro.AboutMeView
import com.health.app.ui.modules.neuro.CardFace
import com.health.app.ui.modules.neuro.EnergyEditor
import com.health.app.ui.modules.neuro.FocusActions
import com.health.app.ui.modules.neuro.FocusScreen
import com.health.app.ui.modules.neuro.MindNeuroCards
import com.health.app.ui.modules.neuro.NeuroActions
import com.health.app.ui.modules.neuro.NeuroHome
import com.health.app.ui.modules.neuro.NeuroState
import com.health.app.ui.modules.neuro.RoutineRunner
import com.health.app.ui.modules.photos.PhotoCompare
import com.health.app.ui.modules.photos.PhotoImage
import com.health.app.ui.modules.photos.PhotosActions
import com.health.app.ui.modules.photos.PhotosHome
import com.health.app.ui.modules.photos.PhotosState
import com.health.app.ui.modules.transition.PitchPoint
import com.health.app.ui.modules.transition.PitchTraceCard
import com.health.app.ui.modules.transition.TransitionActions
import com.health.app.ui.modules.transition.TransitionHome
import com.health.app.ui.modules.transition.TransitionState
import com.health.app.ui.settings.ExportActions
import com.health.app.ui.settings.ExportScreen
import com.health.app.ui.settings.ExportState
import com.health.app.ui.theme.CalmLayer
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
import kotlin.math.PI
import kotlin.math.sin

/** Autism & ADHD, transition, progress photos, measurements and Export, drawn with realistic data to PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WaveThreeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 12)
    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 14, 20)

    private fun render(dark: Boolean = false, calm: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HealthTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, palette = Palette.AURORA) {
                if (calm) CalmLayer(true) { content() } else content()
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        AppFrame(selected = "health", snackbar = remember { SnackbarHostState() }, onTab = {}, tabs = tabsFor(Module.entries.toSet()), content = content)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Autism & ADHD ----

    private val neuroFields = Fields.ALL.keys.filter { it.startsWith("neuro.") }.toSet() + Fields.MIND_ENERGY_BUDGET

    private val profile = NeuroProfile(
        identities = listOf("Autistic", "ADHD"),
        senses = listOf(
            SensePreference(Sense.SOUND, -2, "Sudden noises and lots of talking at once", listOf("ear defenders", "a quiet room")),
            SensePreference(Sense.TOUCH, 1, "Deep pressure is calming", listOf("weighted blanket")),
            SensePreference(Sense.LIGHT, -1, "Fluorescent lights", listOf("sunglasses")),
        ),
        communication = listOf("text over calls", "direct language", "time to process"),
        whatHelps = listOf("noise-cancelling headphones", "written instructions", "movement breaks"),
        accommodations = listOf("flexible start times", "cameras off on calls"),
        warningSigns = listOf("going quiet", "stimming more"),
        interests = listOf("trains", "botany"),
        stims = listOf("rocking", "fidget ring"),
        aboutMe = "If I'm overwhelmed, please give me a few quiet minutes and talk to me later. I'm OK.",
    )

    private val focus = listOf(
        FocusSession(at(today, 9), at(today, 9, 42), FocusKind.FOCUS, "Write the report", 25, rating = 4, id = "f1"),
        FocusSession(at(today.minusDays(1), 10), at(today.minusDays(1), 10, 20), FocusKind.BODY_DOUBLING, "Inbox", 15, id = "f2"),
        FocusSession(at(today.minusDays(2), 14), at(today.minusDays(2), 15, 5), FocusKind.FOCUS, "Tax return", 45, id = "f3"),
        FocusSession(at(today.minusDays(3), 11), at(today.minusDays(3), 11, 30), FocusKind.INTEREST, null, 20, id = "f4"),
    )

    private val routine = Routine(
        "Getting ready", listOf(RoutineStep("Shower", 10), RoutineStep("Get dressed", 5), RoutineStep("Breakfast", 15), RoutineStep("Pack bag", 5)),
        reminder = "07:30", days = listOf(1, 2, 3, 4, 5), id = "r1",
    )

    private val neuroState = NeuroState(
        profile = profile,
        summary = NeuroSummary(
            30, overloads = 4, byKind = listOf(Count("Overwhelm", 2), Count("Shutdown", 1), Count("Sensory overload", 1)),
            triggers = listOf(Count("noise", 3), Count("change of plans", 2)), whatHelped = listOf(Count("headphones", 3), Count("time alone", 2)),
            averageMasking = 3.6, focusMinutes = 640, estimateRatio = 1.4,
            patterns = listOf(Insight("info", "Short nights and hard days", "Overwhelm came on 3 of 6 days after under 6½ hours' sleep, against 1 of 24 other days. Rest may be part of what helps.", "sparkles", Module.NEURO)),
            burnoutRisk = "The last few weeks have asked a lot of you: more masking and less energy. Rest, fewer demands and support from people you trust can help.",
        ),
        events = listOf(OverloadEvent(at(today.minusDays(1), 17, 10), kind = OverloadKind.SHUTDOWN, intensity = 4, whatHelped = listOf("headphones", "dark room"), id = "e1")),
        energy = listOf(EnergyDay(today.toString(), 12, listOf(EnergySpend("work", 4), EnergySpend("phone call", 1), EnergySpend("stimming", -1)), socialBattery = 4)),
        checkIns = listOf(NeuroCheckIn(at(today, 12), masking = 4, socialLoad = 3, sensoryLoad = 4, burnout = 3, id = "c1")),
        focus = focus,
        routines = listOf(routine, Routine("Wind down", listOf(RoutineStep("Lights low"), RoutineStep("Phone away"), RoutineStep("Read")), reminder = "21:30", id = "r2")),
        runs = listOf(RoutineRun("r1", today.toString(), listOf(0, 1))),
        cards = listOf(
            CommunicationCard("I'm autistic and need a quiet minute.", "I'm OK. Please give me a little space and time.", "violet", "default-quiet"),
            CommunicationCard("I can't talk right now. Please text me.", null, "ocean", "default-text"),
            CommunicationCard("Too loud for me. Can we go somewhere quieter?", null, "ocean", "default-loud"),
        ),
        loading = false,
    )

    @Test
    fun neuroLight() {
        render { Frame { NeuroHome(neuroState, neuroFields, today, zone, false, NeuroActions()) } }
        save("neuro_light")
    }

    @Test
    fun neuroDark() {
        render(dark = true) { Frame { NeuroHome(neuroState, neuroFields, today, zone, false, NeuroActions()) } }
        save("neuro_dark")
    }

    @Test
    fun neuroDefaultsCalm() {
        // Only the default fields, with calm mode on: no glow, softer colours.
        render(calm = true) { Frame { NeuroHome(neuroState, Fields.DEFAULTS, today, zone, true, NeuroActions()) } }
        save("neuro_calm_defaults")
    }

    @Test
    fun communicationCard() {
        render { CardFace(neuroState.cards.first(), 0, 8, {}, {}, {}) }
        save("neuro_card")
    }

    @Test
    fun communicationCardAmberDark() {
        render(dark = true) { CardFace(CommunicationCard("Please give me instructions in writing.", "Written steps help me get them right.", "amber"), 4, 8, {}, {}, {}) }
        save("neuro_card_amber")
    }

    @Test
    fun aboutMe() {
        render { Frame { AboutMeView(profile, neuroFields) {} } }
        save("neuro_about_me")
    }

    @Test
    fun focusRunning() {
        render { Frame { FocusScreen(RunningFocus(at(today, 13, 52), FocusKind.FOCUS, "Write the report", 25, breaks = 1, chimeEveryMinutes = 25), focus, 1.4, true, now, false, FocusActions()) } }
        save("neuro_focus_running")
    }

    @Test
    fun focusSetupDark() {
        render(dark = true) { Frame { FocusScreen(null, focus, 1.4, true, now, false, FocusActions()) } }
        save("neuro_focus_dark")
    }

    @Test
    fun routineRunner() {
        render { Frame { RoutineRunner(routine, neuroState.runs.first(), now, now - 6 * 60_000L - 12_000, {}, {}, {}) } }
        save("neuro_routine_run")
    }

    @Test
    fun energyEditor() {
        render { Frame { EnergyEditor(neuroState.energy.first(), true, now, {}) {} } }
        save("neuro_energy")
    }

    @Test
    fun mindCards() {
        render { Frame { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { MindNeuroCards(true, true, neuroState.energy.first(), false, true) {} } } }
        save("mind_neuro_cards")
    }

    // ---- Transition ----

    private val transitionState = TransitionState(
        overview = TransitionOverview(
            profile = TransitionProfile("they/she", "2025-08-01", listOf("feel at home in my body", "a voice that feels like me"), 185.0),
            sinceStart = "1 year, 3 months", onHormones = "8 months",
            milestones = listOf(
                Milestone("Update my passport", MilestoneArea.LEGAL, MilestoneStatus.IN_PROGRESS, checklist = listOf(ChecklistItem("Check what's needed", true), ChecklistItem("Photo", true), ChecklistItem("Apply"), ChecklistItem("New passport arrives")), id = "m1"),
                Milestone("Voice lessons", MilestoneArea.VOICE, MilestoneStatus.PLANNED, date = "2026-12-02", id = "m2"),
                Milestone("Tell my family", MilestoneArea.SOCIAL, MilestoneStatus.DONE, date = "2026-03-14", id = "m3"),
            ),
            recentChanges = listOf(BodyChange("2026-11-02", "skin", "Softer, and fewer breakouts.", 5, "c1")),
            voice = (0 until 6).map { i -> VoiceSession(at(today.minusDays(30L * (5 - i)), 19), 15, listOf("resonance"), pitchAvgHz = 150.0 + i * 7, pitchMinHz = 120.0, pitchMaxHz = 210.0) },
            hairByArea = listOf(Count("face", 6), Count("neck", 2)),
            euphoria = 12, dysphoria = 3,
            highlights = listOf("12 moments of euphoria this month.", "Update my passport: 2 of 4 steps done.", "Next: Voice lessons, 2 Dec."),
            measurements = listOf(MeasurementSet(at(today.minusDays(60), 8), mapOf(Metric.WAIST to 84.0, Metric.HIPS to 98.0)), MeasurementSet(at(today, 8), mapOf(Metric.WAIST to 81.5, Metric.HIPS to 99.5))),
        ),
        changes = listOf(BodyChange("2026-11-02", "skin", "Softer, and fewer breakouts.", 5, "c1"), BodyChange("2026-10-20", "emotions", "Feeling things more fully.", 4, "c2")),
        hair = listOf(HairRemovalSession(at(today.minusDays(9), 16), HairRemovalMethod.LASER, "face", pain = 5, id = "h1")),
        moments = listOf(GenderMoment(at(today, 9), MomentKind.EUPHORIA, 5, listOf("being gendered right"), "The barista said “have a lovely day, love”.", "g1")),
        loading = false,
    )

    private val transitionFields = Fields.ALL.keys.filter { it.startsWith("transition.") }.toSet()

    @Test
    fun transitionLight() {
        render { Frame { TransitionHome(transitionState, transitionFields, Module.entries.toSet(), Units.METRIC, zone, TransitionActions()) } }
        save("transition_light")
    }

    @Test
    fun transitionDark() {
        render(dark = true) { Frame { TransitionHome(transitionState, transitionFields, Module.entries.toSet(), Units.IMPERIAL, zone, TransitionActions()) } }
        save("transition_dark")
    }

    @Test
    fun pitchTrace() {
        val trace = (0 until 120).map { i ->
            val t = now - (119 - i) * 100L
            PitchPoint(t, if (i in 50..58) null else 178.0 + 14 * sin(i / 8.0 * PI / 2) + (i % 7))
        }
        render { Frame { Column(Modifier.padding(16.dp)) { PitchTraceCard(trace, 183.0, 185.0, true) } } }
        save("transition_pitch")
    }

    // ---- Progress photos ----

    private val placeholder: PhotoImage = { photo, _, modifier ->
        val hue = (photo.id?.hashCode() ?: 0) and 0x3F
        Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFFB9A6FF - hue), Color(0xFF6A3FD6 + hue)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Person, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(64.dp)) }
    }

    private val photos = (0 until 7).map { i ->
        Photo(
            album = if (i < 5) "Transition" else "Strength", pose = if (i % 2 == 0) Pose.FRONT else Pose.SIDE, at = at(today.minusDays(30L * (6 - i)), 8),
            id = "p$i", date = today.minusDays(30L * (6 - i)).toString(), width = 3, height = 4, weightKg = 68.0 - i * 0.6, onHormones = "${2 + i} months",
        )
    }.reversed()

    @Test
    fun photosHome() {
        render { Frame { PhotosHome(PhotosState(photos, listOf(PhotoAlbum("Transition", 5, today.toString()), PhotoAlbum("Strength", 2)), loading = false), null, {}, placeholder, PhotosActions()) } }
        save("photos_light")
    }

    @Test
    fun photosCompareDark() {
        val transition = photos.filter { it.album == "Transition" && it.pose == Pose.FRONT }
        render(dark = true) { Frame { PhotoCompare(transition, Units.METRIC, zone, placeholder, null, null, {}) } }
        save("photos_compare_dark")
    }

    @Test
    fun photosCompareSlider() {
        val transition = photos.filter { it.album == "Transition" && it.pose == Pose.FRONT }
        render { Frame { PhotoCompare(transition, Units.IMPERIAL, zone, placeholder, null, null, {}, initialSlider = true) } }
        save("photos_compare_slider")
    }

    // ---- Measurements, Export, the hub ----

    @Test
    fun measurements() {
        val latest = mapOf(
            Metric.WAIST to Sample(Metric.WAIST, at(today, 8), value = 81.5), Metric.HIPS to Sample(Metric.HIPS, at(today, 8), value = 99.5),
            Metric.CHEST to Sample(Metric.CHEST, at(today.minusDays(14), 8), value = 92.0),
        )
        val series = mapOf(
            Metric.WAIST to (0..8).map { Point(at(today.minusDays(40L * (8 - it)), 8), 86.0 - it * 0.55) },
            Metric.HIPS to (0..8).map { Point(at(today.minusDays(40L * (8 - it)), 8), 97.0 + it * 0.3) },
        )
        render { Frame { MeasurementsScreen(MeasurementsState(latest, series, loading = false), Units.METRIC, zone, {}) {} } }
        save("measurements_light")
    }

    @Test
    fun exportLight() {
        render { Frame { ExportScreen(ExportState(true, setOf(ExportKind.WEIGHT, ExportKind.NUTRITION), setOf(ExportKind.WEIGHT), "Wrote 12 to Health Connect"), ExportActions(), today) } }
        save("export_light")
    }

    @Test
    fun exportDark() {
        render(dark = true) { Frame { ExportScreen(ExportState(true, emptySet(), emptySet(), null), ExportActions(), today) } }
        save("export_dark")
    }

    @Test
    fun hubWithEverything() {
        render { Frame { HubScreen(Module.entries.toSet(), false, showMeasurements = true) {} } }
        save("hub_wave3")
    }
}
