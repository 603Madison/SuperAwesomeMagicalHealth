package com.health.server.food

import com.health.api.ConditionStatus
import com.health.api.Fields
import com.health.api.Food
import com.health.api.MealieRecipe
import com.health.server.conditions.ConditionsService
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import kotlinx.coroutines.CancellationException

/**
 * Puts each person's grade ([Grades]) on the foods sent to them: from search, barcode, Mealie,
 * their own foods and the shopping list. Never while grades are off ([Fields.FOOD_GRADES]) or
 * amounts of food don't show (eating disorder recovery), and never for a share (those paths
 * aren't shared).
 */
class FoodGrader(
    private val conditions: ConditionsService,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val clock: () -> Long,
) {
    /** What [userId]'s grades weigh foods against; null while grades are off for them. */
    suspend fun goals(userId: String): GradeGoals? {
        val layout = conditions.layout(userId)
        if (!layout.foodNumbers || Fields.FOOD_GRADES !in layout.effective) return null
        val profile = profiles.get(userId)
        val target = try {
            days.nutritionFor(userId, profiles.days(userId).today(clock())).first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val ids = conditions.get(userId).conditions.filter { it.status != ConditionStatus.PAST }.map { it.id }.toSet()
        return GradeGoals(target, ids, profile.goals.weeklyChangeKg)
    }

    fun graded(food: Food, goals: GradeGoals?): Food = food.copy(grade = goals?.let { Grades.grade(food, it) })

    fun graded(foods: List<Food>, goals: GradeGoals?): List<Food> = foods.map { graded(it, goals) }

    fun graded(recipe: MealieRecipe, goals: GradeGoals?): MealieRecipe = recipe.copy(food = recipe.food?.let { graded(it, goals) })

    suspend fun graded(userId: String, food: Food): Food = graded(food, goals(userId))

    suspend fun graded(userId: String, foods: List<Food>): List<Food> = graded(foods, goals(userId))
}
