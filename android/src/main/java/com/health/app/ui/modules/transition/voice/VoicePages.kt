package com.health.app.ui.modules.transition.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.VoicePlan
import com.health.app.ui.care.LocalReadOnly
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import java.time.LocalDate

/*
 * The voice trainer's pages, under Transition's routes ("transition/<page>?id=…"): "voice-training"
 * (home), "voice-plan" (setting up, then settings), "voice-baseline", "voice-lesson" (id: a
 * lesson), "voice-exercise" (id: an exercise, on its own), "voice-practice" and "voice-recordings".
 */

/** Whether [page] is one of the trainer's. */
fun isVoicePage(page: String): Boolean = page.startsWith("voice-")

@Composable
fun VoicePage(page: String, id: String?, onBack: () -> Unit, open: (String) -> Unit) {
    val c = appContainer()
    val vm = viewModel { VoiceViewModel(c) }
    val state by vm.state.collectAsState()
    val readOnly = LocalReadOnly.current
    val program = state.program
    if (page == "voice-training") {
        val context = LocalContext.current
        val recordings = remember(state) { VoiceRecordings(context).list().size }
        VoiceHome(state, LocalDate.now(c.zone()), readOnly, recordings, vm.store.keepRecordings, VoiceHomeActions(onBack, open) { vm.load() })
        return
    }
    if (program == null || readOnly) {
        Column {
            BackBar(onBack)
            when {
                readOnly -> ErrorCard("Practice is only for your own account.", Modifier.padding(16.dp))
                state.loading -> Loading()
                else -> ErrorCard(state.error ?: "Couldn't load the lessons", Modifier.padding(16.dp), actionLabel = "Try again", onAction = { vm.load() })
            }
        }
        return
    }
    val plan = state.plan ?: VoicePlan()
    when (page) {
        "voice-plan" -> PlanRoute(vm, plan, program.health, onBack, open)
        "voice-baseline" -> BaselineRoute(onBack, skip = null) { b ->
            vm.store.speakingDb = b.speakingDb
            vm.savePlan(plan.copy(baselinePitchHz = b.pitchHz, baselineResonance = b.resonance, baselineAt = System.currentTimeMillis()), onBack)
        }
        "voice-lesson" -> {
            val lesson = program.lessons.firstOrNull { it.id == id }
            TrainerRoute(vm, plan, lesson, lesson?.let { VoiceCourse.exercisesFor(program, it, plan.goal) }.orEmpty(), onBack)
        }
        "voice-exercise" -> TrainerRoute(vm, plan, null, listOfNotNull(program.exercises.firstOrNull { it.id == id }), onBack)
        "voice-practice" -> PracticeList(program, plan, onBack, open)
        "voice-recordings" -> RecordingsRoute(vm, program.exercises.associate { it.id to it.title }, onBack)
        else -> VoiceHome(state, LocalDate.now(c.zone()), readOnly, 0, vm.store.keepRecordings, VoiceHomeActions(onBack, open) { vm.load() })
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** The microphone's state, and a way to ask for it that then does [then] with the answer. */
@Composable
private fun rememberMic(): Pair<MicState, (then: (Boolean) -> Unit) -> Unit> {
    val context = LocalContext.current
    var mic by remember { mutableStateOf(if (micAllowed(context)) MicState.ALLOWED else MicState.UNKNOWN) }
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        mic = if (ok) MicState.ALLOWED else MicState.DENIED
        pending?.invoke(ok)
        pending = null
    }
    return mic to { then ->
        when (mic) {
            MicState.ALLOWED -> then(true)
            MicState.DENIED -> then(micAllowed(context).also { if (it) mic = MicState.ALLOWED })
            MicState.UNKNOWN -> {
                pending = then
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
private fun TrainerRoute(vm: VoiceViewModel, plan: VoicePlan, lesson: com.health.api.VoiceLesson?, exercises: List<com.health.api.VoiceExercise>, onBack: () -> Unit) {
    val context = LocalContext.current
    val trainer = viewModel { TrainerViewModel(context.applicationContext as android.app.Application) }
    // Idempotent: sets the lesson up the first time, so the first frame already shows it.
    trainer.setUp(lesson, exercises, plan, vm::saveSession)
    val ui by trainer.ui.collectAsState()
    val live by trainer.live.collectCalmly()
    val problem by trainer.problem.collectAsState()
    val playing by trainer.clips.playing.collectAsState()
    val (mic, askMic) = rememberMic()
    var leaving by remember { mutableStateOf(false) }
    val partway = ui.phase != TrainerPhase.FINISHED && (ui.phase != TrainerPhase.INTRO || ui.results.isNotEmpty())
    fun back() {
        if (partway) leaving = true else onBack()
    }
    BackHandler(enabled = partway) { leaving = true }
    DisposableEffect(Unit) { onDispose { trainer.clips.stop() } }
    val baseline = remember(ui.phase) { VoiceRecordings(context).baseline()?.file }
    TrainerScreen(
        ui, live, plan, mic,
        TrainerActions(
            back = ::back,
            begin = {
                val ex = ui.exercise
                if (ex != null && VoiceCourse.listens(ex)) askMic { ok -> trainer.begin(context, ok) } else trainer.begin(context, false)
            },
            finish = trainer::finish,
            skip = trainer::skip,
            text = trainer::text,
            playNote = trainer::playNote,
            nextNote = trainer::nextNote,
            feeling = trainer::feeling,
            strain = trainer::strain,
            next = trainer::next,
            restNow = trainer::restNow,
            done = onBack,
            openSettings = { openAppSettings(context) },
            play = trainer.clips::play,
            stopPlaying = trainer.clips::stop,
        ),
        speakingDb = vm.store.speakingDb,
        problem = problem,
        baseline = baseline,
        playing = playing,
    )
    if (leaving) {
        LeaveDialog(onStay = { leaving = false }) {
            leaving = false
            trainer.stopHere()
            onBack()
        }
    }
}

@Composable
private fun BaselineRoute(onBack: () -> Unit, skip: (() -> Unit)?, onUse: (Baseline) -> Unit) {
    Column(androidx.compose.ui.Modifier.padding(bottom = 32.dp)) {
        BackBar(onBack, "Your baseline")
        Column(androidx.compose.ui.Modifier.padding(horizontal = 16.dp)) { BaselineStep(skip, onUse) }
    }
}

/** The baseline recorder, wired to its view model and the microphone. */
@Composable
private fun BaselineStep(skip: (() -> Unit)?, onUse: (Baseline) -> Unit) {
    val context = LocalContext.current
    val baseline = viewModel { BaselineViewModel(context.applicationContext as android.app.Application) }
    val phase by baseline.phase.collectAsState()
    val live by baseline.live.collectCalmly()
    val left by baseline.secondsLeft.collectAsState()
    val result by baseline.result.collectAsState()
    val problem by baseline.problem.collectAsState()
    val (mic, askMic) = rememberMic()
    DisposableEffect(baseline) { onDispose { baseline.cancel() } }
    if (phase == BaselinePhase.READING || phase == BaselinePhase.SPEAKING) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            val before = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = before }
        }
    }
    problem?.let { ErrorCard(it, Modifier.padding(bottom = 12.dp)) }
    BaselineRecorder(
        phase, live, left, result, mic,
        BaselineActions(
            start = { askMic { ok -> if (ok) baseline.start(context) } },
            doneReading = baseline::doneReading,
            finish = baseline::finish,
            again = baseline::again,
            use = { b ->
                baseline.store.speakingDb = b.speakingDb
                onUse(b)
            },
            skip = skip,
            openSettings = { openAppSettings(context) },
        ),
    )
}

@Composable
private fun PlanRoute(vm: VoiceViewModel, saved: VoicePlan, health: List<String>, onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val setUp = VoicePlans.isSetUp(saved)
    var draft by rememberSaveable(stateSaver = PlanSaver) { mutableStateOf(saved) }
    var step by rememberSaveable { mutableStateOf(PlanStep.GOAL) }
    var keep by remember { mutableStateOf(vm.store.keepRecordings) }
    var recordings by remember { mutableIntStateOf(VoiceRecordings(context).list().size) }
    /** Fills in the suggestion for anything not chosen yet. */
    fun withSuggestion(plan: VoicePlan): VoicePlan {
        val s = VoicePlans.suggest(plan.goal, plan.baselinePitchHz, plan.baselineResonance)
        return plan.copy(targetLowHz = plan.targetLowHz ?: s.lowHz, targetHighHz = plan.targetHighHz ?: s.highHz, targetResonance = plan.targetResonance ?: s.resonance)
    }
    val actions = PlanActions(
        back = onBack,
        step = { step = it },
        change = { draft = it },
        save = { vm.savePlan(withSuggestion(draft), onBack) },
        keepRecordings = {
            keep = it
            vm.store.keepRecordings = it
        },
        deleteRecordings = {
            VoiceRecordings(context).deleteAll()
            recordings = 0
        },
        openRecordings = { open(RECORDINGS) },
        newBaseline = { open(BASELINE) },
    )
    if (setUp) {
        PlanSettings(draft, keep, recordings, actions)
        return
    }
    PlanSetup(step, draft, health, actions) {
        BaselineStep(skip = { step = PlanStep.TARGETS }) { b ->
            // A fresh baseline brings fresh suggestions.
            draft = draft.copy(baselinePitchHz = b.pitchHz, baselineResonance = b.resonance, baselineAt = System.currentTimeMillis(), targetLowHz = null, targetHighHz = null, targetResonance = null)
            step = PlanStep.TARGETS
        }
    }
}

@Composable
private fun RecordingsRoute(vm: VoiceViewModel, titles: Map<String, String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = appContainer()
    val store = remember { VoiceRecordings(context) }
    var list by remember { mutableStateOf(store.list()) }
    val player = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val playing by player.playing.collectAsState()
    RecordingsScreen(
        list, titles, vm.store.keepRecordings, playing, c.zone(), onBack,
        onPlay = { player.play(it.file) },
        onStop = player::stop,
        onDelete = { r ->
            if (playing == r.file) player.stop()
            store.delete(r)
            list = store.list()
        },
        onDeleteAll = {
            player.stop()
            store.deleteAll()
            list = emptyList()
        },
    )
}
