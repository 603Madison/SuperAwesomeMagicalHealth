package com.fitness.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
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
import com.fitness.api.Meal
import com.fitness.app.ui.components.Backdrop
import com.fitness.app.ui.components.appContainer
import com.fitness.app.ui.devices.DevicesRoute
import com.fitness.app.ui.devices.RingPairRoute
import com.fitness.app.ui.food.AddFoodRoute
import com.fitness.app.ui.food.FoodRoute
import com.fitness.app.ui.settings.SettingsRoute
import com.fitness.app.ui.today.TodayRoute
import com.fitness.app.ui.trends.TrendsRoute
import java.time.LocalDate
import java.time.LocalTime

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Rounded.Today),
    Tab("food", "Food", Icons.Rounded.Restaurant),
    Tab("trends", "Trends", Icons.AutoMirrored.Rounded.ShowChart),
    Tab("devices", "Devices", Icons.Rounded.Watch),
    Tab("settings", "Settings", Icons.Rounded.Settings),
)

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
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    AppFrame(
        selected = route?.substringBefore('/')?.substringBefore('?'),
        snackbar = snackbar,
        onTab = { tab ->
            nav.navigate(tab) {
                popUpTo("today") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) { Routes(nav) }
}

/** The app around its pages: the aurora backdrop, the tabs along the bottom, and the snackbar. */
@Composable
fun AppFrame(selected: String?, snackbar: SnackbarHostState, onTab: (String) -> Unit, content: @Composable () -> Unit) {
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
private fun Routes(nav: NavHostController) {
    NavHost(nav, startDestination = "today") {
        composable("today") {
            TodayRoute(
                onLogFood = { date -> nav.navigate("food/add/$date/${mealNow().name}") },
                onOpenDevices = { nav.navigate("devices") { launchSingleTop = true } },
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
    }
}
