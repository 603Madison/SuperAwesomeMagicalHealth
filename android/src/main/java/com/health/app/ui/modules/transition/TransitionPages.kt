package com.health.app.ui.modules.transition

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ChecklistItem
import com.health.api.Milestone
import com.health.api.MilestoneStatus
import com.health.api.MomentKind
import com.health.api.TransitionProfile
import com.health.api.VoiceSession
import com.health.app.net.addGenderMoment
import com.health.app.net.addHairRemoval
import com.health.app.net.addVoiceSession
import com.health.app.net.deleteBodyChange
import com.health.app.net.deleteMilestone
import com.health.app.net.saveBodyChange
import com.health.app.net.saveMilestone
import com.health.app.net.saveTransitionProfile
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer
import java.time.LocalDate

/**
 * The pages under Transition, by [page]: "profile", "templates", "milestone" ([id] a milestone's,
 * or "template:N"), "change", "hair", "moment" ([id] EUPHORIA or DYSPHORIA), "voice", "pitch", and
 * the voice trainer's "voice-…" pages.
 */
@Composable
fun TransitionPage(page: String, id: String?, onBack: () -> Unit, open: (String) -> Unit) {
    // The voice trainer's pages ("voice-training", "voice-lesson"…: voice/VoicePages.kt).
    if (com.health.app.ui.modules.transition.voice.isVoicePage(page)) {
        com.health.app.ui.modules.transition.voice.VoicePage(page, id, onBack, open)
        return
    }
    val c = appContainer()
    val vm = viewModel { TransitionViewModel(c) }
    val state by vm.state.collectAsState()
    val today = LocalDate.now(c.zone())
    val now = System.currentTimeMillis()
    val o = state.overview
    if (o == null && page != "hair" && page != "moment") {
        Column {
            BackBar(onBack)
            state.error?.let { ErrorCard(it, Modifier.padding(16.dp), actionLabel = "Try again", onAction = { vm.load() }) } ?: Loading()
        }
        return
    }
    // Pitch from the meter, carried to the practice form.
    var fromMeter by remember { mutableStateOf<VoiceSession?>(null) }
    when (page) {
        "profile" -> TransitionProfileEditor(o?.profile ?: TransitionProfile(), today, onBack) { p -> vm.act("Saved") { it.saveTransitionProfile(p) }; onBack() }
        "templates" -> MilestoneTemplates(state.templates, onBack) { i ->
            open("transition/milestone" + (i?.let { "?id=template:$it" } ?: ""))
        }
        "milestone" -> {
            val template = id?.removePrefix("template:")?.takeIf { id.startsWith("template:") }?.toIntOrNull()?.let { state.templates.getOrNull(it) }
            val initial = o?.milestones?.firstOrNull { it.id == id }
                ?: template?.let { t -> Milestone(t.title, t.area, MilestoneStatus.PLANNED, notes = t.about, checklist = t.checklist.map { ChecklistItem(it) }) }
                ?: Milestone(title = "")
            MilestoneEditor(
                initial, today, onBack,
                onSave = { m -> vm.act("Milestone saved") { it.saveMilestone(m) }; onBack() },
                onDelete = { mid -> vm.act("Deleted") { it.deleteMilestone(mid) }; onBack() },
            )
        }
        "change" -> BodyChangeForm(
            state.changes.firstOrNull { it.id == id }, today, onBack,
            onSave = { ch -> vm.act("Saved") { it.saveBodyChange(ch) }; onBack() },
            onDelete = { cid -> vm.act("Deleted") { it.deleteBodyChange(cid) }; onBack() },
        )
        "hair" -> HairRemovalForm(now, onBack) { h -> vm.act("Saved") { it.addHairRemoval(h) }; onBack() }
        "moment" -> MomentForm(if (id == MomentKind.DYSPHORIA.name) MomentKind.DYSPHORIA else MomentKind.EUPHORIA, now, onBack) { m ->
            vm.act(if (m.kind == MomentKind.EUPHORIA) "Kept. Lovely." else "Noted. Be gentle with yourself.") { it.addGenderMoment(m) }
            onBack()
        }
        "voice" -> VoiceForm(VoiceSession(at = now, minutes = 10), onBack) { v -> vm.act("Practice saved") { it.addVoiceSession(v) }; onBack() }
        "pitch" -> {
            val pending = fromMeter
            if (pending == null) {
                PitchMeterScreen(o?.profile?.voiceTargetHz, onBack) { minutes, min, avg, max ->
                    fromMeter = VoiceSession(at = now, minutes = minutes, pitchAvgHz = avg, pitchMinHz = min, pitchMaxHz = max)
                }
            } else {
                VoiceForm(pending, onBack = { fromMeter = null }) { v -> vm.act("Practice saved") { it.addVoiceSession(v) }; onBack() }
            }
        }
        else -> TransitionRoute(open)
    }
}
