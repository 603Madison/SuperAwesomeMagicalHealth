@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Today
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.TitleAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.OpenChevron
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.ContinenceSummary
import com.health.api.ContinenceWearing
import com.health.api.ProductStats
import com.health.api.Fields
import com.health.api.SupplyCategory
import com.health.api.SupplyStatus
import com.health.api.Verdict
import com.health.app.Container
import com.health.app.net.continenceDiary
import com.health.app.net.continenceProducts
import com.health.app.net.continenceProgress
import com.health.app.net.continenceWearing
import com.health.app.net.continenceSettings
import com.health.app.net.supplyStatus
import com.health.app.reminders.BladderTrainingPlan
import com.health.app.ui.Format
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
    /** What's on now (all null when nothing is known). */
    val wearing: ContinenceWearing? = null,
    /** How each product goes, over 90 days. */
    val products: List<ProductStats> = emptyList(),
    /** Today, grouped: each product worn with its accidents (null from an older server: Today lists each entry on its own). */
    val diary: com.health.api.ContinenceDiary? = null,
) {
    /** The products put on lately, newest first: offered first in a change. */
    val recent: List<String> get() = WearText.recentPutOns(entries)

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
        val today = LocalDate.now(c.zone())
        _state.update { it.copy(loading = !quiet || it.summary == null, error = null, today = today) }
        viewModelScope.launch {
            try {
                val entries = async { c.ui.continence(3) }
                val summary = async { c.ui.continenceSummary(14) }
                // The detail: each can be missing without spoiling the diary.
                val settings = async { runCatching { c.ui.continenceSettings() }.getOrNull() }
                val supplies = async { runCatching { c.ui.supplyStatus() }.getOrNull() }
                val progress = async { runCatching { c.ui.continenceProgress(12) }.getOrNull() }
                val wearing = async { runCatching { c.ui.continenceWearing() }.getOrNull() }
                val products = async { runCatching { c.ui.continenceProducts(90) }.getOrNull() }
                // Today, grouped. Missing (an older server), Today lists each entry on its own, from the fresh entries.
                val diary = async { runCatching { c.ui.continenceDiary(today.toString()) }.getOrNull() }
                _state.update {
                    it.copy(
                        entries = entries.await(), summary = summary.await(), loading = false,
                        settings = settings.await() ?: it.settings, supplies = supplies.await() ?: it.supplies, progress = progress.await() ?: it.progress,
                        wearing = wearing.await() ?: it.wearing, products = products.await() ?: it.products, diary = diary.await(),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your diary") }
            }
        }
    }

    fun save(entry: ContinenceEntry) = viewModelScope.launch {
        runCatching { if (entry.id == null) c.ui.addContinence(entry) else c.ui.updateContinence(entry) }
            .onSuccess { c.tell("${it.kind.label} logged"); c.dataChanged(); if (it.kind == com.health.api.ContinenceKind.PAD_CHANGE) c.continenceChanged() }
            .onFailure { c.tell(it.message ?: "Couldn't save that") }
    }

    /** "Put on" alone, from "What are you wearing?". */
    fun putOn(supplyId: String) = viewModelScope.launch {
        runCatching { c.ui.addContinence(WearText.putOnEntry(supplyId, System.currentTimeMillis())) }
            .onSuccess { c.tell("${_state.value.supplies.firstOrNull { s -> s.supply.id == supplyId }?.supply?.name ?: "It"} on"); c.dataChanged(); c.continenceChanged() }
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
    /** Logs putting something on (from "What are you wearing?"). */
    val putOn: (String) -> Unit = {},
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
            putOn = { vm.putOn(it) },
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

/** A quick log: now, of [kind], with the usual product for a change, and an accident in what's on ([wearing]). */
fun quickEntry(kind: ContinenceKind, settings: ContinenceSettings, supplies: List<SupplyStatus>, now: Long = System.currentTimeMillis(), wearing: ContinenceWearing? = null): ContinenceEntry {
    val usual = settings.defaultSupplyId?.takeIf { id -> ContinenceText.suppliesFor(kind, supplies.map { it.supply }).any { it.id == id } }
    // A change puts the usual on (the one taken off was counted when it went on).
    return ContinenceEntry(now, kind, putOnSupplyId = usual.takeIf { kind == ContinenceKind.PAD_CHANGE }, supplyId = WearText.quickSupply(kind, wearing))
}

/**
 * The diary's sections in the default order (the sections of [FeatureScreens.CONTINENCE]), for the
 * fields that are on. Signs of an infection aren't among them: they always come first.
 */
fun continenceSections(fields: Set<String> = Fields.DEFAULTS, readOnly: Boolean = false): List<ArrangeItem> = buildList {
    if (WearText.tracksProducts(fields, null)) add(ArrangeItem("wearing", "Wearing now"))
    if (!readOnly) add(ArrangeItem("log", "Log"))
    add(ArrangeItem("today", "Today"))
    if (Fields.CONTINENCE_SUPPLIES in fields) add(ArrangeItem("lowsupplies", "Supplies running low"))
    add(ArrangeItem("summary", "Last two weeks"))
    add(ArrangeItem("progress", "How it's going"))
    if (WearText.tracksProducts(fields, null)) add(ArrangeItem("products", "How your products go"))
    add(ArrangeItem("insights", "What we've noticed"))
    if (Fields.CONTINENCE_BLADDER_TRAINING in fields) add(ArrangeItem("training", "Bladder training"))
    if (Fields.CONTINENCE_PELVIC_FLOOR in fields && !readOnly) add(ArrangeItem("pelvicfloor", "Pelvic floor exercises"))
    add(ArrangeItem("more", "Supplies, check-in and settings"))
}

@Composable
fun ContinenceScreen(state: ContinenceState, zone: ZoneId, actions: ContinenceActions, fields: Set<String> = Fields.DEFAULTS, now: Long = System.currentTimeMillis()) {
    var editing by remember { mutableStateOf<ContinenceEntry?>(null) }
    var changing by remember { mutableStateOf<ContinenceEntry?>(null) }
    // The accident just saved with "Save and change": counted in what comes off until the diary reloads with it.
    var justLogged by remember { mutableStateOf<ContinenceEntry?>(null) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    val supplies = state.supplies.map { it.supply }
    val sections = continenceSections(fields, readOnly)
    /** The Change sheet, starting from what's on now. */
    fun change() { changing = WearText.changeDraft(state.wearing, state.settings, supplies, state.recent, System.currentTimeMillis()) }
    fun open(e: ContinenceEntry) { if (e.kind == ContinenceKind.PAD_CHANGE) changing = e else editing = e }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Bladder & bowel", "A diary for you and your doctor or physio") {
            IconButton(onClick = actions.share) { Icon(Icons.Rounded.Share, "Share a 3-day diary") }
            ArrangeAction(FeatureScreens.CONTINENCE, "Bladder & bowel", sections)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = arrangedIn(FeatureScreens.CONTINENCE, sections)
            // What's on shows in the Wearing now card, with its own Change: Today and the quick buttons leave it out.
            val onAbove = "wearing" in shown && state.wearing?.supplyId != null && WearText.tracksProducts(fields, state.wearing)
            val log: @Composable () -> Unit = {
                InfoCard("Log", icon = Icons.Rounded.AddCircle, trailing = {
                    TitleAction(Icons.Rounded.EditNote, "Log with details") { editing = ContinenceEntry(System.currentTimeMillis(), ContinenceKind.URINATION) }
                }) {
                    // A change is one step with what came off and what went on: its sheet, quick to finish.
                    QuickButtons(
                        { kind -> if (kind == ContinenceKind.PAD_CHANGE) change() else actions.save(quickEntry(kind, state.settings, state.supplies, wearing = state.wearing)) },
                        WearText.quickKinds(quickButtonsFor(state.settings, fields), changeAbove = onAbove),
                    )
                    Hint("One tap logs it now; tap an entry to add details.", Modifier.padding(top = 8.dp))
                }
            }
            when {
                // Logging never waits for the rest.
                state.loading && state.summary == null -> { if ("log" in shown) log(); Loading() }
                state.error != null && state.summary == null -> {
                    if ("log" in shown) log()
                    ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                }
                else -> {
                    // Signs of an infection come first, and stay in plain sight.
                    val warnings = state.summary?.patterns.orEmpty().filter { it.tone == "warn" }
                    warnings.forEach { InsightCard(it) }
                    if (warnings.isEmpty() && ContinenceText.infectionSigns(state.entries)) InfectionNudge()
                    Sections(shown) { id ->
                        when (id) {
                            "wearing" -> if (WearText.tracksProducts(fields, state.wearing)) {
                                WearingCard(
                                    state.wearing, supplies, state.settings, state.recent, zone, readOnly,
                                    onChange = { change() }, onPutOn = actions.putOn, onSupplies = { actions.open("supplies/continence") }, now = now,
                                    onAccident = { editing = ContinenceEntry(System.currentTimeMillis(), ContinenceKind.LEAK, supplyId = state.wearing?.supplyId) },
                                    onOpen = { if (!readOnly) open(it) },
                                )
                            }
                            "log" -> log()
                            "today" -> {
                                val todays = state.todays(zone)
                                TodayTimeline(todays, WearTimeline.rows(state.diary, state.today, todays, currentAbove = onAbove), zone, state.today, supplies, readOnly) { if (!readOnly) open(it) }
                            }
                            "lowsupplies" -> {
                                val low = ContinenceText.lowFor(state.supplies, setOf(SupplyCategory.CONTINENCE, SupplyCategory.OTHER))
                                if (low.isNotEmpty()) LowSuppliesCard(low) { actions.open("supplies/continence") }
                            }
                            "summary" -> state.summary?.let { SummaryCard(it) }
                            "progress" -> state.progress?.let { ProgressTeaser(it) { actions.open("continence/progress") } }
                            "products" -> ProductsCard(state.products)
                            "insights" -> state.summary?.patterns.orEmpty().filter { it.tone != "warn" }.forEach { InsightCard(it) }
                            "training" -> BladderTrainingCard(state.settings, state.today, readOnly) { actions.open("continence/settings") }
                            "pelvicfloor" -> InfoCard("Pelvic floor exercises", icon = Icons.Rounded.SelfImprovement) {
                                Hint("A few minutes a day strengthens the muscles that hold urine in. A guide squeezes and relaxes with you.")
                                Spacer(Modifier.height(10.dp))
                                FilledTonalButton(onClick = actions.pelvicFloor) { Text("Start a set") }
                            }
                            "more" -> InfoCard(null) {
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
                }
            }
            Hint(
                "Leaks are common and treatable. A continence nurse or pelvic health physio can help.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    editing?.let { entry ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            EntryForm(
                entry, FormContext(fields, state.settings, supplies, state.wearing),
                onSave = { actions.save(it); editing = null },
                onDelete = entry.id?.let { id -> { actions.delete(id); editing = null } },
                onPadChange = { editing = null; change() },
                // Saved, then the Change sheet takes off what it was in, as it left it.
                onSaveAndChange = { saved ->
                    actions.save(saved)
                    editing = null
                    justLogged = saved
                    changing = WearText.changeAfter(saved, state.wearing, state.settings, supplies, state.recent, System.currentTimeMillis())
                },
            )
        }
    }
    changing?.let { entry ->
        val done = { changing = null; justLogged = null }
        ModalBottomSheet(onDismissRequest = done) {
            ChangeForm(
                entry, FormContext(fields, state.settings, supplies, WearText.withAccident(state.wearing, justLogged)), state.recent,
                onSave = { actions.save(it); done() },
                onDelete = entry.id?.let { id -> { actions.delete(id); done() } },
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

/**
 * Today: the counts, then what happened, newest first ([rows]). Each product worn is one row (its
 * name, how it was when it came off, and what went on next) with the accidents while it was on
 * beneath it; everything else is a row of its own.
 */
@Composable
private fun TodayTimeline(
    entries: List<ContinenceEntry>,
    rows: List<WearTimeline.Row>,
    zone: ZoneId,
    today: LocalDate,
    supplies: List<com.health.api.Supply>,
    readOnly: Boolean,
    onOpen: (ContinenceEntry) -> Unit,
) {
    InfoCard("Today", icon = Icons.Rounded.Today) {
        if (entries.isEmpty() && rows.isEmpty()) {
            Hint("Nothing logged today yet.")
            return@InfoCard
        }
        val counts = WearTimeline.counts(entries)
        Row(Modifier.fillMaxWidth()) {
            Stat("${counts.trips}", if (counts.trips == 1) "toilet trip" else "toilet trips", Modifier.weight(1f))
            Stat("${counts.leaks}", if (counts.leaks == 1) "leak" else "leaks", Modifier.weight(1f))
            Stat("${counts.bowel}", if (counts.bowel == 1) "bowel movement" else "bowel movements", Modifier.weight(1f))
        }
        val more = WearTimeline.moreCounts(counts)
        if (more.isNotEmpty()) Hint(more, Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        // Everything today is with what's on, in the card above.
        if (rows.isEmpty()) Hint("Nothing else logged today yet.")
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            when (row) {
                is WearTimeline.Single -> EntryRow(row.entry, zone, supplies, onOpen)
                is WearTimeline.Worn -> WornRow(row, zone, today, onOpen.takeIf { !readOnly })
            }
        }
    }
}

/** An entry on its own: a toilet trip, a bowel movement, a change… */
@Composable
private fun EntryRow(e: ContinenceEntry, zone: ZoneId, supplies: List<com.health.api.Supply>, onOpen: (ContinenceEntry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(e) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(kindIcon(e.kind), size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val change = e.kind == ContinenceKind.PAD_CHANGE
            // A change reads as what came off and what went on: "Night briefs → Day pants", then "worn 7 h".
            Text(if (change) WearText.changeTitle(e, supplies) else e.kind.label, style = MaterialTheme.typography.bodyLarge)
            val detail = if (change) {
                (WearText.changeDetail(e.copy(fullness = null)) + ContinenceText.detail(e.copy(supplyId = null, count = null, fullness = null, leakedThrough = false), supplies).takeIf { it.isNotEmpty() })
                    .filterNotNull().joinToString(" · ")
            } else ContinenceText.detail(e.copy(fullness = null, leakedThrough = e.leakedThrough && e.fullness != com.health.api.Fullness.LEAKED), supplies)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.fullness?.let { FullnessMeter(it, Modifier.padding(top = 4.dp)) }
        }
        Text(Format.timeOfDay(e.at, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A product worn, as one row: its name, "Worn 3 h 12 min · then Day pants" and how full it was,
 * when it came off on the right (a tap opens that change); beneath, on a thin line, the accidents
 * while it was on (each opens to edit), and "Put on at 7:48 PM" when that took nothing off.
 * What's on now shows here only when the Wearing now card is hidden, marked "On now".
 */
@Composable
private fun WornRow(row: WearTimeline.Worn, zone: ZoneId, today: LocalDate, onOpen: ((ContinenceEntry) -> Unit)?) {
    val w = row.wear
    val opens = row.opens
    val putOn = row.putOnAlone
    val beneath = w.accidents.isNotEmpty() || putOn != null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onOpen != null && opens != null) Modifier.clickable { onOpen(opens) } else Modifier.semantics(mergeDescendants = true) {})
            .padding(top = 6.dp, bottom = if (beneath) 2.dp else 6.dp),
        // With accidents beneath, the icon heads the line down to them.
        verticalAlignment = if (beneath) Alignment.Top else Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.Layers, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(WearTimeline.title(w), style = MaterialTheme.typography.bodyLarge)
            Text(WearTimeline.subtitle(w) { Format.timeOfDay(it, zone) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WearTimeline.fullness(w)?.let { (f, word) -> FullnessMeter(f, Modifier.padding(top = 4.dp), label = word) }
        }
        if (row.onNow) Pill("On now", MaterialTheme.colorScheme.primary, Modifier.padding(start = 8.dp))
        else TimeStamp(row.at, zone, today, Modifier.padding(start = 8.dp))
    }
    if (!beneath) return
    // Beneath it, on a thin line from its icon: what happened while it was on.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(36.dp).fillMaxHeight().padding(top = 2.dp, bottom = 10.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            w.accidents.forEach { a -> AccidentRow(a, zone, today, onOpen?.let { open -> { open(a) } }) }
            if (putOn != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Edit") { onOpen(putOn) } else Modifier)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(WearTimeline.putOnLine(putOn.at, zone, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        trailing = {
            Pill(p.verdict.label, verdictColor(p.verdict))
            OpenChevron("Week by week", onOpen)
        },
    ) {
        Text(if (ProgressText.enough(p)) p.headline else ProgressText.notEnough(p), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BladderTrainingCard(settings: ContinenceSettings, today: LocalDate, readOnly: Boolean, onOpen: () -> Unit) {
    val t = settings.bladderTraining
    if (!t.enabled && readOnly) return
    InfoCard(
        "Bladder training", icon = Icons.Rounded.Alarm, onClick = onOpen.takeIf { !readOnly },
        trailing = if (readOnly) null else ({ OpenChevron(if (t.enabled) "Change bladder training" else "Set up bladder training", onOpen) }),
    ) {
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
    }
}
