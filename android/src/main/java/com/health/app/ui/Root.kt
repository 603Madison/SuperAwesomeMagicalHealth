package com.health.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.health.api.ApiJson
import com.health.api.JournalKind
import com.health.api.Meal
import com.health.api.Medication
import com.health.api.Screener
import com.health.app.attempt
import com.health.app.ui.ai.AiAuditRoute
import com.health.app.ui.ai.AiRoute
import com.health.app.ui.ai.AiSettingsRoute
import com.health.app.ui.body.BodyRoute
import com.health.app.ui.body.BodySpan
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
import com.health.app.ui.log.LogHost
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
import com.health.app.ui.records.RecordsLogic
import com.health.app.ui.records.RecordsRoute
import com.health.app.ui.settings.DIGEST_SETTINGS
import com.health.app.ui.settings.SettingsPage
import com.health.app.ui.settings.SettingsRoute
import com.health.app.ui.settings.WhereDataGoes
import com.health.app.ui.settings.WhereDataGoesRoute
import com.health.app.ui.today.TodayRoute
import com.health.app.ui.today.TodayViewModel
import com.health.app.ui.tracking.HabitsRoute
import com.health.app.ui.tracking.MonitorPairRoute
import com.health.app.ui.tracking.SymptomsRoute
import com.health.app.ui.tracking.VitalsRoute
import com.health.app.ui.you.YouRoute
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/*
 * The app's frame and its map. Along the bottom, always the same whatever's turned on: Today,
 * Body, the round + in the middle (the log sheet, not a page), Care and You. Every other page
 * sits under one of them and has a back arrow; a link to a tab's own page switches to that tab
 * rather than stacking a second copy.
 */

data class Tab(val route: String, val label: String, val icon: ImageVector)

/** The four tabs, the + between Body and Care. */
val TABS: List<Tab> = listOf(
    Tab("today", "Today", Icons.Rounded.WbSunny),
    Tab("body", "Body", Icons.Rounded.MonitorHeart),
    Tab("care", "Care", Icons.Rounded.FavoriteBorder),
    Tab("you", "You", Icons.Rounded.Person),
)

/** The first part of a route ("settings" of "settings/you", "body" of "body?x=1"). */
private fun rootOf(route: String): String = route.substringBefore('?').substringBefore('/')

/** The pages under You: devices, your records, the assistant and Settings. */
private val UNDER_YOU = setOf("you", "devices", "digest", "reports", "assistant", "settings", "records")

/** The pages under Body: its cards opened (body/detail/…), Health alerts, and comparing two devices. */
private val UNDER_BODY = setOf("body", "alerts")

/**
 * Which tab a route belongs to, for the tab bar to light: a tab's own page, Body's (Health alerts,
 * Compare devices), You's (devices, Your week, the report, the assistant, Settings), and everything
 * else (each feature and its pages) under Care.
 */
fun tabOf(route: String?): String? {
    val r = route ?: return null
    val root = rootOf(r)
    return when {
        root in UNDER_BODY || r.startsWith("devices/compare") -> "body"
        root in UNDER_YOU -> "you"
        root == "today" -> "today"
        else -> "care"
    }
}

/** Whether [route] is one of the tabs' own pages (which a link switches to, never stacks). */
fun isTab(route: String): Boolean = TABS.any { it.route == route.substringBefore('?') }

/**
 * Where an older link lands now: the last 24 hours and Trends are Body, Health is Care, More and
 * Settings are You, Appearance and Account are one page.
 */
fun current(route: String): String = when (rootOf(route)) {
    "recent", "trends" -> "body"
    "health" -> "care"
    "more" -> "you"
    else -> when (route) {
        "settings" -> "you"
        "settings/account" -> SettingsPage.LOOK.route
        else -> route
    }
}

/** The span a link asks Body for: the last 24 hours ("recent"), or the week ("trends"); null for Body as it was. */
fun spanOf(route: String): BodySpan? = when (rootOf(route)) {
    "recent" -> BodySpan.DAY
    "trends" -> BodySpan.WEEK
    else -> null
}

/** The med editor with [draft] filled in, to add as a new medication (from a medical record). */
fun medDraftRoute(draft: Medication): String =
    "meds/edit?draft=" + android.net.Uri.encode(ApiJson.encodeToString(Medication.serializer(), draft.copy(id = null)))

/** The meal it probably is now, for logging food. */
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
    val entry by nav.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    var logging by remember { mutableStateOf(false) }
    // A span a link asked Body for ("recent", "trends"), until Body has it.
    var bodyAsk by remember { mutableStateOf<BodySpan?>(null) }
    fun go(route: String) {
        spanOf(route)?.let { bodyAsk = it }
        nav.go(route)
    }
    // A notification asked for a page.
    LaunchedEffect(open) {
        if (open != null) {
            go(open)
            onOpened()
        }
    }
    CompositionLocalProvider(LocalReadOnly provides (viewing != null)) {
        AppFrame(selected = tabOf(entry?.destination?.route), snackbar = snackbar, onTab = ::go, onLog = { logging = true }, canLog = viewing == null) {
            Column(Modifier.fillMaxSize()) {
                viewing?.let { share ->
                    ViewingBanner(share.ownerName, onBack = {
                        scope.launch { c.view(null) }
                        nav.navigate("today") { popUpTo("today") { inclusive = true } }
                    })
                }
                Box(Modifier.weight(1f)) { Routes(nav, ::go, bodyAsk) { bodyAsk = null } }
            }
        }
        if (viewing == null) Log(nav, entry, logging, onClose = { logging = false }, open = ::go)
    }
}

/** The + sheet, with Today's Right now (from Today's own day, while it's showing today). */
@Composable
private fun Log(nav: NavHostController, current: NavBackStackEntry?, showing: Boolean, onClose: () -> Unit, open: (String) -> Unit) {
    val c = appContainer()
    // Today stays at the bottom of the back stack: its day is the one Today shows (looked up again as the page changes).
    val today = remember(current) { attempt { nav.getBackStackEntry("today") }.getOrNull() }
    val vm = today?.let { viewModel(viewModelStoreOwner = it) { TodayViewModel(c) } }
    val state = vm?.state?.collectAsState()?.value?.takeIf { it.date == it.today }
    val summary = state?.summary
    LogHost(
        showing = showing, onClose = onClose, open = open, now = state?.feed?.now.orEmpty(), plan = state?.plan,
        wearing = summary?.modules?.continence?.wearing, checkIn = summary?.checkIn, weightKg = summary?.weightKg,
        takeDose = { med, at, name -> vm?.takeDose(med, at, name) },
    )
}

/**
 * The app around its pages: the aurora backdrop, the tabs along the bottom with the round + in
 * the middle ([onLog]; greyed while viewing a share, [canLog]), and the snackbar.
 */
@Composable
fun AppFrame(
    selected: String?,
    snackbar: SnackbarHostState,
    onTab: (String) -> Unit,
    onLog: () -> Unit = {},
    canLog: Boolean = true,
    content: @Composable () -> Unit,
) {
    Backdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { TabBar(selected, onTab, onLog, canLog) },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { content() }
        }
    }
}

/** Today · Body · + · Care · You: fixed, never shifting. */
@Composable
private fun TabBar(selected: String?, onTab: (String) -> Unit, onLog: () -> Unit, canLog: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                TABS.forEachIndexed { i, tab ->
                    if (i == 2) LogButton(onLog, canLog)
                    TabItem(tab, selected == tab.route) { onTab(tab.route) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The round + in the middle: it opens the log sheet. */
@Composable
private fun RowScope.LogButton(onClick: () -> Unit, enabled: Boolean) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = if (enabled) 6.dp else 0.dp,
            modifier = Modifier.size(56.dp).semantics { contentDescription = if (enabled) "Log something" else "Logging is off while you view a share" },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(28.dp)) }
        }
    }
}

/** A page within a tab: its header gets a back arrow ([LocalBack]). */
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
private fun Routes(nav: NavHostController, go: (String) -> Unit, bodyAsk: BodySpan?, onBodyAsked: () -> Unit) {
    fun open(route: String) = go(route)
    fun back() { nav.popBackStack() }
    NavHost(nav, startDestination = "today") {
        // ---- The tabs' own pages: no back arrow, they're where back leads.
        composable("today") {
            TodayRoute(onOpen = ::open)
        }
        composable("body") { BodyRoute(open = ::open, ask = bodyAsk, onAsked = onBodyAsked) }
        composable("care") { com.health.app.ui.care.CareRoute(::open) }
        composable("you") { YouRoute(::open) }

        // ---- Food (under Care).
        page("food", nav) { FoodRoute(onAdd = { date, meal -> nav.navigate("food/add/$date/${meal.name}") }, onPrivacy = { open(WhereDataGoes.ROUTE) }, onShopping = { nav.navigate("food/shopping") }) }
        page("food/shopping", nav) { com.health.app.ui.food.ShoppingRoute(onBack = ::back) }
        page("food/add/{date}/{meal}", nav, listOf(required("date"), required("meal"))) { back ->
            val date = attempt { LocalDate.parse(back.arguments?.getString("date")) }.getOrElse { LocalDate.now() }
            val meal = Meal.entries.firstOrNull { it.name == back.arguments?.getString("meal") } ?: Meal.SNACK
            AddFoodRoute(date, meal, onDone = ::back, onPrivacy = { open(WhereDataGoes.ROUTE) })
        }

        // ---- Devices (under You): pairing, a device's last 24 hours, and (under Body) comparing two.
        page("devices", nav) {
            DevicesRoute(
                onFindRing = { nav.navigate("devices/ring") },
                onFindMonitor = { nav.navigate("devices/monitor") },
                onRecent = { id -> open("devices/recent?device=${android.net.Uri.encode(id)}") },
                onFindStrap = { nav.navigate("devices/strap") },
                onCompare = { open("devices/compare") },
            )
        }
        page("devices/recent?device={device}", nav, listOf(arg("device"))) { back -> BodyRoute(open = ::open, device = back.arguments?.getString("device"), onBack = ::back) }
        page("devices/compare", nav) { CompareRoute(onBack = ::back) }
        page("devices/ring", nav) { RingPairRoute(onDone = ::back) }
        page("devices/strap", nav) { StrapPairRoute(onDone = ::back) }
        page("devices/monitor", nav) { MonitorPairRoute(onDone = ::back) }

        // ---- A card on Body, opened: its details over 24 hours, a week, a month or a year (under Body).
        page(com.health.app.ui.body.BodyDetail.ROUTE, nav, listOf(required("id"), arg("span"), arg("device"))) { entry ->
            val a = entry.arguments
            com.health.app.ui.body.BodyDetailRoute(
                a?.getString("id").orEmpty(), com.health.app.ui.body.BodyDetail.spanNamed(a?.getString("span")), a?.getString("device"), onBack = ::back,
            )
        }

        // ---- Health alerts (under Body; also from Today's bell and card, and their notifications), your records and the assistant (under You).
        page("alerts", nav) { com.health.app.ui.alerts.AlertsRoute(onBack = ::back) }
        page("digest", nav) { DigestRoute(onBack = ::back, onSettings = { open(DIGEST_SETTINGS) }) }
        page("reports", nav) { ReportsRoute(onBack = ::back) }
        page("assistant", nav) { AiRoute(onBack = ::back, onAudit = { open("assistant/audit") }, onPrivacy = { open(WhereDataGoes.ROUTE) }) }
        page("assistant/audit", nav) { AiAuditRoute(onBack = ::back) }
        page(RecordsLogic.ROUTE, nav) {
            RecordsRoute(onBack = ::back, onOpen = ::open, onAddMedication = { draft -> nav.navigate(medDraftRoute(draft)) })
        }

        // ---- Settings (under You), a page each, and the pages within them.
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

        // ---- The features (under Care), each with its pages.
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

        page("meds", nav) { MedsRoute(onEdit = { id -> nav.navigate("meds/edit" + (id?.let { "?id=$it" } ?: "")) }, onOpen = ::open) }
        page("meds/edit?id={id}&draft={draft}", nav, listOf(arg("id"), arg("draft"))) { back ->
            val draft = back.arguments?.getString("draft")?.let { attempt { ApiJson.decodeFromString(Medication.serializer(), it) }.getOrNull() }
            MedEditorRoute(back.arguments?.getString("id"), onDone = ::back, draft = draft)
        }

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
