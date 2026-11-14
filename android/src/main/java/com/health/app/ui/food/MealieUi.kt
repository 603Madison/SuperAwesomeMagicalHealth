package com.health.app.ui.food

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.MealiePlan
import com.health.api.MealieRecipe
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Pill
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.today.InsightLinks

/**
 * The day's meal plan from Mealie: each planned thing with its meal, added to that meal with one
 * tap (one serving), or a check once it's logged. A recipe without nutrition, and a note, have no
 * button. Without [numbers] (eating disorder recovery), no calories. When Mealie couldn't be
 * asked, one quiet line says why.
 */
@Composable
fun MealPlanCard(plan: MealiePlan, numbers: Boolean, onAdd: (Int) -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard("Meal plan", icon = Icons.AutoMirrored.Rounded.MenuBook) {
        val problem = plan.problem
        if (problem != null) {
            Hint(problem)
        } else {
            MealieText.rows(plan, numbers).forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    when (row.action) {
                        PlanAction.ADD -> if (!readOnly) {
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { onAdd(row.index) }, contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
                                modifier = Modifier.semantics { contentDescription = "Add ${row.title} to ${InsightLinks.mealWord(row.meal)}" },
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add")
                            }
                        }
                        PlanAction.LOGGED -> {
                            Spacer(Modifier.width(8.dp))
                            Pill("Logged", AppTheme.status.good, icon = Icons.Rounded.Check)
                        }
                        PlanAction.NONE -> Unit
                    }
                }
            }
        }
    }
}

/** A recipe in Mealie without nutrition: quiet, nothing to add, and a way to open it there when there's a [MealieRecipe.link]. */
@Composable
fun NoNutritionRow(recipe: MealieRecipe, onOpen: ((String) -> Unit)? = null) {
    val link = recipe.link?.takeIf { onOpen != null }
    Row(
        // With the button, the row is as tall as a food's.
        Modifier.fillMaxWidth().padding(start = 16.dp, end = if (link != null) 4.dp else 16.dp, top = if (link != null) 6.dp else 10.dp, bottom = if (link != null) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(MealieText.NO_NUTRITION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (link != null && onOpen != null) {
            IconButton(onClick = { onOpen(link) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open ${recipe.name} in Mealie", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
