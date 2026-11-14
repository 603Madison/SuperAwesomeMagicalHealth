@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.transition.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.health.api.ExerciseKind
import com.health.api.Layouts
import com.health.api.VoiceExercise
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceProgress
import com.health.api.VoiceSkill
import com.health.api.VoiceWeek
import com.health.app.attempt
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.ProgressBar
import com.health.app.ui.components.Stat
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The voice trainer's home, inside Transition: today's lesson, the daily routine, the streak and
 * this week's minutes, the units and lessons, free practice, progress by week, and the plan. Its
 * sections can be arranged like every feature screen's.
 */

/** The home's arrangement key (saved on the server: never rename it or the section ids). */
val VOICE_SCREEN: String = Layouts.screen(Layouts.ANDROID, "transition.voice")

/** The home's sections in the default order. */
fun voiceSections(setUp: Boolean, recordings: Boolean): List<ArrangeItem> = buildList {
    if (setUp) add(ArrangeItem("today", "Today"))
    if (setUp) add(ArrangeItem("week", "This week"))
    add(ArrangeItem("routine", "Daily routine"))
    add(ArrangeItem("lessons", "Lessons"))
    add(ArrangeItem("practice", "Free practice"))
    if (setUp) add(ArrangeItem("progress", "Progress"))
    if (recordings) add(ArrangeItem("recordings", "Recordings"))
    if (setUp) add(ArrangeItem("plan", "Your plan"))
}

class VoiceHomeActions(
    val back: () -> Unit = {},
    val open: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun VoiceHome(state: VoiceState, today: LocalDate, readOnly: Boolean, recordings: Int, keepRecordings: Boolean, actions: VoiceHomeActions) {
    val program = state.program
    val plan = state.plan ?: state.progress?.plan ?: VoicePlan()
    val progress = state.progress
    val setUp = VoicePlans.isSetUp(plan)
    val sections = voiceSections(setUp, keepRecordings || recordings > 0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(actions.back, "Voice training") {
            if (program != null) ArrangeAction(VOICE_SCREEN, "Voice training", sections)
            if (!readOnly && setUp) IconButton(onClick = { actions.open(PLAN) }) { Icon(Icons.Rounded.Settings, "Your plan") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                program == null && state.loading -> Loading()
                program == null -> ErrorCard(state.error ?: "Couldn't load the lessons", actionLabel = "Try again", onAction = actions.retry)
                else -> {
                    if (state.offline) Hint("Offline: practising from the copy on this phone. Sessions are sent once the server can be reached.")
                    if (state.pending > 0) Hint("${state.pending} ${if (state.pending == 1) "session" else "sessions"} waiting to be sent.")
                    if (!setUp && !readOnly) Welcome { actions.open(PLAN) }
                    progress?.restNote?.let { note -> InfoCard("Rest", icon = Icons.Rounded.Spa) { Text(note, style = MaterialTheme.typography.bodyLarge) } }
                    progress?.highlights?.takeIf { it.isNotEmpty() }?.let { lines ->
                        InfoCard("Lately", icon = Icons.Rounded.AutoAwesome) { lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) } }
                    }
                    Sections(arrangedIn(VOICE_SCREEN, sections)) { id ->
                        when (id) {
                            "today" -> TodayCard(program, plan, progress, today, readOnly, actions.open)
                            "week" -> WeekCard(plan, progress)
                            "routine" -> RoutineCard(program)
                            "lessons" -> LessonsCard(program, plan.goal, progress, readOnly, actions.open)
                            "practice" -> if (!readOnly) InfoCard("Free practice", icon = Icons.Rounded.Tune, onClick = { actions.open(PRACTICE) }, onOpen = { actions.open(PRACTICE) }) {
                                Hint("Pick any exercise, any time: a quick warm-up, a few glides, or some reading aloud.")
                            }
                            "progress" -> ProgressCard(plan, progress)
                            "recordings" -> if (!readOnly) InfoCard("Recordings", icon = Icons.Rounded.Mic, onClick = { actions.open(RECORDINGS) }, onOpen = { actions.open(RECORDINGS) }) {
                                Text(if (recordings == 0) "None yet." else "$recordings on this phone.", style = MaterialTheme.typography.bodyLarge)
                                Hint("On this phone only: never uploaded or backed up.")
                            }
                            "plan" -> PlanCard(plan, readOnly, actions.open)
                        }
                    }
                }
            }
            Hint("Progress isn't a straight line: some days your voice will feel easier than others, and that's part of it.", Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Transition's "Voice training" card: the streak and this week, and the way in. */
@Composable
fun VoiceTrainingCard(progress: VoiceProgress?, readOnly: Boolean, onOpen: () -> Unit) {
    val setUp = VoicePlans.isSetUp(progress?.plan)
    InfoCard(
        "Voice training", icon = Icons.Rounded.RecordVoiceOver,
        onClick = if (readOnly) null else onOpen, onOpen = if (readOnly) null else onOpen, openLabel = "voice training",
    ) {
        if (progress != null && setUp) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${progress.streakDays}", if (progress.streakDays == 1) "day in a row" else "days in a row")
                Stat("${progress.minutesThisWeek}", "minutes this week", unit = "min")
            }
            progress.highlights.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        } else {
            Text("Free, guided lessons with live feedback on pitch, resonance and more, at your pace.", style = MaterialTheme.typography.bodyMedium)
            Hint("Everything is heard on this phone only.", Modifier.padding(top = 4.dp))
        }
        if (!readOnly) {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) { Text(if (setUp) "Practise" else "Get started") }
        }
    }
}

const val HOME = "transition/voice-training"
const val PLAN = "transition/voice-plan"
const val PRACTICE = "transition/voice-practice"
const val RECORDINGS = "transition/voice-recordings"
const val BASELINE = "transition/voice-baseline"
fun lessonRoute(id: String) = "transition/voice-lesson?id=$id"
fun exerciseRoute(id: String) = "transition/voice-exercise?id=$id"

@Composable
private fun Welcome(onStart: () -> Unit) {
    InfoCard("Your voice, your way", icon = Icons.Rounded.RecordVoiceOver) {
        Text(
            "A free, guided programme with live feedback on pitch, resonance and more. Short lessons, at your pace, in whichever direction feels like you.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Hint("Everything is heard on this phone; no sound ever leaves it.", Modifier.padding(top = 6.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp)) { Text("Set up your plan") }
    }
}

@Composable
private fun TodayCard(program: VoiceProgram, plan: VoicePlan, progress: VoiceProgress?, today: LocalDate, readOnly: Boolean, open: (String) -> Unit) {
    val lesson = VoiceCourse.next(program, progress, plan.goal)
    val rest = VoicePlans.restDay(plan, today)
    InfoCard(if (rest) "Today is a rest day" else "Today", icon = Icons.Rounded.Today) {
        if (rest) Text("Rest is part of training. If you'd like to practise anyway, keep it gentle.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        if (lesson == null) {
            Hint("No lessons yet.")
            return@InfoCard
        }
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
        Text("${VoiceCourse.unitTitle(program, lesson.unit)} · ${lesson.minutes} min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(lesson.about, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        if (!readOnly) {
            if (rest) {
                OutlinedButton(onClick = { open(lessonRoute(lesson.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text("Practise anyway") }
            } else {
                Button(onClick = { open(lessonRoute(lesson.id)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Text(if (lesson.id in progress?.lessonsDone.orEmpty()) "Practise again" else "Start", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun WeekCard(plan: VoicePlan, progress: VoiceProgress?) {
    val minutes = progress?.minutesThisWeek ?: 0
    val goal = plan.dailyMinutes * (7 - plan.restDays.distinct().size).coerceAtLeast(1)
    InfoCard("This week", icon = Icons.AutoMirrored.Rounded.ShowChart) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val streak = progress?.streakDays ?: 0
            Stat("$streak", if (streak == 1) "day in a row" else "days in a row")
            Stat("$minutes", "of about $goal minutes", unit = "min")
        }
        ProgressBar(minutes.toFloat() / goal, Modifier.padding(top = 10.dp), color = AppTheme.status.good)
        Hint(
            if (minutes >= goal) "You've met this week's practice. Lovely." else "Little and often: ${plan.dailyMinutes} minutes on practice days.",
            Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RoutineCard(program: VoiceProgram) {
    if (program.daily.isEmpty()) return
    InfoCard("Daily routine", icon = Icons.AutoMirrored.Rounded.FormatListBulleted) {
        program.daily.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The goal's lessons by unit, each under its heading. Units not open yet show their heading and
 * what they're about, folded, so the list stays short.
 */
@Composable
private fun LessonsCard(program: VoiceProgram, goal: com.health.api.VoiceGoal, progress: VoiceProgress?, readOnly: Boolean, open: (String) -> Unit) {
    val done = progress?.lessonsDone.orEmpty().toSet()
    val units = VoiceCourse.units(program, goal)
    val all = units.flatMap { it.second }
    InfoCard("Lessons", icon = Icons.Rounded.RecordVoiceOver, status = "${all.count { it.id in done }} of ${all.size} done") {
        val opened = VoiceCourse.openUnits(program, goal, done)
        units.forEachIndexed { i, (unit, lessons) ->
            Text(
                VoiceCourse.unitTitle(program, unit),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = if (i == 0) 0.dp else 14.dp).semantics { heading() },
            )
            VoiceCourse.unitAbout(program, unit)?.let { Hint(it) }
            if (unit in opened) {
                Spacer(Modifier.height(2.dp))
                lessons.forEach { lesson ->
                    val status = VoiceCourse.status(lesson, program, progress, goal)
                    LessonRow(lesson.title, "${lesson.skill.label} · ${lesson.minutes} min", status, enabled = !readOnly && status != LessonStatus.LOCKED) { open(lessonRoute(lesson.id)) }
                }
            } else {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${lessons.size} ${if (lessons.size == 1) "lesson" else "lessons"} · opens after ${VoiceCourse.unitTitle(program, units[i - 1].first).substringBefore(" ·").lowercase()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LessonRow(title: String, subtitle: String, status: LessonStatus, enabled: Boolean, onClick: () -> Unit) {
    val (icon, tint, said) = when (status) {
        LessonStatus.DONE -> Triple(Icons.Rounded.CheckCircle, AppTheme.status.good, "Done")
        LessonStatus.NEXT -> Triple(Icons.Rounded.PlayCircle, MaterialTheme.colorScheme.primary, "Next")
        LessonStatus.OPEN -> Triple(Icons.Rounded.RadioButtonUnchecked, MaterialTheme.colorScheme.onSurfaceVariant, "Not done yet")
        LessonStatus.LOCKED -> Triple(Icons.Rounded.Lock, MaterialTheme.colorScheme.outline, "Not open yet")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, said, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (status == LessonStatus.LOCKED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(if (status == LessonStatus.NEXT) "Next · $subtitle" else subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Weekly progress: time in range, resonance and average pitch, each on its own small chart. */
@Composable
fun ProgressCard(plan: VoicePlan, progress: VoiceProgress?) {
    val weeks = progress?.weeks.orEmpty().sortedBy { it.weekStart }.takeLast(12)
    InfoCard("Progress", icon = Icons.AutoMirrored.Rounded.ShowChart) {
        if (weeks.count { it.sessions > 0 } < 2) {
            Hint("A couple of weeks of practice and your progress shows here, week by week.")
            return@InfoCard
        }
        val labels = weekLabels(weeks)
        val inRange = weeks.map { it.inRange?.toDouble() }
        if (inRange.any { it != null }) {
            ChartTitle("In your range", inRange.lastOrNull { it != null }?.let { "${it.roundToInt()}% latest week" })
            PercentBars(inRange, MaterialTheme.colorScheme.primary)
            AxisLabels(labels.first, labels.second, labels.third)
        }
        val resonance = weeks.map { it.resonance }
        if (resonance.any { it != null }) {
            ChartTitle("Resonance", resonance.lastOrNull { it != null }?.let { "${it.roundToInt()} latest" + (plan.targetResonance?.let { t -> " · target ${t.roundToInt()}" } ?: "") }, top = 14.dp)
            LineChart(resonance, color = MaterialTheme.colorScheme.tertiary, height = 80.dp, min = 0.0, max = 100.0, goal = plan.targetResonance)
            AxisLabels(labels.first, labels.second, labels.third)
        }
        val pitch = weeks.map { it.pitchAvgHz }
        if (pitch.count { it != null } >= 2) {
            ChartTitle("Average pitch", pitch.lastOrNull { it != null }?.let { "${it.roundToInt()} Hz latest" }, top = 14.dp)
            WeeklyPitch(pitch, plan)
            AxisLabels(labels.first, labels.second, labels.third)
            VoicePlans.range(plan)?.let { Hint("Your range, $it, is shaded.", Modifier.padding(top = 2.dp)) }
        }
        Hint("Weeks go up and down; the longer view is what counts.", Modifier.padding(top = 8.dp))
    }
}

/** A bar a week on a fixed 0–100% scale, with a faint line at half. */
@Composable
private fun PercentBars(values: List<Double?>, color: androidx.compose.ui.graphics.Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val described = values.lastOrNull { it != null }?.let { "Time in your range by week, latest ${it.roundToInt()}%" } ?: "Time in your range by week"
    Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = described }) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val bar = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        drawLine(grid.copy(alpha = 0.6f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v == null || v <= 0) return@forEachIndexed
            val h = (v / 100).toFloat().coerceIn(0f, 1f) * size.height
            drawRoundRect(
                color.copy(alpha = if (i == values.lastIndex) 1f else 0.75f),
                topLeft = Offset(i * slot + (slot - bar) / 2, size.height - h), size = Size(bar, h), cornerRadius = CornerRadius(bar / 3),
            )
        }
    }
}

/** Average pitch a week, in semitones (as it sounds), over the person's range; one colour throughout, gaps for weeks without. */
@Composable
private fun WeeklyPitch(values: List<Double?>, plan: VoicePlan) {
    val line = AppTheme.data.water
    val band = AppTheme.data.light.copy(alpha = 0.3f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val centre = VoicePlans.centre(plan)
    val low = plan.targetLowHz
    val high = plan.targetHighHz
    val marks = values.filterNotNull() + listOfNotNull(low, high)
    val lowest = (marks.minOfOrNull { VoiceScale.semitones(it, centre) } ?: -3.0) - 1.5
    val highest = (marks.maxOfOrNull { VoiceScale.semitones(it, centre) } ?: 3.0) + 1.5
    val described = "Average pitch by week" + (values.lastOrNull { it != null }?.let { ", latest ${it.roundToInt()} Hz" } ?: "") +
        (VoicePlans.range(plan)?.let { ", against your range of $it" } ?: "")
    Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = described }) {
        fun y(hz: Double) = (size.height - ((VoiceScale.semitones(hz, centre) - lowest) / (highest - lowest)).toFloat() * size.height).coerceIn(0f, size.height)
        val n = values.size
        fun x(i: Int) = if (n > 1) i * size.width / (n - 1) else size.width / 2
        if (low != null && high != null) drawRect(band, Offset(0f, y(high)), Size(size.width, y(low) - y(high)))
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        var path: Path? = null
        values.forEachIndexed { i, v ->
            if (v == null) {
                path?.let { drawPath(it, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
                path = null
            } else {
                val p = Offset(x(i), y(v))
                path = (path ?: Path().apply { moveTo(p.x, p.y) }).apply { lineTo(p.x, p.y) }
            }
        }
        path?.let { drawPath(it, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
        values.forEachIndexed { i, v -> if (v != null) drawCircle(line, 4.dp.toPx(), Offset(x(i), y(v))) }
    }
}

@Composable
private fun ChartTitle(title: String, value: String?, top: androidx.compose.ui.unit.Dp = 0.dp) {
    FlowRow(Modifier.padding(top = top, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun weekLabels(weeks: List<VoiceWeek>): Triple<String, String?, String> {
    val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    fun label(w: VoiceWeek) = attempt { LocalDate.parse(w.weekStart).format(format) }.getOrDefault(w.weekStart)
    return Triple(label(weeks.first()), weeks.getOrNull(weeks.size / 2)?.takeIf { weeks.size > 2 }?.let(::label), label(weeks.last()))
}

@Composable
private fun PlanCard(plan: VoicePlan, readOnly: Boolean, open: (String) -> Unit) {
    InfoCard("Your plan", icon = Icons.Rounded.Tune, onOpen = if (readOnly) null else ({ open(PLAN) }), openLabel = "your plan") {
        Text(plan.goal.label, style = MaterialTheme.typography.titleMedium)
        VoicePlans.range(plan)?.let { Text("Pitch: $it", style = MaterialTheme.typography.bodyMedium) }
        plan.targetResonance?.let { Text("Resonance: about ${it.roundToInt()}, ${VoiceWords.resonance(it)}", style = MaterialTheme.typography.bodyMedium) }
        Text(
            "${plan.dailyMinutes} minutes a day" + (plan.reminder?.let { ", a reminder at $it" } ?: "") +
                (if (plan.restDays.isNotEmpty()) ", ${plan.restDays.size} rest ${if (plan.restDays.size == 1) "day" else "days"} a week" else ""),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Free practice ----

/** Every exercise by skill, those for the person's goal first; tap one to practise it on its own. */
@Composable
fun PracticeList(program: VoiceProgram, plan: VoicePlan, onBack: () -> Unit, open: (String) -> Unit) {
    var everything by rememberSaveable { mutableStateOf(false) }
    val shown = program.exercises.filter { everything || VoiceCourse.suits(it, plan.goal) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Free practice")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Anything you like, as often as you like. Each one is saved as a short practice.", Modifier.padding(horizontal = 4.dp))
            VoiceSkill.entries.forEach { skill ->
                val list = shown.filter { it.skill == skill }
                if (list.isEmpty()) return@forEach
                InfoCard(skill.label, icon = skillIcon(skill)) {
                    list.forEach { ex -> ExerciseRow(ex, plan) { open(exerciseRoute(ex.id)) } }
                }
            }
            if (program.exercises.any { !VoiceCourse.suits(it, plan.goal) }) {
                InfoCard(null) { LabeledSwitch("Show exercises for every goal", everything, { everything = it }, subtitle = "Some exercises are meant for a particular direction.") }
            }
        }
    }
}

@Composable
private fun ExerciseRow(ex: VoiceExercise, plan: VoicePlan, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ex.title, style = MaterialTheme.typography.bodyLarge)
            val goals = ex.goals.takeIf { it.isNotEmpty() && plan.goal !in it }?.joinToString(", ") { it.label.lowercase() }
            Text(
                listOfNotNull(kindWords(ex.kind), clock(ex.seconds), goals?.let { "for $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.PlayArrow, "Practise ${ex.title}", tint = MaterialTheme.colorScheme.primary)
    }
}

fun kindWords(kind: ExerciseKind): String = when (kind) {
    ExerciseKind.GUIDED -> "Guided"
    ExerciseKind.HOLD -> "Hold"
    ExerciseKind.GLIDE -> "Glide"
    ExerciseKind.MATCH -> "Match notes"
    ExerciseKind.RESONANCE -> "Resonance"
    ExerciseKind.SAY -> "Words and phrases"
    ExerciseKind.READ -> "Reading aloud"
    ExerciseKind.SPEAK -> "Talking freely"
}

// ---- Recordings ----

/** The recordings on this phone: listen back, compare with the baseline, delete. */
@Composable
fun RecordingsScreen(
    recordings: List<Recording>,
    titles: Map<String, String>,
    keeping: Boolean,
    playing: java.io.File?,
    zone: ZoneId,
    onBack: () -> Unit,
    onPlay: (Recording) -> Unit,
    onStop: () -> Unit,
    onDelete: (Recording) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val format = DateTimeFormatter.ofPattern("EEE d MMM, H:mm", Locale.getDefault())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(onBack, "Recordings")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("On this phone only", icon = Icons.Rounded.Lock) {
                Text("Recordings are never uploaded, synced or backed up. Signing out deletes them.", style = MaterialTheme.typography.bodyMedium)
                if (!keeping) Hint("Keeping new recordings is off; turn it on in your plan.", Modifier.padding(top = 4.dp))
            }
            if (recordings.isEmpty()) Hint("No recordings yet.", Modifier.padding(horizontal = 4.dp))
            recordings.forEach { r ->
                val on = playing == r.file
                InfoCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (on) onStop() else onPlay(r) }) {
                            Icon(if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (on) "Stop" else "Play", tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(if (r.baseline) "Your baseline" else titles[r.exerciseId] ?: "Practice", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${java.time.Instant.ofEpochMilli(r.at).atZone(zone).format(format)} · ${clock(r.seconds)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(r) }) { Icon(Icons.Rounded.Delete, "Delete this recording", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (recordings.isNotEmpty()) {
                TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, null)
                    Text("Delete all recordings", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (confirm) DeleteRecordingsDialog(onDismiss = { confirm = false }) {
        confirm = false
        onDeleteAll()
    }
}
