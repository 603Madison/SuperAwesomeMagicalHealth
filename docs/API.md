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
`BODY` to start; `MIND`, `CYCLE`, `CONTINENCE`, `MEDICATIONS` opt-in). A module's endpoints work
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
