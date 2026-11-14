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
import com.health.api.MealiePlan
import com.health.api.MealieRecipes
import com.health.api.toInput
import com.health.app.attempt
import com.health.app.net.foodSuggestions
import com.health.api.Meal
import com.health.api.Nutrients
import com.health.api.WaterEntry
import com.health.app.Container
import com.health.app.net.addDrink
import com.health.app.net.aiStatus
import com.health.app.net.deleteDrink
import com.health.app.net.drinksDay
import com.health.app.net.mealiePlan
import com.health.app.net.mealieRecipes
import com.health.app.net.mealieStatus
import com.health.app.net.updateDrink
import com.health.app.ui.tracking.quickDrink
import kotlinx.coroutines.CancellationException
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
    /** Mealie is connected (never asked while viewing a share): the day's meal plan shows. */
    val mealie: Boolean = false,
    /** The day's meal plan in Mealie, once it's come (or why it couldn't). */
    val plan: MealiePlan? = null,
)

class FoodViewModel(private val c: Container) : ViewModel() {
    private val _state = MutableStateFlow(LocalDate.now(c.zone()).let { FoodState(it, it) })
    val state: StateFlow<FoodState> = _state.asStateFlow()
    private var job: Job? = null
    private var planJob: Job? = null

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        val today = LocalDate.now(c.zone())
        val date = _state.value.date
        job?.cancel()
        _state.update { it.copy(today = today, loading = !quiet || it.day == null, error = null) }
        loadPlan(date)
        job = viewModelScope.launch {
            try {
                val day = c.ui.foodDay(date.toString())
                val drinks = attempt { c.ui.drinksDay(date.toString()) }.getOrNull()
                _state.update { if (it.date == date) it.copy(day = day, drinks = drinks, loading = false) else it }
                if (date == today) loadIdeas()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the day") }
            }
        }
    }

    fun go(date: LocalDate) {
        _state.update { it.copy(date = date, day = null, ideas = null, ideasError = null, plan = null) }
        load()
    }

    /**
     * The day's meal plan, when Mealie is connected: asked apart from the day, so a slow Mealie
     * doesn't hold it up. Never while viewing a share (the plan is the person's own).
     */
    private fun loadPlan(date: LocalDate) {
        planJob?.cancel()
        if (c.viewing.value != null) {
            _state.update { it.copy(mealie = false, plan = null) }
            return
        }
        planJob = viewModelScope.launch {
            val connected = try {
                c.api.mealieStatus().connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!connected) {
                _state.update { it.copy(mealie = false, plan = null) }
                return@launch
            }
            val plan = try {
                c.api.mealiePlan(date.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MealiePlan(date.toString(), problem = MealieText.problem(e))
            }
            _state.update { if (it.date == date) it.copy(mealie = true, plan = plan) else it }
        }
    }

    /** Logs one serving of a planned recipe to its meal, on the day shown, then offers Undo. */
    fun addPlanned(index: Int) {
        val s = _state.value
        val plan = s.plan ?: return
        val entry = plan.entries.getOrNull(index) ?: return
        val food = entry.recipe?.food ?: return
        if (entry.logged || c.viewing.value != null) return
        // Marked at once, so a second tap doesn't add it twice; the reload brings the server's word.
        _state.update { if (it.plan == plan) it.copy(plan = MealieText.logged(plan, index)) else it }
        viewModelScope.launch {
            val added = try {
                c.api.addFood(FoodEntryInput(s.date.toString(), entry.meal, 1.0, food = food))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { st -> if (st.plan?.date == plan.date) st.copy(plan = plan) else st }
                c.tell(e.message ?: "Couldn't add that")
                return@launch
            }
            c.dataChanged()
            c.tell("Added to ${com.health.app.ui.today.InsightLinks.mealWord(entry.meal)}: ${entry.title}", "Undo") {
                c.scope.launch {
                    attempt { added.id?.let { id -> c.api.deleteFood(id) } }
                        .onSuccess { c.tell("Taken back off") }
                        .onFailure { c.tell(it.message ?: "Couldn't undo that") }
                    c.dataChanged()
                }
            }
        }
    }

    private suspend fun loadIdeas() {
        val s = _state.value
        val result = attempt { c.ui.foodSuggestions(s.date.toString(), s.ideasMeal) }
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
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                added.forEach { a -> a.id?.let { id -> attempt { c.ui.deleteFood(id) } } }
                c.tell(e.message ?: "Couldn't add that")
                return@launch
            }
            c.dataChanged()
            c.tell("Added to ${com.health.app.ui.today.InsightLinks.mealWord(meal)}: ${idea.name}", "Undo") {
                c.scope.launch {
                    attempt { added.forEach { a -> a.id?.let { id -> c.ui.deleteFood(id) } } }
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
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
    /** "Snap a photo" (FoodPhoto.kt), a line pointing to Settings, or nothing. */
    val photoOffer: PhotoOffer? = null,
    /** Mealie is connected (and this isn't someone else's share): its recipes are searched too. */
    val mealieOn: Boolean = false,
    /** Mealie's recipes for [query], once they've come. */
    val mealie: MealieRecipes? = null,
    val mealieSearching: Boolean = false,
)

class AddFoodViewModel(private val c: Container, val date: LocalDate, val meal: Meal) : ViewModel() {
    private val _state = MutableStateFlow(AddFoodState())
    val state: StateFlow<AddFoodState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var mealieJob: Job? = null

    init {
        viewModelScope.launch {
            attempt { c.ui.recentFoods() }.onSuccess { recent -> _state.update { it.copy(recent = recent) } }
        }
        // The assistant and Mealie are only ever the person's own: neither is asked while viewing a share.
        if (c.viewing.value == null) viewModelScope.launch {
            attempt { c.api.aiStatus() }.onSuccess { s -> _state.update { it.copy(photoOffer = FoodPhotoText.offer(s, c.viewing.value != null)) } }
        }
        if (c.viewing.value == null) viewModelScope.launch {
            attempt { c.api.mealieStatus() }.onSuccess { s ->
                if (s.connected && c.viewing.value == null) {
                    _state.update { it.copy(mealieOn = true) }
                    // Something typed before the answer came is looked for in Mealie too.
                    if (_state.value.query.isNotBlank()) searchMealie(_state.value.query)
                }
            }
        }
    }

    fun query(text: String) {
        _state.update { it.copy(query = text, error = null) }
        searchJob?.cancel()
        if (text.isBlank()) {
            mealieJob?.cancel()
            _state.update { it.copy(results = null, searching = false, mealie = null, mealieSearching = false) }
            return
        }
        // Mealie is asked alongside the server's own search, and each shows when it comes.
        searchMealie(text)
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(searching = true) }
            try {
                val results = c.ui.searchFoods(text.trim())
                _state.update { if (it.query == text) it.copy(results = results, searching = false) else it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.message ?: "Couldn't search") }
            }
        }
    }

    /** Mealie's recipes for [text], when it's connected and there's enough to look for. */
    private fun searchMealie(text: String) {
        mealieJob?.cancel()
        val query = text.trim()
        if (!_state.value.mealieOn || query.length < MealieText.MIN_QUERY) {
            _state.update { it.copy(mealie = null, mealieSearching = false) }
            return
        }
        _state.update { it.copy(mealie = null, mealieSearching = true) }
        mealieJob = viewModelScope.launch {
            delay(350)
            val found = try {
                c.api.mealieRecipes(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MealieRecipes(problem = MealieText.problem(e))
            }
            _state.update { if (it.query == text) it.copy(mealie = found, mealieSearching = false) else it }
        }
    }

    fun pick(food: Food?) = _state.update { it.copy(picked = food) }

    fun barcode(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            try {
                val food = c.ui.barcode(code)
                _state.update { it.copy(searching = false, picked = food, unknownBarcode = if (food == null) code else null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't add that") }
            }
        }
    }
}
