package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.util.fetchString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/**
 * Open Food Facts (openfoodfacts.org), the open food database: searches and barcodes. Answers are
 * kept for a week, so the same search or barcode isn't asked for twice. Only the words searched
 * and the barcodes scanned are sent; never who's asking.
 */
class OpenFoodFacts(
    private val http: OkHttpClient,
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    private val urls: Urls = Urls(),
) {
    data class Urls(
        val search: String = "https://search.openfoodfacts.org/search",
        val product: String = "https://world.openfoodfacts.org/api/v2/product/",
    )

    suspend fun search(text: String): List<Food> {
        val query = text.trim().take(100)
        if (query.length < 2) return emptyList()
        return cached("search2:${query.lowercase()}") {
            val url = urls.search.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page_size", "25")
                .addQueryParameter("fields", FIELDS)
                .build()
            val json = ApiJson.parseToJsonElement(http.fetchString(request(url.toString()))).jsonObject
            (json["hits"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toFood) }.distinctBy { it.barcode ?: it.name }
        }
    }

    suspend fun barcode(code: String): Food? {
        val clean = code.filter { it.isDigit() }.take(20)
        if (clean.length < 6) return null
        return cached("barcode2:$clean") {
            val json = try {
                ApiJson.parseToJsonElement(http.fetchString(request("${urls.product}$clean?fields=$FIELDS"))).jsonObject
            } catch (e: com.health.server.util.HttpException) {
                if (e.code == 404) return@cached emptyList() else throw e
            }
            listOfNotNull((json["product"] as? JsonObject)?.let(::toFood))
        }.firstOrNull()
    }

    /** An answer kept for a week under [key] ("search2:", "barcode2:": the 2 since foods carry vitamins and minerals), or [fetch]ed. */
    private suspend fun cached(key: String, fetch: suspend () -> List<Food>): List<Food> {
        val now = clock()
        val kept = db.tx {
            one("SELECT data, fetched_at FROM food_lookups WHERE key = ?", key) { it.string("data") to it.long("fetched_at") }
        }
        if (kept != null && now - kept.second < KEEP_MS) {
            runCatching { ApiJson.decodeFromString(FoodList, kept.first) }.getOrNull()?.let { return it }
        }
        val fresh = fetch()
        db.tx {
            update("INSERT OR REPLACE INTO food_lookups (key, data, fetched_at) VALUES (?, ?, ?)", key, ApiJson.encodeToString(FoodList, fresh), now)
            // Keep the table small: lookups older than a month go.
            update("DELETE FROM food_lookups WHERE fetched_at < ?", now - 30 * DAY_MS)
        }
        return fresh
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "SuperAwesomeMagicalHealth/${Services.version} (self-hosted server)")
        .header("Accept", "application/json")
        .build()

    companion object {
        private const val FIELDS = "code,product_name,product_name_en,brands,nutriments,serving_size,serving_quantity"
        private const val DAY_MS = 24L * 3600 * 1000
        private const val KEEP_MS = 7 * DAY_MS
        private val FoodList = ListSerializer(Food.serializer())

        /** A product as a food: per serving where the label gives one, else per 100 g. */
        fun toFood(product: JsonObject): Food? {
            val name = (product.text("product_name") ?: product.text("product_name_en"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val n = product["nutriments"] as? JsonObject ?: return null
            val kcal = n.number("energy-kcal_100g") ?: n.number("energy_100g")?.let { it / 4.184 } ?: return null
            if (kcal < 0 || kcal > 900) return null
            val per100 = Nutrients(
                calories = kcal,
                protein = n.number("proteins_100g") ?: 0.0,
                carbs = n.number("carbohydrates_100g") ?: 0.0,
                fat = n.number("fat_100g") ?: 0.0,
                fiber = n.number("fiber_100g"),
                sugar = n.number("sugars_100g"),
                saturatedFat = n.number("saturated-fat_100g"),
                sodiumMg = n.number("sodium_100g")?.let { it * 1000 },
                micros = micros(n, "_100g"),
            )
            val grams = product.number("serving_quantity")?.takeIf { it > 0 && it < 2000 }
            val label = product.text("serving_size")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
            val brand = when (val b = product["brands"]) {
                is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> b.contentOrNull?.split(',')?.firstOrNull()
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
            // Per serving where the label gives one: per 100 g scaled, or the label's own per-serving
            // value for what's only given that way.
            val nutrients = if (grams != null) {
                val scaled = per100 * (grams / 100.0)
                scaled.copy(
                    fiber = scaled.fiber ?: n.number("fiber_serving"),
                    sugar = scaled.sugar ?: n.number("sugars_serving"),
                    saturatedFat = scaled.saturatedFat ?: n.number("saturated-fat_serving"),
                    sodiumMg = scaled.sodiumMg ?: n.number("sodium_serving")?.let { it * 1000 },
                    micros = micros(n, "_serving") + scaled.micros,
                )
            } else {
                per100
            }
            return Food(
                name = name.take(160),
                nutrients = round(nutrients),
                serving = if (grams != null) label ?: "${grams.roundToInt()} g" else "100 g",
                servingGrams = grams ?: 100.0,
                brand = brand,
                barcode = product.text("code")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() },
                source = FoodSource.OPEN_FOOD_FACTS,
            )
        }

        /**
         * The vitamins, minerals and finer fats given with [suffix] ("_100g" or "_serving"), each from
         * Open Food Facts' grams into its own unit ([Micronutrient.fromGrams]). Ones that can't be
         * right (below nothing, or more than the whole 100 g) are left out.
         */
        fun micros(n: JsonObject, suffix: String): Map<Micronutrient, Double> = Micronutrient.entries.mapNotNull { m ->
            val grams = n.number(m.offKey.removeSuffix("_100g") + suffix)?.takeIf { it >= 0 && it <= 100 } ?: return@mapNotNull null
            m to grams * m.fromGrams
        }.toMap()

        private fun round(n: Nutrients): Nutrients {
            fun r(v: Double) = (v * 10).roundToInt() / 10.0
            return Nutrients(
                r(n.calories), r(n.protein), r(n.carbs), r(n.fat), n.fiber?.let(::r), n.sugar?.let(::r), n.saturatedFat?.let(::r), n.sodiumMg?.let(::r),
                micros = n.micros.mapValues { (_, v) -> significant(v) },
            )
        }

        /** Three significant figures or so: vitamin B12's 0.24 µg keeps its digits, potassium's 412 mg needs none. */
        private fun significant(v: Double): Double {
            val places = when {
                v >= 100 -> 0
                v >= 10 -> 1
                v >= 1 -> 2
                else -> 3
            }
            return java.math.BigDecimal(v).setScale(places, java.math.RoundingMode.HALF_UP).toDouble()
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }?.takeIf { it.isFinite() }
    }
}
