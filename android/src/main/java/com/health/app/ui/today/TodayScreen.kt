@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.EnergyCheck
import com.health.api.FoodSuggestion
import com.health.api.HealthAlert
import com.health.api.Layouts
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.TodaySection
import com.health.api.Units
import com.health.app.ui.alerts.Alerts
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SectionHeading
import com.health.app.ui.components.ServerNoticeState
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.arrangedBy
import com.health.app.ui.components.foodNumbers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What Today can do, as callbacks (so it can be drawn with sample data). */
class TodayActions(
    val go: (LocalDate) -> Unit = {},
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    /** Opens a page ("alerts", "recent", "meds", "appointments/edit?id=…"). */
    val open: (String) -> Unit = {},
    /** Right now's Take: the medication, the time it was due, and its name. */
    val takeDose: (String, Long?, String) -> Unit = { _, _, _ -> },
    /** Logs a food idea from the protein insight. */
    val addIdea: (FoodSuggestion) -> Unit = {},
    /** Saves an energy and focus check-in, from Right now's quick check-in. */
    val checkEnergy: (EnergyCheck) -> Unit = {},
)

@Composable
fun TodayRoute(onOpen: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { TodayViewModel(c) }
    val state by vm.state.collectAsState()
    val settings by c.settings.collectAsState()
    val readOnly = LocalReadOnly.current
    // Health alerts (the person's own): the bell's count and the card, looked up beside Today (never holding it up).
    val unseen by c.unseenAlerts.collectAsState()
    val alerts by c.alerts.collectAsState()
    val revision by c.revision.collectAsState()
    LaunchedEffect(readOnly, revision) { if (!readOnly) c.refreshAlerts() }
    val now = System.currentTimeMillis()
    val modules = com.health.app.ui.care.shownModules()
    val log = remember(c) { com.health.app.ui.log.LogActions.of(c) }
    TodayScreen(
        state = state,
        units = settings.units,
        zone = c.zone(),
        actions = TodayActions(
            go = vm::go,
            refresh = vm::refresh,
            retry = { vm.load() },
            open = onOpen,
            takeDose = { med, at, name -> vm.takeDose(med, at, name) },
            addIdea = vm::addIdea,
            checkEnergy = log::saveEnergyCheck,
        ),
        modules = modules,
        checkIns = com.health.app.ui.modules.neuro.quickCheckIn(modules, com.health.app.ui.components.shownFields()),
        measurements = com.health.app.ui.modules.body.measurementsShown(),
        serverNotice = com.health.app.ui.components.serverNotice(),
        foodNumbers = foodNumbers(),
        now = now,
        alerts = if (readOnly) 0 else unseen,
        alert = if (readOnly) null else Alerts.mostSerious(alerts, now),
    )
}

/**
 * Today, top to bottom: the day and a greeting with the bell ([alerts] unseen), the most serious
 * active health alert ([alert], always first), then in the person's order ([Layouts.TODAY]) the
 * scores, Right now and Your day (the server's day feed) and For you. [foodNumbers]: whether
 * amounts of food show (not in eating disorder recovery); [now] the time the "Now" line is for;
 * [checkIns]: Right now's energy opens the quick energy and focus check-in. On a day gone by,
 * Right now isn't there and Your day is that day.
 */
@Composable
fun TodayScreen(
    state: TodayState,
    units: Units,
    zone: ZoneId,
    actions: TodayActions,
    modules: Set<Module> = Module.DEFAULT,
    checkIns: Boolean = false,
    measurements: Boolean = false,
    serverNotice: ServerNoticeState? = null,
    foodNumbers: Boolean = true,
    now: Long = System.currentTimeMillis(),
    alerts: Int = 0,
    alert: HealthAlert? = null,
) {
    val readOnly = LocalReadOnly.current
    var changing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val summary = state.summary
    val isToday = state.date == state.today
    val sections = TodayLayout.sections(modules)
    val visible = arrangedBy(Layouts.TODAY, sections) { it.name }
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TodayHeader(
                state.date, state.today, hour, alerts, bell = !readOnly,
                onPickDay = { picking = true }, onToday = { actions.go(state.today) }, onBell = { actions.open("alerts") },
            )
            serverNotice?.let { com.health.app.ui.components.ServerUpdateNotice(it) }
            alert?.let { AlertCard(it) { actions.open("alerts") } }
            when {
                state.loading && summary == null -> Loading()
                state.error != null && summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                summary != null -> {
                    state.error?.let { ErrorCard(it, actionLabel = "Try again", onAction = actions.retry) }
                    val nowItems = if (isToday) TodayRows.now(state.feed?.now.orEmpty(), alert) else emptyList()
                    val showing = TodayRows.showing(nowItems)
                    fun act(item: NowItem) {
                        val a = item.action ?: return
                        when {
                            TodayRows.checksIn(item, modules, checkIns) -> checking = true
                            a.action == NowAction.CONTINENCE_CHANGE -> changing = true
                            a.action == NowAction.DOSE_TAKE -> TodayRows.id(a, "medicationId")?.let { med -> actions.takeDose(med, TodayRows.id(a, "scheduledFor")?.toLongOrNull(), item.title) }
                            else -> TodayRows.route(item, modules, state.today.toString())?.let(actions.open)
                        }
                    }
                    visible.forEach { section ->
                        key(section) {
                            when (section) {
                                TodaySection.SCORES -> ScoresCard(
                                    summary.readiness, summary.sleep, summary.activity, TodayRows.scoresLine(summary), isToday, readOnly,
                                    onOpen = { actions.open("body") }, onDevices = { actions.open("devices") },
                                )
                                TodaySection.UP_NEXT -> if (nowItems.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        SectionHeading(TodayLayout.label(TodaySection.UP_NEXT))
                                        RightNowCard(nowItems, readOnly, ::act)
                                    }
                                }
                                TodaySection.DAY -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionHeading(TodayLayout.label(TodaySection.DAY)) {
                                        // The last 24 hours are only ever the person's own.
                                        if (!readOnly && isToday) TextButton(onClick = { actions.open("recent") }) { Text("Last 24 hours") }
                                    }
                                    DayCard(
                                        state.feed?.items.orEmpty(), TodayRows.nowLine(summary, now), isToday, zone,
                                        routeOf = { TodayRows.route(it, modules, measurements) }, onOpen = actions.open,
                                        stagesOf = { TodayRows.stages(it, summary) },
                                        error = state.feedError.takeIf { state.feed == null }, onRetry = actions.retry,
                                    )
                                }
                                TodaySection.INSIGHTS -> {
                                    val insights = TodayRows.forYou(summary, modules, showing, scores = TodaySection.SCORES in visible)
                                    if (insights.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            SectionHeading(TodayLayout.label(TodaySection.INSIGHTS))
                                            ForYouCard(
                                                insights,
                                                targetOf = { InsightLinks.of(it, modules, readOnly, measurements) },
                                                onOpen = { t -> actions.open(t.route ?: "body") },
                                                readOnly = readOnly,
                                                onAdd = actions.addIdea,
                                                showNumbers = foodNumbers,
                                            )
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (!readOnly && sections.size > 1) {
                        TextButton(onClick = { arranging = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Icon(Icons.Rounded.SwapVert, null, Modifier.padding(end = 6.dp))
                            Text("Arrange Today")
                        }
                    }
                }
            }
        }
    }

    if (changing) com.health.app.ui.modules.continence.ChangeSheet(onDismiss = { changing = false })
    if (checking) com.health.app.ui.modules.neuro.EnergyCheckSheet(onDismiss = { checking = false }) { check -> actions.checkEnergy(check); checking = false }
    if (arranging) ArrangeSheet(Layouts.TODAY, "Today", sections.map { ArrangeItem(it.name, TodayLayout.label(it)) }, { arranging = false })
    if (picking) DayPicker(state.date, state.today, onPick = { picking = false; actions.go(it) }, onDismiss = { picking = false })
}

/** Choosing another day: any day up to today. */
@Composable
private fun DayPicker(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val utc = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val last = utc(today)
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = utc(date),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= last
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Show this day") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(picker) }
}
