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
import androidx.compose.material3.SnackbarHost
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
import com.health.app.ui.components.Backdrop
import com.health.app.ui.components.appContainer
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
fun MainScaffold() {
    val c = appContainer()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it) } }
    val profile by c.profile.collectAsState()
    val tabs = tabsFor(profile?.modules ?: Module.DEFAULT)
    val entry by nav.currentBackStackEntryAsState()
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
    ) { Routes(nav, showDevicesInHub = tabs.none { it.route == "devices" }) }
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
            val date = runCatching { LocalDate.parse(back.arguments?.getString("date")) }.getOrElse { LocalDate.now() }
            val meal = Meal.entries.firstOrNull { it.name == back.arguments?.getString("meal") } ?: Meal.SNACK
            AddFoodRoute(date, meal, onDone = { nav.popBackStack() })
        }
        composable("trends") { TrendsRoute() }
        composable("devices") { DevicesRoute(onFindRing = { nav.navigate("devices/ring") }) }
        composable("devices/ring") { RingPairRoute(onDone = { nav.popBackStack() }) }
        composable("settings") { SettingsRoute() }

        composable("health") {
            HubRoute(
                onOpen = { route ->
                    if (route == "settings") {
                        nav.navigate("settings") { popUpTo("today") { saveState = true }; launchSingleTop = true; restoreState = true }
                    } else {
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
            CycleRoute(back.arguments?.getString("log")?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
        }

        composable("continence") { ContinenceRoute(onPelvicFloor = { open("continence/pelvic") }) }
        composable("continence/pelvic") { PelvicFloorRoute(onBack = { nav.popBackStack() }) }

        composable("meds") { MedsRoute(onEdit = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }) }
        composable(
            "meds/edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back -> MedEditorRoute(back.arguments?.getString("id"), onDone = { nav.popBackStack() }) }
    }
}
