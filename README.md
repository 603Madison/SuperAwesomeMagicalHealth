# SuperAwesomeMagicalHealth

<a href="https://603madison.github.io/SuperAwesomeMagicalHealth/"><img src="docs/images/get-it-on-fdroid.png" alt="Get it on F-Droid" height="80"></a>

Your own health server. Wearables (Colmi rings, Pebble, Garmin, anything in Health
Connect or Gadgetbridge) send heart rate, HRV, sleep, steps and workouts in through the phone app;
you log food, water, weight and how you feel; and the server turns it into Oura-style
**readiness, sleep and activity scores** against your own baseline, with plain-English insights,
trends, stress and resilience, and calorie targets. Turn on what else you want to keep track
of: **mood and mental health**, **your cycle** (and pregnancy, after a birth, perimenopause and
menopause), a **bladder and bowel diary**, **medications** with reminders, **blood pressure and
glucose**, **symptoms and migraines**, **habits**, **hormone therapy** and **appointments**. Get
a **weekly digest**, make a **report for your doctor**, and let someone who cares for you **see
what you choose**. Everything stays on your server, and the private parts are encrypted there.

It's part of the SuperAwesomeMagical family (Music, Weather, Cockpit Manager) and looks like it:
Lexend, rounded cards over a soft glow, and the **Aurora** theme (purple and green) by default,
with Violet, Ocean and Ember, light and dark.

- **Server** (`server/`): Kotlin and Ktor with SQLite, in one container for Podman or Docker.
  Signs people in with **Authentik** (or any OpenID Connect provider), with a local
  **break-glass admin** that always works.
- **Web app** (`web/`): React, served by the server: every feature, Trends, Reports, Devices,
  Settings and Admin, with reminders by browser notification.
- **Android app** (`android/`): logs food (with barcode scanning), weight, workouts, mood,
  periods, toilet trips and doses (with reminders), shows your days and trends, and carries data
  from your wearables to the server.
- **Pebble watch app** (`pebble/`): today's scores, and one-tap mood, water, coffee, bathroom,
  doses, habits, hot flashes and symptoms.
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

**Stress and resilience**: through the day, quarter hours are stressed, engaged, relaxed or
restored, from your heart rate and HRV against your own baseline (or your watch's own stress
readings); resilience is how well recovery (sleep, and calm time awake) has balanced stress over
two weeks, from limited to exceptional.

**Today** is calm: your scores, **Up next** (doses due, today's appointment, a routine), and
**For you**, a few insights that pick out what matters (a resting heart rate up 5 bpm, a short
night against your usual, sleep debt, protein behind for the time of day), each opening the page
it's about. When protein's behind, it suggests **meals you've actually eaten before** that would
help, for the meal that's coming up, with one-tap Add. Features with nothing logged yet wait in
one "Also today" card, and **+ Log** has every way to add something.

**Food**: search your own foods and [Open Food Facts](https://openfoodfacts.org), scan barcodes,
quick-add calories, make your own foods, copy yesterday's breakfast, and track water and
**drinks**: caffeine (and how much is likely still in you at bedtime) and alcohol, with how both
went with your sleep and HRV. Calorie
targets come from your resting energy (Mifflin–St Jeor, with the female or male formula, or the
average of both, as fits your body), your devices' active calories, and the weight change you're
aiming for.

**Two devices, one set of numbers**: when a ring and a watch (say a Colmi ring and a Pebble
Time 2) both measure something, the server works out its own number from both: steps from
whichever counted more each quarter hour, so nothing is counted twice; heart rate merged in time;
HRV, blood oxygen and temperature weighted to the ring at night and each compared with its own
usual, so a finger and a wrist combine fairly; and sleep timed by what they agree on, with stages
from the device that knows them (and if the ring's battery runs out at 3 am, the watch carries
the rest of the night). Anything from two devices says so: tap **From 2 devices** to see each
one's value and how they were combined, or choose one device for that reading. Sending the same
data again is harmless.

## What you're tracking

Pick the **conditions and goals** you live with or want to work on (overactive bladder, IBS,
endometriosis, PCOS, ADHD, autism, depression, high blood pressure, diabetes, transition,
pregnancy, losing weight, sleeping better… or your own), and the apps turn on the features and
the fields within them that fit, so you see what helps you and nothing else. Every field can
still be shown or hidden by hand, with a note of which condition brought it.

## Make it yours

- **Arrange any screen**: the Arrange button at the top of each screen moves its sections up and
  down and hides the ones you don't use, and "Put back as it was" undoes it. Saved on your server,
  so Today, its quick actions and your list of features look the same on the phone and the web.
- **Quick actions**: Today shows a few for the features you use; the rest are under More, and
  you choose which.
- **Leave out what you don't track**: every detail field can be hidden, and so can water
  (Settings › Features, under Food), which takes it off Today, Food, insights, the digest, the
  watch and the rest. What you've logged stays, and comes back if you turn it on again.

## Features you turn on

Settings › **Features**: each person picks what they use. Activity, sleep, food and body are on
to start. These are opt-in, encrypted on the server (AES-GCM, with a key file kept beside the
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
  understands that a warmer, faster heart after ovulation is normal. As much detail as you want:
  products used, clots, colour, where it hurts, mood, energy, libido, sex, cervix, LH and
  pregnancy tests, discharge, and **your own symptoms, moods and fields**. Your own cycle and
  period lengths, reminders before a period and when it's late, and **neutral wording** that
  leaves out fertility talk.
- **Bladder & bowel**: one-tap diary of toilet trips, leaks, bowel movements and pad changes,
  with urgency, leak size, triggers and the Bristol scale; night-time trips from your recorded
  sleep; patterns worth knowing; a guided **pelvic floor exercise**; and a printable **3-day
  bladder diary** for your doctor. Then **every detail you want** for an accident: what came
  out, what you were doing, whether you felt it coming and how long you had, whether you made
  it, where you were, **how full the pad or brief was** (dry to leaked through), its weight (and
  so how much was lost), changed clothes or bedding, sore skin, signs of an infection (with a
  nudge to see someone), wet nights, catheters, your own tags and fields. **Is it getting better
  or worse?** The last four weeks against the four before, week by week. **Bladder training**
  with reminders that space out over time, and weekly check-ins on how much it bothers you.
  **What you're wearing**: "Wearing now" says what's on and for how long, and a **change** is one
  step (what came off and how it was, what went on); each product's usual wear time, how full it
  tends to be and how often it leaks through; and an optional discreet "Time for a check".
- **Supplies**: the pads, briefs, diapers, bed pads, reusable underwear, catheters, wipes and
  period products you have on hand, counted down as you log changes, with how long they'll last
  and a reminder before you run out.
- **Medications**: medications and supplements on daily, weekly, every-few-days or as-needed
  schedules; Take and Skip, reminders (again if a dose isn't marked; refills), adherence, doses
  left, **injection and patch site rotation**, and **lab results** charted over time.
- **Symptoms & pain**: symptoms with severity, body areas, triggers and what helped, and
  **migraines** with aura, timing and medication. What went with them in your own data: short
  nights, stressful days, caffeine, alcohol, your cycle, and falling **air pressure** (from
  Open-Meteo, for a rough place you set, if the admin allows it).
- **Hormone therapy**, of any kind: hormone medications with how long you've been on them and
  every **dose change**, your **levels against the target ranges your clinician set**, and when
  labs are next due.
- **Appointments**: who, when and where, reminders before, **questions to ask** (suggested from
  what you've logged since the last one, from every feature that's on), and notes afterwards.
- **Autism & ADHD**, in Mind, in your words: **about me** (senses you avoid or seek, how you
  like to communicate, what helps, accommodations, warning signs, interests and stims) to show
  people who support you; times things got **too much** (overwhelm, meltdowns, shutdowns) with
  triggers and what helped; an **energy budget** (spoons) and social battery; check-ins on
  masking and executive function; a **focus timer** and body doubling that learn how long things
  really take; **routines** as checklists; **communication cards** for when talking is hard; a
  calm mode; and patterns, with a gentle note if burnout looks to be building.
- **Transition**, whatever it means to you, at your pace: pronouns, **milestones** (social,
  legal, medical, voice) with checklists for documents, changes you notice, hair removal, and
  moments of **euphoria** and dysphoria. With hormone therapy, it shows how long you've been on it.
- **Voice training**, free, in Transition: choose lighter and brighter, deeper and fuller, or
  exploring; record a starting point; then a guided programme (8 units, 27 lessons) through
  vocal health, warm-ups, pitch, resonance, vocal weight, intonation and into everyday speech,
  with **live feedback while you practise**: your pitch against your target range, resonance
  (darker to brighter), vocal weight, intonation and loudness, plus tones to match. Everything
  is listened to on your own device; recordings are optional and never leave it. Streaks,
  weekly progress against your starting point, gentle reminders, rest days, and vocal-health
  guidance throughout (a speech therapist is always worth seeing for pain or hoarseness).
- **Progress photos**, for transition, weight or anything else: albums and poses, a ghost of the
  last photo to line up the next one (in the phone app), and side-by-side comparisons with the
  weight and time on hormones then. Encrypted, stripped of location and camera details, **never
  shown to AI**, and shared only if you share Photos.
- **Life stages**, with the cycle: **pregnancy** (week, due date, a kick counter, a contraction
  timer that knows the 5-1-1 pattern), **after a birth** (feeds, and the **EPDS** check-in in
  Mind), **perimenopause** and **after menopause** (hot flashes, months since the last period;
  predictions widen or pause to suit).

And two that aren't private, so they aren't encrypted:

- **Blood pressure & glucose**: from Bluetooth monitors and meters through the phone app, Health
  Connect, or by hand; categories, your own targets, time in range and an A1c estimate.
- **Habits**: ticked by hand or by what's already recorded (steps, sleep, water, a mood logged,
  every dose taken, no alcohol…). Streaks count **weeks** that reach your aim, so a rest day never
  breaks one.

## Reminders, the weekly digest, and sharing

- **Reminders** come to the phone (they work offline), to browsers (web push, with Taken and
  Snooze), to the watch, and to webhooks: doses (again if not marked, and "missed" for webhooks),
  refills, habits and appointments, with quiet hours and **no medication names on a locked
  screen** unless you want them.
- **The weekly digest**: your week against the one before, on the day and hour you choose, in the
  apps and by email (the admin sets up mail; private features go in the email only if you say).
- **Sharing**: let another account on your server (a partner, a parent, a carer) **see** the
  features you pick, read-only. They switch to "Viewing Alex" and can't change anything; you can
  see when they last looked and stop sharing any time.

## Reports, export, webhooks and Home Assistant

- **A report for your doctor**: a PDF for the dates you choose, with averages, charts and what
  changed, feature by feature; private features only if you tick them.
- **Export everything**: CSV files, and a FHIR R4 bundle for health record systems.
- **To other apps**: the phone app can write weight, body fat, blood pressure, glucose, water,
  food and workouts to **Health Connect** (you pick which), where Samsung Health, MyFitnessPal,
  MyNetDiary and Google Fit read them. Workouts and weight also export as **FIT** files for
  Garmin Connect, Strava and others. Body measurements (waist, hips, chest…) chart with weight.
- **Webhooks**: the server calls a URL when something happens (morning scores ready, readiness
  low, a dose due or missed, blood pressure high, glucose low, a device's battery low…), signed
  with HMAC. Home Assistant's webhook trigger works as it is, and a **Home Assistant key** reads
  today's numbers for REST sensors: see [docs/home-assistant.md](docs/home-assistant.md).

## The Pebble watch app

`pebble/` has a native app for the Pebble Time 2 and other Pebbles. It shows today's scores and
logs mood, water, coffee, bladder and bowel, doses, habits, hot flashes and symptoms in one tap.
Install the `.pbw` from a [release](https://github.com/603Madison/SuperAwesomeMagicalHealth/releases),
open its settings in the Pebble app, type your server's address and sign in. Taps made while the
server is unreachable wait on your phone and send later. See [pebble/README.md](pebble/README.md).

## Getting the app

**With F-Droid** (and updates from then on): open
[the F-Droid page](https://603madison.github.io/SuperAwesomeMagicalHealth/) on your phone and tap
**Add to F-Droid**, or in F-Droid go to Settings › Repositories › **+** and add

```
https://603madison.github.io/SuperAwesomeMagicalHealth/fdroid/repo?fingerprint=0E7544D531E710F53AA540D01256556AD42A62D7ABDC64CF0424339FFBEBB580
```

Then install SuperAwesomeMagicalHealth from it. The repository's fingerprint is the app's own
signing key, so it's the same app as the APK on each
[release](https://github.com/603Madison/SuperAwesomeMagicalHealth/releases), which you can also
install directly. Every release updates the repository (`.github/workflows/fdroid.yml`).

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

### Updating

The phone app and the server go together: keep the server as new as the app (the app says so on
Today and in Settings when the server is older). With the Quadlet above:

```bash
podman auto-update
```

Otherwise, pull the image and start the container again:

```bash
podman pull ghcr.io/603madison/superawesomemagicalhealth:latest
```

```bash
systemctl --user restart superawesomemagicalhealth
```

(With Compose: `podman compose pull && podman compose up -d` in `deploy/`.) Your data in `/data`
stays as it is; the server brings its database up to date when it starts.

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
    QRing app) directly over Bluetooth, no Colmi app needed: heart rate, steps, calories,
    distance, sleep stages, blood oxygen, HRV and stress, and skin temperature on the R09 and
    R10. The app turns on the ring's own measurements for them.
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

## The AI assistant (optional, off unless turned on)

An admin can add an Anthropic API key so people can ask questions about their data in the apps
("How have my nights been since I started the new medication?", "Am I leaking less?"). **It's
off until the admin turns it on, and then each person decides for themselves.** Before anyone uses
it, it says plainly what that means:

- What the assistant looks at to answer is **sent to Anthropic**, a company outside your server,
  and handled under its [commercial terms](https://www.anthropic.com/legal/commercial-terms) and
  [privacy policy](https://www.anthropic.com/legal/privacy).
- **You choose the features it may look at.** Private ones (mind, cycle, bladder and bowel,
  medications…) are never ticked for you and need a separate OK. It **never sees your progress
  photos**.
- **Food photo estimates**: with Food allowed, **Snap a photo** when adding food sends that one
  photo (shrunk, without its location or camera details) to Anthropic to estimate what's on the
  plate and roughly how much. You check and change everything before it's logged, it can use your
  own foods where the names match, and the photo isn't kept on your server. A photo goes only when
  you take one. In eating-disorder recovery it shows foods and portions without numbers.
- Letting it log things for you is a separate choice, and only if the admin allows it.
- Each answer shows what it looked at, and a log keeps which features and tools it used (never
  your words). You can stop and withdraw your choice at any time.
- It can be wrong, and it isn't medical advice.

If your data is very sensitive to you, leaving it off is a perfectly good choice: everything else
works the same without it.

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
(`cycle`), Bladder & bowel (`continence`, `supplies`, `continence_progress`), Medications
(`medications`), Autism & ADHD (`neuro`), Transition (`transition`) and the others, with
`log_mood`, `log_cycle_day`, `log_continence`, `log_dose`, `log_overload`, `log_energy` and
`log_moment` for links that may log. `conditions` lists only the conditions whose private
features the link may see. Photos are never offered. An admin's
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
from Open Food Facts is under the Open Database License. The "Get it on F-Droid" badge is F-Droid's
artwork, under CC BY-SA 3.0.
