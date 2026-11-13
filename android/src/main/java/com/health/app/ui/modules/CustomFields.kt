@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.CustomField
import com.health.api.CustomFieldType
import com.health.app.ui.components.Hint
import com.health.app.ui.log.NumberField
import kotlin.math.roundToInt

/**
 * The person's own fields on a feature's form ([CustomField]), drawn by their type, with values
 * kept as text in the entry's `details` (several choices joined with "; ").
 */
object CustomFieldValues {
    const val SEPARATOR = "; "

    fun split(value: String?): List<String> = value?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun join(values: Collection<String>): String? = values.filter { it.isNotBlank() }.joinToString(SEPARATOR).ifBlank { null }

    /** A key for a new field from its label: "how-tired", unique among [taken]. */
    fun keyFor(label: String, taken: Set<String>): String {
        val base = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(36).ifEmpty { "field" }
        var key = base
        var n = 2
        while (key in taken) key = "$base-${n++}"
        return key
    }

    /** "7 of 10", "Yes", "Tired; foggy": a value as a list shows it. */
    fun show(field: CustomField, value: String?): String? {
        val v = value?.takeIf { it.isNotBlank() } ?: return null
        return when (field.type) {
            CustomFieldType.SCALE -> "$v of ${field.max}"
            CustomFieldType.YES_NO -> if (v == "yes") "Yes" else "No"
            CustomFieldType.NUMBER -> v + (field.unit?.let { " $it" } ?: "")
            else -> v
        }
    }

    /** Values set for [fields], as "Label: value" lines. */
    fun lines(fields: List<CustomField>, details: Map<String, String>): List<String> =
        fields.mapNotNull { f -> show(f, details[f.key])?.let { "${f.label}: $it" } }
}

/** One of the person's own fields, as an input. */
@Composable
fun CustomFieldInput(field: CustomField, value: String?, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (field.type) {
            CustomFieldType.TEXT -> OutlinedTextField(
                value.orEmpty(), { onChange(it.take(500).ifBlank { null }) },
                label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
            )
            CustomFieldType.NUMBER -> NumberField(value.orEmpty(), { onChange(it.take(20).ifBlank { null }) }, field.label, suffix = field.unit)
            CustomFieldType.SCALE -> {
                val range = field.min..maxOf(field.max, field.min + 1)
                val current = value?.toIntOrNull()?.coerceIn(range)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(current?.let { "$it of ${range.last}" } ?: "Not noted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = (current ?: range.first).toFloat(),
                    onValueChange = { onChange(it.roundToInt().toString()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                    modifier = Modifier.semantics { contentDescription = field.label },
                )
            }
            CustomFieldType.CHOICE -> {
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.options.forEach { o -> FilterChip(selected = value == o, onClick = { onChange(if (value == o) null else o) }, label = { Text(o) }) }
                }
            }
            CustomFieldType.MULTI -> {
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                val picked = CustomFieldValues.split(value).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.options.forEach { o ->
                        FilterChip(selected = o in picked, onClick = { onChange(CustomFieldValues.join(if (o in picked) picked - o else picked + o)) }, label = { Text(o) })
                    }
                }
            }
            CustomFieldType.YES_NO -> {
                Text(field.label, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("yes" to "Yes", "no" to "No").forEach { (v, label) ->
                        FilterChip(selected = value == v, onClick = { onChange(if (value == v) null else v) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

/** Every one of [fields] as inputs, writing into [details]. */
@Composable
fun CustomFieldInputs(fields: List<CustomField>, details: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    fields.forEach { f ->
        CustomFieldInput(f, details[f.key], { v -> onChange(if (v == null) details - f.key else details + (f.key to v)) })
    }
}

private fun typeLabel(t: CustomFieldType) = when (t) {
    CustomFieldType.TEXT -> "Words"
    CustomFieldType.NUMBER -> "A number"
    CustomFieldType.SCALE -> "A scale"
    CustomFieldType.CHOICE -> "One of a few"
    CustomFieldType.MULTI -> "Any of a few"
    CustomFieldType.YES_NO -> "Yes or no"
}

/** The person's own fields for a feature: listed, added, changed and taken away. */
@Composable
fun CustomFieldsEditor(fields: List<CustomField>, onChange: (List<CustomField>) -> Unit, max: Int = 30) {
    var editing by remember { mutableStateOf<CustomField?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fields.isEmpty()) Hint("None yet. Add anything you'd like to note each time: a number, a scale, a choice or words.")
        fields.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, style = MaterialTheme.typography.bodyLarge)
                    Hint(typeLabel(f.type) + (f.options.takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(", ")}" } ?: "") +
                        (if (f.type == CustomFieldType.SCALE) " (${f.min}–${f.max})" else "") + (f.unit?.let { ", in $it" } ?: ""))
                }
                TextButton(onClick = { editing = f }) { Text("Edit") }
                IconButton(onClick = { onChange(fields - f) }) { Icon(Icons.Rounded.Delete, "Remove ${f.label}") }
            }
        }
        if (fields.size < max) {
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Rounded.Add, null)
                Text("Add a field", Modifier.padding(start = 6.dp))
            }
        }
    }
    if (adding || editing != null) {
        CustomFieldDialog(
            editing,
            onDismiss = { adding = false; editing = null },
            onSave = { saved ->
                val existing = editing
                onChange(
                    if (existing != null) fields.map { if (it.key == existing.key) saved.copy(key = existing.key) else it }
                    else fields + saved.copy(key = CustomFieldValues.keyFor(saved.label, fields.map { it.key }.toSet())),
                )
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun CustomFieldDialog(initial: CustomField?, onDismiss: () -> Unit, onSave: (CustomField) -> Unit) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: CustomFieldType.SCALE) }
    var options by remember { mutableStateOf(initial?.options?.joinToString(", ").orEmpty()) }
    var min by remember { mutableStateOf((initial?.min ?: 0).toString()) }
    var max by remember { mutableStateOf((initial?.max ?: 10).toString()) }
    var unit by remember { mutableStateOf(initial?.unit.orEmpty()) }
    val optionList = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
    val lo = min.trim().toIntOrNull()
    val hi = max.trim().toIntOrNull()
    val valid = label.isNotBlank() && when (type) {
        CustomFieldType.CHOICE, CustomFieldType.MULTI -> optionList.size >= 2
        CustomFieldType.SCALE -> lo != null && hi != null && hi > lo && hi - lo <= 100
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a field" else "Change a field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it.take(60) }, label = { Text("What to note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CustomFieldType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(typeLabel(t)) }) }
                }
                when (type) {
                    CustomFieldType.CHOICE, CustomFieldType.MULTI -> OutlinedTextField(
                        options, { options = it.take(400) }, label = { Text("Choices, with commas between") }, modifier = Modifier.fillMaxWidth(),
                    )
                    CustomFieldType.SCALE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(min, { min = it }, "From", Modifier.weight(1f), decimals = false)
                        NumberField(max, { max = it }, "To", Modifier.weight(1f), decimals = false)
                    }
                    CustomFieldType.NUMBER -> OutlinedTextField(unit, { unit = it.take(12) }, label = { Text("Unit (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    CustomField(
                        key = initial?.key.orEmpty(), label = label.trim(), type = type,
                        options = if (type == CustomFieldType.CHOICE || type == CustomFieldType.MULTI) optionList else emptyList(),
                        min = if (type == CustomFieldType.SCALE) lo ?: 0 else 0, max = if (type == CustomFieldType.SCALE) hi ?: 10 else 10,
                        unit = unit.trim().takeIf { type == CustomFieldType.NUMBER && it.isNotEmpty() },
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
