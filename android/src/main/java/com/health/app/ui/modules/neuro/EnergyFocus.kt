@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.health.app.ui.modules.neuro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.api.CheckTag
import com.health.api.EnergyCheck
import com.health.api.EnergyFocus
import com.health.api.EnergyFocusSettings
import com.health.api.Medication
import com.health.api.TimeBlock
import com.health.app.ui.Format
import com.health.app.ui.care.TimePickDialog
import com.health.app.ui.components.CardHeading
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.modules.neuro.EnergyFocusMath.MarkKind
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Energy and focus on the Autism & ADHD page (its section "energyfocus"): a check-in in two taps,
 * today's check-ins and doses on a strip, the person's own patterns once there are enough, how it
 * works and the research behind it, and the settings (the ADHD medication, reminders). The quick
 * check-in sheet (from the + sheet and Right now) is the same two rows, the tags and Save.
 */

/** What the energy and focus section can do. */
class EnergyFocusActions(
    val checkIn: (EnergyCheck) -> Unit = {},
    val delete: (String) -> Unit = {},
    val saveSettings: (EnergyFocusSettings) -> Unit = {},
    val retry: () -> Unit = {},
)

/**
 * The section: the check-in (never waiting for anything), today, the patterns with how it works,
 * and the settings. [meds]: whether Medications is on (the ADHD medication's setting needs it).
 * [howOpen]: "How this works" starts open (for pictures of it).
 */
@Composable
fun EnergyFocusSection(
    ef: EnergyFocus?,
    error: String?,
    medications: List<Medication>,
    today: LocalDate,
    zone: ZoneId,
    readOnly: Boolean,
    meds: Boolean,
    actions: EnergyFocusActions,
    howOpen: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Energy and focus", icon = Icons.Rounded.Bolt) {
            if (!readOnly) {
                CardHeading("Check in")
                Hint("How are you right now? Tap a number for each, or just one.", Modifier.padding(top = 2.dp, bottom = 10.dp))
                EnergyCheckForm(withNote = true, onSave = actions.checkIn)
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            CardHeading("Today", note = ef?.today?.size?.takeIf { it > 0 }?.let { if (it == 1) "1 check-in" else "$it check-ins" })
            Spacer(Modifier.height(8.dp))
            if (ef != null) {
                TodayStrip(ef.today, ef.dosesToday, ef.medication, today, zone, readOnly, actions.delete)
            } else {
                Hint(if (error != null) "Couldn't load today's check-ins just now." else "Loading…")
            }
        }
        when {
            ef != null -> {
                InfoCard("Your patterns", icon = Icons.Rounded.Insights) {
                    Patterns(ef)
                    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    HowThisWorks(ef.about, ef.sources, howOpen)
                }
                if (!readOnly) SettingsCard(ef, medications, meds, actions.saveSettings)
            }
            error != null -> ErrorCard(error, actionLabel = "Try again", onAction = actions.retry)
        }
    }
}

// ---------------------------------------------------------------- Checking in

/**
 * Energy and focus, each a row of five, then what else was going on, an optional note
 * ([withNote]), and Save: it saves now, then starts afresh.
 */
@Composable
fun EnergyCheckForm(withNote: Boolean, onSave: (EnergyCheck) -> Unit, modifier: Modifier = Modifier) {
    var energy by rememberSaveable { mutableStateOf<Int?>(null) }
    var focus by rememberSaveable { mutableStateOf<Int?>(null) }
    var tags by remember { mutableStateOf(emptySet<CheckTag>()) }
    var note by rememberSaveable { mutableStateOf("") }
    var noting by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RatingRow("Energy", MarkKind.ENERGY, energy, EnergyFocusMath.ENERGY_LOW, EnergyFocusMath.ENERGY_HIGH) { energy = it }
        RatingRow("Focus", MarkKind.FOCUS, focus, EnergyFocusMath.FOCUS_LOW, EnergyFocusMath.FOCUS_HIGH) { focus = it }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Anything else going on? (optional)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckTag.entries.forEach { t ->
                    val on = t in tags
                    FilterChip(selected = on, onClick = { tags = if (on) tags - t else tags + t }, label = { Text(t.label) })
                }
            }
        }
        if (withNote) {
            if (noting || note.isNotEmpty()) {
                OutlinedTextField(note, { note = it.take(1000) }, label = { Text("Note (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { noting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add a note")
                }
            }
        }
        Button(
            onClick = {
                onSave(EnergyCheck(System.currentTimeMillis(), energy, focus, tags, note.trim().ifEmpty { null }))
                energy = null; focus = null; tags = emptySet(); note = ""; noting = false
            },
            enabled = EnergyFocusMath.canSave(energy, focus),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Save") }
    }
}

/** A 1–5 row: its mark and name, five 48 dp circles (a tap again clears), and plain words at each end. */
@Composable
private fun RatingRow(label: String, kind: MarkKind, value: Int?, low: String, high: String, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarkGlyph(kind)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (value != null) Text("$value of 5", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..5).forEach { n ->
                val selected = value == n
                val spoken = EnergyFocusMath.optionSpoken(label, n, low, high)
                val pick = { onChange(if (selected) null else n) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = pick).clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.RadioButton
                        this.selected = selected
                        onClick(spoken) { pick(); true }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(44.dp),
                    ) { Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.titleMedium) } }
                }
            }
        }
        Row {
            Text(low, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(high, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** The quick check-in, as a bottom sheet: the same two rows, the tags and Save (from the + sheet and Right now). */
@Composable
fun EnergyCheckSheet(onDismiss: () -> Unit, onSave: (EnergyCheck) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EnergyCheckSheetContent(onSave)
    }
}

/** What the quick check-in sheet holds. */
@Composable
fun EnergyCheckSheetContent(onSave: (EnergyCheck) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Energy and focus", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Hint("How are you right now? Two taps, for your patterns.", Modifier.padding(bottom = 10.dp))
        EnergyCheckForm(withNote = false, onSave = onSave)
    }
}

// ---------------------------------------------------------------- Today

/**
 * Today from 6 AM to midnight: each check-in's energy (a ring) and focus (a diamond) at their
 * heights, doses of the ADHD medication (a triangle and a dashed line), then the check-ins in words.
 */
@Composable
private fun TodayStrip(checks: List<EnergyCheck>, doses: List<Long>, medication: String?, date: LocalDate, zone: ZoneId, readOnly: Boolean, onDelete: (String) -> Unit) {
    if (checks.isEmpty() && doses.isEmpty()) {
        Hint("Nothing yet today. Each check-in shows here, by the time you made it.")
        return
    }
    val chart = AppTheme.chart
    val grid = MaterialTheme.colorScheme.outlineVariant
    val card = MaterialTheme.colorScheme.surfaceContainer
    val marks = EnergyFocusMath.marks(checks, doses, date, zone)
    val clock = { at: Long -> Format.timeOfDay(at, zone) }
    val spoken = buildList {
        add("Today's check-ins")
        checks.sortedBy { it.at }.forEach { add(EnergyFocusMath.spoken(it, clock)) }
        doses.sorted().forEach { add("${medication ?: "ADHD medication"} at ${clock(it)}") }
    }.joinToString(". ")
    val inset = 8.dp
    Canvas(Modifier.fillMaxWidth().height(92.dp).clearAndSetSemantics { contentDescription = spoken }) {
        val left = inset.toPx()
        val right = size.width - inset.toPx()
        val top = 9.dp.toPx()
        val bottom = size.height - 9.dp.toPx()
        fun x(f: Float) = left + f * (right - left)
        fun y(f: Float) = top + f * (bottom - top)
        // Guides at 5, 3 and 1; faint hours at noon and 6 PM.
        listOf(0f, 0.5f, 1f).forEach { drawLine(grid, Offset(left, y(it)), Offset(right, y(it)), 1.dp.toPx()) }
        listOf(1f / 3, 2f / 3).forEach { drawLine(grid, Offset(x(it), top), Offset(x(it), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))) }
        val r = 7.5.dp.toPx()
        marks.filter { it.kind == MarkKind.DOSE }.forEach { m ->
            drawLine(chart.c3, Offset(x(m.x), top - 4.dp.toPx()), Offset(x(m.x), bottom), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            doseMark(Offset(x(m.x), bottom), r * 0.8f, chart.c3)
        }
        // Each through the day: energy a solid line, focus a dashed one.
        listOf(MarkKind.ENERGY, MarkKind.FOCUS).forEach { kind ->
            val run = marks.filter { it.kind == kind }
            run.zipWithNext().forEach { (a, b) ->
                drawLine(
                    if (kind == MarkKind.ENERGY) chart.c1 else chart.c2, Offset(x(a.x), y(a.y!!)), Offset(x(b.x), y(b.y!!)), 2.dp.toPx(),
                    cap = StrokeCap.Round, pathEffect = if (kind == MarkKind.FOCUS) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null,
                )
            }
        }
        // Rings first, then diamonds: where energy and focus meet, the diamond sits inside the ring.
        marks.filter { it.kind == MarkKind.ENERGY }.forEach { m -> energyMark(Offset(x(m.x), y(m.y ?: return@forEach)), r, chart.c1, card) }
        marks.filter { it.kind == MarkKind.FOCUS }.forEach { m -> focusMark(Offset(x(m.x), y(m.y ?: return@forEach)), r, chart.c2, card) }
    }
    FractionLabels(EnergyFocusMath.STRIP_LABELS, inset)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        LegendMark(MarkKind.ENERGY, "Energy")
        LegendMark(MarkKind.FOCUS, "Focus")
        if (doses.isNotEmpty()) LegendMark(MarkKind.DOSE, medication ?: "ADHD medication")
        Text("Higher is more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    EnergyFocusMath.beforeStrip(checks, date, zone).takeIf { it > 0 }?.let { n ->
        Hint(if (n == 1) "1 check-in before 6 AM is listed below." else "$n check-ins before 6 AM are listed below.", Modifier.padding(top = 4.dp))
    }
    var removing by remember { mutableStateOf<EnergyCheck?>(null) }
    Column(Modifier.padding(top = 8.dp)) {
        checks.sortedByDescending { it.at }.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(c.at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    EnergyFocusMath.ratings(c)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    EnergyFocusMath.detail(c)?.let { Hint(it) }
                }
                if (!readOnly && c.id != null) {
                    IconButton(onClick = { removing = c }) { Icon(Icons.Rounded.Close, "Remove the ${clock(c.at)} check-in", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this check-in?") },
            text = { Text(EnergyFocusMath.checkLine(c, clock) ?: clock(c.at)) },
            confirmButton = { TextButton(onClick = { c.id?.let(onDelete); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep it") } },
        )
    }
}

// ---------------------------------------------------------------- Patterns

@Composable
private fun Patterns(ef: EnergyFocus) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!ef.enough) {
            Text(ef.needed ?: "A few more check-ins and your patterns start to show.", style = MaterialTheme.typography.bodyLarge)
            ProgressBar(EnergyFocusMath.progress(ef.checks, ef.days), color = MaterialTheme.colorScheme.primary)
            Hint(EnergyFocusMath.soFar(ef.checks, ef.days))
        } else {
            val lines = EnergyFocusMath.summaryLines(ef)
            if (lines.isEmpty() && ef.factors.isEmpty()) {
                Text("Nothing stands out yet. That can change as you check in.", style = MaterialTheme.typography.bodyLarge)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (ef.byHour.isNotEmpty()) {
                CardHeading("By time of day", Modifier.padding(top = 6.dp))
                ChartLegend()
                BlockChart(ef.byHour, EnergyFocusMath::dayBlock, best = ef.bestFocus)
            }
            if (ef.sinceDose.isNotEmpty()) {
                CardHeading(EnergyFocusMath.sinceTitle(ef.medication), Modifier.padding(top = 6.dp))
                BlockChart(ef.sinceDose, EnergyFocusMath::doseBlock, best = null)
            }
            ef.factors.forEach { f ->
                Column(Modifier.padding(top = 4.dp)) {
                    Text(f.title, style = MaterialTheme.typography.titleSmall)
                    Text(f.sentence, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint(EnergyFocusMath.basedOn(ef.checks, ef.days))
        }
        Text(EnergyFocusMath.CAPTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The marks' key for the charts. */
@Composable
private fun ChartLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendMark(MarkKind.ENERGY, "Energy")
        LegendMark(MarkKind.FOCUS, "Focus")
        Text("Averages, 1 to 5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Blocks of hours, a row each: energy (a ring) and focus (a diamond) along 1–5, joined; [best] (best focus) in bold. */
@Composable
private fun BlockChart(blocks: List<TimeBlock>, label: (TimeBlock) -> String, best: TimeBlock?) {
    val chart = AppTheme.chart
    val grid = MaterialTheme.colorScheme.outlineVariant
    val join = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val card = MaterialTheme.colorScheme.surfaceContainer
    val labelWidth = 112.dp
    val inset = 8.dp
    Column {
        Row {
            Spacer(Modifier.width(labelWidth))
            Box(Modifier.weight(1f)) { FractionLabels(listOf(0f to "1", 0.5f to "3", 1f to "5"), inset) }
        }
        blocks.forEach { b ->
            val name = label(b)
            val isBest = best != null && b.from == best.from && b.to == best.to
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).clearAndSetSemantics {
                    contentDescription = EnergyFocusMath.blockSpoken(name, b) + if (isBest) ". Your best focus" else ""
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name, style = MaterialTheme.typography.bodySmall, fontWeight = if (isBest) FontWeight.Bold else null,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(labelWidth).padding(end = 6.dp),
                )
                Canvas(Modifier.weight(1f).height(28.dp)) {
                    val left = inset.toPx()
                    val right = size.width - inset.toPx()
                    val mid = size.height / 2
                    fun x(v: Double) = left + EnergyFocusMath.scaleX(v) * (right - left)
                    drawLine(grid, Offset(left, mid), Offset(right, mid), 2.dp.toPx(), cap = StrokeCap.Round)
                    (1..5).forEach { v -> drawCircle(grid, 2.dp.toPx(), Offset(x(v.toDouble()), mid)) }
                    val e = b.energy
                    val f = b.focus
                    if (e != null && f != null) drawLine(join, Offset(x(e), mid), Offset(x(f), mid), 3.dp.toPx(), cap = StrokeCap.Round)
                    val r = 7.5.dp.toPx()
                    e?.let { energyMark(Offset(x(it), mid), r, chart.c1, card) }
                    f?.let { focusMark(Offset(x(it), mid), r, chart.c2, card) }
                }
            }
        }
    }
}

/** "How this works": the about and the research, folded until asked for. */
@Composable
private fun HowThisWorks(about: String, sources: List<String>, startOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { open = !open }
            .semantics { stateDescription = if (open) "Shown" else "Folded" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("How this works", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodyMedium)
            if (sources.isNotEmpty()) {
                Text("The research", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                sources.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsCard(ef: EnergyFocus, medications: List<Medication>, meds: Boolean, onSave: (EnergyFocusSettings) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val settings = ef.settings
    InfoCard("Energy and focus settings", icon = Icons.Rounded.Tune) {
        if (meds) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your ADHD medication", style = MaterialTheme.typography.bodyLarge)
                    Hint(EnergyFocusMath.medicationLine(ef))
                }
                TextButton(onClick = { picking = true }) { Text(if (ef.medication == null) "Choose" else "Change") }
            }
            Hint("Only to show your focus by hours since a dose.")
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text("Reminders to check in", style = MaterialTheme.typography.bodyLarge)
        Hint(if (settings.reminders.isEmpty()) "None. Add a time if a gentle nudge would help." else "A gentle nudge at each of these times.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            settings.reminders.forEach { t ->
                val text = EnergyFocusMath.reminderText(t)
                InputChip(
                    selected = false,
                    onClick = { onSave(settings.copy(reminders = EnergyFocusMath.withoutReminder(settings.reminders, t))) },
                    label = { Text(text) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Remove the $text reminder", Modifier.size(18.dp)) },
                )
            }
            if (settings.reminders.size < EnergyFocusMath.MAX_REMINDERS) {
                AssistChip(onClick = { adding = true }, label = { Text("Add a time") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
            }
        }
    }
    if (picking) {
        MedicationPicker(EnergyFocusMath.choices(medications, settings.medicationId), EnergyFocusMath.selected(EnergyFocusMath.choices(medications, settings.medicationId), ef), onDismiss = { picking = false }) { id ->
            picking = false
            onSave(EnergyFocusMath.choose(settings, id))
        }
    }
    if (adding) {
        TimePickDialog(LocalTime.of(10, 0), "Remind me at", onDismiss = { adding = false }) { time ->
            adding = false
            onSave(settings.copy(reminders = EnergyFocusMath.withReminder(settings.reminders, time)))
        }
    }
}

/** Choosing the ADHD medication from the person's own, or none of them. */
@Composable
private fun MedicationPicker(choices: List<Medication>, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your ADHD medication") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Hint("Only to show your focus by hours since a dose.", Modifier.padding(bottom = 8.dp))
                choices.forEach { m -> RadioLine(listOfNotNull(m.name, m.dose).joinToString(", "), selected == m.id) { onPick(m.id) } }
                RadioLine("None of these", selected == null) { onPick(null) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RadioLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.RadioButton
                this.selected = selected
                onClick(text) { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ---------------------------------------------------------------- Social battery

/** Today's social battery, 0 empty to 10 full: saved as the slider is let go. */
@Composable
fun SocialBatteryCard(value: Int?, readOnly: Boolean, onSave: (Int) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    InfoCard("Social battery", icon = Icons.Rounded.BatteryChargingFull) {
        Text(v?.let { "$it of 10" } ?: "Not set today", style = MaterialTheme.typography.titleMedium)
        if (readOnly) return@InfoCard
        Hint("How much you've got left for people today.")
        Slider(
            value = (v ?: 5).toFloat(),
            onValueChange = { v = it.roundToInt().coerceIn(0, 10) },
            onValueChangeFinished = { v?.let(onSave) },
            valueRange = 0f..10f, steps = 9,
            modifier = Modifier.semantics { contentDescription = "Social battery today, 0 empty to 10 full" },
        )
        Row {
            Text("Empty", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Full", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Marks

/** Energy: a ring (filled with the card, so lines don't show through). */
private fun DrawScope.energyMark(center: Offset, r: Float, color: Color, fill: Color) {
    drawCircle(fill, r, center)
    drawCircle(color, r - 1.25f * density, center, style = Stroke(2.5f * density))
}

/** Focus: a filled diamond, edged with the card, small enough to sit inside energy's ring of the same [r] where they meet. */
private fun DrawScope.focusMark(center: Offset, r: Float, color: Color, edge: Color) {
    val d = r * 0.64f
    val path = Path().apply {
        moveTo(center.x, center.y - d)
        lineTo(center.x + d, center.y)
        lineTo(center.x, center.y + d)
        lineTo(center.x - d, center.y)
        close()
    }
    drawPath(path, edge, style = Stroke(1.5f * density))
    drawPath(path, color)
}

/** A dose: a triangle pointing up. */
private fun DrawScope.doseMark(center: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r, center.y + r * 0.75f)
        lineTo(center.x - r, center.y + r * 0.75f)
        close()
    }
    drawPath(path, color)
}

@Composable
private fun MarkGlyph(kind: MarkKind, size: Dp = 14.dp) {
    val chart = AppTheme.chart
    val card = MaterialTheme.colorScheme.surfaceContainer
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2 * 0.86f
        when (kind) {
            MarkKind.ENERGY -> energyMark(c, r, chart.c1, card)
            MarkKind.FOCUS -> focusMark(c, r * 1.5f, chart.c2, card)
            MarkKind.DOSE -> doseMark(c, r, chart.c3)
        }
    }
}

@Composable
private fun LegendMark(kind: MarkKind, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MarkGlyph(kind, 12.dp)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Labels centred at their places along a line ([inset] in from each end), kept inside it. */
@Composable
private fun FractionLabels(labels: List<Pair<Float, String>>, inset: Dp) {
    Layout(
        content = {
            labels.forEach { (_, text) -> Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val left = inset.roundToPx()
        val span = (width - 2 * left).coerceAtLeast(0)
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val centre = left + (labels[i].first * span).roundToInt()
                p.placeRelative((centre - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
