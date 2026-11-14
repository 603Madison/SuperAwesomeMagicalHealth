package com.health.app.net

import com.health.api.ApiJson
import com.health.api.MealieConnect
import com.health.api.MealiePlan
import com.health.api.MealieRecipe
import com.health.api.MealieRecipes
import com.health.api.MealieStatus
import kotlinx.serialization.builtins.serializer

/*
 * The person's Mealie, the self-hosted recipe manager (shared Food.kt, "Mealie"): connecting it, its
 * recipes as foods, and a day's meal plan. Always the person's own (Container.api), and never
 * asked for while viewing a share: the server refuses /api/mealie through one. The token goes to
 * the server once, when connecting, and is never kept on the phone or sent back.
 */

/** Whether Mealie is connected, and to what. An older server (before Mealie) says it needs updating. */
suspend fun ServerApi.mealieStatus(): MealieStatus = ServerVersion.plain("for Mealie") { get("${base()}/api/mealie", MealieStatus.serializer()) }

/** Connects (or changes the address, or the token): the server checks it with Mealie first, and says why not in plain words. */
suspend fun ServerApi.connectMealie(connect: MealieConnect): MealieStatus = ServerVersion.plain("for Mealie") {
    send("PUT", "${base()}/api/mealie", ApiJson.encodeToString(MealieConnect.serializer(), connect), MealieStatus.serializer())
}

suspend fun ServerApi.disconnectMealie() = send("DELETE", "${base()}/api/mealie", null, Unit.serializer())

/** Recipes matching [query] (two characters or more), each with one serving ready to log when Mealie has its nutrition. */
suspend fun ServerApi.mealieRecipes(query: String): MealieRecipes = get("${base()}/api/mealie/recipes?q=${enc(query)}", MealieRecipes.serializer())

suspend fun ServerApi.mealieRecipe(slug: String): MealieRecipe = get("${base()}/api/mealie/recipes/${enc(slug)}", MealieRecipe.serializer())

/** What's planned in Mealie on [date] ("2026-11-14"), in the order of the day's meals. */
suspend fun ServerApi.mealiePlan(date: String): MealiePlan = get("${base()}/api/mealie/plan?date=${enc(date)}", MealiePlan.serializer())
