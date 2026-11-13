@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.health.app.ui.modules.neuro

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentInd
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.EnergyDay
import com.health.api.FieldChoices
import com.health.api.Fields
import com.health.api.Module
import com.health.api.NeuroSummary
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.app.HealthApp
import com.health.app.MainActivity
import com.health.app.data.DeviceChoices
import com.health.app.net.energyDays
import com.health.app.net.saveFieldChoices
import com.health.app.net.saveOverload
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.modules.NavRow
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** What the Autism & ADHD page can do, as callbacks (so it can be drawn with sample data). */
class NeuroActions(
    /** Opens one of its pages: "neuro/cards", "neuro/focus", "neuro/overload?id=…". */
    val open: (String) -> Unit = {},
    val quickOverload: (OverloadKind, Int) -> Unit = { _, _ -> },
    val setCalm: (Boolean) -> Unit = {},
    /** Shows a field that's hidden (a tool not yet on). */
    val showField: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

/** The parts of Autism & ADHD a person can turn on, with what each is. */
val NEURO_TOOLS: List<Pair<String, String>> = listOf(
    Fields.NEURO_OVERLOAD to "Times things got too much: meltdowns, shutdowns and overwhelm",
    Fields.NEURO_SENSORY to "Your senses, and sensory overload",
    Fields.NEURO_BURNOUT to "Burnout, gently watched for",
    Fields.NEURO_MASKING to "How much you masked",
    Fields.NEURO_SOCIAL to "Your social battery",
    Fields.NEURO_EXECUTIVE to "Starting, focus, organising, remembering, switching",
    Fields.NEURO_FOCUS to "A focus timer and body doubling",
    Fields.NEURO_TIME to "How long things really take",
    Fields.NEURO_ROUTINES to "Routines, step by step",
    Fields.NEURO_INTERESTS to "Special interests",
    Fields.NEURO_STIMS to "Stims that help",
    Fields.NEURO_MED_EFFECT to "How medication worked",
    Fields.NEURO_CARDS to "Cards to show when talking is hard",
    Fields.NEURO_ABOUT_ME to "About me, to show people",
    Fields.NEURO_CALM to "Calm mode",
)

/** Whether any check-in item shows. */
fun checkInShown(fields: Set<String>) = listOf(
    Fields.NEURO_MASKING, Fields.NEURO_SOCIAL, Fields.NEURO_SENSORY, Fields.NEURO_EXECUTIVE, Fields.NEURO_BURNOUT, Fields.NEURO_MED_EFFECT,
).any { it in fields }

@Composable
fun NeuroRoute(open: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { NeuroViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val choices = remember { DeviceChoices.of(context) }
    val calm by choices.calm.collectAsState()
    val fields = shownFields()
    val layout by c.fields.collectAsState()
    val scope = rememberCoroutineScope()
    // Routine reminders follow the routines (only the person's own).
    LaunchedEffect(state.routines, state.runs) {
        if (c.viewing.value == null && !state.loading) c.routineReminders.update(state.routines, state.runs, c.zone())
    }
    NeuroHome(
        state, fields, LocalDate.now(c.zone()), c.zone(), calm,
        NeuroActions(
            open = open,
            quickOverload = { kind, intensity ->
                vm.act("Logged. Go gently.") { it.saveOverload(OverloadEvent(start = System.currentTimeMillis(), kind = kind, intensity = intensity)) }
            },
            setCalm = choices::setCalm,
            showField = { key ->
                scope.launch {
                    runCatching {
                        val now = layout
                        c.api.saveFieldChoices(FieldChoices(show = now?.show.orEmpty() + key, hide = now?.hide.orEmpty() - key))
                        c.refreshFields()
                    }.onFailure { c.tell(it.message ?: "Couldn't turn that on") }
                }
            },
            retry = { vm.load() },
        ),
    )
}

@Composable
fun NeuroHome(state: NeuroState, fields: Set<String>, today: LocalDate, zone: ZoneId, calm: Boolean, actions: NeuroActions) {
    val readOnly = LocalReadOnly.current
    var quick by remember { mutableStateOf(false) }
    fun shows(key: String) = key in fields
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Autism & ADHD", "Your senses, energy and tools")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The things wanted fastest come first, big.
            QuickRow(fields, readOnly, onCards = { actions.open("neuro/cards") }, onTooMuch = { quick = true }, onFocus = { actions.open("neuro/focus") }, onCheckIn = { actions.open("neuro/checkin") })
            when {
                state.loading && state.summary == null -> Loading()
                state.error != null && state.summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                else -> {
                    state.summary?.burnoutRisk?.takeIf { shows(Fields.NEURO_BURNOUT) || shows(Fields.NEURO_MASKING) || shows(Fields.NEURO_OVERLOAD) }?.let { risk ->
                        InfoCard("Look after yourself", icon = Icons.Rounded.Favorite, iconTint = AppTheme.status.warn) {
                            Text(risk, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    EnergyCard(state.energyOn(today), shows(Fields.NEURO_SOCIAL), readOnly) { actions.open("neuro/energy") }
                    if (shows(Fields.NEURO_OVERLOAD) || shows(Fields.NEURO_SENSORY)) OverloadCard(state, zone, readOnly, actions)
                    state.summary?.patterns?.forEach { InsightCard(it) }
                    if (checkInShown(fields)) CheckInCard(state, zone, readOnly) { actions.open("neuro/checkin") }
                    if (shows(Fields.NEURO_FOCUS) || shows(Fields.NEURO_TIME)) FocusCard(state, shows(Fields.NEURO_TIME), readOnly) { actions.open("neuro/focus") }
                    if (shows(Fields.NEURO_ROUTINES)) RoutinesCard(state, today, readOnly, actions.open)
                    if (shows(Fields.NEURO_CARDS)) CardsPreview(state, actions.open)
                    if (shows(Fields.NEURO_ABOUT_ME)) AboutMeCard(state, fields, readOnly, actions.open)
                    if (shows(Fields.NEURO_CALM) && !readOnly) {
                        InfoCard("Calm mode", icon = Icons.Rounded.Spa) {
                            LabeledSwitch(
                                "Calm mode", calm, actions.setCalm,
                                subtitle = "Less motion, no glow behind the pages, and softer colours everywhere in the app. On this phone only.",
                            )
                        }
                    }
                    if (!readOnly) MoreTools(fields, actions.showField)
                }
            }
            Hint(
                "Autistic and ADHD experiences are part of who you are, not something wrong with you. This is here to help you notice what " +
                    "drains you and what helps, in your own words.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    if (quick) {
        ModalBottomSheet(onDismissRequest = { quick = false }) {
            QuickOverload(
                onLog = { kind, intensity -> actions.quickOverload(kind, intensity); quick = false },
                onDetail = { quick = false; actions.open("neuro/overload") },
            )
        }
    }
}

@Composable
private fun QuickRow(fields: Set<String>, readOnly: Boolean, onCards: () -> Unit, onTooMuch: () -> Unit, onFocus: () -> Unit, onCheckIn: () -> Unit) {
    val buttons = buildList<Triple<String, ImageVector, () -> Unit>> {
        if (Fields.NEURO_CARDS in fields) add(Triple("Show a card", Icons.Rounded.TouchApp, onCards))
        if (!readOnly && (Fields.NEURO_OVERLOAD in fields || Fields.NEURO_SENSORY in fields)) add(Triple("It's too much", Icons.Rounded.Waves, onTooMuch))
        if (!readOnly && Fields.NEURO_FOCUS in fields) add(Triple("Focus", Icons.Rounded.Timer, onFocus))
        if (!readOnly && checkInShown(fields)) add(Triple("Check in", Icons.Rounded.Insights, onCheckIn))
    }
    if (buttons.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        buttons.forEachIndexed { i, (label, icon, onClick) ->
            val modifier = Modifier.weight(1f).heightIn(min = 56.dp)
            if (i == 0) {
                Button(onClick = onClick, modifier = modifier) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label) }
            } else {
                FilledTonalButton(onClick = onClick, modifier = modifier) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label) }
            }
        }
    }
}

/** Logging a hard moment in two taps: what kind, how strong, now. */
@Composable
fun QuickOverload(onLog: (OverloadKind, Int) -> Unit, onDetail: () -> Unit) {
    var kind by remember { mutableStateOf(OverloadKind.OVERWHELM) }
    var intensity by remember { mutableStateOf<Int?>(3) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Things got too much", style = MaterialTheme.typography.titleLarge)
        Hint("Log it now, add detail later if you want. There's nothing to apologise for.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverloadKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        StepScale("How strong", intensity, "Mild", "Intense", { intensity = it })
        Button(onClick = { onLog(kind, intensity ?: 3) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Log it now") }
        OutlinedButton(onClick = onDetail, modifier = Modifier.fillMaxWidth()) { Text("Add more detail") }
    }
}

/** Today's energy budget: what's left, and the social battery. */
@Composable
fun EnergyCard(day: EnergyDay?, social: Boolean, readOnly: Boolean, onOpen: () -> Unit) {
    InfoCard(
        "Energy today", icon = Icons.Rounded.BatteryChargingFull,
        trailing = { if (!readOnly) TextButton(onClick = onOpen) { Text(if (day?.startWith == null) "Plan today" else "Update") } },
    ) {
        if (day == null || (day.startWith == null && day.spends.isEmpty() && day.socialBattery == null)) {
            Hint("Start the day with a number of spoons (or any unit you like), then note what uses them and what gives some back.")
            return@InfoCard
        }
        EnergyMath.leftText(day)?.let { text ->
            Text(text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            ProgressBar(EnergyMath.fraction(day) ?: 0f, color = if (EnergyMath.low(day)) AppTheme.status.warn else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        Hint(listOfNotNull(
            "used ${EnergyMath.used(day)}".takeIf { day.spends.isNotEmpty() },
            "${EnergyMath.gained(day)} back from rest and things that help".takeIf { EnergyMath.gained(day) > 0 },
        ).joinToString(" · "))
        if (EnergyMath.low(day)) Text("Running low. It's OK to leave things for tomorrow.", style = MaterialTheme.typography.bodyMedium, color = AppTheme.status.warn)
        if (social) day.socialBattery?.let { Hint("Social battery: $it of 10") }
    }
}

@Composable
private fun OverloadCard(state: NeuroState, zone: ZoneId, readOnly: Boolean, actions: NeuroActions) {
    val s = state.summary
    InfoCard(
        "Times things got too much", icon = Icons.Rounded.Waves,
        trailing = { if (!readOnly) TextButton(onClick = { actions.open("neuro/overload") }) { Text("Log") } },
    ) {
        if (s == null || s.overloads == 0) {
            Hint("None logged in the last 30 days. When things get too much, logging it helps you see what sets it off and what helps.")
        } else {
            Text("${s.overloads} in the last ${s.days} days", style = MaterialTheme.typography.titleMedium)
            if (s.byKind.isNotEmpty()) Hint(s.byKind.joinToString(" · ") { "${it.count} ${it.name.lowercase()}" })
            if (s.triggers.isNotEmpty()) DetailLine("Often with", s.triggers.take(4).joinToString(", ") { it.name })
            if (s.whatHelped.isNotEmpty()) DetailLine("What helped", s.whatHelped.take(4).joinToString(", ") { it.name })
        }
        state.events.take(3).forEach { e ->
            NavRow(
                Icons.Rounded.Waves, "${e.kind.label}, ${e.intensity} of 5",
                listOfNotNull(CareFormat.dayTime(e.start, zone), e.whatHelped.takeIf { it.isNotEmpty() }?.let { "helped: ${it.joinToString(", ")}" }).joinToString(" · "),
                onClick = { if (!readOnly) actions.open("neuro/overload?id=${e.id}") },
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(top = 4.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckInCard(state: NeuroState, zone: ZoneId, readOnly: Boolean, onCheckIn: () -> Unit) {
    val s = state.summary
    InfoCard("Check-ins", icon = Icons.Rounded.Insights, trailing = { if (!readOnly) TextButton(onClick = onCheckIn) { Text("Check in") } }) {
        val last = state.checkIns.firstOrNull()
        if (last == null) {
            Hint("A minute to notice masking, how loud the day's been, and how your brain's working today. As often as you like.")
        } else {
            Hint("Last: ${CareFormat.dayTime(last.at, zone)}")
            val bits = listOfNotNull(
                last.masking?.let { "masking $it of 5" }, last.socialLoad?.let { "social $it of 5" }, last.sensoryLoad?.let { "sensory $it of 5" },
                last.burnout?.let { "depleted $it of 5" }, last.medEffect?.let { "medication helped $it of 5" },
            )
            if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        s?.averageMasking?.let { Hint("Masking averaged ${it} of 5 over ${s.days} days.") }
    }
}

@Composable
private fun FocusCard(state: NeuroState, time: Boolean, readOnly: Boolean, onFocus: () -> Unit) {
    val s: NeuroSummary? = state.summary
    InfoCard("Focus", icon = Icons.Rounded.Timer, trailing = { if (!readOnly) TextButton(onClick = onFocus) { Text("Start") } }) {
        val minutes = s?.focusMinutes ?: 0
        Text(if (minutes > 0) "${FocusMath.mins(minutes)} in the last ${s?.days ?: 30} days" else "No focus sessions yet", style = MaterialTheme.typography.titleMedium)
        Hint("A timer for a task, body doubling, or time for a special interest.")
        if (time) {
            (s?.estimateRatio ?: FocusMath.averageRatio(state.focus))?.let { Text(FocusMath.describe(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                ?: Hint("Guess how long before you start; after a few sessions you'll see how long things really take.")
        }
        state.focus.filter { it.end != null }.take(2).forEach { f ->
            Text("${f.task ?: f.kind.label}: ${FocusMath.line(f)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RoutinesCard(state: NeuroState, today: LocalDate, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("Routines", icon = Icons.Rounded.Checklist, trailing = { TextButton(onClick = { open("neuro/routines") }) { Text("All") } }) {
        val active = state.routines.filter { it.active }
        if (active.isEmpty()) {
            Hint("A routine is a short checklist with how long each step takes, and a gentle reminder if you'd like one.")
            if (!readOnly) OutlinedButton(onClick = { open("neuro/routine/edit") }, modifier = Modifier.padding(top = 8.dp)) { Text("Make a routine") }
        }
        val dow = today.dayOfWeek.value
        active.filter { it.days.isEmpty() || dow in it.days }.take(4).forEach { r ->
            val done = state.runOf(r.id, today)?.done?.size ?: 0
            NavRow(
                Icons.Rounded.Checklist, r.name,
                "$done of ${r.steps.size} done today" + (r.reminder?.let { " · at $it" } ?: ""),
                onClick = { if (!readOnly) open("neuro/routine/run?id=${r.id}") },
            )
        }
    }
}

@Composable
private fun CardsPreview(state: NeuroState, open: (String) -> Unit) {
    InfoCard("Communication cards", icon = Icons.Rounded.TouchApp, trailing = { TextButton(onClick = { open("neuro/cards") }) { Text("Open") } }) {
        Hint("Big, clear cards to show someone when talking is hard.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            state.cards.take(3).forEach { card -> AssistChip(onClick = { open("neuro/cards?show=${card.id}") }, label = { Text(card.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) }
        }
    }
}

@Composable
private fun AboutMeCard(state: NeuroState, fields: Set<String>, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("About me", icon = Icons.Rounded.AssignmentInd) {
        val p = state.profile
        Text(NeuroWords.describe(p) ?: "Who you are, how your senses work, how you like to communicate and what helps: in one place to show people.", style = MaterialTheme.typography.bodyMedium)
        if (p.whatHelps.isNotEmpty()) Hint("What helps: ${p.whatHelps.take(4).joinToString(", ")}")
        if (Fields.NEURO_INTERESTS in fields && p.interests.isNotEmpty()) Hint("Interests: ${p.interests.take(4).joinToString(", ")}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (!readOnly) OutlinedButton(onClick = { open("neuro/about") }) { Text("Edit") }
            FilledTonalButton(onClick = { open("neuro/about/show") }) { Text("Show someone") }
        }
    }
}

@Composable
private fun MoreTools(fields: Set<String>, onShow: (String) -> Unit) {
    val hidden = NEURO_TOOLS.filter { it.first !in fields }
    if (hidden.isEmpty()) return
    InfoCard("More you can turn on", icon = Icons.Rounded.Tune) {
        Hint("Only what's useful to you shows. Turn on any of these:")
        hidden.forEach { (key, about) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(about, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onShow(key) }) { Text("Turn on") }
            }
        }
    }
}

// ---------------------------------------------------------------- In Mind

/** Opens a page of the app from anywhere, the way a notification does. */
fun openInApp(context: Context, route: String) {
    context.startActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN, route),
    )
}

/**
 * What Mind shows of Autism & ADHD: the way in, when it's on, and the energy budget when Mind's
 * energy budget field is on (the same days as Autism & ADHD's). Loads its own; draws nothing
 * where the app isn't running (as in screenshots).
 */
@Composable
fun MindNeuroSection() {
    val app = LocalContext.current.applicationContext as? HealthApp ?: return
    val c = app.container
    val fields = shownFields()
    val modules = com.health.app.ui.care.shownModules()
    val revision by c.revision.collectAsState()
    val context = LocalContext.current
    val energy = Fields.MIND_ENERGY_BUDGET in fields
    var day by remember { mutableStateOf<EnergyDay?>(null) }
    LaunchedEffect(revision, energy) {
        if (energy) day = runCatching { c.ui.energyDays(1).firstOrNull { it.date == LocalDate.now(c.zone()).toString() } }.getOrNull()
    }
    MindNeuroCards(Module.NEURO in modules, energy, day, LocalReadOnly.current, Fields.NEURO_SOCIAL in fields) { openInApp(context, it) }
}

/** The cards Mind shows for Autism & ADHD and the energy budget. */
@Composable
fun MindNeuroCards(neuro: Boolean, energy: Boolean, day: EnergyDay?, readOnly: Boolean, social: Boolean, open: (String) -> Unit) {
    if (energy) EnergyCard(day, social, readOnly) { open("neuro/energy") }
    if (neuro) {
        InfoCard(null) {
            NavRow(Icons.Rounded.SelfImprovement, "Autism & ADHD", "Your senses, energy, focus, routines and communication cards", onClick = { open("neuro") })
        }
    }
}
