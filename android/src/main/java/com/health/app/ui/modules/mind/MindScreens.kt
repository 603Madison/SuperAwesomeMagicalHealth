@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.mind

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FrontHand
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Support
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.CrisisResource
import com.health.api.JournalEntry
import com.health.api.JournalKind
import com.health.api.MoodEntry
import com.health.api.Screener
import com.health.api.ScreenerDefinition
import com.health.api.ScreenerResult
import com.health.api.ThoughtRecord
import com.health.app.attempt
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.showsField
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.TitleAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.OpenChevron
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.Sections
import com.health.api.Fields
import com.health.api.Module
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.FaceScale
import com.health.app.ui.modules.Moods
import com.health.app.ui.modules.NavRow
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

val FEELINGS = listOf(
    "calm", "happy", "grateful", "hopeful", "proud", "loved", "content", "excited",
    "tired", "bored", "anxious", "stressed", "overwhelmed", "sad", "lonely", "irritable", "angry", "numb",
)
val CONTEXTS = listOf("work", "family", "friends", "partner", "sleep", "health", "body", "money", "exercise", "weather", "news", "alone time")
val JOURNAL_PROMPTS = listOf(
    "What's taking up space in your head?",
    "What went well today?",
    "What would you tell a friend feeling this way?",
    "What do you need right now?",
    "What are you looking forward to?",
)

/** What Mind can do, as callbacks (so it can be drawn with sample data). */
class MindActions(
    val logMood: (MoodEntry) -> Unit = {},
    val openJournal: () -> Unit = {},
    val newJournal: (JournalKind) -> Unit = {},
    val openCheck: (Screener) -> Unit = {},
    val openTool: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

/** The calming tools, by the kind they're logged as. */
data class CalmTool(val kind: String, val title: String, val subtitle: String)

val CALM_TOOLS = listOf(
    CalmTool("box-breathing", "Box breathing", "In 4, hold 4, out 4, hold 4. Steadying."),
    CalmTool("breathing-478", "4-7-8 breathing", "In 4, hold 7, out 8. Winding down."),
    CalmTool("coherent-breathing", "Coherent breathing", "Slow and even, about 5½ breaths a minute."),
    CalmTool("grounding", "5-4-3-2-1 grounding", "Coming back to the room when everything's too much."),
    CalmTool("meditation", "Meditation timer", "A few quiet minutes, with a gentle end."),
)

@Composable
fun MindRoute(onJournal: () -> Unit, onNewJournal: (JournalKind) -> Unit, onCheck: (Screener) -> Unit, onTool: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { MindViewModel(c) }
    val state by vm.state.collectAsState()
    MindHome(
        state, c.zone(), MindActions(vm::logMood, onJournal, onNewJournal, onCheck, onTool) { vm.load() },
        energy = showsField(Fields.MIND_ENERGY_BUDGET),
        neuro = Module.NEURO in com.health.app.ui.care.shownModules(),
    )
}

/**
 * Mind's sections in the default order (the sections of [FeatureScreens.MIND]): the energy budget
 * when its field is on, the way into Autism & ADHD when that's on, and no mood faces while viewing a share.
 */
fun mindSections(readOnly: Boolean = false, energy: Boolean = false, neuro: Boolean = false): List<ArrangeItem> = buildList {
    if (!readOnly) add(ArrangeItem("feeling", "How are you feeling?"))
    add(ArrangeItem("mood", "Mood"))
    add(ArrangeItem("insights", "What we've noticed"))
    add(ArrangeItem("tools", "Calm tools"))
    if (energy) add(ArrangeItem("energy", "Energy today"))
    if (neuro) add(ArrangeItem("neuro", "Autism & ADHD"))
    add(ArrangeItem("journal", "Journal"))
    add(ArrangeItem("checkins", "Check-ins"))
}

@Composable
fun MindHome(state: MindState, zone: ZoneId, actions: MindActions, initialMood: Int? = null, energy: Boolean = false, neuro: Boolean = false) {
    var moodSheet by remember { mutableStateOf(initialMood) }
    var support by remember { mutableStateOf(false) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    val sections = mindSections(readOnly, energy, neuro)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Mind", "How you feel, and what helps") {
            TextButton(onClick = { support = true }) {
                Icon(Icons.Rounded.Support, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Help now")
            }
            ArrangeAction(FeatureScreens.MIND, "Mind", sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = arrangedIn(FeatureScreens.MIND, sections)
            val feeling: @Composable () -> Unit = {
                AppCard(padding = 18.dp) {
                    Text("How are you feeling?", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    FaceScale(null, { it?.let { m -> moodSheet = m } })
                }
            }
            when {
                // Logging a mood never waits for the rest.
                state.loading && state.summary == null -> { if ("feeling" in shown) feeling(); Loading() }
                state.error != null && state.summary == null -> {
                    if ("feeling" in shown) feeling()
                    ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                }
                else -> Sections(shown) { id ->
                    when (id) {
                        "feeling" -> feeling()
                        "mood" -> MoodCard(state, zone)
                        "insights" -> state.summary?.patterns?.forEach { InsightCard(it) }
                        "tools" -> ToolsCard(actions.openTool)
                        // Autism & ADHD and the energy budget (ui/modules/neuro).
                        "energy", "neuro" -> com.health.app.ui.modules.neuro.MindNeuroSection(id)
                        "journal" -> JournalCard(state.journal, zone, readOnly, actions)
                        "checkins" -> CheckInsCard(state, actions.openCheck)
                    }
                }
            }
            Hint(
                "A place to notice how you're doing, not a diagnosis. If things feel heavy, a doctor, therapist or someone you trust can help.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    moodSheet?.let { first ->
        ModalBottomSheet(onDismissRequest = { moodSheet = null }) {
            MoodForm(first, onSave = { actions.logMood(it); moodSheet = null })
        }
    }
    if (support) SupportSheet(state.resources) { support = false }
}

@Composable
private fun MoodCard(state: MindState, zone: ZoneId) {
    val days = state.summary?.days.orEmpty()
    InfoCard("Mood", icon = Icons.Rounded.Favorite, iconTint = AppTheme.data.protein) {
        val average = state.summary?.averageMood
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (average != null) {
                Icon(Moods.icon(Moods.nearest(average)), null, tint = Moods.color(Moods.nearest(average)), modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(average?.let { "${Format.compact(it)} of 5" } ?: "Nothing logged yet", style = MaterialTheme.typography.titleLarge)
                Hint(if (average != null) "your average over 30 days" else "Tap a face above whenever you like. Patterns show up after a week or two.")
            }
        }
        if (days.count { it.mood != null } >= 2) {
            Spacer(Modifier.height(12.dp))
            LineChart(days.map { it.mood }, color = AppTheme.data.protein, min = 1.0, max = 5.0, height = 88.dp)
            AxisLabels(
                days.firstOrNull()?.let { Format.shortDay(LocalDate.parse(it.date)) } ?: "",
                null,
                days.lastOrNull()?.let { Format.shortDay(LocalDate.parse(it.date)) } ?: "",
            )
        }
        val feelings = state.summary?.feelings.orEmpty().take(6)
        if (feelings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Felt most", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                feelings.forEach { Pill("${it.name} · ${it.count}", MaterialTheme.colorScheme.primary) }
            }
        }
        val recent = state.moods.take(3)
        if (recent.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            recent.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Moods.icon(m.mood), null, tint = Moods.color(m.mood), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        listOf(Moods.label(m.mood), *m.feelings.take(3).toTypedArray()).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(whenText(m.at, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToolsCard(onTool: (String) -> Unit) {
    InfoCard("Calm tools", icon = Icons.Rounded.SelfImprovement) {
        CALM_TOOLS.forEach { tool ->
            NavRow(toolIcon(tool.kind), tool.title, tool.subtitle, onClick = { onTool(tool.kind) })
        }
    }
}

fun toolIcon(kind: String) = when (kind) {
    "grounding" -> Icons.Rounded.FrontHand
    "meditation" -> Icons.Rounded.Timer
    else -> Icons.Rounded.Air
}

@Composable
private fun JournalCard(entries: List<JournalEntry>, zone: ZoneId, readOnly: Boolean, actions: MindActions) {
    var choosing by remember { mutableStateOf(false) }
    InfoCard("Journal", icon = Icons.Rounded.EditNote, onClick = actions.openJournal.takeIf { entries.isNotEmpty() }, trailing = {
        if (!readOnly) {
            Box {
                TitleAction(Icons.Rounded.Add, "Write something") { choosing = true }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    JournalKind.entries.forEach { kind ->
                        DropdownMenuItem(text = { Text(kind.label) }, onClick = { choosing = false; actions.newJournal(kind) })
                    }
                }
            }
        }
        if (entries.isNotEmpty()) OpenChevron("All entries", actions.openJournal)
    }) {
        if (entries.isEmpty()) {
            Hint(if (readOnly) "Nothing written yet." else "Private to you. Tap + for a free entry, a gratitude list or a thought record.")
        }
        entries.take(3).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            JournalPreview(e, zone)
        }
    }
}

@Composable
fun JournalPreview(e: JournalEntry, zone: ZoneId, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.title?.takeIf { it.isNotBlank() } ?: e.kind.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText(e.at, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val preview = when (e.kind) {
            JournalKind.GRATITUDE -> e.gratitude.filter { it.isNotBlank() }.joinToString(" · ")
            JournalKind.THOUGHT_RECORD -> e.thought?.balancedThought?.takeIf { it.isNotBlank() } ?: e.thought?.thought.orEmpty()
            JournalKind.FREE -> e.text
        }.ifBlank { e.text }
        if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CheckInsCard(state: MindState, onCheck: (Screener) -> Unit) {
    InfoCard("Check-ins", icon = Icons.Rounded.Psychology) {
        Hint("Short questionnaires about the last two weeks. Every few weeks is plenty.")
        Spacer(Modifier.height(6.dp))
        com.health.app.ui.care.StageMath.screenersFor(state.stage).forEach { s ->
            val definition = state.screeners.firstOrNull { it.id == s }
            val last = state.latest(s)
            NavRow(
                Icons.Rounded.Psychology,
                definition?.title ?: screenerName(s),
                last?.let { "${it.score} · ${it.severity} · ${Format.shortDay(LocalDate.parse(it.date ?: LocalDate.now().toString()))}" } ?: "Not taken yet",
                onClick = { onCheck(s) },
                trailing = { TextButton(onClick = { onCheck(s) }) { Text(if (last == null) "Start" else "Again") } },
            )
        }
    }
}

fun screenerName(s: Screener) = when (s) {
    Screener.PHQ9 -> "Low mood (PHQ-9)"
    Screener.GAD7 -> "Anxiety (GAD-7)"
    Screener.EPDS -> "Edinburgh (EPDS)"
}

/** Logging a mood: the face, and if wanted energy, anxiety, feelings, what it's about and a note. */
@Composable
fun MoodForm(initial: Int, onSave: (MoodEntry) -> Unit) {
    var mood by remember { mutableStateOf<Int?>(initial) }
    var energy by remember { mutableStateOf<Int?>(null) }
    var anxiety by remember { mutableStateOf<Int?>(null) }
    var feelings by remember { mutableStateOf(setOf<String>()) }
    var context by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("How are you feeling?", style = MaterialTheme.typography.titleLarge)
        FaceScale(mood, { mood = it })
        StepScale("Energy", energy, "Drained", "Full", { energy = it })
        StepScale("Anxiety", anxiety, "Calm", "Very", { anxiety = it })
        Text("Feelings", style = MaterialTheme.typography.titleSmall)
        ChipPicker(FEELINGS, feelings, { feelings = it }, addLabel = "Another feeling")
        Text("What's it about?", style = MaterialTheme.typography.titleSmall)
        ChipPicker(CONTEXTS, context, { context = it }, addLabel = "Something else")
        OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
        Button(
            enabled = mood != null,
            onClick = {
                onSave(MoodEntry(System.currentTimeMillis(), mood!!, energy, anxiety, feelings.toList(), context.toList(), note.trim().ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

/** Somewhere to turn: the server's list of helplines, with tap-to-call, text or open. */
@Composable
fun SupportSheet(resources: List<CrisisResource>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { SupportContent(resources) }
}

@Composable
fun SupportContent(resources: List<CrisisResource>) {
    val context = LocalContext.current
    fun open(intent: Intent) = attempt { context.startActivity(intent) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolunteerActivism, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("You don't have to go through this alone", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Talking to someone can help, right now. These are free and confidential.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppCard(padding = 14.dp) {
            Text("If you're in immediate danger, call your local emergency number (911, 999, 112).", style = MaterialTheme.typography.bodyLarge)
        }
        if (resources.isEmpty()) Hint("Your server has no helplines listed. findahelpline.com lists them for every country.")
        resources.forEach { r ->
            AppCard(padding = 14.dp) {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(r.contact, r.region).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                r.note?.let { Hint(it) }
                val ways = ContactWays.of(r.contact)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ways.call) {
                        Button(onClick = { open(Intent(Intent.ACTION_DIAL, "tel:${ways.number}".toUri())) }) {
                            Icon(Icons.Rounded.Call, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call")
                        }
                    }
                    if (ways.text) {
                        val sms: () -> Unit = {
                            open(Intent(Intent.ACTION_SENDTO, "smsto:${ways.number}".toUri()).apply { ways.textBody?.let { putExtra("sms_body", it) } })
                        }
                        val label: @Composable () -> Unit = {
                            Icon(Icons.AutoMirrored.Rounded.Chat, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(ways.textBody?.let { "Text $it" } ?: "Text")
                        }
                        if (ways.call) OutlinedButton(onClick = sms) { label() } else Button(onClick = sms) { label() }
                    }
                    r.url?.let { url ->
                        OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, "https://findahelpline.com".toUri())) }, modifier = Modifier.fillMaxWidth()) {
            Text("Find a helpline in another country")
        }
    }
}

/** The phone number in "Call or text 988" or "Samaritans: 116 123", digits only. */
fun phoneNumberIn(contact: String): String? =
    Regex("""\+?\d[\d\s-]{1,16}\d|\b\d{3}\b""").find(contact)?.value?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }

/**
 * How a helpline can be reached from its contact line: "Call or text 988" both ways, "Text HOME to
 * 741741" by text only (with HOME ready to send), "116 123" by calling.
 */
data class ContactWays(val number: String?, val call: Boolean, val text: Boolean, val textBody: String?) {
    companion object {
        fun of(contact: String): ContactWays {
            val number = phoneNumberIn(contact) ?: return ContactWays(null, call = false, text = false, textBody = null)
            val mentionsText = Regex("""\btext\b""", RegexOption.IGNORE_CASE).containsMatchIn(contact)
            val mentionsCall = Regex("""\bcall\b""", RegexOption.IGNORE_CASE).containsMatchIn(contact)
            val body = Regex("""\btext\s+([A-Za-z]{2,})\s+to\b""", RegexOption.IGNORE_CASE).find(contact)?.groupValues?.get(1)
            return ContactWays(number, call = mentionsCall || !mentionsText, text = mentionsText, textBody = body)
        }
    }
}

// ---------------------------------------------------------------- Journal

@Composable
fun JournalRoute(onBack: () -> Unit, onOpen: (JournalEntry?, JournalKind) -> Unit) {
    val c = appContainer()
    val vm = viewModel { MindViewModel(c) }
    val state by vm.state.collectAsState()
    JournalList(state.journal, c.zone(), onBack, onOpen)
}

@Composable
fun JournalList(entries: List<JournalEntry>, zone: ZoneId, onBack: () -> Unit, onOpen: (JournalEntry?, JournalKind) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Journal")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JournalKind.entries.forEach { kind -> FilledTonalButton(onClick = { onOpen(null, kind) }) { Text(kind.label) } }
            }
            if (entries.isEmpty()) Hint("Nothing written yet. A few lines is plenty.")
            entries.forEach { e -> AppCard(onClick = { onOpen(e, e.kind) }) { JournalPreview(e, zone) } }
        }
    }
}

@Composable
fun JournalEditorRoute(id: String?, kind: JournalKind, onDone: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MindViewModel(c) }
    val state by vm.state.collectAsState()
    val existing = id?.let { i -> state.journal.firstOrNull { it.id == i } }
    if (id != null && existing == null && state.loading) {
        Loading()
        return
    }
    JournalEditor(existing, existing?.kind ?: kind, onBack = onDone, onSave = { vm.saveJournal(it); onDone() }, onDelete = existing?.id?.let { i -> { vm.deleteJournal(i); onDone() } })
}

@Composable
fun JournalEditor(existing: JournalEntry?, kind: JournalKind, onBack: () -> Unit, onSave: (JournalEntry) -> Unit, onDelete: (() -> Unit)?) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt) }
    val gratitude = remember { mutableStateListOf(*(existing?.gratitude.orEmpty() + List(3) { "" }).take(3).toTypedArray()) }
    var thought by remember { mutableStateOf(existing?.thought ?: ThoughtRecord()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, if (existing == null) "New ${kind.label.lowercase()}" else kind.label) {
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (kind) {
                JournalKind.FREE -> {
                    if (existing == null) {
                        Hint("Stuck? Try a prompt.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            JOURNAL_PROMPTS.forEach { p -> FilterChip(selected = prompt == p, onClick = { prompt = if (prompt == p) null else p }, label = { Text(p) }) }
                        }
                    }
                    prompt?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                    OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Write freely") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp))
                }
                JournalKind.GRATITUDE -> {
                    Text("Three things, big or small, you're glad about today.", style = MaterialTheme.typography.bodyLarge)
                    (0 until 3).forEach { i ->
                        OutlinedTextField(gratitude[i], { gratitude[i] = it }, label = { Text("${i + 1}.") }, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(text, { text = it }, label = { Text("Anything else (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                }
                JournalKind.THOUGHT_RECORD -> {
                    Hint("A way to slow down a thought that's hitting hard, and look at it from another side.")
                    OutlinedTextField(thought.situation, { thought = thought.copy(situation = it) }, label = { Text("What happened?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(thought.thought, { thought = thought.copy(thought = it) }, label = { Text("What went through your mind?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(thought.feelings.orEmpty(), { thought = thought.copy(feelings = it) }, label = { Text("How did it make you feel?") }, modifier = Modifier.fillMaxWidth())
                    BeliefSlider("How much did you believe it?", thought.beliefBefore) { thought = thought.copy(beliefBefore = it) }
                    OutlinedTextField(thought.evidenceFor.orEmpty(), { thought = thought.copy(evidenceFor = it) }, label = { Text("What supports the thought?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(thought.evidenceAgainst.orEmpty(), { thought = thought.copy(evidenceAgainst = it) }, label = { Text("What doesn't fit with it?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(thought.balancedThought.orEmpty(), { thought = thought.copy(balancedThought = it) }, label = { Text("A kinder, more balanced thought") }, modifier = Modifier.fillMaxWidth())
                    BeliefSlider("And now, how much do you believe the first thought?", thought.beliefAfter) { thought = thought.copy(beliefAfter = it) }
                }
            }
            val hasContent = when (kind) {
                JournalKind.FREE -> text.isNotBlank() || title.isNotBlank()
                JournalKind.GRATITUDE -> gratitude.any { it.isNotBlank() } || text.isNotBlank()
                JournalKind.THOUGHT_RECORD -> thought.situation.isNotBlank() || thought.thought.isNotBlank()
            }
            Button(
                enabled = hasContent,
                onClick = {
                    onSave(
                        (existing ?: JournalEntry(at = System.currentTimeMillis(), kind = kind)).copy(
                            title = title.trim().ifBlank { null },
                            text = text.trim(),
                            prompt = prompt,
                            gratitude = if (kind == JournalKind.GRATITUDE) gratitude.map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                            thought = if (kind == JournalKind.THOUGHT_RECORD) thought else null,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun BeliefSlider(label: String, value: Int?, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value?.let { "$it%" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = (value ?: 50).toFloat(), onValueChange = { onChange((it / 10).roundToInt() * 10) },
            valueRange = 0f..100f, steps = 9,
        )
    }
}

// ---------------------------------------------------------------- Check-ins

@Composable
fun ScreenerRoute(screener: Screener, onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { MindViewModel(c) }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.clearScored(); vm.loadResources() }
    val definition = state.screeners.firstOrNull { it.id == screener }
    when {
        definition == null && state.loading -> Loading()
        definition == null -> Column {
            BackBar(onBack, screenerName(screener))
            ErrorCard(state.error ?: "Couldn't load the questions", Modifier.padding(16.dp), "Try again") { vm.load() }
        }
        else -> ScreenerScreen(
            definition, state.results.filter { it.screener == screener }.sortedByDescending { it.at }, state.justScored, state.resources, c.zone(),
            onBack = onBack, onSubmit = vm::submitScreener, onAgain = vm::clearScored,
        )
    }
}

@Composable
fun ScreenerScreen(
    definition: ScreenerDefinition,
    history: List<ScreenerResult>,
    result: ScreenerResult?,
    resources: List<CrisisResource>,
    zone: ZoneId,
    onBack: () -> Unit,
    onSubmit: (ScreenerResult) -> Unit,
    onAgain: () -> Unit,
    initialAnswers: List<Int?>? = null,
) {
    val answers = remember(definition.id, result == null) { mutableStateListOf(*(initialAnswers ?: List(definition.items.size) { null }).toTypedArray()) }
    var support by remember(result) { mutableStateOf(result?.flagged == true) }
    val past = history.filter { it.screener == definition.id }.sortedByDescending { it.at }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, definition.title)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result != null) {
                ResultCard(definition, result, onSupport = { support = true })
                OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                Hint(definition.about)
                Text(definition.question, style = MaterialTheme.typography.titleMedium)
                definition.items.forEachIndexed { i, item ->
                    AppCard(padding = 14.dp) {
                        Text("${i + 1}. $item", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            com.health.app.ui.care.StageMath.optionsFor(definition, i).forEachIndexed { value, option ->
                                FilterChip(selected = answers[i] == value, onClick = { answers[i] = value }, label = { Text(option) })
                            }
                        }
                    }
                }
                Button(
                    enabled = com.health.app.ui.care.StageMath.complete(definition, answers),
                    onClick = { onSubmit(ScreenerResult(definition.id, System.currentTimeMillis(), answers.map { it!! })) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (answers.all { it != null }) "See my score" else "${answers.count { it == null }} to go") }
            }
            if (past.isNotEmpty()) {
                SectionTitle("Before", Modifier.padding(start = 0.dp))
                AppCard(padding = 14.dp) {
                    if (past.size >= 2) {
                        val max = com.health.app.ui.care.StageMath.maxScore(definition)
                        LineChart(past.reversed().map { it.score.toDouble() }, min = 0.0, max = max.toDouble(), height = 72.dp)
                        Spacer(Modifier.height(8.dp))
                    }
                    past.take(6).forEach { r ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(Format.shortDay(Instant.ofEpochMilli(r.at).atZone(zone).toLocalDate()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${r.score} · ${r.severity}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Hint(definition.source)
        }
    }
    if (support) SupportSheet(resources) { support = false }
}

@Composable
private fun ResultCard(definition: ScreenerDefinition, result: ScreenerResult, onSupport: () -> Unit) {
    val max = com.health.app.ui.care.StageMath.maxScore(definition)
    val high = result.score >= (definition.bands.getOrNull(2)?.from ?: 10)
    AppCard(padding = 18.dp) {
        Text("Your score", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${result.score}", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.width(8.dp))
            Text("of $max · ${result.severity}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (high) "That's a lot to be carrying. It could really help to talk it through with your doctor, a therapist or someone you trust."
            else "Thanks for checking in. Keep doing what helps, and come back in a couple of weeks to see how things move.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Hint("This is a snapshot of the last two weeks, not a diagnosis.")
        if (result.flagged) {
            Spacer(Modifier.height(12.dp))
            AppCard(padding = 14.dp) {
                Text("You mentioned thoughts of being better off dead or of hurting yourself.", style = MaterialTheme.typography.titleSmall)
                Text("You deserve support with that, today. Talking to someone can help.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSupport, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Rounded.Support, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("See who you can talk to")
                }
            }
        }
    }
}

/** "Today 9:41", "Tue 18:20", "3 Nov". */
fun whenText(at: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    val date = time.toLocalDate()
    val clock = Format.timeOfDay(at, zone)
    return when {
        date == today -> clock
        date == today.minusDays(1) -> "Yesterday $clock"
        date.isAfter(today.minusDays(7)) -> "${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase)} $clock"
        else -> Format.shortDay(date)
    }
}
