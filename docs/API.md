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
`HORMONES` and `APPOINTMENTS` opt-in). A module's endpoints work
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
| `GET /api/mind/screeners` | `[ScreenerDefinition]`: PHQ-9 and GAD-7, their questions and bands |
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
| `POST /api/cycle/kicks` · `PUT /{id}` · `DELETE /{id}` | `KickSession` |
| `POST /api/cycle/contractions` · `PUT /{id}` · `DELETE /{id}` | `Contraction` |
| `POST /api/cycle/feeds` · `PUT /{id}` · `DELETE /{id}` | `Feed` |
| `POST /api/cycle/hot-flashes` · `DELETE /{id}` | `HotFlash` |

While pregnant or after a birth, Mind offers the EPDS check-in.

### Appointments

| | |
|---|---|
| `GET /api/appointments?days=365&upcoming=true` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Appointment` |
| `GET /api/appointments/{id}/suggestions` | `[SuggestedQuestion]` from what was logged since the last appointment |

## Sharing

| | |
|---|---|
| `GET /api/shares` | `Shares`: who sees this account, and whose this account sees |
| `POST /api/shares` · `PUT /{id}` · `DELETE /{id}` | `ShareRequest` / `ShareChange` (the owner, or the viewer leaving) |

A viewer sends `X-SAMH-View: <ownerId>` with GET requests to see the owner's data, limited to the
shared features (`/api/day` and `/api/trends` leave the rest out). Anything else with the header is
refused.

## Reminders, digest, webhooks and Home Assistant

| | |
|---|---|
| `GET /api/push/key` | `PushKey`: the server's VAPID public key |
| `POST /api/push/subscriptions` · `GET` · `DELETE /{id}` | `PushSubscriptionInput` / `PushSubscriptionInfo` |
| `POST /api/push/test` | Sends a test notification to this account's browsers |
| `GET /api/reminders/upcoming?hours=24` | `[PushMessage]`: what's coming (the phone app plans its alarms from meds itself) |
| `GET /api/digest/latest` · `GET /api/digest?week=YYYY-MM-DD` | `Digest` |
| `POST /api/digest/email-test` | Sends the latest digest to `Profile.digest.email` |
| `GET /api/webhooks` · `POST` · `PUT /{id}` · `DELETE /{id}` | `Webhook` (the `secret` comes back once, on POST) |
| `POST /api/webhooks/{id}/test` · `GET /api/webhooks/deliveries` | Sends a `test` event · the last deliveries |
| `POST /api/integrations/keys` · `GET` · `DELETE /{id}` | `IntegrationKey` for Home Assistant |
| `GET /api/integrations/state` | With an integration key: today's numbers as flat JSON for REST sensors |

Webhook calls are `POST` with a `WebhookPayload` body and `X-SAMH-Signature: sha256=<hex>`, the
HMAC-SHA256 of the body with the hook's secret. The admin sets up mail (SMTP) for digests.

## Reports and export

| | |
|---|---|
| `GET /api/report.pdf?from=&to=&sections=SLEEP,VITALS,…` | A PDF for a doctor (sections: `ReportSectionKey`; default, every feature that's on) |
| `GET /api/export.zip?from=&to=` | CSV files of everything, and `fhir.json` (a FHIR R4 Bundle) |
| `GET /api/export/fhir?from=&to=` | The FHIR Bundle alone (`application/fhir+json`) |

## The watch app

| | |
|---|---|
| `GET /pebble/config?return_to=` | The watch app's settings page: after signing in, makes a `WATCH` key and returns to the watch |
| `GET /api/watch/today` | `WatchToday` (with a `WATCH` key, or any app sign-in) |
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
