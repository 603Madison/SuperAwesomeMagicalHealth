package com.health.app.ui.modules.neuro

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.EnergyDay
import com.health.api.Fields
import com.health.api.FocusSession
import com.health.api.RoutineRun
import com.health.app.net.addNeuroCheckIn
import com.health.app.net.breakIntoSteps
import com.health.app.net.deleteOverload
import com.health.app.net.deleteRoutine
import com.health.app.net.saveCommunicationCards
import com.health.app.net.saveEnergyDay
import com.health.app.net.saveFocus
import com.health.app.net.saveNeuroProfile
import com.health.app.net.saveOverload
import com.health.app.net.saveRoutine
import com.health.app.net.saveRoutineRun
import com.health.app.reminders.FocusAlerts
import com.health.app.reminders.FocusStore
import com.health.app.ui.care.LocalReadOnly
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * The pages under Autism & ADHD, by [page]: "about", "about/show", "overload", "energy",
 * "checkin", "focus", "routines", "routine/edit", "routine/run", "cards".
 */
@Composable
fun NeuroPage(page: String, id: String?, show: String?, onBack: () -> Unit, open: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { NeuroViewModel(c) }
    val state by vm.state.collectAsState()
    val fields = shownFields()
    val readOnly = LocalReadOnly.current
    val zone = c.zone()
    val today = LocalDate.now(zone)
    // Pages that replace what's saved wait for it first, so nothing is lost.
    if (state.summary == null && page != "focus" && page != "checkin") {
        Column {
            BackBar(onBack)
            state.error?.let { ErrorCard(it, Modifier.padding(16.dp), actionLabel = "Try again", onAction = { vm.load() }) } ?: Loading()
        }
        return
    }
    when (page) {
        "about" -> AboutMeEditor(state.profile, fields, onBack) { p -> vm.act("Saved") { it.saveNeuroProfile(p) }; onBack() }
        "about/show" -> AboutMeView(state.profile, fields, onBack)
        "overload" -> OverloadForm(
            state.events.firstOrNull { it.id == id }, state.profile, fields, zone, System.currentTimeMillis(), onBack,
            onSave = { e -> vm.act("Saved. Go gently.") { it.saveOverload(e) }; onBack() },
            onDelete = { eventId -> vm.act("Deleted") { it.deleteOverload(eventId) }; onBack() },
        )
        "energy" -> EnergyEditor(
            state.energyOn(today) ?: EnergyDay(today.toString()), Fields.NEURO_SOCIAL in fields || Fields.MIND_ENERGY_BUDGET in fields,
            System.currentTimeMillis(), onBack,
        ) { day -> vm.act("Saved") { it.saveEnergyDay(day) }; onBack() }
        "checkin" -> NeuroCheckInForm(fields, System.currentTimeMillis(), onBack) { checkIn -> vm.act("Thanks for checking in") { it.addNeuroCheckIn(checkIn) }; onBack() }
        "focus" -> FocusPage(state, fields, readOnly, onBack, vm)
        "routines" -> RoutinesList(state.routines, state.runs, today, readOnly, onBack, open)
        "routine/edit" -> RoutineEditor(
            state.routines.firstOrNull { it.id == id }, state.canBreakDown,
            breakDown = { task -> c.api.breakIntoSteps(task).steps },
            onBack = onBack,
            onSave = { r -> vm.act("Routine saved") { it.saveRoutine(r) }; onBack() },
            onDelete = { routineId -> vm.act("Routine deleted") { it.deleteRoutine(routineId) }; onBack() },
        )
        "routine/run" -> {
            val routine = state.routines.firstOrNull { it.id == id }
            if (routine == null) {
                Column { BackBar(onBack); Loading() }
                return
            }
            var run by remember(routine.id, state.runs) { mutableStateOf(state.runOf(routine.id, today)) }
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            var stepStarted by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
            RoutineRunner(
                routine, run, now, stepStarted, onBack,
                onDone = { done ->
                    val next = RoutineRun(routine.id!!, today.toString(), done, System.currentTimeMillis())
                    run = next
                    vm.act(if (done.size == routine.steps.size) "All done. Nicely done." else null) { it.saveRoutineRun(next) }
                },
                onStepStarted = { stepStarted = System.currentTimeMillis() },
            )
        }
        "cards" -> CardsScreen(state.cards, state.defaultCards, readOnly, show, onBack) { cards -> vm.act("Cards saved") { it.saveCommunicationCards(cards) } }
        else -> NeuroRoute(open)
    }
}

@Composable
private fun FocusPage(state: NeuroState, fields: Set<String>, readOnly: Boolean, onBack: () -> Unit, vm: NeuroViewModel) {
    val c = appContainer()
    val context = LocalContext.current
    val store = remember { FocusStore.of(context) }
    val running by store.running.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running != null) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    var pending by remember { mutableStateOf<com.health.app.reminders.RunningFocus?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { FocusAlerts.start(context, it, c.reminders.canNotify()) }
        pending = null
    }
    FocusScreen(
        running, state.focus, state.summary?.estimateRatio ?: FocusMath.averageRatio(state.focus), Fields.NEURO_TIME in fields, now, readOnly,
        FocusActions(
            back = onBack,
            start = { focus ->
                val started = focus.copy(start = System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !c.reminders.canNotify()) {
                    // The ongoing notification needs permission; the timer runs either way.
                    FocusStore.of(context).set(started)
                    pending = started
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    FocusAlerts.start(context, started, c.reminders.canNotify())
                }
            },
            addBreak = { running?.let { FocusAlerts.start(context, it.copy(breaks = it.breaks + 1), c.reminders.canNotify()) } },
            finish = { rating, note ->
                running?.let { r ->
                    val session = FocusSession(
                        start = r.start, end = System.currentTimeMillis().coerceAtMost(r.start + 16 * 3_600_000L), kind = r.kind, task = r.task,
                        estimateMinutes = r.estimateMinutes, breaks = r.breaks, rating = rating, note = note,
                    )
                    FocusAlerts.stop(context)
                    vm.act("Saved: ${FocusMath.line(session).ifEmpty { "well done" }}") { it.saveFocus(session) }
                }
            },
            discard = { FocusAlerts.stop(context) },
        ),
    )
}
