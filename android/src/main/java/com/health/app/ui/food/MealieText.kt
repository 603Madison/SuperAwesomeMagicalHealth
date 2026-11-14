package com.health.app.ui.food

import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.MealieConnect
import com.health.api.MealiePlan
import com.health.api.MealieRecipe
import com.health.api.MealieRecipes
import com.health.api.MealieStatus
import com.health.app.ui.Format

/** What a planned thing on the Meal plan card offers: one tap to add it, a check that it's logged, or nothing (a note, or no nutrition). */
enum class PlanAction { ADD, LOGGED, NONE }

/**
 * One thing planned in Mealie, as the Food tab's Meal plan card shows it: [index] is its place in
 * the plan; [detail] is the meal and what's known ("Dinner · 320 kcal"); [food] is what Add logs
 * (one serving of the recipe).
 */
data class PlanRow(
    val index: Int,
    val meal: Meal,
    val title: String,
    val detail: String,
    val action: PlanAction,
    val food: Food? = null,
)

/** The add-food search's "Your Mealie" group: recipes in Mealie's order, and one quiet [line] (still looking, nothing found, or why not). */
data class MealieGroup(val recipes: List<MealieRecipe>, val line: String? = null)

/** Which part of a connected Mealie is being changed in Settings: nothing, its address, or its token. */
enum class MealieEdit { NONE, ADDRESS, TOKEN }

/** Mealie's words and choices in plain Kotlin: Settings' page, the search's group and the Meal plan card (the screens are in MealieUi.kt and MealieSettings.kt). */
object MealieText {
    const val GROUP = "Your Mealie"
    const val INTRO = "Bring your Mealie recipes and meal plan into Food. Your recipes' nutrition comes along, per serving."
    const val ADDRESS_HINT = "http://mealie.home:9000"
    const val TOKEN_HINT = "From Mealie: your profile, then API tokens"
    const val NO_NUTRITION = "No nutrition in Mealie yet"
    const val LOOKING = "Looking in Mealie…"
    const val NOTHING = "Nothing in Mealie for that."
    const val UNREACHABLE = "Mealie isn't answering just now."

    /** The search asks Mealie from this many characters (as the server does). */
    const val MIN_QUERY = 2

    // ---- Settings

    /** "Connected to http://mealie.home:9000 as Sam". */
    fun connected(status: MealieStatus): String =
        "Connected to ${status.url.orEmpty()}" + (status.user?.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")

    /** "Mealie 3.1.0", when Mealie said. */
    fun version(status: MealieStatus): String? = status.version?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }?.let { "Mealie $it" }

    /** Settings' row: where it's connected, or what it's for. */
    fun row(status: MealieStatus?): String =
        saved(status)?.let { "Connected to ${it.substringAfter("://")}" } ?: "Your recipes and meal plan in Food"

    /** The address Mealie is connected at, or null when it isn't. */
    private fun saved(status: MealieStatus?): String? = status?.takeIf { it.connected }?.url?.takeIf { it.isNotBlank() }

    /**
     * What Connect sends, or null until it's filled in: the address and a token the first time;
     * then the new address (keeping the token), or a new token for the address it has.
     */
    fun request(status: MealieStatus?, edit: MealieEdit, url: String, token: String): MealieConnect? {
        val address = url.trim()
        val key = token.trim()
        val saved = saved(status)
        return when {
            saved == null -> if (address.isNotEmpty() && key.isNotEmpty()) MealieConnect(address, key) else null
            edit == MealieEdit.ADDRESS -> if (address.isNotEmpty()) MealieConnect(address) else null
            edit == MealieEdit.TOKEN -> if (key.isNotEmpty()) MealieConnect(saved, key) else null
            else -> null
        }
    }

    // ---- Finding a food

    /**
     * The "Your Mealie" group for [query], or null when Mealie isn't asked ([on] is false: not
     * connected, or viewing a share; or the query is too short).
     */
    fun group(on: Boolean, query: String, result: MealieRecipes?, searching: Boolean): MealieGroup? {
        if (!on || query.trim().length < MIN_QUERY) return null
        return when {
            result == null -> if (searching) MealieGroup(emptyList(), LOOKING) else null
            result.problem != null -> MealieGroup(result.items, result.problem)
            result.items.isEmpty() -> MealieGroup(emptyList(), NOTHING)
            else -> MealieGroup(result.items)
        }
    }

    /** "Your foods", less the person's own copies of recipes the Mealie group shows (with Mealie's numbers as they are now). */
    fun mine(mine: List<Food>, group: MealieGroup?): List<Food> {
        val refs = group?.recipes.orEmpty().mapNotNull { it.food?.ref }.toSet()
        if (refs.isEmpty()) return mine
        return mine.filterNot { it.source == FoodSource.MEALIE && it.ref in refs }
    }

    /** How many servings the recipe behind [food] makes, when it's a Mealie recipe among [recipes] and Mealie says. */
    fun makes(food: Food, recipes: List<MealieRecipe>): Double? {
        if (food.source != FoodSource.MEALIE || food.ref == null) return null
        return recipes.firstOrNull { it.food?.ref == food.ref }?.servings?.takeIf { it > 0 }
    }

    /** The servings dialog's line: "1 serving (the recipe makes 4)" for a recipe, else what a serving is ("1 serving = 1 cup (240 g)"). */
    fun servingLine(food: Food, makes: Double?): String = when {
        food.source == FoodSource.MEALIE && makes != null && makes > 0 ->
            if (makes == 1.0) "1 serving (the whole recipe)" else "1 serving (the recipe makes ${Format.compact(makes)})"
        else -> "1 serving = ${food.serving}${food.servingGrams?.let { " (${Format.compact(it)} g)" } ?: ""}"
    }

    // ---- The Meal plan card

    /** Whether the card shows: once the plan has come, with something planned, or why it couldn't come. */
    fun shown(plan: MealiePlan?): Boolean = plan != null && (plan.entries.isNotEmpty() || plan.problem != null)

    /**
     * The plan's rows, in its order. Without [numbers] (eating disorder recovery), no calories:
     * a recipe is just its meal, and still adds with one tap.
     */
    fun rows(plan: MealiePlan, numbers: Boolean = true): List<PlanRow> = plan.entries.mapIndexed { i, e ->
        val food = e.recipe?.food
        val what = when {
            e.recipe != null && food == null -> NO_NUTRITION
            food != null && numbers -> "${Format.number(food.nutrients.calories)} kcal"
            else -> null
        }
        val action = when {
            e.logged -> PlanAction.LOGGED
            food != null -> PlanAction.ADD
            else -> PlanAction.NONE
        }
        val note = e.note?.trim()?.takeIf { it.isNotEmpty() && it != e.title.trim() }
        PlanRow(i, e.meal, e.title, listOfNotNull(e.meal.label, what, note).joinToString(" · "), action, food)
    }

    /** The plan with one entry marked as logged (after a tap on Add, before the day reloads). */
    fun logged(plan: MealiePlan, index: Int): MealiePlan =
        plan.copy(entries = plan.entries.mapIndexed { i, e -> if (i == index) e.copy(logged = true) else e })

    /** Why the plan or a search couldn't come, in plain words. */
    fun problem(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: UNREACHABLE
}
