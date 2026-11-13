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
| `POST /api/me/password` | `{current, new}` |
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
`FOOD`, `MEDS`, `SLEEP`, `ACTIVITY`, `TRENDS`, `BODY`, `DEVICES` and each feature's page); a tap
opens it. Without a link, the apps open the page of its `module` (`AppPage.of`); with neither, it
isn't a link. Viewing a share, pages that aren't shared (and Devices) aren't offered. `shownBy`
names the Today card that already shows the same thing (the day's doses: `MEDS`): the apps leave
the insight out while that card shows. The protein insight (today only) carries up to three
`suggestions` (`FoodSuggestion`, as below) and names the top one or two in its body.

## Food

| | |
|---|---|
| `GET /api/food/{date}` | `FoodDay`: entries, water, totals and the day's target |
| `POST /api/food/entries` | `FoodEntryInput` → `FoodEntry` (without a `food`: `name`, `nutrients` and an optional `serving` label, like "about 180 g") |
| `PATCH /api/food/entries/{id}` · `DELETE …` | `FoodEntryChange` → `FoodEntry` |
| `POST /api/food/copy` | `{from, to, meal?}`: copies a day's meal (or whole day) to another day |
| `GET /api/food/suggestions?date=&meal=` | `FoodSuggestions`: ideas from the last 120 days for `meal` (without one, the meal it is now in the person's time zone: breakfast until 10:30, lunch until 15:00, a snack until 17:00, dinner until 21:30, then a snack) |
| `GET /api/foods/search?q=` | `FoodSearch`: the person's foods, then Open Food Facts |
| `GET /api/foods/barcode/{code}` | `Food`, or 404 |
| `GET /api/foods/recent` | `[Food]`: the person's foods, most used first |
| `POST /api/foods` · `PUT /api/foods/{id}` · `DELETE /api/foods/{id}` | The person's own foods |
| `POST /api/water` · `DELETE /api/water/{id}` | `WaterEntry` |

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
| `POST /api/devices/upload-keys` | `UploadKeyRequest` → `UploadKey` (shown once) |
| `POST /api/ingest` | `IngestBatch` → `IngestResult` |
| `POST /api/import/fit` | multipart `file` (one or more `.fit`) → `FitImportResult` |

`POST /api/ingest` is idempotent: a sample is identified by device, metric and start time, a
sleep session and a workout by device and start time, so the app can resend a whole day
whenever it likes. Batches over 20,000 samples are refused; send them in parts.

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
  every five. Average, lowest and highest come from the merged line; so does the day's chart.
- **Resting heart rate:** the devices' own resting figures where they give them (weighted as
  below); otherwise the lowest the merged night line stayed (the lowest three five-minute points
  in a row).
- **HRV, blood oxygen, breathing rate, skin temperature, a device's temperature deviation and
  stress:** each device's own night value (for stress, its day's), weighted by how much of the
  night (or day) it measured and how well it suits the reading:

  | | Ring (finger) | Watch (wrist) | Phone | Other |
  |---|---|---|---|---|
  | Resting heart rate | 1.0 | 0.8 | 0.2 | 0.6 |
  | HRV | 1.0 | 0.6 | 0.1 | 0.5 |
  | Blood oxygen | 1.0 | 0.5 | 0.1 | 0.5 |
  | Breathing rate | 0.9 | 0.7 | 0.2 | 0.6 |
  | Skin temperature, temperature deviation | 1.0 | 0.5 | 0.1 | 0.5 |
  | Stress (the day's) | 0.6 | 1.0 | 0.2 | 0.7 |
  | Time asleep | 1.0 | 0.9 | 0.4 | 0.7 |

  A Colmi ring is a finger; a Pebble or Garmin a wrist; Gadgetbridge, Health Connect and other
  devices by their name ("ring", "watch", "band"…). How much it measured: up to 90 minutes from
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
| `GET /api/continence/wearing` | `ContinenceWearing`: what's on now, since when, the usual wear time, and when a change reminder is due |
| `GET /api/continence/products?days=90` | `[ProductStats]`: how each product goes (wear time, how full, leaks through, nights) |

### Medications

| | |
|---|---|
| `GET /api/meds` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Medication` (`?all=true` includes ones turned off) |
| `GET /api/meds/day/{date}` | `MedsDay`: each scheduled dose with its log and the next injection or patch site |
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

Air pressure comes from Open-Meteo for `Profile.place`, when the admin allows it (`weather`).
`GET /api/places?q=Leeds` finds places (`[Place]`) for the profile, through the server.

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
| `GET /api/neuro/energy?days=30` · `PUT /api/neuro/energy/{date}` | `EnergyDay` |
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
| `GET /api/ai/audit?days=30` | `[AiAuditEntry]` |

**Food photo estimates.** With Food among the features the person allowed, `POST /api/ai/food-photo`
sends one photo of a meal to Anthropic and returns what's in it: each food and drink with its
portion (grams, or ml), estimated kcal, protein, carbs, fat and fibre, a confidence (`LOW`,
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

## Sharing

| | |
|---|---|
| `GET /api/shares` | `Shares`: who sees this account, and whose this account sees |
| `POST /api/shares` · `PUT /{id}` · `DELETE /{id}` | `ShareRequest` / `ShareChange` (the owner, or the viewer leaving) |

A viewer sends `X-SAMH-View: <ownerId>` with GET requests to see the owner's data, limited to the
shared features (and to those the owner has on): `/api/day` and `/api/trends` leave the rest out,
`/api/profile` gives only units, time zone, goals and the features shared, and each feature's own
endpoints need it shared (`/api/series` and `/api/measurements` by their `metric`; photos only with
`PHOTOS`). `/api/fields` gives only the shared features' fields, without the conditions behind them;
`/api/conditions` is never shared. A day's `combined` keeps only the readings of shared features.
Anything else with the header (a change, or `/api/me`, devices and their choices, settings, admin,
reports, exports) is refused with 403. Shares go to existing accounts by username, and only for the features picked.

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
also log entries, and an admin's `MCP_ALL` link reads every account and the server's log.
`ConnectorRequest.modules` names the sensitive modules a link may read (and, for `MCP_WRITE`,
log to); every-account links never see them. `tools/list` describes each tool.

## Admin

`GET/POST /api/admin/users`, `PATCH/DELETE /api/admin/users/{id}`,
`POST /api/admin/users/{id}/password`, `DELETE /api/admin/users/{id}/sessions`,
`GET/PUT /api/admin/settings`, `POST /api/admin/oidc/test`.
