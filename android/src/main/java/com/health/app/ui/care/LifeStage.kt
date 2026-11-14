@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChildFriendly
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.health.app.attempt
import com.health.app.ui.components.ArrangeItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.Contraction
import com.health.api.Feed
import com.health.api.FeedKind
import com.health.api.HotFlash
import com.health.api.KickSession
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.LifeStageOverview
import com.health.api.Module
import com.health.app.Container
import com.health.app.net.addHotFlash
import com.health.app.net.lifeStageOverview
import com.health.app.net.saveContraction
import com.health.app.net.saveFeed
import com.health.app.net.saveKicks
import com.health.app.net.saveLifeStage
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Pill
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Movements being counted right now. */
data class KickProgress(val start: Long, val kicks: Int)

data class LifeStageState(
    val today: LocalDate,
    val overview: LifeStageOverview? = null,
    val counting: KickProgress? = null,
    /** A contraction being timed (saved when it starts, finished when it stops). */
    val contraction: Contraction? = null,
    val feed: Feed? = null,
    val error: String? = null,
)

class LifeStageViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LifeStageState(LocalDate.now(c.zone())))
    val state: StateFlow<LifeStageState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load() } }
    }

    fun load() {
        if (Module.CYCLE !in c.shownModules()) return
        viewModelScope.launch {
            try {
                val overview = c.ui.lifeStageOverview(30)
                _state.update { s ->
                    s.copy(
                        overview = overview, error = null, today = LocalDate.now(c.zone()),
                        // Something started on another device and not stopped yet carries on here.
                        contraction = s.contraction ?: overview.contractions.firstOrNull { it.end == null },
                        feed = s.feed ?: overview.feeds.firstOrNull { it.end == null },
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't load your life stage") }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            message?.let(c::tell)
            c.dataChanged()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            c.tell(e.message ?: "That didn't save")
        }
    }

    fun saveInfo(info: LifeStageInfo) = act("Saved") { c.api.saveLifeStage(info) }

    fun startKicks() = _state.update { it.copy(counting = KickProgress(System.currentTimeMillis(), 0)) }

    fun kick() = _state.update { s -> s.copy(counting = s.counting?.let { it.copy(kicks = it.kicks + 1) }) }

    fun finishKicks() {
        val progress = _state.value.counting ?: return
        _state.update { it.copy(counting = null) }
        if (progress.kicks == 0) return
        act("${progress.kicks} ${if (progress.kicks == 1) "movement" else "movements"} saved") {
            c.api.saveKicks(KickSession(progress.start, System.currentTimeMillis(), progress.kicks))
        }
    }

    fun cancelKicks() = _state.update { it.copy(counting = null) }

    fun toggleContraction() {
        val running = _state.value.contraction
        if (running == null) {
            val started = Contraction(System.currentTimeMillis())
            _state.update { it.copy(contraction = started) }
            act(null) {
                val saved = c.api.saveContraction(started)
                _state.update { s -> if (s.contraction?.start == started.start) s.copy(contraction = saved) else s }
            }
        } else {
            _state.update { it.copy(contraction = null) }
            act(null) { c.api.saveContraction(running.copy(end = System.currentTimeMillis())) }
        }
    }

    fun startFeed(kind: FeedKind) {
        val started = Feed(System.currentTimeMillis(), kind = kind)
        _state.update { it.copy(feed = started) }
        act(null) {
            val saved = c.api.saveFeed(started)
            _state.update { s -> if (s.feed?.start == started.start) s.copy(feed = saved) else s }
        }
    }

    fun stopFeed() {
        val running = _state.value.feed ?: return
        _state.update { it.copy(feed = null) }
        act("Feed saved") { c.api.saveFeed(running.copy(end = System.currentTimeMillis())) }
    }

    fun hotFlash(intensity: Int) = act("Hot flash noted") {
        val now = System.currentTimeMillis()
        val hour = java.time.Instant.ofEpochMilli(now).atZone(c.zone()).hour
        c.api.addHotFlash(HotFlash(now, intensity, night = hour !in 7..21))
    }
}

class LifeStageActions(
    val saveInfo: (LifeStageInfo) -> Unit = {},
    val startKicks: () -> Unit = {},
    val kick: () -> Unit = {},
    val finishKicks: () -> Unit = {},
    val cancelKicks: () -> Unit = {},
    val toggleContraction: () -> Unit = {},
    val startFeed: (FeedKind) -> Unit = {},
    val stopFeed: () -> Unit = {},
    val hotFlash: (Int) -> Unit = {},
)

/**
 * The life stage's cards for [stage], in the default order: sections of the Cycle screen
 * ([com.health.app.ui.modules.FeatureScreens.CYCLE]), so they're arranged with it. None before it's loaded.
 */
fun lifeStageSections(stage: LifeStage?): List<ArrangeItem> = if (stage == null) emptyList() else buildList {
    add(ArrangeItem("stage", "Life stage"))
    when (stage) {
        LifeStage.PREGNANT -> {
            add(ArrangeItem("stage.pregnancy", "Pregnancy"))
            add(ArrangeItem("stage.movements", "Movements"))
            add(ArrangeItem("stage.contractions", "Contractions"))
        }
        LifeStage.POSTPARTUM -> add(ArrangeItem("stage.feeds", "Feeds"))
        LifeStage.PERIMENOPAUSE, LifeStage.MENOPAUSE -> add(ArrangeItem("stage.hotflashes", "Hot flashes"))
        else -> Unit
    }
    add(ArrangeItem("stage.tips", "Tips for this stage"))
}

/** The life stage's part of the Cycle screen, all its cards in the default order. */
@Composable
fun LifeStageSection(state: LifeStageState, zone: ZoneId, actions: LifeStageActions, now: () -> Long = System::currentTimeMillis) {
    val overview = state.overview ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lifeStageSections(overview.info.stage).forEach { LifeStagePart(it.id, state, zone, actions, now) }
    }
}

/** One of the life stage's cards, by its section id ([lifeStageSections]). */
@Composable
fun LifeStagePart(id: String, state: LifeStageState, zone: ZoneId, actions: LifeStageActions, now: () -> Long = System::currentTimeMillis) {
    val overview = state.overview ?: return
    val readOnly = LocalReadOnly.current
    when (id) {
        "stage" -> StageCard(state, overview.info, readOnly, actions)
        "stage.pregnancy" -> PregnancyCard(overview)
        "stage.movements" -> KickCard(state, zone, readOnly, actions, now)
        "stage.contractions" -> ContractionCard(state, overview, zone, readOnly, actions, now)
        "stage.feeds" -> FeedsCard(state, overview, zone, readOnly, actions, now)
        "stage.hotflashes" -> HotFlashCard(overview, zone, readOnly, actions)
        "stage.tips" -> overview.tips.forEach { InsightCard(it) }
    }
}

/** Which stage, what it changes, and a way to change it. */
@Composable
private fun StageCard(state: LifeStageState, info: LifeStageInfo, readOnly: Boolean, actions: LifeStageActions) {
    var choosing by remember { mutableStateOf(false) }
    AppCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Hint("Life stage")
                Text(info.stage.label, style = MaterialTheme.typography.titleMedium)
                stageNote(info.stage)?.let { Hint(it) }
            }
            if (!readOnly) TextButton(onClick = { choosing = true }) { Text("Change") }
        }
    }
    if (choosing) StageDialog(info, state.today, onDismiss = { choosing = false }) { actions.saveInfo(it); choosing = false }
}

/** What each stage changes about the cycle's predictions. */
fun stageNote(stage: LifeStage): String? = when (stage) {
    LifeStage.CYCLING -> null
    LifeStage.TRYING -> "The fertile window is an estimate; ovulation tests and temperature make it surer."
    LifeStage.PREGNANT -> "Period predictions are paused."
    LifeStage.POSTPARTUM -> "Period predictions are paused until periods come back."
    LifeStage.PERIMENOPAUSE -> "Cycles often vary now, so predictions are shown wider."
    LifeStage.MENOPAUSE -> "Period predictions are off. Any bleeding is worth mentioning to a doctor."
}

@Composable
private fun PregnancyCard(overview: LifeStageOverview) {
    val p = overview.pregnancy ?: return
    AppCard(padding = 18.dp) {
        Text("Week ${p.week}" + if (p.day > 0) " + ${p.day}" else "", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Pill(listOf("", "First trimester", "Second trimester", "Third trimester").getOrElse(p.trimester) { "Trimester ${p.trimester}" }, MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Due ${CareFormat.date(p.dueDate) ?: p.dueDate}" + when {
                p.daysToGo > 1 -> " · ${p.daysToGo} days to go"
                p.daysToGo == 1 -> " · tomorrow"
                p.daysToGo == 0 -> " · today"
                else -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun rememberTick(running: Boolean, now: () -> Long): Long {
    var tick by remember { mutableLongStateOf(now()) }
    LaunchedEffect(running) {
        while (running) {
            tick = now()
            delay(1_000)
        }
    }
    return tick
}

@Composable
private fun KickCard(state: LifeStageState, zone: ZoneId, readOnly: Boolean, actions: LifeStageActions, now: () -> Long) {
    val counting = state.counting
    val tick = rememberTick(counting != null, now)
    InfoCard("Movements", icon = Icons.Rounded.TouchApp) {
        if (counting != null) {
            Text("${counting.kicks}", style = MaterialTheme.typography.displayMedium)
            Hint("in ${CareFormat.clock(tick - counting.start)}")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = actions.kick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = "Count a movement" },
            ) { Text("A movement", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = actions.finishKicks) { Text("Done") }
                TextButton(onClick = actions.cancelKicks) { Text("Cancel") }
            }
        } else {
            Hint("Get to know your baby's usual pattern. If movements slow down or change, contact your midwife or maternity unit straight away; don't wait.")
            if (!readOnly) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = actions.startKicks) { Text("Start counting") }
            }
        }
        state.overview?.kicks?.sortedByDescending { it.start }?.take(3)?.forEach { k ->
            val minutes = k.end?.let { ((it - k.start) / 60_000).coerceAtLeast(1) }
            Text(
                "${CareFormat.dayTime(k.start, zone)} · ${k.kicks} ${if (k.kicks == 1) "movement" else "movements"}" + (minutes?.let { " in $it min" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ContractionCard(state: LifeStageState, overview: LifeStageOverview, zone: ZoneId, readOnly: Boolean, actions: LifeStageActions, now: () -> Long) {
    val running = state.contraction
    val tick = rememberTick(running != null, now)
    val summary = StageMath.contractions(overview.contractions, tick)
    InfoCard("Contractions", icon = Icons.Rounded.Timer) {
        val pattern = overview.contractionsPattern
        if (pattern != null || summary.fiveOneOne) {
            AppCard(padding = 12.dp) {
                Text(pattern ?: "About 5 minutes apart, lasting about a minute, for an hour: time to call your midwife or maternity unit.", style = MaterialTheme.typography.titleSmall, color = AppTheme.status.warn)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!readOnly) {
            Button(onClick = actions.toggleContraction, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(if (running == null) "Contraction started" else "Stopped · ${CareFormat.clock(tick - running.start)}", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (summary.count > 0) {
            Hint(
                buildString {
                    append("Last hour: ${summary.count}")
                    summary.averageIntervalMs?.let { append(", about ${CareFormat.duration(it)} apart") }
                    summary.averageDurationMs?.let { append(", each lasting about ${CareFormat.duration(it)}") }
                },
                Modifier.padding(top = 8.dp),
            )
        }
        overview.contractions.filter { it.end != null }.sortedByDescending { it.start }.take(4).forEach { c ->
            Text("${CareFormat.time(c.start, zone)} · ${CareFormat.duration(c.end!! - c.start)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun FeedsCard(state: LifeStageState, overview: LifeStageOverview, zone: ZoneId, readOnly: Boolean, actions: LifeStageActions, now: () -> Long) {
    val running = state.feed
    val tick = rememberTick(running != null, now)
    InfoCard("Feeds", icon = Icons.Rounded.ChildFriendly) {
        overview.weeksSinceBirth?.let { Text("$it ${if (it == 1) "week" else "weeks"} since the birth", style = MaterialTheme.typography.titleMedium) }
        if (running != null) {
            Text("${running.kind.label} · ${CareFormat.clock(tick - running.start)}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))
            if (!readOnly) Button(onClick = actions.stopFeed, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Stop") }
        } else if (!readOnly) {
            Hint("Tap to start timing a feed.", Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                FeedKind.entries.forEach { k -> FilledTonalButton(onClick = { actions.startFeed(k) }) { Text(k.label) } }
            }
        }
        val done = overview.feeds.filter { it.end != null }.sortedByDescending { it.start }
        done.firstOrNull()?.let { last ->
            Hint("Last: ${last.kind.label.lowercase()}, ${CareFormat.time(last.start, zone)}" + (last.amountMl?.let { " · $it ml" } ?: ""), Modifier.padding(top = 8.dp))
        }
        done.take(5).forEach { f ->
            Text(
                "${CareFormat.time(f.start, zone)} · ${f.kind.label} · ${CareFormat.duration(f.end!! - f.start)}" + (f.amountMl?.let { " · $it ml" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HotFlashCard(overview: LifeStageOverview, zone: ZoneId, readOnly: Boolean, actions: LifeStageActions) {
    InfoCard("Hot flashes", icon = Icons.Rounded.LocalFireDepartment) {
        overview.monthsSinceLastPeriod?.let { m ->
            Text(
                if (m >= 12) "A year or more since your last period" else "$m ${if (m == 1) "month" else "months"} since your last period",
                style = MaterialTheme.typography.titleMedium,
            )
            if (overview.info.stage == LifeStage.PERIMENOPAUSE && m >= 12) Hint("Twelve months without a period is when menopause is counted.")
        }
        overview.hotFlashesPerDay?.let { Hint("About ${com.health.app.ui.Format.upTo2(it)} a day lately", Modifier.padding(top = 4.dp)) }
        if (!readOnly) {
            Hint("Log one as it happens:", Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(1 to "Mild", 2 to "Moderate", 3 to "Strong").forEach { (i, label) -> FilledTonalButton(onClick = { actions.hotFlash(i) }) { Text(label) } }
            }
        }
        overview.hotFlashes.sortedByDescending { it.at }.take(4).forEach { h ->
            Text(
                "${CareFormat.dayTime(h.at, zone)} · ${listOf("mild", "moderate", "strong").getOrElse(h.intensity - 1) { "" }}${if (h.night) " · at night" else ""}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StageDialog(current: LifeStageInfo, today: LocalDate, onDismiss: () -> Unit, onSave: (LifeStageInfo) -> Unit) {
    var stage by remember { mutableStateOf(current.stage) }
    var due by remember { mutableStateOf(current.dueDate?.let { attempt { LocalDate.parse(it) }.getOrNull() }) }
    var birth by remember { mutableStateOf(current.birthDate?.let { attempt { LocalDate.parse(it) }.getOrNull() }) }
    var picking by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Life stage") },
        text = {
            Column {
                LifeStage.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().selectable(stage == s, role = Role.RadioButton) { stage = s }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = stage == s, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                when (stage) {
                    LifeStage.PREGNANT -> OutlinedButton(onClick = { picking = "due" }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(due?.let { "Due ${CareFormat.date(it)}" } ?: "Choose the due date")
                    }
                    LifeStage.POSTPARTUM -> OutlinedButton(onClick = { picking = "birth" }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(birth?.let { "Birth on ${CareFormat.date(it)}" } ?: "Choose the date of the birth")
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (stage != LifeStage.PREGNANT || due != null) && (stage != LifeStage.POSTPARTUM || birth != null),
                onClick = {
                    onSave(
                        LifeStageInfo(
                            stage = stage,
                            dueDate = due?.toString()?.takeIf { stage == LifeStage.PREGNANT },
                            lastPeriod = current.lastPeriod.takeIf { stage == LifeStage.PREGNANT },
                            birthDate = birth?.toString()?.takeIf { stage == LifeStage.POSTPARTUM },
                            since = if (stage == current.stage) current.since else today.toString(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (picking) {
        "due" -> DatePickDialog(due ?: today.plusMonths(6), { picking = null }) { due = it; picking = null }
        "birth" -> DatePickDialog(birth ?: today, { picking = null }) { birth = it; picking = null }
    }
}
