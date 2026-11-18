package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.server.Services
import com.health.server.db.Database
import com.health.server.util.fetchString
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
 * and the barcodes scanned are sent; never who's asking. Searches are in English: a product found
 * by name shows only with an English name (its own, or its main one where that's English).
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

    private val lookups = FoodLookups(db, clock)

    suspend fun search(text: String): List<Food> {
        val query = text.trim().take(100)
        if (query.length < 2) return emptyList()
        // "search3:" since searches are in English (the 2 since foods carry vitamins and minerals).
        return lookups.cached("search3:${query.lowercase()}") {
            val url = urls.search.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("langs", "en")
                .addQueryParameter("page_size", "25")
                .addQueryParameter("fields", FIELDS)
                .build()
            val json = ApiJson.parseToJsonElement(http.fetchString(request(url.toString()))).jsonObject
            (json["hits"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> toFood(p, englishOnly = true) } }.distinctBy { it.barcode ?: it.name }
        }
    }

    suspend fun barcode(code: String): Food? {
        val clean = code.filter { it.isDigit() }.take(20)
        if (clean.length < 6) return null
        return lookups.cached("barcode2:$clean", briefMisses = true) {
            val json = try {
                ApiJson.parseToJsonElement(http.fetchString(request("${urls.product}$clean?fields=$FIELDS"))).jsonObject
            } catch (e: com.health.server.util.HttpException) {
                if (e.code == 404) return@cached emptyList() else throw e
            }
            listOfNotNull((json["product"] as? JsonObject)?.let { toFood(it) })
        }.firstOrNull()
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "SuperAwesomeMagicalHealth/${Services.version} (self-hosted server)")
        .header("Accept", "application/json")
        .build()

    companion object {
        private const val FIELDS = "code,lang,product_name,product_name_en,brands,nutriments,serving_size,serving_quantity"

        /**
         * A product as a food: per serving where the label gives one, else per 100 g. Its English
         * name first; with [englishOnly] (searches), one with no English name isn't a food here
         * (its main name counts only where its main language is English).
         */
        fun toFood(product: JsonObject, englishOnly: Boolean = false): Food? {
            val english = product.text("product_name_en")?.trim()?.takeIf { it.isNotEmpty() }
            val main = product.text("product_name")?.trim()?.takeIf { it.isNotEmpty() }
            val name = english ?: main?.takeIf { !englishOnly || product.text("lang")?.trim()?.lowercase() == "en" } ?: return null
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

        /** Calories and macros to a tenth, vitamins and minerals to about three figures. */
        internal fun round(n: Nutrients): Nutrients {
            fun r(v: Double) = (v * 10).roundToInt() / 10.0
            return Nutrients(
                r(n.calories), r(n.protein), r(n.carbs), r(n.fat), n.fiber?.let(::r), n.sugar?.let(::r), n.saturatedFat?.let(::r), n.sodiumMg?.let(::r),
                micros = n.micros.mapValues { (_, v) -> significant(v) },
            )
        }

        /** Three significant figures or so: vitamin B12's 0.24 µg keeps its digits, potassium's 412 mg needs none. */
        internal fun significant(v: Double): Double {
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
