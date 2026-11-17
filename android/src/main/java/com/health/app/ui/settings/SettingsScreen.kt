@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.health.api.AiStatus
import com.health.api.EnergyProfile
import com.health.api.Fields
import com.health.api.Goals
import com.health.api.Layouts
import com.health.api.MealieStatus
import com.health.api.Module
import com.health.api.Profile
import com.health.api.Units
import com.health.app.attempt
import com.health.app.data.Palette
import com.health.app.data.Settings
import com.health.app.data.ThemeMode
import com.health.app.net.aiStatus
import com.health.app.net.mealieStatus
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.MenuGroup
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.ValueRow
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.screenLayout
import com.health.app.ui.components.showsField
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.NavRow
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Settings, as short pages opened from More: You & goals, What you're tracking, Reminders &
 * sharing, Connections & privacy, Appearance and Account. Each is one screen's worth; the pages
 * further in (reminders, sharing, Mealie, the watch…) open from their rows. Settings' sections were
 * once arranged under android.settings; with a page each, nothing here is arranged any more, and
 * those ids are left as they were (never used for anything else).
 */

class SettingsActions(
    val saveProfile: (Profile) -> Unit = {},
    val updateSettings: ((Settings) -> Settings) -> Unit = {},
    val signOut: () -> Unit = {},
    /** Shows or hides water (a field), keeping the person's other choices. */
    val setWater: (Boolean) -> Unit = {},
    /** Puts every arranged screen back as it was (both apps). */
    val resetScreens: () -> Unit = {},
    /** Opens a part of the server's web settings in the browser, by its anchor ("connector", "account"). */
    val openWeb: (String) -> Unit = {},
)

/** The Settings pages, in More's order, each with its route, its name and what it holds. */
enum class SettingsPage(val route: String, val title: String, val about: String, val icon: ImageVector) {
    YOU("settings/you", "You & goals", "Your body, units and what you aim for", Icons.Rounded.Person),
    TRACKING("settings/tracking", "What you're tracking", "Conditions, features and fields", Icons.Rounded.Tune),
    REMINDERS("settings/reminders-sharing", "Reminders & sharing", "Reminders, the weekly digest and sharing", Icons.Rounded.Notifications),
    CONNECTIONS("settings/connections", "Connections & privacy", "Where your data goes, syncing and other apps", Icons.Rounded.Shield),
    APPEARANCE("settings/appearance", "Appearance", "Theme, bold text and arranged screens", Icons.Rounded.Palette),
    ACCOUNT("settings/account", "Account", "Who's signed in, and signing out", Icons.Rounded.AccountCircle),
}

@Composable
fun SettingsRoute(page: SettingsPage, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.collectAsState()
    val profile by c.profile.collectAsState()
    val layout by c.layout.collectAsState()
    // The assistant and Mealie are the person's own, and never asked about while viewing a share.
    val viewing by c.viewing.collectAsState()
    var ai by remember { mutableStateOf<AiStatus?>(null) }
    var mealie by remember { mutableStateOf<MealieStatus?>(null) }
    LaunchedEffect(page, viewing) {
        if (page == SettingsPage.CONNECTIONS && viewing == null) {
            ai = attempt { c.api.aiStatus() }.getOrNull()
            mealie = attempt { c.api.mealieStatus() }.getOrNull()
        }
    }
    SettingsScreen(
        page, settings, profile,
        SettingsActions(
            saveProfile = { p -> scope.launch { attempt { c.saveProfile(p) }.onFailure { c.tell(it.message ?: "Couldn't save that") } } },
            updateSettings = { t -> scope.launch { c.prefs.update(t) } },
            signOut = { scope.launch { c.signOut() } },
            setWater = { on -> scope.launch { attempt { WaterSetting.save(c, on) }.onFailure { c.tell(it.message ?: "Couldn't save that") } } },
            resetScreens = {
                scope.launch {
                    attempt { c.resetLayouts() }.onSuccess { c.tell("Every screen is back as it was") }.onFailure { c.tell(it.message ?: "Couldn't put them back") }
                }
            },
            openWeb = { anchor ->
                attempt { context.startActivity(Intent(Intent.ACTION_VIEW, "${settings.server.trimEnd('/')}/settings#$anchor".toUri())) }
            },
        ),
        onBack = onBack,
        onOpen = onOpen,
        water = showsField(Fields.NUTRITION_WATER),
        micros = com.health.app.ui.food.showsMicros(),
        ai = ai,
        mealie = mealie,
        hiddenFromHealth = screenLayout(Layouts.NAV)?.hidden.orEmpty(),
        arrangedScreens = layout.screens.size,
    )
}

/** Which dialog is open: a field of the profile or goals. */
private enum class Edit { BIRTH_YEAR, HEIGHT, ENERGY, STEPS, ACTIVE, SLEEP, CALORIES, PROTEIN, WATER, WORKOUTS, TARGET_WEIGHT, WEEKLY }

/**
 * One Settings page. [water] is whether water is shown; [ai] the assistant's status (null until
 * known, or without one); [mealie] whether Mealie is connected (null until known);
 * [hiddenFromHealth] the features hidden from the Health tab, which open from What you're tracking
 * instead; [arrangedScreens] how many screens the person has arranged.
 */
@Composable
fun SettingsScreen(
    page: SettingsPage,
    settings: Settings,
    profile: Profile?,
    actions: SettingsActions,
    onBack: (() -> Unit)? = null,
    onOpen: (String) -> Unit = {},
    water: Boolean = true,
    ai: AiStatus? = null,
    hiddenFromHealth: Set<String> = emptySet(),
    /** Whether vitamins and minerals show (their field, never in eating disorder recovery): their targets are under Goals. */
    micros: Boolean = false,
    mealie: MealieStatus? = null,
    arrangedScreens: Int = 0,
) {
    var editing by remember { mutableStateOf<Edit?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val units = profile?.units ?: settings.units
    val food = profile?.modules?.contains(Module.NUTRITION) == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, page.title)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (page) {
                SettingsPage.YOU -> if (profile == null) Loading() else {
                    YouSection(profile, units, actions) { editing = it }
                    GoalsSection(profile.goals, units, water && food, micros && food, onOpen) { editing = it }
                }
                SettingsPage.TRACKING -> {
                    Hint("Your conditions and goals decide what the app shows, so you see what helps you and nothing else.", Modifier.padding(horizontal = 4.dp))
                    MenuGroup(null) {
                        NavRow(Icons.Rounded.MedicalInformation, "Your conditions and goals", "What you live with or want to keep track of", onClick = { onOpen("settings/conditions") })
                        NavRow(Icons.Rounded.EditNote, "Fields", "The details each form asks for", onClick = { onOpen("settings/fields") })
                    }
                    if (profile == null) Loading() else FeaturesSection(profile, water, hiddenFromHealth, actions, onOpen)
                }
                SettingsPage.REMINDERS -> MenuGroup(null) {
                    NavRow(Icons.Rounded.Notifications, "Reminders", "Doses, refills, habits and appointments", onClick = { onOpen("settings/reminders") })
                    NavRow(Icons.Rounded.Summarize, "Weekly digest", "When Your week comes, and by email", onClick = { onOpen(DIGEST_SETTINGS) })
                    NavRow(Icons.Rounded.Group, "Sharing", "Let someone you trust see what you choose", onClick = { onOpen("settings/sharing") })
                }
                SettingsPage.CONNECTIONS -> ConnectionsSection(settings, ai, mealie, food, actions, onOpen)
                SettingsPage.APPEARANCE -> {
                    AppearanceSection(settings, actions)
                    SectionCard("Arranged screens") {
                        Text(
                            "Each page with an Arrange button at the top can be arranged: move what matters up, and hide what you don't use. " +
                                "Today, its quick actions and Health are arranged the same way on the web.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(if (arrangedScreens == 0) "Nothing arranged yet." else "$arrangedScreens ${if (arrangedScreens == 1) "screen" else "screens"} arranged.")
                        OutlinedButton(enabled = arrangedScreens > 0 && !LocalReadOnly.current, onClick = { confirmReset = true }) { Text("Put every screen back as it was") }
                    }
                }
                SettingsPage.ACCOUNT -> {
                    SectionCard("Signed in") {
                        Text(settings.user?.displayName ?: "", style = MaterialTheme.typography.titleMedium)
                        Hint("${settings.user?.username ?: ""} on ${settings.serverName.ifBlank { settings.server }}")
                        Hint(settings.server)
                    }
                    MenuGroup(null) {
                        NavRow(Icons.Rounded.Key, "Password, sign-ins and upload keys", "On your server's web page", onClick = { actions.openWeb("account") })
                    }
                    OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("The app forgets this server and your paired ring here. What's on the server stays.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; actions.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Put every screen back?") },
            text = { Text("Every screen you've arranged goes back to its first order, with nothing hidden, here and on the web.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; actions.resetScreens() }) { Text("Put them back") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    EditDialogs(profile, units, editing, actions) { editing = null }
}

/** Where the weekly digest's settings open (Reminders & sharing › Weekly digest). */
const val DIGEST_SETTINGS = "settings/digest"

@Composable
private fun YouSection(profile: Profile, units: Units, actions: SettingsActions, edit: (Edit) -> Unit) {
    SectionCard("You") {
        ValueRow("Born in", profile.birthYear?.toString() ?: "Not set", onClick = { edit(Edit.BIRTH_YEAR) })
        ValueRow("Height", profile.heightCm?.let { Format.height(it, units) } ?: "Not set", onClick = { edit(Edit.HEIGHT) })
        ValueRow(
            "Calorie estimates use", profile.energyProfile.label,
            subtitle = "The formula for the energy your body uses at rest",
            onClick = { edit(Edit.ENERGY) },
        )
        Text("Units", style = MaterialTheme.typography.bodyLarge)
        SegmentedChoice(Units.entries, profile.units, { if (it == Units.METRIC) "Metric" else "Imperial" }, { actions.saveProfile(profile.copy(units = it)) })
        val phone = ZoneId.systemDefault().id
        ValueRow("Time zone", profile.timezone.ifBlank { phone }, subtitle = "When your days start and end")
        if (profile.timezone.isNotBlank() && profile.timezone != phone) {
            OutlinedButton(onClick = { actions.saveProfile(profile.copy(timezone = phone)) }) { Text("Use this phone's ($phone)") }
        }
    }
}

/** The features to turn on, with water under Food and the photo lock under photos; a hidden feature opens from here. */
@Composable
private fun FeaturesSection(profile: Profile, water: Boolean, hiddenFromHealth: Set<String>, actions: SettingsActions, onOpen: (String) -> Unit) {
    SectionCard("Features") {
        Hint("Turn on what's useful to you. What's off is hidden, and nothing you've logged is lost.")
        Module.entries.forEach { m ->
            val on = m in profile.modules
            val route = ModuleInfo.route(m)
            val hidden = on && route != null && ModuleInfo.navId(m) in hiddenFromHealth
            FeatureRow(
                m, on, hidden,
                onToggle = { v -> actions.saveProfile(profile.copy(modules = if (v) profile.modules + m else profile.modules - m)) },
                onOpen = route?.takeIf { hidden }?.let { r -> { onOpen(r) } },
            )
            if (m == Module.NUTRITION && on) {
                LabeledSwitch(
                    "Water", water, actions.setWater,
                    subtitle = "Its goal, and logging it on Today and in Food",
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            if (m == Module.PHOTOS && on) PhotoLockSwitch(Modifier.padding(start = 16.dp))
        }
        val sensitive = Module.entries.filter { it.sensitive }.map { it.label }
        Hint(
            sensitive.dropLast(1).joinToString(", ") + " and ${sensitive.last()} are encrypted on your server, and an AI connector " +
                "link only sees them if you allow it when making the link.",
        )
    }
}

/** A feature's switch; one hidden from the Health tab says so, and has a way to open it. */
@Composable
private fun FeatureRow(m: Module, on: Boolean, hidden: Boolean, onToggle: (Boolean) -> Unit, onOpen: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (hidden) "Hidden from the Health tab" else ModuleInfo.description(m),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open ${m.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

@Composable
private fun GoalsSection(g: Goals, units: Units, water: Boolean, micros: Boolean, onOpen: (String) -> Unit, edit: (Edit) -> Unit) {
    SectionCard("Goals") {
        ValueRow("Steps", Format.number(g.steps), onClick = { edit(Edit.STEPS) })
        ValueRow("Active calories", "${Format.number(g.activeCalories)} kcal", onClick = { edit(Edit.ACTIVE) })
        ValueRow("Sleep", Format.minutes(g.sleepMinutes), onClick = { edit(Edit.SLEEP) })
        ValueRow("Workouts", "${g.workoutsPerWeek} a week", onClick = { edit(Edit.WORKOUTS) })
        ValueRow("Calories to eat", g.calories?.let { "${Format.number(it)} kcal" } ?: "Automatic", subtitle = "Automatic follows what you burn and your weight goal", onClick = { edit(Edit.CALORIES) })
        ValueRow("Protein", g.protein?.let { "$it g" } ?: "Automatic", onClick = { edit(Edit.PROTEIN) })
        if (water) ValueRow("Water", Format.water(g.waterMl, units), onClick = { edit(Edit.WATER) })
        ValueRow("Goal weight", g.targetWeightKg?.let { Format.weight(it, units) } ?: "Not set", onClick = { edit(Edit.TARGET_WEIGHT) })
        ValueRow("Weight change", weeklyLabel(g.weeklyChangeKg, units), onClick = { edit(Edit.WEEKLY) })
        if (micros) NavRow(Icons.Rounded.Eco, "Vitamins & minerals", "Fibre, sodium, iron and the rest: reference intakes, or your own", onClick = { onOpen("settings/nutrients") })
    }
}

/**
 * Connections & privacy: Where your data goes first, then syncing from this phone, then the apps
 * and services the server or this phone talks to: Mealie (with Food on), the AI assistant's
 * settings (where the server offers it), AI connector links, webhooks and Home Assistant, the
 * Pebble watch, and sending to other apps.
 */
@Composable
private fun ConnectionsSection(settings: Settings, ai: AiStatus?, mealie: MealieStatus?, food: Boolean, actions: SettingsActions, onOpen: (String) -> Unit) {
    MenuGroup(null) {
        NavRow(Icons.Rounded.Shield, WhereDataGoes.TITLE, "Everything that can leave your server, and pausing it", onClick = { onOpen(WhereDataGoes.ROUTE) })
    }
    SectionCard("Syncing") {
        LabeledSwitch(
            "Sync in the background", settings.backgroundSync, { v -> actions.updateSettings { it.copy(backgroundSync = v) } },
            subtitle = "About once an hour, your ring, Health Connect and Gadgetbridge send what's new to your server.",
        )
        LabeledSwitch(
            "Only on Wi-Fi", settings.syncOnWifiOnly, { v -> actions.updateSettings { it.copy(syncOnWifiOnly = v) } },
            enabled = settings.backgroundSync,
            subtitle = "Background syncs wait for Wi-Fi. Opening the app still syncs.",
        )
    }
    MenuGroup("Apps and services") {
        if (food) NavRow(Icons.AutoMirrored.Rounded.MenuBook, "Mealie", com.health.app.ui.food.MealieText.row(mealie), onClick = { onOpen("settings/mealie") })
        if (ai?.available == true) NavRow(Icons.Rounded.AutoAwesome, "AI assistant", com.health.app.ui.ai.AiText.row(ai), onClick = { onOpen("settings/ai") })
        NavRow(Icons.Rounded.Link, "AI connector links", "Made on your server's web page", onClick = { actions.openWeb("connector") })
        NavRow(Icons.Rounded.Hub, INTEGRATIONS, "Events sent where you choose, and today's numbers", onClick = { onOpen("settings/integrations") })
        NavRow(Icons.Rounded.Watch, "Pebble watch", "Today's scores and one-tap logging on your wrist", onClick = { onOpen("settings/watch") })
        NavRow(Icons.Rounded.FileUpload, SEND_TO_APPS, "Health Connect, FIT files, CSV and FHIR", onClick = { onOpen("settings/export") })
    }
}

/** The names of two pages, said the same wherever they're mentioned. */
const val INTEGRATIONS = "Webhooks & Home Assistant"
const val SEND_TO_APPS = "Send to other apps"

@Composable
private fun AppearanceSection(settings: Settings, actions: SettingsActions) {
    SectionCard(null) {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        val wallpaperOn = settings.wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.entries.forEach { palette ->
                FilterChip(
                    selected = palette == settings.palette,
                    enabled = !wallpaperOn,
                    onClick = { actions.updateSettings { it.copy(palette = palette) } },
                    label = { Text(palette.label) },
                    leadingIcon = {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(palette.swatchA), Color(palette.swatchB)))))
                    },
                )
            }
        }
        if (wallpaperOn) Hint("Your wallpaper's colours are in use.")
        Text("Style", style = MaterialTheme.typography.bodyLarge)
        SegmentedChoice(ThemeMode.entries, settings.themeMode, { it.label }, { mode -> actions.updateSettings { it.copy(themeMode = mode) } })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LabeledSwitch(
                "Wallpaper colours", settings.wallpaperColors, { v -> actions.updateSettings { it.copy(wallpaperColors = v) } },
                subtitle = "Use your phone's wallpaper colours instead of the theme's.",
            )
        }
        LabeledSwitch(
            "Bold text", settings.boldText, { v -> actions.updateSettings { it.copy(boldText = v) } },
            subtitle = "All text in Lexend Bold, the way your phone's Bold text setting draws it.",
        )
        CalmModeSwitch()
    }
}

@Composable
private fun EditDialogs(profile: Profile?, units: Units, editing: Edit?, actions: SettingsActions, close: () -> Unit) {
    val p = profile ?: return
    val g = p.goals
    fun goals(change: (Goals) -> Goals) = actions.saveProfile(p.copy(goals = change(g)))
    when (editing) {
        Edit.BIRTH_YEAR -> NumberDialog("Born in", p.birthYear?.toString(), null, valid = { it in 1900.0..2020.0 }, close) { v -> actions.saveProfile(p.copy(birthYear = v?.roundToInt())); close() }
        Edit.HEIGHT -> HeightDialog(p.heightCm, units, close) { cm -> actions.saveProfile(p.copy(heightCm = cm)); close() }
        Edit.ENERGY -> EnergyDialog(p.energyProfile, close) { e -> actions.saveProfile(p.copy(energyProfile = e)); close() }
        Edit.STEPS -> NumberDialog("Daily steps", g.steps.toString(), "steps", valid = { it in 500.0..60_000.0 }, close) { v -> goals { it.copy(steps = v!!.roundToInt()) }; close() }
        Edit.ACTIVE -> NumberDialog("Active calories", g.activeCalories.toString(), "kcal", valid = { it in 50.0..5_000.0 }, close) { v -> goals { it.copy(activeCalories = v!!.roundToInt()) }; close() }
        Edit.SLEEP -> NumberDialog("Sleep", Format.compact(g.sleepMinutes / 60.0), "hours", valid = { it in 4.0..12.0 }, close) { v -> goals { it.copy(sleepMinutes = (v!! * 60).roundToInt()) }; close() }
        Edit.WORKOUTS -> NumberDialog("Workouts a week", g.workoutsPerWeek.toString(), null, valid = { it in 0.0..21.0 }, close) { v -> goals { it.copy(workoutsPerWeek = v!!.roundToInt()) }; close() }
        Edit.CALORIES -> NumberDialog("Calories to eat", g.calories?.toString(), "kcal", valid = { it in 800.0..8_000.0 }, close, automatic = true) { v -> goals { it.copy(calories = v?.roundToInt()) }; close() }
        Edit.PROTEIN -> NumberDialog("Protein", g.protein?.toString(), "g", valid = { it in 10.0..500.0 }, close, automatic = true) { v -> goals { it.copy(protein = v?.roundToInt()) }; close() }
        Edit.WATER -> {
            val shown = if (units == Units.IMPERIAL) (g.waterMl / Format.ML_PER_FL_OZ).roundToInt().toString() else g.waterMl.toString()
            NumberDialog("Water", shown, if (units == Units.IMPERIAL) "fl oz" else "ml", valid = { it > 0 }, close) { v ->
                val ml = if (units == Units.IMPERIAL) v!! * Format.ML_PER_FL_OZ else v!!
                if (ml in 250.0..8_000.0) goals { it.copy(waterMl = ml.roundToInt()) }
                close()
            }
        }
        Edit.TARGET_WEIGHT -> NumberDialog(
            "Goal weight", g.targetWeightKg?.let { Format.compact(Format.weightValue(it, units)) }, Format.weightUnit(units),
            valid = { Format.weightToKg(it, units) in 25.0..350.0 }, close, automatic = true, automaticLabel = "No goal",
        ) { v -> goals { it.copy(targetWeightKg = v?.let { x -> Format.weightToKg(x, units) }) }; close() }
        Edit.WEEKLY -> WeeklyDialog(g.weeklyChangeKg, units, close) { kg -> goals { it.copy(weeklyChangeKg = kg) }; close() }
        null -> Unit
    }
}

private fun weeklyLabel(kg: Double, units: Units): String {
    val amount = "${Format.upTo2(Format.weightValue(kotlin.math.abs(kg), units))} ${Format.weightUnit(units)}"
    return when {
        kg == 0.0 -> "Stay where I am"
        kg < 0 -> "Lose $amount a week"
        else -> "Gain $amount a week"
    }
}

/** A number to type, or (with [automatic]) nothing, which the server works out. */
@Composable
private fun NumberDialog(
    title: String,
    initial: String?,
    suffix: String?,
    valid: (Double) -> Boolean,
    onDismiss: () -> Unit,
    automatic: Boolean = false,
    automaticLabel: String = "Automatic",
    onSave: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    val value = Format.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField(text, { text = it }, title, suffix = suffix) },
        confirmButton = { TextButton(enabled = value != null && valid(value), onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (automatic) TextButton(onClick = { onSave(null) }) { Text(automaticLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun HeightDialog(cm: Double?, units: Units, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    val inches = cm?.let { it / Format.CM_PER_INCH }
    var a by remember { mutableStateOf(if (units == Units.IMPERIAL) inches?.let { (it / 12).toInt().toString() }.orEmpty() else cm?.roundToInt()?.toString().orEmpty()) }
    var b by remember { mutableStateOf(if (units == Units.IMPERIAL) inches?.let { (it % 12).roundToInt().toString() }.orEmpty() else "") }
    val result = if (units == Units.IMPERIAL) {
        a.trim().toIntOrNull()?.let { ft -> Format.heightToCm(ft, Format.parse(b) ?: 0.0) }
    } else Format.parse(a)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Height") },
        text = {
            if (units == Units.IMPERIAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(a, { a = it }, "Feet", Modifier.weight(1f), decimals = false)
                    NumberField(b, { b = it }, "Inches", Modifier.weight(1f))
                }
            } else {
                NumberField(a, { a = it }, "Height", suffix = "cm")
            }
        },
        confirmButton = { TextButton(enabled = result != null && result in 100.0..250.0, onClick = { onSave(result!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EnergyDialog(current: EnergyProfile, onDismiss: () -> Unit, onSave: (EnergyProfile) -> Unit) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calorie estimates use") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Hint("Resting energy is estimated with the Mifflin–St Jeor formula, which has a female and a male version. Pick whichever fits your body best (hormones and body composition matter more than anything else here), or the average of the two.")
                EnergyProfile.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).selectable(choice == option, role = Role.RadioButton) { choice = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == option, onClick = null)
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WeeklyDialog(current: Double, units: Units, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    val options = listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5)
    var choice by remember { mutableDoubleStateOf(options.minByOrNull { kotlin.math.abs(it - current) } ?: 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight change") },
        text = {
            Column {
                options.forEach { kg ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { choice = kg }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == kg, onClick = { choice = kg })
                        Text(weeklyLabel(kg, units), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Hint("Automatic calorie targets follow this. Slow and steady is easier to keep up.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
