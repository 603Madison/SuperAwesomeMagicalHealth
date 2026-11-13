@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.health.app.ui.modules.continence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.ContinenceEntry
import com.health.api.ContinenceSettings
import com.health.api.ContinenceWearing
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.ProductStats
import com.health.api.Supply
import com.health.app.net.continenceSettings
import com.health.app.net.continenceWearing
import com.health.app.net.supplies
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.shownFields
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.StepScale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * What's being worn, and a change as one step: the diary's "Wearing now" card, the Change sheet
 * (what came off and how it was, what went on, when), and how each product goes. Today's quick
 * log and Up next open the same sheet. While viewing a share, nothing changes.
 */

/**
 * The diary's "Wearing now": what's on, since when and the usual, with Change; with nothing on,
 * or nothing known ("What are you wearing?"), a row of products to put on with a tap.
 */
@Composable
fun WearingCard(
    wearing: ContinenceWearing?,
    supplies: List<Supply>,
    settings: ContinenceSettings,
    recent: List<String>,
    zone: ZoneId,
    readOnly: Boolean,
    onChange: () -> Unit,
    onPutOn: (String) -> Unit,
    onSupplies: () -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    val clock = { at: Long -> Format.timeOfDay(at, zone) }
    if (wearing?.supplyId != null) {
        InfoCard("Wearing now", icon = Icons.Rounded.Layers) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(wearing.name ?: "A product", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterVertically))
                WearText.kindWord(wearing.kind ?: supplies.firstOrNull { it.id == wearing.supplyId }?.kind)?.let { KindTag(it, Modifier.align(Alignment.CenterVertically)) }
            }
            Text(WearText.sinceLine(wearing, now, clock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
            val usual = WearText.usualLine(wearing)
            val due = WearText.changeDue(wearing, now)
            if (usual != null || due) {
                Hint(listOfNotNull(usual?.let { "$it." }, "Time for a check when you're ready.".takeIf { due }).joinToString(" "), Modifier.padding(top = 4.dp))
            }
            if (!readOnly) {
                Button(onClick = onChange, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Change")
                }
            }
        }
        return
    }
    if (readOnly && wearing?.nothingOn != true) return
    val offer = WearText.putOnOrder(supplies, settings.defaultSupplyId, recent)
    InfoCard(if (wearing?.nothingOn == true) "Wearing now" else "What are you wearing?", icon = Icons.Rounded.Layers) {
        if (wearing?.nothingOn == true) {
            Text("Nothing on for now", style = MaterialTheme.typography.headlineSmall)
            Text(WearText.sinceLine(wearing, now, clock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
        if (!readOnly) {
            if (offer.isEmpty()) {
                Hint("Add your pads, briefs or pull-ups in Supplies, and this keeps track of what you're wearing and for how long.")
                TextButton(onClick = onSupplies) { Text("Open Supplies") }
            } else {
                Hint(if (wearing?.nothingOn == true) "Put something on:" else "Tap what you have on, and the diary keeps the time.", Modifier.padding(top = 6.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    offer.forEach { s ->
                        FilterChip(
                            selected = false, onClick = { s.id?.let(onPutOn) },
                            leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                            label = { Text(s.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindTag(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/**
 * A change as one step, on one sheet: what came off (what's on, already picked) and how it was
 * (as much as the person's fields show), what went on (the same again, picked; the usual and the
 * last used first; or nothing for now), and when. Saving logs one change with all of it.
 */
@Composable
fun ChangeForm(initial: ContinenceEntry, context: FormContext, recent: List<String>, onSave: (ContinenceEntry) -> Unit, onDelete: (() -> Unit)?) {
    val fields = context.fields
    fun shows(key: String) = key in fields
    var e by remember { mutableStateOf(WearText.asChange(initial)) }
    var weight by remember { mutableStateOf(initial.padWeightG?.let { Format.upTo2(it) }.orEmpty()) }
    var minutesAgo by remember { mutableIntStateOf(0) }
    val worn = WearText.wearable(context.supplies)
    // The one taken off may have been put away since: it's still shown.
    val offOptions = worn + context.supplies.filter { s -> s.id == e.supplyId && worn.none { it.id == s.id } }
    val onOptions = WearText.putOnOrder(context.supplies, context.settings.defaultSupplyId, recent)
    val off = context.supplies.firstOrNull { it.id == e.supplyId }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (initial.id == null) "Change" else "Edit change", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

        Step("Taking off") {
            if (offOptions.isEmpty()) Hint("Add your pads, briefs or pull-ups in Supplies to pick them here.")
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offOptions.forEach { s -> FilterChip(selected = e.supplyId == s.id, onClick = { e = e.copy(supplyId = s.id) }, label = { Text(s.name) }) }
                FilterChip(selected = e.supplyId == null, onClick = { e = e.copy(supplyId = null, fullness = null) }, label = { Text("Nothing") })
            }
        }

        val how = e.supplyId != null && (shows(Fields.CONTINENCE_PROTECTION) || shows(Fields.CONTINENCE_CHANGES) || shows(Fields.CONTINENCE_SKIN) || shows(Fields.CONTINENCE_PAD_WEIGHT))
        if (how) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("How it was", style = MaterialTheme.typography.titleSmall)
                    if (shows(Fields.CONTINENCE_PROTECTION)) {
                        FullnessGauge(e.fullness, { e = e.copy(fullness = it, leakedThrough = if (it == Fullness.LEAKED) true else e.leakedThrough) })
                    }
                    if (shows(Fields.CONTINENCE_CHANGES)) {
                        Column {
                            LabeledSwitch("It leaked through", e.leakedThrough, { e = e.copy(leakedThrough = it) })
                            LabeledSwitch("Changed clothes", e.changedClothes, { e = e.copy(changedClothes = it) })
                            LabeledSwitch("Changed bedding", e.changedBedding, { e = e.copy(changedBedding = it) })
                        }
                    }
                    if (shows(Fields.CONTINENCE_SKIN)) StepScale("Skin", e.skin, "Fine", "Very sore", { e = e.copy(skin = it) }, range = 0..3)
                    if (shows(Fields.CONTINENCE_PAD_WEIGHT)) {
                        val loss = ContinenceText.loss(Format.parse(weight), off)
                        NumberField(
                            weight, { weight = it }, "Weight when wet", suffix = "g",
                            supporting = when {
                                loss != null -> "About ${ContinenceText.fmt(loss)} g lost (it weighs ${ContinenceText.fmt(off!!.dryWeightG!!)} g dry)"
                                off?.dryWeightG != null -> "It weighs ${ContinenceText.fmt(off.dryWeightG!!)} g dry, so the amount lost is worked out"
                                else -> "Add its dry weight in Supplies, and the amount lost is worked out"
                            },
                        )
                    }
                }
            }
        }

        Step("Putting on") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onOptions.forEach { s ->
                    FilterChip(
                        selected = !e.nothingOn && e.putOnSupplyId == s.id,
                        onClick = { e = e.copy(putOnSupplyId = s.id, nothingOn = false) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name)
                                if (s.id == context.settings.defaultSupplyId) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("usual", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                    )
                }
                FilterChip(selected = e.nothingOn, onClick = { e = e.copy(putOnSupplyId = null, nothingOn = true) }, label = { Text("Nothing for now") })
            }
        }

        if (initial.id == null) {
            Step("When") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Now", 15 to "15 min ago", 30 to "30 min ago", 60 to "1 hour ago", 120 to "2 hours ago").forEach { (m, label) ->
                        FilterChip(selected = minutesAgo == m, onClick = { minutesAgo = m }, label = { Text(label) })
                    }
                }
            }
        }
        OutlinedTextField(e.note.orEmpty(), { e = e.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val at = if (initial.id == null) initial.at - minutesAgo * 60_000L else e.at
                val grams = Format.parse(weight)?.takeIf { it > 0 && it <= 5000 }
                onSave(WearText.finishChange(e.copy(at = at, padWeightG = grams)))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Save") }
        if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
    }
}

@Composable
private fun Step(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** How each product goes: "Night briefs: usually 7 h, often full, leaked through 1 in 12". Nothing without changes to go on. */
@Composable
fun ProductsCard(stats: List<ProductStats>) {
    if (stats.isEmpty()) return
    InfoCard("How your products go", icon = Icons.Rounded.Layers) {
        stats.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(WearText.productPattern(p).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${p.changes} ${if (p.changes == 1) "change" else "changes"}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Hint("From the last 90 days of changes. A continence nurse can help if a product often leaks through.")
    }
}

/** What the Change sheet needs, loaded by itself (from Today's quick log and Up next). */
private class ChangeData(val supplies: List<Supply>, val settings: ContinenceSettings, val recent: List<String>, val wearing: ContinenceWearing?)

/** The Change sheet from anywhere outside the diary: it loads what it needs, saves, and closes. */
@Composable
fun ChangeSheet(onDismiss: () -> Unit) {
    val c = appContainer()
    val fields = shownFields()
    val data by produceState<ChangeData?>(null) {
        value = runCatching {
            coroutineScope {
                val supplies = async { runCatching { c.ui.supplies() }.getOrDefault(emptyList()) }
                val settings = async { runCatching { c.ui.continenceSettings() }.getOrDefault(ContinenceSettings()) }
                val entries = async { runCatching { c.ui.continence(3) }.getOrDefault(emptyList()) }
                val wearing = async { runCatching { c.ui.continenceWearing() }.getOrNull() }
                ChangeData(supplies.await(), settings.await(), WearText.recentPutOns(entries.await()), wearing.await())
            }
        }.getOrNull()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val d = data
        if (d == null) Loading()
        else ChangeForm(
            WearText.changeDraft(d.wearing, d.settings, d.supplies, d.recent, System.currentTimeMillis()),
            FormContext(fields, d.settings, d.supplies), d.recent,
            onSave = { entry ->
                c.scope.launch {
                    runCatching { c.ui.addContinence(entry) }
                        .onSuccess { c.tell("Change logged"); c.dataChanged(); c.continenceChanged() }
                        .onFailure { c.tell(it.message ?: "Couldn't save that") }
                }
                onDismiss()
            },
            onDelete = null,
        )
    }
}
