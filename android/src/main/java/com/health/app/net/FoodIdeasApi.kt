package com.health.app.net

import com.health.api.FoodSuggestions
import com.health.api.Meal

/*
 * Ideas from what the person has eaten before (docs/API.md, "Food"): for the Food tab, and Today's
 * protein insight carries its own. An older server (before 0.3) says it needs updating.
 */

suspend fun ServerApi.foodSuggestions(date: String, meal: Meal? = null): FoodSuggestions = ServerVersion.plain("for meal ideas", badRequestToo = true) {
    get("${base()}/api/food/suggestions?date=${enc(date)}${meal?.let { "&meal=${it.name}" } ?: ""}", FoodSuggestions.serializer())
}
