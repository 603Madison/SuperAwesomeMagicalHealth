@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.Awareness
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.Fields
import com.health.api.LeakContents
import com.health.api.LeakSize
import com.health.api.ReachedToilet
import com.health.api.Supply
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.CustomFieldInputs
import com.health.app.ui.modules.StepScale
import com.health.app.ui.theme.AppTheme

/** What the diary's form needs besides the entry: which fields show, the person's own words, and their supplies. */
data class FormContext(
    val fields: Set<String> = Fields.DEFAULTS,
    val settings: ContinenceSettings = ContinenceSettings(),
    val supplies: List<Supply> = emptyList(),
)

/**
 * An entry's details: what it was, when, and whatever fits that kind, as much or as little as
 * the person wants. The core (the kind, the time, a note, blood) always shows; everything else
 * shows when its field is on (Settings › Fields, or the person's conditions).
 */
@Composable
fun EntryForm(initial: ContinenceEntry, context: FormContext, onSave: (ContinenceEntry) -> Unit, onDelete: (() -> Unit)?) {
    val fields = context.fields
    fun shows(key: String) = key in fields
    val settings = context.settings
    var e by remember { mutableStateOf(withDefaultSupply(initial, context)) }
    var volume by remember { mutableStateOf(initial.volumeMl?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(initial.padWeightG?.let { Format.upTo2(it) }.orEmpty()) }
    var reps by remember { mutableStateOf(initial.reps?.toString().orEmpty()) }
    var hold by remember { mutableStateOf(initial.holdSeconds?.toString().orEmpty()) }
    var minutesAgo by remember { mutableIntStateOf(0) }
    val kind = e.kind
    val accident = ContinenceText.isAccident(kind)
    val supplies = ContinenceText.suppliesFor(kind, context.supplies)
    val supply = supplies.firstOrNull { it.id == e.supplyId }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (initial.id == null) "Add to your diary" else kind.label, style = MaterialTheme.typography.titleLarge)
        if (initial.id == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ContinenceText.kinds(fields).forEach { k ->
                    FilterChip(selected = kind == k, onClick = { e = withDefaultSupply(e.copy(kind = k), context) }, label = { Text(k.label) })
                }
            }
            Section("When") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Now", 15 to "15 min ago", 30 to "30 min ago", 60 to "1 hour ago", 120 to "2 hours ago", 480 to "Overnight").forEach { (m, label) ->
                        FilterChip(selected = minutesAgo == m, onClick = { minutesAgo = m }, label = { Text(label) })
                    }
                }
            }
        }

        // ---- What happened
        if (kind == ContinenceKind.BOWEL_LEAK || (accident && shows(Fields.CONTINENCE_BOWEL_ACCIDENTS))) {
            Section("What leaked") {
                Chips(ContinenceText.contentsFor(kind), e.contents, { it.label }) { e = e.copy(contents = it) }
            }
        }
        if (accident && shows(Fields.CONTINENCE_LEAK_SIZE)) {
            Section("How much") { Chips(LeakSize.entries, e.leak, { it.label }) { e = e.copy(leak = it) } }
        }
        if (kind == ContinenceKind.PAD_CHANGE && shows(Fields.CONTINENCE_LEAK_SIZE) && !shows(Fields.CONTINENCE_PROTECTION)) {
            Section("How wet") { Chips(LeakSize.entries, e.leak, { it.label }) { e = e.copy(leak = it) } }
        }
        if (kind == ContinenceKind.BOWEL || (kind == ContinenceKind.BOWEL_LEAK && e.contents != LeakContents.WIND)) {
            if (shows(Fields.CONTINENCE_BRISTOL)) {
                Section("Stool type (Bristol scale)") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BRISTOL.forEachIndexed { i, text ->
                            FilterChip(selected = e.bristol == i + 1, onClick = { e = e.copy(bristol = if (e.bristol == i + 1) null else i + 1) }, label = { Text("${i + 1} · $text") })
                        }
                    }
                }
            }
        }
        if (kind == ContinenceKind.URINATION || kind == ContinenceKind.CATHETER) {
            if (kind == ContinenceKind.CATHETER || shows(Fields.CONTINENCE_VOLUME)) {
                NumberField(volume, { volume = it }, if (kind == ContinenceKind.CATHETER) "How much drained" else "Volume, if you measured", suffix = "ml", decimals = false)
            }
        }
        val urgent = kind == ContinenceKind.URINATION || kind == ContinenceKind.BOWEL || kind == ContinenceKind.LEAK || kind == ContinenceKind.BOWEL_LEAK
        if (urgent && shows(Fields.CONTINENCE_URGENCY)) {
            StepScale("Urgency", e.urgency, "None", "Couldn't wait", { e = e.copy(urgency = it) }, range = 0..3)
        }
        if (urgent && kind != ContinenceKind.BOWEL && shows(Fields.CONTINENCE_WARNING)) {
            Section("How much warning you had") {
                val nearest = ContinenceText.nearestWarning(e.warningSeconds)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContinenceText.WARNINGS.forEach { (s, label) ->
                        FilterChip(selected = nearest == s, onClick = { e = e.copy(warningSeconds = if (nearest == s) null else s) }, label = { Text(label) })
                    }
                }
            }
        }
        if (accident && shows(Fields.CONTINENCE_AWARENESS)) {
            Section("Did you feel it?") { Chips(Awareness.entries, e.awareness, { it.label }) { e = e.copy(awareness = it) } }
        }
        if ((kind == ContinenceKind.LEAK || kind == ContinenceKind.BOWEL_LEAK || kind == ContinenceKind.URINATION) && shows(Fields.CONTINENCE_REACHED_TOILET)) {
            Section("Did you reach the toilet?") {
                val options = if (kind == ContinenceKind.URINATION) listOf(ReachedToilet.YES, ReachedToilet.PARTLY, ReachedToilet.NO) else ReachedToilet.entries
                Chips(options, e.reachedToilet, { it.label }) { e = e.copy(reachedToilet = it) }
            }
        }
        if ((kind == ContinenceKind.LEAK || kind == ContinenceKind.BOWEL_LEAK) && shows(Fields.CONTINENCE_TRIGGERS)) {
            Section("What set it off?") {
                ChipPicker((TRIGGERS + settings.triggers).distinct(), e.triggers.toSet(), { e = e.copy(triggers = it.toList()) }, addLabel = "Something else")
            }
        }
        if ((kind == ContinenceKind.LEAK || kind == ContinenceKind.BOWEL_LEAK) && shows(Fields.CONTINENCE_ACTIVITY)) {
            Section("What you were doing") {
                ChipPicker((ContinenceText.ACTIVITIES + settings.activities).distinct(), e.activity.toSet(), { e = e.copy(activity = it.toList()) }, addLabel = "Something else")
            }
        }

        // ---- What was worn or used
        val productFields = shows(Fields.CONTINENCE_PROTECTION) || shows(Fields.CONTINENCE_SUPPLIES)
        val picksSupply = (ContinenceText.usesProduct(kind) && productFields) || (kind == ContinenceKind.CATHETER && (productFields || shows(Fields.CONTINENCE_CATHETER)))
        if (picksSupply && supplies.isNotEmpty()) {
            Section(if (kind == ContinenceKind.CATHETER) "Catheter" else if (kind == ContinenceKind.PAD_CHANGE) "What you changed" else "What you were wearing") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = e.supplyId == null, onClick = { e = e.copy(supplyId = null, count = if (kind == ContinenceKind.PAD_CHANGE) e.count else null) }, label = { Text("Nothing listed") })
                    supplies.forEach { s ->
                        FilterChip(
                            selected = e.supplyId == s.id,
                            onClick = { e = e.copy(supplyId = s.id, count = if (kind == ContinenceKind.CATHETER && e.count == null) 1 else e.count) },
                            label = { Text(ContinenceText.supplyName(s)) },
                        )
                    }
                }
            }
        }
        if (kind == ContinenceKind.PAD_CHANGE || (kind == ContinenceKind.CATHETER && e.supplyId != null)) {
            Stepper(if (kind == ContinenceKind.PAD_CHANGE) "How many" else "Catheters used", e.count ?: 1, 1..20) { e = e.copy(count = it) }
            if (supply != null && !supply.reusable) Hint("These come off what you have on hand (${supply.onHand} now).")
        }
        if (accident && e.supplyId != null) {
            LabeledSwitch(
                "Changed it afterwards", e.count != null, { e = e.copy(count = if (it) 1 else null) },
                subtitle = if (supply?.reusable == false) "Takes one off what you have on hand." else null,
            )
        }
        if (ContinenceText.usesProduct(kind) && shows(Fields.CONTINENCE_PROTECTION)) {
            FullnessGauge(e.fullness, { e = e.copy(fullness = it, leakedThrough = if (it == com.health.api.Fullness.LEAKED) true else e.leakedThrough) })
        }
        if (ContinenceText.usesProduct(kind) && shows(Fields.CONTINENCE_PAD_WEIGHT)) {
            val loss = ContinenceText.loss(Format.parse(weight), supply)
            NumberField(
                weight, { weight = it }, "Weight when wet", suffix = "g",
                supporting = when {
                    loss != null -> "About ${ContinenceText.fmt(loss)} g lost (it weighs ${ContinenceText.fmt(supply!!.dryWeightG!!)} g dry)"
                    supply?.dryWeightG == null && e.supplyId != null -> "Add its dry weight in Supplies, and the amount lost is worked out"
                    supply?.dryWeightG != null -> "It weighs ${ContinenceText.fmt(supply.dryWeightG!!)} g dry, so the amount lost is worked out"
                    else -> "With a product's dry weight, the amount lost is worked out"
                },
            )
        }
        if ((accident || kind == ContinenceKind.PAD_CHANGE) && shows(Fields.CONTINENCE_CHANGES)) {
            Section("Afterwards") {
                LabeledSwitch("It leaked through", e.leakedThrough, { e = e.copy(leakedThrough = it) })
                if (accident) LabeledSwitch("Changed clothes", e.changedClothes, { e = e.copy(changedClothes = it) })
                if (accident || kind == ContinenceKind.PAD_CHANGE) LabeledSwitch("Changed bedding", e.changedBedding, { e = e.copy(changedBedding = it) })
            }
        }
        if ((accident || kind == ContinenceKind.PAD_CHANGE) && shows(Fields.CONTINENCE_SKIN)) {
            StepScale("Skin", e.skin, "Fine", "Very sore", { e = e.copy(skin = it) }, range = 0..3)
        }

        // ---- Body and place
        if (kind == ContinenceKind.PELVIC_FLOOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(reps, { reps = it }, "Squeezes", Modifier.weight(1f), decimals = false)
                NumberField(hold, { hold = it }, "Held for", Modifier.weight(1f), suffix = "s", decimals = false)
            }
        }
        if ((kind == ContinenceKind.URINATION || kind == ContinenceKind.BOWEL || kind == ContinenceKind.CATHETER) && shows(Fields.CONTINENCE_PAIN)) {
            LabeledSwitch("It hurt", e.pain, { e = e.copy(pain = it) })
        }
        if (kind == ContinenceKind.URINATION || kind == ContinenceKind.BOWEL || kind == ContinenceKind.CATHETER) {
            LabeledSwitch("There was blood", e.blood, { e = e.copy(blood = it) }, subtitle = if (e.blood) "Worth mentioning to your doctor soon." else null)
        }
        if ((kind == ContinenceKind.URINATION || kind == ContinenceKind.CATHETER || kind == ContinenceKind.LEAK) && shows(Fields.CONTINENCE_UTI_SIGNS)) {
            Section("Anything else you noticed?") {
                ChipPicker(ContinenceText.UTI_SIGNS, e.utiSigns.toSet(), { e = e.copy(utiSigns = it.toList()) }, addLabel = "Something else")
            }
        }
        if (e.utiSigns.isNotEmpty()) InfectionNudge()
        if (kind != ContinenceKind.PELVIC_FLOOR && kind != ContinenceKind.BEDWETTING && shows(Fields.CONTINENCE_LOCATION)) {
            Section("Where you were") {
                val options = (ContinenceText.LOCATIONS + settings.locations).distinct()
                ChipPicker(options, setOfNotNull(e.location), { picked -> e = e.copy(location = (picked - setOfNotNull(e.location)).firstOrNull() ?: picked.firstOrNull()?.takeIf { picked.size == 1 }) }, addLabel = "Somewhere else")
            }
        }

        // ---- The person's own
        if (settings.customFields.isNotEmpty()) {
            CustomFieldInputs(settings.customFields, e.details) { e = e.copy(details = it) }
        }
        Section("Tags") {
            ChipPicker(emptyList(), e.tags.toSet(), { e = e.copy(tags = it.toList()) }, addLabel = "Add a tag")
        }
        OutlinedTextField(e.note.orEmpty(), { e = e.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        Button(
            onClick = {
                val at = if (initial.id == null) System.currentTimeMillis() - minutesAgo * 60_000L else e.at
                onSave(finish(e.copy(at = at), volume, weight, reps, hold))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
    }
}

/** The usual product, chosen already for a new entry that uses one. */
private fun withDefaultSupply(e: ContinenceEntry, context: FormContext): ContinenceEntry {
    if (e.id != null || !ContinenceText.usesProduct(e.kind)) return if (e.id == null && e.kind != ContinenceKind.CATHETER) e.copy(supplyId = null) else e
    val default = context.settings.defaultSupplyId?.takeIf { id -> ContinenceText.suppliesFor(e.kind, context.supplies).any { it.id == id } }
    return e.copy(supplyId = e.supplyId ?: default)
}

/** The form's typed numbers into the entry; fields left over from another kind are dropped. */
fun finish(e: ContinenceEntry, volume: String, weight: String, reps: String, hold: String): ContinenceEntry {
    val kind = e.kind
    val accident = ContinenceText.isAccident(kind)
    return e.copy(
        volumeMl = Format.parse(volume)?.toInt()?.takeIf { it in 1..2000 && (kind == ContinenceKind.URINATION || kind == ContinenceKind.CATHETER) },
        padWeightG = Format.parse(weight)?.takeIf { it > 0 && it <= 5000 && ContinenceText.usesProduct(kind) },
        reps = if (kind == ContinenceKind.PELVIC_FLOOR) reps.trim().toIntOrNull()?.takeIf { it in 1..500 } ?: e.reps else null,
        holdSeconds = if (kind == ContinenceKind.PELVIC_FLOOR) hold.trim().toIntOrNull()?.takeIf { it in 1..120 } ?: e.holdSeconds else null,
        bristol = e.bristol.takeIf { kind == ContinenceKind.BOWEL || kind == ContinenceKind.BOWEL_LEAK },
        leak = e.leak.takeIf { accident || kind == ContinenceKind.PAD_CHANGE || kind == ContinenceKind.URINATION },
        contents = e.contents.takeIf { accident },
        fullness = e.fullness.takeIf { ContinenceText.usesProduct(kind) },
        supplyId = e.supplyId.takeIf { ContinenceText.usesProduct(kind) || kind == ContinenceKind.CATHETER },
        count = e.count.takeIf { kind == ContinenceKind.PAD_CHANGE || e.supplyId != null },
        note = e.note?.trim()?.ifBlank { null },
    )
}

/** The server's nudge about the signs of a urine infection, kept in plain sight. */
@Composable
fun InfectionNudge(modifier: Modifier = Modifier) {
    Surface(
        color = AppTheme.status.warn.copy(alpha = 0.12f), contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.LocalHospital, null, tint = AppTheme.status.warn, modifier = Modifier.padding(end = 10.dp).size(20.dp))
            Column {
                Text("Worth telling a doctor or nurse", style = MaterialTheme.typography.titleSmall)
                Text(ContinenceText.INFECTION_NUDGE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** One of [options], or none: tapping the chosen one again clears it. */
@Composable
private fun <T> Chips(options: List<T>, selected: T?, label: (T) -> String, onChange: (T?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = selected == o, onClick = { onChange(if (selected == o) null else o) }, label = { Text(label(o)) }) }
    }
}

/** A count with − and +, each a full-size target. */
@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(enabled = value > range.first, onClick = { onChange(value - 1) }) { Icon(Icons.Rounded.Remove, "One fewer") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(enabled = value < range.last, onClick = { onChange(value + 1) }) { Icon(Icons.Rounded.Add, "One more") }
    }
}
