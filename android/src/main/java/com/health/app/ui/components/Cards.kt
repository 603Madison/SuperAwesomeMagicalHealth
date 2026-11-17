package com.health.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The redesign's card language, shared by the tabs (Today, Body, the + sheet, You): a big title,
 * a heading above each card, rows inside white rounded cards with thin dividers, tinted icon
 * tiles, and pill buttons. Theme tokens only, so every palette, light and dark, and Bold text work.
 */

/** A tab's big title ("Body", "You", Today's greeting). */
val titleStyle: TextStyle
    @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)

/** A tab's big title, read out as a heading. */
@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = titleStyle, color = MaterialTheme.colorScheme.onBackground, modifier = modifier.semantics { heading() })
}

/** A section's heading above its card ("Right now", "Your day"), with something at the end ([trailing]: a link). */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** A card's own heading, quieter, inside it ("Last night", "Heart rate"), with a short note at the end. */
@Composable
fun CardHeading(text: String, modifier: Modifier = Modifier, note: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (note != null) {
            Text(
                note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/** The thin line between rows in a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    HorizontalDivider(modifier.padding(horizontal = inset), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** An icon on a tinted tile: 12 dp corners, or round. */
@Composable
fun Tile(icon: ImageVector, container: Color, content: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, round: Boolean = false) {
    Box(
        modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(12.dp)).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = content, modifier = Modifier.size(size / 2)) }
}

/** A pill button in a row's colours (40 dp tall, a 48 dp touch target), read out as [spoken]. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, container: Color, content: Color, modifier: Modifier = Modifier, spoken: String = text, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = spoken },
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

/**
 * A row that opens a page: its title, a short line under it, and a chevron; all of it one tap,
 * at least 56 dp tall. [leading] goes before the words (an icon tile).
 */
@Composable
fun LinkRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    quietColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = quietColor, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = quietColor)
    }
}

/**
 * A card in Body's language: its quiet heading ([title], with a short [note] at the end, or
 * [trailing]), then [content]. 18 dp in from the top, 16 dp from the sides.
 */
@Composable
fun TitledCard(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier, padding = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (trailing != null) 8.dp else 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CardHeading(title, note = note) }
                    trailing()
                }
            } else {
                CardHeading(title, note = note)
            }
            content()
        }
    }
}

/** A big figure and what it is: "54" over "resting", or "7h 42m" alone. */
@Composable
fun BigFigure(value: String, modifier: Modifier = Modifier, caption: String? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
