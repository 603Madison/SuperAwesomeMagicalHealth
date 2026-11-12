package com.health.app.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.Food
import com.health.api.FoodDay
import com.health.api.FoodEntryChange
import com.health.api.FoodEntryInput
import com.health.api.FoodSearch
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.api.WaterEntry
import com.health.app.Container
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
                val day = c.api.foodDay(date.toString())
                _state.update { if (it.date == date) it.copy(day = day, loading = false) else it }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the day") }
            }
        }
    }

    fun go(date: LocalDate) {
        _state.update { it.copy(date = date, day = null) }
        load()
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

    fun delete(id: String) = act("Removed") { c.api.deleteFood(id) }

    fun change(id: String, change: FoodEntryChange) = act(null) { c.api.changeFood(id, change) }

    fun addWater(ml: Int) = act("Added $ml ml of water") { c.api.addWater(WaterEntry(_state.value.date.toString(), ml)) }

    fun deleteWater(id: String) = act(null) { c.api.deleteWater(id) }

    /** Copies yesterday's [meal] (or the whole day) to the day shown. */
    fun copyYesterday(meal: Meal?) = act("Copied from the day before") {
        val to = _state.value.date
        c.api.copyFood(to.minusDays(1).toString(), to.toString(), meal)
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
            runCatching { c.api.recentFoods() }.onSuccess { recent -> _state.update { it.copy(recent = recent) } }
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
                val results = c.api.searchFoods(text.trim())
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
                val food = c.api.barcode(code)
                _state.update { it.copy(searching = false, picked = food, unknownBarcode = if (food == null) code else null) }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't look that up") }
            }
        }
    }

    fun dismissUnknown() = _state.update { it.copy(unknownBarcode = null) }

    fun add(food: Food, servings: Double) = save { c.api.addFood(FoodEntryInput(date.toString(), meal, servings, food = food)) }

    fun quickAdd(name: String, nutrients: Nutrients) =
        save { c.api.addFood(FoodEntryInput(date.toString(), meal, 1.0, name = name.ifBlank { "Quick add" }, nutrients = nutrients)) }

    /** Saves a new food of the person's own, then logs [servings] of it. */
    fun create(food: Food, servings: Double) = save {
        val saved = c.api.createFood(food)
        c.api.addFood(FoodEntryInput(date.toString(), meal, servings, food = saved))
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
