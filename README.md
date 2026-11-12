# SuperAwesomeMagicalHealth

Your own health server. Wearables (Colmi rings, Pebble, Garmin, anything in Health
Connect or Gadgetbridge) send heart rate, HRV, sleep, steps and workouts in through the phone app;
you log food, water, weight and how you feel; and the server turns it into Oura-style
**readiness, sleep and activity scores** against your own baseline, with plain-English insights,
trends and calorie targets. Turn on what else you want to keep track of: **mood and mental
health**, **your cycle**, a **bladder and bowel diary**, and **medications** with reminders.
Everything stays on your server, and the private parts are encrypted there.

It's part of the SuperAwesomeMagical family (Music, Weather, Cockpit Manager) and looks like it:
Lexend, rounded cards over a soft glow, and the **Aurora** theme (purple and green) by default,
with Violet, Ocean and Ember, light and dark.

- **Server** (`server/`): Kotlin and Ktor with SQLite, in one container for Podman or Docker.
  Signs people in with **Authentik** (or any OpenID Connect provider), with a local
  **break-glass admin** that always works.
- **Web app** (`web/`): React, served by the server. Today, Sleep, Activity, Food, Body, Mind,
  Cycle, Bladder & bowel, Medications, Trends, Devices, Settings and Admin.
- **Android app** (`android/`): logs food (with barcode scanning), weight, workouts, mood,
  periods, toilet trips and doses (with reminders), shows your days and trends, and carries data
  from your wearables to the server.
- **AI connector** (MCP): bring your data to the AI of your choice, to ask about it, spot
  patterns, log meals by chat, and troubleshoot device syncs.

## What it does

**Scores, the Oura way.** Each is 0–100, made of contributors that each score 0–100 and say what
they're based on, measured against *your* usual rather than a population average:

- **Readiness**: resting heart rate and HRV against your two-week baseline, body temperature,
  last night's sleep, your sleep balance over two weeks, yesterday's activity, your training
  load, and when in the night your heart rate bottomed out.
- **Sleep**: total sleep against your goal, efficiency, restfulness, deep and REM sleep, how
  long you took to fall asleep, and timing.
- **Activity**: daily goals, moving every hour, weekly goals, training frequency and volume, and
  recovery days.

**Insights** pick out what matters each day: a resting heart rate up 5 bpm, a short night against
your usual, sleep debt, low protein by the evening, a weight trend heading the right way.

**Food**: search your own foods and [Open Food Facts](https://openfoodfacts.org), scan barcodes,
quick-add calories, make your own foods, copy yesterday's breakfast, and track water. Calorie
targets come from your resting energy (Mifflin–St Jeor, with the female or male formula, or the
average of both, as fits your body), your devices' active calories, and the weight change you're
aiming for.

**Devices**: data from several devices is combined sensibly (a ring and a phone don't both count
the same steps; the night with sleep stages wins over one without). Sending the same data again
is harmless.

## Features you turn on

Settings › **Features**: each person picks what they use. Activity, sleep, food and body are on
to start; these are opt-in, encrypted on the server (AES-GCM, with a key file kept beside the
database), and shown to an AI connector only if the person says so when making its link:

- **Mind**: log mood (with energy, anxiety, feelings and what it's about) as often as you like,
  and see a month of it with **what seems to help** (mood after good nights, on workout days,
  after calm practice). **Calm tools**: box, 4-7-8 and coherent breathing, 5-4-3-2-1 grounding
  and a quiet timer. **Check-ins**: the PHQ-9 (depression) and GAD-7 (anxiety) questionnaires,
  scored and charted over time. A private **journal** with prompts, gratitude lists and CBT
  thought records. **I need support** is always one tap away, with the support lines your admin
  sets (988, Trans Lifeline, Samaritans and others to start), and it opens by itself if a
  check-in's answers call for it.
- **Cycle**: periods, symptoms, pain, ovulation tests, mucus and basal temperature. Averages,
  the next period with a likely range and confidence, the estimated fertile window, and ovulation
  confirmed by a sustained temperature rise from your ring or basal temperature. Readiness
  understands that a warmer, faster heart after ovulation is normal.
- **Bladder & bowel**: one-tap diary of toilet trips, leaks, bowel movements and pad changes,
  with urgency, leak size, triggers and the Bristol scale; night-time trips from your recorded
  sleep; patterns worth knowing; a guided **pelvic floor exercise**; and a printable **3-day
  bladder diary** for your doctor.
- **Medications**: medications and supplements on daily, weekly, every-few-days or as-needed
  schedules; Take and Skip, reminders on the phone, adherence, doses left, **injection and patch
  site rotation**, and **lab results** charted over time.

## Running it

The image is `ghcr.io/603madison/superawesomemagicalhealth`. Everything it keeps is in `/data`.

### Podman (Quadlet, the systemd way, shows in Cockpit)

```bash
mkdir -p ~/.config/containers/systemd
cp deploy/superawesomemagicalhealth.container ~/.config/containers/systemd/
```

```bash
systemctl --user daemon-reload && systemctl --user start superawesomemagicalhealth
```

```bash
loginctl enable-linger $USER
```

The first start logs the break-glass admin's password once:

```bash
journalctl --user -u superawesomemagicalhealth | grep -A3 "break-glass"
```

Then open `http://<server>:8090`. `AutoUpdate=registry` lets `podman auto-update` keep it current.

### Compose

```bash
cd deploy && podman compose up -d
```

(`docker compose up -d` works the same.) Set `SAMH_ADMIN_PASSWORD` in `compose.yaml` to choose the
break-glass admin's first password instead of reading it from the log.

### Lost the admin password?

```bash
podman exec superawesomemagicalhealth /opt/samh/bin/samh-server reset-admin-password
```

## Single sign-on with Authentik

1. In Authentik, create an **OAuth2/OpenID Provider** (confidential client, signing key set) and
   an **Application** for it, with the slug `superawesomemagicalhealth`.
2. In SuperAwesomeMagicalHealth, sign in as the break-glass admin and open **Admin**. Copy the
   **redirect URI** shown there into the provider's redirect URIs
   (`https://health.example.com/api/auth/oidc/callback`).
3. Fill in the issuer (`https://auth.example.com/application/o/superawesomemagicalhealth/`),
   client ID and secret, press **Check the issuer**, turn single sign-on on and save.
4. Optionally name a **group allowed to sign in** and an **admin group**; Authentik sends groups
   in the `groups` claim. People get an account the first time they sign in (unless you turn that
   off and add them yourself).

Turning off **People can sign in with a password** leaves only the break-glass admin able to use
one, so there's always a way in if Authentik is down.

Behind a reverse proxy, set `SAMH_PUBLIC_URL` and `SAMH_TRUST_PROXY=true`.

## Settings from the environment

| Variable | |
|---|---|
| `SAMH_DATA_DIR` | Where everything is kept (`/data` in the image) |
| `SAMH_PORT`, `SAMH_HOST` | Where it listens (8080, all addresses) |
| `SAMH_ADMIN_USERNAME`, `SAMH_ADMIN_PASSWORD` | The break-glass admin, made at first start |
| `SAMH_PUBLIC_URL` | The address people use (for sign-in redirects and links) |
| `SAMH_TRUST_PROXY`, `SAMH_PROXIES`, `SAMH_CLIENT_IP_HEADER` | Behind reverse proxies (and Cloudflare's `CF-Connecting-IP`) |
| `SAMH_OIDC_ISSUER`, `SAMH_OIDC_CLIENT_ID`, `SAMH_OIDC_CLIENT_SECRET` | Single sign-on (turns it on) |
| `SAMH_OIDC_BUTTON_LABEL`, `SAMH_OIDC_SCOPES`, `SAMH_OIDC_USER_GROUP`, `SAMH_OIDC_ADMIN_GROUP` | Its details |
| `SAMH_LOCAL_LOGIN` | `false`: only the break-glass admin uses a password |
| `SAMH_FOOD_DATABASE` | `false`: don't ask Open Food Facts (searches and barcodes stay on the server) |

Settings given here win at every start; the rest are on the Admin page.

## Bringing in data

- **The phone app** signs in to the server (through Authentik in a browser tab, or with a
  password) and syncs in the background:
  - **Colmi rings** (R02, R03, R06, R09, R10, R12, the Colmi Ring 2 and other rings that use the
    QRing app) directly over Bluetooth: heart rate, steps, calories, distance and sleep stages.
  - **Health Connect**: anything that writes to it, like Garmin Connect, Fitbit, Samsung Health,
    Oura or Gadgetbridge.
  - **Gadgetbridge's database export**, for the many devices Gadgetbridge supports.
- **Pebble Time 2**: pair it with Gadgetbridge (which supports Core Devices' new Pebbles) and
  turn on its Health Connect sync or its database export. A Pebble watch app that sends straight
  to the server is planned.
- **Garmin**: Health Connect, Gadgetbridge, or upload `.FIT` files on the Devices page (from the
  watch's `GARMIN/Activity` folder or exported from Garmin Connect).
- **Anything else**: make an **upload key** on the Devices page and `POST /api/ingest` (see
  [docs/API.md](docs/API.md)). A key can only send data in.

## The AI connector (MCP)

Settings › **AI connector** makes a link that gives an AI your data over the
[Model Context Protocol](https://modelcontextprotocol.io). It's a sign-in of its own: listed with
your devices and removable there.

- **Claude**: Settings › Connectors › Add custom connector, and paste the link.
- **Other MCP clients**: add it as a remote (Streamable HTTP) server. The token can be in the URL
  or sent as `Authorization: Bearer` to `/mcp`.
- **Clients that only run local servers**: `npx mcp-remote <link>`.

Its tools: `status`, `day`, `trends`, `sleep`, `workouts`, `food_log`, `profile`,
`search_foods`, and for troubleshooting `devices`, `sync_check` (gaps and duplicate nights per
device and day) and `readings` (raw data as devices sent it). Links made with **Let it log
entries** add `log_food`, `log_water`, `log_weight`, `log_workout` and `check_in`.

The private features are opt-in per link: **Also let it see** Mind (`mind`, `journal`), Cycle
(`cycle`), Bladder & bowel (`continence`) or Medications (`medications`), with `log_mood`,
`log_cycle_day`, `log_continence` and `log_dose` for links that may log. An admin's
**every-account** link reads any account's activity, sleep, food and devices, plus `server_log`
and `recent_warnings`, but never the private features.

## Building

Requires JDK 17+ (21 for the image), Node 22 and, for the Android app, the Android SDK
(platform 36).

```bash
cd web && npm ci && npm run build
```

```bash
./gradlew :server:test :server:installDist
```

```bash
./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
```

```bash
podman build -t superawesomemagicalhealth .
```

`npm run dev` in `web/` serves the web app on :5173 against a server on :8080.

## Licence

GPL-3.0-only. Lexend is under the SIL Open Font License (`licenses/Lexend-OFL.txt`). Food data
from Open Food Facts is under the Open Database License.
