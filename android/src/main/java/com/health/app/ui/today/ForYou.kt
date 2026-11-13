@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.AppPage
import com.health.api.FoodSuggestion
import com.health.api.Insight
import com.health.api.Meal
import com.health.api.Module
import com.health.app.ui.AppIcons
import com.health.app.ui.components.InfoCard
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * Where an insight's page ([AppPage]) is in this app, and what it's called ("Open Trends"). The
 * phone keeps sleep, activity and the body's numbers in Trends (or Measurements, when they show),
 * and Autism & ADHD inside Mind when only Mind is on. Plain Kotlin, so it's tested without a phone.
 */
object InsightLinks {
    /** Where a tap goes: [route] for the app's navigation, or null for Today's own (the scores). */
    data class Target(val page: AppPage, val route: String?, val name: String)

    fun target(page: AppPage, modules: Set<Module>, measurements: Boolean = false): Target = when (page) {
        AppPage.TODAY -> Target(page, null, "your scores")
        AppPage.FOOD -> Target(page, "food", "Food")
        AppPage.MEDS -> Target(page, "meds", "Medications")
        AppPage.MIND -> Target(page, "mind", "Mind")
        AppPage.CYCLE -> Target(page, "cycle", "Cycle")
        AppPage.CONTINENCE -> Target(page, "continence", "Bladder & bowel")
        AppPage.SUPPLIES -> Target(page, "supplies/" + (if (Module.CONTINENCE !in modules && Module.CYCLE in modules) SupplyArea.CYCLE else SupplyArea.CONTINENCE).route, "Supplies")
        AppPage.VITALS -> Target(page, "vitals", "Blood pressure & glucose")
        AppPage.HABITS -> Target(page, "habits", "Habits")
        AppPage.SYMPTOMS -> Target(page, "symptoms", "Symptoms")
        AppPage.HORMONES -> Target(page, "hormones", "Hormone therapy")
        AppPage.APPOINTMENTS -> Target(page, "appointments", "Appointments")
        AppPage.NEURO -> if (Module.NEURO in modules) Target(page, "neuro", "Autism & ADHD") else Target(page, "mind", "Mind")
        AppPage.TRANSITION -> Target(page, "transition", "Transition")
        AppPage.PHOTOS -> Target(page, "photos", "Progress photos")
        AppPage.BODY -> if (measurements) Target(page, "measurements", "Measurements") else Target(page, "trends", "Trends")
        AppPage.SLEEP, AppPage.ACTIVITY, AppPage.TRENDS -> Target(page, "trends", "Trends")
        AppPage.DEVICES -> Target(page, "devices", "Devices")
    }

    /**
     * What [insight] opens: its link, else its feature's page ([AppPage.of]); null when it's about
     * nothing in particular, or its page isn't here. [modules] are the features showing (while
     * viewing a share, the shared ones, and then never Devices: [readOnly]).
     */
    fun of(insight: Insight, modules: Set<Module>, readOnly: Boolean = false, measurements: Boolean = false): Target? {
        val page = AppPage.of(insight) ?: return null
        if (readOnly && page.ownOnly) return null
        val here = when (page) {
            // Trends (or Measurements) hold these, whatever's on.
            AppPage.SLEEP, AppPage.ACTIVITY, AppPage.BODY, AppPage.TRENDS, AppPage.TODAY, AppPage.DEVICES -> true
            AppPage.SUPPLIES -> Module.CONTINENCE in modules || Module.CYCLE in modules
            AppPage.NEURO -> Module.NEURO in modules || Module.MIND in modules
            else -> page.module?.let { it in modules } ?: true
        }
        return if (here) target(page, modules, measurements) else null
    }

    /** The row's name for screen readers: "2 doses still to take. Open Medications". */
    fun label(insight: Insight, target: Target): String = "${insight.title}. Open ${target.name}"

    /**
     * Today's insights to show: those for features that are on, less any that repeat a card
     * that's showing ([Insight.shownBy]: the day's doses and today's appointment while Up next shows them, TodayPlan.showing).
     */
    fun shown(insights: List<Insight>, modules: Set<Module>, showing: Set<com.health.api.TodaySection>): List<Insight> =
        insights.filter { (it.module == null || it.module in modules) && (it.shownBy == null || it.shownBy !in showing) }

    /** "dinner", "snacks": for "Add … to dinner". */
    fun mealWord(meal: Meal): String = when (meal) {
        Meal.SNACK -> "snacks"
        else -> meal.label.lowercase()
    }
}

/**
 * One insight as a compact row: its icon, title and a line or two, and a chevron when it opens a
 * page ([target]). Read out as its title and "Open <page>", then what it says. The protein
 * insight's food ideas sit beneath it as chips, each with Add ([onAdd]; none while viewing a share).
 */
@Composable
fun InsightRow(
    insight: Insight,
    target: InsightLinks.Target?,
    onOpen: (InsightLinks.Target) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onAdd: ((FoodSuggestion) -> Unit)? = null,
    showNumbers: Boolean = true,
) {
    val tone = when (insight.tone) {
        "good" -> AppTheme.status.good
        "warn" -> AppTheme.status.warn
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth()) {
        val tap = if (target != null) {
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clearAndSetSemantics {
                    contentDescription = InsightLinks.label(insight, target)
                    if (insight.body.isNotBlank()) stateDescription = insight.body
                    role = Role.Button
                    onClick("Open ${target.name}") { onOpen(target); true }
                }
                .clickable { onOpen(target) }
        } else {
            Modifier.semantics(mergeDescendants = true) {}
        }
        Row(
            tap.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.insight(insight.icon), null, tint = tone, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(insight.title, style = MaterialTheme.typography.titleSmall)
                // With food ideas, the chips say what the sentence would (it's still read out): a short line instead.
                val line = insight.suggestions.firstOrNull()?.let { "Ideas for ${InsightLinks.mealWord(it.meal)}" } ?: insight.body
                if (line.isNotBlank()) {
                    Text(
                        line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (target != null) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (insight.suggestions.isNotEmpty()) {
            // Under the whole row (a phone is narrow): the ideas' names fit. Each chip's touch target is 48 dp tall.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                // A long name is cut short rather than pushing the chip's protein and "+" off the line.
                val nameMax = maxWidth - 60.dp - 34.dp * LocalDensity.current.fontScale
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    insight.suggestions.take(3).forEach { FoodIdeaChip(it, if (readOnly) null else onAdd, showNumbers, nameMax) }
                }
            }
        }
    }
}

/**
 * A food idea as a chip: its name and protein ([showNumbers]), and a "+": the whole chip adds it
 * ([onAdd]; null makes it a plain label, as while viewing a share). Read out as "Add … to dinner".
 */
@Composable
fun FoodIdeaChip(idea: FoodSuggestion, onAdd: ((FoodSuggestion) -> Unit)?, showNumbers: Boolean = true, nameMax: Dp = 220.dp) {
    val inside: @Composable () -> Unit = {
        Row(
            Modifier.clearAndSetSemantics {}.height(36.dp).padding(start = 14.dp, end = if (onAdd != null) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Capped rather than weighted, so the chip is only as wide as it needs and two can share a line.
            Text(idea.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = nameMax.coerceAtLeast(48.dp)))
            if (showNumbers) {
                Spacer(Modifier.width(6.dp))
                Text("${idea.nutrients.protein.roundToInt()} g", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (onAdd != null) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
    val shape = RoundedCornerShape(50)
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    if (onAdd != null) {
        Surface(
            onClick = { onAdd(idea) }, shape = shape, color = color,
            modifier = Modifier.semantics { contentDescription = "Add ${idea.name} to ${InsightLinks.mealWord(idea.meal)}" },
        ) { inside() }
    } else {
        Surface(shape = shape, color = color, modifier = Modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = idea.name }) { inside() }
    }
}

/**
 * "For you": Today's insights as one card of [InsightRow]s, the first [shown] and "Show N more"
 * for the rest. [targetOf] says what each opens.
 */
@Composable
fun ForYouCard(
    insights: List<Insight>,
    targetOf: (Insight) -> InsightLinks.Target?,
    onOpen: (InsightLinks.Target) -> Unit,
    readOnly: Boolean = false,
    onAdd: ((FoodSuggestion) -> Unit)? = null,
    shown: Int = 3,
    /** Amounts of food (the ideas' protein): not in eating disorder recovery. */
    showNumbers: Boolean = true,
) {
    if (insights.isEmpty()) return
    var all by rememberSaveable { mutableStateOf(false) }
    val rows = if (all) insights else insights.take(shown)
    InfoCard("For you", icon = Icons.Rounded.AutoAwesome) {
        rows.forEachIndexed { i, insight ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InsightRow(insight, targetOf(insight), onOpen, readOnly = readOnly, onAdd = onAdd, showNumbers = showNumbers)
        }
        if (insights.size > shown) {
            TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show ${insights.size - shown} more") }
        }
    }
}
