@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.neuro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.health.api.CommunicationCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard

/** A card's colours: dark with white, or amber with near-black, each well past 7:1 contrast. */
data class CardColors(val background: Color, val text: Color, val label: String)

val CARD_COLORS: Map<String, CardColors> = linkedMapOf(
    "slate" to CardColors(Color(0xFF111827), Color.White, "Dark"),
    "violet" to CardColors(Color(0xFF2E1A6B), Color.White, "Violet"),
    "ocean" to CardColors(Color(0xFF0B3D6E), Color.White, "Blue"),
    "green" to CardColors(Color(0xFF0E4D33), Color.White, "Green"),
    "amber" to CardColors(Color(0xFFFFC94D), Color(0xFF1A1200), "Amber"),
    "white" to CardColors(Color.White, Color(0xFF111827), "White"),
)

fun cardColors(name: String?): CardColors = CARD_COLORS[name] ?: CARD_COLORS.getValue("slate")

/** The cards: tap one to show it full screen; edit them into your own. */
@Composable
fun CardsScreen(
    cards: List<CommunicationCard>,
    defaults: Boolean,
    readOnly: Boolean,
    initialShow: String?,
    onBack: () -> Unit,
    onSave: (List<CommunicationCard>) -> Unit,
) {
    var showing by remember(initialShow, cards) { mutableStateOf(cards.indexOfFirst { it.id == initialShow }.takeIf { it >= 0 }) }
    var editing by remember { mutableStateOf(false) }
    var list by remember(cards) { mutableStateOf(cards) }
    var editingCard by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Communication cards") {
            if (!readOnly) {
                if (editing) TextButton(onClick = { onSave(list); editing = false }) { Text("Save") }
                else TextButton(onClick = { editing = true }) { Text("Edit") }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(
                if (editing) "Change the words, add your own, or take away any you won't use." + (if (defaults) " These are starting suggestions until you save." else "")
                else "Tap a card to show it full screen, then turn your phone round so the other person can read it.",
                Modifier.padding(horizontal = 4.dp),
            )
            list.forEachIndexed { i, card ->
                val colors = cardColors(card.color)
                Surface(
                    color = colors.background, contentColor = colors.text, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(20.dp))
                        .clickable { if (editing) editingCard = i else showing = i }
                        .semantics { contentDescription = (if (editing) "Edit card: " else "Show card: ") + card.text },
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.text, style = MaterialTheme.typography.titleLarge)
                            card.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        if (editing) {
                            Icon(Icons.Rounded.Edit, null)
                            IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Rounded.Delete, "Remove card", tint = colors.text) }
                        }
                    }
                }
            }
            if (editing) {
                OutlinedButton(onClick = { list = list + CommunicationCard(text = "", color = "slate"); editingCard = list.lastIndex }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Add a card")
                }
            }
        }
    }
    showing?.let { start -> FullScreenCards(list, start) { showing = null } }
    editingCard?.let { i ->
        list.getOrNull(i)?.let { card ->
            CardEditor(card, onDismiss = {
                if (card.text.isBlank()) list = list.filterIndexed { j, _ -> j != i }
                editingCard = null
            }) { changed ->
                list = list.toMutableList().also { it[i] = changed }
                editingCard = null
            }
        }
    }
}

@Composable
private fun CardEditor(card: CommunicationCard, onDismiss: () -> Unit, onSave: (CommunicationCard) -> Unit) {
    var text by remember { mutableStateOf(card.text) }
    var detail by remember { mutableStateOf(card.detail.orEmpty()) }
    var color by remember { mutableStateOf(card.color ?: "slate") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(300) }, label = { Text("What it says") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(detail, { detail = it.take(500) }, label = { Text("Smaller line (optional)") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CARD_COLORS.forEach { (name, c) ->
                        FilterChip(
                            selected = color == name, onClick = { color = name }, label = { Text(c.label) },
                            leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(c.background)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(card.copy(text = text.trim(), detail = detail.trim().ifEmpty { null }, color = color)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A card filling the screen, high contrast and very large, with big buttons to move between cards or close. */
@Composable
fun FullScreenCards(cards: List<CommunicationCard>, start: Int, onClose: () -> Unit) {
    if (cards.isEmpty()) return
    var index by remember { mutableStateOf(start.coerceIn(0, cards.lastIndex)) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CardFace(cards[index], index, cards.size, onPrevious = { index = (index - 1 + cards.size) % cards.size }, onNext = { index = (index + 1) % cards.size }, onClose = onClose)
    }
}

/** One card's face (also drawn on its own for screenshots). */
@Composable
fun CardFace(card: CommunicationCard, index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val colors = cardColors(card.color)
    Box(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 96.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(card.text, color = colors.text, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, style = MaterialTheme.typography.displaySmall)
            card.detail?.let { Text(it, color = colors.text, fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp)) }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(64.dp)) {
            Icon(Icons.Rounded.Close, "Close", tint = colors.text, modifier = Modifier.size(36.dp))
        }
        if (count > 1) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous card", tint = colors.text, modifier = Modifier.size(40.dp)) }
                Text("${index + 1} of $count", color = colors.text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next card", tint = colors.text, modifier = Modifier.size(40.dp)) }
            }
        }
    }
}
