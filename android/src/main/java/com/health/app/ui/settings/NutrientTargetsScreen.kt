package com.health.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.MicroAim
import com.health.api.NutrientTargetSetting
import com.health.api.NutrientTargets
import com.health.app.attempt
import com.health.app.net.nutrientTargets
import com.health.app.net.saveNutrientTargets
import com.health.app.ui.Format
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.ValueRow
import com.health.app.ui.components.appContainer
import com.health.app.ui.food.MicroText
import com.health.app.ui.food.NutrientGroup
import com.health.app.ui.log.NumberField
import kotlinx.coroutines.launch

/** Settings › Goals › Vitamins & minerals: the targets, loaded from the server and saved there. */
@Composable
fun NutrientTargetsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var targets by remember { mutableStateOf<NutrientTargets?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(tries) {
        attempt { c.api.nutrientTargets() }.onSuccess { targets = it; error = null }.onFailure { error = it.message ?: "Couldn't load your targets" }
    }
    NutrientTargetsScreen(
        targets, error, onBack, onRetry = { tries++ },
        onSave = { own ->
            scope.launch {
                attempt { c.api.saveNutrientTargets(own) }
                    .onSuccess { targets = it; c.tell("Saved"); c.dataChanged() }
                    .onFailure { c.tell(it.message ?: "Couldn't save that") }
            }
        },
    )
}

/**
 * Each nutrient's daily target: the reference intake for the person (female or male values, by
 * the formula they chose for calories) with its upper limit, or their own. Tap one to set your
 * own, or to go back to the reference.
 */
@Composable
fun NutrientTargetsScreen(targets: NutrientTargets?, error: String?, onBack: () -> Unit, onRetry: () -> Unit = {}, onSave: (Map<String, Double>) -> Unit = {}) {
    var editing by remember { mutableStateOf<NutrientTargetSetting?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Vitamins & minerals")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                targets == null && error != null -> ErrorCard(error, actionLabel = "Try again", onAction = onRetry)
                targets == null -> Loading()
                else -> {
                    Hint("${targets.basis}. Female or male values follow the formula you picked for calorie estimates. Tap one to set your own.")
                    val byKey = targets.items.associateBy { it.key }
                    NutrientGroup.entries.forEach { group ->
                        SectionCard(group.label) {
                            MicroText.ALL.filter { it.group == group }.forEach { info ->
                                val item = byKey[info.key] ?: return@forEach
                                val own = item.own
                                val value = when {
                                    own != null -> "Yours: ${MicroText.withUnit(own, info.unit)}"
                                    item.reference != null -> MicroText.withUnit(item.reference!!, info.unit)
                                    else -> "None"
                                }
                                ValueRow(info.label, value, subtitle = MicroText.referenceText(item), onClick = { editing = item })
                            }
                        }
                    }
                    Hint("From the ${targets.source}.")
                }
            }
        }
    }
    val own = targets?.items?.mapNotNull { i -> i.own?.let { i.key to it } }?.toMap().orEmpty()
    editing?.let { item ->
        TargetDialog(item, onDismiss = { editing = null }) { v ->
            onSave(if (v == null) own - item.key else own + (item.key to v))
            editing = null
        }
    }
}

/** One nutrient's own target: a number, or Reset (back to the reference intake). */
@Composable
private fun TargetDialog(item: NutrientTargetSetting, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    val info = MicroText.info(item.key)
    var text by remember { mutableStateOf(item.own?.let { Format.upTo2(it) }.orEmpty()) }
    val value = Format.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(text, { text = it }, if (info.aim == MicroAim.AT_MOST) "Your limit" else "Your target", suffix = info.unit)
                Hint(MicroText.referenceText(item))
            }
        },
        confirmButton = { TextButton(enabled = value != null && value > 0, onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (item.own != null) TextButton(onClick = { onSave(null) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
