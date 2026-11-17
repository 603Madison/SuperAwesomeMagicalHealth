package com.health.app.ui.care

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.DueDose
import com.health.api.GlucoseUnit
import com.health.api.Layouts
import com.health.app.data.DeviceChoices
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeButton
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.ArrangeSheet
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.arrangedBy
import com.health.app.ui.components.foodNumbers
import com.health.app.ui.components.shownFields
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.modules.continence.ChangeSheet
import com.health.app.ui.modules.cycle.plainOnToday
import com.health.app.ui.modules.meds.SitePicker
import com.health.app.ui.modules.meds.needsSite
import com.health.app.ui.modules.photos.PhotoUnlock
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.delay

/*
 * The Care tab: what the person looks after, soonest first. One card for each area they use, each
 * with what's true now and at most one thing to do (the logic is in CareTabLogic.kt, the loading in
 * CareTabModel.kt). A card's header opens the area's page. Viewing someone's share, only what's
 * shared shows, and nothing can be done from here.
 */

/** Where "Choose what you track" goes: Settings › What you're tracking. */
const val CHOOSE_WHAT_YOU_TRACK = "settings/tracking"

/** What the tab can do: open a page ([open] takes a route), take a dose, log a change, try again. */
class CareActions(
    val open: (String) -> Unit = {},
    val take: (DueDose, String?) -> Unit = { _, _ -> },
    val change: () -> Unit = {},
    val retry: () -> Unit = {},
)

/**
 * The Care tab. [open] takes a route of the app's ("meds", "continence", "appointments", "food",
 * "transition", "neuro", "mind", "cycle", "vitals", "symptoms", "habits", "hormones", "photos",
 * a voice lesson's, and "settings/tracking").
 */
@Composable
fun CareRoute(open: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { CareViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    // Progress photos stay behind their lock: while it's on and they're locked, not even their number shows.
    val lockOn by remember(context) { DeviceChoices.of(context) }.photoLock.collectAsState()
    val unlocked by PhotoUnlock.unlocked.collectAsState()
    val locked = lockOn && !unlocked
    val viewing by c.viewing.collectAsState()
    val profile by c.profile.collectAsState()
    val viewProfile by c.viewProfile.collectAsState()
    // What's due moves with the clock.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val zone = c.zone()
    // Past midnight: today's cards, not yesterday's.
    val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    LaunchedEffect(date) { if (date != vm.state.value.today) vm.load() }
    var changing by remember { mutableStateOf(false) }
    val ctx = CareContext(
        now = now,
        zone = zone,
        today = state.today,
        modules = shownModules(),
        fields = shownFields(),
        foodNumbers = foodNumbers(),
        readOnly = LocalReadOnly.current || viewing != null,
        glucoseUnit = (if (viewing != null) viewProfile else profile)?.vitalsTargets?.glucoseUnit ?: GlucoseUnit.MMOL_L,
        plainCycle = plainOnToday(),
        photosLocked = locked,
        viewing = viewing != null,
        clock = { Format.timeOfDay(it, zone) },
    )
    CareScreen(state, ctx, CareActions(open = open, take = vm::take, change = { changing = true }, retry = vm::load))
    if (changing) ChangeSheet(onDismiss = { changing = false })
}

/** The tab itself, from [state] and [ctx]: drawn the same in the app and in screenshots. */
@Composable
fun CareScreen(state: CareState, ctx: CareContext, actions: CareActions) {
    var arranging by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<DueDose?>(null) }
    val areas = CareArea.shown(ctx.modules)
    val arranged = arrangedBy(Layouts.NAV, areas) { it.navId }
    val cards = CareOrder.dueFirst(arranged.map { CareCards.card(it, state.data, ctx, state.pending, state.failed) }, ctx.now)
    val act = { a: CareAction ->
        when (a.kind) {
            CareActionKind.TAKE -> a.dose?.let { d -> if (needsSite(d)) picking = d else actions.take(d, null) }
            CareActionKind.CHANGE, CareActionKind.PUT_ON -> actions.change()
            CareActionKind.START -> a.route?.let(actions.open)
        }
        Unit
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Care", if (ctx.viewing) "What's shared with you, soonest first" else "What you look after, soonest first") {
            if (areas.size > 1) ArrangeButton { arranging = true }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                areas.isEmpty() -> NothingTracked(ctx.viewing) { actions.open(CHOOSE_WHAT_YOU_TRACK) }
                state.starting -> Loading()
                cards.isEmpty() -> AllHidden(ctx.readOnly) { arranging = true }
                else -> {
                    if (state.problem) Problem(actions.retry)
                    cards.forEach { card ->
                        key(card.area) { CareCardView(card, onOpen = { actions.open(card.area.route) }, onAction = act) }
                    }
                }
            }
            // Choosing is the person's own: never from someone else's share.
            if (areas.isNotEmpty() && !ctx.viewing) {
                TextButton(onClick = { actions.open(CHOOSE_WHAT_YOU_TRACK) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                    Text("Choose what you track", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (arranging) {
        ArrangeSheet(Layouts.NAV, "Care", areas.map { ArrangeItem(it.navId, it.title) }, { arranging = false }, hiddenMeans = "Hidden here, still on")
    }
    picking?.let { due ->
        val sites = state.data.meds?.firstOrNull { it.id == due.medicationId }?.sites.orEmpty()
        SitePicker(due, sites, onDismiss = { picking = null }) { site ->
            actions.take(due, site)
            picking = null
        }
    }
}

/** A card: its header (the way to the area's page), then its live row, chart or bar, and lines. */
@Composable
private fun CareCardView(card: CareCard, onOpen: () -> Unit, onAction: (CareAction) -> Unit) {
    // A single line and nothing else sits under the title, as a short card.
    val short = card.now == null && card.bar == null && card.week == null && card.subtitle == null && card.lines.size == 1
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(card, if (short) card.lines.first() else card.subtitle, onOpen)
            if (card.loading) Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.now?.let { NowRow(it, onAction) }
            card.bar?.let { ProgressBar(it, color = MaterialTheme.colorScheme.tertiary) }
            if (card.week != null || (!short && card.lines.isNotEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.week?.let { Week(it) }
                    if (!short) card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** The area's tile, its name (and a short line), and a chevron: all one tap to its page. */
@Composable
private fun Header(card: CareCard, subtitle: String?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open ${card.area.title}", role = Role.Button, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val green = card.area == CareArea.FOOD
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (green) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (green) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(ModuleInfo.icon(card.area.module), null, Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What's live now, with its one action: Take, Change, Put on or Start. */
@Composable
private fun NowRow(now: CareNow, onAction: (CareAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(now.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            now.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        now.action?.let { a ->
            Spacer(Modifier.width(12.dp))
            val said = Modifier.semantics { contentDescription = a.description }
            if (a.kind == CareActionKind.START) {
                FilledTonalButton(onClick = { onAction(a) }, modifier = said) { Text(a.label, maxLines = 1) }
            } else {
                Button(onClick = { onAction(a) }, modifier = said) { Text(a.label, maxLines = 1) }
            }
        }
    }
}

/**
 * Seven days of toilet trips as bars, today's the strongest; leaks as dots above their day's bar
 * (a different shape, not only a different colour), with a key when there are any.
 */
@Composable
private fun Week(week: List<TripDay>) {
    val today = MaterialTheme.colorScheme.primary
    val past = today.copy(alpha = 0.35f)
    val leak = AppTheme.status.warn
    val ring = MaterialTheme.colorScheme.surfaceContainer
    val description = remember(week) { CareWords.weekDescription(week) }
    Canvas(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = description }) {
        val gap = 8.dp.toPx()
        val w = (size.width - gap * (week.size - 1)) / week.size
        // Room above the bars for the leak dots.
        val top = 12.dp.toPx()
        val most = week.maxOf { it.trips }.coerceAtLeast(1)
        val corner = CornerRadius(4.dp.toPx())
        val dot = 3.5.dp.toPx()
        val step = 9.dp.toPx()
        week.forEachIndexed { i, d ->
            val x = i * (w + gap)
            val h = ((size.height - top) * d.trips / most).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (d.today) today else past, Offset(x, size.height - h), Size(w, h), corner)
            if (d.leaks > 0) {
                val n = d.leaks.coerceAtMost(3)
                val y = size.height - h - 7.dp.toPx()
                val start = x + w / 2 - step * (n - 1) / 2
                repeat(n) { k ->
                    drawCircle(ring, dot + 1.5.dp.toPx(), Offset(start + k * step, y))
                    drawCircle(leak, dot, Offset(start + k * step, y))
                }
            }
        }
    }
    if (week.any { it.leaks > 0 }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Box(Modifier.size(width = 8.dp, height = 12.dp).clip(RoundedCornerShape(2.dp)).background(past))
            Spacer(Modifier.width(6.dp))
            Text("Trips", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(leak))
            Spacer(Modifier.width(6.dp))
            Text("Leaks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Some part couldn't load: said gently once, with a way to try again. The rest still shows. */
@Composable
private fun Problem(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Text("Some of this couldn't load just now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}

/** Nothing tracked yet (or nothing of this shared): a kind word, and the way to choose. */
@Composable
private fun NothingTracked(viewing: Boolean, onChoose: () -> Unit) {
    if (viewing) {
        EmptyState(Icons.Rounded.VolunteerActivism, "Nothing here is shared with you", "When medications, appointments or other care are shared with you, they'll show here.")
        return
    }
    EmptyState(
        Icons.Rounded.VolunteerActivism,
        "Nothing to look after here yet",
        "Choose what you'd like to keep an eye on, like medications, appointments or a bladder diary, and it'll show here, soonest first.",
    ) {
        Button(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose what you track") }
    }
}

/** Every card hidden with Arrange: how to bring them back. */
@Composable
private fun AllHidden(readOnly: Boolean, onArrange: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Everything here is hidden.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!readOnly) TextButton(onClick = onArrange, modifier = Modifier.heightIn(min = 48.dp)) { Text("Arrange") }
        }
    }
}
