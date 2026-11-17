package com.health.server

import com.health.api.ApiJson
import com.health.api.AppPage
import com.health.api.Appointment
import com.health.api.BloodPressure
import com.health.api.CheckIn
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.CycleDay
import com.health.api.DayFeed
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.DoseLog
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.FoodEntryInput
import com.health.api.Flow
import com.health.api.Fullness
import com.health.api.GenderMoment
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.Goals
import com.health.api.Habit
import com.health.api.HabitTick
import com.health.api.IngestBatch
import com.health.api.LeakSize
import com.health.api.Meal
import com.health.api.MedSchedule
import com.health.api.Medication
import com.health.api.Metric
import com.health.api.Module
import com.health.api.MomentKind
import com.health.api.MoodEntry
import com.health.api.MyCondition
import com.health.api.MyConditions
import com.health.api.NowAction
import com.health.api.NowKind
import com.health.api.Nutrients
import com.health.api.OverloadEvent
import com.health.api.OverloadKind
import com.health.api.Photo
import com.health.api.Pose
import com.health.api.Profile
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SymptomEntry
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.api.VoicePlan
import com.health.api.VoiceSession
import com.health.api.WaterEntry
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.server.care.SharedView
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.imageio.ImageIO

/** Today's feed: one timeline of the day from every feature, and what's live now, as the owner and a viewer see them. */
class FeedTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 3, 20)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    private fun at(hour: Int, minute: Int = 0) = at(today, hour, minute)
    private val now = at(15)
    private var time = now

    private val everything = Module.entries.toSet()

    private fun services() = testServices(clock = { time })

    private fun Services.admin() = runBlocking { users.byUsername("admin")!!.id }

    /** What was made while seeding, for the checks. */
    private class Seeded(val dayPants: String, val vitaminD: String, val magnesium: String, val estradiol: String)

    /** A day across most features: a night, a walk, meals and drinks, doses, the diary, readings, a migraine of a day… */
    private suspend fun seed(s: Services): Seeded {
        val me = s.users.byUsername("admin")!!.id
        s.profiles.put(me, Profile(timezone = zone.id, modules = everything, birthYear = 1990, heightCm = 170.0, goals = Goals(calories = 2000)))
        time = now

        // Sleep, a walk, a weigh-in.
        s.ingest.manualSleep(me, SleepSession(at(today.minusDays(1), 23), at(7)))
        s.ingest.manualWorkout(me, Workout(WorkoutType.WALK, at(10), at(10, 45), distanceM = 3200.0))
        s.ingest.manualSample(me, Sample(Metric.WEIGHT, at(7, 30), value = 72.4))

        // Food (logged as it was eaten), water and a coffee.
        time = at(8, 10)
        s.foods.log(me, FoodEntryInput(today.toString(), Meal.BREAKFAST, name = "Porridge", nutrients = Nutrients(calories = 250.0)))
        s.foods.log(me, FoodEntryInput(today.toString(), Meal.BREAKFAST, name = "Banana", nutrients = Nutrients(calories = 100.0)))
        time = at(12, 40)
        s.foods.log(me, FoodEntryInput(today.toString(), Meal.LUNCH, name = "Pasta salad", nutrients = Nutrients(calories = 610.0)))
        time = now
        s.foods.addWater(me, WaterEntry(today.toString(), 250, at = at(9, 15)))
        s.tracking.drinks.save(me, Drink(at(9), DrinkKind.COFFEE, 240, caffeineMg = 96.0))

        // Medications: one taken, one past its time, one due within the hour; and hormone therapy.
        val sertraline = s.modules.meds.save(me, Medication("Sertraline", "50 mg", schedule = MedSchedule(times = listOf("08:00"))))
        s.modules.meds.logDose(me, DoseLog(sertraline.id!!, at(8, 5), scheduledFor = at(8)))
        val vitaminD = s.modules.meds.save(me, Medication("Vitamin D", "1000 IU", schedule = MedSchedule(times = listOf("14:00"))))
        val magnesium = s.modules.meds.save(me, Medication("Magnesium", "200 mg", schedule = MedSchedule(times = listOf("15:30"))))
        val estradiol = s.modules.meds.save(me, Medication("Estradiol", "2 mg", schedule = MedSchedule(times = listOf("09:00")), hormone = true))
        s.modules.meds.logDose(me, DoseLog(estradiol.id!!, at(9, 10), scheduledFor = at(9)))

        // The bladder and bowel diary: night briefs on last night, day pants on in the morning, changed after lunch.
        val night = s.supplies.save(me, Supply("Night briefs", SupplyKind.BRIEF, onHand = 20))
        val day = s.supplies.save(me, Supply("Day pants", SupplyKind.PULL_UP, onHand = 30))
        val c = s.modules.continence
        c.save(me, ContinenceEntry(at(today.minusDays(1), 22, 30), ContinenceKind.PAD_CHANGE, putOnSupplyId = night.id))
        c.save(me, ContinenceEntry(at(7, 15), ContinenceKind.PAD_CHANGE, supplyId = night.id, putOnSupplyId = day.id, fullness = Fullness.WET))
        c.save(me, ContinenceEntry(at(11), ContinenceKind.URINATION, volumeMl = 300, urgency = 2))
        c.save(me, ContinenceEntry(at(12, 10), ContinenceKind.LEAK, leak = LeakSize.SMALL, triggers = listOf("cough"), supplyId = day.id))
        c.save(me, ContinenceEntry(at(13), ContinenceKind.PAD_CHANGE, supplyId = day.id, putOnSupplyId = day.id, fullness = Fullness.DAMP))

        // Mind, the check-in, readings, a headache, a habit.
        s.modules.mind.saveMood(me, MoodEntry(at(9, 30), mood = 4, feelings = listOf("calm")))
        s.checkIns.put(me, today, CheckIn(today.toString(), mood = 4, energy = 3))
        s.tracking.vitals.saveBloodPressure(me, BloodPressure(at(8), 128, 82, pulse = 70))
        s.tracking.vitals.saveGlucose(me, GlucoseReading(at(12, 45), 6.1, GlucoseContext.AFTER_MEAL))
        s.tracking.symptoms.save(me, SymptomEntry(at(14), "headache", 4, durationMinutes = 60))
        val stretch = s.tracking.habits.save(me, Habit("Stretch"))
        s.tracking.habits.tick(me, stretch.id!!, HabitTick(today.toString(), 1))

        // A GP appointment later today, physio tomorrow.
        s.appointments.save(me, Appointment(at(16, 30), "GP", with = "Dr Patel"))
        s.appointments.save(me, Appointment(at(today.plusDays(1), 10), "Physio"))

        // Autism & ADHD, transition (with yesterday's voice practice), the cycle, a photo.
        s.neuro.saveEvent(me, OverloadEvent(at(13, 30), at(14), OverloadKind.SENSORY_OVERLOAD, intensity = 3))
        s.neuro.saveEnergy(me, today.toString(), EnergyDay(today.toString(), startWith = 12, spends = listOf(EnergySpend("Shopping", 4, at(11)))))
        s.transition.trainer.savePlan(me, VoicePlan(dailyMinutes = 10))
        s.transition.saveVoice(me, VoiceSession(at(today.minusDays(1), 18), 12, pitchAvgHz = 180.0))
        s.transition.saveMoment(me, GenderMoment(at(12), MomentKind.EUPHORIA, intensity = 4, context = listOf("clothes")))
        s.modules.cycle.put(me, CycleDay(today.toString(), flow = Flow.MEDIUM, symptoms = listOf("cramps")))
        s.photos.upload(me, jpeg(), Photo(album = "Progress", pose = Pose.FRONT, at = at(8, 30)))

        // A ring that saw a heart rate high at rest after lunch: a health alert.
        val ring = DeviceInfo(DeviceKind.COLMI_RING, "30:36:43:34:C3:01", "COLMI R02_C301")
        s.ingest.ingest(me, IngestBatch(ring, samples = (0..8).map { Sample(Metric.HEART_RATE, at(13) + it * 5 * 60_000L, value = 128.0) } + Sample(Metric.STEPS, at(12), at(14), 40.0)))
        time = now
        return Seeded(day.id!!, vitaminD.id!!, magnesium.id!!, estradiol.id!!)
    }

    private fun jpeg(): ByteArray {
        val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
    }

    private fun List<TimelineItem>.one(kind: TimelineKind, title: String? = null): TimelineItem =
        filter { it.kind == kind && (title == null || it.title == title) }.let { found ->
            assertEquals("one $kind${title?.let { " ($it)" } ?: ""} in ${map { it.kind to it.title }}", 1, found.size)
            found.single()
        }

    @Test
    fun `the day's timeline has everything, newest first, said plainly`() = runBlocking {
        val s = services()
        s.bootstrap()
        seed(s)
        val feed = s.feed.feed(s.admin(), today.toString())
        val items = feed.items
        assertEquals(today.toString(), feed.date)
        assertEquals("ids are unique", items.size, items.map { it.id }.toSet().size)

        // Newest first; the day's untimed things after, at the day's start.
        val timed = items.takeWhile { !it.allDay }
        val untimed = items.drop(timed.size)
        assertEquals(timed.map { it.at }.sortedDescending(), timed.map { it.at })
        assertTrue(untimed.all { it.allDay && it.at == at(0) })
        assertEquals(setOf(TimelineKind.CHECK_IN, TimelineKind.ENERGY, TimelineKind.HABIT, TimelineKind.CYCLE_DAY), untimed.map { it.kind }.toSet())
        assertEquals("GP", timed.first().title) // later today: the day's plan is part of it
        assertEquals(TimelineKind.SLEEP, timed.last().kind) // began last night

        // Each feature's part, with its words.
        fun check(kind: TimelineKind, title: String, detail: String?, module: Module?, page: AppPage) {
            val item = items.one(kind, title)
            assertEquals("$kind's detail", detail, item.detail)
            assertEquals("$kind's module", module, item.module)
            assertEquals("$kind's page", page, item.page)
        }
        // Both changes put on day pants: the one after lunch, then the morning's.
        assertEquals(listOf("Day pants", "Day pants"), items.filter { it.kind == TimelineKind.PRODUCT_CHANGE }.map { it.title })
        assertTrue(items.filter { it.kind == TimelineKind.PRODUCT_CHANGE }.all { it.module == Module.CONTINENCE && it.page == AppPage.CONTINENCE })
        assertEquals(
            listOf("Changed · the one before was damp after 5 h 45 min", "Changed · the one before was wet after 8 h 45 min"),
            items.filter { it.kind == TimelineKind.PRODUCT_CHANGE }.map { it.detail },
        )
        check(TimelineKind.BATHROOM, "Toilet trip", "300 ml · a strong urge", Module.CONTINENCE, AppPage.CONTINENCE)
        check(TimelineKind.LEAK, "Leak", "Small · cough", Module.CONTINENCE, AppPage.CONTINENCE)
        check(TimelineKind.MEAL, "Pasta salad", "Lunch · 610 kcal", Module.NUTRITION, AppPage.FOOD)
        check(TimelineKind.MEAL, "Porridge and Banana", "Breakfast · 350 kcal", Module.NUTRITION, AppPage.FOOD)
        assertEquals(at(12, 40), items.one(TimelineKind.MEAL, "Pasta salad").at)
        check(TimelineKind.DRINK, "Coffee", "240 ml · 96 mg caffeine", Module.NUTRITION, AppPage.FOOD)
        check(TimelineKind.DRINK, "Water", "250 ml", Module.NUTRITION, AppPage.FOOD)
        check(TimelineKind.DOSE_TAKEN, "Sertraline", "Taken · 50 mg", Module.MEDICATIONS, AppPage.MEDS)
        check(TimelineKind.DOSE_TAKEN, "Estradiol", "Taken · 2 mg", Module.MEDICATIONS, AppPage.MEDS)
        check(TimelineKind.DOSE_CHANGE, "Estradiol", "Started · 2 mg · daily at 09:00", Module.HORMONES, AppPage.HORMONES)
        check(TimelineKind.WORKOUT, "Walk", "45 min · 3.2 km", Module.ACTIVITY, AppPage.ACTIVITY)
        check(TimelineKind.WEIGHT, "Weight", "72.4 kg", Module.BODY, AppPage.BODY)
        check(TimelineKind.MOOD, "Mood", "Good · calm", Module.MIND, AppPage.MIND)
        check(TimelineKind.CHECK_IN, "Check-in", "Mood 4 of 5 · energy 3 of 5", Module.MIND, AppPage.TODAY)
        check(TimelineKind.BLOOD_PRESSURE, "Blood pressure", "128/82 · pulse 70", Module.VITALS, AppPage.VITALS)
        check(TimelineKind.GLUCOSE, "Glucose", "6.1 mmol/L · after a meal", Module.VITALS, AppPage.VITALS)
        check(TimelineKind.SYMPTOM, "Headache", "4 of 10 · 1 h", Module.SYMPTOMS, AppPage.SYMPTOMS)
        check(TimelineKind.HABIT, "Stretch", "Done", Module.HABITS, AppPage.HABITS)
        check(TimelineKind.APPOINTMENT, "GP", "With Dr Patel", Module.APPOINTMENTS, AppPage.APPOINTMENTS)
        check(TimelineKind.OVERLOAD, "Sensory overload", "Intensity 3 of 5 · 30 min", Module.NEURO, AppPage.NEURO)
        check(TimelineKind.ENERGY, "Energy", "Started with 12 · 8 left · 1 thing logged", Module.NEURO, AppPage.NEURO)
        check(TimelineKind.TRANSITION_MOMENT, "Euphoria", "Intensity 4 of 5 · clothes", Module.TRANSITION, AppPage.TRANSITION)
        check(TimelineKind.PHOTO, "Photo added", "Progress · Front", Module.PHOTOS, AppPage.PHOTOS)
        val sleep = items.one(TimelineKind.SLEEP)
        assertEquals(at(7), sleep.end)
        assertTrue(sleep.detail!!, sleep.detail!!.startsWith("8 h asleep"))
        val cycle = items.one(TimelineKind.CYCLE_DAY)
        assertTrue(cycle.title, cycle.title.startsWith("Cycle"))
        assertEquals("Medium flow · cramps", cycle.detail)
        assertEquals(today.toString(), cycle.ref)
        val alert = items.one(TimelineKind.HEALTH_ALERT)
        assertNull(alert.module)
        assertEquals(AppPage.ALERTS, alert.page)
        // Entries carry what the apps open or edit.
        assertNotNull(items.one(TimelineKind.LEAK).ref)
        assertNotNull(items.one(TimelineKind.DOSE_TAKEN, "Sertraline").ref)
        // Ones with a span keep it.
        assertEquals(at(10, 45), items.one(TimelineKind.WORKOUT).end)

        // Another day: yesterday's voice practice, and nothing live.
        val yesterday = s.feed.feed(s.admin(), today.minusDays(1).toString())
        val voice = yesterday.items.one(TimelineKind.VOICE_PRACTICE)
        assertEquals("12 min · around 180 Hz", voice.detail)
        assertTrue(yesterday.now.isEmpty())
        assertTrue(yesterday.items.none { it.kind == TimelineKind.MEAL })
    }

    @Test
    fun `right now has each live thing with its one action, most pressing first`() = runBlocking {
        val s = services()
        s.bootstrap()
        val seeded = seed(s)
        val now = s.feed.feed(s.admin(), "today").now
        assertEquals(
            listOf(
                NowKind.HEALTH_ALERT, NowKind.DOSE_OVERDUE, NowKind.DOSE_DUE, NowKind.APPOINTMENT_SOON, NowKind.APPOINTMENT_SOON,
                NowKind.WEARING, NowKind.VOICE_PRACTICE_DUE, NowKind.FOOD_TODAY, NowKind.ENERGY_TODAY,
            ),
            now.map { it.kind },
        )
        assertEquals(now.map { it.priority }.sortedDescending(), now.map { it.priority })
        assertTrue("every one has an action", now.all { it.action != null })
        fun of(kind: NowKind) = now.first { it.kind == kind }

        val alert = of(NowKind.HEALTH_ALERT)
        assertEquals(NowAction.ALERT_OPEN, alert.action!!.action)
        assertNotNull(alert.action!!.ids["alertId"])
        assertEquals(AppPage.ALERTS, alert.page)

        val overdue = of(NowKind.DOSE_OVERDUE)
        assertEquals("Vitamin D", overdue.title)
        assertEquals("1000 IU · was due at 14:00", overdue.detail)
        assertEquals("Take", overdue.action!!.label)
        assertEquals(NowAction.DOSE_TAKE, overdue.action!!.action)
        assertEquals(mapOf("medicationId" to seeded.vitaminD, "scheduledFor" to at(14).toString()), overdue.action!!.ids)
        val due = of(NowKind.DOSE_DUE)
        assertEquals("Magnesium", due.title)
        assertEquals("200 mg · due at 15:30", due.detail)
        assertEquals(mapOf("medicationId" to seeded.magnesium, "scheduledFor" to at(15, 30).toString()), due.action!!.ids)

        val appointments = now.filter { it.kind == NowKind.APPOINTMENT_SOON }
        assertEquals(listOf("GP", "Physio"), appointments.map { it.title })
        assertEquals("Today at 4:30 PM · with Dr Patel", appointments[0].detail)
        assertTrue(appointments[1].detail!!, appointments[1].detail!!.startsWith("Tomorrow at 10:00 AM"))
        assertEquals(NowAction.APPOINTMENT_OPEN, appointments[0].action!!.action)

        val wearing = of(NowKind.WEARING)
        assertEquals("Day pants", wearing.title)
        assertEquals("On for 2 h, since 13:00", wearing.detail)
        assertEquals("Change", wearing.action!!.label)
        assertEquals(NowAction.CONTINENCE_CHANGE, wearing.action!!.action)
        assertEquals(mapOf("supplyId" to seeded.dayPants), wearing.action!!.ids)
        assertEquals(at(13), wearing.at)

        val voice = of(NowKind.VOICE_PRACTICE_DUE)
        assertEquals("Start", voice.action!!.label)
        assertEquals(NowAction.VOICE_START, voice.action!!.action)
        assertNotNull(voice.action!!.ids["lessonId"])
        assertTrue(voice.detail!!, voice.detail!!.startsWith("About 10 minutes"))

        val food = of(NowKind.FOOD_TODAY)
        assertEquals("960 of 2,000 kcal", food.detail)
        assertEquals(0.48, food.progress!!, 0.001)
        assertEquals("Add", food.action!!.label)
        assertEquals(mapOf("meal" to "SNACK", "date" to today.toString()), food.action!!.ids)

        val energy = of(NowKind.ENERGY_TODAY)
        assertEquals("8 of 12 left", energy.detail)
        assertEquals(8 / 12.0, energy.progress!!, 0.001)
        assertEquals(NowAction.ENERGY_ADD, energy.action!!.action)
        assertEquals(mapOf("date" to today.toString()), energy.action!!.ids)

        // Marking the dose takes it out of "Right now" and puts it in the day.
        s.modules.meds.logDose(s.admin(), DoseLog(seeded.vitaminD, at(15), scheduledFor = at(14)))
        val after = s.feed.feed(s.admin(), "today")
        assertTrue(after.now.none { it.kind == NowKind.DOSE_OVERDUE })
        assertEquals("Taken · 1000 IU · for 14:00", after.items.one(TimelineKind.DOSE_TAKEN, "Vitamin D").detail)
    }

    @Test
    fun `only the features that are on, and hormone doses with hormone therapy alone`() = runBlocking {
        val s = services()
        s.bootstrap()
        seed(s)
        val me = s.admin()
        val off =setOf(Module.CONTINENCE, Module.MIND, Module.MEDICATIONS, Module.NEURO)
        s.profiles.put(me, s.profiles.get(me).copy(modules = everything - off))
        val feed = s.feed.feed(me, "today")
        val kinds = feed.items.map { it.kind }.toSet()
        listOf(TimelineKind.BATHROOM, TimelineKind.LEAK, TimelineKind.PRODUCT_CHANGE, TimelineKind.MOOD, TimelineKind.OVERLOAD, TimelineKind.ENERGY).forEach {
            assertTrue("$it while its feature is off", it !in kinds)
        }
        assertTrue(feed.items.none { it.module in off })
        assertTrue(feed.now.none { it.kind == NowKind.WEARING || it.kind == NowKind.ENERGY_TODAY })
        // The check-in is everyone's: without Mind it carries no feature.
        assertNull(feed.items.one(TimelineKind.CHECK_IN).module)
        // Without Medications, hormone therapy has its own doses, and only those.
        val doses = feed.items.filter { it.kind == TimelineKind.DOSE_TAKEN }
        assertEquals(listOf("Estradiol"), doses.map { it.title })
        assertEquals(Module.HORMONES, doses.single().module)
        assertEquals(AppPage.HORMONES, doses.single().page)
        assertTrue(feed.now.none { it.kind == NowKind.DOSE_DUE || it.kind == NowKind.DOSE_OVERDUE })
        assertTrue(feed.items.none { it.title == "Sertraline" })

        // With food numbers off for the person (eating disorder recovery): meals and food without amounts.
        s.conditions.put(me, MyConditions(listOf(MyCondition("eating-disorder", "Eating disorder recovery"))))
        val quiet = s.feed.feed(me, "today")
        assertEquals("Lunch", quiet.items.one(TimelineKind.MEAL, "Pasta salad").detail)
        val food = quiet.now.first { it.kind == NowKind.FOOD_TODAY }
        assertEquals("Breakfast and lunch logged", food.detail)
        assertNull(food.progress)
    }

    @Test
    fun `a viewer sees only the shared features' timeline, nothing live and no alerts`() {
        val s = services()
        serverTest(s) {
            seed(s)
            val ownerId = s.admin()
            s.users.create("sam", "Sam", password = "sam-password-123")
            s.users.create("kim", "Kim", password = "kim-password-123")
            val owner = adminBrowser()

            // The owner, through the API.
            val mine = ApiJson.decodeFromString(DayFeed.serializer(), owner.get("/api/feed?date=${today}").bodyAsText())
            assertTrue(mine.now.isNotEmpty())
            assertTrue(mine.items.any { it.kind == TimelineKind.HEALTH_ALERT })
            assertEquals(HttpStatusCode.BadRequest, owner.get("/api/feed?date=yesterdayish").status)

            assertEquals(HttpStatusCode.OK, owner.postJson("/api/shares", """{"username":"sam","modules":["ACTIVITY","CONTINENCE","NUTRITION"]}""").status)
            val viewer = browser().also { it.post("/api/auth/login") { json("""{"username":"sam","password":"sam-password-123"}""") } }
            val seen = viewer.get("/api/feed?date=$today") { header(SharedView.HEADER, ownerId) }
            assertEquals(HttpStatusCode.OK, seen.status)
            val shared = ApiJson.decodeFromString(DayFeed.serializer(), seen.bodyAsText())
            assertTrue("nothing live for a viewer", shared.now.isEmpty())
            val shown = setOf(Module.ACTIVITY, Module.CONTINENCE, Module.NUTRITION)
            assertTrue(shared.items.all { it.module in shown })
            assertEquals(
                setOf(TimelineKind.WORKOUT, TimelineKind.BATHROOM, TimelineKind.LEAK, TimelineKind.PRODUCT_CHANGE, TimelineKind.MEAL, TimelineKind.DRINK),
                shared.items.map { it.kind }.toSet(),
            )
            assertTrue(shared.items.none { it.kind == TimelineKind.HEALTH_ALERT || it.kind == TimelineKind.CHECK_IN || it.kind == TimelineKind.DOSE_TAKEN })
            assertEquals("Lunch · 610 kcal", shared.items.one(TimelineKind.MEAL, "Pasta salad").detail)
            // In the same order as the owner's.
            assertEquals(mine.items.filter { it.module in shown }.map { it.id }, shared.items.map { it.id })

            // A viewer in eating disorder recovery sees no amounts of food.
            s.conditions.put(s.users.byUsername("sam")!!.id, MyConditions(listOf(MyCondition("eating-disorder", "Eating disorder recovery"))))
            val quiet = ApiJson.decodeFromString(DayFeed.serializer(), viewer.get("/api/feed?date=$today") { header(SharedView.HEADER, ownerId) }.bodyAsText())
            assertEquals("Lunch", quiet.items.one(TimelineKind.MEAL, "Pasta salad").detail)

            // Someone it isn't shared with sees nothing.
            val kim = browser().also { it.post("/api/auth/login") { json("""{"username":"kim","password":"kim-password-123"}""") } }
            assertEquals(HttpStatusCode.Forbidden, kim.get("/api/feed") { header(SharedView.HEADER, ownerId) }.status)
        }
    }

    @Test
    fun `an alert that could need care now says when to call emergency services, first`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id))
        val strap = DeviceInfo(DeviceKind.HEART_RATE_STRAP, "strap-1", "Chest strap")
        s.ingest.ingest(me, IngestBatch(strap, samples = (0..8).map { Sample(Metric.HEART_RATE, at(13) + it * 5 * 60_000L, value = 165.0) } + Sample(Metric.STEPS, at(12), at(14), 40.0)))
        val feed = s.feed.feed(me, "today")
        val alert = feed.now.first()
        assertEquals(NowKind.HEALTH_ALERT, alert.kind)
        assertEquals(100, alert.priority)
        assertEquals(com.health.server.insights.AlertRules.EMERGENCY, alert.detail)
        assertEquals(com.health.server.insights.AlertRules.EMERGENCY, feed.items.one(TimelineKind.HEALTH_ALERT).detail)
        // Only the features that are on: food today, nothing else live.
        assertEquals(listOf(NowKind.HEALTH_ALERT, NowKind.FOOD_TODAY), feed.now.map { it.kind })
        assertEquals("Nothing logged yet", feed.now.last().detail)
    }

    @Test
    fun `a glucose sensor's day is one item, a finger-prick reading its own`() = runBlocking {
        val s = services()
        s.bootstrap()
        val me = s.admin()
        s.profiles.put(me, Profile(timezone = zone.id, modules = setOf(Module.VITALS)))
        val sensor = DeviceInfo(DeviceKind.HEALTH_CONNECT, "hc-1", "Phone")
        val readings = (0 until 30).map { GlucoseReading(at(6) + it * 5 * 60_000L, if (it < 27) 6.0 else 11.0, GlucoseContext.CGM) }
        s.ingest.ingest(me, IngestBatch(sensor, glucose = readings))
        s.tracking.vitals.saveGlucose(me, GlucoseReading(at(12), 5.4, GlucoseContext.BEFORE_MEAL))
        val items = s.feed.feed(me, "today").items
        assertEquals(listOf("Glucose", "Glucose sensor"), items.map { it.title })
        val day = items.one(TimelineKind.GLUCOSE, "Glucose sensor")
        assertEquals("Latest 11.0 mmol/L · 90% in range · 30 readings", day.detail)
        assertEquals(at(6), day.at)
        assertEquals(at(8, 25), day.end)
        assertEquals("5.4 mmol/L · before a meal", items.one(TimelineKind.GLUCOSE, "Glucose").detail)
    }

    @Test
    fun `a feed for a viewer, worked out on its own`() {
        val feed = DayFeed(
            "2026-03-20",
            now = listOf(com.health.api.NowItem("food:x", NowKind.FOOD_TODAY, "Food today", module = Module.NUTRITION)),
            items = listOf(
                TimelineItem("meal:x", TimelineKind.MEAL, 3, title = "Toast", detail = "Breakfast · 1,250 kcal", module = Module.NUTRITION, page = AppPage.FOOD),
                TimelineItem("health_alert:x", TimelineKind.HEALTH_ALERT, 2, title = "Alert", page = AppPage.ALERTS),
                TimelineItem("check_in:x", TimelineKind.CHECK_IN, 1, title = "Check-in", page = AppPage.TODAY),
                TimelineItem("mood:x", TimelineKind.MOOD, 0, title = "Mood", module = Module.MIND, page = AppPage.MIND),
            ),
        )
        val seen = SharedView.limit(feed, setOf(Module.NUTRITION), foodNumbers = false)
        assertTrue(seen.now.isEmpty())
        assertEquals(listOf("meal:x"), seen.items.map { it.id })
        assertEquals("Breakfast", seen.items.single().detail)
        assertEquals("Breakfast · 1,250 kcal", SharedView.limit(feed, setOf(Module.NUTRITION)).items.single().detail)
        // A kind from a newer server still reads in the Android app (which coerces what it doesn't know), as OTHER.
        val app = kotlinx.serialization.json.Json(ApiJson) { coerceInputValues = true }
        val newer = app.decodeFromString(TimelineItem.serializer(), """{"id":"x:1","kind":"SOMETHING_NEW","at":1,"title":"New","page":"SOMEWHERE"}""")
        assertEquals(TimelineKind.OTHER, newer.kind)
        assertNull(newer.page)
        assertEquals(NowKind.OTHER, app.decodeFromString(com.health.api.NowItem.serializer(), """{"id":"n","kind":"LATER","title":"Later"}""").kind)
    }
}
