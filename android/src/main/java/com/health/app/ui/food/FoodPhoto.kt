@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.food

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.AiStatus
import com.health.api.FoodPhotoEstimate
import com.health.api.Meal
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.aiStatus
import com.health.app.net.foodPhoto
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/*
 * Food photo estimates: snap a photo of a meal and the assistant estimates what's in it, to check
 * and change before anything is logged (docs/API.md, "The AI assistant"). The add-food screen
 * offers it only when the assistant is on, the person said yes and Food is among what they
 * allowed, never while viewing a share. The photo stays in memory: it's shrunk and written again
 * here (dropping its location and camera details), sent, and forgotten.
 */

data class FoodPhotoState(
    /** Whether the first-time notice has been ticked on this phone. */
    val noticeSeen: Boolean = false,
    val status: AiStatus? = null,
    /** The photo as it will be sent (shrunk JPEG, in memory), and a preview of it. */
    val photo: ByteArray? = null,
    val preview: Bitmap? = null,
    val preparing: Boolean = false,
    val note: String = "",
    val sending: Boolean = false,
    /** Couldn't make it out, or nothing to see: what to try. */
    val problem: String? = null,
    val estimate: FoodPhotoEstimate? = null,
    val rows: List<PhotoRow> = emptyList(),
    val logging: Boolean = false,
    val done: Boolean = false,
)

class FoodPhotoActions(
    val back: () -> Unit = {},
    val acknowledge: () -> Unit = {},
    val taken: (ByteArray, Int) -> Unit = { _, _ -> },
    val chosen: (Uri) -> Unit = {},
    val note: (String) -> Unit = {},
    val send: () -> Unit = {},
    val retake: () -> Unit = {},
    val change: (PhotoRow) -> Unit = {},
    val remove: (Int) -> Unit = {},
    val add: () -> Unit = {},
    val log: () -> Unit = {},
)

class FoodPhotoViewModel(private val c: Container, val date: LocalDate, val meal: Meal) : ViewModel() {
    private val prefs = c.context.getSharedPreferences("food-photo", Context.MODE_PRIVATE)
    private val noticeKey = "notice." + (c.settings.value.user?.id ?: "me")
    private val _state = MutableStateFlow(FoodPhotoState(noticeSeen = prefs.getBoolean(noticeKey, false)))
    val state: StateFlow<FoodPhotoState> = _state.asStateFlow()
    private var nextKey = 1000

    init {
        // For the notice's links; the assistant is only ever the person's own.
        viewModelScope.launch { attempt { c.api.aiStatus() }.onSuccess { s -> _state.update { it.copy(status = s) } } }
    }

    fun acknowledge() {
        prefs.edit { putBoolean(noticeKey, true) }
        _state.update { it.copy(noticeSeen = true) }
    }

    /** A photo from the camera ([rotation] degrees to turn it upright) or the photo picker (null: its EXIF says). */
    private fun prepare(read: suspend () -> ByteArray, rotation: Int?) {
        _state.update { it.copy(preparing = true, problem = null) }
        viewModelScope.launch {
            try {
                val (jpeg, preview) = withContext(Dispatchers.Default) {
                    val jpeg = FoodPhotoImage.shrink(read(), rotation)
                    jpeg to android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                }
                _state.update { it.copy(photo = jpeg, preview = preview, preparing = false) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(preparing = false, problem = e.message ?: "That photo didn't work. Try another one.") }
            }
        }
    }

    fun taken(bytes: ByteArray, rotation: Int) = prepare({ bytes }, rotation)

    fun chosen(uri: Uri) = prepare({ withContext(Dispatchers.IO) { read(uri) } }, null)

    private fun read(uri: Uri): ByteArray = c.context.contentResolver.openInputStream(uri)?.use { input ->
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

    fun note(text: String) = _state.update { it.copy(note = text.take(200)) }

    fun send() {
        val photo = _state.value.photo ?: return
        if (_state.value.sending) return
        _state.update { it.copy(sending = true, problem = null) }
        viewModelScope.launch {
            try {
                val estimate = c.api.foodPhoto(photo, meal, _state.value.note)
                _state.update {
                    if (estimate.refused || estimate.items.isEmpty()) {
                        it.copy(sending = false, problem = estimate.notes.ifBlank { "Couldn't make that out; try another angle or add it by hand" }, photo = null, preview = null)
                    } else {
                        // The photo isn't needed any more: it's dropped here too.
                        it.copy(sending = false, estimate = estimate, rows = FoodPhotoText.rows(estimate), photo = null, preview = null)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(sending = false, problem = FoodPhotoText.error(e)) }
            }
        }
    }

    fun retake() = _state.update { it.copy(photo = null, preview = null, estimate = null, rows = emptyList(), problem = null) }

    fun change(row: PhotoRow) = _state.update { s -> s.copy(rows = s.rows.map { if (it.key == row.key) row else it }) }

    fun remove(key: Int) = _state.update { s -> s.copy(rows = s.rows.filter { it.key != key }) }

    fun add() = _state.update { s -> s.copy(rows = s.rows + FoodPhotoText.added(nextKey++)) }

    /** Logs each row with the food-logging API, one at a time; what's logged leaves the list if one fails. */
    fun log() {
        val ready = FoodPhotoText.ready(_state.value.rows)
        if (ready.isEmpty() || _state.value.logging) return
        _state.update { it.copy(logging = true) }
        viewModelScope.launch {
            var done = 0
            try {
                ready.forEach { row ->
                    c.ui.addFood(FoodPhotoText.input(row, date.toString(), meal))
                    done++
                }
                c.dataChanged()
                c.tell("Logged $done to ${meal.label.lowercase()}")
                _state.update { it.copy(logging = false, done = true) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val logged = ready.take(done).map { it.key }.toSet()
                if (done > 0) c.dataChanged()
                c.tell(e.message ?: "Couldn't log that")
                _state.update { s -> s.copy(logging = false, rows = s.rows.filter { it.key !in logged }) }
            }
        }
    }
}

@Composable
fun FoodPhotoRoute(date: LocalDate, meal: Meal, onBack: () -> Unit, onDone: () -> Unit) {
    val c = appContainer()
    val vm = viewModel(key = "photo-$date-$meal") { FoodPhotoViewModel(c, date, meal) }
    val state by vm.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // Back from the review is another photo; back from the photo leaves, forgetting it.
    val back = { if (state.estimate != null) vm.retake() else { vm.retake(); onBack() } }
    BackHandler(onBack = back)
    FoodPhotoScreen(
        meal, state,
        FoodPhotoActions(
            back = back, acknowledge = vm::acknowledge, taken = vm::taken, chosen = vm::chosen, note = vm::note, send = vm::send,
            retake = vm::retake, change = vm::change, remove = vm::remove, add = vm::add, log = vm::log,
        ),
        numbers = com.health.app.ui.components.foodNumbers(),
    )
}

/**
 * The notice the first time; then taking the photo; then checking what it saw. Without
 * [numbers] (the person's fields: eating disorder recovery), the review shows no amounts of food.
 */
@Composable
fun FoodPhotoScreen(meal: Meal, state: FoodPhotoState, actions: FoodPhotoActions, camera: Boolean = true, numbers: Boolean = true) {
    when {
        !state.noticeSeen -> FoodPhotoNotice(state.status, actions.back, actions.acknowledge)
        state.estimate != null -> FoodPhotoReview(meal, state, actions, numbers)
        else -> FoodPhotoCapture(meal, state, actions, camera)
    }
}

/** The first time only: where the photo goes, plainly, with the provider's terms. */
@Composable
fun FoodPhotoNotice(status: AiStatus?, onBack: () -> Unit, onAcknowledge: () -> Unit) {
    val provider = status?.provider ?: "Anthropic"
    var understood by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Snap a photo")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Where the photo goes", icon = Icons.Rounded.CloudUpload) {
                Text(FoodPhotoText.notice(provider), style = MaterialTheme.typography.bodyMedium)
                if (status != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { attempt { uri.openUri(status.termsUrl) } }) {
                            Text("$provider's commercial terms")
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp))
                        }
                        TextButton(onClick = { attempt { uri.openUri(status.privacyUrl) } }) {
                            Text("$provider's privacy policy")
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp))
                        }
                    }
                }
            }
            AppCard(padding = 12.dp) { Tick("I understand", understood) { understood = it } }
            Button(enabled = understood, onClick = onAcknowledge, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Carry on") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}

@Composable
private fun Tick(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Taking the photo (in the app, so it isn't saved to the gallery) or choosing one, with a note if it helps. */
@Composable
fun FoodPhotoCapture(meal: Meal, state: FoodPhotoState, actions: FoodPhotoActions, camera: Boolean = true) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(actions.chosen) }
    val pick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(actions.back, "Snap your ${meal.label.lowercase().removeSuffix("s")}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.problem?.let { InfoCard(null) { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            val preview = state.preview
            when {
                state.sending -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Hint("Looking at your photo…")
                }
                preview != null -> {
                    Image(
                        preview.asImageBitmap(), "Your photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)),
                    )
                    OutlinedTextField(
                        state.note, actions.note, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Anything to know? (optional)") }, placeholder = { Text("Large plate, half eaten…") },
                    )
                    Button(onClick = actions.send, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Estimate") }
                    OutlinedButton(onClick = actions.retake, modifier = Modifier.fillMaxWidth()) { Text("Another photo") }
                }
                state.preparing -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                granted && camera -> {
                    FoodCamera(actions.taken)
                    OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose from your photos")
                    }
                    Hint("The whole plate, from a little above, works best. The photo isn't saved to your phone.")
                }
                else -> {
                    InfoCard("The camera", icon = Icons.Rounded.PhotoCamera) {
                        Text("The camera opens here, so the photo isn't saved to your phone.", style = MaterialTheme.typography.bodyMedium)
                        if (denied) Hint("The camera is off for this app. You can turn it on in the phone's settings, or choose a photo instead.")
                        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Allow the camera") }
                    }
                    OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose from your photos")
                    }
                }
            }
        }
    }
}

/** The camera, in memory: the picture goes straight to [onTaken] with how far to turn it. */
@Composable
private fun FoodCamera(onTaken: (ByteArray, Int) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            attempt {
                provider = future.get().also { p ->
                    p.unbindAll()
                    val use = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, use, capture)
                }
            }.onFailure { problem = "The camera couldn't start. You can choose a photo instead." }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp))) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        FilledIconButton(
            enabled = !busy,
            onClick = {
                busy = true
                capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = attempt {
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }.getOrNull()
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        busy = false
                        if (bytes != null) onTaken(bytes, rotation) else problem = "That photo didn't work. Try again?"
                    }

                    override fun onError(exception: ImageCaptureException) {
                        busy = false
                        problem = "That photo didn't work. Try again?"
                    }
                })
            },
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(72.dp),
        ) { Icon(Icons.Rounded.PhotoCamera, "Take the photo", Modifier.size(34.dp)) }
    }
    problem?.let { ErrorCard(it) }
}

/**
 * Checking what it saw: names and amounts can change (the numbers follow the amount), rows can go
 * or be added, and the person's own food can stand in for the estimate. In eating-disorder
 * recovery (hideNumbers) the review shows foods and amounts only, with no calorie or macro
 * numbers; they're still logged, as the server worked them out.
 */
@Composable
fun FoodPhotoReview(meal: Meal, state: FoodPhotoState, actions: FoodPhotoActions, numbers: Boolean = true) {
    // The estimate says so, and so do the person's fields (FieldLayout.foodNumbers).
    val hide = state.estimate?.hideNumbers == true || !numbers
    val mealName = meal.label.lowercase()
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(actions.retake, "Check what's on the plate")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint(if (hide) FoodPhotoText.ESTIMATES_PLAIN else FoodPhotoText.ESTIMATES, Modifier.padding(horizontal = 4.dp))
            state.estimate?.notes?.takeIf { it.isNotBlank() }?.let { Hint(it, Modifier.padding(horizontal = 4.dp)) }
            if (!hide) Hint(FoodPhotoText.NO_VITAMINS, Modifier.padding(horizontal = 4.dp))
            state.rows.forEach { row -> RowCard(row, hide, actions) }
            TextButton(onClick = actions.add) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add something it missed")
            }
            if (!hide && FoodPhotoText.ready(state.rows).isNotEmpty()) {
                val total = FoodPhotoText.total(state.rows)
                AppCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${com.health.app.ui.Format.number(total.calories)} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Hint(FoodPhotoText.numbers(total).substringAfter(" · "))
                }
            }
            Spacer(Modifier.size(4.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !state.logging && FoodPhotoText.ready(state.rows).isNotEmpty(), onClick = actions.log,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (state.logging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Log to $mealName")
            }
            TextButton(onClick = actions.retake, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Another photo") }
        }
    }
}

@Composable
private fun RowCard(row: PhotoRow, hide: Boolean, actions: FoodPhotoActions) {
    val unit = FoodPhotoText.unit(row)
    AppCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                row.name, { actions.change(row.copy(name = it.take(120))) }, singleLine = true,
                label = { Text("Food") }, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.remove(row.key) }) { Icon(Icons.Rounded.Close, "Remove ${row.name.ifBlank { "this" }}") }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                row.amount, { actions.change(row.copy(amount = it.take(7))) }, singleLine = true,
                label = { Text(if (unit.length > 3) "Servings" else "Amount") },
                suffix = { Text(unit.takeIf { it.length <= 3 } ?: "") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                modifier = Modifier.width(128.dp),
            )
            when {
                unit.length > 3 -> Hint("Servings of ${unit.removePrefix("× ")}", Modifier.weight(1f))
                !row.added -> Hint(FoodPhotoText.confidence(row.item), Modifier.weight(1f))
                !hide -> OutlinedTextField(
                    row.calories, { actions.change(row.copy(calories = it.take(6))) }, singleLine = true,
                    label = { Text("Calories") }, suffix = { Text("kcal") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!hide) {
            Text(
                FoodPhotoText.numbers(FoodPhotoText.nutrients(row)), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
        row.item.match?.let { mine ->
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Tick("Use my ${mine.name}", row.useMine) { actions.change(FoodPhotoText.useMine(row, it)) }
        }
    }
}
