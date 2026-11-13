@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.cycle

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.Cervix
import com.health.api.Clots
import com.health.api.CycleDay
import com.health.api.CycleSettings
import com.health.api.Fields
import com.health.api.Flow
import com.health.api.Mucus
import com.health.api.OvulationTest
import com.health.api.PregnancyTest
import com.health.api.ProductUse
import com.health.api.SexEntry
import com.health.api.Supply
import com.health.api.SupplyCategory
import com.health.api.SupplyKind
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.ChipPicker
import com.health.app.ui.modules.CustomFieldInputs
import com.health.app.ui.modules.StepScale
import com.health.app.ui.modules.continence.FullnessGauge
import com.health.app.ui.modules.continence.Stepper
import kotlin.math.roundToInt

/** The words the cycle's pickers offer, before the person's own. */
object CycleWords {
    val PAIN_AREAS = listOf("lower belly", "lower back", "pelvis", "thighs", "rectum", "chest", "head", "joints")
    val MOODS = listOf("calm", "content", "energetic", "irritable", "anxious", "low", "tearful", "sensitive", "foggy")
    val COLORS = listOf("bright red", "dark red", "brown", "pink", "black")
    val DISCHARGE = listOf("none", "creamy", "sticky", "watery", "unusual colour", "unusual smell")
    val SEVERITY = listOf("Mild", "Moderate", "Severe")
    val POSITION = listOf("low", "medium", "high")
    val FIRMNESS = listOf("firm", "medium", "soft")
    val OPENING = listOf("closed", "a little open", "open")

    /** The symptoms to offer: the person's own first, then the usual ones, less any they've hidden. */
    fun symptoms(settings: CycleSettings): List<String> =
        (settings.symptoms + SYMPTOMS).distinct().filter { s -> settings.hiddenSymptoms.none { it.equals(s, ignoreCase = true) } }

    /** Period supplies a product can come from. */
    fun periodSupplies(supplies: List<Supply>) = supplies.filter { it.active && it.id != null && it.kind.category == SupplyCategory.PERIOD }

    val PRODUCT_KINDS = SupplyKind.entries.filter { it.category == SupplyCategory.PERIOD }
}

/**
 * Logging a day: bleeding and products, pain and where, symptoms and how bad, mood, energy, sex,
 * signs, tests, and the person's own fields. The core (symptoms, a note) always shows; the rest
 * when its field is on. Anything already logged that this form doesn't show is kept as it was.
 */
@Composable
fun DayForm(
    date: java.time.LocalDate,
    existing: CycleDay?,
    units: Units,
    onSave: (CycleDay) -> Unit,
    onClear: () -> Unit,
    fields: Set<String> = Fields.DEFAULTS,
    settings: CycleSettings = CycleSettings(),
    supplies: List<Supply> = emptyList(),
) {
    fun shows(key: String) = key in fields
    var d by remember { mutableStateOf(existing ?: CycleDay(date.toString())) }
    var temp by remember {
        mutableStateOf(existing?.temperature?.let { Format.upTo2(if (units == Units.IMPERIAL) it * 9 / 5 + 32 else it) }.orEmpty())
    }
    var lh by remember { mutableStateOf(existing?.lh?.let { Format.upTo2(it) }.orEmpty()) }
    val periodSupplies = CycleWords.periodSupplies(supplies)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(Format.longDay(date), style = MaterialTheme.typography.titleLarge)
        if (shows(Fields.CYCLE_FLOW)) {
            Section("Bleeding") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<Flow?>(null) + Flow.entries).forEach { f -> FilterChip(selected = d.flow == f, onClick = { d = d.copy(flow = f) }, label = { Text(f?.label ?: "None") }) }
                }
            }
        }
        if (shows(Fields.CYCLE_PRODUCTS)) {
            Section("Products used") {
                Products(d.products, periodSupplies) { d = d.copy(products = it) }
            }
        }
        if (shows(Fields.CYCLE_CLOTS)) {
            Section("Clots") { Chips(Clots.entries, d.clots, { it.label }) { d = d.copy(clots = it) } }
        }
        if (shows(Fields.CYCLE_COLOR)) {
            Section("Colour") { OneWord(CycleWords.COLORS, d.color, { d = d.copy(color = it) }, "Another colour") }
        }
        if (shows(Fields.CYCLE_PAIN)) {
            Column {
                Row {
                    Text("Pain", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(d.pain?.let { if (it == 0) "None" else "$it of 10" } ?: "Not noted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = (d.pain ?: 0).toFloat(), onValueChange = { d = d.copy(pain = it.roundToInt()) }, valueRange = 0f..10f, steps = 9)
            }
        }
        if (shows(Fields.CYCLE_PAIN_AREAS)) {
            Section("Where it hurts") {
                ChipPicker((settings.painAreas + CycleWords.PAIN_AREAS).distinct(), d.painAreas.toSet(), { d = d.copy(painAreas = it.toList()) }, addLabel = "Somewhere else")
            }
        }
        Section("Symptoms") {
            ChipPicker(CycleWords.symptoms(settings), d.symptoms.toSet(), { picked ->
                d = d.copy(symptoms = picked.toList(), symptomSeverity = d.symptomSeverity.filterKeys { k -> picked.any { it.equals(k, ignoreCase = true) } })
            }, addLabel = "Another symptom")
        }
        if (shows(Fields.CYCLE_SYMPTOM_SEVERITY) && d.symptoms.isNotEmpty()) {
            Section("How bad") {
                d.symptoms.forEach { s ->
                    val key = s.lowercase()
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CycleWords.SEVERITY.forEachIndexed { i, label ->
                            val v = i + 1
                            val on = d.symptomSeverity[key] == v
                            FilterChip(
                                selected = on,
                                onClick = { d = d.copy(symptomSeverity = if (on) d.symptomSeverity - key else d.symptomSeverity + (key to v)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
        if (shows(Fields.CYCLE_MOOD)) {
            Section("Mood") { ChipPicker((settings.moods + CycleWords.MOODS).distinct(), d.mood.toSet(), { d = d.copy(mood = it.toList()) }, addLabel = "Another feeling") }
        }
        if (shows(Fields.CYCLE_ENERGY)) StepScale("Energy", d.energy, "Low", "High", { d = d.copy(energy = it) })
        if (shows(Fields.CYCLE_LIBIDO)) StepScale("Sex drive", d.libido, "Low", "High", { d = d.copy(libido = it) })
        if (shows(Fields.CYCLE_SEX)) {
            Section("Sex") {
                val sex = d.sex
                LabeledSwitch("Had sex", sex?.had == true, { on -> d = d.copy(sex = if (on) (sex ?: SexEntry()).copy(had = true) else null) })
                if (sex?.had == true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(true to "Protected", false to "Unprotected").forEach { (v, label) ->
                            FilterChip(selected = sex.protected == v, onClick = { d = d.copy(sex = sex.copy(protected = if (sex.protected == v) null else v)) }, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(sex.note.orEmpty(), { d = d.copy(sex = sex.copy(note = it.take(500).ifBlank { null })) }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (shows(Fields.CYCLE_MUCUS)) {
            Section("Cervical mucus") { Chips(Mucus.entries, d.mucus, { it.label }) { d = d.copy(mucus = it) } }
        }
        if (shows(Fields.CYCLE_DISCHARGE)) {
            Section("Discharge") { OneWord(CycleWords.DISCHARGE, d.discharge, { d = d.copy(discharge = it) }, "Something else") }
        }
        if (shows(Fields.CYCLE_CERVIX)) {
            Section("Cervix") {
                val c = d.cervix ?: Cervix()
                fun set(v: Cervix) { d = d.copy(cervix = v.takeIf { it != Cervix() }) }
                Text("Position", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chips(CycleWords.POSITION, c.position, { it }) { set(c.copy(position = it)) }
                Text("Feels", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chips(CycleWords.FIRMNESS, c.firmness, { it }) { set(c.copy(firmness = it)) }
                Text("Opening", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chips(CycleWords.OPENING, c.opening, { it }) { set(c.copy(opening = it)) }
            }
        }
        if (shows(Fields.CYCLE_OVULATION_TEST)) {
            Section("Ovulation test") { Chips(OvulationTest.entries, d.ovulationTest, { it.label }) { d = d.copy(ovulationTest = it) } }
        }
        if (shows(Fields.CYCLE_LH)) {
            NumberField(lh, { lh = it }, "LH reading", supporting = "If your test reader shows a number")
        }
        if (shows(Fields.CYCLE_PREGNANCY_TEST)) {
            Section("Pregnancy test") { Chips(PregnancyTest.entries, d.pregnancyTest, { it.label }) { d = d.copy(pregnancyTest = it) } }
        }
        if (shows(Fields.CYCLE_BBT)) {
            NumberField(temp, { temp = it }, "Temperature on waking", suffix = if (units == Units.IMPERIAL) "°F" else "°C", supporting = "Optional: your ring's nightly temperature is used too")
        }
        if (settings.customFields.isNotEmpty()) {
            CustomFieldInputs(settings.customFields, d.details) { d = d.copy(details = it) }
        }
        Section("Tags") { ChipPicker(emptyList(), d.tags.toSet(), { d = d.copy(tags = it.toList()) }, addLabel = "Add a tag") }
        OutlinedTextField(d.note.orEmpty(), { d = d.copy(note = it.ifBlank { null }) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp))
        Button(
            onClick = {
                val celsius = Format.parse(temp)?.let { if (units == Units.IMPERIAL) (it - 32) * 5 / 9 else it }?.takeIf { it in 34.0..42.0 }
                onSave(
                    d.copy(
                        temperature = if (temp.isBlank()) null else celsius ?: d.temperature,
                        lh = if (lh.isBlank()) null else Format.parse(lh)?.takeIf { it in 0.0..500.0 } ?: d.lh,
                        note = d.note?.trim()?.ifBlank { null },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (existing != null) TextButton(onClick = onClear, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Clear this day") }
    }
}

/** Products used: each from a supply (counted down) or just its kind, how many, and how full. */
@Composable
private fun Products(products: List<ProductUse>, supplies: List<Supply>, onChange: (List<ProductUse>) -> Unit) {
    products.forEachIndexed { i, p ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        fun set(v: ProductUse) = onChange(products.mapIndexed { j, x -> if (j == i) v else x })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(supplies.firstOrNull { it.id == p.supplyId }?.name ?: p.kind.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(products.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove this product") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            supplies.forEach { s -> FilterChip(selected = p.supplyId == s.id, onClick = { set(p.copy(supplyId = s.id, kind = s.kind)) }, label = { Text(com.health.app.ui.modules.continence.ContinenceText.supplyName(s)) }) }
            // Kinds without a supply of their own, for products not kept count of.
            CycleWords.PRODUCT_KINDS.filter { k -> supplies.none { it.kind == k } || (p.supplyId == null && p.kind == k) }.forEach { k ->
                FilterChip(selected = p.supplyId == null && p.kind == k, onClick = { set(p.copy(kind = k, supplyId = null)) }, label = { Text(k.label) })
            }
        }
        Stepper("How many", p.count, 1..20) { set(p.copy(count = it)) }
        FullnessGauge(p.fullness, { set(p.copy(fullness = it)) })
    }
    TextButton(onClick = {
        val first = supplies.firstOrNull()
        onChange(products + ProductUse(first?.kind ?: SupplyKind.PERIOD_PAD, supplyId = first?.id))
    }) {
        Icon(Icons.Rounded.Add, null)
        Spacer(Modifier.width(6.dp))
        Text(if (products.isEmpty()) "Add a product" else "Add another")
    }
    if (supplies.isNotEmpty()) Hint("Ones from your period products come off what you have on hand.")
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T?, label: (T) -> String, onChange: (T?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = selected == o, onClick = { onChange(if (selected == o) null else o) }, label = { Text(label(o)) }) }
    }
}

/** One word from [options] or the person's own. */
@Composable
private fun OneWord(options: List<String>, selected: String?, onChange: (String?) -> Unit, addLabel: String) {
    ChipPicker(options, setOfNotNull(selected), { picked ->
        onChange((picked - setOfNotNull(selected)).firstOrNull() ?: picked.firstOrNull()?.takeIf { picked.size == 1 })
    }, addLabel = addLabel)
}
