@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Stat
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.TitleAction
import com.health.app.ui.modules.IconTile
import com.health.app.ui.modules.waterShown
import com.health.app.ui.theme.AppTheme
import java.time.ZoneId
import kotlin.math.roundToInt

/** The drinks a tap logs straight away, at their usual size. */
val QUICK_DRINKS = listOf(DrinkKind.COFFEE, DrinkKind.TEA, DrinkKind.WATER, DrinkKind.BEER, DrinkKind.WINE)

/** [QUICK_DRINKS], without water while it isn't tracked (Fields.NUTRITION_WATER hidden). */
fun quickDrinks(water: Boolean): List<DrinkKind> = QUICK_DRINKS.filter { water || it != DrinkKind.WATER }

/** The kinds a drink can be: not water while it isn't tracked, unless [current] already is. */
fun drinkKinds(water: Boolean, current: DrinkKind? = null): List<DrinkKind> =
    DrinkKind.entries.filter { water || it != DrinkKind.WATER || it == current }

/** What the drinks card says before anything's logged. */
fun drinksIntro(water: Boolean): String =
    if (water) "Coffee, tea and the rest count towards your water; caffeine and alcohol are kept apart, to see how they go with your sleep."
    else "Caffeine and alcohol are kept apart, to see how they go with your sleep."

/** A drink of [kind] now, at its usual size, with its caffeine and alcohol filled in. */
fun quickDrink(kind: DrinkKind, at: Long = System.currentTimeMillis()): Drink {
    val (caffeine, alcohol) = drinkContents(kind, kind.defaultMl)
    return Drink(at, kind, kind.defaultMl, caffeineMg = caffeine, alcoholG = alcohol)
}

/** "1.5 drinks", "1 drink". */
fun standardDrinksText(n: Double): String = "${Format.compact(n)} ${if (Format.compact(n) == "1") "drink" else "drinks"}"

/**
 * Today's drinks: one-tap logging, caffeine and what's likely still in you at bedtime, and
 * standard drinks of alcohol. For Food and Today. With water not tracked ([water] false, from
 * Fields.NUTRITION_WATER), there's no water to log and nothing said about it.
 */
@Composable
fun DrinksCard(
    day: DrinksDay?,
    units: Units,
    zone: ZoneId,
    onQuick: (DrinkKind) -> Unit,
    onSave: (Drink) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    water: Boolean = waterShown(),
) {
    var editing by remember { mutableStateOf<Drink?>(null) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    InfoCard("Drinks", modifier, icon = Icons.Rounded.LocalCafe, iconTint = AppTheme.data.carbs, trailing = if (readOnly) null else ({
        TitleAction(Icons.Rounded.Add, "Add a drink") { editing = quickDrink(DrinkKind.COFFEE) }
    })) {
        if (!readOnly) {
            // Even rows: three and two with water, two and two without.
            val kinds = quickDrinks(water)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (kinds.size == 4) 2 else 3,
            ) {
                kinds.forEach { kind ->
                    FilledTonalButton(onClick = { onQuick(kind) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), modifier = Modifier.weight(1f)) {
                        Icon(drinkIcon(kind), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(kind.label, maxLines = 1)
                    }
                }
            }
        }
        val d = day ?: DrinksDay("")
        if (d.drinks.isNotEmpty() || d.caffeineMg > 0 || d.alcoholG > 0) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("${d.caffeineMg.roundToInt()}", "mg caffeine", Modifier.weight(1f), color = AppTheme.data.carbs)
                Stat(d.caffeineAtBedtimeMg?.let { "${it.roundToInt()}" } ?: "—", "mg at bedtime", Modifier.weight(1f))
                Stat(
                    Format.compact(d.standardDrinks), if (Format.compact(d.standardDrinks) == "1") "drink" else "drinks", Modifier.weight(1f),
                    color = if (d.standardDrinks > 0) AppTheme.data.protein else MaterialTheme.colorScheme.onSurface,
                )
            }
            d.caffeineAtBedtimeMg?.takeIf { it >= 50 }?.let {
                Hint("About ${it.roundToInt()} mg of caffeine will likely still be with you at bedtime, which can make sleep lighter.", Modifier.padding(top = 6.dp))
            }
            if (water && d.fluidMl > 0) Hint("${Format.water(d.fluidMl, units)} of these count towards your water.", Modifier.padding(top = 4.dp))
        } else {
            if (!readOnly) Spacer(Modifier.height(8.dp))
            Hint(drinksIntro(water))
        }
        if (!compact) {
            d.drinks.sortedByDescending { it.at }.forEachIndexed { i, drink ->
                if (i == 0) Spacer(Modifier.height(8.dp))
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !readOnly) { editing = drink }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(drinkIcon(drink.kind), size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(drink.name ?: drink.kind.label, style = MaterialTheme.typography.bodyLarge)
                        Text(drinkDetail(drink, units), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Format.timeOfDay(drink.at, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    editing?.let { drink ->
        DrinkDialog(drink, units, onDismiss = { editing = null }, onSave = { onSave(it); editing = null }, onDelete = drink.id?.let { id -> { onDelete(id); editing = null } }, water = water)
    }
}

/** "240 ml · 96 mg caffeine", "150 ml · 1.3 drinks". */
fun drinkDetail(d: Drink, units: Units): String = listOfNotNull(
    Format.water(d.volumeMl, units),
    d.caffeineMg.takeIf { it > 0 }?.let { "${it.roundToInt()} mg caffeine" },
    d.alcoholG.takeIf { it > 0 }?.let { standardDrinksText(it / 14.0) },
).joinToString(" · ")

/** A drink: its kind (which fills in the rest), size, caffeine and alcohol, and a name. */
@Composable
fun DrinkDialog(initial: Drink, units: Units, onDismiss: () -> Unit, onSave: (Drink) -> Unit, onDelete: (() -> Unit)? = null, water: Boolean = true) {
    var kind by remember { mutableStateOf(initial.kind) }
    var ml by remember { mutableStateOf(initial.volumeMl.toString()) }
    var caffeine by remember { mutableStateOf(Format.compact(initial.caffeineMg)) }
    var alcohol by remember { mutableStateOf(Format.compact(initial.alcoholG)) }
    var name by remember { mutableStateOf(initial.name.orEmpty()) }
    fun refill(k: DrinkKind, size: Int) {
        val (c, a) = drinkContents(k, size)
        caffeine = Format.compact(c)
        alcohol = Format.compact(a)
    }
    val volume = ml.toIntOrNull()?.takeIf { it in 1..3000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Add a drink" else "Drink") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    drinkKinds(water, initial.kind).forEach { k ->
                        FilterChip(kind == k, { kind = k; ml = k.defaultMl.toString(); refill(k, k.defaultMl) }, label = { Text(k.label) })
                    }
                }
                NumberField(ml, { ml = it; it.toIntOrNull()?.let { size -> refill(kind, size) } }, "How much", suffix = "ml", decimals = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(caffeine, { caffeine = it }, "Caffeine", Modifier.weight(1f), suffix = "mg")
                    NumberField(alcohol, { alcohol = it }, "Alcohol", Modifier.weight(1f), suffix = "g")
                }
                Hint("Filled in from the kind and size; change them if you know better. A standard drink is 14 g of alcohol.")
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (units == Units.IMPERIAL && volume != null) Hint("About ${Format.water(volume, units)}.")
            }
        },
        confirmButton = {
            TextButton(enabled = volume != null, onClick = {
                onSave(
                    initial.copy(
                        kind = kind, volumeMl = volume!!, caffeineMg = Format.parse(caffeine)?.coerceIn(0.0, 2000.0) ?: 0.0,
                        alcoholG = Format.parse(alcohol)?.coerceIn(0.0, 500.0) ?: 0.0, name = name.trim().ifBlank { null },
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
