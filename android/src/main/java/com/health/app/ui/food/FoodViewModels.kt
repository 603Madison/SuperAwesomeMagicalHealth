package com.health.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.DrinksDay
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntryChange
import com.health.api.FoodEntryInput
import com.health.api.FoodSearch
import com.health.api.FoodSuggestion
import com.health.api.FoodSuggestions
import com.health.api.toInput
import com.health.app.net.foodSuggestions
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.api.WaterEntry
import com.health.app.Container
import com.health.app.net.addDrink
import com.health.app.net.deleteDrink
import com.health.app.net.drinksDay
import com.health.app.net.updateDrink
import com.health.app.ui.tracking.quickDrink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class FoodState(
    val date: LocalDate,
    val today: LocalDate,
    val day: FoodDay? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The day's coffee, tea, alcohol and the rest (ui/tracking). */
    val drinks: DrinksDay? = null,
    /** Ideas from what's been eaten before (today only), for [ideasMeal] or the meal it is now. */
    val ideas: FoodSuggestions? = null,
    val ideasMeal: Meal? = null,
    /** Why there are no ideas: an older server, or no answer. */
    val ideasError: String? = null,
)

class FoodViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LocalDate.now(c.zone()).let { FoodState(it, it) })
    val state: StateFlow<FoodState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val today = LocalDate.now(c.zone())
        val date = _state.value.date
        job?.cancel()
        _state.update { it.copy(today = today, loading = !quiet || it.day == null, error = null) }
        job = viewModelScope.launch {
            try {
                val day = c.ui.foodDay(date.toString())
                val drinks = runCatching { c.ui.drinksDay(date.toString()) }.getOrNull()
                _state.update { if (it.date == date) it.copy(day = day, drinks = drinks, loading = false) else it }
                if (date == today) loadIdeas()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the day") }
            }
        }
    }

    fun go(date: LocalDate) {
        _state.update { it.copy(date = date, day = null, ideas = null, ideasError = null) }
        load()
    }

    private suspend fun loadIdeas() {
        val s = _state.value
        val result = runCatching { c.ui.foodSuggestions(s.date.toString(), s.ideasMeal) }
        _state.update {
            if (it.date != s.date) it
            else it.copy(ideas = result.getOrNull(), ideasError = result.exceptionOrNull()?.let { e -> e.message ?: "Ideas aren't available just now" })
        }
    }

    /** Ideas for another meal. */
    fun ideasFor(meal: Meal) {
        _state.update { it.copy(ideasMeal = meal, ideas = null, ideasError = null) }
        viewModelScope.launch { loadIdeas() }
    }

    /** Logs an idea's items on the day shown, at the ideas' meal, then offers Undo. */
    fun addIdea(idea: FoodSuggestion) {
        viewModelScope.launch {
            val date = _state.value.date.toString()
            val meal = _state.value.ideas?.meal ?: idea.meal
            val added = mutableListOf<com.health.api.FoodEntry>()
            try {
                idea.items.forEach { added += c.ui.addFood(it.toInput(date, meal)) }
            } catch (e: Exception) {
                added.forEach { a -> a.id?.let { id -> runCatching { c.ui.deleteFood(id) } } }
                c.tell(e.message ?: "Couldn't add that")
                return@launch
            }
            c.dataChanged()
            c.tell("Added to ${com.health.app.ui.today.InsightLinks.mealWord(meal)}: ${idea.name}", "Undo") {
                c.scope.launch {
                    runCatching { added.forEach { a -> a.id?.let { id -> c.ui.deleteFood(id) } } }
                        .onSuccess { c.tell("Taken back off") }
                        .onFailure { c.tell(it.message ?: "Couldn't undo that") }
                    c.dataChanged()
                }
            }
        }
    }

    private fun act(message: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                message?.let(c::tell)
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "That didn't work")
            }
        }
    }

    fun delete(id: String) = act("Removed") { c.ui.deleteFood(id) }

    fun change(id: String, change: FoodEntryChange) = act(null) { c.ui.changeFood(id, change) }

    fun addWater(ml: Int) = act("Added $ml ml of water") { c.ui.addWater(WaterEntry(_state.value.date.toString(), ml)) }

    fun deleteWater(id: String) = act(null) { c.ui.deleteWater(id) }

    /** A drink: a quick one now on today, else at noon on the day shown. */
    private fun drinkTime(): Long = _state.value.let { s ->
        if (s.date == s.today) System.currentTimeMillis() else s.date.atTime(12, 0).atZone(c.zone()).toInstant().toEpochMilli()
    }

    fun quickDrink(kind: DrinkKind) = act("${kind.label} logged") { c.api.addDrink(quickDrink(kind, drinkTime())) }

    fun saveDrink(drink: Drink) = act("Saved") { if (drink.id == null) c.api.addDrink(drink) else c.api.updateDrink(drink) }

    fun deleteDrink(id: String) = act(null) { c.api.deleteDrink(id) }

    /** Copies yesterday's [meal] (or the whole day) to the day shown. */
    fun copyYesterday(meal: Meal?) = act("Copied from the day before") {
        val to = _state.value.date
        c.ui.copyFood(to.minusDays(1).toString(), to.toString(), meal)
    }
}

data class AddFoodState(
    val query: String = "",
    val results: FoodSearch? = null,
    val recent: List<Food> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    /** A food found (by barcode, or picked) waiting for how much. */
    val picked: Food? = null,
    /** A barcode nobody knows, offered for a new food. */
    val unknownBarcode: String? = null,
    val done: Boolean = false,
)

class AddFoodViewModel(private val c: Container, val date: LocalDate, val meal: Meal) : ViewModel() {
    private val _state = MutableStateFlow(AddFoodState())
    val state: StateFlow<AddFoodState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { c.ui.recentFoods() }.onSuccess { recent -> _state.update { it.copy(recent = recent) } }
        }
    }

    fun query(text: String) {
        _state.update { it.copy(query = text, error = null) }
        searchJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(results = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(searching = true) }
            try {
                val results = c.ui.searchFoods(text.trim())
                _state.update { if (it.query == text) it.copy(results = results, searching = false) else it }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't search") }
            }
        }
    }

    fun pick(food: Food?) = _state.update { it.copy(picked = food) }

    fun barcode(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            try {
                val food = c.ui.barcode(code)
                _state.update { it.copy(searching = false, picked = food, unknownBarcode = if (food == null) code else null) }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't look that up") }
            }
        }
    }

    fun dismissUnknown() = _state.update { it.copy(unknownBarcode = null) }

    fun add(food: Food, servings: Double) = save { c.ui.addFood(FoodEntryInput(date.toString(), meal, servings, food = food)) }

    fun quickAdd(name: String, nutrients: Nutrients) =
        save { c.ui.addFood(FoodEntryInput(date.toString(), meal, 1.0, name = name.ifBlank { "Quick add" }, nutrients = nutrients)) }

    /** Saves a new food of the person's own, then logs [servings] of it. */
    fun create(food: Food, servings: Double) = save {
        val saved = c.ui.createFood(food)
        c.ui.addFood(FoodEntryInput(date.toString(), meal, servings, food = saved))
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                c.dataChanged()
                c.tell("Added to ${meal.label.lowercase()}")
                _state.update { it.copy(done = true, picked = null, unknownBarcode = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't add that") }
            }
        }
    }
}
