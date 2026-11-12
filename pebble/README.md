# SAM Health for Pebble

SuperAwesomeMagicalHealth on your wrist: today's scores at a glance, and one-tap logging for mood,
water, coffee, bladder and bowel, medications, habits, hot flashes and symptoms. It talks to your
own server through the Pebble app on your phone; nothing goes anywhere else.

Built for the Pebble Time 2, and also runs on the Pebble Time, Time Steel, Time Round, Pebble 2,
Pebble 2 Duo and Pebble Round 2 (`basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro`).

## Install it

1. Download `SuperAwesomeMagicalHealth-watch-<version>.pbw` from the
   [releases](../../../releases) (or the `SuperAwesomeMagicalHealth-watch` artifact of a CI run).
2. Open it on your phone with the Pebble app, which installs it on the watch.

## Connect it to your server

1. In the Pebble app, open **SAM Health**'s settings (the gear).
2. Type your server's address, for example `https://health.example.com`, and tap **Connect**.
3. Sign in on your server's page. It makes a key for the watch and comes back to the Pebble app.

The key can only read today's numbers and log taps; it can't see anything else. It's listed under
**Settings › Signed in on** on the server, where you can remove it at any time. To disconnect from
the watch's side, open the settings again and tap **Disconnect this watch**.

## What's on it

The menu shows what you have turned on in **Settings › Features** on the server:

| | |
|---|---|
| **Today** | Readiness, sleep and activity scores; steps and water against your goals; how the day feels right now (stressed, engaged, relaxed or restored) |
| **Mood** | Five faces, from very low to great |
| **Water** · **Coffee** | A glass or a bottle; a cup, a mug or a small cup |
| **Bladder & bowel** | A toilet trip, a leak or a bowel movement |
| **Medications** | Today's doses: choose one and **Taken** to mark it |
| **Habits** | Today's habits: choose one to tick it |
| **Hot flash** | Mild, medium or strong (with the cycle feature) |
| **Symptom** | What (headache, pain, nausea, tiredness, dizziness or something else), then how bad, 1 to 10 |
| **Update now** | Asks for today's numbers again (they also refresh every 30 minutes while the app is open) |

Each tap buzzes once when it's saved. If your server can't be reached, the tap is kept on your
phone (for up to two weeks) and sent, in order, the next time the app opens and the server answers.

## Building it

You need the [Pebble SDK](https://developer.repebble.com/sdk/) from Core Devices:

```bash
uv tool install pebble-tool==5.2.0 --python 3.13
```

```bash
pebble sdk install 4.44
```

```bash
cd pebble && pebble build
```

The watch app is `build/pebble.pbw`. Try it in the emulator with
`pebble install --emulator emery build/pebble.pbw`.

The phone-side logic (reading the settings page's answer, turning taps into log entries, the
offline queue) lives in `src/pkjs/logic.js` and has tests that run with plain Node:

```bash
node --test pebble/test/*.test.js
```

## How it talks to the server

- `GET /api/watch/today` (`WatchToday`) and `POST /api/watch/log` (`WatchLog`), with
  `Authorization: Bearer <watch key>`; see `docs/API.md`.
- The settings page is `https://<server>/pebble/config?return_to=pebblejs://close`. After you sign
  in, the server returns to `pebblejs://close#<JSON>` with `{"server": "…", "token": "…"}`.
- Between the phone and the watch, today's lists travel as lines of tab-separated fields (doses
  `time⇥name⇥0|1`, habits `name⇥0|1`), at most ten of each; the watch sends back row numbers, and
  the phone knows which dose (`medicationId@scheduledFor`) or habit each row is.
