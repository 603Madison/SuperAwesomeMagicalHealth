# SuperAwesomeMagicalFitness

Your own fitness and health server. Wearables (Colmi rings, Pebble, Garmin, anything in Health
Connect or Gadgetbridge) send heart rate, HRV, sleep, steps and workouts in through the phone app;
you log food, water, weight and how you feel; and the server turns it into Oura-style
**readiness, sleep and activity scores** against your own baseline, with plain-English insights,
trends and calorie targets. Everything stays on your server.

It's part of the SuperAwesomeMagical family (Music, Weather, Cockpit Manager) and looks like it:
Lexend, rounded cards over a soft glow, and the **Aurora** theme (purple and green) by default,
with Violet, Ocean and Ember, light and dark.

- **Server** (`server/`): Kotlin and Ktor with SQLite, in one container for Podman or Docker.
  Signs people in with **Authentik** (or any OpenID Connect provider), with a local
  **break-glass admin** that always works.
- **Web app** (`web/`): React, served by the server. Today, Sleep, Activity, Food, Body, Trends,
  Devices, Settings and Admin.
- **Android app** (`android/`): logs food (with barcode scanning), weight, workouts and
  check-ins, shows your days and trends, and carries data from your wearables to the server.
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

## Running it

The image is `ghcr.io/603madison/superawesomemagicalfitness`. Everything it keeps is in `/data`.

### Podman (Quadlet, the systemd way, shows in Cockpit)

```bash
mkdir -p ~/.config/containers/systemd
cp deploy/superawesomemagicalfitness.container ~/.config/containers/systemd/
```

```bash
systemctl --user daemon-reload && systemctl --user start superawesomemagicalfitness
```

```bash
loginctl enable-linger $USER
```

The first start logs the break-glass admin's password once:

```bash
journalctl --user -u superawesomemagicalfitness | grep -A3 "break-glass"
```

Then open `http://<server>:8090`. `AutoUpdate=registry` lets `podman auto-update` keep it current.

### Compose

```bash
cd deploy && podman compose up -d
```

(`docker compose up -d` works the same.) Set `SAMF_ADMIN_PASSWORD` in `compose.yaml` to choose the
break-glass admin's first password instead of reading it from the log.

### Lost the admin password?

```bash
podman exec superawesomemagicalfitness /opt/samf/bin/samf-server reset-admin-password
```

## Single sign-on with Authentik

1. In Authentik, create an **OAuth2/OpenID Provider** (confidential client, signing key set) and
   an **Application** for it, with the slug `superawesomemagicalfitness`.
2. In SuperAwesomeMagicalFitness, sign in as the break-glass admin and open **Admin**. Copy the
   **redirect URI** shown there into the provider's redirect URIs
   (`https://fitness.example.com/api/auth/oidc/callback`).
3. Fill in the issuer (`https://auth.example.com/application/o/superawesomemagicalfitness/`),
   client ID and secret, press **Check the issuer**, turn single sign-on on and save.
4. Optionally name a **group allowed to sign in** and an **admin group**; Authentik sends groups
   in the `groups` claim. People get an account the first time they sign in (unless you turn that
   off and add them yourself).

Turning off **People can sign in with a password** leaves only the break-glass admin able to use
one, so there's always a way in if Authentik is down.

Behind a reverse proxy, set `SAMF_PUBLIC_URL` and `SAMF_TRUST_PROXY=true`.

## Settings from the environment

| Variable | |
|---|---|
| `SAMF_DATA_DIR` | Where everything is kept (`/data` in the image) |
| `SAMF_PORT`, `SAMF_HOST` | Where it listens (8080, all addresses) |
| `SAMF_ADMIN_USERNAME`, `SAMF_ADMIN_PASSWORD` | The break-glass admin, made at first start |
| `SAMF_PUBLIC_URL` | The address people use (for sign-in redirects and links) |
| `SAMF_TRUST_PROXY`, `SAMF_PROXIES`, `SAMF_CLIENT_IP_HEADER` | Behind reverse proxies (and Cloudflare's `CF-Connecting-IP`) |
| `SAMF_OIDC_ISSUER`, `SAMF_OIDC_CLIENT_ID`, `SAMF_OIDC_CLIENT_SECRET` | Single sign-on (turns it on) |
| `SAMF_OIDC_BUTTON_LABEL`, `SAMF_OIDC_SCOPES`, `SAMF_OIDC_USER_GROUP`, `SAMF_OIDC_ADMIN_GROUP` | Its details |
| `SAMF_LOCAL_LOGIN` | `false`: only the break-glass admin uses a password |
| `SAMF_FOOD_DATABASE` | `false`: don't ask Open Food Facts (searches and barcodes stay on the server) |

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
entries** add `log_food`, `log_water`, `log_weight`, `log_workout` and `check_in`. An admin's
**every-account** link reads any account, plus `server_log` and `recent_warnings`.

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
podman build -t superawesomemagicalfitness .
```

`npm run dev` in `web/` serves the web app on :5173 against a server on :8080.

## Licence

GPL-3.0-only. Lexend is under the SIL Open Font License (`licenses/Lexend-OFL.txt`). Food data
from Open Food Facts is under the Open Database License.
