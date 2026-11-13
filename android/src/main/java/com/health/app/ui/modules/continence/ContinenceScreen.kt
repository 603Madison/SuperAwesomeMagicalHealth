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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.ContinenceSummary
import com.health.api.Fields
import com.health.api.SupplyCategory
import com.health.api.SupplyStatus
import com.health.api.Verdict
import com.health.app.Container
import com.health.app.net.continenceProgress
import com.health.app.net.continenceSettings
import com.health.app.net.supplyStatus
import com.health.app.reminders.BladderTrainingPlan
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.Stat
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.modules.IconTile
import com.health.app.ui.modules.NavRow
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
    val settings: ContinenceSettings = ContinenceSettings(),
    /** Supplies with how long they'll last (bladder and bowel, and periods: the screen picks). */
    val supplies: List<SupplyStatus> = emptyList(),
    val progress: ContinenceProgress? = null,
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
                // The detail: each can be missing without spoiling the diary.
                val settings = async { runCatching { c.ui.continenceSettings() }.getOrNull() }
                val supplies = async { runCatching { c.ui.supplyStatus() }.getOrNull() }
                val progress = async { runCatching { c.ui.continenceProgress(12) }.getOrNull() }
                _state.update {
                    it.copy(
                        entries = entries.await(), summary = summary.await(), loading = false,
                        settings = settings.await() ?: it.settings, supplies = supplies.await() ?: it.supplies, progress = progress.await() ?: it.progress,
                    )
                }
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
    /** Opens one of the diary's pages: "supplies/continence", "continence/progress", "continence/settings", "continence/check-in". */
    val open: (String) -> Unit = {},
)

@Composable
fun ContinenceRoute(onPelvicFloor: () -> Unit, onOpen: (String) -> Unit = {}) {
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
            open = onOpen,
        ),
        fields = shownFields(),
    )
}

fun kindIcon(kind: ContinenceKind): ImageVector = when (kind) {
    ContinenceKind.URINATION -> Icons.Rounded.Wc
    ContinenceKind.LEAK -> Icons.Rounded.WaterDrop
    ContinenceKind.BOWEL -> Icons.Rounded.Waves
    ContinenceKind.PAD_CHANGE -> Icons.Rounded.Layers
    ContinenceKind.PELVIC_FLOOR -> Icons.Rounded.SelfImprovement
    ContinenceKind.BOWEL_LEAK -> Icons.Rounded.Opacity
    ContinenceKind.CATHETER -> Icons.Rounded.MedicalServices
    ContinenceKind.BEDWETTING -> Icons.Rounded.Bed
}

/** The quick logs to start with, in order (the person can choose their own in the diary's settings). */
val QUICK_KINDS = listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL, ContinenceKind.PAD_CHANGE)

/** A quick log: now, of [kind], with the usual product for a change. */
fun quickEntry(kind: ContinenceKind, settings: ContinenceSettings, supplies: List<SupplyStatus>, now: Long = System.currentTimeMillis()): ContinenceEntry {
    val usual = settings.defaultSupplyId?.takeIf { id -> ContinenceText.suppliesFor(kind, supplies.map { it.supply }).any { it.id == id } }
    return ContinenceEntry(now, kind, supplyId = usual.takeIf { kind == ContinenceKind.PAD_CHANGE })
}

@Composable
fun ContinenceScreen(state: ContinenceState, zone: ZoneId, actions: ContinenceActions, fields: Set<String> = Fields.DEFAULTS) {
    var editing by remember { mutableStateOf<ContinenceEntry?>(null) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    val supplies = state.supplies.map { it.supply }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Bladder & bowel", "A diary for you, and for your doctor or physio") {
            TextButton(onClick = actions.share) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("3-day diary")
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!readOnly) AppCard(padding = 14.dp) {
                Text("Log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                QuickButtons({ kind -> actions.save(quickEntry(kind, state.settings, state.supplies)) }, quickButtonsFor(state.settings, fields))
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
                    // Signs of an infection come first, and stay in plain sight.
                    val warnings = state.summary?.patterns.orEmpty().filter { it.tone == "warn" }
                    warnings.forEach { InsightCard(it) }
                    if (warnings.isEmpty() && ContinenceText.infectionSigns(state.entries)) InfectionNudge()
                    TodayTimeline(state.todays(zone), zone, supplies) { if (!readOnly) editing = it }
                    if (Fields.CONTINENCE_SUPPLIES in fields) {
                        val low = ContinenceText.lowFor(state.supplies, setOf(SupplyCategory.CONTINENCE, SupplyCategory.OTHER))
                        if (low.isNotEmpty()) LowSuppliesCard(low) { actions.open("supplies/continence") }
                    }
                    state.summary?.let { SummaryCard(it) }
                    state.progress?.let { ProgressTeaser(it) { actions.open("continence/progress") } }
                    state.summary?.patterns.orEmpty().filter { it.tone != "warn" }.forEach { InsightCard(it) }
                    if (Fields.CONTINENCE_BLADDER_TRAINING in fields) {
                        BladderTrainingCard(state.settings, state.today, readOnly) { actions.open("continence/settings") }
                    }
                    if (Fields.CONTINENCE_PELVIC_FLOOR in fields && !readOnly) {
                        InfoCard("Pelvic floor exercises", icon = Icons.Rounded.SelfImprovement) {
                            Hint("A few minutes a day strengthens the muscles that hold urine in. A guide squeezes and relaxes with you.")
                            Spacer(Modifier.height(10.dp))
                            FilledTonalButton(onClick = actions.pelvicFloor) { Text("Start a set") }
                        }
                    }
                    InfoCard(null) {
                        if (state.progress == null) NavRow(Icons.AutoMirrored.Rounded.TrendingUp, "How it's going", "Getting better or worse, week by week", onClick = { actions.open("continence/progress") })
                        if (Fields.CONTINENCE_SUPPLIES in fields) {
                            NavRow(Icons.Rounded.Inventory2, "Supplies", "Pads, briefs and the rest: what's on hand", onClick = { actions.open("supplies/continence") })
                        }
                        if (Fields.CONTINENCE_BOTHER in fields) {
                            NavRow(Icons.AutoMirrored.Rounded.EventNote, "Weekly check-in", "How much it's bothered you", onClick = { actions.open("continence/check-in") })
                        }
                        if (!readOnly) NavRow(Icons.Rounded.Settings, "Diary settings", "Quick buttons, your own words and fields, night hours", onClick = { actions.open("continence/settings") })
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
                entry, FormContext(fields, state.settings, supplies),
                onSave = { actions.save(it); editing = null },
                onDelete = entry.id?.let { id -> { actions.delete(id); editing = null } },
            )
        }
    }
}

@Composable
fun QuickButtons(onLog: (ContinenceKind) -> Unit, kinds: List<ContinenceKind> = QUICK_KINDS) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { kind ->
            FilledTonalButton(onClick = { onLog(kind) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(kindIcon(kind), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(kind.label)
            }
        }
    }
}

/** "Urgency strong · 250 ml", "Small · cough, sneeze", "Type 4". */
fun entryDetail(e: ContinenceEntry): String = ContinenceText.detail(e)

@Composable
private fun TodayTimeline(entries: List<ContinenceEntry>, zone: ZoneId, supplies: List<com.health.api.Supply>, onOpen: (ContinenceEntry) -> Unit) {
    InfoCard("Today") {
        if (entries.isEmpty()) {
            Hint("Nothing logged today yet.")
            return@InfoCard
        }
        Row(Modifier.fillMaxWidth()) {
            val trips = entries.count { it.kind == ContinenceKind.URINATION }
            val leaks = entries.count { it.kind == ContinenceKind.LEAK || it.kind == ContinenceKind.BEDWETTING }
            val bowel = entries.count { it.kind == ContinenceKind.BOWEL }
            Stat("$trips", if (trips == 1) "toilet trip" else "toilet trips", Modifier.weight(1f))
            Stat("$leaks", if (leaks == 1) "leak" else "leaks", Modifier.weight(1f))
            Stat("$bowel", if (bowel == 1) "bowel movement" else "bowel movements", Modifier.weight(1f))
        }
        val bowelLeaks = entries.count { it.kind == ContinenceKind.BOWEL_LEAK }
        val products = entries.sumOf { if (it.kind == ContinenceKind.PAD_CHANGE) it.count ?: 1 else 0 }
        val catheters = entries.count { it.kind == ContinenceKind.CATHETER }
        val more = listOfNotNull(
            bowelLeaks.takeIf { it > 0 }?.let { "$it ${if (it == 1) "bowel accident" else "bowel accidents"}" },
            products.takeIf { it > 0 }?.let { "$it ${if (it == 1) "product" else "products"} changed" },
            catheters.takeIf { it > 0 }?.let { "$it ${if (it == 1) "catheter" else "catheters"}" },
        )
        if (more.isNotEmpty()) Hint(more.joinToString(" · "), Modifier.padding(top = 4.dp))
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
                    val detail = ContinenceText.detail(e.copy(fullness = null, leakedThrough = e.leakedThrough && e.fullness != com.health.api.Fullness.LEAKED), supplies)
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.fullness?.let { FullnessMeter(it, Modifier.padding(top = 4.dp)) }
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

@Composable
fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.IMPROVING -> AppTheme.status.good
    Verdict.WORSENING -> AppTheme.status.warn
    Verdict.STEADY -> MaterialTheme.colorScheme.primary
    Verdict.NOT_ENOUGH -> AppTheme.status.idle
}

/** The verdict and its headline, opening the week-by-week page. */
@Composable
private fun ProgressTeaser(p: ContinenceProgress, onOpen: () -> Unit) {
    InfoCard(
        "How it's going", icon = Icons.AutoMirrored.Rounded.TrendingUp, onClick = onOpen,
        trailing = { Pill(p.verdict.label, verdictColor(p.verdict)) },
    ) {
        Text(if (ProgressText.enough(p)) p.headline else ProgressText.notEnough(p), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) { Text("Week by week") }
    }
}

@Composable
private fun BladderTrainingCard(settings: ContinenceSettings, today: LocalDate, readOnly: Boolean, onOpen: () -> Unit) {
    val t = settings.bladderTraining
    if (!t.enabled && readOnly) return
    InfoCard("Bladder training", icon = Icons.Rounded.Alarm) {
        if (t.enabled) {
            Text(BladderTrainingPlan.today(t, today), style = MaterialTheme.typography.bodyLarge)
            val interval = BladderTrainingPlan.interval(t, today)
            Hint(
                if (interval >= t.goalMinutes) "You've reached your goal of every ${BladderTrainingPlan.say(t.goalMinutes)}. Well done."
                else "Going up by ${t.stepMinutes} minutes every ${t.stepEveryDays} ${if (t.stepEveryDays == 1) "day" else "days"}, to every ${BladderTrainingPlan.say(t.goalMinutes)}.",
            )
        } else {
            Hint("Stretch the time between trips a little at a time, with gentle reminders. It often helps with sudden urges.")
        }
        if (!readOnly) TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) { Text(if (t.enabled) "Change" else "Set it up") }
    }
}
