@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.photos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.api.Pose
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A picture ready to upload: the bytes (in memory only) and a small preview. */
class Picked(val bytes: ByteArray, val type: String, val preview: Bitmap?, val at: Long)

/**
 * Adding a photo: with the camera (with the last photo of the same album and pose as a see-through
 * guide) or from the gallery through the system's photo picker (no storage permission needed);
 * then its album, pose, note and tags. Nothing is saved to the phone.
 */
@Composable
fun PhotoAddPage(
    source: String,
    album: String?,
    photos: List<Photo>,
    albums: List<PhotoAlbum>,
    image: PhotoImage,
    onBack: () -> Unit,
    onUpload: (Picked, Photo) -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Picked?>(null) }
    var details by remember { mutableStateOf(Photo(album = album ?: albums.firstOrNull()?.name ?: "Progress", pose = Pose.FRONT, at = System.currentTimeMillis())) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri == null) onBack() else reading = uri }
    LaunchedEffect(reading) {
        val uri = reading ?: return@LaunchedEffect
        picked = runCatching { readPicked(context, uri) }.getOrElse { problem = it.message ?: "Couldn't read that picture"; null }
        reading = null
    }
    LaunchedEffect(source) {
        if (source == "gallery" && picked == null) gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val ready = picked
    when {
        ready != null -> UploadForm(ready, details, albums, onChange = { details = it }, onBack = { picked = null; if (source == "gallery") onBack() }) {
            onUpload(ready, details.copy(at = ready.at))
        }
        source == "camera" -> CameraPage(details, photos, albums, image, onDetails = { details = it }, onBack = onBack) { picked = it }
        else -> Column(Modifier.fillMaxSize()) {
            BackBar(onBack, "From your gallery")
            problem?.let { ErrorCard(it, Modifier.padding(16.dp)) } ?: Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

private suspend fun readPicked(context: Context, uri: Uri): Picked = withContext(Dispatchers.IO) {
    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > 20 * 1024 * 1024) throw IllegalArgumentException("Photos can be up to 20 MB")
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("Couldn't read that picture")
    val upload = if (type == "image/jpeg" || type == "image/png" || type == "image/webp") bytes to type else {
        // HEIC and others: turned into a JPEG here, in memory.
        val bitmap = PhotoMemory.decode(bytes, 3000) ?: throw IllegalArgumentException("That picture's format isn't one the app can read")
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray() to "image/jpeg"
    }
    Picked(upload.first, upload.second, PhotoMemory.decode(bytes, 1200), System.currentTimeMillis())
}

@Composable
private fun UploadForm(picked: Picked, details: Photo, albums: List<PhotoAlbum>, onChange: (Photo) -> Unit, onBack: () -> Unit, onUpload: () -> Unit) {
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "New photo") { TextButton(enabled = !sending, onClick = { sending = true; onUpload() }) { Text("Save") } }
        picked.preview?.let { bitmap ->
            Image(
                bitmap.asImageBitmap(), "The new photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)),
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhotoDetailsForm(details, albums, onChange)
            Hint("Your server removes its location and camera details, and keeps it encrypted.")
            Button(enabled = !sending, onClick = { sending = true; onUpload() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save to your photos")
            }
        }
    }
}

@Composable
private fun CameraPage(details: Photo, photos: List<Photo>, albums: List<PhotoAlbum>, image: PhotoImage, onDetails: (Photo) -> Unit, onBack: () -> Unit, onTaken: (Picked) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Take a photo")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (albums.map { it.name } + details.album).distinctBy { it.lowercase() }.take(6).forEach { a ->
                    FilterChip(selected = details.album.equals(a, true), onClick = { onDetails(details.copy(album = a)) }, label = { Text(a) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pose.entries.forEach { p -> FilterChip(selected = details.pose == p, onClick = { onDetails(details.copy(pose = p)) }, label = { Text(p.label) }) }
            }
            if (!granted) {
                InfoCard("The camera") {
                    Text(
                        "The camera opens here so the photo goes straight to your server, encrypted. It isn't saved to your phone's gallery.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (denied) Hint("The camera is off for this app; you can turn it on in the phone's settings, or add photos from your gallery instead.")
                    Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Allow the camera") }
                }
            } else {
                CameraView(PhotoPicks.ghost(photos, details.album, details.pose), image, onTaken)
            }
        }
    }
}

@Composable
private fun CameraView(ghost: Photo?, image: PhotoImage, onTaken: (Picked) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(false) }
    var opacity by remember { mutableFloatStateOf(0.35f) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                provider = future.get().also { p ->
                    p.unbindAll()
                    val use = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                    p.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, use, capture)
                }
            }.onFailure { problem = "The camera couldn't start" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp))) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        // The last photo of this album and pose, see-through, to line up with.
        if (ghost != null && opacity > 0.01f) image(ghost, false, Modifier.fillMaxSize().alpha(opacity))
    }
    problem?.let { ErrorCard(it) }
    if (ghost != null) {
        Text("Guide: your last ${ghost.pose.label.lowercase()} photo, ${(opacity * 100).toInt()}% see-through", style = MaterialTheme.typography.bodyMedium)
        Slider(opacity, { opacity = it }, valueRange = 0f..0.8f, modifier = Modifier.semantics { contentDescription = "How visible the guide photo is" })
    } else {
        Hint("Your next photo in this album and pose will show this one faintly, to line up with.")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = { front = !front }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Cameraswitch, if (front) "Use the back camera" else "Use the front camera") }
        FilledIconButton(
            enabled = !busy,
            onClick = {
                busy = true
                capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val picked = runCatching { upright(image) }.getOrNull()
                        image.close()
                        busy = false
                        if (picked != null) onTaken(picked) else problem = "That photo didn't work. Try again?"
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy = false
                        problem = "That photo didn't work. Try again?"
                    }
                })
            },
            shape = CircleShape, modifier = Modifier.size(76.dp),
        ) { Icon(Icons.Rounded.PhotoCamera, "Take the photo", Modifier.size(36.dp)) }
        Box(Modifier.size(56.dp))
    }
}

/** The captured JPEG turned the right way up and encoded again, all in memory. */
private fun upright(image: ImageProxy): Picked {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val decoded = PhotoMemory.decode(bytes, 3000) ?: throw IllegalStateException("Unreadable photo")
    val rotation = image.imageInfo.rotationDegrees
    val turned = if (rotation == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    val jpeg = ByteArrayOutputStream().also { turned.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    val preview = turned.scale(turned.width / 3, turned.height / 3)
    return Picked(jpeg, "image/jpeg", preview, System.currentTimeMillis())
}
