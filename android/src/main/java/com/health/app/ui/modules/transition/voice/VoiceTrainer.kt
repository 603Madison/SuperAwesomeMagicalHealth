@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition.voice

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.health.api.ExerciseKind
import com.health.api.ExerciseResult
import com.health.api.VoiceExercise
import com.health.api.VoiceLesson
import com.health.api.VoicePlan
import com.health.api.VoiceSession
import com.health.api.VoiceSkill
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Stat
import com.health.app.ui.theme.AppTheme
import com.health.app.ui.theme.reduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/*
 * The trainer: one exercise at a time, in a lesson or on its own. Each runs as the exercise's kind
 * says: steps and a timer, live meters while listening, texts one by one for speech, tones to
 * match for MATCH. Then a short, kind summary, "how did that feel?" and "any strain?", and on to
 * the next. Finishing (or stopping) saves a VoiceSession with what was done. The screen stays on
 * while practising.
 */

enum class TrainerPhase { INTRO, ACTIVE, SUMMARY, FINISHED }

/** Whether the microphone may be used: not asked yet, allowed, or refused. */
enum class MicState { UNKNOWN, ALLOWED, DENIED }

/** Matching notes, as the screen shows it. */
data class MatchUi(
    val targets: List<Double>,
    val index: Int = 0,
    val matched: List<Boolean> = targets.map { false },
    val progress: Float = 0f,
    val playing: Boolean = false,
    val done: Boolean = false,
) {
    val target: Double? get() = targets.getOrNull(index)
}

data class TrainerUi(
    /** Null for free practice. */
    val lesson: VoiceLesson? = null,
    val exercises: List<VoiceExercise> = emptyList(),
    val index: Int = 0,
    val phase: TrainerPhase = TrainerPhase.INTRO,
    val elapsed: Int = 0,
    val textIndex: Int = 0,
    val match: MatchUi? = null,
    /** The finished exercise's result, before "how did that feel?". */
    val result: ExerciseResult? = null,
    val summary: List<String> = emptyList(),
    val feeling: Int? = null,
    val strain: Boolean? = null,
    /** Whether the microphone was used for this exercise. */
    val listened: Boolean = false,
    /** This exercise's recording on the phone, if the person keeps them. */
    val recording: File? = null,
    val results: List<ExerciseResult> = emptyList(),
    val strainAny: Boolean = false,
    val startedAt: Long = 0,
    val saved: Boolean = false,
) {
    val exercise: VoiceExercise? get() = exercises.getOrNull(index)
    val last: Boolean get() = index >= exercises.lastIndex
}

/** Runs a lesson or a single exercise: listening, matching, timing and results. */
class TrainerViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val app: Context get() = getApplication()
    val store = VoiceStore(application)
    private val listener = VoiceListener { store.speakingDb }
    val clips = ClipPlayer()
    val live: StateFlow<VoiceLive> = listener.live
    val problem: StateFlow<String?> = listener.problem

    private val _ui = MutableStateFlow(TrainerUi())
    val ui: StateFlow<TrainerUi> = _ui.asStateFlow()

    private var plan = VoicePlan()
    private var save: (VoiceSession) -> Unit = {}
    private var round: MatchRound? = null
    private val lock = Any()
    private var ticker: Job? = null
    private var playing: Job? = null
    private var activeSince = 0L
    private var recording: File? = null

    /** Sets up once, when the programme and plan are in. */
    fun setUp(lesson: VoiceLesson?, exercises: List<VoiceExercise>, plan: VoicePlan, save: (VoiceSession) -> Unit) {
        this.save = save
        this.plan = plan
        if (_ui.value.exercises.isEmpty() && exercises.isNotEmpty()) _ui.value = TrainerUi(lesson = lesson, exercises = exercises)
    }

    fun begin(context: Context, mic: Boolean) {
        val ex = _ui.value.exercise ?: return
        val now = System.currentTimeMillis()
        activeSince = now
        val listens = mic && VoiceCourse.listens(ex)
        recording = if (listens && store.keepRecordings) VoiceRecordings(app).newFile(ex.id, now) else null
        if (listens) listener.start(context, recording)
        _ui.update {
            it.copy(
                phase = TrainerPhase.ACTIVE, elapsed = 0, textIndex = 0, result = null, summary = emptyList(), feeling = null, strain = null,
                listened = listens, recording = null, startedAt = if (it.startedAt == 0L) now else it.startedAt, match = null,
            )
        }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _ui.update { it.copy(elapsed = ((System.currentTimeMillis() - activeSince) / 1000).toInt()) }
            }
        }
        if (ex.kind == ExerciseKind.MATCH) startMatch(ex, listens)
    }

    private fun startMatch(ex: VoiceExercise, listens: Boolean) {
        val targets = MatchRound.targets(ex.notes.ifEmpty { listOf(0, 2, 4, 2, 0) }, plan)
        val r = MatchRound(targets)
        round = r
        _ui.update { it.copy(match = MatchUi(targets)) }
        if (listens) {
            listener.onFrame = { _, live ->
                val completed = synchronized(lock) { r.hear(live.pitchHz) }
                publish()
                if (completed) viewModelScope.launch {
                    delay(900)
                    nextNote()
                }
            }
        }
        playNote()
    }

    private fun publish(playing: Boolean? = null) {
        val r = round ?: return
        val snapshot = synchronized(lock) { MatchUi(r.targets, r.index.coerceAtMost(r.targets.size - 1), r.targets.indices.map(r::matched), r.progress, done = r.done) }
        _ui.update { s -> s.copy(match = snapshot.copy(playing = playing ?: s.match?.playing ?: false)) }
    }

    /** Plays the note to match (again); the microphone's ears are closed while it plays. */
    fun playNote() {
        val r = round ?: return
        val target = synchronized(lock) { r.target } ?: return
        playing?.cancel()
        playing = viewModelScope.launch {
            publish(playing = true)
            listener.paused = true
            try {
                TonePlayer.play(target)
                delay(200)
            } finally {
                listener.paused = false
                publish(playing = false)
            }
        }
    }

    fun nextNote() {
        val r = round ?: return
        val done = synchronized(lock) {
            if (!r.done) r.next()
            r.done
        }
        publish(playing = false)
        if (!done) playNote()
    }

    fun text(step: Int) {
        val ex = _ui.value.exercise ?: return
        _ui.update { it.copy(textIndex = (it.textIndex + step).coerceIn(0, (ex.texts.size - 1).coerceAtLeast(0))) }
    }

    /** Ends the exercise and works out how it went. */
    fun finish() {
        val ex = _ui.value.exercise ?: return
        ticker?.cancel()
        playing?.cancel()
        listener.onFrame = null
        listener.paused = false
        viewModelScope.launch {
            val frames = listener.stop()
            val seconds = ((System.currentTimeMillis() - activeSince) / 1000).toInt().coerceAtLeast(1)
            val r = round
            round = null
            val listened = _ui.value.listened
            val (matched, of) = if (r != null && listened) synchronized(lock) { r.count to (if (r.done) r.targets.size else (r.index + 1).coerceAtMost(r.targets.size)) } else null to null
            val result = VoiceResults.result(ex, seconds, frames, plan, matched = matched, of = of)
            val file = recording?.takeIf { it.exists() && it.length() > 44 + VoiceScale.RATE * 2 }
            if (file == null) recording?.delete()
            _ui.update { it.copy(phase = TrainerPhase.SUMMARY, elapsed = seconds, result = result, summary = VoiceWords.summary(ex, result, plan, it.index), recording = file, match = null) }
        }
    }

    fun feeling(n: Int) = _ui.update { it.copy(feeling = n) }
    fun strain(yes: Boolean) = _ui.update { it.copy(strain = yes) }

    /** Keeps this exercise's result and moves on (or finishes the lesson). */
    fun next() {
        clips.stop()
        val s = _ui.value
        val results = s.results + listOfNotNull(s.result?.copy(feeling = s.feeling))
        val strain = s.strainAny || s.strain == true
        if (!s.last) {
            _ui.value = s.copy(index = s.index + 1, phase = TrainerPhase.INTRO, results = results, strainAny = strain, result = null, summary = emptyList(), feeling = null, strain = null, recording = null)
        } else {
            complete(results, strain)
        }
    }

    /** Strain: keeps this result and stops for today. */
    fun restNow() {
        clips.stop()
        val s = _ui.value
        complete(s.results + listOfNotNull(s.result?.copy(feeling = s.feeling)), true)
    }

    /** Leaves this exercise out. */
    fun skip() {
        val s = _ui.value
        if (!s.last) _ui.value = s.copy(index = s.index + 1, phase = TrainerPhase.INTRO) else complete(s.results, s.strainAny)
    }

    /** Stops partway: what's done so far is saved. */
    fun stopHere() {
        val s = _ui.value
        if (s.phase == TrainerPhase.ACTIVE) {
            ticker?.cancel()
            playing?.cancel()
            listener.onFrame = null
            viewModelScope.launch { listener.stop() }
        }
        val results = s.results + listOfNotNull(s.result?.takeIf { s.phase == TrainerPhase.SUMMARY }?.copy(feeling = s.feeling))
        complete(results, s.strainAny || s.strain == true)
    }

    private fun complete(results: List<ExerciseResult>, strain: Boolean) {
        val s = _ui.value
        if (results.isNotEmpty()) {
            val titles = results.mapNotNull { r -> s.exercises.firstOrNull { it.id == r.exerciseId }?.title }
            save(VoiceResults.session(s.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), s.lesson?.id, titles, results, strain))
        }
        _ui.value = s.copy(phase = TrainerPhase.FINISHED, results = results, strainAny = strain, saved = results.isNotEmpty(), result = null)
    }

    override fun onCleared() {
        listener.close()
        clips.stop()
    }
}

/** What the trainer screen can do. */
class TrainerActions(
    val back: () -> Unit = {},
    val begin: () -> Unit = {},
    val finish: () -> Unit = {},
    val skip: () -> Unit = {},
    val text: (Int) -> Unit = {},
    val playNote: () -> Unit = {},
    val nextNote: () -> Unit = {},
    val feeling: (Int) -> Unit = {},
    val strain: (Boolean) -> Unit = {},
    val next: () -> Unit = {},
    val restNow: () -> Unit = {},
    val done: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val play: (File) -> Unit = {},
    val stopPlaying: () -> Unit = {},
)

/** The trainer's screen, from its state alone (so it can be drawn for screenshots). */
@Composable
fun TrainerScreen(
    ui: TrainerUi,
    live: VoiceLive,
    plan: VoicePlan,
    mic: MicState,
    actions: TrainerActions,
    speakingDb: Double? = null,
    problem: String? = null,
    baseline: File? = null,
    playing: File? = null,
) {
    val ex = ui.exercise
    // The screen stays on while practising.
    if (ui.phase == TrainerPhase.ACTIVE) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            val before = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = before }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // The lesson in the bar; the exercise as a heading that wraps, so large text never cuts it.
        BackBar(actions.back, ui.lesson?.title ?: "Free practice")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ex != null && (ui.phase == TrainerPhase.ACTIVE || ui.phase == TrainerPhase.SUMMARY)) {
                Text(ex.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
            }
            if (ui.exercises.size > 1 && ui.phase != TrainerPhase.FINISHED) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    ProgressBar((ui.index + if (ui.phase == TrainerPhase.SUMMARY) 1f else 0f) / ui.exercises.size, Modifier.weight(1f), height = 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("${ui.index + 1} of ${ui.exercises.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            problem?.let { ErrorCard(it) }
            when {
                ui.phase == TrainerPhase.FINISHED -> Finished(ui, plan, actions)
                ex == null -> Hint("Nothing to practise here yet.")
                ui.phase == TrainerPhase.INTRO -> Intro(ex, ui, mic, actions)
                ui.phase == TrainerPhase.ACTIVE -> Active(ex, ui, live, plan, mic, speakingDb, actions)
                ui.phase == TrainerPhase.SUMMARY -> Summary(ex, ui, actions, baseline, playing)
            }
        }
    }
}

/** An icon for each skill. */
fun skillIcon(skill: VoiceSkill): ImageVector = when (skill) {
    VoiceSkill.HEALTH -> Icons.Rounded.Spa
    VoiceSkill.WARM_UP -> Icons.Rounded.Air
    VoiceSkill.PITCH -> Icons.AutoMirrored.Rounded.ShowChart
    VoiceSkill.RESONANCE -> Icons.Rounded.WbTwilight
    VoiceSkill.WEIGHT -> Icons.Rounded.Tune
    VoiceSkill.INTONATION -> Icons.Rounded.GraphicEq
    VoiceSkill.SPEECH -> Icons.Rounded.RecordVoiceOver
}

/** "1:05". */
fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun Steps(ex: VoiceExercise, compact: Boolean = false) {
    ex.steps.forEachIndexed { i, step ->
        Row(Modifier.padding(vertical = if (compact) 2.dp else 4.dp)) {
            Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
            Text(step, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Intro(ex: VoiceExercise, ui: TrainerUi, mic: MicState, actions: TrainerActions) {
    val listens = VoiceCourse.listens(ex)
    InfoCard(ex.skill.label, icon = skillIcon(ex.skill), status = clock(ex.seconds)) {
        Text(ex.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        Steps(ex)
        ex.tip?.let { tip ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(tip, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (listens && mic != MicState.ALLOWED) {
        InfoCard("The microphone", icon = Icons.Rounded.Lock) {
            Text(if (mic == MicState.DENIED) VoiceWords.NO_MIC else VoiceWords.MIC_WHY, style = MaterialTheme.typography.bodyMedium)
            if (mic == MicState.DENIED) TextButton(onClick = actions.openSettings, modifier = Modifier.padding(top = 4.dp)) { Text("Open the app's settings") }
        }
    }
    Button(onClick = actions.begin, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(if (listens && mic == MicState.UNKNOWN) Icons.Rounded.Mic else Icons.Rounded.PlayArrow, null)
        Text(
            when {
                !listens || mic == MicState.ALLOWED -> "Start"
                mic == MicState.UNKNOWN -> "Allow the microphone and start"
                else -> "Start without the meters"
            },
            Modifier.padding(start = 8.dp),
        )
    }
    if (ui.exercises.size > 1) TextButton(onClick = actions.skip, modifier = Modifier.fillMaxWidth()) { Text(if (ui.last) "Skip this one and finish" else "Skip this one") }
}

@Composable
private fun Active(ex: VoiceExercise, ui: TrainerUi, live: VoiceLive, plan: VoicePlan, mic: MicState, speakingDb: Double?, actions: TrainerActions) {
    val over = ui.elapsed >= ex.seconds
    InfoCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(clock(ui.elapsed), style = MaterialTheme.typography.headlineMedium)
            Text("  of ${clock(ex.seconds)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        ProgressBar(ui.elapsed.toFloat() / ex.seconds.coerceAtLeast(1), Modifier.padding(top = 8.dp), color = if (over) AppTheme.status.good else MaterialTheme.colorScheme.primary)
        if (over) Hint("That's the time. Finish whenever you're ready.", Modifier.padding(top = 6.dp))
    }
    when (ex.kind) {
        ExerciseKind.SAY, ExerciseKind.READ, ExerciseKind.SPEAK -> if (ex.texts.isNotEmpty()) Texts(ex, ui.textIndex, actions)
        ExerciseKind.MATCH -> ui.match?.let { MatchCard(it, ui.listened, actions) }
        else -> {}
    }
    InfoCard("What to do", icon = skillIcon(ex.skill)) {
        Steps(ex, compact = true)
        ex.tip?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    }
    if (ui.listened) {
        Meters(VoiceCourse.meters(ex), live, plan, speakingDb, target = ui.match?.target?.takeIf { !ui.match.done }, matched = ui.match?.progress?.takeIf { !ui.match.done })
    } else if (VoiceCourse.listens(ex)) {
        Hint(if (mic == MicState.ALLOWED) "Not listening." else "The meters need the microphone; the steps and timer work without it.")
    }
    Button(onClick = actions.finish, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Rounded.CheckCircle, null)
        Text(if (ui.match?.done == true || over) "Finish" else "I'm done", Modifier.padding(start = 8.dp))
    }
}

/** Words, phrases or passages one at a time, large, with back and next. */
@Composable
private fun Texts(ex: VoiceExercise, index: Int, actions: TrainerActions) {
    val text = ex.texts.getOrNull(index) ?: return
    val long = ex.kind == ExerciseKind.READ || text.length > 90
    InfoCard(
        when (ex.kind) {
            ExerciseKind.READ -> "Read aloud"
            ExerciseKind.SPEAK -> "Talk about"
            else -> "Say"
        },
        icon = Icons.Rounded.RecordVoiceOver,
        status = if (ex.texts.size > 1) "${index + 1} of ${ex.texts.size}" else null,
    ) {
        Text(
            text,
            style = if (long) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = if (long) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
        if (ex.texts.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.text(-1) }, enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    Text("Back", Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = { actions.text(1) }, enabled = index < ex.texts.lastIndex, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Next", Modifier.padding(end = 6.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
        }
    }
}

/** The notes to match: which are done, the one now, and hearing it again. */
@Composable
private fun MatchCard(match: MatchUi, listening: Boolean, actions: TrainerActions) {
    InfoCard("Match the note", icon = Icons.Rounded.MusicNote, status = "${(match.index + 1).coerceAtMost(match.targets.size)} of ${match.targets.size}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            match.targets.forEachIndexed { i, hz ->
                val matched = match.matched.getOrElse(i) { false }
                val current = i == match.index && !match.done
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (matched) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (matched) "Matched" else "Not matched yet",
                        tint = if (matched) AppTheme.status.good else if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        VoiceScale.note(hz),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
        val target = match.target
        Text(
            when {
                match.done -> "That's every note. Well listened."
                match.playing -> "Listen…"
                match.matched.getOrElse(match.index) { false } -> "Matched. Nicely done."
                !listening -> "Sing or hum it back, then go on when you're ready."
                else -> "Your turn: hum or sing it back, and hold it."
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (!match.done && target != null) {
            Text("The note: ${VoiceScale.hzAndNote(target)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                OutlinedButton(onClick = actions.playNote, enabled = !match.playing, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Hear it again") }
                FilledTonalButton(onClick = actions.nextNote, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (match.index >= match.targets.lastIndex) "Done" else "Next note") }
            }
        }
    }
}

@Composable
private fun Summary(ex: VoiceExercise, ui: TrainerUi, actions: TrainerActions, baseline: File?, playing: File?) {
    InfoCard("How it went", icon = Icons.Rounded.CheckCircle, iconTint = AppTheme.status.good, status = clock(ui.elapsed)) {
        ui.summary.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 3.dp)) }
        if (ui.recording != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PlayButton("Listen back", ui.recording, playing, actions)
                baseline?.let { PlayButton("Your baseline", it, playing, actions) }
            }
            Hint("Recordings stay on this phone only.", Modifier.padding(top = 4.dp))
        }
    }
    InfoCard("How did that feel?", icon = Icons.Rounded.Favorite) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { n ->
                FilterChip(selected = ui.feeling == n, onClick = { actions.feeling(n) }, label = { Text(VoiceWords.feeling(n)!!) })
            }
        }
    }
    InfoCard("Any strain or discomfort?", icon = Icons.Rounded.Spa) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.strain == false, onClick = { actions.strain(false) }, label = { Text("No, it felt fine") })
            FilterChip(selected = ui.strain == true, onClick = { actions.strain(true) }, label = { Text("Yes, a little") })
        }
        if (ui.strain == true) Text(VoiceWords.REST_NOTE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    if (ui.strain == true) {
        Button(onClick = actions.restNow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Rest now and save") }
        if (!ui.last) OutlinedButton(onClick = actions.next, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Carry on gently") }
    } else {
        val next = ui.exercises.getOrNull(ui.index + 1)
        Button(onClick = actions.next, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (next == null) (if (ui.lesson != null) "Finish the lesson" else "Finish") else "Next: ${next.title}", maxLines = 2)
        }
    }
}

@Composable
private fun PlayButton(label: String, file: File, playing: File?, actions: TrainerActions) {
    val on = playing == file
    OutlinedButton(onClick = { if (on) actions.stopPlaying() else actions.play(file) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
        Text(if (on) "Stop" else label, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Finished(ui: TrainerUi, plan: VoicePlan, actions: TrainerActions) {
    val results = ui.results
    InfoCard(if (ui.saved) (if (ui.lesson != null) "Lesson done" else "Practice done") else "Nothing saved", icon = Icons.Rounded.CheckCircle, iconTint = AppTheme.status.good) {
        if (results.isEmpty()) {
            Text("That's OK: come back whenever you like.", style = MaterialTheme.typography.bodyLarge)
        } else {
            val session = VoiceResults.session(0, null, emptyList(), results, ui.strainAny)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${session.minutes}", "minutes", unit = "min")
                Stat("${results.size}", if (results.size == 1) "exercise" else "exercises")
                session.pitchAvgHz?.let { Stat("${it.roundToInt()}", "average pitch", unit = "Hz") }
                session.resonance?.let { Stat("${it.roundToInt()}", "resonance") }
            }
            val inRange = results.mapNotNull { it.inRange }
            if (inRange.isNotEmpty()) Text("In your range ${inRange.average().roundToInt()}% of the time.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            Text(VoiceWords.cheer(results.size + ui.index), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (ui.strainAny) {
        InfoCard("Rest your voice", icon = Icons.Rounded.Spa) { Text(VoiceWords.REST_NOTE, style = MaterialTheme.typography.bodyMedium) }
    }
    Button(onClick = actions.done, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Back to voice training") }
}

/** A flow's value for the screen, redrawn at most four times a second when things should keep still. */
@Composable
fun <T> StateFlow<T>.collectCalmly(): State<T> {
    val slow = reduceMotion()
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this, slow) {
        if (slow) {
            while (true) {
                state.value = value
                delay(250)
            }
        } else {
            collect { state.value = it }
        }
    }
    return state
}

/** The "Stop here?" question when leaving partway through a lesson. */
@Composable
fun LeaveDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Stop here?") },
        text = { Text("What you've done so far will be saved. You can pick up the rest another time.") },
        confirmButton = { TextButton(onClick = onLeave) { Text("Stop here") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Keep practising") } },
    )
}
