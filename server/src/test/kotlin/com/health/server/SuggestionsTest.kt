package com.health.server

import com.health.api.AppPage
import com.health.api.FoodEntry
import com.health.api.FoodEntryInput
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import com.health.api.Goals
import com.health.api.Meal
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.NutritionTarget
import com.health.api.Nutrients
import com.health.api.Profile
import com.health.api.Score
import com.health.api.SleepSummary
import com.health.api.ApiJson
import com.health.api.toInput
import com.health.server.food.Suggestions
import com.health.server.insights.DayStats
import com.health.server.insights.InsightRules
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class SuggestionsTest {
    /** Friday 13 November 2026; Tuesday was the 10th. */
    private val today = LocalDate.of(2026, 11, 13)

    private fun entry(date: String, meal: Meal, name: String, kcal: Double, protein: Double, serving: String = "1 serving", servings: Double = 1.0) =
        FoodEntry(date, meal, name, Nutrients(calories = kcal, protein = protein), servings, serving, foodId = "food-" + name.lowercase().replace(' ', '-'), id = "e-$date-$name")

    private val history = listOf(
        // Tuesday's dinner: two things, so a meal of its own.
        entry("2026-11-10", Meal.DINNER, "Chicken stir-fry", 450.0, 32.0),
        entry("2026-11-10", Meal.DINNER, "Rice", 200.0, 6.0),
        // A snack most days.
        entry("2026-11-12", Meal.SNACK, "Greek yoghurt", 150.0, 17.0, "1 pot"),
        entry("2026-11-11", Meal.SNACK, "Greek yoghurt", 150.0, 17.0, "1 pot"),
        entry("2026-11-09", Meal.SNACK, "Greek yoghurt", 150.0, 17.0, "1 pot"),
        // Breakfast, nearly every day.
        *(3..12).map { entry("2026-11-%02d".format(it), Meal.BREAKFAST, "Porridge", 300.0, 10.0) }.toTypedArray(),
        entry("2026-10-20", Meal.DINNER, "Crisps", 300.0, 2.0),
        entry("2026-10-21", Meal.DINNER, "Crisps", 300.0, 2.0),
        // Not ideas: a quick add with no name, and a black coffee.
        FoodEntry("2026-11-12", Meal.LUNCH, "Quick add", Nutrients(calories = 500.0)),
        entry("2026-11-12", Meal.BREAKFAST, "Black coffee", 2.0, 0.3),
    )

    @Test
    fun `the meal follows the time of day`() {
        fun at(h: Int, m: Int) = Suggestions.mealAt(LocalTime.of(h, m))
        assertEquals(Meal.BREAKFAST, at(7, 0))
        assertEquals(Meal.BREAKFAST, at(10, 29))
        assertEquals(Meal.LUNCH, at(10, 30))
        assertEquals(Meal.LUNCH, at(14, 59))
        assertEquals(Meal.SNACK, at(15, 0))
        assertEquals(Meal.SNACK, at(16, 59))
        assertEquals(Meal.DINNER, at(17, 0))
        assertEquals(Meal.DINNER, at(21, 29))
        assertEquals(Meal.SNACK, at(21, 30))
        assertEquals(Meal.SNACK, at(23, 45))
    }

    @Test
    fun `no history gives no ideas`() {
        assertTrue(Suggestions.rank(emptyList(), today, Meal.DINNER, Nutrients(calories = 800.0, protein = 40.0)).isEmpty())
        // Only what's been eaten today, or long ago, isn't history to suggest from.
        val todayOnly = listOf(entry(today.toString(), Meal.LUNCH, "Soup", 300.0, 12.0), entry("2026-06-01", Meal.DINNER, "Lasagne", 700.0, 30.0))
        assertTrue(Suggestions.rank(todayOnly, today, Meal.DINNER, null).isEmpty())
    }

    @Test
    fun `past meals are offered, ready to log, and habit at the meal counts`() {
        val ideas = Suggestions.rank(history, today, Meal.DINNER, Nutrients(calories = 900.0, protein = 40.0))
        assertTrue(ideas.size in 3..5)
        val meal = ideas.take(3).firstOrNull { it.items.size > 1 }
        assertNotNull("a meal they've had is among the first three", meal)
        assertEquals("Chicken stir-fry and rice", meal!!.name)
        assertEquals(38.0, meal.nutrients.protein, 0.01)
        assertEquals("You had this for dinner on Tuesday", meal.why)
        assertEquals("2026-11-10", meal.lastEaten)
        // Ready to log on the day asked about, at the meal asked about.
        assertTrue(meal.items.all { it.date == today.toString() && it.meal == Meal.DINNER && it.id == null })
        val input = meal.items.first().toInput(today.toString(), Meal.DINNER)
        assertEquals("food-chicken-stir-fry", input.food!!.id)
        assertEquals(450.0, input.food!!.nutrients.calories, 0.01)
        // Quick adds without a name and black coffee aren't ideas.
        assertTrue(ideas.none { it.name == "Quick add" || it.name == "Black coffee" })

        // For breakfast, the porridge they have every morning comes first, said plainly.
        val breakfast = Suggestions.rank(history, today, Meal.BREAKFAST, null, numbers = false)
        assertEquals("Porridge", breakfast.first().name)
        assertEquals("You often have this at breakfast", breakfast.first().why)
        assertEquals(10, breakfast.first().timesEaten)
    }

    @Test
    fun `the protein gap and the calories left steer the order`() {
        val dinner = listOf(
            entry("2026-11-11", Meal.DINNER, "Crisps", 300.0, 2.0),
            entry("2026-11-11", Meal.LUNCH, "Chicken breast", 250.0, 35.0),
            entry("2026-11-11", Meal.BREAKFAST, "Pizza", 1400.0, 40.0),
            entry("2026-11-11", Meal.SNACK, "Toast", 200.0, 5.0),
        )
        // What closes the protein gap comes first.
        val ideas = Suggestions.rank(dinner, today, Meal.LUNCH, Nutrients(calories = 2000.0, protein = 40.0))
        assertEquals("Chicken breast", ideas.first().name)
        val names = { left: Double -> Suggestions.rank(dinner.filter { it.name != "Chicken breast" }, today, Meal.SNACK, Nutrients(calories = left, protein = 40.0)).map { it.name } }
        // Plenty of calories left: a big pizza closes the gap.
        assertTrue(names(2000.0).indexOf("Pizza") < names(2000.0).indexOf("Toast"))
        // Not many left: going well over them puts it behind.
        assertTrue(names(200.0).indexOf("Pizza") > names(200.0).indexOf("Toast"))
    }

    @Test
    fun `in recovery, ideas come from habit alone and why never names an amount`() {
        val ideas = Suggestions.rank(history, today, Meal.DINNER, null, numbers = false)
        assertTrue(ideas.isNotEmpty())
        ideas.forEach { assertFalse(it.why, Regex("""\d|kcal|calorie|protein|gram""", RegexOption.IGNORE_CASE).containsMatchIn(it.why)) }
        // Older ones are said without dates.
        val old = Suggestions.rank(listOf(entry("2026-09-20", Meal.DINNER, "Fish pie", 600.0, 30.0)), today, Meal.LUNCH, null, numbers = false)
        assertEquals("You had this in September", old.single().why)
    }

    private val chicken = FoodSuggestion(
        "Chicken stir-fry and rice", null, "1 meal", 1.0, Nutrients(650.0, 38.0), Meal.DINNER,
        listOf(entry("2026-11-13", Meal.DINNER, "Chicken stir-fry", 450.0, 32.0), entry("2026-11-13", Meal.DINNER, "Rice", 200.0, 6.0)),
        "You had this for dinner on Tuesday", 1, "2026-11-10",
    )
    private val yoghurt = FoodSuggestion(
        "Greek yoghurt", null, "1 pot", 1.0, Nutrients(150.0, 17.0), Meal.DINNER,
        listOf(entry("2026-11-13", Meal.DINNER, "Greek yoghurt", 150.0, 17.0, "1 pot")), "One of your regulars", 4, "2026-11-12",
    )

    private fun context(nutrition: Nutrients, food: InsightRules.Food, hour: Int = 20, history: List<DayStats> = emptyList(), steps: Int? = null) = InsightRules.Context(
        today = DayStats(date = today.toString(), nutrition = nutrition, waterMl = 300, steps = steps, hasData = true, sleep = history.firstOrNull()?.sleep?.let { night(300) }),
        history = history, profile = Profile(timezone = "UTC", goals = Goals(steps = 8000)),
        target = NutritionTarget(2000, 100, 250, 60, 2000, "Your goal"),
        readiness = Score(52, "Pay attention", emptyList()), sleep = null,
        isToday = true, hourNow = hour, restingCalories = null, food = food,
    )

    private fun night(minutes: Int) = SleepSummary(0, minutes * 60_000L, minutes, minutes, 100, 60, 60, minutes - 120, 0, true)

    @Test
    fun `the protein insight names the top ideas, and keeps its plain sentence without any`() {
        val with = InsightRules.insights(context(Nutrients(calories = 1200.0, protein = 40.0), InsightRules.Food(true, listOf(chicken, yoghurt))))
        val protein = with.single { it.icon == "egg" }
        assertEquals("Protein 60 g under target", protein.title)
        assertEquals("For dinner, the chicken stir-fry and rice you had Tuesday (38 g protein) or Greek yoghurt (17 g) would close a good part of it.", protein.body)
        assertEquals(AppPage.FOOD, protein.link)
        assertEquals(listOf("Chicken stir-fry and rice", "Greek yoghurt"), protein.suggestions.map { it.name })

        val without = InsightRules.insights(context(Nutrients(calories = 1200.0, protein = 40.0), InsightRules.Food())).single { it.icon == "egg" }
        assertEquals("Protein helps you recover and keeps you full: eggs, yoghurt, beans, tofu, fish or chicken.", without.body)
        assertTrue(without.suggestions.isEmpty())

        // A single idea that covers it all.
        assertEquals("For dinner, the chicken stir-fry and rice you had Tuesday (38 g protein) would cover it.", Suggestions.proteinSentence(listOf(chicken), 30.0, today))
        assertNull(Suggestions.proteinSentence(emptyList(), 30.0, today))
    }

    @Test
    fun `protein is compared with what's usual by this time of day, so ideas come for the next meal`() {
        fun protein(eaten: Double, hour: Int) =
            InsightRules.insights(context(Nutrients(calories = 600.0, protein = eaten), InsightRules.Food(true, listOf(yoghurt)), hour = hour)).singleOrNull { it.icon == "egg" }
        // Nothing expected before 11:00.
        assertNull(protein(0.0, 9))
        // At lunchtime a quarter of the 100 g is usual: 5 g eaten is 20 g behind.
        val lunch = protein(5.0, 12)!!
        assertEquals("Protein 20 g behind for this time of day", lunch.title)
        assertEquals(listOf(yoghurt), lunch.suggestions)
        // 20 g by lunchtime is fine; so is a gap under 15 g.
        assertNull(protein(20.0, 12))
        assertNull(protein(42.0, 16))
        // From 19:00, the whole day's target.
        assertEquals("Protein 60 g under target", protein(40.0, 20)!!.title)
    }

    @Test
    fun `in recovery there are no insights with amounts of food`() {
        val insights = InsightRules.insights(context(Nutrients(calories = 3000.0, protein = 20.0), InsightRules.Food(numbers = false), hour = 20))
        assertTrue(insights.none { it.icon == "egg" || it.icon == "flame" })
        val water = insights.single { it.title == "Time for some water" }
        assertFalse(water.body, Regex("""\d""").containsMatchIn(water.body))
        // With numbers, both show.
        val shown = InsightRules.insights(context(Nutrients(calories = 3000.0, protein = 20.0), InsightRules.Food(), hour = 20))
        assertTrue(shown.any { it.icon == "egg" } && shown.any { it.icon == "flame" && it.link == AppPage.FOOD })
        // Nor any weight trend in recovery.
        val weights = listOf(80.0, 79.0, 78.0).mapIndexed { i, kg -> DayStats(date = "2026-11-0${i + 1}", weightKg = kg) }
        assertTrue(InsightRules.insights(context(Nutrients(), InsightRules.Food(numbers = false), history = weights)).none { it.icon == "scale" })
        assertTrue(InsightRules.insights(context(Nutrients(), InsightRules.Food(), history = weights)).any { it.icon == "scale" })
    }

    @Test
    fun `insights open the page they're about`() {
        val nights = (1..10).map { DayStats(date = "2026-11-%02d".format(it), sleep = night(480)) }
        val insights = InsightRules.insights(context(Nutrients(calories = 1500.0, protein = 90.0), InsightRules.Food(), hour = 12, history = nights, steps = 9000))
        assertEquals(AppPage.SLEEP, insights.single { it.title.endsWith("less sleep than usual") }.link)
        assertEquals(AppPage.ACTIVITY, insights.single { it.title == "Step goal reached" }.link)
        assertEquals(AppPage.TODAY, insights.single { it.title == "Take it easy today" }.link)
        // Without a link, a feature's insight opens its page.
        assertEquals(AppPage.CYCLE, AppPage.of(com.health.api.Insight("info", "Period likely tomorrow", "", "calendar", com.health.api.Module.CYCLE)))
        assertNull(AppPage.of(com.health.api.Insight("info", "Learning your baseline", "")))
    }

    // ---- The endpoint, and Today ----

    private val now = Instant.parse("2026-11-13T19:30:00Z").toEpochMilli()

    @Test
    fun `the endpoint suggests for the meal it is now, and Today's protein insight carries the ideas`() = serverTest(testServices(clock = { now })) { s ->
        val userId = s.users.byUsername("admin")!!.id
        s.profiles.put(userId, Profile(timezone = "UTC", goals = Goals(calories = 2000, protein = 120)))
        history.filter { it.name != "Quick add" }.forEach { e -> s.foods.log(userId, e.toInput(e.date, e.meal).let { it.copy(food = it.food?.copy(id = null)) }) }
        s.foods.log(userId, FoodEntryInput(today.toString(), Meal.LUNCH, name = "Soup", nutrients = Nutrients(calories = 400.0, protein = 20.0)))
        history.map { it.date }.distinct().forEach { s.days.changedDate(userId, LocalDate.parse(it)) }
        s.days.changedDate(userId, today)
        val browser = adminBrowser()

        val current = ApiJson.decodeFromString(FoodSuggestions.serializer(), browser.get("/api/food/suggestions").bodyAsText())
        assertEquals(Meal.DINNER, current.meal)
        assertTrue(current.showNumbers)
        assertEquals(100.0, current.gap!!.protein, 0.01)
        assertTrue(current.items.any { it.items.size == 2 })
        val breakfast = ApiJson.decodeFromString(FoodSuggestions.serializer(), browser.get("/api/food/suggestions?date=2026-11-13&meal=breakfast").bodyAsText())
        assertEquals(Meal.BREAKFAST, breakfast.meal)
        assertEquals("Porridge", breakfast.items.first().name)
        assertEquals(HttpStatusCode.BadRequest, browser.get("/api/food/suggestions?meal=brunch").status)

        val protein = s.days.summary(userId, "today").insights.single { it.icon == "egg" }
        assertEquals(AppPage.FOOD, protein.link)
        assertTrue(protein.suggestions.size in 1..3)
        assertTrue(protein.body, protein.body.startsWith("For dinner, "))
        // Only today: an earlier day keeps the plain sentence.
        assertTrue(s.days.summary(userId, "2026-11-12").insights.none { it.suggestions.isNotEmpty() })

        // In eating disorder recovery: ideas without numbers, and no food insights with amounts.
        s.conditions.put(userId, MyConditions(listOf(MyCondition("eating-disorder", "Eating disorder recovery"))))
        val gentle = ApiJson.decodeFromString(FoodSuggestions.serializer(), browser.get("/api/food/suggestions").bodyAsText())
        assertFalse(gentle.showNumbers)
        assertNull(gentle.gap)
        assertTrue(gentle.items.isNotEmpty())
        gentle.items.forEach { assertFalse(it.why, Regex("""\d""").containsMatchIn(it.why)) }
        assertTrue(s.days.summary(userId, "today").insights.none { it.icon == "egg" || it.icon == "flame" })
    }
}
