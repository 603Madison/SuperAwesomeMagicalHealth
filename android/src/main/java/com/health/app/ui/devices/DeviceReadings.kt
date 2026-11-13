package com.health.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.app.devices.colmi.Answer
import com.health.app.devices.colmi.ReadingStatus
import com.health.app.devices.colmi.RingReading
import com.health.app.devices.colmi.RingReadingsState
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.StatusDot
import com.health.app.ui.components.ValueRow
import com.health.app.ui.theme.AppTheme

/** How a ring's reading is doing, in words, for the Devices page. */
object RingReadingText {
    enum class Tone { COMING_IN, WAITING, NOT_COMING }

    fun of(reading: RingReading, status: ReadingStatus?, now: Long): Pair<String, Tone> = when {
        status == null -> "At the next sync" to Tone.WAITING
        !status.offered -> "Not on this ring" to Tone.NOT_COMING
        status.latest != null -> (if (reading == RingReading.SLEEP) "Night ended ${Format.ago(status.latest, now)}" else "Latest ${Format.ago(status.latest, now)}") to Tone.COMING_IN
        status.answer == Answer.REFUSED -> "This ring doesn't measure it" to Tone.NOT_COMING
        status.answer == Answer.SILENT -> "No answer from the ring yet" to Tone.NOT_COMING
        else -> "Waiting for its first reading" to Tone.WAITING
    }
}

/** Each reading the ring can bring in, and when it last did ([readings] null before the ring's first sync). */
@Composable
fun RingReadingsList(readings: RingReadingsState?, now: Long, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("What it brings in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        RingReading.entries.forEach { reading ->
            val (text, tone) = RingReadingText.of(reading, readings?.readings?.get(reading), now)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(toneColor(tone), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(reading.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            }
        }
    }
}

@Composable
private fun toneColor(tone: RingReadingText.Tone): Color = when (tone) {
    RingReadingText.Tone.COMING_IN -> AppTheme.status.good
    RingReadingText.Tone.WAITING -> AppTheme.status.info
    RingReadingText.Tone.NOT_COMING -> AppTheme.status.idle
}

/**
 * Each reading more than one device measures, and sleep: combined (the app's own number), or one
 * device. Read-only while viewing someone's share.
 */
@Composable
fun OverlapsCard(overlaps: List<Overlap>, choose: (Overlap, String?) -> Unit) {
    val readOnly = LocalReadOnly.current
    InfoCard(null) {
        Hint("Where more than one device measures something, the app works out its own number from them. You can use just one instead.")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            overlaps.forEach { OverlapRow(it, readOnly, choose) }
        }
    }
}

@Composable
private fun OverlapRow(overlap: Overlap, readOnly: Boolean, choose: (Overlap, String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ValueRow(
            overlap.label,
            overlap.current,
            subtitle = overlap.devices.joinToString(" · ") { it.name },
            onClick = if (readOnly) null else ({ menu = true }),
        )
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            val check: @Composable () -> Unit = { Icon(Icons.Rounded.Check, "Chosen") }
            DropdownMenuItem(
                text = { Text("Combine them") },
                onClick = { menu = false; if (overlap.chosen != null) choose(overlap, null) },
                trailingIcon = if (overlap.chosen == null) check else null,
            )
            overlap.devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("Use only ${device.name} for ${overlap.reading}") },
                    onClick = { menu = false; if (overlap.chosen != device.id) choose(overlap, device.id) },
                    trailingIcon = if (overlap.chosen == device.id) check else null,
                )
            }
        }
    }
}
