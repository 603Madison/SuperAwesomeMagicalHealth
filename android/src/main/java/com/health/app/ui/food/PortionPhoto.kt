package com.health.app.ui.food

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.health.api.Food
import com.health.api.PortionEstimate
import com.health.api.PortionFood
import com.health.app.attempt
import com.health.app.ui.components.Hint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Asks the server how many servings the photos show (AddFoodActions.portion). */
typealias PortionAsk = suspend (photo: ByteArray, pack: ByteArray?, food: PortionFood, note: String) -> PortionEstimate

/** "How much did I have?": what goes with the photo, and how the answer reads. */
object PortionText {
    const val BUTTON = "How much did I have?"
    const val NOTICE = "The photos go to Anthropic to estimate this, and aren't kept."
    const val COULD_NOT = "Couldn't tell from that one; try another angle, or set the servings yourself."

    /** Only the food's words, never its numbers; a recipe's yield from Mealie when the food doesn't say. */
    fun food(food: Food, makes: Double?): PortionFood = PortionFood.of(food).let { if (it.recipeServings == null && makes != null) it.copy(recipeServings = makes) else it }

    /** Servings as the field shows them: up to two places, "1.5", "0.33". */
    fun text(v: Double): String = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** The servings to prefill, or null when it couldn't tell. */
    fun servings(e: PortionEstimate): Double? = e.servings.takeIf { !e.refused && it.isFinite() && it > 0 }?.coerceAtMost(50.0)

    /** The line under the field: what it thought, and that it's hers to check. */
    fun line(e: PortionEstimate, serving: String): String {
        val s = servings(e) ?: return e.note.ifBlank { COULD_NOT }
        return "About ${text(s)} × $serving" + (e.note.trimEnd('.').takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Check it before adding."
    }
}

/**
 * The servings dialog's "How much did I have?": a photo of what's being eaten (and the package, if
 * she likes) goes with the food's words to the assistant, and its answer fills [onServings] for her
 * to check. Nothing is logged here. Shown only when the photo offer is ready (the assistant on,
 * Food allowed, not paused); the photos are shrunk and stripped of their details first.
 */
@Composable
fun PortionPhoto(food: Food, makes: Double?, ask: PortionAsk, onServings: (Double) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf<Boolean?>(null) } // true: the food; false: the package
    var photo by remember { mutableStateOf<Pair<ByteArray, Bitmap?>?>(null) }
    var pack by remember { mutableStateOf<Pair<ByteArray, Bitmap?>?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }

    fun prepare(forFood: Boolean, read: suspend () -> ByteArray, rotation: Int?) {
        capturing = null
        busy = true
        scope.launch {
            attempt {
                withContext(Dispatchers.Default) {
                    val jpeg = FoodPhotoImage.shrink(read(), rotation)
                    jpeg to android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                }
            }.onSuccess { if (forFood) photo = it else pack = it }
                .onFailure { line = it.message ?: "That photo didn't work. Try another one." }
            busy = false
        }
    }

    if (!open) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { open = true; line = null }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(PortionText.BUTTON)
            }
            line?.let { Hint(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Estimate from a photo", style = MaterialTheme.typography.titleSmall)
        line?.let { Hint(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(photo?.second?.let { it to "What you're eating" }, pack?.second?.let { it to "The package" }).forEach { (b, label) ->
                Image(b.asImageBitmap(), label, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Hint("Looking at your photo…")
            }
        } else {
            OutlinedButton(onClick = { capturing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (photo == null) "Photo of what you're eating" else "Another photo")
            }
            if (photo != null) {
                TextButton(onClick = { capturing = false }) { Text(if (pack == null) "Add the package (optional)" else "Another of the package") }
                OutlinedTextField(note, { note = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Anything to know? (optional)") })
                Hint(PortionText.NOTICE)
                Button(
                    onClick = {
                        val p = photo ?: return@Button
                        busy = true
                        scope.launch {
                            attempt { ask(p.first, pack?.first, PortionText.food(food, makes), note) }
                                .onSuccess { e ->
                                    line = PortionText.line(e, food.serving)
                                    PortionText.servings(e)?.let { onServings(it); open = false }
                                }
                                .onFailure { line = FoodPhotoText.error(it) }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Estimate servings") }
            }
            TextButton(onClick = { open = false }) { Text("Cancel") }
        }
    }
    capturing?.let { forFood ->
        PortionCapture(
            onDismiss = { capturing = null },
            onTaken = { bytes, rotation -> prepare(forFood, { bytes }, rotation) },
            onChosen = { uri -> prepare(forFood, { withContext(Dispatchers.IO) { readPhoto(context, uri) } }, null) },
        )
    }
}

private fun readPhoto(context: android.content.Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > FoodPhotoImage.MAX_BYTES) throw IllegalArgumentException("That photo is too big. Try another one.")
    }
    out.toByteArray()
} ?: throw IllegalArgumentException("Couldn't read that photo")

/** The camera in memory (nothing saved to the phone), or a photo chosen from the phone's. */
@Composable
private fun PortionCapture(onDismiss: () -> Unit, onTaken: (ByteArray, Int) -> Unit, onChosen: (Uri) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onChosen) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Take a photo", style = MaterialTheme.typography.titleLarge)
            if (granted) {
                FoodCamera(onTaken)
            } else {
                Text("The camera opens here, so the photo isn't saved to your phone.", style = MaterialTheme.typography.bodyMedium)
                if (denied) Hint("The camera is off for this app. You can turn it on in the phone's settings, or choose a photo instead.")
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Allow the camera") }
            }
            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose from your photos")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}
