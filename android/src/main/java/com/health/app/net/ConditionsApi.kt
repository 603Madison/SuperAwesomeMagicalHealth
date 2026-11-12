package com.health.app.net

import com.health.api.ApiJson
import com.health.api.ConditionDefinition
import com.health.api.FieldChoices
import com.health.api.FieldLayout
import com.health.api.MyConditions
import kotlinx.serialization.builtins.ListSerializer

/*
 * Conditions and goals, and the fields they bring (docs/API.md, "Conditions and goals"). Screens
 * read the fields through Container.fields, so they show what the person's conditions call for.
 */

suspend fun ServerApi.conditionCatalog(): List<ConditionDefinition> =
    get("${base()}/api/conditions/catalog", ListSerializer(ConditionDefinition.serializer()))

suspend fun ServerApi.conditions(): MyConditions = get("${base()}/api/conditions", MyConditions.serializer())

suspend fun ServerApi.saveConditions(conditions: MyConditions): MyConditions =
    send("PUT", "${base()}/api/conditions", ApiJson.encodeToString(MyConditions.serializer(), conditions), MyConditions.serializer())

suspend fun ServerApi.fields(): FieldLayout = get("${base()}/api/fields", FieldLayout.serializer())

suspend fun ServerApi.saveFieldChoices(choices: FieldChoices): FieldLayout =
    send("PUT", "${base()}/api/fields", ApiJson.encodeToString(FieldChoices.serializer(), choices), FieldLayout.serializer())
