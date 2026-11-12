package com.fitness.server.food

import com.fitness.api.Food
import com.fitness.api.FoodDay
import com.fitness.api.FoodEntryChange
import com.fitness.api.FoodEntryInput
import com.fitness.api.FoodSearch
import com.fitness.api.Meal
import com.fitness.api.Nutrients
import com.fitness.api.WaterEntry
import com.fitness.server.ApiError
import com.fitness.server.Services
import com.fitness.server.auth.requireUser
import com.fitness.server.util.parseDate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.LocalDate

@Serializable
data class CopyRequest(val from: String, val to: String, val meal: Meal? = null)

@Serializable
data class Copied(val copied: Int)

private val log = LoggerFactory.getLogger("samf.food")

/** The food diary, the person's foods, the food database, and water. */
fun Route.foodRoutes(services: Services) {
    route("/api/food") {
        get("/{date}") {
            val user = call.requireUser(services)
            val date = services.profiles.days(user.id).parse(call.parameters["date"].orEmpty(), services.clock())
            call.respond(foodDay(services, user.id, date))
        }
        post("/entries") {
            val user = call.requireUser(services)
            val entry = services.foods.log(user.id, call.receive<FoodEntryInput>())
            services.days.changedDate(user.id, LocalDate.parse(entry.date))
            call.respond(entry)
        }
        patch("/entries/{id}") {
            val user = call.requireUser(services)
            val (entry, before) = services.foods.change(user.id, call.parameters["id"].orEmpty(), call.receive<FoodEntryChange>())
            services.days.changedDate(user.id, LocalDate.parse(before))
            services.days.changedDate(user.id, LocalDate.parse(entry.date))
            call.respond(entry)
        }
        delete("/entries/{id}") {
            val user = call.requireUser(services)
            val date = services.foods.deleteEntry(user.id, call.parameters["id"].orEmpty())
            services.days.changedDate(user.id, LocalDate.parse(date))
            call.respond(mapOf("ok" to true))
        }
        post("/copy") {
            val user = call.requireUser(services)
            val request = call.receive<CopyRequest>()
            val days = services.profiles.days(user.id)
            val to = days.parse(request.to, services.clock())
            val copied = services.foods.copy(user.id, days.parse(request.from, services.clock()), to, request.meal)
            services.days.changedDate(user.id, to)
            call.respond(Copied(copied))
        }
    }

    route("/api/foods") {
        get("/search") {
            val user = call.requireUser(services)
            val query = call.request.queryParameters["q"].orEmpty().trim()
            val mine = services.foods.search(user.id, query)
            var problem: String? = null
            val online = if (!services.settings.get().foodDatabase || query.length < 2) emptyList() else try {
                services.foodDatabase.search(query)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("Open Food Facts search failed: {}", e.message)
                problem = "The food database didn't answer; try again in a moment"
                emptyList()
            }
            // Foods the person already has come first; the database's copies of them are left out.
            val known = mine.mapNotNull { it.barcode }.toSet()
            call.respond(FoodSearch(mine, online.filter { it.barcode == null || it.barcode !in known }, problem))
        }
        get("/barcode/{code}") {
            val user = call.requireUser(services)
            val code = call.parameters["code"].orEmpty().filter { it.isDigit() }
            val food = services.foods.byBarcode(user.id, code)
                ?: (if (services.settings.get().foodDatabase) runCatching { services.foodDatabase.barcode(code) }.onFailure { log.warn("Open Food Facts barcode lookup failed: {}", it.message) }.getOrNull() else null)
                ?: throw ApiError.notFound("That barcode isn't in the food database yet; add the food yourself")
            call.respond(food)
        }
        get("/recent") {
            val user = call.requireUser(services)
            call.respond(services.foods.recent(user.id))
        }
        post {
            val user = call.requireUser(services)
            call.respond(services.foods.save(user.id, call.receive<Food>()))
        }
        put("/{id}") {
            val user = call.requireUser(services)
            call.respond(services.foods.save(user.id, call.receive<Food>(), call.parameters["id"].orEmpty()))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            services.foods.delete(user.id, call.parameters["id"].orEmpty())
            call.respond(mapOf("ok" to true))
        }
    }

    route("/api/water") {
        post {
            val user = call.requireUser(services)
            val entry = services.foods.addWater(user.id, call.receive<WaterEntry>())
            services.days.changedDate(user.id, LocalDate.parse(entry.date))
            call.respond(entry)
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            val date = services.foods.deleteWater(user.id, call.parameters["id"].orEmpty())
            services.days.changedDate(user.id, parseDate(date))
            call.respond(mapOf("ok" to true))
        }
    }
}

suspend fun foodDay(services: Services, userId: String, date: LocalDate): FoodDay {
    val (entries, water) = services.foods.day(userId, date)
    val (target, burned) = services.days.nutritionFor(userId, date)
    return FoodDay(
        date = date.toString(),
        entries = entries,
        water = water,
        totals = entries.fold(Nutrients()) { sum, e -> sum + e.nutrients },
        waterMl = water.sumOf { it.ml },
        target = target,
        burned = burned,
    )
}
