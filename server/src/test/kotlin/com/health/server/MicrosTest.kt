package com.health.server

import com.health.api.ApiJson
import com.health.api.AppPage
import com.health.api.EnergyProfile
import com.health.api.FoodDay
import com.health.api.FoodEntry
import com.health.api.FoodEntryInput
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.Meal
import com.health.api.MicroTarget
import com.health.api.Micronutrient
import com.health.api.Module
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.NutrientTargets
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.Profile
import com.health.api.Trends
import com.health.api.toInput
import com.health.server.food.Micros
import com.health.server.food.OpenFoodFacts
import com.health.server.insights.DayStats
import com.health.server.insights.InsightRules
import com.health.server.insights.ReferenceIntakes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream

class MicrosTest {
    // ---- Open Food Facts

    @Test
    fun `Open Food Facts' grams become each nutrient's own unit, per serving`() {
        val product = Json.parseToJsonElement(
            """{"code":"5000","product_name":"Fortified flakes","serving_size":"30 g","serving_quantity":30,
               "nutriments":{"energy-kcal_100g":380,"proteins_100g":8,"carbohydrates_100g":80,"fat_100g":2,"fiber_100g":9,
                 "iron_100g":0.014,"calcium_100g":0.45,"vitamin-d_100g":0.0000042,"vitamin-b12_100g":0.0000021,"folate_100g":1,
                 "vitamin-b9_100g":0.0002,"potassium_100g":0.3,"saturated-fat_100g":1,
                 "zinc_serving":0.0012,"iron_serving":0.5,"selenium_100g":-1,"magnesium_100g":"0.12"}}""",
        ).jsonObject
        val food = OpenFoodFacts.toFood(product)!!
        val m = food.nutrients.micros
        // 14 mg of iron in 100 g is 4.2 mg in a 30 g serving (the per-100 g value wins over the label's per serving).
        assertEquals(4.2, m[Micronutrient.IRON]!!, 0.001)
        assertEquals(135.0, m[Micronutrient.CALCIUM]!!, 0.001)
        assertEquals(1.26, m[Micronutrient.VITAMIN_D]!!, 0.001)
        assertEquals(0.63, m[Micronutrient.VITAMIN_B12]!!, 0.001)
        assertEquals(60.0, m[Micronutrient.FOLATE]!!, 0.001)
        assertEquals(90.0, m[Micronutrient.POTASSIUM]!!, 0.001)
        assertEquals(36.0, m[Micronutrient.MAGNESIUM]!!, 0.001)
        // Only given per serving: taken as it is.
        assertEquals(1.2, m[Micronutrient.ZINC]!!, 0.001)
        // Below nothing: left out, not zero.
        assertNull(m[Micronutrient.SELENIUM])
        assertNull(m[Micronutrient.VITAMIN_C])
        assertEquals(2.7, food.nutrients.fiber!!, 0.01)
        assertEquals(0.3, food.nutrients.saturatedFat!!, 0.01)

        // Per 100 g without a serving.
        val per100 = OpenFoodFacts.toFood(Json.parseToJsonElement("""{"product_name":"Spinach","nutriments":{"energy-kcal_100g":23,"iron_100g":0.0027,"vitamin-k_100g":0.000483}}""").jsonObject)!!
        assertEquals(2.7, per100.nutrients.micros[Micronutrient.IRON]!!, 0.001)
        assertEquals(483.0, per100.nutrients.micros[Micronutrient.VITAMIN_K]!!, 0.001)

        // A product without any: no micros at all.
        val plain = OpenFoodFacts.toFood(Json.parseToJsonElement("""{"product_name":"Nutella","nutriments":{"energy_100g":2228}}""").jsonObject)!!
        assertTrue(plain.nutrients.micros.isEmpty())
        assertNull(plain.nutrients.fiber)
    }

    // ---- Sums, scaling, and entries from before

    @Test
    fun `nutrients add up and scale with their micros, keeping what's missing missing`() {
        val a = Nutrients(100.0, micros = mapOf(Micronutrient.IRON to 2.0, Micronutrient.CALCIUM to 100.0))
        val b = Nutrients(50.0, fiber = 3.0, micros = mapOf(Micronutrient.IRON to 1.5, Micronutrient.VITAMIN_C to 30.0))
        val sum = a + b
        assertEquals(3.5, sum.micros[Micronutrient.IRON]!!, 1e-9)
        assertEquals(100.0, sum.micros[Micronutrient.CALCIUM]!!, 1e-9)
        assertEquals(30.0, sum.micros[Micronutrient.VITAMIN_C]!!, 1e-9)
        assertNull(sum.micros[Micronutrient.ZINC])
        assertEquals(3.0, sum.fiber!!, 1e-9)
        val half = sum * 0.5
        assertEquals(1.75, half.micros[Micronutrient.IRON]!!, 1e-9)
        assertEquals(3, half.micros.size)
        // Logging again from an entry (ideas, copy): each serving keeps its micros.
        val entry = FoodEntry("2026-11-01", Meal.LUNCH, "Soup", Nutrients(200.0, micros = mapOf(Micronutrient.IRON to 4.0)), servings = 2.0)
        assertEquals(2.0, entry.toInputFood().micros[Micronutrient.IRON]!!, 1e-9)
    }

    private fun FoodEntry.toInputFood(): Nutrients = toInput(date, meal).nutrients!!

    @Test
    fun `entries stored before micronutrients still read, and count as carrying none`() = serverTest { s ->
        val userId = s.users.byUsername("admin")!!.id
        s.db.tx {
            update(
                """INSERT INTO food_entries (id, user_id, date, meal, name, brand, servings, serving, food_id, nutrients, logged_at)
                   VALUES ('old', ?, '2026-03-01', 'LUNCH', 'Old soup', NULL, 1, '1 bowl', NULL, ?, 1)""",
                userId, """{"calories":250.0,"protein":10.0,"carbs":30.0,"fat":8.0,"fiber":4.0}""",
            )
        }
        s.foods.log(userId, FoodEntryInput("2026-03-01", Meal.DINNER, name = "Lentils", nutrients = Nutrients(300.0, micros = mapOf(Micronutrient.IRON to 6.0))))
        val day = ApiJson.decodeFromString(FoodDay.serializer(), adminBrowser().get("/api/food/2026-03-01").bodyAsText())
        assertEquals(2, day.entries.size)
        assertTrue(day.entries.single { it.name == "Old soup" }.nutrients.micros.isEmpty())
        assertEquals(6.0, day.totals.micros[Micronutrient.IRON]!!, 1e-9)
        assertEquals(550.0, day.totals.calories, 1e-9)
        // And the day's numbers (kept in the day cache) carry them.
        assertEquals(6.0, s.days.summary(userId, "2026-03-01").nutrition.micros[Micronutrient.IRON]!!, 1e-9)
    }

    // ---- Targets

    private val date = LocalDate.parse("2026-11-13")
    private val base = NutritionTarget(2000, 100, 250, 67, 2000, "A general 2,000 kcal")

    private fun target(profile: Profile, own: Map<String, Double> = emptyMap(), pregnant: Boolean = false) = ReferenceIntakes.apply(base, profile, date, pregnant, own)

    private fun NutritionTarget.of(m: Micronutrient): MicroTarget = micros.single { it.micro == m }

    @Test
    fun `targets follow the chosen formula, and the average is the mean`() {
        val female = target(Profile(birthYear = 1990, energyProfile = EnergyProfile.FEMALE))
        val male = target(Profile(birthYear = 1990, energyProfile = EnergyProfile.MALE))
        val average = target(Profile(birthYear = 1990, energyProfile = EnergyProfile.AVERAGE))
        assertEquals(18.0, female.of(Micronutrient.IRON).amount, 1e-9)
        assertEquals(8.0, male.of(Micronutrient.IRON).amount, 1e-9)
        assertEquals(13.0, average.of(Micronutrient.IRON).amount, 1e-9)
        assertEquals(45.0, average.of(Micronutrient.IRON).upper!!, 1e-9)
        assertEquals(3000.0, average.of(Micronutrient.POTASSIUM).amount, 1e-9)
        assertEquals(800.0, average.of(Micronutrient.VITAMIN_A).amount, 1e-9)
        assertNull(average.of(Micronutrient.VITAMIN_A).upper) // the UL is for preformed vitamin A only
        assertEquals(25.0, female.fiberG!!, 1e-9)
        assertEquals(38.0, male.fiberG!!, 1e-9)
        assertEquals(31.5, average.fiberG!!, 1e-9)
        assertEquals(2300.0, female.sodiumMg!!, 1e-9)
        assertEquals(22.2, female.saturatedFatG!!, 1e-9) // 10% of 2,000 kcal
        assertEquals(90.0, female.sugarG!!, 1e-9)
        assertEquals("Adult reference intake, female values", female.microBasis)
        assertEquals("Adult reference intake, the average of female and male values", average.microBasis)
        // Every nutrient with a reference has a target; mono and polyunsaturated fat don't.
        assertTrue(female.micros.none { it.micro == Micronutrient.MONO_FAT || it.micro == Micronutrient.POLY_FAT })
        assertTrue(female.micros.none { it.custom })
        // Older: less iron for female bodies, more calcium; and a different basis.
        val older = target(Profile(birthYear = 1966, energyProfile = EnergyProfile.FEMALE))
        assertEquals(8.0, older.of(Micronutrient.IRON).amount, 1e-9)
        assertEquals(1200.0, older.of(Micronutrient.CALCIUM).amount, 1e-9)
        assertEquals(21.0, older.fiberG!!, 1e-9)
        assertEquals("Reference intake for 51 to 70, female values", older.microBasis)
    }

    @Test
    fun `the person's own targets win, and say so`() {
        val mine = target(Profile(energyProfile = EnergyProfile.MALE), own = mapOf("IRON" to 15.0, "FIBER" to 30.0, "MONO_FAT" to 20.0))
        assertEquals(15.0, mine.of(Micronutrient.IRON).amount, 1e-9)
        assertTrue(mine.of(Micronutrient.IRON).custom)
        assertEquals(45.0, mine.of(Micronutrient.IRON).upper!!, 1e-9) // the upper limit stays
        assertEquals(30.0, mine.fiberG!!, 1e-9)
        assertTrue(mine.of(Micronutrient.MONO_FAT).custom)
        assertFalse(mine.of(Micronutrient.CALCIUM).custom)
    }

    @Test
    fun `pregnancy raises folate, iron and iodine`() {
        val pregnant = target(Profile(energyProfile = EnergyProfile.AVERAGE), pregnant = true)
        assertEquals(600.0, pregnant.of(Micronutrient.FOLATE).amount, 1e-9)
        assertEquals(27.0, pregnant.of(Micronutrient.IRON).amount, 1e-9)
        assertEquals(220.0, pregnant.of(Micronutrient.IODINE).amount, 1e-9)
        assertTrue(pregnant.microBasis!!.endsWith("for pregnancy"))
    }

    @Test
    fun `targets are set, kept sealed, reset, checked, and hidden from a viewer who doesn't see the cycle`() = serverTest { s ->
        val ownerId = s.users.byUsername("admin")!!.id
        s.profiles.put(ownerId, Profile(birthYear = 1990, energyProfile = EnergyProfile.FEMALE, modules = com.health.api.Module.DEFAULT + Module.CYCLE))
        val owner = adminBrowser()
        val start = ApiJson.decodeFromString(NutrientTargets.serializer(), owner.get("/api/food/targets").bodyAsText())
        assertEquals("Adult reference intake, female values", start.basis)
        assertTrue(start.source.contains("Dietary Reference Intakes"))
        assertEquals(18.0, start.items.single { it.key == "IRON" }.reference!!, 1e-9)
        assertEquals(25.0, start.items.single { it.key == "FIBER" }.reference!!, 1e-9)
        assertNull(start.items.single { it.key == "MONO_FAT" }.reference)

        val saved = owner.putJson("/api/food/targets", """{"own":{"IRON":14,"SODIUM":1800}}""")
        assertEquals(HttpStatusCode.OK, saved.status)
        val after = ApiJson.decodeFromString(NutrientTargets.serializer(), saved.bodyAsText())
        assertEquals(14.0, after.items.single { it.key == "IRON" }.own!!, 1e-9)
        val day = ApiJson.decodeFromString(FoodDay.serializer(), owner.get("/api/food/today").bodyAsText())
        assertEquals(14.0, day.target.micros.single { it.micro == Micronutrient.IRON }.amount, 1e-9)
        assertTrue(day.target.micros.single { it.micro == Micronutrient.IRON }.custom)
        assertEquals(1800.0, day.target.sodiumMg!!, 1e-9)
        // Sealed in the person's settings.
        val stored = s.db.tx { one("SELECT data FROM user_settings WHERE user_id = ? AND key = 'nutrition.targets'", ownerId) { it.string("data") } }!!
        assertFalse(stored.contains("IRON"))

        // Checked: a nutrient that isn't one, or an amount that can't be right.
        assertEquals(HttpStatusCode.BadRequest, owner.putJson("/api/food/targets", """{"own":{"UNOBTAINIUM":1}}""").status)
        assertEquals(HttpStatusCode.BadRequest, owner.putJson("/api/food/targets", """{"own":{"IRON":-1}}""").status)
        assertEquals(HttpStatusCode.BadRequest, owner.putJson("/api/food/targets", """{"own":{"IRON":500000}}""").status)

        // Pregnant: the owner sees pregnancy's folate; someone shown Food but not the cycle doesn't.
        s.lifeStages.saveInfo(ownerId, LifeStageInfo(LifeStage.PREGNANT, dueDate = LocalDate.now().plusDays(120).toString()))
        val mineNow = ApiJson.decodeFromString(FoodDay.serializer(), owner.get("/api/food/today").bodyAsText())
        assertEquals(600.0, mineNow.target.micros.single { it.micro == Micronutrient.FOLATE }.amount, 1e-9)
        s.users.create("sam", "Sam", password = "sam-password-123")
        assertEquals(HttpStatusCode.OK, owner.postJson("/api/shares", """{"username":"sam","modules":["NUTRITION"]}""").status)
        val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
        val seen = ApiJson.decodeFromString(FoodDay.serializer(), viewer.get("/api/food/today") { header("X-SAMH-View", ownerId) }.bodyAsText())
        assertEquals(400.0, seen.target.micros.single { it.micro == Micronutrient.FOLATE }.amount, 1e-9)
        assertFalse(seen.target.microBasis!!.contains("pregnancy"))
        val seenTrends = ApiJson.decodeFromString(Trends.serializer(), viewer.get("/api/trends?days=7") { header("X-SAMH-View", ownerId) }.bodyAsText())
        assertEquals(400.0, seenTrends.nutritionTarget!!.micros.single { it.micro == Micronutrient.FOLATE }.amount, 1e-9)
        val seenDay = ApiJson.decodeFromString(com.health.api.DaySummary.serializer(), viewer.get("/api/day/today") { header("X-SAMH-View", ownerId) }.bodyAsText())
        assertEquals(400.0, seenDay.nutritionTarget!!.micros.single { it.micro == Micronutrient.FOLATE }.amount, 1e-9)
        // A viewer can't change them.
        assertEquals(HttpStatusCode.Forbidden, viewer.put("/api/food/targets") { csrf(); header("X-SAMH-View", ownerId); json("""{"own":{}}""") }.status)

        // Reset: an empty set of their own goes back to the reference intakes.
        val reset = ApiJson.decodeFromString(NutrientTargets.serializer(), owner.putJson("/api/food/targets", """{"own":{}}""").bodyAsText())
        assertTrue(reset.items.all { it.own == null })
    }

    // ---- Checking what's sent

    @Test
    fun `micronutrients typed in are checked, and a food database's odd ones are left out`() = serverTest {
        val browser = adminBrowser()
        val ok = browser.postJson("/api/food/entries", """{"date":"2026-03-02","meal":"LUNCH","name":"Salad","nutrients":{"calories":120,"micros":{"IRON":2.5,"VITAMIN_C":40}}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        val entry = ApiJson.decodeFromString(FoodEntry.serializer(), ok.bodyAsText())
        assertEquals(2.5, entry.nutrients.micros[Micronutrient.IRON]!!, 1e-9)
        assertEquals(HttpStatusCode.BadRequest, browser.postJson("/api/food/entries", """{"date":"2026-03-02","meal":"LUNCH","name":"Odd","nutrients":{"calories":120,"micros":{"IRON":-1}}}""").status)
        assertEquals(HttpStatusCode.BadRequest, browser.postJson("/api/food/entries", """{"date":"2026-03-02","meal":"LUNCH","name":"Odd","nutrients":{"calories":120,"micros":{"POTASSIUM":200000}}}""").status)
        // A nutrient this version doesn't know (from a newer app) is left out, not refused.
        val unknown = browser.postJson("/api/food/entries", """{"date":"2026-03-09","meal":"LUNCH","name":"Odd","nutrients":{"calories":120,"micros":{"NOT_A_VITAMIN":1,"IRON":1}}}""")
        assertEquals(HttpStatusCode.OK, unknown.status)
        assertEquals(setOf(Micronutrient.IRON), ApiJson.decodeFromString(FoodEntry.serializer(), unknown.bodyAsText()).nutrients.micros.keys)
        // The person's own food: the same checks.
        val food = browser.postJson("/api/foods", """{"name":"Fortified milk","serving":"250 ml","nutrients":{"calories":120,"protein":8,"carbs":12,"fat":5,"micros":{"CALCIUM":300,"VITAMIN_D":2.5}}}""")
        assertEquals(HttpStatusCode.OK, food.status)
        assertEquals(300.0, food.obj()["nutrients"]!!.jsonObject["micros"]!!.jsonObject["CALCIUM"]!!.toString().toDouble(), 1e-9)
        assertEquals(HttpStatusCode.BadRequest, browser.postJson("/api/foods", """{"name":"Odd","nutrients":{"calories":120,"micros":{"CALCIUM":-3}}}""").status)
        // A food from the database with a value that can't be right logs, without it.
        val logged = browser.postJson(
            "/api/food/entries",
            """{"date":"2026-03-02","meal":"SNACK","servings":1,"food":{"name":"Crisps","source":"OPEN_FOOD_FACTS","barcode":"123456","serving":"25 g",
               "nutrients":{"calories":130,"micros":{"POTASSIUM":300,"IRON":-4}}}}""",
        )
        assertEquals(HttpStatusCode.OK, logged.status)
        val crisps = ApiJson.decodeFromString(FoodEntry.serializer(), logged.bodyAsText())
        assertEquals(300.0, crisps.nutrients.micros[Micronutrient.POTASSIUM]!!, 1e-9)
        assertNull(crisps.nutrients.micros[Micronutrient.IRON])
        // Changing the amount scales them.
        val doubled = ApiJson.decodeFromString(FoodEntry.serializer(), browser.patchJson("/api/food/entries/${entry.id}", """{"servings":2}""").bodyAsText())
        assertEquals(5.0, doubled.nutrients.micros[Micronutrient.IRON]!!, 1e-9)
        // Copying a day keeps them.
        browser.postJson("/api/food/copy", """{"from":"2026-03-02","to":"2026-03-03"}""")
        val copied = ApiJson.decodeFromString(FoodDay.serializer(), browser.get("/api/food/2026-03-03").bodyAsText())
        assertEquals(5.0, copied.totals.micros[Micronutrient.IRON]!!, 1e-9)
    }

    // ---- The week's insight

    private val week = (6 downTo 0).map { date.minusDays(it.toLong()) }
    private val ironTarget = base.copy(micros = listOf(MicroTarget(Micronutrient.IRON, 18.0), MicroTarget(Micronutrient.CALCIUM, 1000.0)), fiberG = 25.0)

    private fun day(d: LocalDate, vararg foods: Nutrients) = foods.mapIndexed { i, n -> FoodEntry(d.toString(), Meal.LUNCH, "Food $i", n) }

    @Test
    fun `the week's insight needs foods carrying the nutrient on five days, and most of them under`() {
        val iron = Nutrients(500.0, micros = mapOf(Micronutrient.IRON to 6.0, Micronutrient.CALCIUM to 600.0))
        val plain = Nutrients(500.0)
        // Five days where both foods carried iron, all under 18 mg; two days with nothing.
        val five = week.take(5).flatMap { day(it, iron, iron) }
        val insight = Micros.weeklyInsight(five, week, ironTarget)!!
        assertEquals("Iron has been under your target most days this week", insight.title)
        assertEquals(AppPage.FOOD, insight.link)
        assertTrue(insight.body.contains("Only foods that came with iron numbers count"))
        assertFalse(insight.body, Regex("""\d""").containsMatchIn(insight.body))
        // Only four days with data: nothing (a missing day isn't a day with none).
        assertNull(Micros.weeklyInsight(week.take(4).flatMap { day(it, iron, iron) }, week, ironTarget))
        // A day where most foods came without iron doesn't count.
        val thin = week.take(5).flatMap { day(it, iron, plain, plain) }
        assertNull(Micros.weeklyInsight(thin, week, ironTarget))
        // Iron under on only three of the days: not iron (calcium, under on four, instead).
        val enough = Nutrients(500.0, micros = mapOf(Micronutrient.IRON to 10.0, Micronutrient.CALCIUM to 400.0))
        val mixed = week.take(3).flatMap { day(it, iron, iron) } + week.drop(3).flatMap { day(it, enough, enough) }
        assertEquals("Calcium has been under your target most days this week", Micros.weeklyInsight(mixed, week, ironTarget)!!.title)
        // Fibre counts too, and only one insight ever comes back.
        val fibreLow = week.flatMap { day(it, Nutrients(500.0, fiber = 3.0)) }
        assertEquals("Fibre has been under your target most days this week", Micros.weeklyInsight(fibreLow, week, ironTarget)!!.title)
    }

    @Test
    fun `the week's insight never shows in recovery`() {
        val micro = Micros.weeklyInsight(week.flatMap { day(it, Nutrients(500.0, fiber = 3.0)) }, week, ironTarget)!!
        fun insights(food: InsightRules.Food) = InsightRules.insights(
            InsightRules.Context(
                today = DayStats(date = date.toString(), nutrition = Nutrients(1500.0, protein = 100.0)), history = emptyList(), profile = Profile(),
                target = ironTarget, readiness = null, sleep = null, isToday = false, hourNow = 12, restingCalories = null, food = food,
            ),
        )
        assertTrue(insights(InsightRules.Food(micro = micro)).any { it.icon == "salad" })
        assertTrue(insights(InsightRules.Food(numbers = false, micro = micro)).none { it.icon == "salad" })
    }

    @Test
    fun `the insight comes through Today, not in recovery, and not with vitamins hidden`() = serverTest(testServices(clock = { Instant.parse("2026-11-13T12:00:00Z").toEpochMilli() })) { s ->
        val userId = s.users.byUsername("admin")!!.id
        s.profiles.put(userId, Profile(timezone = "UTC", birthYear = 1990, energyProfile = EnergyProfile.FEMALE))
        (1..7).forEach { back ->
            val d = date.minusDays(back.toLong())
            s.foods.log(userId, FoodEntryInput(d.toString(), Meal.LUNCH, name = "Pasta", nutrients = Nutrients(600.0, 20.0, 80.0, 10.0, micros = mapOf(Micronutrient.IRON to 3.0))))
            s.days.changedDate(userId, d)
        }
        assertTrue(s.days.summary(userId, "today").insights.any { it.title == "Iron has been under your target most days this week" })
        // With vitamins and minerals hidden (a field), no insight.
        s.conditions.putChoices(userId, com.health.api.FieldChoices(hide = setOf(com.health.api.Fields.NUTRITION_MICROS)))
        assertTrue(s.days.summary(userId, "today").insights.none { it.icon == "salad" })
        s.conditions.putChoices(userId, com.health.api.FieldChoices())
        assertTrue(s.days.summary(userId, "today").insights.any { it.icon == "salad" })
        // In recovery: never.
        s.conditions.put(userId, MyConditions(listOf(MyCondition("eating-disorder", "Eating disorder recovery"))))
        assertTrue(s.days.summary(userId, "today").insights.none { it.icon == "salad" })
    }

    // ---- Exports and the connector

    @Test
    fun `the export has a column for each nutrient, empty where nothing carried it`() = serverTest { s ->
        val userId = s.users.byUsername("admin")!!.id
        val today = s.profiles.days(userId).today(s.clock())
        s.foods.log(userId, FoodEntryInput(today.toString(), Meal.LUNCH, name = "Lentil soup", nutrients = Nutrients(300.0, fiber = 8.0, sodiumMg = 700.0, micros = mapOf(Micronutrient.IRON to 4.5, Micronutrient.FOLATE to 180.0))))
        s.foods.log(userId, FoodEntryInput(today.toString(), Meal.SNACK, name = "Apple", nutrients = Nutrients(95.0)))
        s.days.changedDate(userId, today)
        val zip = adminBrowser().get("/api/export.zip?from=${today.minusDays(2)}&to=$today").bodyAsBytes()
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { files[it.name] = String(z.readBytes()) } }

        val food = files["food.csv"]!!.lines().filter { it.isNotBlank() }.map { it.split(",") }
        val header = food.first()
        assertTrue(header.containsAll(listOf("fiber_g", "sugar_g", "saturated_fat_g", "sodium_mg", "iron_mg", "folate_ug", "vitamin_d_ug", "trans_fat_g")))
        assertEquals(1, food.map { it.size }.distinct().size)
        val soup = food.single { it[2] == "Lentil soup" }
        assertEquals("4.5", soup[header.indexOf("iron_mg")])
        assertEquals("180", soup[header.indexOf("folate_ug")])
        assertEquals("700", soup[header.indexOf("sodium_mg")])
        assertEquals("", soup[header.indexOf("calcium_mg")])
        assertEquals("", food.single { it[2] == "Apple" }[header.indexOf("iron_mg")])

        val daily = files["daily.csv"]!!.lines().filter { it.isNotBlank() }.map { it.split(",") }
        val dailyHeader = daily.first()
        assertEquals(1, daily.map { it.size }.distinct().size)
        assertTrue(dailyHeader.containsAll(listOf("water_ml", "fiber_g", "iron_mg", "vitamin_b12_ug")))
        val row = daily.single { it[0] == today.toString() }
        assertEquals("4.5", row[dailyHeader.indexOf("iron_mg")])
        assertEquals("8", row[dailyHeader.indexOf("fiber_g")])
        assertEquals("", row[dailyHeader.indexOf("vitamin_c_mg")])
    }

    @Test
    fun `the connector's food log names micronutrients where the foods carried them`() {
        assertEquals("fibre 8 g, sodium 700 mg, iron 4.5 mg, folate 180 µg", Micros.text(Nutrients(300.0, fiber = 8.0, sodiumMg = 700.0, micros = mapOf(Micronutrient.IRON to 4.5, Micronutrient.FOLATE to 180.0))))
        assertNull(Micros.text(Nutrients(95.0)))
        assertEquals("vitamin_d_ug", Micros.column(Micronutrient.VITAMIN_D))
    }

    @Test
    fun `a nutrient this version doesn't know is skipped, not a failure`() {
        val json = """{"calories":100.0,"micros":{"IRON":2.5,"SOME_NEW_NUTRIENT":7.0}}"""
        val n = com.health.api.ApiJson.decodeFromString(com.health.api.Nutrients.serializer(), json)
        org.junit.Assert.assertEquals(mapOf(com.health.api.Micronutrient.IRON to 2.5), n.micros)
        org.junit.Assert.assertTrue(com.health.api.ApiJson.encodeToString(com.health.api.Nutrients.serializer(), n).contains("\"IRON\":2.5"))
    }
}
