package com.health.app.net

import com.health.api.ApiJson
import com.health.api.NutrientTargets
import com.health.api.NutrientTargetsChange

/*
 * Nutrient targets (docs/API.md, "Food"): each one's reference intake for the person, its upper
 * limit, and their own. An older server (before vitamins and minerals) says it needs updating.
 */

suspend fun ServerApi.nutrientTargets(): NutrientTargets = ServerVersion.plain("for vitamin and mineral targets") {
    get("${base()}/api/food/targets", NutrientTargets.serializer())
}

/** Saves the person's own targets (a nutrient left out goes back to its reference intake). */
suspend fun ServerApi.saveNutrientTargets(own: Map<String, Double>): NutrientTargets =
    send("PUT", "${base()}/api/food/targets", ApiJson.encodeToString(NutrientTargetsChange.serializer(), NutrientTargetsChange(own)), NutrientTargets.serializer())
