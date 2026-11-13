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

## Food

| | |
|---|---|
| `GET /api/food/{date}` | `FoodDay`: entries, water, totals and the day's target |
| `POST /api/food/entries` | `FoodEntryInput` → `FoodEntry` |
| `PATCH /api/food/entries/{id}` · `DELETE …` | `FoodEntryChange` → `FoodEntry` |
| `POST /api/food/copy` | `{from, to, meal?}`: copies a day's meal (or whole day) to another day |
| `GET /api/foods/search?q=` | `FoodSearch`: the person's foods, then Open Food Facts |
| `GET /api/foods/barcode/{code}` | `Food`, or 404 |
| `GET /api/foods/recent` | `[Food]`: the person's foods, most used first |
| `POST /api/foods` · `PUT /api/foods/{id}` · `DELETE /api/foods/{id}` | The person's own foods |
| `POST /api/water` · `DELETE /api/water/{id}` | `WaterEntry` |

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
| `DELETE /api/devices/{id}` | Forgets a device and everything it recorded |
| `POST /api/devices/upload-keys` | `UploadKeyRequest` → `UploadKey` (shown once) |
| `POST /api/ingest` | `IngestBatch` → `IngestResult` |
| `POST /api/import/fit` | multipart `file` (one or more `.fit`) → `FitImportResult` |

`POST /api/ingest` is idempotent: a sample is identified by device, metric and start time, a
sleep session and a workout by device and start time, so the app can resend a whole day
whenever it likes. Batches over 20,000 samples are refused; send them in parts.

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
no water insights or tips; no water line in the digest (kept ones too), the report or the export;
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
| `GET /api/continence/settings` · `PUT` | `ContinenceSettings` (quick buttons, own triggers, activities and places, custom fields, the usual product, night hours, bladder training) |
| `GET /api/continence/progress?weeks=12` | `ContinenceProgress`: getting better or worse, week by week |
| `GET /api/continence/check-ins?days=180` · `POST` · `DELETE /{id}` | `ContinenceCheckIn` (weekly bother) |

`ContinenceEntry` carries the detail fields; a `PAD_CHANGE` (or an entry with `count`) takes products
off its supply's count; `padWeightG` with the supply's dry weight sets `lossG`. `ContinenceDay` counts
the day: `bowelLeaks`, `wetNights`, `products`, `leakedThrough`, `catheters`, `catheterMl` and `lossG`.
Bladder training reminds through `FeatureHooks.reminders` (push, the phone, the `bladder.training`
webhook), with discreet lock-screen words.

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
| `GET /api/transition/hair-removal?days=365` · `POST` · `DELETE /{id}` | `HairRemovalSession` |
| `GET /api/transition/moments?days=90` · `POST` · `DELETE /{id}` | `GenderMoment` |

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
| `GET /api/ai/audit?days=30` | `[AiAuditEntry]` |

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
`/api/conditions` is never shared. Anything else
with the header (a change, or `/api/me`, devices, settings, admin, reports, exports) is refused with
403. Shares go to existing accounts by username, and only for the features picked.

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
unmarked), refills, appointments, habits, routines, bladder training, low supplies, cycle reminders
and the weekly digest, by browser push (names only if
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
