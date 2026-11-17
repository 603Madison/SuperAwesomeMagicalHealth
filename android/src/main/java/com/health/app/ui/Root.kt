package com.health.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.health.api.JournalKind
import com.health.api.Meal
import com.health.api.Screener
import com.health.app.attempt
import com.health.app.ui.ai.AiAuditRoute
import com.health.app.ui.ai.AiRoute
import com.health.app.ui.ai.AiSettingsRoute
import com.health.app.ui.care.AppointmentEditorRoute
import com.health.app.ui.care.AppointmentsRoute
import com.health.app.ui.care.DigestRoute
import com.health.app.ui.care.DigestSettingsRoute
import com.health.app.ui.care.HormonesRoute
import com.health.app.ui.care.IntegrationsRoute
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.care.RemindersRoute
import com.health.app.ui.care.ReportsRoute
import com.health.app.ui.care.SharingRoute
import com.health.app.ui.care.ViewingBanner
import com.health.app.ui.care.WatchRoute
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.LocalBack
import com.health.app.ui.components.appContainer
import com.health.app.ui.devices.CompareRoute
import com.health.app.ui.devices.DevicesRoute
import com.health.app.ui.devices.RingPairRoute
import com.health.app.ui.devices.StrapPairRoute
import com.health.app.ui.food.AddFoodRoute
import com.health.app.ui.food.FoodRoute
import com.health.app.ui.modules.HubRoute
import com.health.app.ui.modules.continence.ContinenceRoute
import com.health.app.ui.modules.continence.PelvicFloorRoute
import com.health.app.ui.modules.cycle.CycleRoute
import com.health.app.ui.modules.meds.MedEditorRoute
import com.health.app.ui.modules.meds.MedsRoute
import com.health.app.ui.modules.mind.CalmToolRoute
import com.health.app.ui.modules.mind.JournalEditorRoute
import com.health.app.ui.modules.mind.JournalRoute
import com.health.app.ui.modules.mind.MindRoute
import com.health.app.ui.modules.mind.ScreenerRoute
import com.health.app.ui.more.MoreRoute
import com.health.app.ui.recent.RecentRoute
import com.health.app.ui.settings.DIGEST_SETTINGS
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.SettingsRoute
import com.health.app.ui.settings.WhereDataGoes
import com.health.app.ui.settings.WhereDataGoesRoute
import com.health.app.ui.today.TodayRoute
import com.health.app.ui.tracking.HabitsRoute
import com.health.app.ui.tracking.MonitorPairRoute
import com.health.app.ui.tracking.SymptomsRoute
import com.health.app.ui.tracking.VitalsRoute
import com.health.app.ui.trends.TrendsRoute
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/*
 * The app's frame and its map. Five tabs, always the same whatever's turned on: Today, 24 hours,
 * Health (every feature), Trends and More (records, devices, the assistant and Settings). Every
 * other page sits under one of them and has a way back; a link to a tab's own page switches to
 * that tab rather than stacking a second copy.
 */

data class Tab(val route: String, val label: String, val icon: ImageVector)

/** The five tabs along the bottom. */
val TABS: List<Tab> = listOf(
    Tab("today", "Today", Icons.Rounded.Today),
    Tab("recent", "24 hours", Icons.Rounded.History),
    Tab("health", "Health", Icons.Rounded.Favorite),
    Tab("trends", "Trends", Icons.AutoMirrored.Rounded.ShowChart),
    Tab("more", "More", Icons.Rounded.Menu),
)

/** The first part of a route ("settings" of "settings/you", "recent" of "recent?x=1"). */
private fun rootOf(route: String): String = route.substringBefore('?').substringBefore('/')

/** The pages that sit under More: records, devices, the assistant and Settings. The rest sit under Health. */
private val UNDER_MORE = setOf("more", "alerts", "digest", "reports", "devices", "settings", "assistant")

/**
 * Which tab a route belongs to, for the tab bar to light: a tab's own page, More's pages (Health
 * alerts, Your week, Reports & export, Devices and what's in them, the assistant, Settings), Trends,
 * and everything else (each feature and its pages) under Health.
 */
fun tabOf(route: String?): String? {
    val root = route?.let(::rootOf) ?: return null
    return when {
        TABS.any { it.route == root } -> root
        root in UNDER_MORE -> "more"
        else -> "health"
    }
}

/** Whether [route] is one of the tabs' own pages (which a link switches to, never stacks). */
fun isTab(route: String): Boolean = TABS.any { it.route == route.substringBefore('?') }

/** Where an older link lands now: Settings was a tab and is now in More. */
fun current(route: String): String = if (route == "settings") "more" else route

/** The meal it probably is now, for "Log food" from Today. */
fun mealNow(time: LocalTime = LocalTime.now()): Meal = when (time.hour) {
    in 4..10 -> Meal.BREAKFAST
    in 11..14 -> Meal.LUNCH
    in 17..21 -> Meal.DINNER
    else -> Meal.SNACK
}

/**
 * Opens [route]: a tab's own page by switching to that tab (its place kept, as a tap on the tab
 * does), anything else on top of where the person is. A page the app doesn't have (yet) does nothing.
 */
private fun NavHostController.go(route: String) {
    val to = current(route)
    attempt {
        if (isTab(to)) {
            navigate(to) {
                popUpTo("today") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            navigate(to) { launchSingleTop = true }
        }
    }
}

@Composable
fun MainScaffold(open: String? = null, onOpened: () -> Unit = {}) {
    val c = appContainer()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        c.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, m.action, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
    // Viewing someone's share: their shared features, read-only, with a way back.
    val viewing by c.viewing.collectAsState()
    // Health alerts unseen and not put away (the person's own): on the More tab, and its row there.
    val unseenAlerts by c.unseenAlerts.collectAsState()
    val alerts = if (viewing == null) unseenAlerts else 0
    val entry by nav.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    // A notification asked for a page.
    LaunchedEffect(open) {
        if (open != null) {
            nav.go(open)
            onOpened()
        }
    }
    CompositionLocalProvider(LocalReadOnly provides (viewing != null)) {
        AppFrame(selected = tabOf(entry?.destination?.route), snackbar = snackbar, onTab = { tab -> nav.go(tab) }, badges = mapOf("more" to alerts)) {
            Column(Modifier.fillMaxSize()) {
                viewing?.let { share ->
                    ViewingBanner(share.ownerName, onBack = {
                        scope.launch { c.view(null) }
                        nav.navigate("today") { popUpTo("today") { inclusive = true } }
                    })
                }
                Box(Modifier.weight(1f)) { Routes(nav, alerts) }
            }
        }
    }
}

/**
 * The app around its pages: the aurora backdrop, the five tabs along the bottom (each with a count
 * from [badges] by its route, shown above 0), and the snackbar.
 */
@Composable
fun AppFrame(selected: String?, snackbar: SnackbarHostState, onTab: (String) -> Unit, badges: Map<String, Int> = emptyMap(), content: @Composable () -> Unit) {
    Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab.route,
                            onClick = { onTab(tab.route) },
                            icon = {
                                val count = badges[tab.route] ?: 0
                                if (count > 0) {
                                    BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text(if (count > 99) "99+" else "$count") } }) {
                                        Icon(tab.icon, com.health.app.ui.alerts.Alerts.countText(count))
                                    }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }
}

/** A page within a tab: its header gets a way back ([LocalBack]). */
private fun NavGraphBuilder.page(
    route: String,
    nav: NavHostController,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    CompositionLocalProvider(LocalBack provides { nav.popBackStack() }) { content(entry) }
}

private fun arg(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }

private fun required(name: String) = navArgument(name) { type = NavType.StringType }

@Composable
private fun Routes(nav: NavHostController, alerts: Int) {
    fun open(route: String) = nav.go(route)
    fun back() { nav.popBackStack() }
    NavHost(nav, startDestination = "today") {
        // ---- The five tabs' own pages: no way back, they're where back leads.
        composable("today") {
            TodayRoute(
                onLogFood = { date -> nav.navigate("food/add/$date/${mealNow().name}") },
                onOpenDevices = { open("devices") },
                onOpen = ::open,
            )
        }
        composable("recent") { RecentRoute(device = null) }
        composable("health") { HubRoute(onOpen = ::open) }
        composable("trends") { TrendsRoute(onRecent = { open("recent") }) }
        composable("more") { MoreRoute(onOpen = ::open, unseenAlerts = alerts) }

        // ---- Food (in Health).
        page("food", nav) { FoodRoute(onAdd = { date, meal -> nav.navigate("food/add/$date/${meal.name}") }, onPrivacy = { open(WhereDataGoes.ROUTE) }) }
        page("food/add/{date}/{meal}", nav, listOf(required("date"), required("meal"))) { back ->
            val date = attempt { LocalDate.parse(back.arguments?.getString("date")) }.getOrElse { LocalDate.now() }
            val meal = Meal.entries.firstOrNull { it.name == back.arguments?.getString("meal") } ?: Meal.SNACK
            AddFoodRoute(date, meal, onDone = ::back, onPrivacy = { open(WhereDataGoes.ROUTE) })
        }

        // ---- Devices (in More): pairing, a device's last 24 hours, and comparing two.
        page("devices", nav) {
            DevicesRoute(
                onFindRing = { nav.navigate("devices/ring") },
                onFindMonitor = { nav.navigate("devices/monitor") },
                onRecent = { id -> open("devices/recent?device=${android.net.Uri.encode(id)}") },
                onFindStrap = { nav.navigate("devices/strap") },
                onCompare = { open("devices/compare") },
            )
        }
        page("devices/recent?device={device}", nav, listOf(arg("device"))) { back -> RecentRoute(back.arguments?.getString("device"), onBack = ::back) }
        page("devices/compare", nav) { CompareRoute(onBack = ::back) }
        page("devices/ring", nav) { RingPairRoute(onDone = ::back) }
        page("devices/strap", nav) { StrapPairRoute(onDone = ::back) }
        page("devices/monitor", nav) { MonitorPairRoute(onDone = ::back) }

        // ---- Your records and the assistant (in More). Health alerts (ui/alerts) also open from Today's bell, alert insights and their notifications.
        page("alerts", nav) { com.health.app.ui.alerts.AlertsRoute(onBack = ::back) }
        page("digest", nav) { DigestRoute(onBack = ::back, onSettings = { open(DIGEST_SETTINGS) }) }
        page("reports", nav) { ReportsRoute(onBack = ::back) }
        page("assistant", nav) { AiRoute(onBack = ::back, onAudit = { open("assistant/audit") }, onPrivacy = { open(WhereDataGoes.ROUTE) }) }
        page("assistant/audit", nav) { AiAuditRoute(onBack = ::back) }

        // ---- Settings (in More), a page each, and the pages within them.
        SettingsPage.entries.forEach { p -> page(p.route, nav) { SettingsRoute(p, onBack = ::back, onOpen = ::open) } }
        page("settings/conditions", nav) { com.health.app.ui.conditions.ConditionsRoute(onBack = ::back, onFields = { open("settings/fields") }) }
        page("settings/fields", nav) { com.health.app.ui.conditions.FieldsRoute(onBack = ::back) }
        page("settings/nutrients", nav) { com.health.app.ui.settings.NutrientTargetsRoute(onBack = ::back) }
        page("settings/reminders", nav) { RemindersRoute(onBack = ::back) }
        page(DIGEST_SETTINGS, nav) { DigestSettingsRoute(onBack = ::back) }
        page("settings/sharing", nav) {
            SharingRoute(onBack = ::back, onViewing = { nav.navigate("today") { popUpTo("today") { inclusive = true } } })
        }
        page(WhereDataGoes.ROUTE, nav) { WhereDataGoesRoute(onBack = ::back, onOpen = ::open) }
        page("settings/mealie", nav) { com.health.app.ui.settings.MealieRoute(onBack = ::back, onPrivacy = { open(WhereDataGoes.ROUTE) }) }
        page("settings/ai", nav) { AiSettingsRoute(onBack = ::back) }
        page("settings/integrations", nav) { IntegrationsRoute(onBack = ::back) }
        page("settings/watch", nav) { WatchRoute(onBack = ::back) }
        page("settings/export", nav) { com.health.app.ui.settings.ExportRoute(onBack = ::back, onOpen = ::open) }

        // ---- The features (in Health), each with its pages.
        page("mind", nav) {
            MindRoute(
                onJournal = { open("mind/journal") },
                onNewJournal = { kind -> nav.navigate("mind/journal/edit?kind=${kind.name}") },
                onCheck = { s -> nav.navigate("mind/check/${s.name}") },
                onTool = { kind -> nav.navigate("mind/tool/$kind") },
            )
        }
        page("mind/journal", nav) {
            JournalRoute(
                onBack = ::back,
                onOpen = { entry, kind -> nav.navigate("mind/journal/edit?kind=${kind.name}" + (entry?.id?.let { "&id=$it" } ?: "")) },
            )
        }
        page(
            "mind/journal/edit?kind={kind}&id={id}", nav,
            listOf(navArgument("kind") { type = NavType.StringType; defaultValue = JournalKind.FREE.name }, arg("id")),
        ) { back ->
            val kind = JournalKind.entries.firstOrNull { it.name == back.arguments?.getString("kind") } ?: JournalKind.FREE
            JournalEditorRoute(back.arguments?.getString("id"), kind, onDone = ::back)
        }
        page("mind/check/{screener}", nav, listOf(required("screener"))) { back ->
            val screener = Screener.entries.firstOrNull { it.name == back.arguments?.getString("screener") } ?: Screener.PHQ9
            ScreenerRoute(screener, onBack = ::back)
        }
        page("mind/tool/{kind}", nav, listOf(required("kind"))) { back -> CalmToolRoute(back.arguments?.getString("kind") ?: "box-breathing", onBack = ::back) }

        page("cycle?log={log}", nav, listOf(arg("log"))) { back ->
            CycleRoute(back.arguments?.getString("log")?.let { attempt { LocalDate.parse(it) }.getOrNull() }, onOpen = ::open)
        }
        page("cycle/settings", nav) { com.health.app.ui.modules.cycle.CycleSettingsRoute(onBack = ::back) }

        page("continence", nav) { ContinenceRoute(onPelvicFloor = { open("continence/pelvic") }, onOpen = ::open) }
        page("continence/pelvic", nav) { PelvicFloorRoute(onBack = ::back) }
        page("continence/progress", nav) { com.health.app.ui.modules.continence.ProgressRoute(onBack = ::back) }
        page("continence/check-in", nav) { com.health.app.ui.modules.continence.CheckInRoute(onBack = ::back) }
        page("continence/settings", nav) { com.health.app.ui.modules.continence.DiarySettingsRoute(onBack = ::back) }
        page("supplies/{area}", nav, listOf(required("area"))) { back ->
            com.health.app.ui.modules.continence.SuppliesRoute(com.health.app.ui.modules.continence.SupplyArea.of(back.arguments?.getString("area")), onBack = ::back)
        }

        page("meds", nav) { MedsRoute(onEdit = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        page("meds/edit?id={id}", nav, listOf(arg("id"))) { back -> MedEditorRoute(back.arguments?.getString("id"), onDone = ::back) }

        page("vitals", nav) { VitalsRoute() }
        page("symptoms", nav) { SymptomsRoute() }
        page("habits", nav) { HabitsRoute() }
        page("hormones", nav) { HormonesRoute(onEditMedication = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        page("appointments", nav) { AppointmentsRoute(onOpen = { id -> nav.navigate("appointments/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        page("appointments/edit?id={id}", nav, listOf(arg("id"))) { back -> AppointmentEditorRoute(back.arguments?.getString("id"), onDone = ::back) }

        page("neuro", nav) { com.health.app.ui.modules.neuro.NeuroRoute(::open) }
        page("neuro/{page}?id={id}&show={show}", nav, listOf(required("page"), arg("id"), arg("show"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.neuro.NeuroPage(a?.getString("page").orEmpty(), a?.getString("id"), a?.getString("show"), ::back, ::open)
        }
        page("neuro/{page}/{sub}?id={id}", nav, listOf(required("page"), required("sub"), arg("id"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.neuro.NeuroPage("${a?.getString("page")}/${a?.getString("sub")}", a?.getString("id"), null, ::back, ::open)
        }
        page("transition", nav) { com.health.app.ui.modules.transition.TransitionRoute(::open) }
        page("transition/{page}?id={id}", nav, listOf(required("page"), arg("id"))) { back ->
            com.health.app.ui.modules.transition.TransitionPage(back.arguments?.getString("page").orEmpty(), back.arguments?.getString("id"), ::back, ::open)
        }
        page("photos", nav) { com.health.app.ui.modules.photos.PhotosPage("", null, null, ::back, ::open) }
        page("photos/{page}?id={id}&album={album}", nav, listOf(required("page"), arg("id"), arg("album"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.photos.PhotosPage(a?.getString("page").orEmpty(), a?.getString("id"), a?.getString("album"), ::back, ::open)
        }
        page("measurements", nav) { com.health.app.ui.modules.body.MeasurementsRoute(onBack = ::back) }
    }
}
