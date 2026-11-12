package com.health.app.net

import com.health.api.ApiJson
import com.health.api.BloodPressure
import com.health.api.Drink
import com.health.api.DrinksDay
import com.health.api.DrinksSummary
import com.health.api.GlucoseReading
import com.health.api.Habit
import com.health.api.HabitStatus
import com.health.api.HabitTick
import com.health.api.Migraine
import com.health.api.Place
import com.health.api.SymptomEntry
import com.health.api.SymptomsSummary
import com.health.api.VitalsSummary
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/*
 * Blood pressure and glucose, drinks, symptoms and migraines, and habits (docs/API.md).
 */

private suspend fun <T> ServerApi.put(path: String, body: T, serializer: KSerializer<T>): T =
    send("PUT", "${base()}$path", ApiJson.encodeToString(serializer, body), serializer)

// ---- Blood pressure and glucose ----

suspend fun ServerApi.bloodPressure(days: Int): List<BloodPressure> =
    get("${base()}/api/vitals/bp?days=$days", ListSerializer(BloodPressure.serializer()))

suspend fun ServerApi.addBloodPressure(reading: BloodPressure): BloodPressure = post("/api/vitals/bp", reading, BloodPressure.serializer())

suspend fun ServerApi.deleteBloodPressure(id: String) = delete("/api/vitals/bp/${enc(id)}")

suspend fun ServerApi.glucose(days: Int): List<GlucoseReading> =
    get("${base()}/api/vitals/glucose?days=$days", ListSerializer(GlucoseReading.serializer()))

suspend fun ServerApi.addGlucose(reading: GlucoseReading): GlucoseReading = post("/api/vitals/glucose", reading, GlucoseReading.serializer())

suspend fun ServerApi.deleteGlucose(id: String) = delete("/api/vitals/glucose/${enc(id)}")

suspend fun ServerApi.vitalsSummary(days: Int): VitalsSummary = get("${base()}/api/vitals/summary?days=$days", VitalsSummary.serializer())

// ---- Drinks ----

suspend fun ServerApi.drinksDay(date: String): DrinksDay = get("${base()}/api/drinks/${enc(date)}", DrinksDay.serializer())

suspend fun ServerApi.addDrink(drink: Drink): Drink = post("/api/drinks", drink, Drink.serializer())

suspend fun ServerApi.updateDrink(drink: Drink): Drink = put("/api/drinks/${enc(drink.id!!)}", drink, Drink.serializer())

suspend fun ServerApi.deleteDrink(id: String) = delete("/api/drinks/${enc(id)}")

suspend fun ServerApi.drinksSummary(days: Int): DrinksSummary = get("${base()}/api/drinks/summary?days=$days", DrinksSummary.serializer())

// ---- Symptoms and migraines ----

suspend fun ServerApi.symptoms(days: Int): List<SymptomEntry> =
    get("${base()}/api/symptoms/entries?days=$days", ListSerializer(SymptomEntry.serializer()))

suspend fun ServerApi.addSymptom(entry: SymptomEntry): SymptomEntry = post("/api/symptoms/entries", entry, SymptomEntry.serializer())

suspend fun ServerApi.updateSymptom(entry: SymptomEntry): SymptomEntry = put("/api/symptoms/entries/${enc(entry.id!!)}", entry, SymptomEntry.serializer())

suspend fun ServerApi.deleteSymptom(id: String) = delete("/api/symptoms/entries/${enc(id)}")

suspend fun ServerApi.migraines(days: Int): List<Migraine> =
    get("${base()}/api/symptoms/migraines?days=$days", ListSerializer(Migraine.serializer()))

suspend fun ServerApi.addMigraine(migraine: Migraine): Migraine = post("/api/symptoms/migraines", migraine, Migraine.serializer())

suspend fun ServerApi.updateMigraine(migraine: Migraine): Migraine = put("/api/symptoms/migraines/${enc(migraine.id!!)}", migraine, Migraine.serializer())

suspend fun ServerApi.deleteMigraine(id: String) = delete("/api/symptoms/migraines/${enc(id)}")

suspend fun ServerApi.symptomsSummary(days: Int): SymptomsSummary = get("${base()}/api/symptoms/summary?days=$days", SymptomsSummary.serializer())

/** Places matching [query], for the weather (through the server; the app never calls out itself). */
suspend fun ServerApi.places(query: String): List<Place> = get("${base()}/api/places?q=${enc(query)}", ListSerializer(Place.serializer()))

// ---- Habits ----

suspend fun ServerApi.habits(all: Boolean = false): List<Habit> = get("${base()}/api/habits?all=$all", ListSerializer(Habit.serializer()))

suspend fun ServerApi.addHabit(habit: Habit): Habit = post("/api/habits", habit, Habit.serializer())

suspend fun ServerApi.updateHabit(habit: Habit): Habit = put("/api/habits/${enc(habit.id!!)}", habit, Habit.serializer())

suspend fun ServerApi.deleteHabit(id: String) = delete("/api/habits/${enc(id)}")

suspend fun ServerApi.habitsToday(date: String? = null): List<HabitStatus> =
    get("${base()}/api/habits/today" + (date?.let { "?date=${enc(it)}" } ?: ""), ListSerializer(HabitStatus.serializer()))

/** Sets a habit's count for a day (0 clears it). */
suspend fun ServerApi.tickHabit(id: String, tick: HabitTick): HabitTick = put("/api/habits/${enc(id)}/ticks", tick, HabitTick.serializer())

suspend fun ServerApi.habitHistory(id: String, days: Int): List<HabitTick> =
    get("${base()}/api/habits/${enc(id)}/history?days=$days", ListSerializer(HabitTick.serializer()))
