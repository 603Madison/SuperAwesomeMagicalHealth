package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodEntry
import com.health.api.FoodEntryChange
import com.health.api.FoodEntryInput
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.api.WaterEntry
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Row
import com.health.server.db.Tx
import com.health.server.util.Random
import java.time.LocalDate

/** Each person's foods (their own, and ones from the food database they've logged), what they ate, and water. */
class FoodStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    // ---- Foods ----

    suspend fun recent(userId: String, limit: Int = 40): List<Food> = db.tx {
        query("SELECT * FROM foods WHERE user_id = ? ORDER BY COALESCE(last_used_at, created_at) DESC LIMIT ?", userId, limit, map = ::toFood)
    }

    suspend fun search(userId: String, text: String, limit: Int = 12): List<Food> {
        val words = text.lowercase().split(' ').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" AND ") { "(LOWER(name) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(brand, '')) LIKE ? ESCAPE '\\')" }
        val args = words.flatMap { w -> val like = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; listOf(like, like) }
        return db.tx { query("SELECT * FROM foods WHERE user_id = ? AND $where ORDER BY uses DESC, name LIMIT ?", userId, *args.toTypedArray(), limit, map = ::toFood) }
    }

    suspend fun byBarcode(userId: String, barcode: String): Food? = db.tx {
        one("SELECT * FROM foods WHERE user_id = ? AND barcode = ? ORDER BY uses DESC", userId, barcode, map = ::toFood)
    }

    suspend fun save(userId: String, food: Food, id: String? = null): Food {
        val clean = validated(food)
        val foodId = id ?: Random.id()
        db.tx {
            if (id != null && one("SELECT 1 FROM foods WHERE id = ? AND user_id = ?", id, userId) { true } == null) throw ApiError.notFound("No such food")
            update(
                """INSERT INTO foods (id, user_id, name, brand, barcode, serving, serving_grams, nutrients, source, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET name = excluded.name, brand = excluded.brand, barcode = excluded.barcode,
                     serving = excluded.serving, serving_grams = excluded.serving_grams, nutrients = excluded.nutrients""",
                foodId, userId, clean.name, clean.brand, clean.barcode, clean.serving, clean.servingGrams,
                ApiJson.encodeToString(Nutrients.serializer(), clean.nutrients), clean.source, clock(),
            )
        }
        return db.tx { one("SELECT * FROM foods WHERE id = ?", foodId, map = ::toFood)!! }
    }

    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM foods WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such food")
    }

    // ---- Diary ----

    suspend fun day(userId: String, date: LocalDate): Pair<List<FoodEntry>, List<WaterEntry>> = db.tx {
        val entries = query("SELECT * FROM food_entries WHERE user_id = ? AND date = ? ORDER BY logged_at", userId, date.toString(), map = ::toEntry)
        val water = query("SELECT * FROM water WHERE user_id = ? AND date = ? ORDER BY at", userId, date.toString()) {
            WaterEntry(it.string("date"), it.int("ml"), it.string("id"), it.long("at"))
        }
        entries to water
    }

    suspend fun entries(userId: String, from: LocalDate, to: LocalDate): List<FoodEntry> = db.tx {
        query("SELECT * FROM food_entries WHERE user_id = ? AND date >= ? AND date <= ? ORDER BY date, logged_at", userId, from.toString(), to.toString(), map = ::toEntry)
    }

    /** Logs something eaten; a food from the database that hasn't been logged before joins the person's foods. */
    suspend fun log(userId: String, input: FoodEntryInput): FoodEntry {
        val date = com.health.server.util.parseDate(input.date)
        val servings = input.servings
        if (!servings.isFinite() || servings <= 0 || servings > 100) throw ApiError.badRequest("Servings are between 0 and 100")
        val given = input.food
        val givenNutrients = input.nutrients
        return db.tx {
            val (food, name, brand, serving, nutrients) = when {
                given != null -> {
                    val food = validated(given)
                    val saved = remember(this, userId, food)
                    Logged(saved, food.name, food.brand, food.serving, food.nutrients * servings)
                }
                givenNutrients != null -> {
                    val n = checked(givenNutrients)
                    Logged(null, input.name?.trim()?.take(120)?.ifBlank { null } ?: "Quick add", null, "1 serving", n * servings)
                }
                else -> throw ApiError.badRequest("Say what was eaten: a food, or its calories")
            }
            val id = Random.id()
            update(
                """INSERT INTO food_entries (id, user_id, date, meal, name, brand, servings, serving, food_id, nutrients, logged_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, date.toString(), input.meal, name, brand, servings, serving, food,
                ApiJson.encodeToString(Nutrients.serializer(), nutrients), clock(),
            )
            one("SELECT * FROM food_entries WHERE id = ?", id, map = ::toEntry)!!
        }
    }

    private data class Logged(val foodId: String?, val name: String, val brand: String?, val serving: String, val nutrients: Nutrients)

    /** The person's copy of [food]: theirs already (by id or barcode), or a new one; counts the use. */
    private fun remember(tx: Tx, userId: String, food: Food): String = with(tx) {
        val existing = food.id?.let { id -> one("SELECT id FROM foods WHERE id = ? AND user_id = ?", id, userId) { it.string("id") } }
            ?: food.barcode?.let { code -> one("SELECT id FROM foods WHERE user_id = ? AND barcode = ? AND serving = ?", userId, code, food.serving) { it.string("id") } }
        val id = existing ?: Random.id().also { id ->
            update(
                """INSERT INTO foods (id, user_id, name, brand, barcode, serving, serving_grams, nutrients, source, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, food.name, food.brand, food.barcode, food.serving, food.servingGrams,
                ApiJson.encodeToString(Nutrients.serializer(), food.nutrients), food.source, clock(),
            )
        }
        update("UPDATE foods SET uses = uses + 1, last_used_at = ? WHERE id = ?", clock(), id)
        id
    }

    /** Changes an entry; returns it, and the day it was on before. */
    suspend fun change(userId: String, id: String, change: FoodEntryChange): Pair<FoodEntry, String> = db.tx {
        val entry = one("SELECT * FROM food_entries WHERE id = ? AND user_id = ?", id, userId, map = ::toEntry) ?: throw ApiError.notFound("No such entry")
        val servings = change.servings ?: entry.servings
        if (!servings.isFinite() || servings <= 0 || servings > 100) throw ApiError.badRequest("Servings are between 0 and 100")
        val nutrients = entry.nutrients * (servings / entry.servings)
        val date = change.date?.let { com.health.server.util.parseDate(it).toString() } ?: entry.date
        update(
            "UPDATE food_entries SET meal = ?, servings = ?, nutrients = ?, date = ? WHERE id = ?",
            change.meal ?: entry.meal, servings, ApiJson.encodeToString(Nutrients.serializer(), nutrients), date, id,
        )
        one("SELECT * FROM food_entries WHERE id = ?", id, map = ::toEntry)!! to entry.date
    }

    /** Deletes an entry; returns its day. */
    suspend fun deleteEntry(userId: String, id: String): String = db.tx {
        val date = one("SELECT date FROM food_entries WHERE id = ? AND user_id = ?", id, userId) { it.string("date") } ?: throw ApiError.notFound("No such entry")
        update("DELETE FROM food_entries WHERE id = ?", id)
        date
    }

    /** Copies a day's meal (or the whole day) to another day; returns how many entries. */
    suspend fun copy(userId: String, from: LocalDate, to: LocalDate, meal: Meal?): Int = db.tx {
        val entries = query(
            "SELECT * FROM food_entries WHERE user_id = ? AND date = ?${if (meal != null) " AND meal = ?" else ""} ORDER BY logged_at",
            *listOfNotNull(userId, from.toString(), meal).toTypedArray(), map = ::toEntry,
        )
        entries.forEach { e ->
            update(
                """INSERT INTO food_entries (id, user_id, date, meal, name, brand, servings, serving, food_id, nutrients, logged_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Random.id(), userId, to.toString(), e.meal, e.name, e.brand, e.servings, e.serving, e.foodId,
                ApiJson.encodeToString(Nutrients.serializer(), e.nutrients), clock(),
            )
        }
        entries.size
    }

    // ---- Water ----

    suspend fun addWater(userId: String, entry: WaterEntry): WaterEntry {
        val date = com.health.server.util.parseDate(entry.date)
        if (entry.ml !in 1..5000) throw ApiError.badRequest("Water is in millilitres, up to 5 litres at a time")
        val id = Random.id()
        val at = entry.at ?: clock()
        db.tx { update("INSERT INTO water (id, user_id, date, ml, at) VALUES (?, ?, ?, ?, ?)", id, userId, date.toString(), entry.ml, at) }
        return WaterEntry(date.toString(), entry.ml, id, at)
    }

    /** Deletes a glass of water; returns its day. */
    suspend fun deleteWater(userId: String, id: String): String = db.tx {
        val date = one("SELECT date FROM water WHERE id = ? AND user_id = ?", id, userId) { it.string("date") } ?: throw ApiError.notFound("No such entry")
        update("DELETE FROM water WHERE id = ?", id)
        date
    }

    // ---- Helpers ----

    private fun toFood(row: Row) = Food(
        name = row.string("name"),
        nutrients = ApiJson.decodeFromString(Nutrients.serializer(), row.string("nutrients")),
        serving = row.string("serving"),
        servingGrams = row.doubleOrNull("serving_grams"),
        brand = row.stringOrNull("brand"),
        barcode = row.stringOrNull("barcode"),
        source = runCatching { FoodSource.valueOf(row.string("source")) }.getOrDefault(FoodSource.CUSTOM),
        id = row.string("id"),
        uses = row.int("uses"),
    )

    private fun toEntry(row: Row) = FoodEntry(
        date = row.string("date"),
        meal = Meal.valueOf(row.string("meal")),
        name = row.string("name"),
        nutrients = ApiJson.decodeFromString(Nutrients.serializer(), row.string("nutrients")),
        servings = row.double("servings"),
        serving = row.string("serving"),
        brand = row.stringOrNull("brand"),
        foodId = row.stringOrNull("food_id"),
        id = row.string("id"),
        loggedAt = row.long("logged_at"),
    )

    companion object {
        fun validated(food: Food): Food {
            val name = food.name.trim().take(160).ifBlank { throw ApiError.badRequest("A food needs a name") }
            return food.copy(
                name = name,
                brand = food.brand?.trim()?.take(120)?.ifBlank { null },
                barcode = food.barcode?.trim()?.filter { it.isDigit() }?.take(20)?.ifBlank { null },
                serving = food.serving.trim().take(80).ifBlank { "1 serving" },
                servingGrams = food.servingGrams?.takeIf { it.isFinite() && it > 0 && it < 10_000 },
                nutrients = checked(food.nutrients),
            )
        }

        fun checked(n: Nutrients): Nutrients {
            fun ok(v: Double, max: Double) = v.isFinite() && v >= 0 && v <= max
            if (!ok(n.calories, 20_000.0) || !ok(n.protein, 2000.0) || !ok(n.carbs, 3000.0) || !ok(n.fat, 2000.0)) {
                throw ApiError.badRequest("Those nutrients don't look right")
            }
            fun opt(v: Double?, max: Double) = v?.takeIf { ok(it, max) }
            return n.copy(fiber = opt(n.fiber, 500.0), sugar = opt(n.sugar, 3000.0), saturatedFat = opt(n.saturatedFat, 2000.0), sodiumMg = opt(n.sodiumMg, 100_000.0))
        }
    }
}
