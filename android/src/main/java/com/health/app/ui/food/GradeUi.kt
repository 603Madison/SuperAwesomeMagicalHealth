package com.health.app.ui.food

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.api.FoodGrade
import com.health.app.ui.components.SectionTitle
import com.health.app.ui.theme.LocalChartColors

/*
 * Food grades (A to F, for the person's own goals) and their own foods, kept apart from the
 * add-food screen so it changes little. Grades only come from the server while they're on
 * (What you track › Food › Food grades), so nothing shows when they're off.
 */

/** A grade's background: the theme's own containers for A and B, neutral for C, a soft warm tone for D and F. */
@Composable
private fun gradeColors(letter: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val warm = LocalChartColors.current.c3
    return when (letter) {
        "A" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "B" -> scheme.primaryContainer to scheme.onPrimaryContainer
        "D" -> warm.copy(alpha = 0.26f).compositeOver(scheme.surfaceContainer) to scheme.onSurface
        "F" -> warm.copy(alpha = 0.42f).compositeOver(scheme.surfaceContainer) to scheme.onSurface
        else -> scheme.surfaceContainerHighest to scheme.onSurface
    }
}

/** A food's grade as a small letter; read out with its reasons. */
@Composable
fun GradeBadge(grade: FoodGrade?, modifier: Modifier = Modifier) {
    if (grade == null) return
    val (background, content) = gradeColors(grade.letter)
    val spoken = "Grade ${grade.letter}" + grade.reasons.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ").orEmpty()
    Box(
        modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(background).semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(grade.letter, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** The grade with its reasons beside it, for a food's dialog or a product. */
@Composable
fun GradeReasons(grade: FoodGrade?, modifier: Modifier = Modifier) {
    if (grade == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GradeBadge(grade)
        Column {
            Text(grade.reasons.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text("For your goals", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Your foods" with "Clear my foods", which asks first: the diary stays as it is. */
@Composable
fun MyFoodsTitle(onClear: (() -> Unit)?) {
    var asking by remember { mutableStateOf(false) }
    SectionTitle("Your foods", trailing = onClear?.let { { TextButton(onClick = { asking = true }) { Text("Clear my foods") } } })
    if (asking && onClear != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Clear my foods?") },
            text = { Text("Your diary stays as it is.") },
            confirmButton = { TextButton(onClick = { asking = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** Removes one of the person's foods from the list. */
@Composable
fun RemoveFoodButton(name: String, onRemove: () -> Unit) {
    IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove $name", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}
