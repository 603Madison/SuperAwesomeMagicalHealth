package com.health.app.ui.meditation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.health.api.Fields
import com.health.app.attempt
import com.health.app.container
import com.health.app.net.meditations
import com.health.app.net.updateMeditationSession
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.modules.fieldShown
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.theme.reduceMotion
import kotlinx.coroutines.launch

/*
 * The player, full screen and made for bedtime: always dark and very dim, whatever the theme, with
 * the words large and calm. A breathing step's pacer is a circle growing and shrinking slowly with
 * "Breathe in" and "Breathe out" (just the words where the phone asks for less motion). Pause,
 * Stop and the time left; read aloud or text only (remembered here); and, if chosen, a fade to
 * black at the end with nothing more said. The playing itself is MeditationService's, so it goes
 * on with the screen off and this page can come and go.
 */

/** The player's colours: near black, the words in a dim slate, a muted violet for the pacer and buttons. */
object Night {
    val background = Color(0xFF040507)
    val text = Color(0xFF8C95A8)
    val quiet = Color(0xFF6F788B)
    val accent = Color(0xFF7C77AE)

    val colors: ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0B0B14),
        primaryContainer = Color(0xFF1A1928),
        onPrimaryContainer = Color(0xFFA6A1D2),
        secondaryContainer = Color(0xFF1A1928),
        onSecondaryContainer = Color(0xFFA6A1D2),
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = Color(0xFF12141A),
        onSurfaceVariant = quiet,
        surfaceContainerHighest = Color(0xFF1A1D24),
        outline = Color(0xFF353A47),
        outlineVariant = Color(0xFF1C2028),
    )
}

class MeditationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_ID)?.let(::begin)
        val c = container
        setContent {
            val settings by c.settings.collectAsState()
            HealthTheme(com.health.app.data.ThemeMode.DARK, bold = settings.boldText, palette = settings.palette) {
                MaterialTheme(colorScheme = Night.colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                    PlayerRoute(onClose = ::finish)
                }
            }
        }
    }

    /** Starts [id], unless one's playing already (that one shows). Fetched first if the phone hasn't kept it yet. */
    private fun begin(id: String) {
        if (MeditationPlayback.playing) return
        MeditationPlayback.forget()
        val store = MeditationStore(this)
        lifecycleScope.launch {
            if (store.meditation(id) == null) attempt { container.api.meditations() }.onSuccess(store::saveMeditations)
            if (store.meditation(id) == null || !MeditationService.start(this@MeditationActivity, id, store.voice, store.fadeOut)) {
                Toast.makeText(this@MeditationActivity, "Couldn't open that meditation. Check the connection and try again.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) MeditationPlayback.forget()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"

        fun open(context: Context, id: String) {
            context.startActivity(Intent(context, MeditationActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}

/** What the player can do, as callbacks (so it can be drawn with sample data). */
class PlayerActions(
    val back: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val stop: () -> Unit = {},
    val voice: (Boolean) -> Unit = {},
    val fadeOut: (Boolean) -> Unit = {},
    val felt: (Int) -> Unit = {},
    val close: () -> Unit = {},
    val voiceSettings: () -> Unit = {},
)

@Composable
private fun PlayerRoute(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = context.container
    val state by MeditationPlayback.state.collectAsState()
    val saved by MeditationPlayback.saved.collectAsState()
    val store = remember { MeditationStore(context) }
    val askFelt = fieldShown(Fields.MEDITATION_FELT_AFTER) && saved?.id != null
    val ended = state?.phase == PlayerPhase.ENDED
    // Stopped within the first minute: nothing's kept, so there's nothing more to show.
    LaunchedEffect(state?.phase, state?.finished) {
        val s = state ?: return@LaunchedEffect
        if (s.phase == PlayerPhase.ENDED && !MeditationPlayback.keeps(s)) onClose()
    }
    val back = {
        if (!ended) MeditationPlayback.stop()
        onClose()
    }
    BackHandler(onBack = back)
    MeditationPlayerScreen(
        state, still = reduceMotion(), askFelt = askFelt,
        actions = PlayerActions(
            back = back,
            pause = { MeditationPlayback.pause() },
            resume = { MeditationPlayback.resume() },
            stop = { MeditationPlayback.stop() },
            voice = { on -> store.voice = on; MeditationPlayback.setVoice(on) },
            fadeOut = { on -> store.fadeOut = on; MeditationPlayback.setFadeOut(on) },
            felt = { value ->
                saved?.let { session ->
                    val id = session.id ?: return@let
                    c.scope.launch {
                        attempt { c.api.updateMeditationSession(id, session.copy(feltAfter = value)) }
                            .onSuccess { c.dataChanged() }
                            .onFailure { c.tell("Couldn't save how you feel") }
                    }
                }
                onClose()
            },
            close = onClose,
            voiceSettings = {
                attempt { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { c.tell("Look for text-to-speech in your phone's settings") }
            },
        ),
    )
}

/** The player from [state] (null while it gets ready): drawn the same in the app and in screenshots. */
@Composable
fun MeditationPlayerScreen(state: PlayerState?, still: Boolean, askFelt: Boolean, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Night.background)) {
        when {
            state == null -> Text(
                "Getting ready…", style = MaterialTheme.typography.bodyLarge, color = Night.quiet,
                modifier = Modifier.align(Alignment.Center),
            )
            state.phase == PlayerPhase.ENDED && state.finished && state.fadeOut -> FadedOut(actions.close)
            state.phase == PlayerPhase.ENDED -> Ended(askFelt, actions)
            else -> Playing(state, still, actions)
        }
    }
}

/** Faded out: black, and nothing more. A tap (or back) closes it. */
@Composable
private fun FadedOut(onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "Meditation ended. Tap to close." },
    )
}

@Composable
private fun Playing(state: PlayerState, still: Boolean, actions: PlayerActions) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Stop and go back", tint = Night.quiet) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, color = Night.text, modifier = Modifier.semantics { heading() })
                Text("${state.minutes} min", style = MaterialTheme.typography.bodySmall, color = Night.quiet)
            }
            Text(
                if (state.phase == PlayerPhase.PAUSED) "Paused" else MeditationPlan.clockText(state.secondsLeft),
                style = MaterialTheme.typography.bodyMedium, color = Night.quiet, modifier = Modifier.padding(end = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val breath = state.breath
            if (breath != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text(
                        state.say, style = MaterialTheme.typography.bodyLarge, color = Night.quiet, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                    BreathCircle(breath, still)
                }
            } else {
                Text(
                    state.say,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light, fontSize = 26.sp, lineHeight = 38.sp),
                    color = Night.text, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 460.dp).padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.voice == VoiceMode.NO_OFFLINE_VOICE) NoVoiceNote(actions.voiceSettings)
        if (state.voice == VoiceMode.VOICE_FAILED) NoVoiceNote(actions.voiceSettings, failed = true)
        Controls(state, actions)
    }
}

/** The pacer: a circle breathing with the person, the words inside. Still where the phone asks for less motion. */
@Composable
private fun BreathCircle(point: PacerPoint, still: Boolean) {
    val target = if (still) 0.8f else Pacer.scale(point)
    val scale by animateFloatAsState(target, tween(if (still) 0 else 180, easing = LinearEasing), label = "breath")
    val fill = Night.accent.copy(alpha = 0.16f)
    val ring = Night.accent.copy(alpha = 0.42f)
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * scale
            drawCircle(fill, r)
            drawCircle(ring, r, style = Stroke(1.5.dp.toPx()))
        }
        Text(
            point.phase.words, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light), color = Night.text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** No offline voice on this phone: words on screen instead, and where to get a voice. */
@Composable
private fun NoVoiceNote(onSettings: () -> Unit, failed: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (failed) "Your phone's voice didn't read that, so it's words only for now. Turn Voice on to try again, or check your phone's text-to-speech settings."
            else "There's no offline voice on this phone yet, so it's words only. You can download one in your phone's text-to-speech settings.",
            style = MaterialTheme.typography.bodySmall, color = Night.quiet, textAlign = TextAlign.Center,
        )
        TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Text-to-speech settings") }
    }
}

@Composable
private fun Controls(state: PlayerState, actions: PlayerActions) {
    val paused = state.phase == PlayerPhase.PAUSED
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = actions.stop, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop", color = Night.quiet) }
            }
            FilledTonalIconButton(
                onClick = if (paused) actions.resume else actions.pause,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Night.colors.primaryContainer, contentColor = Night.colors.onPrimaryContainer),
            ) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause", Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
        }
        val voiceOn = state.voice != VoiceMode.TEXT_ONLY && state.voice != VoiceMode.VOICE_FAILED
        SegmentedChoice(listOf(true, false), voiceOn, { if (it) "Voice" else "Text only" }, actions.voice, Modifier.padding(horizontal = 24.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp).clickable { actions.fadeOut(!state.fadeOut) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Then fade out", style = MaterialTheme.typography.bodyLarge, color = Night.text)
                Text("At the end, the screen stays dark", style = MaterialTheme.typography.bodySmall, color = Night.quiet)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = state.fadeOut, onCheckedChange = actions.fadeOut)
        }
    }
}

/** The end: a quiet word, then (if the person keeps track of it) how they feel, in words. */
@Composable
private fun Ended(askFelt: Boolean, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        IconButton(onClick = actions.close, modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Night.quiet)
        }
        EndWords(askFelt, actions)
    }
}

@Composable
private fun EndWords(askFelt: Boolean, actions: PlayerActions) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Rest well.", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light), color = Night.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(32.dp))
        if (askFelt) {
            Text("How do you feel?", style = MaterialTheme.typography.titleMedium, color = Night.text)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeditationWords.FELT.forEachIndexed { i, words ->
                    OutlinedButton(onClick = { actions.felt(i + 1) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(words, color = Night.text) }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip", color = Night.quiet) }
        } else {
            TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close", color = Night.quiet) }
        }
    }
}
