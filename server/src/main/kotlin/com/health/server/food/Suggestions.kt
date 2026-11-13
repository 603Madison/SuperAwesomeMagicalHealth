package com.health.server.food

import com.health.api.FoodEntry
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import com.health.api.Meal
import com.health.api.NutritionTarget
import com.health.api.Nutrients
import com.health.server.conditions.ConditionsService
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.insights.DayStats
import com.health.server.insights.InsightRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Food ideas from what the person has eaten before: single foods, and whole meals they've had,
 * ranked for a meal by how often they have them then, how lately, how much of the day's protein
 * gap they'd close, and whether they'd go well over the calories left. Pure, for tests.
 */
object Suggestions {
    /** How far back ideas come from. */
    const val HISTORY_DAYS = 120L

    /** At most this many ideas. */
    const val MAX = 5

    /** The condition whose people see no numbers about food (conditions/Catalog.kt). */
    const val EATING_DISORDER = "eating-disorder"

    /** The meal it is at [time]: breakfast until 10:30, lunch until 3, a snack until 5, dinner until 9:30, then a snack. */
    fun mealAt(time: LocalTime): Meal {
        val minutes = time.hour * 60 + time.minute
        return when {
            minutes < 10 * 60 + 30 -> Meal.BREAKFAST
            minutes < 15 * 60 -> Meal.LUNCH
            minutes < 17 * 60 -> Meal.SNACK
            minutes < 21 * 60 + 30 -> Meal.DINNER
            else -> Meal.SNACK
        }
    }

    /** One thing that could be suggested: a food, or a meal of 2–5 of them. */
    private class Candidate(
        val key: String,
        val items: List<FoodEntry>,
        val isMeal: Boolean,
        /** Separate times it was eaten, and how many of those were at the meal asked about. */
        val times: Int,
        val atMeal: Int,
        val last: LocalDate,
        /** The meal it was last eaten at. */
        val lastMeal: Meal,
    ) {
        val nutrients: Nutrients = items.fold(Nutrients()) { sum, e -> sum + e.nutrients }
        var score = 0.0
    }

    /**
     * The best ideas for [meal] on [date], from [history] (the person's entries, the days before
     * and [date] itself): at most [limit], with at least one whole meal when they've had any.
     * [gap] is what's left of the day's targets (its protein and calories count); with
     * [numbers] false nothing about amounts counts, only habit. No history, no ideas.
     */
    fun rank(history: List<FoodEntry>, date: LocalDate, meal: Meal, gap: Nutrients?, numbers: Boolean = true, limit: Int = MAX): List<FoodSuggestion> {
        val day = date.toString()
        val before = history.filter { it.date < day && it.date >= date.minusDays(HISTORY_DAYS).toString() && usable(it) }
        if (before.isEmpty()) return emptyList()
        val eatenOnDate = history.filter { it.date == day }.map(::keyOf).toSet()
        val candidates = singles(before, meal) + meals(before, meal)
        candidates.forEach { it.score = score(it, date, gap.takeIf { numbers }, eatenOnDate) }
        val ranked = candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.last })
            .distinctBy { if (it.isMeal) it.key else it.items.first().name.trim().lowercase() }
        val top = ranked.take(limit).toMutableList()
        // At least one meal they've had before, when there is one: within the first three, so Today's chips show it.
        if (top.none { it.isMeal }) {
            ranked.firstOrNull { it.isMeal }?.let { m ->
                if (top.size >= limit) top.removeAt(top.lastIndex)
                top.add(min(2, top.size), m)
            }
        }
        return top.map { toSuggestion(it, date, meal) }
    }

    /** A quick add with no name, or something with next to nothing in it (a black coffee), isn't an idea. */
    private fun usable(e: FoodEntry): Boolean =
        !(e.foodId == null && e.name.equals("Quick add", ignoreCase = true)) && (e.nutrients.calories >= 30 || e.nutrients.protein >= 5)

    private fun keyOf(e: FoodEntry): String = listOf(e.name, e.brand.orEmpty(), e.serving).joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }

    private fun singles(entries: List<FoodEntry>, meal: Meal): List<Candidate> =
        entries.groupBy(::keyOf).map { (key, list) ->
            val sorted = list.sortedWith(compareBy<FoodEntry> { it.date }.thenBy { it.loggedAt ?: 0 })
            val occasions = sorted.distinctBy { it.date to it.meal }
            val latest = sorted.last()
            Candidate(key, listOf(latest), false, occasions.size, occasions.count { it.meal == meal }, LocalDate.parse(latest.date), latest.meal)
        }

    /** Each day's meals of 2–5 things, the same mix on different days counted as one. */
    private fun meals(entries: List<FoodEntry>, meal: Meal): List<Candidate> =
        entries.groupBy { it.date to it.meal }.values
            .filter { it.size in 2..5 }
            .groupBy { list -> list.map(::keyOf).sorted().joinToString("+") }
            .map { (key, occasions) ->
                val latest = occasions.maxBy { it.first().date }
                val sorted = latest.sortedWith(compareByDescending<FoodEntry> { it.nutrients.calories }.thenBy { it.loggedAt ?: 0 })
                Candidate(key, sorted, true, occasions.size, occasions.count { it.first().meal == meal }, LocalDate.parse(latest.first().date), latest.first().meal)
            }

    private fun score(c: Candidate, date: LocalDate, gap: Nutrients?, eatenOnDate: Set<String>): Double {
        // Habit: how often at this meal, and overall (each capped).
        val habit = min(c.atMeal, 6) / 6.0 * 0.6 + min(c.times, 10) / 10.0 * 0.4
        val days = ChronoUnit.DAYS.between(c.last, date).coerceAtLeast(0)
        val recency = 1.0 / (1.0 + days / 14.0)
        var s = habit + 0.6 * recency
        if (gap != null) {
            val p = c.nutrients.protein
            val kcal = c.nutrients.calories
            if (gap.protein > 0) s += 1.2 * min(p, gap.protein) / gap.protein
            // Protein for its calories: half the calories from protein counts in full.
            if (kcal > 0) s += 0.4 * min(1.0, p * 4 / kcal / 0.5)
            // Well over what's left of the day's calories.
            val over = kcal - max(gap.calories, 0.0)
            if (over > 150) s -= min(1.5, (over - 150) / 400.0)
        }
        if (c.isMeal) s += 0.1
        // Something already eaten today makes way for something else.
        if (c.items.any { keyOf(it) in eatenOnDate }) s -= 0.3
        return s
    }

    private fun toSuggestion(c: Candidate, date: LocalDate, meal: Meal): FoodSuggestion {
        val items = c.items.map { it.copy(date = date.toString(), meal = meal, id = null, loggedAt = null) }
        val first = items.first()
        return FoodSuggestion(
            name = if (c.isMeal) mealName(items) else first.name,
            brand = if (c.isMeal) null else first.brand,
            serving = if (c.isMeal) "1 meal" else first.serving,
            servings = if (c.isMeal) 1.0 else first.servings,
            nutrients = c.nutrients,
            meal = meal,
            items = items,
            why = why(c, date, meal),
            timesEaten = c.times,
            lastEaten = c.last.toString(),
        )
    }

    /** "Chicken stir-fry and rice", "Salmon, potatoes and peas", "Pasta, salad and 2 more". */
    fun mealName(items: List<FoodEntry>): String {
        val names = items.map { it.name.trim() }
        val rest = names.drop(1).map(::lowerFirst)
        return when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${rest[0]}"
            3 -> "${names[0]}, ${rest[0]} and ${rest[1]}"
            else -> "${names[0]}, ${rest[0]} and ${names.size - 2} more"
        }
    }

    /** Plain words, never an amount. */
    private fun why(c: Candidate, date: LocalDate, meal: Meal): String {
        val at = if (meal == Meal.SNACK) "as a snack" else "at ${mealWord(meal)}"
        return when {
            c.atMeal >= 3 || (c.isMeal && c.atMeal >= 2) -> "You often have this $at"
            !c.isMeal && c.times >= 4 -> "One of your regulars"
            c.isMeal -> "You had this for ${mealWord(c.lastMeal)} ${dayRef(c.last, date, on = true)}"
            else -> "You had this ${dayRef(c.last, date, on = true)}"
        }
    }

    private fun mealWord(meal: Meal) = when (meal) {
        Meal.BREAKFAST -> "breakfast"
        Meal.LUNCH -> "lunch"
        Meal.DINNER -> "dinner"
        Meal.SNACK -> "a snack"
    }

    /** "yesterday", "on Tuesday" (or "Tuesday"), "last week", "in October": never a number. */
    fun dayRef(day: LocalDate, date: LocalDate, on: Boolean): String {
        val days = ChronoUnit.DAYS.between(day, date)
        return when {
            days <= 0 -> "earlier today"
            days == 1L -> "yesterday"
            days < 7 -> (if (on) "on " else "") + day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
            days < 14 -> "last week"
            day.year == date.year || day.month > date.month -> "in " + day.month.getDisplayName(TextStyle.FULL, Locale.UK)
            else -> "a while ago"
        }
    }

    /**
     * The protein insight's sentence, naming the first one or two [ideas]: "For dinner, the chicken
     * stir-fry you had Tuesday (38 g protein) or Greek yoghurt (17 g) would close a good part of it."
     */
    fun proteinSentence(ideas: List<FoodSuggestion>, gap: Double, date: LocalDate): String? {
        val two = ideas.take(2).takeIf { it.isNotEmpty() } ?: return null
        val parts = two.mapIndexed { i, s ->
            val grams = "${s.nutrients.protein.roundToInt()} g${if (i == 0) " protein" else ""}"
            val what = if (s.items.size > 1) {
                "the ${lowerFirst(s.name)} you had ${s.lastEaten?.let { dayRef(LocalDate.parse(it), date, on = false) } ?: "before"}"
            } else {
                lowerFirst(s.name)
            }
            "$what ($grams)"
        }
        val best = two.maxOf { it.nutrients.protein }
        val effect = when {
            best >= gap -> "would cover it"
            best >= gap * 0.3 -> "would close a good part of it"
            else -> "would help"
        }
        val meal = when (two.first().meal) {
            Meal.SNACK -> "For a snack"
            else -> "For ${two.first().meal.label.lowercase()}"
        }
        return "$meal, ${parts.joinToString(" or ")} $effect."
    }

    /** Words that keep their capital at the start of a food's name. */
    private val PROPER = setOf(
        "greek", "thai", "indian", "italian", "mexican", "chinese", "japanese", "french", "english", "spanish", "turkish", "korean",
        "vietnamese", "caesar", "cheddar", "parmesan", "swiss", "dutch", "scottish", "irish", "welsh", "american", "cajun", "moroccan",
        "lebanese", "persian", "polish", "german", "danish", "belgian", "hawaiian", "cornish", "yorkshire", "bombay", "brie", "camembert",
    )

    /** "Chicken stir-fry" → "chicken stir-fry", but "Greek yoghurt" and "BLT" stay as they are. */
    fun lowerFirst(name: String): String {
        val text = name.trim()
        val first = text.substringBefore(' ').trimEnd(',').lowercase()
        if (text.length < 2 || !text[1].isLowerCase() || first in PROPER) return text
        return text[0].lowercase() + text.substring(1)
    }

    /** What's left of the day's targets, never below nothing. */
    fun gap(target: NutritionTarget, eaten: Nutrients): Nutrients = Nutrients(
        calories = max(0.0, target.calories - eaten.calories),
        protein = max(0.0, target.protein - eaten.protein),
        carbs = max(0.0, target.carbs - eaten.carbs),
        fat = max(0.0, target.fat - eaten.fat),
    )
}

/** Food ideas for a person: for the Food page, and for Today's protein insight. */
class SuggestionService(
    private val foods: FoodStore,
    private val days: DayService,
    private val profiles: ProfileStore,
    private val conditions: ConditionsService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Whether numbers about food show at all: not for people in eating disorder recovery. */
    suspend fun numbers(userId: String): Boolean = conditions.get(userId).conditions.none { it.id == Suggestions.EATING_DISORDER }

    /** Ideas for [meal] (the one it is now, without) on [date]. */
    suspend fun suggestions(userId: String, date: LocalDate, meal: Meal?): FoodSuggestions {
        val zone = profiles.days(userId).zone
        val chosen = meal ?: Suggestions.mealAt(timeNow(zone))
        val numbers = numbers(userId)
        val history = foods.entries(userId, date.minusDays(Suggestions.HISTORY_DAYS), date)
        val gap = if (numbers) {
            val (target, _) = days.nutritionFor(userId, date)
            Suggestions.gap(target, history.filter { it.date == date.toString() }.fold(Nutrients()) { sum, e -> sum + e.nutrients })
        } else {
            null
        }
        return FoodSuggestions(chosen, gap, Suggestions.rank(history, date, chosen, gap, numbers), numbers)
    }

    /**
     * Today's food for the insights: whether numbers show, and (only for today, only when the
     * protein insight shows) up to three ideas for the meal it is now.
     */
    suspend fun forInsights(userId: String, date: LocalDate, isToday: Boolean, today: DayStats, target: NutritionTarget): InsightRules.Food {
        val numbers = numbers(userId)
        if (!numbers || !isToday) return InsightRules.Food(numbers)
        val zone = profiles.days(userId).zone
        val gap = InsightRules.proteinGap(today, target, isToday = true, hourNow = timeNow(zone).hour) ?: return InsightRules.Food(numbers)
        val history = foods.entries(userId, date.minusDays(Suggestions.HISTORY_DAYS), date)
        val left = Suggestions.gap(target, today.nutrition ?: Nutrients()).copy(protein = gap)
        return InsightRules.Food(numbers, Suggestions.rank(history, date, Suggestions.mealAt(timeNow(zone)), left, limit = 3))
    }

    private fun timeNow(zone: ZoneId): LocalTime = Instant.ofEpochMilli(clock()).atZone(zone).toLocalTime()
}
