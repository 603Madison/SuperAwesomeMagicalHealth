@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.api.Pose
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.EmptyState
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.modules.ChipPicker
import java.time.Instant
import java.time.ZoneId

/** How a photo is drawn: from the server (in the app), or a stand-in (in screenshots). */
typealias PhotoImage = @Composable (photo: Photo, thumb: Boolean, modifier: Modifier) -> Unit

/** A photo from the server, kept in memory. */
val NetworkPhoto: PhotoImage = { photo, thumb, modifier ->
    val image = rememberPhoto(photo.id, thumb)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (image != null) {
            Image(
                image, "${photo.pose.label} photo, ${photo.date ?: ""}",
                contentScale = if (thumb) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

class PhotosActions(
    val back: () -> Unit = {},
    val open: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

/** Albums, then each album's photos as a grid of thumbnails. */
@Composable
fun PhotosHome(state: PhotosState, album: String?, onAlbum: (String?) -> Unit, image: PhotoImage, actions: PhotosActions) {
    val readOnly = LocalReadOnly.current
    val shown = state.photos.filter { album == null || it.album.equals(album, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        BackBar(actions.back, "Progress photos")
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Hint("Encrypted on your server, never shown to AI, and only seen by people you share photos with.")
                    }
                    if (!readOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { actions.open("photos/add?id=camera" + (album?.let { "&album=$it" } ?: "")) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Take one")
                            }
                            FilledTonalButton(onClick = { actions.open("photos/add?id=gallery" + (album?.let { "&album=$it" } ?: "")) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("From gallery")
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = album == null, onClick = { onAlbum(null) }, label = { Text("All") })
                            state.albums.forEach { a -> FilterChip(selected = album.equals(a.name, true), onClick = { onAlbum(a.name) }, label = { Text("${a.name} · ${a.count}") }) }
                        }
                    }
                    if (shown.size >= 2) {
                        OutlinedButton(onClick = { actions.open("photos/compare" + (album?.let { "?album=$it" } ?: "")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Compare, null); Spacer(Modifier.width(8.dp)); Text("Compare two dates")
                        }
                    }
                    when {
                        state.loading && state.photos.isEmpty() -> Loading()
                        state.error != null && state.photos.isEmpty() -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                        shown.isEmpty() -> EmptyState(
                            Icons.Rounded.PhotoLibrary, "No photos yet",
                            "Photos taken in the same pose and light, every few weeks, show change that's hard to see day to day. " +
                                "Use them for whatever you're working on: transition, weight, skin, strength.",
                        )
                    }
                }
            }
            items(shown, key = { it.id ?: it.at.toString() }) { p ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { actions.open("photos/photo?id=${p.id}") }) {
                    image(p, true, Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(12.dp)))
                    Text(CareFormat.date(p.date) ?: "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(listOf(p.pose.label, p.album).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A photo, large, with what the server knows about that day; its details can be changed or it can be deleted. */
@Composable
fun PhotoDetail(photo: Photo, albums: List<PhotoAlbum>, units: Units, zone: ZoneId, image: PhotoImage, onBack: () -> Unit, onSave: (Photo) -> Unit, onDelete: (String) -> Unit, onCompare: () -> Unit) {
    val readOnly = LocalReadOnly.current
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var p by remember(photo) { mutableStateOf(photo) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, CareFormat.date(photo.date)) {
            if (!readOnly) TextButton(onClick = { if (editing) { onSave(p); editing = false } else editing = true }) { Text(if (editing) "Save" else "Edit") }
        }
        image(photo, false, Modifier.fillMaxWidth().aspectRatio((photo.width ?: 3).toFloat() / (photo.height ?: 4).coerceAtLeast(1)).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(null) {
                Text(CareFormat.dayTime(photo.at, zone), style = MaterialTheme.typography.titleMedium)
                Hint(listOfNotNull(photo.album, photo.pose.label, photo.weightKg?.let { Format.weight(it, units) }, photo.onHormones?.let { "$it on hormones" }).joinToString(" · "))
                photo.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                if (photo.tags.isNotEmpty()) Hint(photo.tags.joinToString(" · ") { "#$it" })
            }
            if (editing) PhotoDetailsForm(p, albums) { p = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCompare, modifier = Modifier.weight(1f)) { Text("Compare") }
                if (!readOnly) OutlinedButton(onClick = { deleting = true }, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this photo?") },
            text = { Text("It's removed from your server for good.") },
            confirmButton = { TextButton(onClick = { deleting = false; photo.id?.let(onDelete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep it") } },
        )
    }
}

/** Album, pose, note and tags. */
@Composable
fun PhotoDetailsForm(p: Photo, albums: List<PhotoAlbum>, onChange: (Photo) -> Unit) {
    InfoCard("Details") {
        OutlinedTextField(p.album, { onChange(p.copy(album = it.take(60))) }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (albums.map { it.name } + listOf("Transition", "Weight", "Skin", "Strength")).distinctBy { it.lowercase() }.take(8).forEach { a ->
                FilterChip(selected = p.album.equals(a, true), onClick = { onChange(p.copy(album = a)) }, label = { Text(a) })
            }
        }
        Text("Pose", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pose.entries.forEach { pose -> FilterChip(selected = p.pose == pose, onClick = { onChange(p.copy(pose = pose)) }, label = { Text(pose.label) }) }
        }
        OutlinedTextField(p.note.orEmpty(), { onChange(p.copy(note = it.take(2000).ifEmpty { null })) }, label = { Text("Note") }, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text("Tags", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        ChipPicker(listOf("morning", "evening", "after workout", "same light"), p.tags.toSet(), { onChange(p.copy(tags = it.toList())) }, addLabel = "A tag")
    }
}

/** Two dates side by side, or one over the other with a slider between, with each day's weight and time on hormones. */
@Composable
fun PhotoCompare(photos: List<Photo>, units: Units, zone: ZoneId, image: PhotoImage, initialBefore: String?, initialAfter: String?, onBack: () -> Unit, initialSlider: Boolean = false) {
    val sorted = photos.sortedBy { it.at }
    var before by remember { mutableStateOf(sorted.firstOrNull { it.id == initialBefore } ?: sorted.firstOrNull()) }
    var after by remember { mutableStateOf(sorted.firstOrNull { it.id == initialAfter } ?: sorted.lastOrNull()) }
    var slider by remember { mutableStateOf(initialSlider) }
    var split by remember { mutableFloatStateOf(0.5f) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Compare")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(listOf(false, true), slider, { if (it) "Slider" else "Side by side" }, { slider = it })
            val b = before
            val a = after
            if (b == null || a == null) {
                Hint("Take at least two photos to compare.")
                return@Column
            }
            if (slider) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp))) {
                    val full = maxWidth
                    image(b, false, Modifier.fillMaxSize())
                    // The later photo, shown up to the divider (unbounded so it isn't squeezed, then clipped).
                    Box(Modifier.fillMaxHeight().width(full * split).clipToBounds()) {
                        image(a, false, Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(full).fillMaxHeight())
                    }
                    Box(Modifier.fillMaxHeight().width(3.dp).offset { androidx.compose.ui.unit.IntOffset((full * split - 1.5.dp).roundToPx(), 0) }.background(MaterialTheme.colorScheme.surface))
                }
                Slider(split, { split = it }, modifier = Modifier.semantics { contentDescription = "How much of the later photo shows" })
                Row {
                    Hint("← ${CareFormat.date(a.date)}", Modifier.weight(1f))
                    Hint("${CareFormat.date(b.date)} →")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(b, a).forEach { p ->
                        Column(Modifier.weight(1f)) {
                            image(p, false, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Before" to b, "After" to a).forEach { (label, p) ->
                    InfoCard(label, Modifier.weight(1f)) {
                        Text(CareFormat.date(p.date) ?: "", style = MaterialTheme.typography.titleSmall)
                        p.weightKg?.let { Hint(Format.weight(it, units)) }
                        p.onHormones?.let { Hint("$it on hormones") }
                        Hint(p.pose.label)
                    }
                }
            }
            PhotoPicks.between(b, a) { Format.weight(it, units) }.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyLarge) }
            PickRow("Before", sorted, b, zone, image) { before = it }
            PickRow("After", sorted, a, zone, image) { after = it }
        }
    }
}

@Composable
private fun PickRow(label: String, photos: List<Photo>, chosen: Photo, zone: ZoneId, image: PhotoImage, onPick: (Photo) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(photos, key = { it.id ?: it.at.toString() }) { p ->
            val on = p.id == chosen.id
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(p) }
                    .then(if (on) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier).padding(3.dp)
                    .semantics { contentDescription = "$label: ${CareFormat.date(Instant.ofEpochMilli(p.at).atZone(zone).toLocalDate())}" + if (on) ", chosen" else "" },
            ) {
                image(p, true, Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(8.dp)))
                Text(CareFormat.date(p.date)?.substringBeforeLast(' ') ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
