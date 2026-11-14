package com.health.app.ui.food

import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.MealieConnect
import com.health.api.MealiePlan
import com.health.api.MealiePlanEntry
import com.health.api.MealieRecipe
import com.health.api.MealieRecipes
import com.health.api.MealieStatus
import com.health.api.Nutrients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Mealie's words and choices: the plan's rows, the search's group, a recipe's yield and the status in Settings. */
class MealieTextTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private fun recipe(slug: String, name: String, kcal: Double? = null, servings: Double? = null) = MealieRecipe(
        slug, name,
        food = kcal?.let { Food(name, Nutrients(it, 20.0, 40.0, 10.0), source = FoodSource.MEALIE, ref = "mealie:id-$slug", link = "http://mealie.home:9000/g/home/r/$slug") },
        servings = servings,
        link = "http://mealie.home:9000/g/home/r/$slug",
    )

    private val soup = recipe("lentil-soup", "Lentil soup", 1320.0, servings = 4.0)
    private val toast = recipe("plain-toast", "Plain toast")
    private val wrap = recipe("chicken-wrap", "Chicken wrap", 520.0)

    private val plan = MealiePlan(
        "2026-11-14",
        listOf(
            MealiePlanEntry(Meal.BREAKFAST, "Plain toast", recipe = toast),
            MealiePlanEntry(Meal.LUNCH, "Chicken wrap", recipe = wrap, logged = true),
            MealiePlanEntry(Meal.DINNER, "Lentil soup", recipe = soup, note = "Double it for tomorrow"),
            MealiePlanEntry(Meal.SNACK, "Fruit", note = "Whatever's in the bowl"),
            MealiePlanEntry(Meal.SNACK, "Leftovers"),
        ),
    )

    @Test
    fun `each planned thing is a row with its meal, what's known, and what it offers`() {
        val rows = MealieText.rows(plan)
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.index })
        // No nutrition: said plainly, nothing to add.
        assertEquals("Breakfast · No nutrition in Mealie yet", rows[0].detail)
        assertEquals(PlanAction.NONE, rows[0].action)
        assertNull(rows[0].food)
        // Logged already: a check, whatever it holds.
        assertEquals("Lunch · 520 kcal", rows[1].detail)
        assertEquals(PlanAction.LOGGED, rows[1].action)
        // To add: one serving of the recipe, with its note.
        assertEquals("Dinner · 1,320 kcal · Double it for tomorrow", rows[2].detail)
        assertEquals(PlanAction.ADD, rows[2].action)
        assertEquals(soup.food, rows[2].food)
        assertEquals(Meal.DINNER, rows[2].meal)
        // Notes: the note, never a button.
        assertEquals("Snacks · Whatever's in the bowl", rows[3].detail)
        assertEquals(PlanAction.NONE, rows[3].action)
        assertEquals("Leftovers", rows[4].title)
        assertEquals("Snacks", rows[4].detail)
        assertEquals(PlanAction.NONE, rows[4].action)
    }

    @Test
    fun `without numbers, no calories, and recipes still add`() {
        val rows = MealieText.rows(plan, numbers = false)
        assertTrue(rows.none { "kcal" in it.detail })
        assertEquals("Lunch", rows[1].detail)
        assertEquals("Dinner · Double it for tomorrow", rows[2].detail)
        assertEquals(PlanAction.ADD, rows[2].action)
        assertEquals("Breakfast · No nutrition in Mealie yet", rows[0].detail)
    }

    @Test
    fun `the card shows once the plan has something in it or a problem, and one tap marks it logged`() {
        assertFalse(MealieText.shown(null))
        assertFalse(MealieText.shown(MealiePlan("2026-11-14")))
        assertTrue(MealieText.shown(plan))
        assertTrue(MealieText.shown(MealiePlan("2026-11-14", problem = "Mealie didn't answer at mealie.home")))
        val marked = MealieText.logged(plan, 2)
        assertTrue(marked.entries[2].logged)
        assertEquals(PlanAction.LOGGED, MealieText.rows(marked)[2].action)
        assertEquals(plan.entries.filterIndexed { i, _ -> i != 2 }, marked.entries.filterIndexed { i, _ -> i != 2 })
        // A note the same as the title isn't said twice.
        assertEquals("Snacks", MealieText.rows(MealiePlan("d", listOf(MealiePlanEntry(Meal.SNACK, "Fruit", note = "Fruit"))))[0].detail)
    }

    @Test
    fun `the search's Mealie group shows only when it's asked, with one quiet line`() {
        val found = MealieRecipes(listOf(soup, toast))
        // Not connected (or viewing a share), or too little typed: no group.
        assertNull(MealieText.group(false, "soup", found, false))
        assertNull(MealieText.group(true, "s", found, false))
        assertNull(MealieText.group(true, " s ", null, true))
        // Asked, not back yet.
        assertEquals(MealieGroup(emptyList(), MealieText.LOOKING), MealieText.group(true, "so", null, true))
        assertNull(MealieText.group(true, "so", null, false))
        // Found, in Mealie's order, with or without nutrition.
        assertEquals(MealieGroup(listOf(soup, toast)), MealieText.group(true, "soup", found, false))
        // Nothing, or Mealie's problem, quietly.
        assertEquals(MealieGroup(emptyList(), MealieText.NOTHING), MealieText.group(true, "cake", MealieRecipes(), false))
        val problem = "Mealie didn't answer at mealie.home. Check the address, and that this server can reach it"
        assertEquals(MealieGroup(emptyList(), problem), MealieText.group(true, "cake", MealieRecipes(problem = problem), false))
    }

    @Test
    fun `the person's copy of a recipe Mealie shows isn't listed twice`() {
        val group = MealieText.group(true, "soup", MealieRecipes(listOf(soup, toast)), false)
        val myCopy = soup.food!!.copy(id = "f9", uses = 3, link = null)
        val oats = Food("Porridge oats", Nutrients(150.0, 5.0, 27.0, 3.0), "40 g", id = "f1")
        val otherRecipe = Food("Lentil soup (old)", Nutrients(300.0), source = FoodSource.MEALIE, ref = "mealie:id-other", id = "f2")
        assertEquals(listOf(oats, otherRecipe), MealieText.mine(listOf(myCopy, oats, otherRecipe), group))
        // Without the group (or before it's back), the copy stays.
        assertEquals(listOf(myCopy, oats), MealieText.mine(listOf(myCopy, oats), null))
        assertEquals(listOf(myCopy, oats), MealieText.mine(listOf(myCopy, oats), MealieGroup(emptyList(), MealieText.LOOKING)))
    }

    @Test
    fun `a recipe's yield, where Mealie says`() {
        val recipes = listOf(soup, wrap)
        assertEquals(4.0, MealieText.makes(soup.food!!, recipes)!!, 0.0)
        // The person's copy is the same recipe.
        assertEquals(4.0, MealieText.makes(soup.food!!.copy(id = "f9", link = null), recipes)!!, 0.0)
        assertNull(MealieText.makes(wrap.food!!, recipes))
        assertNull(MealieText.makes(soup.food!!, emptyList()))
        assertNull(MealieText.makes(Food("Porridge oats", Nutrients(150.0), "40 g"), recipes))

        assertEquals("1 serving (the recipe makes 4)", MealieText.servingLine(soup.food!!, 4.0))
        assertEquals("1 serving (the recipe makes 2.5)", MealieText.servingLine(soup.food!!, 2.5))
        assertEquals("1 serving (the whole recipe)", MealieText.servingLine(soup.food!!, 1.0))
        assertEquals("1 serving = 1 serving", MealieText.servingLine(wrap.food!!, null))
        // Any other food says what a serving is, as it always has.
        assertEquals("1 serving = 1 bowl (40 g)", MealieText.servingLine(Food("Porridge oats", Nutrients(150.0), "1 bowl", servingGrams = 40.0), 4.0))
        assertEquals("1 serving = 1 slice", MealieText.servingLine(Food("Toast", Nutrients(80.0), "1 slice"), null))
    }

    @Test
    fun `the status in Settings, and what Connect sends`() {
        val connected = MealieStatus(true, "http://mealie.home:9000", "Sam", "v3.1.0")
        assertEquals("Connected to http://mealie.home:9000 as Sam", MealieText.connected(connected))
        assertEquals("Connected to http://mealie.home:9000", MealieText.connected(connected.copy(user = " ")))
        assertEquals("Mealie 3.1.0", MealieText.version(connected))
        assertEquals("Mealie 2.8", MealieText.version(connected.copy(version = "2.8")))
        assertNull(MealieText.version(connected.copy(version = null)))
        assertEquals("Connected to mealie.home:9000", MealieText.row(connected))
        assertEquals("Your recipes and meal plan in Food", MealieText.row(MealieStatus()))
        assertEquals("Your recipes and meal plan in Food", MealieText.row(null))

        // The first time: the address and a token, both needed.
        assertEquals(MealieConnect("mealie.home:9000", "tok"), MealieText.request(MealieStatus(), MealieEdit.NONE, " mealie.home:9000 ", " tok "))
        assertNull(MealieText.request(MealieStatus(), MealieEdit.NONE, "mealie.home:9000", " "))
        assertNull(MealieText.request(null, MealieEdit.NONE, "", "tok"))
        // A new address keeps the token; a new token keeps the address.
        assertEquals(MealieConnect("https://food.example.org"), MealieText.request(connected, MealieEdit.ADDRESS, "https://food.example.org", "ignored"))
        assertNull(MealieText.request(connected, MealieEdit.ADDRESS, "  ", ""))
        assertEquals(MealieConnect("http://mealie.home:9000", "new"), MealieText.request(connected, MealieEdit.TOKEN, "", "new"))
        assertNull(MealieText.request(connected, MealieEdit.TOKEN, "", ""))
        assertNull(MealieText.request(connected, MealieEdit.NONE, "x", "y"))
    }

    @Test
    fun `the Food tab's Meal plan section comes after the ideas, while Mealie is connected`() {
        // Its id is saved with the person's arrangement: it stays "mealplan".
        assertEquals("mealplan", FoodSections.MEALPLAN)
        assertEquals(
            listOf("ideas", "mealplan", "totals", "breakfast", "lunch", "dinner", "snack", "water", "drinks"),
            FoodSections.items(water = true, mealPlan = true).map { it.id },
        )
        assertEquals(listOf("mealplan", "totals"), FoodSections.items(water = true, ideas = false, mealPlan = true).map { it.id }.take(2))
        assertFalse(FoodSections.items(water = true).any { it.id == FoodSections.MEALPLAN })
        // Someone who arranged Food before keeps their order; the new section goes after the ideas.
        val saved = com.health.api.ScreenLayout(listOf("dinner", "ideas", "totals", "breakfast", "lunch", "snack", "water", "drinks"))
        assertEquals(
            listOf("dinner", "ideas", "mealplan", "totals"),
            com.health.api.Layouts.arrange(FoodSections.items(water = true, mealPlan = true), { it.id }, saved).map { it.id }.take(4),
        )
    }

    @Test
    fun `a problem in plain words`() {
        assertEquals("Mealie didn't accept the token", MealieText.problem(Exception("Mealie didn't accept the token")))
        assertEquals(MealieText.UNREACHABLE, MealieText.problem(Exception()))
        assertEquals(MealieText.UNREACHABLE, MealieText.problem(Exception(" ")))
    }
}
