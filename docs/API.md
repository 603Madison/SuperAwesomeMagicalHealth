# The server's API

Everything is JSON, with the models in `shared/src/main/kotlin/com/health/api/` (the web app's
copies are in `web/src/lib/types.ts`). Units are metric (kg, cm, metres, kcal, ml, °C); instants
are epoch milliseconds; days are the person's local dates, `YYYY-MM-DD`, in their profile's time
zone (`today` works wherever a date does).

**Signing in.** The web app uses a session cookie and sends `X-SAMH: 1` with every change (other
sites' pages can't add it). The Android app sends `Authorization: Bearer <token>`. Upload keys
(also bearer tokens) only work for `POST /api/ingest` and `POST /api/import/fit`.

Errors come back as `{"error": "A message meant for people"}` with a fitting status.

## Accounts

| | |
|---|---|
| `GET /api/health` | `{ok, version}` |
| `GET /api/auth/config` | `AuthConfig`: server name, whether passwords are offered, the SSO button |
| `POST /api/auth/login` | `LoginRequest` → `SignedIn` (`app: true` gets a token instead of a cookie) |
| `POST /api/auth/logout` | Ends this sign-in |
| `GET /api/auth/oidc/start?return=/path` | Off to the identity provider (Authentik…) |
| `GET /api/auth/oidc/callback` | And back |
| `POST /api/auth/app/code` | (cookie only) `AppCodeRequest` → `AppCodeResponse`: the web page hands the app a one-time code |
| `POST /api/auth/app/token` | `AppTokenRequest` → `SignedIn` with the app's token |
| `GET /api/me` | `Me` |
| `PATCH /api/me` | `{displayName}` → `User` |
| `POST /api/me/password` | `{current, new}`. A password an admin set (`User.mustChangePassword`) must be changed here first: until then every other route but `/api/auth/…` and `GET /api/me` answers 403 "Choose a new password first" |
| `GET /api/me/sessions` · `DELETE /api/me/sessions/{id}` | Signed-in browsers, apps, upload keys and connector links |
| `POST /api/me/connectors` | `ConnectorRequest` → `ConnectorLink`: an AI connector (MCP) link, shown once |

The app's browser sign-in: open `<server>/connect-app?challenge=<S256 of verifier>&state=<random>&device=<name>`
in a Custom Tab; the page sends the person to `samhealth://signed-in?code=…&state=…`; the app
then calls `POST /api/auth/app/token` with the code and its verifier.

## Profile and days

| | |
|---|---|
| `GET /api/profile` · `PUT /api/profile` | `Profile` (birth year, height, energy profile, units, time zone, `Goals`) |
| `GET /api/day/{date}` | `DaySummary`: scores with their contributors, activity, heart, sleep, food, insights |
| `GET /api/trends?days=30` (or `from=&to=`) | `Trends`: a `TrendDay` per day |
| `GET /api/series?metric=HEART_RATE&from=&to=` | `[Point]`, thinned to a few hundred points |

Each `Insight` may name the page it's about in `link` (an `AppPage`: `TODAY` for the scores,
`FOOD`, `MEDS`, `SLEEP`, `ACTIVITY`, `TRENDS`, `BODY`, `DEVICES`, `ALERTS` and each feature's page);
a tap opens it. Without a link, the apps open the page of its `module` (`AppPage.of`); with neither,
it isn't a link. Viewing a share, pages that aren't shared (and Devices and Health alerts) aren't
offered. `tone` is `"good"`, `"info"`, `"warn"`, or `"alert"` for a health alert that could need
care now, which the apps set apart in the theme's danger colours. `shownBy`
names the Today card that already shows the same thing (the day's doses: `MEDS`): the apps leave
the insight out while that card shows. The protein insight (today only) carries up to three
`suggestions` (`FoodSuggestion`, as below) and names the top one or two in its body.

### Today's feed

| | |
|---|---|
| `GET /api/feed?date=YYYY-MM-DD` (default today) | `DayFeed`: `{date, now, items}` for Today's "Right now" and "Your day" |

The day is the person's, in their time zone. Every feature that's on adds its part (the platform's
`FeatureHooks.timeline` and `now`), so the apps need no other call to draw Today's two parts.

`items` (`TimelineItem`) is one timeline of everything logged or recorded that day, newest first,
then the day's untimed things (`allDay`: the check-in, a cycle day, habits done, the energy budget,
whose `at` is the day's start). Each has a stable `id` (its kind and the source's id), a `kind`
(`TimelineKind`), `at` and, for things that last, `end`, a short `title` and a factual `detail`
("Changed · the one before was damp after 2 h 30 min", "Lunch · 610 kcal"), the `module` it belongs
to, the `page` (`AppPage`) a tap opens, and `ref`, what the apps can open or edit (the entry's id;
a date for a day's entry like the cycle or the check-in; none for a meal, which is several foods).

| `kind` | From |
|---|---|
| `SLEEP`, `NAP` | The night that ended that day (with its sleep score) and naps (`SLEEP`) |
| `WORKOUT` | Workouts, recorded or entered (`ACTIVITY`) |
| `WEIGHT`, `MEASUREMENT` | Weigh-ins (with body fat) and body measurements taken together (`BODY`) |
| `MEAL`, `DRINK` | Each meal as one item, at its first food logged that day; drinks; water while it's on (`NUTRITION`) |
| `DOSE_TAKEN`, `DOSE_SKIPPED` | Doses marked (`MEDICATIONS`; without it, hormone medications' under `HORMONES`) |
| `DOSE_CHANGE` | A hormone medication's start, or a change to its dose or schedule (`HORMONES`) |
| `BATHROOM`, `LEAK`, `PRODUCT_CHANGE`, `PELVIC_FLOOR` | The bladder and bowel diary: trips, bowel movements and catheters; leaks, bowel accidents and wet nights; changes, with how the one taken off was; pelvic floor sets (`CONTINENCE`) |
| `MOOD`, `CALM`, `JOURNAL` | Moods, calm practice, journal entries by kind only (`MIND`) |
| `CHECK_IN` | The day's check-in (`MIND` when that's on, otherwise no module), and Autism & ADHD check-ins (`NEURO`) |
| `ENERGY`, `OVERLOAD`, `FOCUS`, `ROUTINE` | The energy budget, times things got too much, focus, routines done (`NEURO`; the energy budget under `MIND` with Mind's energy budget and without Autism & ADHD) |
| `SYMPTOM` | Symptoms and migraines (`SYMPTOMS`) |
| `BLOOD_PRESSURE`, `GLUCOSE` | Each reading; a sensor's day as one item, with its time in range (`VITALS`) |
| `HABIT` | Habits done that day, by hand or by what's recorded (`HABITS`) |
| `APPOINTMENT` | The day's appointments, later ones too (`APPOINTMENTS`) |
| `VOICE_PRACTICE`, `TRANSITION_MOMENT` | Voice practice; moments of euphoria and dysphoria (`TRANSITION`) |
| `CYCLE_DAY` | The day logged, as its day in the cycle: bleeding, pain and symptoms (`CYCLE`) |
| `PHOTO` | Photos added, by album and pose (`PHOTOS`) |
| `HEALTH_ALERT` | Health alerts that began that day (no module, never shared) |
| `OTHER` | A kind this version doesn't know (a newer server's): show its title and detail |

`now` (`NowItem`) is only for today and only for the person's own account: the few live things,
most pressing first (`priority`, higher first; then the soonest `at`), each with one `action`
(`NowAction`: a `label`, an `action` key and the `ids` it needs). `progress` (0–1) is there where it
means something.

| `kind` | When | `action` (`ids`) |
|---|---|---|
| `HEALTH_ALERT` | An alert not put away (last 36 h); one that could need care now says when to call emergency services. Priority 100 urgent, 90 a doctor's look, 60 to keep an eye on | Open: `alert.open` (`alertId`) |
| `DOSE_OVERDUE`, `DOSE_DUE` | Today's scheduled doses not marked: past their time (85), or due within the hour (80) | Take: `dose.take` (`medicationId`, `scheduledFor`) |
| `APPOINTMENT_SOON` | The next three in the next seven days, not over yet: today 75, tomorrow 55, later 35 | Open: `appointment.open` (`appointmentId`) |
| `WEARING` | What's on now (bladder and bowel), with how long and the usual: 70 once a change reminder is due, else 45 | Change: `continence.change` (`supplyId`) |
| `VOICE_PRACTICE_DUE` | With a voice plan, no practice yet today, not a rest day, from its reminder time (40) | Start: `voice.start` (`lessonId`, the next lesson) |
| `FOOD_TODAY` | Food so far against the day's target (30) | Add: `food.add` (`meal`, the meal it is now, and `date`) |
| `ENERGY_TODAY` | The energy budget: what's left, or a nudge to set it (65 when running low, else 25 or 20) | Add: `energy.add` (`date`) |

Without numbers about food (`FieldLayout.foodNumbers` false: eating disorder recovery), meals have
no calories and `FOOD_TODAY` says which meals were logged, with no `progress`. With water turned
off, water isn't listed. Viewing a share (`X-SAMH-View`), `now` is empty and `items` keeps only the
shared features' items: never health alerts, nothing without a module, and no page the viewer
can't open; meals lose their calories when the owner's or the viewer's numbers about food are off.

## Food

| | |
|---|---|
| `GET /api/food/{date}` | `FoodDay`: entries, water, totals and the day's target |
| `POST /api/food/entries` | `FoodEntryInput` → `FoodEntry` (without a `food`: `name`, `nutrients` and an optional `serving` label, like "about 180 g") |
| `PATCH /api/food/entries/{id}` · `DELETE …` | `FoodEntryChange` → `FoodEntry` |
| `POST /api/food/copy` | `{from, to, meal?}`: copies a day's meal (or whole day) to another day |
| `GET /api/food/suggestions?date=&meal=` | `FoodSuggestions`: ideas from the last 120 days for `meal` (without one, the meal it is now in the person's time zone: breakfast until 10:30, lunch until 15:00, a snack until 17:00, dinner until 21:30, then a snack) |
| `GET /api/foods/search?q=` | `FoodSearch`: the person's foods (`mine`), then in `online` USDA FoodData Central's everyday foods (Foundation, SR Legacy, FNDDS; up to 8) and packaged foods (Branded; up to 15), then Open Food Facts' (English names only) where it's switched on and USDA found fewer than 5. Each food once: one with the barcode of a food before it (leading zeros aside) or the same name and brand is left out. The apps put the person's Mealie recipes after `mine`. `onlineError` says, in plain words, when nothing could be asked (no USDA key, turned off, paused) or nothing answered |
| `GET /api/foods/barcode/{code}` | `Food`: the person's own, else USDA's packaged food with exactly that `gtinUpc` (digits only, leading zeros aside; searched as scanned and as a 12-digit UPC-A, 13-digit EAN and 14-digit GTIN), else Open Food Facts' where it's switched on. 404 when nobody knows it (a miss is kept a day); 409 with a plain sentence when neither can be asked (no USDA key and Open Food Facts off) |
| `GET /api/foods/recent` | `[Food]`: the person's foods, most used first |
| `POST /api/foods` · `PUT /api/foods/{id}` · `DELETE /api/foods/{id}` | The person's own foods |
| `GET /api/food/targets` · `PUT` | `NutrientTargets` / `NutrientTargetsChange`: each nutrient's reference intake, upper limit and the person's own target |
| `POST /api/water` · `DELETE /api/water/{id}` | `WaterEntry` |
| `GET /api/recent?hours=24&device=` | `RecentReadings`: the last hours as recorded (1–72, default 24), from one device or all: the nights and naps in it with their stages, and heart rate, blood oxygen, HRV, stress, breathing, temperature and steps (hourly), each with its points and a summary. Never shared |
| `GET /api/mealie` · `PUT` · `DELETE` | `MealieStatus` / `MealieConnect`: the person's Mealie (its address and an API token, checked with Mealie before it's saved; the token is never returned) |
| `GET /api/mealie/recipes?q=` | `MealieRecipes`: recipes in Mealie (2+ characters, up to 10), each with one serving as a `Food` when it has nutrition there |
| `GET /api/mealie/recipes/{slug}` | `MealieRecipe` |
| `GET /api/mealie/plan?date=` | `MealiePlan`: the day's meal plan in Mealie, in meal order, each marked when it's already logged |

**Mealie** (mealie.io, the self-hosted recipe manager) is each person's own. Its recipes' nutrition
is free text per serving ("320 kcal", "12 g", "650 mg"; kilojoules become kilocalories, sodium and
cholesterol are milligrams unless they say grams); without calories, they come from the
macronutrients; with neither, there's nothing to log. A logged recipe joins the person's foods with
`source: MEALIE` and `ref: "mealie:<id>"`, and takes the recipe's numbers as they are each time
it's logged. Plan entries' meals: breakfast, lunch, dinner (and sides), and snacks (and desserts and
drinks). Nothing is written to Mealie. `/api/mealie` is never reachable through a share. Private
addresses follow the `webhooksPrivateNetworks` setting; any-address, cloud metadata and redirects
are refused. Mealie before version 2 (no households) has its group's plan.

Suggestions are single foods and whole meals the person has had (one day's meal of 2–5 things),
ranked by how often they have them at that meal and overall, how lately, how much of the day's
protein gap they'd close (and protein for their calories), less when they'd go well over the
calories left; at most five, with a whole meal among the first three when there is one. No history,
no suggestions. Each `FoodSuggestion`'s `items` are `FoodEntry`s ready to log, one
`POST /api/food/entries` each (`FoodEntry.toInput`: the person's food when it was one, else its
name and nutrients); `why` is plain words and never an amount ("You often have this at dinner",
"You had this on Tuesday"). For someone in eating disorder recovery (the `eating-disorder`
condition), `showNumbers` is false and `gap` absent: the ranking goes by habit alone, the apps show
no amounts, and Today has no food insights with amounts or targets (protein, calories; water
without its numbers).

**Vitamins and minerals.** `Nutrients.micros` maps each `Micronutrient` (the finer fats, minerals
and vitamins, each in its `unit`) to an amount; with `fiber`, `sugar`, `saturatedFat` and
`sodiumMg` (the `ExtraNutrient`s), a nutrient that's missing is unknown, never 0, and sums and
servings keep it that way (`Nutrients.plus`, `times`). Entries stored before micronutrients read
with none. USDA foods carry each `Micronutrient` FoodData Central gives, by nutrient number (per 100 g
in its own unit, G, MG or UG; vitamin A as RAE, vitamin D as D2 + D3 or from a label's IU, folate as
DFE; omega-3 has no one number and is left out), per serving where there is one (a packaged food's
label, a survey food's first real portion), else per 100 g. Open Food Facts foods carry every `Micronutrient.offKey` the product has (grams per
100 g, turned into each unit with `fromGrams`; per serving where the label gives a serving, using
the `_serving` value for what's only given that way). What a person types (`POST /api/foods`,
`PUT`, and `POST /api/food/entries` with `nutrients`) is checked: each micronutrient 0 or more and
at most 2,000 g, 100,000 mg or 1,000,000 µg, else 400; a database food's odd values are dropped.
`FoodDay.target` (and `DaySummary.nutritionTarget`, `Trends.nutritionTarget` for the last day)
carries `micros` (`MicroTarget`: `amount`, `upper` where an upper limit applies to food, `custom`
for the person's own) and `fiberG`, `sugarG`, `saturatedFatG`, `sodiumMg`, with `microBasis`
("Adult reference intake, female values"). The reference intakes are the US National Academies'
DRIs as the NIH Office of Dietary Supplements lists them (RDA or AI; the UL), by age (19–50, 51–70,
71 and over; 30 without a birth year), with the female, male or average values of the person's
chosen `EnergyProfile` (the calorie formula's choice, never inferred); sodium's 2,300 mg limit;
saturated fat under 10% and trans fat under 1% of the day's calories; cholesterol 300 mg; total
sugars 90 g (the EU's reference intake); and in pregnancy (the life stage, with the cycle on) at
least 600 µg folate, 27 mg iron and 220 µg iodine. Someone viewing a share that leaves out the
cycle gets the targets without pregnancy's. `GET /api/food/targets` lists every nutrient by key
(`ExtraNutrient` or `Micronutrient` name) with its `reference`, `upper` and `own`, plus `basis`
and `source`; `PUT` with `{own: {"IRON": 15}}` replaces the person's own (sealed in their
settings; each more than 0 and within the maxima above; a key left out goes back to the
reference). `TrendDay` has `fiber`, `sugar`, `saturatedFat`, `sodiumMg` and `micros` where a food
that day carried them. At most one gentle insight a day looks at the week before (`salad`, linking
to `FOOD`): fibre or a vitamin or mineral under its target on at least 4 of the 7 days, counting
only days where at least half the foods carried it, and only when 5 of the 7 do. It's never shown
in eating-disorder recovery or with the field `nutrition.micros` ("Vitamins & minerals", on by
default) hidden; the apps hide the Food section, a food's micronutrients, "More nutrients", the
Trends chart and the targets the same way. The CSV export's `food.csv` and `daily.csv` have a
column for each (`fiber_g`, `sugar_g`, `saturated_fat_g`, `sodium_mg`, `iron_mg`, `vitamin_d_ug`…),
empty where nothing carried it; the connector's `food_log` and `day` add the totals the foods
carried.

## Body, workouts, sleep, check-ins

| | |
|---|---|
| `GET /api/measurements?metric=WEIGHT&days=365` | `[Sample]` |
| `POST /api/measurements` · `DELETE /api/measurements/{id}` | `MeasurementInput` → `Sample` |
| `GET /api/workouts?days=30` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Workout` |
| `GET /api/sleep?days=30` | `[SleepSummary]` |
| `POST /api/sleep` · `DELETE /api/sleep/{id}` | `SleepSession` entered by hand |
| `GET /api/checkins/{date}` · `PUT /api/checkins/{date}` | `CheckIn` |

## Devices and data in

| | |
|---|---|
| `GET /api/devices` | `[Device]` |
| `PATCH /api/devices/{id}` | `{name}` → `Device` |
| `DELETE /api/devices/{id}` | Forgets a device and everything it recorded (and any choice of it) |
| `GET /api/devices/choices` · `PUT` | `DeviceChoices`: which device to use for a reading, and for sleep |
| `GET /api/devices/compare?a=&b=&days=14` | `DeviceComparison`: two devices side by side over 1–90 nights (`a` the one to compare against, `b` the one being checked; without them the server picks, a strap first, then the devices with the most readings), each night with both devices' own sleep and their `ComparedValue`s, and over them all a `ComparedMetric` for each `CompareKey` (`difference` is b − a on average, `apart` how far either way, `lowest`/`highest` the spread). Never shared |
| `POST /api/devices/upload-keys` | `UploadKeyRequest` → `UploadKey` (shown once) |
| `POST /api/ingest` | `IngestBatch` → `IngestResult` |
| `POST /api/import/fit` | multipart `file` (one or more `.fit`) → `FitImportResult` |

`POST /api/ingest` is idempotent: a sample is identified by device, metric and start time, a
sleep session and a workout by device and start time, so the app can resend a whole day
whenever it likes. Batches over 20,000 samples are refused; send them in parts. A device's `kind`
is a `DeviceKind`: `COLMI_RING`, `PEBBLE`, `GARMIN`, `HEALTH_CONNECT`, `GADGETBRIDGE`, `FIT_FILE`,
`PHONE`, `HEART_RATE_STRAP` (any standard Bluetooth heart rate strap, worn on the chest),
`BP_MONITOR`, `GLUCOSE_METER`, `MANUAL` or `OTHER`.

**Comparing two devices.** Readings are set side by side only over the time both measured: heart
rate five minutes at a time, the rest half an hour at a time (`ComparedValue.pairs` counts the
spells). Steps are compared over only the hours of the day both counted at least 20 in (a phone
counts only while it's carried; rings keep steps by the hour), a reading over several hours shared
out between them; their `pairs` are those hours, and `ComparedMetric.pairs` adds them up. A night
is both devices' sleep together, or 10 pm to 7 am without any; sleep is each device's own.
`BEDTIME` and `WAKE` are minutes after 6 pm on the evening before the night's `date` (the morning
it ended), in the person's time zone. Nights are newest first, and only those either device
recorded something in. With fewer than `DeviceComparison.ENOUGH` (3) nights or days for a thing,
the apps say it's early rather than how far apart the devices usually read.

A device sent with only an id for a name (Health Connect's own count of the phone's steps sends
one) is called "Phone" for that, or by its kind otherwise; a saved name that was only an id gives
way to a real one on the next sync, and a name the person chose is never changed.

### More than one device

When two devices measure the same thing (a ring and a watch, say), the server works out its own
number from both (`server/…/insights/Combine.kt`). With one device, its numbers are used as they
are. `DaySummary.combined` lists each reading more than one device measured that day (or that the
person chose one of several for): the result, each device's value and share (`SourceValue.weight`,
0–1), and `how`, in plain words. `SleepSummary.combined` does the same for a night.

- **Steps, distance, active calories, floors:** each quarter hour, the device that counted most.
  The same walk isn't counted twice, and a device taken off doesn't lose the steps the other
  counted. Hourly steps come from the same quarter hours. A reading longer than an hour (a day's
  total from Health Connect, say) is spread over its time but only tops up what the others
  counted then.
- **Heart rate:** merged every 5 minutes (each device's average there, then the average of the
  devices that had readings), so one that samples every minute doesn't outweigh one that samples
  every five. Where a chest strap measured, its reading alone is used: it reads the heart's own
  electrical beat. Average, lowest and highest come from the merged line; so does the day's chart.
- **Resting heart rate:** the devices' own resting figures where they give them (weighted as
  below); otherwise the lowest the merged night line stayed (the lowest three five-minute points
  in a row).
- **HRV, blood oxygen, breathing rate, skin temperature, a device's temperature deviation and
  stress:** each device's own night value (for stress, its day's), weighted by how much of the
  night (or day) it measured and how well it suits the reading:

  | | Ring (finger) | Watch (wrist) | Strap (chest) | Phone | Other |
  |---|---|---|---|---|---|
  | Resting heart rate | 1.0 | 0.8 | 2.0 | 0.2 | 0.6 |
  | HRV | 1.0 | 0.6 | 2.0 | 0.1 | 0.5 |
  | Blood oxygen | 1.0 | 0.5 | 0.2 | 0.1 | 0.5 |
  | Breathing rate | 0.9 | 0.7 | 1.0 | 0.2 | 0.6 |
  | Skin temperature, temperature deviation | 1.0 | 0.5 | 0.3 | 0.1 | 0.5 |
  | Stress (the day's) | 0.6 | 1.0 | 1.0 | 0.2 | 0.7 |
  | Time asleep | 1.0 | 0.9 | 0.3 | 0.4 | 0.7 |

  A Colmi ring is a finger; a Pebble or Garmin a wrist; a heart rate strap (`HEART_RATE_STRAP`) the
  chest; Gadgetbridge, Health Connect and other devices by their name ("strap", "hrm", "H10" before
  "ring", then "watch", "band"…). How much it measured: up to 90 minutes from
  each reading, against the night's length (one reading is the device's summary, and counts in
  full); under half is noted "only part of the night".

  HRV and skin temperature read differently on a finger and a wrist, so each device is compared
  with its own usual first (the median of its last 14 nights, once it has 3): the combined
  difference (for HRV, as a share) is shown on the scale of the device that counts most, its
  usual plus the difference. Until a device's usual is known, it isn't used ("still learning its
  usual"); until any is, the best-suited device's own value is. The skin temperature difference
  is the day's temperature deviation, unless a device gives one of its own.

  A device far from its own usual while another isn't counts for a quarter of its weight ("far from
  its usual": HRV 35%, blood oxygen and breathing 3, temperature 1 °C, stress 25).
- **Sleep:** nights from different devices that overlap by more than half are one night. Bedtime
  and waking are averaged where they're within 45 minutes of each other, otherwise the staged
  device's; the stages come from the device that tells them apart (light sleep takes up any
  difference, so the stages add up); time asleep is a weighted average (a night with no stages
  counts half). Nights entered by hand stay as they are; the main night and naps are picked as
  before.

`DeviceChoices` picks one device for a reading (`MetricChoice`: `metric` and `deviceId`) and one
for sleep (`sleepDeviceId`); a reading with no choice is combined. A chosen device's values are
used alone (it has weight 1, the others 0, "not chosen"); on a day it has none, the readings are
combined and `how` says so ("You chose the watch, which had none that day. …"). Choices can be made
for steps, distance, active calories, floors, heart rate, resting heart rate, HRV, blood oxygen,
breathing rate, skin temperature, temperature deviation and stress; others, and devices that
aren't the person's, are refused with 400. Changing the choices (or renaming or forgetting a
device) works every day out again. A share sees the combined numbers for what it's shown, never
the choices.

## Health alerts

| | |
|---|---|
| `GET /api/alerts?days=30` | `HealthAlerts`: the last 1–400 days' `HealthAlert`s, newest first (after a fresh look at the readings), the person's `AlertSettings`, what it's `watching` (`AlertWatch`: what, how, and whether it can), and `limits`, what alerts can and can't do. Never shared |
| `PUT /api/alerts/settings` | `AlertSettings` → `AlertSettings`, kept within its limits (high heart rate 100–180 bpm, low 30–60 bpm, low blood oxygen 85–94%) |
| `POST /api/alerts/seen` · `POST /api/alerts/{id}/seen` | Marks every alert, or one, seen |
| `POST /api/alerts/{id}/dismiss` | `AlertDismiss` (`{feedback}`, an `AlertFeedback` or none) → `HealthAlert`: put away, with why. It stays in the list |

The server watches what the devices send for a heart rate that stays high at rest or low while
awake, an irregular rhythm from a chest strap (`IRREGULAR_RHYTHM`, the share of a five-minute
window's stretches that looked irregular, worked out on the phone), blood oxygen that stays low or
dips at night, the signs of getting ill against the person's own usual, and a resting heart rate
that stays up (`AlertKind`). Each alert says how soon to act (`AlertLevel`: `URGENT` could need
care now, and its `advice` says when to call emergency services; `DOCTOR` is worth a doctor's look;
`WATCH` worth keeping an eye on), what happened and when (`start`, `end`), the readings behind it
(`evidence`) and the `devices` they came from. A spell is one alert, brought up to date as it goes
on, and never less serious than it was. Alerts are sealed, never shared and never in the digest.
Today's `/api/day` puts the active ones (not put away, from the last day and a half) first in
`insights`, with `tone` `"alert"` (urgent) or `"warn"` and `link` `ALERTS`. A new alert, or one that
becomes more serious, goes out once by notification (with `AlertSettings.notify`; it opens
`/alerts`) and to webhooks subscribed to `HEALTH_ALERT` (its level only, unless the hook includes
details). Appointments suggest asking about urgent and doctor's-look alerts not explained away.

## Health modules

Each person turns modules on and off in `Profile.modules` (`ACTIVITY`, `SLEEP`, `NUTRITION`,
`BODY` to start; `MIND`, `CYCLE`, `CONTINENCE`, `MEDICATIONS`, `VITALS`, `HABITS`, `SYMPTOMS`,
`HORMONES`, `APPOINTMENTS`, `NEURO`, `TRANSITION` and `PHOTOS` opt-in). A module's endpoints work
whether or not it's on; the apps show only the ones that are. Entries in the sensitive modules are
encrypted on the server. `GET /api/day/{date}` includes `modules` (a `ModulesToday`) for the
modules that are on, and `TrendDay` carries their daily numbers.

Lists take `?days=N` (the last N days, today included). Every entry's `date` is set by the server
from `at`, in the person's time zone.

### Mind

| | |
|---|---|
| `GET /api/mind/moods?days=30` · `POST` · `PUT /{id}` · `DELETE /{id}` | `MoodEntry` |
| `GET /api/mind/journal?days=90` · `POST` · `PUT /{id}` · `DELETE /{id}` | `JournalEntry` |
| `GET /api/mind/screeners` | `[ScreenerDefinition]`: PHQ-9, GAD-7 and the EPDS, their questions and bands |
| `GET /api/mind/screeners/results?days=365` · `POST` · `DELETE /{id}` | `ScreenerResult` (post `screener`, `at`, `answers`; the server scores it) |
| `GET /api/mind/sessions?days=30` · `POST` · `DELETE /{id}` | `MindfulnessSession` |
| `GET /api/mind/summary?days=30` | `MindSummary`: daily averages, feelings, patterns, latest screeners |
| `GET /api/mind/resources` | `[CrisisResource]`, as the server's admin set them |

### Cycle

| | |
|---|---|
| `GET /api/cycle?months=12` | `CycleOverview`: logged days, cycles, averages, the prediction, today's phase |
| `PUT /api/cycle/days/{date}` · `DELETE /api/cycle/days/{date}` | `CycleDay` (a PUT replaces the day) |

### Bladder and bowel

| | |
|---|---|
| `GET /api/continence/entries?days=7` · `POST` · `PUT /{id}` · `DELETE /{id}` | `ContinenceEntry` |
| `GET /api/continence/summary?days=14` | `ContinenceSummary`: per-day counts, averages, triggers, patterns |
| `GET /api/continence/wearing` | `ContinenceWearing`: what's on now, since when, the usual wear time, when a change reminder is due, and its `wear` (how full, the accidents since it went on) |
| `GET /api/continence/diary?date=` | `ContinenceDiary`: a day grouped, newest first: each product worn with the accidents while it was on, and every other entry on its own |
| `GET /api/continence/products?days=90` | `[ProductStats]`: how each product goes (wear time, how full, leaks through, nights) |

### Medications

| | |
|---|---|
| `GET /api/meds` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Medication` (`?all=true` includes ones turned off) |
| `GET /api/meds/day/{date}` | `MedsDay`: each scheduled dose with its log and the next injection or patch site |
| `GET /api/meds/history?from=&to=` | `MedsHistory`: each day from `from` to `to` (up to a year; by default the last 35 days): its scheduled doses taken, skipped, missed (a day that's over, never marked) or still open (today's), and doses taken off the schedule (`extra`) |
| `GET /api/meds/doses?days=30` · `POST` · `DELETE /{id}` | `DoseLog` (marking a scheduled dose: send its `scheduledFor`) |
| `GET /api/labs?days=3650` · `POST` · `DELETE /{id}` | `LabResult` |

Reminders: `Profile.reminders` (`ReminderPrefs`) sets how every device reminds: again after N
minutes if a dose isn't marked, when it counts as missed, refills, quiet hours, and whether names
show on a locked screen.

### Blood pressure and glucose

| | |
|---|---|
| `GET /api/vitals/bp?days=30` · `POST` · `DELETE /{id}` | `BloodPressure` |
| `GET /api/vitals/glucose?days=14` · `POST` · `DELETE /{id}` | `GlucoseReading` (mmol/L; the apps convert) |
| `GET /api/vitals/summary?days=30` | `VitalsSummary`: averages, category, time in range, GMI, patterns |

Targets are `Profile.vitalsTargets`. Monitors send readings in `IngestBatch.bloodPressure` and
`IngestBatch.glucose` (`IngestResult.readings` counts them; a resend replaces them); samples of the
`BLOOD_PRESSURE_*` and `BLOOD_GLUCOSE` metrics (from Health Connect) count too. Only readings
entered by hand can be deleted.

### Drinks (with food)

| | |
|---|---|
| `GET /api/drinks/{date}` | `DrinksDay` |
| `POST /api/drinks` · `PUT /{id}` · `DELETE /{id}` | `Drink` |
| `GET /api/drinks/summary?days=30` | `DrinksSummary`: averages and how caffeine and alcohol went with sleep |

### Symptoms and migraines

| | |
|---|---|
| `GET /api/symptoms/entries?days=30` · `POST` · `PUT /{id}` · `DELETE /{id}` | `SymptomEntry` |
| `GET /api/symptoms/migraines?days=90` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Migraine` (an open one has no `end`) |
| `GET /api/symptoms/summary?days=90` | `SymptomsSummary`: counts, triggers, and what went with symptoms (sleep, cycle, caffeine, alcohol, stress, air pressure) |

Air pressure comes from the US National Weather Service (`api.weather.gov`) for `Profile.place`,
when the admin allows it (`weather`). It keeps only about a week, so the server keeps each day's mean
(fetched once a day, and when the summary is worked out): history builds from when the place is set.
`SymptomsSummary.weatherNote` says so, or that there's no data outside the US.
`GET /api/places/zip/03820` gives a US ZIP code's `Place` (404 if unknown), from the Census
Bureau's ZCTA centres kept with the server: nothing is sent anywhere.

### Stress and resilience

`DaySummary.stressDay` and `DaySummary.resilience`; `TrendDay.stressedMinutes` and
`restoredMinutes`. Worked out from daytime heart rate and HRV against the person's own baseline,
or from the device's stress readings (Garmin, Colmi).

### Habits

| | |
|---|---|
| `GET /api/habits` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Habit` (`?all=true` includes ones turned off) |
| `GET /api/habits/today?date=` | `[HabitStatus]` |
| `PUT /api/habits/{id}/ticks` | `HabitTick` (sets the day's count; 0 clears it) |
| `GET /api/habits/{id}/history?days=90` | `[HabitTick]` |

### Hormone therapy

| | |
|---|---|
| `GET /api/hormones` | `HormoneOverview`: hormone medications with dose history, labs against targets, when labs are due |
| `PUT /api/hormones/settings` | `HormoneSettings` |
| `POST /api/hormones/changes` · `DELETE /api/hormones/changes/{id}` | `DoseChange` (also recorded when a hormone medication's dose or schedule is edited) |

Medications are marked with `Medication.hormone`.

### Life stages (with the cycle)

| | |
|---|---|
| `GET /api/cycle/stage` · `PUT` | `LifeStageInfo` |
| `GET /api/cycle/stage/overview?days=30` | `LifeStageOverview` |
| `GET /api/cycle/kicks?days=30` · `POST` · `PUT /{id}` · `DELETE /{id}` | `KickSession` |
| `GET /api/cycle/contractions?days=2` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Contraction` |
| `GET /api/cycle/feeds?days=7` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Feed` |
| `GET /api/cycle/hot-flashes?days=30` · `POST` · `DELETE /{id}` | `HotFlash` |

A pregnancy needs `dueDate` or `lastPeriod` (the due date is then 280 days on). While pregnant,
after a birth or after menopause, `GET /api/cycle` has no prediction (its `caveat` says why); in
perimenopause the range is wider. While pregnant or after a birth, Mind offers the EPDS check-in:
its questions each have their own answers (`ScreenerDefinition.itemOptions`), the answers sent are
the chosen option's index (0–3), and `itemScores` turns them into points (some count in reverse).
`flagItem` is the self-harm question for PHQ-9 and EPDS.

### Appointments

| | |
|---|---|
| `GET /api/appointments?days=365&upcoming=true` · `POST` · `GET /{id}` · `PUT /{id}` · `DELETE /{id}` | `Appointment` (without `upcoming`: the last `days` and everything ahead) |
| `GET /api/appointments/{id}/suggestions` | `[SuggestedQuestion]` from what was logged since the last appointment (or 30 days), by every feature that's on; ones already on the list are left out |

## Conditions and goals

Each person picks the conditions and goals they live with or want to keep track of; each turns on
the features and the fields within them that fit (`Fields` in `shared/…/Conditions.kt`), so the
apps show only what's useful. Kept encrypted.

| | |
|---|---|
| `GET /api/conditions/catalog` | `[ConditionDefinition]`: conditions (bladder and bowel, periods and hormones, neurodivergence, mental health, chronic illness…), goals (weight, fitness, sleep…) and life (transition, pregnancy, menopause) |
| `GET /api/conditions` · `PUT` | `MyConditions` (a PUT turns on the features they need; it never turns features off) |
| `GET /api/fields` · `PUT` | `FieldLayout` / `FieldChoices`: which fields show, and why |

Food's water is a field too (`nutrition.water`, on by default): hidden, the apps and the server
leave out water everywhere (the quick button, the goal, insights, the digest, the watch, habits,
exports to other apps); drinks still count caffeine and alcohol. What's logged stays stored, and
`DaySummary.waterMl` is still filled in (the apps decide what to show). On the server that means:
no water insights or tips; no water line in the digest (kept ones too) or the report (the CSV
export still has everything logged, water included);
`WatchToday.water` is false, with no water figures, and a watch `WATER` tap is refused; a new
`WATER_GOAL` habit is refused (a kept one still counts, but never reminds); Home Assistant's state
leaves out `water_ml` and `water_goal`; and the AI connector and assistant leave water out of
what they read, don't offer `log_water`, and refuse `log_drink` with `WATER`.

### Arranging screens

| | |
|---|---|
| `GET /api/layout` | `LayoutPrefs`: each arranged screen's `ScreenLayout` (order, hidden), by key |
| `PUT /api/layout` | Replaces them all |
| `PUT /api/layout/{key}` · `DELETE /api/layout/{key}` | One screen's `ScreenLayout` · puts it back as it was (so does an empty one) |

Keys (`Layouts` in `shared/…/Layouts.kt`): `today` (`TodaySection` names), `today.quick`
(`QuickAction` names; hidden ones go under "More"), `nav` (`Module` and `NavPage` names, for the
web's sidebar and the phone's Health tab), shared by both apps; and `web.<screen>` or
`android.<screen>` for the rest, with ids each app keeps stable. Sections a saved order doesn't
name go where they'd be by default (`Layouts.arrange`). Always the person's own: refused with
`X-SAMH-View`. Up to 100 screens, 80 sections each, ids of letters, digits, `.`, `-` and `_`.

### Bladder and bowel, in detail

| | |
|---|---|
| `GET /api/continence/settings` · `PUT` | `ContinenceSettings` (quick buttons, own triggers, activities and places, custom fields, the usual product, night hours, bladder training, the change reminder) |
| `GET /api/continence/progress?weeks=12` | `ContinenceProgress`: getting better or worse, week by week |
| `GET /api/continence/check-ins?days=180` · `POST` · `DELETE /{id}` | `ContinenceCheckIn` (weekly bother) |

`ContinenceEntry` carries the detail fields; `padWeightG` with the supply's dry weight sets `lossG`.
`ContinenceDay` counts the day: `bowelLeaks`, `wetNights`, `products`, `leakedThrough`, `catheters`,
`catheterMl` and `lossG`. Bladder training reminds through `FeatureHooks.reminders` (push, the phone,
the `bladder.training` webhook), with discreet lock-screen words.

**A change, and what's being worn.** A `PAD_CHANGE` is one step: `supplyId` is what was **taken
off**, `putOnSupplyId` what was **put on**, and `nothingOn` says it came off with nothing on for now
(both at once is a 400). A change with only `putOnSupplyId` is "put on". `fullness`, `leakedThrough`,
`changedClothes`, `changedBedding`, `skin` and `padWeightG` are about the one taken off.

- **Stock:** a change takes one (or `count`) off the **put-on** product's stock; the one taken off
  was counted when it went on, and nothing is counted with `nothingOn`. A change from before
  `putOnSupplyId` existed counts its `supplyId`, as it always did, so older diaries stay right. Any
  other entry with a product and a `count` (a leak, changed afterwards) uses that many. Edits and
  deletes give back what was counted and count again.
- **`wornMinutes`** (set by the server, on every entry returned) is how long the product taken off was
  worn: from the change before it that put that product on, within a day. An older change put on a
  fresh one of what it took off, so older diaries pair up too.
- **`GET /api/continence/wearing`** comes from the latest change in the last day that put something
  on or took everything off (`nothingOn`); every field is null when there's none. `typicalMinutes`
  is the middle of that product's paired wears over 60 days (three or more); `changeDueAt` is
  `changeReminderHours` after it went on, moved to the end of the night (`nightFrom`/`nightTo`,
  11 pm to 6 am by default) unless `changeReminderAtNight`. Today's `ModulesToday.continence.wearing`
  carries the same, on today only (a card even before anything else is logged).
- **Wears** (`ContinenceWear`) run from the change that put a product on to the one that took it off.
  When the next change takes off something else, or puts something on without taking anything off,
  the wear ends there with no `off`; past a day with no change, its end isn't known. An accident
  (`LEAK`, `BOWEL_LEAK`, `BEDWETTING`) belongs to the wear it happened in: never to a product other
  than the one it names (`supplyId`), at a change's moment to what came off, and up to 10 minutes
  before the product it names went on to that product. A wear's `fullness` is the change's, or, while
  it's on, the latest accident's; `leakedThrough` is the change's or any accident's.
- **`GET /api/continence/diary`** lists each wear that was on at any time that day (what's on now has
  `current`) and every other entry of the day on its own; a listed wear's changes and accidents aren't
  repeated as entries, and an overnight wear shows on both days with all its accidents.
- **`GET /api/continence/products`** groups the changes that took each product off: `changes`,
  `avgWornMinutes`, `typicalFullness` (the most common), `leakedThrough` and `leakedThroughPct`
  (leaked through, or `LEAKED`), and `nightShare` (wears whose middle was in the night), most-changed
  first.
- **The change reminder** goes out once per wear at `changeDueAt` as `continence.change`. Every
  screen sees only "Time for a check" and a time ("About 6 hours since your last change."), never the
  product, whatever `ReminderPrefs.showNames` says; only a webhook with `includeDetails` gets
  `product` in its data.
- The report's Bladder and bowel section has a line per product and a Products table; the export
  adds `put_on`, `nothing_on` and `worn_minutes` to `bladder-bowel-details.csv`, and
  `bladder-bowel-products.csv`. The connector's `continence` tool says what's worn now and how each
  product goes, and `day` what's on (both only for links that may read Bladder and bowel). Shares
  that include `CONTINENCE` can read all of it and change nothing.

### Supplies

| | |
|---|---|
| `GET /api/supplies` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Supply` (`?all=true` includes ones put away) |
| `GET /api/supplies/status` | `[SupplyStatus]`: on hand, used a day, days left, low |
| `POST /api/supplies/{id}/adjust` · `GET /api/supplies/{id}/log?days=90` | `SupplyAdjustment` (restocked, counted, gave away; used ones come from the diary) |

Low supplies show on Today (`ModulesToday.lowSupplies`) and remind once a day (like refills; the
`supplies.low` webhook).

### Cycle settings

| | |
|---|---|
| `GET /api/cycle/settings` · `PUT` | `CycleSettings` (own symptoms, moods and pain areas, custom fields, lengths, reminders, neutral wording) |

Reminders before a period, when it's late, and a daily nudge go out as `cycle.reminder`. With
neutral wording on (or the fertility field hidden), Today and the AI connector leave out fertility
words.

`CycleDay` carries the detail fields; `products` with a `supplyId` come off that supply's count.

### Autism & ADHD

| | |
|---|---|
| `GET /api/neuro/profile` · `PUT` | `NeuroProfile` (about me) |
| `GET /api/neuro/events?days=90` · `POST` · `PUT /{id}` · `DELETE /{id}` | `OverloadEvent` |
| `GET /api/neuro/energy?days=30` · `PUT /api/neuro/energy/{date}` | `EnergyDay`: the social battery (`{date, socialBattery}`), and Mind's own energy budget for people without Autism & ADHD |
| `GET /api/neuro/checks?days=30` · `POST` · `DELETE /{id}` | `EnergyCheck`: energy and focus check-ins (each 1–5, with optional tags and a note), newest first |
| `GET /api/neuro/energy-focus?days=42` | `EnergyFocus`: today's check-ins and ADHD medication doses, and the person's own patterns (by time of day, since a dose, and with sleep, medication, movement, meals and stress) once there are enough check-ins, with how it works and its sources. Through a share without Medications, nothing about the medication |
| `PUT /api/neuro/energy-focus/settings` | `EnergyFocusSettings`: the ADHD medication (unset: found from the medications' names) and reminder times (`"HH:mm"`, up to 6, none by default) |
| `GET /api/neuro/check-ins?days=30` · `POST` · `DELETE /{id}` | `NeuroCheckIn` |
| `GET /api/neuro/focus?days=30` · `POST` · `PUT /{id}` · `DELETE /{id}` | `FocusSession` |
| `GET /api/neuro/routines` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Routine` |
| `GET /api/neuro/routines/runs?days=14` · `PUT /api/neuro/routines/{id}/runs/{date}` | `RoutineRun` |
| `GET /api/neuro/cards` · `PUT` | `[CommunicationCard]` (the server offers kind defaults until the person changes them) |
| `GET /api/neuro/summary?days=30` | `NeuroSummary`: patterns, burnout signs, time estimates |

### Transition

| | |
|---|---|
| `GET /api/transition` | `TransitionOverview` |
| `GET /api/transition/profile` · `PUT` | `TransitionProfile` |
| `GET /api/transition/templates` | `[MilestoneTemplate]` |
| `GET /api/transition/milestones` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Milestone` |
| `GET /api/transition/changes` · `POST` · `PUT /{id}` · `DELETE /{id}` | `BodyChange` |
| `GET /api/transition/voice?days=180` · `POST` · `DELETE /{id}` | `VoiceSession` |
| `GET /api/transition/voice/program` | `VoiceProgram`: the voice programme (`VoiceCatalog.PROGRAM`) |
| `GET /api/transition/voice/plan` · `PUT` | `VoicePlan` |
| `GET /api/transition/voice/progress` | `VoiceProgress` |
| `GET /api/transition/hair-removal?days=365` · `POST` · `DELETE /{id}` | `HairRemovalSession` |
| `GET /api/transition/moments?days=90` · `POST` · `DELETE /{id}` | `GenderMoment` |

**The voice trainer** is a free, guided programme with live feedback on pitch, resonance, weight,
intonation and loudness, for whichever direction the person chooses (`VoiceGoal`: `LIGHTER`,
`DEEPER` or `EXPLORING`). Sound is only ever listened to on the person's device; the server gets
the numbers. The programme lives in `shared/…/VoiceCatalog.kt` (the phone app uses it offline):
eight units in order (looking after your voice and a starting recording, warm-ups, pitch,
resonance, vocal weight, intonation, everyday speech, keeping it going), each lesson 5–15 minutes.
Most exercises suit every goal and take their direction from the person's target range and
resonance target (`MATCH` notes are semitones from the middle of the range); where direction
matters there are variants by goal (`VoiceExercise.goals`, `VoiceLesson.goals`, empty meaning all),
and `EXPLORING` gets both. `VoiceCatalog.lessonsFor(goal)` and `exercisesFor(lesson, goal)` give a
person's path. `health` puts vocal health first (warm up, stop if it hurts, rest days, water, and
when to see a speech-language pathologist) and `daily` is a short daily routine.

The plan is sealed in the person's settings (`voice.plan`). A target range sits within 60–500 Hz
with its bottom below its top, resonance is 0–100, `dailyMinutes` 1–60, `reminder` is `HH:mm`
and `restDays` are ISO days (1 Monday … 7 Sunday); anything else is a 400. Sessions from the
trainer carry `lessonId`, `results` (each `ExerciseResult.exerciseId` must be in the programme,
and its numbers in range), `resonance`, `weight` and `strain`. Progress gives the lessons done,
the next lesson for the goal (in unit order), the streak (rest days don't break it), minutes this
week, up to twelve `VoiceWeek`s, kind highlights against the week before and the starting point
(only ever "nearer your target", never a direction), and a `restNote` after strain. A reminder
goes out at the plan's time as `voice.practice` (not on rest days, nor once the day's practice is
done; a locked screen shows only "Time for a little practice"). Practice has a line in the digest,
the report's Transition section (with the pitch chart banded by the target range, and resonance),
the export (`voice.csv`) and the connector's `transition` tool. Shares that include `TRANSITION`
can read the programme, plan and progress, and change nothing.

Body measurements are `Metric.WAIST`, `HIPS`, `CHEST`, `BUST`, `UNDERBUST`, `SHOULDERS`, `NECK`,
`UPPER_ARM`, `THIGH`, `CALF` through `/api/measurements` (in cm), like weight.

### Progress photos

| | |
|---|---|
| `GET /api/photos?album=&days=` | `[Photo]` |
| `GET /api/photos/albums` | `[PhotoAlbum]` |
| `POST /api/photos` | multipart: `file` (JPEG, PNG or WebP, up to 20 MB) and `photo` (`Photo` as JSON) |
| `GET /api/photos/{id}/image` · `GET /api/photos/{id}/thumb` | The picture (`Cache-Control: private, no-store`) |
| `PUT /api/photos/{id}` · `DELETE /api/photos/{id}` | `Photo` |

Photos are re-encoded on upload (dropping location and camera details), encrypted in the data
folder, never shown to AI connectors or the assistant, and seen by a share only if it includes
`PHOTOS`.

### Export to other apps

`GET /api/export/fit?from=&to=` is a zip of Garmin FIT files (workouts, and weight with body fat)
for Garmin Connect, Strava and the like. The phone app writes to **Health Connect** (each kind of
data chosen by the person), which Samsung Health, Google Fit and other apps read; it never reads
back what it wrote.

## The AI assistant

Off unless the admin turns it on (with an Anthropic API key), and then only for people who opt
in. What it looks at is sent to Anthropic, under Anthropic's commercial terms and privacy policy.

| | |
|---|---|
| `GET /api/admin/ai` · `PUT` | `AiSettings` (turning it on needs the admin's acknowledgement) |
| `PUT /api/admin/ai/key` · `DELETE` · `POST /api/admin/ai/test` | `AiKey`, kept sealed and never returned |
| `GET /api/ai/status` | `AiStatus` |
| `PUT /api/ai/consent` · `DELETE` | `AiConsent` |
| `POST /api/ai/chat` | `AiChatRequest` → `AiChatReply` (with what it looked at) |
| `POST /api/ai/breakdown` | `AiBreakdownRequest` → `AiBreakdownReply` (a task's text only) |
| `POST /api/ai/food-photo` | multipart: `photo` (JPEG, PNG or WebP, up to 20 MB), optional `meal` (`Meal`) and `note` → `FoodPhotoEstimate` |
| `POST /api/ai/portion` | multipart: `photo`, optional `package` (each JPEG, PNG or WebP, up to 20 MB), `food` (`PortionFood` JSON) and optional `note` → `PortionEstimate` |
| `GET /api/ai/audit?days=30` | `[AiAuditEntry]` |

**Food photo estimates.** With Food among the features the person allowed, `POST /api/ai/food-photo`
sends one photo of a meal to Anthropic and returns what's in it: each food and drink with its
portion (grams, or ml), estimated kcal, protein, carbs, fat, fibre, sugar, saturated fat and
sodium (never vitamins or minerals, which a photo can't show), a confidence (`LOW`,
`MEDIUM`, `HIGH`) and a line on what it assumed, plus `match`, the person's own food with a
similar name, if they have one. The apps shrink the photo to 1024 px and re-encode it (dropping
location and camera details); the server does the same again, sends it in that one request, and
never keeps it (not on disk, in the database or in the log). The reply is a structured output, so
it's exactly that shape. `refused: true` (with `notes`) means it couldn't make the photo out. It
counts towards the day's limit, and the audit log records `food_photo`, Food, the model and the
tokens. With the condition `eating-disorder`, `hideNumbers` is true: the apps show foods and
portions without calorie or macro numbers (the numbers are still there, for logging). Nothing is
logged until the person checks the estimate and logs it with `POST /api/food/entries`. Someone
viewing a share can't send one.

**How much did I have?** With the same gates (the assistant on, the person's consent with Food,
and the assistant not paused in Where your data goes), `POST /api/ai/portion` sends a photo of
what's being eaten (and the package's, if given) with the food's words only: its name, brand,
serving label and grams, the package size (`Food.packageGrams`, from Open Food Facts) and a
Mealie recipe's yield (`Food.recipeServings`). It returns `servings` (above 0, at most 50, to two
places) and one short `note`; `refused: true` (with `servings` 0) means it couldn't tell. The
photos go through the same pipeline as food photos and are never kept. It counts towards the
day's limit and the audit log records `portion`, Food, the model and the tokens. The apps prefill
the servings field with it; nothing is logged until the person adds the food.

## Medical records

A hospital, clinic or practice's own record of the person, read from its patient portal through
the standard patient API (SMART on FHIR, R4) of **Epic** (MyChart) and **athenahealth**
(athenaPatient). Read-only: nothing is ever written back. The server's admin registers an app with
each system once (Admin › Medical records: client ID, optional client secret for lasting access,
and the redirect URI below); people then connect their own portals. Access tokens are sealed on the
server and never sent to the apps. Never shared (no `X-SAMH-View`), not offered to the assistant or
connector links, and gated by `DataPath.HEALTH_RECORDS`: while it's paused, `connect`, `sync` and
`organizations` answer 409 with "Medical records is paused in Settings › Where your data goes", and
nothing is fetched.

| | |
|---|---|
| `GET /api/records` | `HealthRecords`: the systems set up on this server, whether it's paused, and each `RecordConnection` with counts by `RecordKind` |
| `GET /api/records/organizations?q=` | `RecordOrganization[]`: up to 25 Epic organizations whose names have every word of `q` (each the start of a word in the name, any case) from Epic's public endpoint list (`https://open.epic.com/Endpoints/R4`, kept a week), then athenahealth's single entry ("A practice on athenahealth", `https://api.platform.athenahealth.com/fhir/r4`), for the systems set up. An empty `q` gives athenahealth's entry alone |
| `POST /api/records/connect` | `RecordConnect` → `RecordAuthorize`: the portal's sign-in address. `fhirBaseUrl` must be exactly an address on Epic's list (a slash at the end doesn't matter) or athenahealth's, over https, for that `provider`; anything else is 400 and never asked. SMART discovery at `{fhirBaseUrl}/.well-known/smart-configuration` (else the CapabilityStatement's oauth-uris at `{fhirBaseUrl}/metadata`); PKCE S256; `state` (32 random bytes) bound to the person, single use, ten minutes; `aud` = `fhirBaseUrl`. Scopes: Epic `launch/patient openid fhirUser offline_access patient/*.read`; athenahealth `launch/patient openid fhirUser offline_access patient/Patient.r` and `patient/{type}.rs` for each type read |
| `GET /api/records/callback?code=&state=` | Where the portal sends the browser back (the redirect URI to register: `{public URL}/api/records/callback`). No session needed: the `state` says whose it is (when a session cookie does come, it must be the same person's). Nothing is connected yet: the portal's code is kept, sealed under a new single-use token, for five minutes, and the browser goes on to `/records?confirm={token}` on the web, or `samhealth://records?confirm={token}` for the phone app. Otherwise `?error=` with a code the apps turn into its sentence (`expired`, `someone-else`, `denied`, `refused`; anything else is said as a plain failure), so a link made up elsewhere can't put words of its own on the page |
| `POST /api/records/confirm` | `{"token":"…"}` → `RecordConnection`. Only the signed-in person who started the sign-in can confirm it (anyone else: 403, and the token is spent), so nobody else on the server can have someone's record land with them. Exchanges the code (the client secret as HTTP Basic when one is set, else `client_id` with PKCE alone), saves the connection (the same portal and patient again reuses its connection) and brings the record in (waiting a few seconds, then carrying on in the background) |
| `POST /api/records/{id}/sync` | Brings that record up to date now (refreshing access a minute before it runs out, keeping a new refresh token if the portal rotates it) → `RecordConnection`, whose `problem` says what went wrong ("Sign in again to bring it up to date" when the portal no longer accepts its access). 409 when it's paused or the system is no longer set up |
| `DELETE /api/records/{id}?keep=false` | Disconnects: forgets its access (asking the portal to end it, where it offers revoking), and with `keep=false` (the default) removes the connection and everything it brought in, labs too. `keep=true` keeps what's here: the connection stays listed without access (`problem` says it's disconnected, `keepsUpdating` false) until it's removed or connected again, and its labs stay in Labs with their `source` |
| `GET /api/records/items?kind=&connection=` | `RecordItem[]`, newest first (400 for an unknown `kind`) |

What's read, per connection: `Patient` (only to check it's the person's own; on athenahealth, also
the practice's name), lab `Observation`s (`category=laboratory`; those with a plain number are also
saved as `LabResult`s with `source` = the organization and `ref` = `record:{connection}:{id}`, so
they chart in Labs, are never added twice and change when the record does; `ref` is never taken from
`POST /api/labs`), vital-sign `Observation`s, `MedicationRequest`s (with `medication` ready for "Add
to my medications"), problem-list `Condition`s, `AllergyIntolerance`s, `Immunization`s and
`Procedure`s, each searched with `patient=` and following `next` pages on the same server. Anything
about another patient, or entered in error, is left out. A kind the portal doesn't offer or allow
(400, 403, 404) is skipped quietly; after a kind is read in full, what's no longer in the record is
removed (its labs too). With lasting access (a refresh token), each connection is brought up to date
once a day; without, each sign-in brings it up to date once.

## Sharing

| | |
|---|---|
| `GET /api/shares` | `Shares`: who sees this account, and whose this account sees |
| `POST /api/shares` · `PUT /{id}` · `DELETE /{id}` | `ShareRequest` / `ShareChange` (the owner, or the viewer leaving) |

A viewer sends `X-SAMH-View: <ownerId>` with GET requests to see the owner's data, limited to the
shared features (and to those the owner has on): `/api/day`, `/api/trends` and `/api/feed` leave the rest out
(the feed with no "Right now" and no health alerts),
`/api/profile` gives only units, time zone, goals and the features shared, and each feature's own
endpoints need it shared (`/api/series` and `/api/measurements` by their `metric`; photos only with
`PHOTOS`). `/api/fields` gives only the shared features' fields, without the conditions behind them;
`/api/conditions` is never shared. A day's `combined` keeps only the readings of shared features.
Anything else with the header (a change, or `/api/me`, devices and their choices, settings, admin,
reports, exports) is refused with 403. Shares go to existing accounts by username, and only for the features picked.

## Where your data goes

| | |
|---|---|
| `GET /api/privacy` | `DataMap`: each way something about the person leaves the server now (only those set up that send something out, and any paused) (`DataDestination`: who's at the other end, the `hosts`, what goes and when, its `state`, how many are set up, and `setUp`, where in the apps it's set up), a `note` on what stays put, and `admins`, what the server's admins can and can't see. Never shared |
| `PUT /api/privacy/{path}` | `DataPathChange` (`{paused}`) → `DataMap`: pauses or resumes one `DataPath` for this person |

`state` is `ON`, `PAUSED` (set up, but paused by the person: nothing goes), `NOT_SET_UP` or
`OFF_ON_SERVER` (the admin turned it off for everyone). Pausing keeps what's set up (a Mealie
token, a webhook, a share) and only stops anything going until it's resumed; every way out checks
it first. `SIGN_IN` can't be paused (`pausable: false`, and a PUT answers 400). While paused,
`MealieStatus.paused` and `AiStatus.paused` are true (the apps say so where Mealie and the
assistant show), and asking for something that would go (a Mealie recipe, the assistant, Home
Assistant's state) answers that it "is paused in Settings › Where your data goes".

## Reminders, digest, webhooks and Home Assistant

| | |
|---|---|
| `GET /api/push/key` | `PushKey`: the server's VAPID public key |
| `POST /api/push/subscriptions` · `GET` · `DELETE /{id}` | `PushSubscriptionInput` / `PushSubscriptionInfo` |
| `POST /api/push/test` | Sends a test notification to this account's browsers |
| `GET /api/reminders/upcoming?hours=24` | `[PushMessage]` with `at`: what's coming, as a browser would show it (the phone app plans its alarms from meds itself) |
| `GET /api/digest/latest` · `GET /api/digest?week=YYYY-MM-DD` | `Digest` (latest: the last one made, or last week's worked out now) |
| `GET /api/digest/smtp` | `SmtpStatus`: whether the server can send email |
| `POST /api/digest/email-test` | Sends the latest digest to `Profile.digest.email` |
| `GET /api/webhooks` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Webhook` (the `secret` comes back once, on POST) |
| `POST /api/webhooks/{id}/test` · `GET /api/webhooks/deliveries` | Sends a `test` event (answers the `WebhookDelivery`) · the last deliveries |
| `POST /api/integrations/keys` · `GET` · `DELETE /{id}` | `IntegrationKey` for Home Assistant (the `token` only on POST) |
| `GET /api/integrations/state` | With an integration key: today's numbers as flat JSON for REST sensors |
| `GET /api/admin/smtp` · `PUT` · `POST /api/admin/smtp/test` | Admins: `{settings, passwordSet}` · `{settings, password?}` · `{to}` |

Every minute the server sends what's due: doses (and again, every `remindAgainMinutes` while
unmarked), refills, appointments, habits, routines, bladder training, change checks, low supplies, cycle reminders,
voice practice and the weekly digest, by browser push (names only if
`ReminderPrefs.showNames`; nothing but doses in quiet hours) and to webhooks subscribed to the
event. A push is a `PushMessage` (JSON, encrypted per RFC 8291); "taken" and "skip" actions carry
`medicationId` and `scheduledFor` for `POST /api/meds/doses`.

Webhook calls are `POST` with a `WebhookPayload` body and `X-SAMH-Signature: sha256=<hex>`, the
HMAC-SHA256 of the body with the hook's secret; three tries for server errors. Names are left out
unless the hook's `includeDetails` is on. Private-network addresses are allowed unless the admin
turns off `webhooksPrivateNetworks`. See [home-assistant.md](home-assistant.md).

The admin sets up mail in `ServerSettings.smtp` (`enabled`, `host`, `port`, `security`
`NONE`/`STARTTLS`/`TLS`, `username`, `from`); the password goes to `PUT /api/admin/smtp` and is
kept sealed. Digest emails leave out sensitive features not in `DigestPrefs.emailModules`.

## Reports and export

| | |
|---|---|
| `GET /api/report.pdf?from=&to=&sections=SLEEP,VITALS,…` | A PDF for a doctor (sections: `ReportSectionKey`; default, every feature that's on) |
| `GET /api/export.zip?from=&to=` | CSV files of everything, and `fhir.json` (a FHIR R4 Bundle) |
| `GET /api/export/fhir?from=&to=` | The FHIR Bundle alone (`application/fhir+json`) |

## The watch app

| | |
|---|---|
| `GET /pebble/config?return_to=` | The watch app's settings page: `return_to` must start `pebblejs://close`; redirects to the web app's `/watch-setup?return_to=…` |
| `POST /api/watch/keys` | Signed in: makes a `WATCH` key, as an `IntegrationKey` whose `url` is the server's address. `/watch-setup` then goes to `return_to + "#" + encodeURIComponent(JSON.stringify({server, token}))` |
| `GET /api/watch/today` | `WatchToday` (with a `WATCH` key, or any app sign-in); `water` is false when the person has turned water off: the watch then hides its water tap and progress |
| `POST /api/watch/log` | `WatchLog`: one tap from the watch |
| `POST /api/ingest` | A `WATCH` key may also send data in, like an upload key |

## The AI connector (MCP)

`POST /mcp/<token>` (or `POST /mcp` with `Authorization: Bearer <token>`) speaks the Model
Context Protocol over Streamable HTTP, answering each JSON-RPC request with plain JSON. Tokens
come from `POST /api/me/connectors`: `MCP` links read the account's data, `MCP_WRITE` links can
also log entries. A link only ever reads its own account: `allAccounts` is refused, and an old
`MCP_ALL` link reads only its own account (never the sensitive modules). An admin's own link also
gets the server's log and settings. `ConnectorRequest.modules` names the sensitive modules a link
may read (and, for `MCP_WRITE`, log to). `tools/list` describes each tool.

## Admin

`GET/POST /api/admin/users`, `PATCH/DELETE /api/admin/users/{id}`,
`POST /api/admin/users/{id}/password`, `DELETE /api/admin/users/{id}/sessions`,
`GET/PUT /api/admin/settings`, `POST /api/admin/oidc/test`.

**USDA's food list on this server** (Admin › Food search; `ServerSettings.usdaLocal`: `use`,
`packaged`, `autoUpdate`). With `use` and a copy here, food searches and barcodes are answered from
`usda-foods.db` in the data folder and nothing leaves the server; until a copy is here, the online
service answers where a key is set (and `onlineError` says the copy isn't here yet), else Open Food
Facts where it's on.

| | |
|---|---|
| `GET /api/admin/usda-local` | `UsdaLocalStatus`: `ready`; `working`, `phase` and `progress` (0–1) while a download is under way; the copy's `release` (date), `packaged`, `foods` and `bytes` on disk; `lastUpdated`, `lastChecked`, `nextCheck`; `newer` (a newer release's date, from the last check); `error` and `errorAt` (the last failure, in plain words); and roughly what each choice takes (`everydayDownload`, `everydayDisk`, `allDownload`, `allDisk`, in bytes) |
| `POST /api/admin/usda-local/download` | Downloads the latest release (everyday foods, and packaged ones with `usdaLocal.packaged`) in the background → `UsdaLocalStatus`; 409 if one is under way |
| `POST /api/admin/usda-local/check` | Looks for a newer release now → `UsdaLocalStatus` |
| `DELETE /api/admin/usda-local` | Stops a download under way and removes the copy → `UsdaLocalStatus` |

The files are found on USDA's download page (`https://fdc.nal.usda.gov/download-datasets/`, public
domain) by their names, `FoodData_Central_{foundation_food,sr_legacy_food,survey_food,branded_food}_json_{date}.zip`,
the newest of each kind. Free space is checked first (the largest file, the new copy and a margin);
each file goes to a temporary file in the data folder, is read as it streams into a new copy beside
the one in use (never whole into memory), and is deleted; the new copy is swapped in at once when
complete, so search keeps working from the old one meanwhile, and a failure keeps it. Redirects are
followed only to the same host over https. Each month (while `use` is on) the server looks for a
newer release; with `autoUpdate` it downloads it too, the same way, and a failure waits for the
next check. The copy keeps each food's name, brand, barcode, data type, serving and nutrients per
serving, with an FTS5 index (a LIKE search where SQLite has no FTS5).
