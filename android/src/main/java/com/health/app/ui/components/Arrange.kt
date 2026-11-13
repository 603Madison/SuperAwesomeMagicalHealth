package com.health.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.Layouts
import com.health.api.ScreenLayout
import kotlinx.coroutines.launch

/*
 * Arranging a screen: the person moves its sections up and down and hides the ones they don't
 * want, from a sheet opened by the screen's Arrange button. Saved on the server (Container.layout).
 *
 * A screen lists its sections in the default order as [ArrangeItem]s (only those that apply now,
 * e.g. for features that are on), renders `arranged(key, items)` in that order, and puts an
 * [ArrangeButton] in its title bar that opens [ArrangeSheet]. Keys: [Layouts.TODAY], [Layouts.QUICK],
 * [Layouts.NAV], or `Layouts.screen(Layouts.ANDROID, "<screen>")` for the rest.
 */

/** A section a screen can show: a stable id (saved) and the name the sheet shows. */
data class ArrangeItem(val id: String, val label: String)

/** The person's saved arrangement of a screen, or null for the default. */
@Composable
fun screenLayout(key: String): ScreenLayout? {
    val prefs by appContainer().layout.collectAsState()
    return prefs.screens[key]
}

/** The ids of [items] to show, in the person's order, without those hidden. */
@Composable
fun arranged(key: String, items: List<ArrangeItem>): List<String> = Layouts.visible(items, { it.id }, screenLayout(key)).map { it.id }

/** [arranged] for things that aren't [ArrangeItem]s (cards, enum values…). */
@Composable
fun <T : Any> arrangedBy(key: String, items: List<T>, id: (T) -> String): List<T> = Layouts.visible(items, id, screenLayout(key))

/** The title-bar button that opens a screen's [ArrangeSheet]. Hidden while viewing someone's share. */
@Composable
fun ArrangeButton(onClick: () -> Unit) {
    if (com.health.app.ui.care.LocalReadOnly.current) return
    IconButton(onClick = onClick) { Icon(Icons.Rounded.SwapVert, "Arrange this screen") }
}

/**
 * The sheet: each section with up and down, and a switch to show or hide it; "Put back as it was"
 * resets the screen. Changes save as they're made. [hiddenMeans] says what hiding does here
 * ("Hidden" by default; for quick actions, "Under More").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrangeSheet(
    key: String,
    title: String,
    items: List<ArrangeItem>,
    onDismiss: () -> Unit,
    hiddenMeans: String = "Hidden",
) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val saved = screenLayout(key)
    val ordered = Layouts.arrange(items, { it.id }, saved)
    val hidden = saved?.hidden.orEmpty()
    var error by remember { mutableStateOf<String?>(null) }

    fun save(order: List<ArrangeItem>, hide: Set<String>) {
        scope.launch {
            error = null
            // Ids not offered now (a feature turned off) keep their place, so turning it back on restores it.
            val keepOthers = saved?.order.orEmpty().filter { id -> items.none { it.id == id } }
            runCatching { c.saveLayout(key, ScreenLayout(order.map { it.id } + keepOthers, hide)) }
                .onFailure { error = "Couldn't save that. Check the connection and try again." }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Arrange $title", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Move what matters to the top, and hide what you don't use.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 8.dp)) {
                itemsIndexed(ordered, key = { _, item -> item.id }) { i, item ->
                    val isHidden = item.id in hidden
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isHidden) Text(hiddenMeans, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { save(ordered.toMutableList().apply { add(i - 1, removeAt(i)) }, hidden) }, enabled = i > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Move ${item.label} up")
                        }
                        IconButton(onClick = { save(ordered.toMutableList().apply { add(i + 1, removeAt(i)) }, hidden) }, enabled = i < ordered.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Move ${item.label} down")
                        }
                        IconButton(onClick = { save(ordered, if (isHidden) hidden - item.id else hidden + item.id) }) {
                            Icon(
                                if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (isHidden) "Show ${item.label}" else "Hide ${item.label}",
                                tint = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (i < ordered.lastIndex) HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = {
                        scope.launch {
                            error = null
                            runCatching { c.saveLayout(key, ScreenLayout()) }.onFailure { error = "Couldn't save that. Check the connection and try again." }
                        }
                    },
                    enabled = saved != null,
                ) { Text("Put back as it was") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
