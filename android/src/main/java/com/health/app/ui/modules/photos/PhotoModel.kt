package com.health.app.ui.modules.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.api.Pose
import com.health.app.Container
import com.health.app.net.ServerApi
import com.health.app.net.photoAlbums
import com.health.app.net.photoImage
import com.health.app.net.photoThumb
import com.health.app.net.photos
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Progress photos (Module.PHOTOS): encrypted on the server, fetched with the app's sign-in, and
 * kept only in memory here, never written to the phone's storage.
 */

data class PhotosState(
    val photos: List<Photo> = emptyList(),
    val albums: List<PhotoAlbum> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class PhotosViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(PhotosState())
    val state: StateFlow<PhotosState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            try {
                val photos = async { c.ui.photos() }
                val albums = async { c.ui.photoAlbums() }
                _state.update { it.copy(photos = photos.await().sortedByDescending { p -> p.at }, albums = albums.await(), loading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your photos") }
            }
        }
    }

    /** Saves on the app's scope, so it finishes even though the page closes straight after. */
    fun act(message: String?, block: suspend (ServerApi) -> Unit) {
        c.scope.launch {
            try {
                block(c.ui)
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't save")
            }
        }
    }
}

/** Which photo to line up with: the latest in the same album and pose. */
object PhotoPicks {
    fun ghost(photos: List<Photo>, album: String, pose: Pose): Photo? =
        photos.filter { it.album.equals(album, ignoreCase = true) && it.pose == pose }.maxByOrNull { it.at }

    /** The first and latest of an album (and pose, if given), to compare by default. */
    fun defaultPair(photos: List<Photo>, pose: Pose? = null): Pair<Photo, Photo>? {
        val list = photos.filter { pose == null || it.pose == pose }.sortedBy { it.at }
        return if (list.size >= 2) list.first() to list.last() else null
    }

    /** "2 kg less" and "4 months apart", between two photos. */
    fun between(before: Photo, after: Photo, weight: (Double) -> String): List<String> = listOfNotNull(
        before.weightKg?.let { b -> after.weightKg?.let { a -> (a - b).takeIf { kotlin.math.abs(it) >= 0.1 }?.let { d -> "${weight(kotlin.math.abs(d))} ${if (d < 0) "less" else "more"}" } } },
        daysApart(before, after)?.let { "$it apart" },
    )

    private fun daysApart(a: Photo, b: Photo): String? {
        val days = kotlin.math.abs(b.at - a.at) / 86_400_000
        return when {
            days < 1 -> null
            days < 14 -> "$days ${if (days == 1L) "day" else "days"}"
            days < 60 -> "${days / 7} weeks"
            days < 730 -> "${days / 30} months"
            else -> "${days / 365} years"
        }
    }
}

/**
 * Pictures in memory only: thumbnails and full pictures (scaled to the screen), dropped when
 * memory's needed or the person signs out. Keyed by whose they are, so viewing a share never mixes.
 */
object PhotoMemory {
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val full = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String, thumb: Boolean): Bitmap? = (if (thumb) thumbs else full).get(key)

    fun put(key: String, thumb: Boolean, bitmap: Bitmap) {
        (if (thumb) thumbs else full).put(key, bitmap)
    }

    fun clear() {
        thumbs.evictAll()
        full.evictAll()
    }

    /** Decodes [bytes], scaled down so the long side is at most [maxSide]. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** A photo's picture (or its thumbnail), fetched with the app's sign-in and kept in memory. */
@Composable
fun rememberPhoto(id: String?, thumb: Boolean): ImageBitmap? {
    val c = appContainer()
    val viewing by c.viewing.collectAsState()
    val key = "${viewing?.ownerId ?: "me"}:$id"
    var image by remember(key, thumb) { mutableStateOf(PhotoMemory.get(key, thumb)?.asImageBitmap()) }
    LaunchedEffect(key, thumb) {
        if (image != null || id == null) return@LaunchedEffect
        val bitmap = runCatching {
            val bytes = if (thumb) c.ui.photoThumb(id) else c.ui.photoImage(id)
            withContext(Dispatchers.Default) { PhotoMemory.decode(bytes, if (thumb) 400 else 1600) }
        }.getOrNull()
        if (bitmap != null) {
            PhotoMemory.put(key, thumb, bitmap)
            image = bitmap.asImageBitmap()
        }
    }
    return image
}
