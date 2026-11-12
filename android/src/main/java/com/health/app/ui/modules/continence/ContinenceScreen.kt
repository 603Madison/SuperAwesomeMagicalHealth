@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSummary
import com.health.api.LeakSize
import com.health.app.Container
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.IconTile
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightCard
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val TRIGGERS = listOf("cough", "sneeze", "laugh", "lifting", "exercise", "urge", "on the way", "asleep", "standing up", "cold")

val BRISTOL = listOf(
    "Separate hard lumps",
    "Lumpy and sausage-like",
    "A sausage with cracks",
    "Smooth and soft, like a sausage",
    "Soft blobs with clear edges",
    "Mushy, with ragged edges",
    "Watery, no solid pieces",
)

val URGENCY = listOf("None", "Mild", "Strong", "Couldn't wait")

data class ContinenceState(
    val today: LocalDate,
    val entries: List<ContinenceEntry> = emptyList(),
    val summary: ContinenceSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
) {
    fun todays(zone: ZoneId): List<ContinenceEntry> =
        entries.filter { (it.date ?: Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toString()) == today.toString() }.sortedByDescending { it.at }
}

class ContinenceViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(ContinenceState(LocalDate.now(c.zone())))
    val state: StateFlow<ContinenceState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null, today = LocalDate.now(c.zone())) }
        viewModelScope.launch {
            try {
                val entries = async { c.ui.continence(3) }
                val summary = async { c.ui.continenceSummary(14) }
                _state.update { it.copy(entries = entries.await(), summary = summary.await(), loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your diary") }
            }
        }
    }

    fun save(entry: ContinenceEntry) = viewModelScope.launch {
        runCatching { if (entry.id == null) c.ui.addContinence(entry) else c.ui.updateContinence(entry) }
            .onSuccess { c.tell("${it.kind.label} logged"); c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { c.ui.deleteContinence(id) }
            .onSuccess { c.dataChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't delete that") }
    }
}

class ContinenceActions(
    val save: (ContinenceEntry) -> Unit = {},
    val delete: (String) -> Unit = {},
    val share: () -> Unit = {},
    val pelvicFloor: () -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun ContinenceRoute(onPelvicFloor: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { ContinenceViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    ContinenceScreen(
        state, c.zone(),
        ContinenceActions(
            save = { vm.save(it) },
            delete = { vm.delete(it) },
            share = {
                val text = ContinenceDiary.export(state.entries, state.summary?.days.orEmpty(), c.zone(), state.today)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Bladder and bowel diary")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "Share your diary")) }
            },
            pelvicFloor = onPelvicFloor,
            retry = { vm.load() },
        ),
    )
}

fun kindIcon(kind: ContinenceKind): ImageVector = when (kind) {
    ContinenceKind.URINATION -> Icons.Rounded.Wc
    ContinenceKind.LEAK -> Icons.Rounded.WaterDrop
    ContinenceKind.BOWEL -> Icons.Rounded.Waves
    ContinenceKind.PAD_CHANGE -> Icons.Rounded.Layers
    ContinenceKind.PELVIC_FLOOR -> Icons.Rounded.SelfImprovement
    ContinenceKind.BOWEL_LEAK -> Icons.Rounded.Waves
    ContinenceKind.CATHETER -> Icons.Rounded.Wc
    ContinenceKind.BEDWETTING -> Icons.Rounded.WaterDrop
}

/** The four quick logs, in order. */
val QUICK_KINDS = listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL, ContinenceKind.PAD_CHANGE)

@Composable
fun ContinenceScreen(state: ContinenceState, zone: ZoneId, actions: ContinenceActions) {
    var editing by remember { mutableStateOf<ContinenceEntry?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Bladder & bowel", "A diary for you, and for your doctor or physio") {
            TextButton(onClick = actions.share) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("3-day diary")
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!com.health.app.ui.care.LocalReadOnly.current) AppCard(padding = 14.dp) {
                Text("Log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                QuickButtons { kind -> actions.save(ContinenceEntry(System.currentTimeMillis(), kind)) }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("One tap logs it now. Tap an entry below to add details.", Modifier.weight(1f))
                    TextButton(onClick = { editing = ContinenceEntry(System.currentTimeMillis(), ContinenceKind.URINATION) }) { Text("With details") }
                }
            }
            when {
                state.loading && state.summary == null -> Loading()
                state.error != null && state.summary == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                else -> {
                    TodayTimeline(state.todays(zone), zone) { editing = it }
                    state.summary?.let { SummaryCard(it) }
                    state.summary?.patterns?.forEach { InsightCard(it) }
                    InfoCard("Pelvic floor exercises", icon = Icons.Rounded.SelfImprovement) {
                        Hint("A few minutes a day strengthens the muscles that hold urine in. A guide squeezes and relaxes with you.")
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = actions.pelvicFloor) { Text("Start a set") }
                    }
                }
            }
            Hint(
                "Leaks are common and treatable. A doctor, continence nurse or pelvic health physio can help, and this diary is what they'll often ask for.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    editing?.let { entry ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            EntryForm(
                entry,
                onSave = { actions.save(it); editing = null },
                onDelete = entry.id?.let { id -> { actions.delete(id); editing = null } },
            )
        }
    }
}

@Composable
fun QuickButtons(onLog: (ContinenceKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QUICK_KINDS.forEach { kind ->
            FilledTonalButton(onClick = { onLog(kind) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(kindIcon(kind), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(kind.label)
            }
        }
    }
}

/** "Urgency strong · 250 ml", "Small · cough, sneeze", "Type 4". */
fun entryDetail(e: ContinenceEntry): String = listOfNotNull(
    e.volumeMl?.let { "$it ml" },
    e.urgency?.takeIf { it > 0 }?.let { "urgency ${URGENCY[it.coerceIn(0, 3)].lowercase()}" },
    e.leak?.label,
    e.triggers.takeIf { it.isNotEmpty() }?.joinToString(", "),
    e.bristol?.let { "type $it" },
    e.reps?.let { "$it reps" + (e.holdSeconds?.let { s -> " of ${s}s" } ?: "") },
    "at night".takeIf { e.night },
    "pain".takeIf { e.pain },
    "blood".takeIf { e.blood },
).joinToString(" · ")

@Composable
private fun TodayTimeline(entries: List<ContinenceEntry>, zone: ZoneId, onOpen: (ContinenceEntry) -> Unit) {
    InfoCard("Today") {
        if (entries.isEmpty()) {
            Hint("Nothing logged today yet.")
            return@InfoCard
        }
        Row(Modifier.fillMaxWidth()) {
            val trips = entries.count { it.kind == ContinenceKind.URINATION }
            val leaks = entries.count { it.kind == ContinenceKind.LEAK }
            val bowel = entries.count { it.kind == ContinenceKind.BOWEL }
            Stat("$trips", if (trips == 1) "toilet trip" else "toilet trips", Modifier.weight(1f))
            Stat("$leaks", if (leaks == 1) "leak" else "leaks", Modifier.weight(1f))
            Stat("$bowel", if (bowel == 1) "bowel movement" else "bowel movements", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        entries.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(e) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(kindIcon(e.kind), size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.kind.label, style = MaterialTheme.typography.bodyLarge)
                    val detail = entryDetail(e)
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Format.timeOfDay(e.at, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: ContinenceSummary) {
    InfoCard("Last two weeks", icon = Icons.Rounded.Insights) {
        Row(Modifier.fillMaxWidth()) {
            Stat(summary.averageVoids?.let { Format.compact(it) } ?: "—", "trips a day", Modifier.weight(1f))
            Stat(summary.averageNightVoids?.let { Format.compact(it) } ?: "—", "at night", Modifier.weight(1f))
            Stat(summary.averageLeaks?.let { Format.compact(it) } ?: "—", "leaks a day", Modifier.weight(1f))
        }
        summary.averageInterval?.let {
            Spacer(Modifier.height(6.dp))
            Hint("About ${Format.minutes(it)} between trips while you're up.")
        }
        val days = summary.days
        if (days.any { it.leaks > 0 || it.voids > 0 }) {
            Spacer(Modifier.height(12.dp))
            Text("Leaks each day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            BarChart(days.map { it.leaks.toDouble() }, color = AppTheme.data.water, height = 72.dp)
            AxisLabels(
                days.firstOrNull()?.let { Format.shortDay(LocalDate.parse(it.date)) } ?: "",
                null,
                days.lastOrNull()?.let { Format.shortDay(LocalDate.parse(it.date)) } ?: "",
            )
        }
        if (summary.triggers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Leaks came with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                summary.triggers.take(6).forEach { Pill("${it.name} · ${it.count}", MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/** An entry's details: what it was, when, and what's useful for that kind. */
@Composable
fun EntryForm(initial: ContinenceEntry, onSave: (ContinenceEntry) -> Unit, onDelete: (() -> Unit)?) {
    var e by remember { mutableStateOf(initial) }
    var volume by remember { mutableStateOf(initial.volumeMl?.toString().orEmpty()) }
    var minutesAgo by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (initial.id == null) "Add to your diary" else e.kind.label, style = MaterialTheme.typography.titleLarge)
        if (initial.id == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_KINDS.forEach { kind -> FilterChip(selected = e.kind == kind, onClick = { e = e.copy(kind = kind) }, label = { Text(kind.label) }) }
            }
            Text("When", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Now", 15 to "15 min ago", 30 to "30 min ago", 60 to "1 hour ago", 120 to "2 hours ago").forEach { (m, label) ->
                    FilterChip(selected = minutesAgo == m, onClick = { minutesAgo = m }, label = { Text(label) })
                }
            }
        }
        when (e.kind) {
            ContinenceKind.URINATION, ContinenceKind.CATHETER -> {
                StepScale("Urgency", e.urgency, "None", "Couldn't wait", { e = e.copy(urgency = it) }, range = 0..3)
                NumberField(volume, { volume = it }, "Volume, if you measured", suffix = "ml", decimals = false)
            }
            ContinenceKind.LEAK, ContinenceKind.PAD_CHANGE, ContinenceKind.BOWEL_LEAK, ContinenceKind.BEDWETTING -> {
                Text(if (e.kind == ContinenceKind.LEAK) "How much" else "How wet", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LeakSize.entries.forEach { size -> FilterChip(selected = e.leak == size, onClick = { e = e.copy(leak = if (e.leak == size) null else size) }, label = { Text(size.label) }) }
                }
                if (e.kind == ContinenceKind.LEAK) {
                    Text("What was happening?", style = MaterialTheme.typography.titleSmall)
                    ChipPicker(TRIGGERS, e.triggers.toSet(), { e = e.copy(triggers = it.toList()) }, addLabel = "Something else")
                    StepScale("Urgency", e.urgency, "None", "Couldn't wait", { e = e.copy(urgency = it) }, range = 0..3)
                }
            }
            ContinenceKind.BOWEL -> {
                Text("Stool type (Bristol scale)", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BRISTOL.forEachIndexed { i, text ->
                        FilterChip(selected = e.bristol == i + 1, onClick = { e = e.copy(bristol = if (e.bristol == i + 1) null else i + 1) }, label = { Text("${i + 1} · $text") })
                    }
                }
                StepScale("Urgency", e.urgency, "None", "Couldn't wait", { e = e.copy(urgency = it) }, range = 0..3)
            }
            ContinenceKind.PELVIC_FLOOR -> {
                Text(entryDetail(e).ifBlank { "A set of exercises" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (e.kind == ContinenceKind.URINATION || e.kind == ContinenceKind.BOWEL) {
            LabeledSwitch("It hurt", e.pain, { e = e.copy(pain = it) })
            LabeledSwitch("There was blood", e.blood, { e = e.copy(blood = it) }, subtitle = if (e.blood) "Worth mentioning to your doctor soon." else null)
        }
        OutlinedTextField(e.note.orEmpty(), { e = e.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        Button(
            onClick = {
                val at = if (initial.id == null) System.currentTimeMillis() - minutesAgo * 60_000L else e.at
                onSave(e.copy(at = at, volumeMl = Format.parse(volume)?.toInt()?.takeIf { it in 1..2000 }, note = e.note?.trim()?.ifBlank { null }))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
    }
}

