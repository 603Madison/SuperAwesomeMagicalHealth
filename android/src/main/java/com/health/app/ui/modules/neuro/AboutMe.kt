@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.neuro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.health.api.Fields
import com.health.api.NeuroProfile
import com.health.api.Sense
import com.health.api.SensePreference
import com.health.api.Wording
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.modules.ChipPicker

/** "About me": who the person is, their senses, how they communicate and what helps, in their words. */
@Composable
fun AboutMeEditor(initial: NeuroProfile, fields: Set<String>, onBack: () -> Unit, onSave: (NeuroProfile) -> Unit) {
    var p by remember(initial) { mutableStateOf(initial) }
    var openSense by remember { mutableStateOf<Sense?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "About me") { TextButton(onClick = { onSave(p) }) { Text("Save") } }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Everything here is in your words, and you choose who sees it. Leave out anything you like.", Modifier.padding(horizontal = 4.dp))
            InfoCard("How you describe yourself") {
                ChipPicker(NeuroSuggestions.identities, p.identities.toSet(), { p = p.copy(identities = it.toList()) }, addLabel = "In your own words")
                Spacer(Modifier.height(8.dp))
                Text("How you'd like it said", style = MaterialTheme.typography.bodyLarge)
                SegmentedChoice(Wording.entries, p.wording, { if (it == Wording.IDENTITY_FIRST) "Identity first" else "Person first" }, { p = p.copy(wording = it) })
                Hint(p.wording.label)
            }
            InfoCard("Your senses") {
                Hint("For each: do you tend to avoid it or seek it out? Tap one to add notes and what helps.")
                Sense.entries.forEach { sense ->
                    val pref = p.senses.firstOrNull { it.sense == sense } ?: SensePreference(sense)
                    SenseRow(
                        pref, expanded = openSense == sense, onExpand = { openSense = if (openSense == sense) null else sense },
                        onChange = { changed -> p = p.copy(senses = p.senses.filterNot { it.sense == sense } + changed) },
                    )
                }
            }
            ListCard("How you like to communicate", NeuroSuggestions.communication, p.communication) { p = p.copy(communication = it) }
            ListCard("What helps", NeuroSuggestions.whatHelped, p.whatHelps) { p = p.copy(whatHelps = it) }
            ListCard("Accommodations that help", NeuroSuggestions.accommodations, p.accommodations, "At work, school, appointments.") { p = p.copy(accommodations = it) }
            ListCard("Early signs of overwhelm", NeuroSuggestions.warningSigns, p.warningSigns, "So the people around you can notice too.") { p = p.copy(warningSigns = it) }
            if (Fields.NEURO_INTERESTS in fields) ListCard("Special interests", emptyList(), p.interests, "The things you love to dive into.") { p = p.copy(interests = it) }
            if (Fields.NEURO_STIMS in fields) ListCard("Stims that help", listOf("rocking", "fidget toy", "humming", "hand flapping", "music on repeat", "pacing"), p.stims) { p = p.copy(stims = it) }
            InfoCard("Anything else") {
                OutlinedTextField(
                    p.aboutMe.orEmpty(), { p = p.copy(aboutMe = it.take(4000)) },
                    label = { Text("\"If I'm overwhelmed, please…\"") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ListCard(title: String, suggestions: List<String>, items: List<String>, hint: String? = null, onChange: (List<String>) -> Unit) {
    InfoCard(title) {
        hint?.let { Hint(it) }
        ChipPicker(suggestions, items.toSet(), { onChange(it.toList()) })
    }
}

/** A sense: avoid −2 … seek +2 as five round steps, and (opened) notes and what helps. */
@Composable
private fun SenseRow(pref: SensePreference, expanded: Boolean, onExpand: () -> Unit, onChange: (SensePreference) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pref.sense.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(NeuroWords.response(pref.response), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Avoid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (-2..2).forEach { v ->
                val selected = pref.response == v
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable { onChange(pref.copy(response = v)) }
                        .semantics { contentDescription = "${pref.sense.label}: ${NeuroWords.response(v)}"; this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(if (v == 0) 30.dp else 36.dp + (kotlin.math.abs(v) * 2).dp),
                    ) { Box(contentAlignment = Alignment.Center) { Text(if (v > 0) "+$v" else "$v", style = MaterialTheme.typography.labelMedium) } }
                }
            }
            Text("Seek", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            OutlinedTextField(pref.notes.orEmpty(), { onChange(pref.copy(notes = it.take(500))) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            ChipPicker(emptyList(), pref.helps.toSet(), { onChange(pref.copy(helps = it.toList())) }, addLabel = "What helps")
        }
    }
}

/** "About me" to show someone: clean, large and calm, with nothing to tap by accident. */
@Composable
fun AboutMeView(p: NeuroProfile, fields: Set<String>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, null)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(padding = 22.dp) {
                Text("About me", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                NeuroWords.describe(p)?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                p.aboutMe?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp)) }
            }
            Section("How I like to communicate", p.communication)
            Section("What helps me", p.whatHelps)
            Section("Things that help at work, school and appointments", p.accommodations)
            Section("Signs I'm getting overwhelmed", p.warningSigns)
            val senses = p.senses.filter { it.response != 0 || !it.notes.isNullOrBlank() || it.helps.isNotEmpty() }
            if (senses.isNotEmpty()) {
                AppCard(padding = 22.dp) {
                    Heading("My senses")
                    senses.sortedBy { it.sense.ordinal }.forEach { s ->
                        Text(s.sense.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            listOfNotNull(
                                when {
                                    s.response <= -2 -> "I avoid it as much as I can."
                                    s.response == -1 -> "I tend to avoid it."
                                    s.response == 1 -> "I tend to seek it out."
                                    s.response >= 2 -> "I seek it out a lot."
                                    else -> null
                                },
                                s.notes?.takeIf { it.isNotBlank() },
                                s.helps.takeIf { it.isNotEmpty() }?.let { "What helps: ${it.joinToString(", ")}." },
                            ).joinToString(" "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            if (Fields.NEURO_INTERESTS in fields) Section("Things I love", p.interests)
            if (Fields.NEURO_STIMS in fields) Section("Stims that help me", p.stims)
            if (p == NeuroProfile()) {
                Text("Nothing here yet.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

@Composable
private fun Section(title: String, items: List<String>) {
    if (items.isEmpty()) return
    AppCard(padding = 22.dp) {
        Heading(title)
        items.forEach { Text("•  ${it.replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp)) }
    }
}
