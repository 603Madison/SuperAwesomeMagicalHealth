@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Fields
import com.health.api.Metric
import com.health.api.Milestone
import com.health.api.MilestoneStatus
import com.health.api.Module
import com.health.api.MomentKind
import com.health.api.Units
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.care.RangeChart
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.modules.NavRow
import com.health.app.ui.modules.body.MeasurementFormat
import com.health.app.ui.theme.AppTheme
import java.time.ZoneId
import kotlin.math.roundToInt

class TransitionActions(
    val open: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun TransitionRoute(open: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { TransitionViewModel(c) }
    val state by vm.state.collectAsState()
    val profile by c.profile.collectAsState()
    TransitionHome(state, shownFields(), com.health.app.ui.care.shownModules(), profile?.units ?: Units.METRIC, c.zone(), TransitionActions(open) { vm.load() })
}

@Composable
fun TransitionHome(state: TransitionState, fields: Set<String>, modules: Set<Module>, units: Units, zone: ZoneId, actions: TransitionActions) {
    val readOnly = LocalReadOnly.current
    val o = state.overview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Transition", o?.profile?.pronouns?.let { "Pronouns: $it" } ?: "Whatever it means to you, at your pace") {
            if (!readOnly) TextButton(onClick = { actions.open("transition/profile") }) { Text("Profile") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading && o == null -> Loading()
                state.error != null && o == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                o != null -> {
                    AppCard(padding = 18.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Since(o.sinceStart ?: "—", "since you began", Modifier.weight(1f))
                            o.onHormones?.let { Since(it, "on hormones", Modifier.weight(1f)) }
                        }
                        if (o.sinceStart == null && !readOnly) Hint("Add when you began, in whatever sense matters to you, in your profile.", Modifier.padding(top = 6.dp))
                        if (o.profile.goals.isNotEmpty()) {
                            Text("Your goals", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                            o.profile.goals.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    if (o.highlights.isNotEmpty()) {
                        InfoCard("Lately", icon = Icons.Rounded.AutoAwesome) {
                            o.highlights.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                    }
                    if (Fields.TRANSITION_MILESTONES in fields) MilestonesCard(o.milestones, readOnly, actions.open)
                    if (Fields.TRANSITION_MOMENTS in fields) MomentsCard(state, zone, readOnly, actions.open)
                    if (Fields.TRANSITION_CHANGES in fields) ChangesCard(state, readOnly, actions.open)
                    if (Fields.TRANSITION_VOICE in fields) VoiceCard(state, zone, readOnly, actions.open)
                    if (Fields.TRANSITION_HAIR in fields) HairCard(state, zone, readOnly, actions.open)
                    if (Fields.TRANSITION_MEASUREMENTS in fields) MeasurementsCard(state, units, actions.open)
                    if (Module.PHOTOS in modules) {
                        InfoCard(null) { NavRow(Icons.Rounded.PhotoLibrary, "Progress photos", "Private, and compared side by side", onClick = { actions.open("photos") }) }
                    }
                }
            }
            Hint(
                "This is yours: there's no right order, pace or end point. Leave out anything that doesn't fit you.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** A length of time, large, wrapping onto two lines when it needs to. */
@Composable
private fun Since(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MilestonesCard(milestones: List<Milestone>, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("Milestones", icon = Icons.Rounded.Flag, trailing = { if (!readOnly) TextButton(onClick = { open("transition/templates") }) { Text("Add") } }) {
        if (milestones.isEmpty()) {
            Hint("Steps that matter to you: telling someone, a new name on your documents, starting something new. Start from a suggestion or write your own.")
            if (!readOnly) FilledTonalButton(onClick = { open("transition/templates") }, modifier = Modifier.padding(top = 8.dp)) { Text("Add a milestone") }
        }
        TransitionWords.STATUS_ORDER.forEach { status ->
            val list = milestones.filter { it.status == status }
            if (list.isEmpty()) return@forEach
            Text(status.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
            list.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    NavRow(
                        Icons.Rounded.Flag, m.title,
                        listOfNotNull(m.area.label, CareFormat.date(m.date), m.checklist.takeIf { it.isNotEmpty() }?.let { c -> "${c.count { it.done }} of ${c.size} steps" }).joinToString(" · "),
                        onClick = { if (!readOnly) open("transition/milestone?id=${m.id}") },
                    )
                    if (m.checklist.isNotEmpty() && m.status != MilestoneStatus.DONE) {
                        ProgressBar(m.checklist.count { it.done }.toFloat() / m.checklist.size, Modifier.padding(start = 58.dp), height = 6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentsCard(state: TransitionState, zone: ZoneId, readOnly: Boolean, open: (String) -> Unit) {
    val o = state.overview ?: return
    InfoCard("Euphoria and dysphoria", icon = Icons.Rounded.Spa) {
        Row {
            Stat("${o.euphoria}", "moments of euphoria", Modifier.weight(1f), color = AppTheme.status.good)
            Stat("${o.dysphoria}", "of dysphoria", Modifier.weight(1f))
        }
        Hint("In the last 30 days. Euphoria is worth keeping; dysphoria is worth understanding, and it's OK to reach out when it's heavy.")
        if (!readOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { open("transition/moment?id=EUPHORIA") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Euphoria") }
                OutlinedButton(onClick = { open("transition/moment?id=DYSPHORIA") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Dysphoria") }
            }
        }
        state.moments.take(3).forEach { m ->
            Text(
                "${if (m.kind == MomentKind.EUPHORIA) "Euphoria" else "Dysphoria"}, ${m.intensity} of 5 · ${CareFormat.dayTime(m.at, zone)}" +
                    (if (m.context.isNotEmpty()) " · ${m.context.joinToString(", ")}" else ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
            m.note?.let { Hint(it) }
        }
    }
}

@Composable
private fun ChangesCard(state: TransitionState, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("Changes you notice", icon = Icons.Rounded.Timeline, trailing = { if (!readOnly) TextButton(onClick = { open("transition/change") }) { Text("Add") } }) {
        if (state.changes.isEmpty()) Hint("Skin, hair, voice, how you feel: notice what changes, and how you feel about it.")
        state.changes.take(5).forEach { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Text(c.area.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(CareFormat.date(c.date).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodyMedium)
                TransitionWords.feeling(c.feeling)?.let { Hint("Feeling: $it") }
            }
        }
    }
}

@Composable
private fun VoiceCard(state: TransitionState, zone: ZoneId, readOnly: Boolean, open: (String) -> Unit) {
    val voice = state.voice
    val target = state.overview?.profile?.voiceTargetHz
    InfoCard("Voice", icon = Icons.Rounded.Mic) {
        val minutes = voice.sumOf { it.minutes }
        Row {
            Stat("$minutes", "minutes of practice", Modifier.weight(1f), unit = "min")
            voice.firstOrNull { it.pitchAvgHz != null }?.pitchAvgHz?.let { Stat("${it.roundToInt()}", "latest average", Modifier.weight(1f), unit = "Hz") }
            target?.let { Stat("${it.roundToInt()}", "your target", Modifier.weight(1f), unit = "Hz") }
        }
        val pitched = voice.filter { it.pitchAvgHz != null }.sortedBy { it.at }
        if (pitched.size >= 2) {
            Spacer(Modifier.height(8.dp))
            val band = PitchTarget.band(target)
            RangeChart(pitched.map { it.pitchAvgHz!! }, band?.start, band?.endInclusive, MaterialTheme.colorScheme.primary, AppTheme.data.light.copy(alpha = 0.3f), height = 100.dp)
            Hint("Average pitch by session" + (band?.let { ", with your range shaded" } ?: "") + ". Last 6 months.")
        }
        if (!readOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { open("transition/pitch") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Pitch meter") }
                OutlinedButton(onClick = { open("transition/voice") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Log practice") }
            }
        }
        voice.take(2).forEach { v ->
            Text(
                "${CareFormat.dayTime(v.at, zone)} · ${v.minutes} min" + (v.exercises.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString(", ")}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun HairCard(state: TransitionState, zone: ZoneId, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("Hair removal", trailing = { if (!readOnly) TextButton(onClick = { open("transition/hair") }) { Text("Add") } }) {
        val areas = state.overview?.hairByArea.orEmpty()
        if (areas.isEmpty()) Hint("Sessions by area, with pain, time and cost if you like.")
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { areas.forEach { Text("${it.name}: ${it.count}", style = MaterialTheme.typography.bodyMedium) } }
        state.hair.take(3).forEach { h ->
            Text(
                "${CareFormat.dayTime(h.at, zone)} · ${h.method.label}, ${h.area}" + (h.pain?.let { " · pain $it of 10" } ?: ""),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MeasurementsCard(state: TransitionState, units: Units, open: (String) -> Unit) {
    val sets = state.overview?.measurements.orEmpty()
    InfoCard("Body measurements", icon = Icons.Rounded.Straighten, trailing = { TextButton(onClick = { open("measurements") }) { Text("Open") } }) {
        if (sets.isEmpty()) {
            Hint("Waist, hips, chest and more, over time.")
            return@InfoCard
        }
        val latest = Metric.entries.mapNotNull { m -> sets.lastOrNull { m in it.values }?.let { m to it.values.getValue(m) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            latest.forEach { (m, cm) -> Text("${m.label} ${MeasurementFormat.length(cm, units)}", style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.width(1.dp))
    }
}
