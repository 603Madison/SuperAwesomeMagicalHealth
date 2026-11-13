package com.health.app.net

import com.health.api.ApiJson
import com.health.api.ContinenceCheckIn
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.CycleSettings
import com.health.api.Supply
import com.health.api.SupplyAdjustment
import com.health.api.SupplyStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/*
 * The deeper bladder and bowel diary (settings, progress, weekly check-ins), supplies, and cycle
 * settings (docs/API.md, "Bladder and bowel, in detail", "Supplies", "Cycle settings").
 */

private suspend fun <T> ServerApi.put(path: String, body: T, serializer: KSerializer<T>): T =
    send("PUT", "${base()}$path", ApiJson.encodeToString(serializer, body), serializer)

// ---- Bladder and bowel ----

suspend fun ServerApi.continenceSettings(): ContinenceSettings = get("${base()}/api/continence/settings", ContinenceSettings.serializer())

suspend fun ServerApi.saveContinenceSettings(settings: ContinenceSettings): ContinenceSettings =
    put("/api/continence/settings", settings, ContinenceSettings.serializer())

suspend fun ServerApi.continenceProgress(weeks: Int = 12): ContinenceProgress =
    get("${base()}/api/continence/progress?weeks=$weeks", ContinenceProgress.serializer())

suspend fun ServerApi.continenceCheckIns(days: Int = 180): List<ContinenceCheckIn> =
    get("${base()}/api/continence/check-ins?days=$days", ListSerializer(ContinenceCheckIn.serializer()))

suspend fun ServerApi.addContinenceCheckIn(checkIn: ContinenceCheckIn): ContinenceCheckIn =
    post("/api/continence/check-ins", checkIn, ContinenceCheckIn.serializer())

suspend fun ServerApi.deleteContinenceCheckIn(id: String) = delete("/api/continence/check-ins/${enc(id)}")

// ---- Supplies ----

suspend fun ServerApi.supplies(all: Boolean = false): List<Supply> =
    get("${base()}/api/supplies${if (all) "?all=true" else ""}", ListSerializer(Supply.serializer()))

suspend fun ServerApi.supplyStatus(): List<SupplyStatus> = get("${base()}/api/supplies/status", ListSerializer(SupplyStatus.serializer()))

suspend fun ServerApi.addSupply(supply: Supply): Supply = post("/api/supplies", supply, Supply.serializer())

suspend fun ServerApi.updateSupply(supply: Supply): Supply = put("/api/supplies/${enc(supply.id!!)}", supply, Supply.serializer())

suspend fun ServerApi.deleteSupply(id: String) = delete("/api/supplies/${enc(id)}")

/** Restocked, counted (sets the count), gave away or used: the supply as it is afterwards. */
suspend fun ServerApi.adjustSupply(id: String, adjustment: SupplyAdjustment): Supply = send(
    "POST", "${base()}/api/supplies/${enc(id)}/adjust",
    ApiJson.encodeToString(SupplyAdjustment.serializer(), adjustment), Supply.serializer(),
)

suspend fun ServerApi.supplyLog(id: String, days: Int = 90): List<SupplyAdjustment> =
    get("${base()}/api/supplies/${enc(id)}/log?days=$days", ListSerializer(SupplyAdjustment.serializer()))

// ---- Cycle ----

suspend fun ServerApi.cycleSettings(): CycleSettings = get("${base()}/api/cycle/settings", CycleSettings.serializer())

suspend fun ServerApi.saveCycleSettings(settings: CycleSettings): CycleSettings = put("/api/cycle/settings", settings, CycleSettings.serializer())
