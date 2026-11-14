package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Meal
import com.health.api.MealieConnect
import com.health.api.MealiePlan
import com.health.api.MealiePlanEntry
import com.health.api.MealieRecipe
import com.health.api.MealieRecipes
import com.health.api.MealieStatus
import com.health.api.Micronutrient
import com.health.api.Nutrients
import com.health.server.ApiError
import com.health.server.modules.UserSettingsStore
import com.health.server.platform.WebhookService
import com.health.server.util.HttpException
import com.health.server.util.fetchString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Mealie (mealie.io), the self-hosted recipe manager, connected by each person with its address and
 * an API token of theirs: their recipes become foods to log (one serving, from the nutrition the
 * recipe has in Mealie), and a day's meal plan can be logged a meal at a time. The token is kept
 * sealed with the person's settings and never sent back to an app; nothing is ever written to
 * Mealie. Mealie usually lives on a home network, so private addresses are allowed when the server
 * allows them for webhooks; "any address", cloud metadata and redirects never are.
 */
class MealieService(
    http: OkHttpClient,
    private val settings: UserSettingsStore,
    private val foods: FoodStore,
    private val allowPrivate: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = http.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** What's kept for a person: [url] is the site, with no trailing slash. */
    @Serializable
    data class Saved(val url: String, val token: String, val user: String? = null, val version: String? = null, val groupSlug: String? = null)

    /** Why Mealie couldn't be used, in words for the person; [code] is Mealie's HTTP status, if it answered. */
    class Problem(message: String, val code: Int? = null) : Exception(message)

    /** Recipes looked at lately, for ten minutes, so a search, the plan and logging don't ask twice. */
    private val recent = ConcurrentHashMap<String, Pair<Long, MealieRecipe>>()

    private suspend fun saved(userId: String): Saved? = settings.get(userId, KEY, Saved.serializer())

    suspend fun status(userId: String): MealieStatus =
        saved(userId)?.let { MealieStatus(connected = true, url = it.url, user = it.user, version = it.version) } ?: MealieStatus()

    /** Connects (or reconnects) after checking the address and token with Mealie itself. */
    suspend fun connect(userId: String, input: MealieConnect): MealieStatus {
        val base = site(input.url)
        val old = saved(userId)
        val token = input.token?.trim()?.ifBlank { null } ?: old?.token
            ?: throw ApiError.badRequest("Add an API token from Mealie: your profile, then API tokens")
        if (token.length > 4000 || token.any { it.isWhitespace() }) throw ApiError.badRequest("That token doesn't look right: copy it again from Mealie")
        val trial = Saved(base, token)
        val checked = try {
            val me = get(trial, "api/users/self") as? JsonObject ?: throw Problem(NOT_MEALIE)
            if (me["id"] == null) throw Problem(NOT_MEALIE)
            // Only a name to show: Mealie's other details about the person stay there.
            val user = me.str("fullName") ?: me.str("username")
            val version = optional { (get(trial, "api/app/about") as? JsonObject)?.str("version") }
            val group = optional { (get(trial, "api/groups/self") as? JsonObject)?.str("slug") }
            trial.copy(user = user?.take(80), version = version?.take(40), groupSlug = group?.take(80))
        } catch (e: Problem) {
            throw ApiError.badRequest(e.message ?: NOT_MEALIE)
        }
        settings.put(userId, KEY, checked, Saved.serializer())
        forget(userId)
        return status(userId)
    }

    suspend fun disconnect(userId: String) {
        settings.delete(userId, KEY)
        forget(userId)
    }

    /** Recipes whose name or ingredients match [text], each with its nutrition (up to [LIMIT]). */
    suspend fun recipes(userId: String, text: String): MealieRecipes {
        val saved = saved(userId) ?: return MealieRecipes(problem = NOT_CONNECTED)
        val query = text.trim().take(100)
        if (query.length < 2) return MealieRecipes()
        return try {
            val page = get(saved, "api/recipes", listOf("search" to query, "page" to "1", "perPage" to "$LIMIT")) as? JsonObject ?: throw Problem(NOT_MEALIE)
            val slugs = (page["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("slug") }.distinct().take(LIMIT)
            MealieRecipes(coroutineScope { slugs.map { async { recipeOrNull(userId, saved, it) } }.awaitAll() }.filterNotNull())
        } catch (e: Problem) {
            MealieRecipes(problem = e.message)
        }
    }

    /** One recipe, by its slug. */
    suspend fun recipe(userId: String, slug: String): MealieRecipe {
        val saved = saved(userId) ?: throw ApiError.badRequest(NOT_CONNECTED)
        return try {
            recipe(userId, saved, slug)
        } catch (e: Problem) {
            if (e.code == 404) throw ApiError.notFound("That recipe isn't in Mealie any more")
            throw ApiError.upstream(e.message ?: NOT_MEALIE)
        }
    }

    /**
     * What's planned in Mealie for [date], in the order of the day's meals, each marked when it's
     * already in the diary that day (the same recipe, or something with its name).
     */
    suspend fun plan(userId: String, date: LocalDate): MealiePlan {
        val day = date.toString()
        val saved = saved(userId) ?: return MealiePlan(day, problem = NOT_CONNECTED)
        return try {
            val query = listOf("start_date" to day, "end_date" to day, "page" to "1", "perPage" to "50")
            // Households came in Mealie 2; before, plans were the group's.
            val json = try {
                get(saved, "api/households/mealplans", query)
            } catch (e: Problem) {
                if (e.code == 404) get(saved, "api/groups/mealplans", query) else throw e
            }
            val items = ((json as? JsonObject)?.get("items") as? JsonArray ?: json as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.filter { it.str("date") == day }
            val (diary, _) = foods.day(userId, date)
            val refs = foods.refs(userId, diary.mapNotNull { it.foodId }).values.toSet()
            val names = diary.map { it.name.trim().lowercase() }.toSet()
            val entries = coroutineScope {
                items.map { e ->
                    async {
                        val slug = (e["recipe"] as? JsonObject)?.str("slug")
                        val recipe = slug?.let { recipeOrNull(userId, saved, it) }
                        val title = e.str("title")?.trim()?.ifBlank { null } ?: recipe?.name ?: (e["recipe"] as? JsonObject)?.str("name")
                        val note = e.str("text")?.trim()?.ifBlank { null }?.take(500)
                        if (title == null && note == null) return@async null
                        MealiePlanEntry(
                            meal = mealOf(e.str("entryType")),
                            title = (title ?: note!!).take(160),
                            recipe = recipe,
                            note = note.takeIf { title != null },
                            logged = (recipe?.food?.ref?.let { it in refs } ?: false) || listOfNotNull(title, recipe?.name).any { it.trim().lowercase() in names },
                        )
                    }
                }.awaitAll()
            }.filterNotNull()
            MealiePlan(day, entries.sortedBy { it.meal.ordinal })
        } catch (e: Problem) {
            MealiePlan(day, problem = e.message)
        }
    }

    // ---- Talking to Mealie

    private suspend fun recipeOrNull(userId: String, saved: Saved, slug: String): MealieRecipe? =
        try { recipe(userId, saved, slug) } catch (e: Problem) { null }

    private suspend fun recipe(userId: String, saved: Saved, slug: String): MealieRecipe {
        val key = "$userId|${saved.url}|$slug"
        val now = clock()
        recent[key]?.takeIf { now - it.first < KEEP_MS }?.let { return it.second }
        val json = get(saved, "api/recipes/${encode(slug)}") as? JsonObject ?: throw Problem(NOT_MEALIE)
        val recipe = toRecipe(json, saved) ?: throw Problem(NOT_MEALIE)
        if (recent.size > 2000) recent.clear()
        recent[key] = now to recipe
        return recipe
    }

    private fun forget(userId: String) = recent.keys.removeIf { it.startsWith("$userId|") }

    private suspend fun get(saved: Saved, path: String, query: List<Pair<String, String>> = emptyList()): JsonElement {
        val base = saved.url.toHttpUrl()
        refusal(base)?.let { throw Problem(it) }
        val url = base.newBuilder().addEncodedPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${saved.token}").header("Accept", "application/json").get().build()
        val text = try {
            client.fetchString(request)
        } catch (e: HttpException) {
            throw Problem(
                when (e.code) {
                    401, 403 -> "Mealie didn't accept the token. Make a new one in Mealie (your profile, then API tokens) and connect again"
                    in 300..399 -> "Mealie answered from a different address. Use the address your browser shows for Mealie, with https:// if it has it"
                    404 -> "Mealie didn't have that"
                    else -> "Mealie had a problem (error ${e.code}). Try again in a moment"
                },
                e.code,
            )
        } catch (e: IOException) {
            throw Problem("Mealie didn't answer at ${base.host}. Check the address, and that this server can reach it")
        }
        return runCatching { ApiJson.parseToJsonElement(text) }.getOrElse { throw Problem(NOT_MEALIE) }
    }

    /** Why the server won't call [url], or null if it will (as for webhooks). */
    private suspend fun refusal(url: HttpUrl): String? {
        val addresses = withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(url.host).toList() }.getOrNull() }
            ?: return "Can't find ${url.host}. Check the address"
        if (addresses.any { it.isAnyLocalAddress || (it.isLinkLocalAddress && it !is Inet6Address) }) return "That address isn't allowed"
        if (!allowPrivate() && addresses.any { WebhookService.isPrivate(it) }) return "This server doesn't connect to private addresses (an admin can allow it)"
        return null
    }

    private suspend fun <T> optional(block: suspend () -> T?): T? = try { block() } catch (e: Problem) { null }

    companion object {
        const val KEY = "food.mealie"
        const val LIMIT = 10
        private const val KEEP_MS = 10 * 60_000L
        const val NOT_CONNECTED = "Mealie isn't connected"
        const val NOT_MEALIE = "That doesn't look like Mealie. Check the address"

        /**
         * The site of a Mealie address, however it was pasted: "mealie.home:9000" gets http://, and
         * Mealie's own pages ("/g/home/r/…") and "/api" are left off. Refused when it isn't one.
         */
        fun site(text: String): String {
            val raw = text.trim().let { if ("://" in it) it else "http://$it" }
            val url = raw.toHttpUrlOrNull()?.takeIf { it.host.isNotBlank() } ?: throw ApiError.badRequest("That doesn't look like an address. Try something like http://mealie.home:9000")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw ApiError.badRequest("Leave any name and password out of the address: the token is enough")
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val cut = segments.indexOfFirst { it in setOf("g", "api", "recipe", "recipes", "user", "login", "docs") }.let { if (it < 0) segments.size else it }
            val builder = HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port)
            segments.take(cut).forEach { builder.addPathSegment(it) }
            return builder.build().toString().trimEnd('/')
        }

        private fun encode(slug: String) = slug.filter { it.isLetterOrDigit() || it in "-_." }.ifBlank { "_" }

        /** The diary's meal for a Mealie plan entry: sides with dinner, desserts and drinks with snacks. */
        fun mealOf(type: String?): Meal = when (type?.lowercase()) {
            "breakfast" -> Meal.BREAKFAST
            "lunch" -> Meal.LUNCH
            "dinner", "side" -> Meal.DINNER
            else -> Meal.SNACK
        }

        /** A recipe as Mealie gives it, with one serving ready to log when it has nutrition. */
        fun toRecipe(json: JsonObject, saved: Saved): MealieRecipe? {
            val slug = json.str("slug")?.takeIf { it.isNotBlank() } ?: return null
            val name = json.str("name")?.trim()?.ifBlank { null }?.take(160) ?: return null
            val id = json.str("id") ?: slug
            val servings = json.num("recipeServings")?.takeIf { it > 0 } ?: json.num("recipeYieldQuantity")?.takeIf { it > 0 }
            val link = saved.groupSlug?.let { "${saved.url}/g/$it/r/$slug" } ?: "${saved.url}/recipe/$slug"
            val food = nutrients(json["nutrition"] as? JsonObject)?.let {
                Food(name = name, nutrients = it, serving = "1 serving", source = FoodSource.MEALIE, ref = "mealie:$id", link = link)
            }
            return MealieRecipe(slug, name, food, servings, link)
        }

        /**
         * One serving's nutrients from Mealie's nutrition (free text, per serving): "250 kcal", "12 g",
         * "450 mg". Calories from the macronutrients when only they're there; null with neither.
         */
        fun nutrients(n: JsonObject?): Nutrients? {
            n ?: return null
            fun text(key: String) = (n[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
            val protein = grams(text("proteinContent"))
            val carbs = grams(text("carbohydrateContent"))
            val fat = grams(text("fatContent"))
            val calories = calories(text("calories"))
                ?: if (protein != null || carbs != null || fat != null) 4 * (protein ?: 0.0) + 4 * (carbs ?: 0.0) + 9 * (fat ?: 0.0) else null
            calories ?: return null
            val micros = buildMap {
                grams(text("transFatContent"))?.let { put(Micronutrient.TRANS_FAT, round(it)) }
                milligrams(text("cholesterolContent"))?.let { put(Micronutrient.CHOLESTEROL, round(it)) }
            }
            return Nutrients(
                calories = calories.roundToInt().toDouble(), protein = round(protein ?: 0.0), carbs = round(carbs ?: 0.0), fat = round(fat ?: 0.0),
                fiber = grams(text("fiberContent"))?.let(::round), sugar = grams(text("sugarContent"))?.let(::round),
                saturatedFat = grams(text("saturatedFatContent"))?.let(::round), sodiumMg = milligrams(text("sodiumContent"))?.let(::round),
                micros = micros,
            )
        }

        /** The first number in [text]: "1,200" is twelve hundred, "1,5" one and a half. */
        fun number(text: String?): Double? {
            text ?: return null
            val found = Regex("""\d[\d.,]*""").find(text)?.value?.trimEnd('.', ',') ?: return null
            val plain = if (Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""").matches(found)) found.replace(",", "") else found.replace(',', '.')
            if (plain.count { it == '.' } > 1) return null
            return plain.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        }

        private fun calories(text: String?): Double? {
            val v = number(text) ?: return null
            val t = text!!.lowercase()
            return if ("kj" in t && "kcal" !in t) v / 4.184 else v
        }

        private fun grams(text: String?): Double? {
            val v = number(text) ?: return null
            val t = text!!.lowercase()
            return when {
                "mg" in t -> v / 1000
                "µg" in t || "mcg" in t -> v / 1_000_000
                else -> v
            }
        }

        /** Sodium and cholesterol: Mealie asks for milligrams, so a bare number is that. */
        private fun milligrams(text: String?): Double? {
            val v = number(text) ?: return null
            val t = text!!.lowercase()
            return when {
                "mg" in t -> v
                "µg" in t || "mcg" in t -> v / 1000
                Regex("""\d\s*g\b""").containsMatchIn(t) -> v * 1000
                else -> v
            }
        }

        private fun round(v: Double) = (v * 10).roundToInt() / 10.0

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.let(::number) }
    }
}
