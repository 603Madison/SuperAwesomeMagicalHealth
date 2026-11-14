package com.health.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.health.api.JournalKind
import com.health.api.Meal
import com.health.api.Module
import com.health.api.Screener
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import com.health.app.attempt
import com.health.app.ui.care.AppointmentEditorRoute
import com.health.app.ui.care.AppointmentsRoute
import com.health.app.ui.care.DigestRoute
import com.health.app.ui.care.HormonesRoute
import com.health.app.ui.care.IntegrationsRoute
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.care.RemindersRoute
import com.health.app.ui.care.ReportsRoute
import com.health.app.ui.care.SharingRoute
import com.health.app.ui.care.ViewingBanner
import com.health.app.ui.care.WatchRoute
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.launch
import com.health.app.ui.devices.DevicesRoute
import com.health.app.ui.devices.RingPairRoute
import com.health.app.ui.food.AddFoodRoute
import com.health.app.ui.food.FoodRoute
import com.health.app.ui.modules.HubRoute
import com.health.app.ui.modules.ModuleInfo
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
import com.health.app.ui.settings.SettingsRoute
import com.health.app.ui.today.TodayRoute
import com.health.app.ui.tracking.HabitsRoute
import com.health.app.ui.tracking.MonitorPairRoute
import com.health.app.ui.tracking.SymptomsRoute
import com.health.app.ui.tracking.VitalsRoute
import com.health.app.ui.trends.TrendsRoute
import java.time.LocalDate
import java.time.LocalTime

data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * The tabs along the bottom, at most five: Today, Food when it's on, Health when any opt-in module
 * is on (it lists them, and Devices), Trends, Devices when there's room, and Settings.
 */
fun tabsFor(modules: Set<Module>): List<Tab> {
    val health = ModuleInfo.OPT_IN.any { it in modules }
    val tabs = buildList {
        add(Tab("today", "Today", Icons.Rounded.Today))
        if (Module.NUTRITION in modules) add(Tab("food", "Food", Icons.Rounded.Restaurant))
        if (health) add(Tab("health", "Health", Icons.Rounded.Favorite))
        add(Tab("trends", "Trends", Icons.AutoMirrored.Rounded.ShowChart))
    }
    return tabs + listOfNotNull(Tab("devices", "Devices", Icons.Rounded.Watch).takeIf { tabs.size < 4 }) +
        Tab("settings", "Settings", Icons.Rounded.Settings)
}

/** Which tab a route belongs to: a module's pages, and Devices when it isn't a tab, sit under Health. */
fun tabOf(route: String?, tabs: List<Tab>): String? {
    val root = route?.substringBefore('/')?.substringBefore('?') ?: return null
    if (tabs.any { it.route == root }) return root
    return if (tabs.any { it.route == "health" }) "health" else root
}

/** The meal it probably is now, for "Log food" from Today. */
fun mealNow(time: LocalTime = LocalTime.now()): Meal = when (time.hour) {
    in 4..10 -> Meal.BREAKFAST
    in 11..14 -> Meal.LUNCH
    in 17..21 -> Meal.DINNER
    else -> Meal.SNACK
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
    val profile by c.profile.collectAsState()
    // Viewing someone's share: their shared features, read-only, with a way back.
    val viewing by c.viewing.collectAsState()
    val viewProfile by c.viewProfile.collectAsState()
    val modules = if (viewing != null) viewProfile?.modules ?: viewing?.modules.orEmpty() else profile?.modules ?: Module.DEFAULT
    val tabs = tabsFor(modules)
    val entry by nav.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    // A notification asked for a page.
    LaunchedEffect(open) {
        if (open != null) {
            attempt { nav.navigate(open) { launchSingleTop = true } }
            onOpened()
        }
    }
    CompositionLocalProvider(LocalReadOnly provides (viewing != null)) {
        AppFrame(
            selected = tabOf(entry?.destination?.route, tabs),
            snackbar = snackbar,
            tabs = tabs,
            onTab = { tab ->
                nav.navigate(tab) {
                    popUpTo("today") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                viewing?.let { share ->
                    ViewingBanner(share.ownerName, onBack = {
                        scope.launch { c.view(null) }
                        nav.navigate("today") { popUpTo("today") { inclusive = true } }
                    })
                }
                Box(Modifier.weight(1f)) { Routes(nav, showDevicesInHub = tabs.none { it.route == "devices" }) }
            }
        }
    }
}

/** The app around its pages: the aurora backdrop, the tabs along the bottom, and the snackbar. */
@Composable
fun AppFrame(
    selected: String?,
    snackbar: SnackbarHostState,
    onTab: (String) -> Unit,
    tabs: List<Tab> = tabsFor(Module.DEFAULT),
    content: @Composable () -> Unit,
) {
    Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab.route,
                            onClick = { onTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun Routes(nav: NavHostController, showDevicesInHub: Boolean) {
    fun open(route: String) = nav.navigate(route) { launchSingleTop = true }
    NavHost(nav, startDestination = "today") {
        composable("today") {
            TodayRoute(
                onLogFood = { date -> nav.navigate("food/add/$date/${mealNow().name}") },
                onOpenDevices = { open("devices") },
                onOpen = ::open,
            )
        }
        composable("food") { FoodRoute(onAdd = { date, meal -> nav.navigate("food/add/$date/${meal.name}") }) }
        composable(
            "food/add/{date}/{meal}",
            arguments = listOf(navArgument("date") { type = NavType.StringType }, navArgument("meal") { type = NavType.StringType }),
        ) { back ->
            val date = attempt { LocalDate.parse(back.arguments?.getString("date")) }.getOrElse { LocalDate.now() }
            val meal = Meal.entries.firstOrNull { it.name == back.arguments?.getString("meal") } ?: Meal.SNACK
            AddFoodRoute(date, meal, onDone = { nav.popBackStack() })
        }
        composable("trends") { TrendsRoute() }
        composable("devices") { DevicesRoute(onFindRing = { nav.navigate("devices/ring") }, onFindMonitor = { nav.navigate("devices/monitor") }) }
        composable("devices/ring") { RingPairRoute(onDone = { nav.popBackStack() }) }
        composable("settings") { SettingsRoute(onOpen = ::open) }

        composable("health") {
            HubRoute(
                onOpen = { route ->
                    if (route == "settings") {
                        nav.navigate("settings") { popUpTo("today") { saveState = true }; launchSingleTop = true; restoreState = true }
                    } else if (nav.graph.findNode(route) != null) {
                        open(route)
                    }
                },
                showDevices = showDevicesInHub,
            )
        }

        composable("mind") {
            MindRoute(
                onJournal = { open("mind/journal") },
                onNewJournal = { kind -> nav.navigate("mind/journal/edit?kind=${kind.name}") },
                onCheck = { s -> nav.navigate("mind/check/${s.name}") },
                onTool = { kind -> nav.navigate("mind/tool/$kind") },
            )
        }
        composable("mind/journal") {
            JournalRoute(
                onBack = { nav.popBackStack() },
                onOpen = { entry, kind -> nav.navigate("mind/journal/edit?kind=${kind.name}" + (entry?.id?.let { "&id=$it" } ?: "")) },
            )
        }
        composable(
            "mind/journal/edit?kind={kind}&id={id}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType; defaultValue = JournalKind.FREE.name },
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { back ->
            val kind = JournalKind.entries.firstOrNull { it.name == back.arguments?.getString("kind") } ?: JournalKind.FREE
            JournalEditorRoute(back.arguments?.getString("id"), kind, onDone = { nav.popBackStack() })
        }
        composable("mind/check/{screener}", arguments = listOf(navArgument("screener") { type = NavType.StringType })) { back ->
            val screener = Screener.entries.firstOrNull { it.name == back.arguments?.getString("screener") } ?: Screener.PHQ9
            ScreenerRoute(screener, onBack = { nav.popBackStack() })
        }
        composable("mind/tool/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) { back ->
            CalmToolRoute(back.arguments?.getString("kind") ?: "box-breathing", onBack = { nav.popBackStack() })
        }

        composable(
            "cycle?log={log}",
            arguments = listOf(navArgument("log") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            CycleRoute(back.arguments?.getString("log")?.let { attempt { LocalDate.parse(it) }.getOrNull() }, onOpen = ::open)
        }

        composable("continence") { ContinenceRoute(onPelvicFloor = { open("continence/pelvic") }, onOpen = ::open) }
        composable("continence/pelvic") { PelvicFloorRoute(onBack = { nav.popBackStack() }) }

        composable("meds") { MedsRoute(onEdit = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        composable(
            "meds/edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back -> MedEditorRoute(back.arguments?.getString("id"), onDone = { nav.popBackStack() }) }

        // Blood pressure and glucose, symptoms, habits, and pairing monitors (ui/tracking).
        composable("vitals") { VitalsRoute() }
        composable("symptoms") { SymptomsRoute() }
        composable("habits") { HabitsRoute() }
        composable("devices/monitor") { MonitorPairRoute(onDone = { nav.popBackStack() }) }
        // Hormone therapy, appointments, and Settings' pages for reminders, the digest, sharing,
        // reports, the watch and integrations.
        composable("hormones") { HormonesRoute(onEditMedication = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        composable("appointments") { AppointmentsRoute(onOpen = { id -> nav.navigate("appointments/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        composable(
            "appointments/edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back -> AppointmentEditorRoute(back.arguments?.getString("id"), onDone = { nav.popBackStack() }) }
        composable("settings/reminders") { RemindersRoute(onBack = { nav.popBackStack() }) }
        composable("settings/nutrients") { com.health.app.ui.settings.NutrientTargetsRoute(onBack = { nav.popBackStack() }) }
        composable("settings/sharing") {
            SharingRoute(onBack = { nav.popBackStack() }, onViewing = { nav.navigate("today") { popUpTo("today") { inclusive = true } } })
        }
        composable("settings/watch") { WatchRoute(onBack = { nav.popBackStack() }) }
        composable("settings/integrations") { IntegrationsRoute(onBack = { nav.popBackStack() }) }
        composable("settings/mealie") { com.health.app.ui.settings.MealieRoute(onBack = { nav.popBackStack() }) }
        composable("digest") { DigestRoute(onBack = { nav.popBackStack() }) }
        composable("reports") { ReportsRoute(onBack = { nav.popBackStack() }) }

        // Conditions and goals, fields, bladder and bowel detail, supplies, cycle settings and the AI assistant.
        composable("settings/conditions") { com.health.app.ui.conditions.ConditionsRoute(onBack = { nav.popBackStack() }, onFields = { open("settings/fields") }) }
        composable("settings/fields") { com.health.app.ui.conditions.FieldsRoute(onBack = { nav.popBackStack() }) }
        composable("continence/progress") { com.health.app.ui.modules.continence.ProgressRoute(onBack = { nav.popBackStack() }) }
        composable("continence/check-in") { com.health.app.ui.modules.continence.CheckInRoute(onBack = { nav.popBackStack() }) }
        composable("continence/settings") { com.health.app.ui.modules.continence.DiarySettingsRoute(onBack = { nav.popBackStack() }) }
        composable("supplies/{area}", arguments = listOf(navArgument("area") { type = NavType.StringType })) { back ->
            com.health.app.ui.modules.continence.SuppliesRoute(com.health.app.ui.modules.continence.SupplyArea.of(back.arguments?.getString("area")), onBack = { nav.popBackStack() })
        }
        composable("cycle/settings") { com.health.app.ui.modules.cycle.CycleSettingsRoute(onBack = { nav.popBackStack() }) }
        composable("settings/ai") { com.health.app.ui.ai.AiRoute(onBack = { nav.popBackStack() }, onAudit = { open("settings/ai/audit") }) }
        composable("settings/ai/audit") { com.health.app.ui.ai.AiAuditRoute(onBack = { nav.popBackStack() }) }
        // Wave 3: Autism & ADHD (also reached from Mind), transition, progress photos, body
        // measurements and Settings › Export (ui/modules/neuro, transition, photos, body; ui/settings/ExportSettings.kt).
        fun arg(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
        composable("neuro") { com.health.app.ui.modules.neuro.NeuroRoute(::open) }
        composable("neuro/{page}?id={id}&show={show}", arguments = listOf(navArgument("page") { type = NavType.StringType }, arg("id"), arg("show"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.neuro.NeuroPage(a?.getString("page").orEmpty(), a?.getString("id"), a?.getString("show"), { nav.popBackStack() }, ::open)
        }
        composable("neuro/{page}/{sub}?id={id}", arguments = listOf(navArgument("page") { type = NavType.StringType }, navArgument("sub") { type = NavType.StringType }, arg("id"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.neuro.NeuroPage("${a?.getString("page")}/${a?.getString("sub")}", a?.getString("id"), null, { nav.popBackStack() }, ::open)
        }
        composable("transition") { com.health.app.ui.modules.transition.TransitionRoute(::open) }
        composable("transition/{page}?id={id}", arguments = listOf(navArgument("page") { type = NavType.StringType }, arg("id"))) { back ->
            com.health.app.ui.modules.transition.TransitionPage(back.arguments?.getString("page").orEmpty(), back.arguments?.getString("id"), { nav.popBackStack() }, ::open)
        }
        composable("photos") { com.health.app.ui.modules.photos.PhotosPage("", null, null, { nav.popBackStack() }, ::open) }
        composable("photos/{page}?id={id}&album={album}", arguments = listOf(navArgument("page") { type = NavType.StringType }, arg("id"), arg("album"))) { back ->
            val a = back.arguments
            com.health.app.ui.modules.photos.PhotosPage(a?.getString("page").orEmpty(), a?.getString("id"), a?.getString("album"), { nav.popBackStack() }, ::open)
        }
        composable("measurements") { com.health.app.ui.modules.body.MeasurementsRoute(onBack = { nav.popBackStack() }) }
        composable("settings/export") { com.health.app.ui.settings.ExportRoute(onBack = { nav.popBackStack() }, onOpen = ::open) }
    }
}
