@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.modules.continence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.ContinenceProgress
import com.health.api.ProgressWeek
import com.health.app.Container
import com.health.app.net.continenceProgress
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.AxisLabels
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.BarChart
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.appContainer
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ProgressState(val weeks: Int = 12, val progress: ContinenceProgress? = null, val loading: Boolean = true, val error: String? = null)

class ProgressViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    init {
        load(12)
    }

    fun load(weeks: Int) {
        _state.update { it.copy(weeks = weeks, loading = true, error = null) }
        viewModelScope.launch {
            try {
                val p = c.ui.continenceProgress(weeks)
                _state.update { it.copy(progress = p, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't work out how things are going") }
            }
        }
    }
}

@Composable
fun ProgressRoute(onBack: () -> Unit) {
    val c = appContainer()
    val vm = viewModel { ProgressViewModel(c) }
    val state by vm.state.collectAsState()
    ProgressScreen(state, onBack, onWeeks = vm::load)
}

/** One number from each week, for the chart: its name, its colour from the data palette, and how to read it. */
private class Series(val label: String, val caption: String, val value: (ProgressWeek) -> Double?)

@Composable
private fun seriesColor(i: Int): Color = when (i % 5) {
    0 -> AppTheme.data.water
    1 -> AppTheme.data.rem
    2 -> AppTheme.data.carbs
    3 -> AppTheme.data.protein
    else -> AppTheme.data.fat
}

private val SERIES = listOf(
    Series("Leaks", "Leaks a day", { it.leaksPerDay }),
    Series("Toilet trips", "Toilet trips a day", { it.voidsPerDay }),
    Series("Products", "Products used a day", { it.productsPerDay }),
    Series("Urgency", "Urgency, 0 to 3", { it.urgency }),
    Series("Night trips", "Trips a night", { it.nightVoidsPerDay }),
    Series("Bowel accidents", "Bowel accidents a day", { it.bowelLeaksPerDay }),
    Series("How full", "How full products were, 0 to 5", { it.fullness }),
    Series("Loss", "Grams lost a day", { it.lossGPerDay }),
    Series("Bother", "How much it bothered you, 0 to 10", { it.bother }),
    Series("Pelvic floor sets", "Pelvic floor sets a week", { w -> w.pelvicFloorSets.toDouble().takeIf { w.days > 0 } }),
)

/**
 * Whether things are getting better: the verdict and its headline, each number before and after,
 * a week-by-week chart, and what went along with it. Says plainly when there isn't enough yet.
 */
@Composable
fun ProgressScreen(state: ProgressState, onBack: () -> Unit, onWeeks: (Int) -> Unit = {}) {
    var chart by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "How it's going")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(8, 12, 26).forEach { w -> FilterChip(selected = state.weeks == w, onClick = { onWeeks(w) }, label = { Text("$w weeks") }) }
            }
            val p = state.progress
            when {
                state.loading && p == null -> Loading()
                state.error != null && p == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = { onWeeks(state.weeks) })
                p != null -> {
                    AppCard(padding = 18.dp) {
                        Pill(p.verdict.label, verdictColor(p.verdict), icon = Icons.AutoMirrored.Rounded.TrendingUp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (ProgressText.enough(p)) p.headline else ProgressText.notEnough(p), style = MaterialTheme.typography.bodyLarge)
                    }
                    val shown = p.metrics.filter { it.before != null || it.after != null }
                    if (shown.isNotEmpty()) {
                        InfoCard("The last ${p.recentWeeks} ${if (p.recentWeeks == 1) "week" else "weeks"} against the ${if (p.recentWeeks == 1) "week" else "weeks"} before") {
                            shown.forEachIndexed { i, m ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                                        contentDescription = "${m.label}: ${ProgressText.value(m.before, m)} before, ${ProgressText.value(m.after, m)} lately, ${ProgressText.verdictWords(m.verdict)}"
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(ProgressText.change(m), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Pill(ProgressText.verdictWords(m.verdict).replaceFirstChar { it.uppercase() }, verdictColor(m.verdict))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Hint("Lower is better for each of these.")
                        }
                    }
                    val logged = p.weeks.filter { it.days > 0 }
                    if (logged.isNotEmpty()) {
                        val available = SERIES.withIndex().filter { (_, s) -> p.weeks.any { s.value(it) != null && s.value(it)!! > 0 } }
                        if (available.isNotEmpty()) {
                            val pick = available.firstOrNull { it.index == chart } ?: available.first()
                            InfoCard("Week by week") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    available.forEach { (i, s) -> FilterChip(selected = pick.index == i, onClick = { chart = i }, label = { Text(s.label) }) }
                                }
                                Spacer(Modifier.height(8.dp))
                                val values = p.weeks.map { pick.value.value(it) }
                                Text(pick.value.caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Column(
                                    Modifier.semantics(mergeDescendants = true) {
                                        contentDescription = "${pick.value.caption}, each week: " + p.weeks.mapNotNull { w ->
                                            pick.value.value(w)?.let { "week of ${Format.shortDay(LocalDate.parse(w.weekStart))} ${ContinenceText.fmt(it)}" }
                                        }.joinToString(", ")
                                    },
                                ) {
                                    BarChart(values, color = seriesColor(pick.index), height = 110.dp, highlightLast = true)
                                    AxisLabels(
                                        Format.shortDay(LocalDate.parse(p.weeks.first().weekStart)), null,
                                        Format.shortDay(LocalDate.parse(p.weeks.last().weekStart)),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Hint("Each bar is a week, from Monday. Weeks with nothing logged are left empty.")
                            }
                        }
                    }
                    if (p.notes.isNotEmpty()) {
                        InfoCard("What went along with it", icon = Icons.Rounded.Lightbulb) {
                            p.notes.forEach { note ->
                                if (note.contains("infection")) InfectionNudge(Modifier.padding(vertical = 4.dp))
                                else Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    Hint("These go together; they don't show what causes what. Your doctor, continence nurse or physio can help make sense of them.", Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}
