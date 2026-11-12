package com.health.app.ui.care

import com.health.api.Contraction
import com.health.api.LifeStage
import com.health.api.Screener
import com.health.api.ScreenerDefinition

/** Contractions over the last hour, as the timer shows them while they happen. */
data class ContractionSummary(
    val count: Int,
    /** Start to start, on average. */
    val averageIntervalMs: Long?,
    val averageDurationMs: Long?,
    /** About 5 minutes apart, lasting about a minute, for an hour. */
    val fiveOneOne: Boolean,
)

object StageMath {
    private const val MINUTE = 60_000L

    /**
     * The last hour's finished contractions: how far apart and how long. The 5-1-1 pattern is
     * contractions no more than about 5 minutes apart, lasting about a minute, for an hour.
     */
    fun contractions(list: List<Contraction>, now: Long): ContractionSummary {
        val hour = list.filter { it.end != null && it.start >= now - 60 * MINUTE && it.start <= now }.sortedBy { it.start }
        if (hour.isEmpty()) return ContractionSummary(0, null, null, false)
        val durations = hour.map { it.end!! - it.start }
        val intervals = hour.zipWithNext { a, b -> b.start - a.start }
        val interval = intervals.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val duration = durations.average().toLong()
        // An hour's worth: the first in the window started 50 minutes or more ago.
        val spansHour = now - hour.first().start >= 50 * MINUTE
        val pattern = hour.size >= 6 && spansHour && interval != null && interval <= 5 * MINUTE + 30_000 && duration >= 50_000
        return ContractionSummary(hour.size, interval, duration, pattern)
    }

    /** Whether the EPDS check-in is offered: while pregnant or after a birth. */
    fun offersEpds(stage: LifeStage?): Boolean = stage == LifeStage.PREGNANT || stage == LifeStage.POSTPARTUM

    /** The screeners to offer in Mind: the EPDS only while pregnant or after a birth. */
    fun screenersFor(stage: LifeStage?): List<Screener> = Screener.entries.filter { it != Screener.EPDS || offersEpds(stage) }

    /** The answers a question offers: its own (the EPDS), or the screener's shared ones. */
    fun optionsFor(definition: ScreenerDefinition, item: Int): List<String> =
        definition.itemOptions?.getOrNull(item)?.takeIf { it.isNotEmpty() } ?: definition.options

    /** The highest score possible: some questions count in reverse, but each tops out at its highest answer. */
    fun maxScore(definition: ScreenerDefinition): Int = definition.items.indices.sumOf { i ->
        definition.itemScores?.getOrNull(i)?.maxOrNull() ?: (optionsFor(definition, i).size - 1).coerceAtLeast(0)
    }

    /** Whether every question has an answer the question offers. */
    fun complete(definition: ScreenerDefinition, answers: List<Int?>): Boolean =
        answers.size == definition.items.size && answers.withIndex().all { (i, a) -> a != null && a in optionsFor(definition, i).indices }
}
