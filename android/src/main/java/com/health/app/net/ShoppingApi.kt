package com.health.app.net

import com.health.api.ApiJson
import com.health.api.FoodsCleared
import com.health.api.ShoppingAdd
import com.health.api.ShoppingChange
import com.health.api.ShoppingList

/*
 * The person's foods cleared, and their shopping list (shared Food.kt, "Shopping"). Always the
 * person's own (Container.api): neither is ever shared.
 */

/** Clears the person's foods; their diary stays as it is. */
suspend fun ServerApi.clearFoods(): FoodsCleared = send("DELETE", "${base()}/api/foods", null, FoodsCleared.serializer())

suspend fun ServerApi.shopping(): ShoppingList = ServerVersion.plain("for the shopping list") { get("${base()}/api/shopping", ShoppingList.serializer()) }

suspend fun ServerApi.addShopping(add: ShoppingAdd): ShoppingList =
    send("POST", "${base()}/api/shopping", ApiJson.encodeToString(ShoppingAdd.serializer(), add), ShoppingList.serializer())

suspend fun ServerApi.tickShopping(id: String, checked: Boolean): ShoppingList =
    send("PATCH", "${base()}/api/shopping/${enc(id)}", ApiJson.encodeToString(ShoppingChange.serializer(), ShoppingChange(checked)), ShoppingList.serializer())

suspend fun ServerApi.removeShopping(id: String): ShoppingList = send("DELETE", "${base()}/api/shopping/${enc(id)}", null, ShoppingList.serializer())

/** Takes off everything ticked as bought. */
suspend fun ServerApi.clearBought(): ShoppingList = send("DELETE", "${base()}/api/shopping/bought", null, ShoppingList.serializer())
