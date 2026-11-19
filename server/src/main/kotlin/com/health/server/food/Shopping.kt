package com.health.server.food

import com.health.api.ShoppingAdd
import com.health.api.ShoppingChange
import com.health.api.ShoppingItem
import com.health.api.ShoppingList
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.requireUser
import com.health.server.modules.UserSettingsStore
import com.health.server.util.Random
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Each person's shopping list, sealed in their settings (`food.shopping`): things by name, or
 * foods found by search or barcode (kept without their grade, which is worked out again each time
 * the list is sent). Never shared.
 */
class ShoppingService(private val settings: UserSettingsStore, private val clock: () -> Long) {
    suspend fun list(userId: String): ShoppingList = settings.get(userId, KEY, ShoppingList.serializer()) ?: ShoppingList()

    private suspend fun save(userId: String, list: ShoppingList): ShoppingList = settings.put(userId, KEY, list, ShoppingList.serializer())

    suspend fun add(userId: String, input: ShoppingAdd): ShoppingList {
        val food = input.food?.let { FoodStore.validated(it).copy(grade = null, uses = 0) }
        val name = (input.name?.trim()?.ifBlank { null } ?: food?.name)?.take(160) ?: throw ApiError.badRequest("Say what to buy")
        val list = list(userId)
        if (list.items.size >= MAX) throw ApiError.badRequest("Up to $MAX things on the list: clear what's bought first")
        val item = ShoppingItem(Random.id(), name, food?.brand, food, checked = false, addedAt = clock())
        return save(userId, list.copy(items = list.items + item))
    }

    suspend fun change(userId: String, id: String, change: ShoppingChange): ShoppingList {
        val list = list(userId)
        if (list.items.none { it.id == id }) throw ApiError.notFound("That isn't on the list")
        return save(userId, list.copy(items = list.items.map { if (it.id == id) it.copy(checked = change.checked) else it }))
    }

    suspend fun remove(userId: String, id: String): ShoppingList {
        val list = list(userId)
        if (list.items.none { it.id == id }) throw ApiError.notFound("That isn't on the list")
        return save(userId, list.copy(items = list.items.filterNot { it.id == id }))
    }

    /** Takes off everything ticked as bought. */
    suspend fun clearBought(userId: String): ShoppingList {
        val list = list(userId)
        return save(userId, list.copy(items = list.items.filterNot { it.checked }))
    }

    companion object {
        const val KEY = "food.shopping"
        const val MAX = 200
    }
}

/** The shopping list (`/api/shopping`): never through a share (its path isn't among what a viewer may ask for). */
fun Route.shoppingRoutes(services: Services) {
    suspend fun graded(userId: String, list: ShoppingList): ShoppingList {
        val goals = services.grader.goals(userId)
        return list.copy(items = list.items.map { item -> item.copy(food = item.food?.let { services.grader.graded(it, goals) }) })
    }
    route("/api/shopping") {
        get {
            val user = call.requireUser(services)
            call.respond(graded(user.id, services.shopping.list(user.id)))
        }
        post {
            val user = call.requireUser(services)
            call.respond(graded(user.id, services.shopping.add(user.id, call.receive<ShoppingAdd>())))
        }
        delete("/bought") {
            val user = call.requireUser(services)
            call.respond(graded(user.id, services.shopping.clearBought(user.id)))
        }
        patch("/{id}") {
            val user = call.requireUser(services)
            call.respond(graded(user.id, services.shopping.change(user.id, call.parameters["id"].orEmpty(), call.receive<ShoppingChange>())))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            call.respond(graded(user.id, services.shopping.remove(user.id, call.parameters["id"].orEmpty())))
        }
    }
}
