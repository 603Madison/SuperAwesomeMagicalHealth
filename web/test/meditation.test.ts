// Guided meditation's pure parts (src/lib/meditation.ts): the timeline, the time left, the pacer,
// the sentences read one at a time, and which voice may read them (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  PACER_MAX, PACER_MIN, START, advance, afterWords, buildTimeline, chooseVoice, clockText, daysThisWeek, defaultLength, feltWord, isDone,
  loggedMinutes, meditationGroups, pacerAt, speechSeconds, splitSentences, timeLeft, totalSeconds, weekLine, worthLogging, type VoiceLike,
} from "../src/lib/meditation.ts";
import type { Meditation } from "../src/lib/types.ts";

const breath = { inSeconds: 4, holdSeconds: 0, outSeconds: 6, rounds: 3 };
const steps = [
  { say: "One two three four five.", pauseSeconds: 10 }, // 5 words: 2 s
  { say: "Breathe with the circle now.", pauseSeconds: 5, breath }, // 5 words: 2 s, then 30 s of breaths
  { say: "Rest.", pauseSeconds: 0 }, // 1 word: at least a second
];

test("a meditation plays as words, then breaths, then a pause, for each step", () => {
  const t = buildTimeline({ steps });
  assert.deepEqual(t.map((s) => [s.kind, s.step, s.seconds, s.start]), [
    ["say", 0, 2, 0], ["pause", 0, 10, 2],
    ["say", 1, 2, 12], ["breath", 1, 30, 14], ["pause", 1, 5, 44],
    ["say", 2, 1, 49],
  ]);
  assert.equal(totalSeconds(t), 50);
  assert.equal(t[3].breath, breath);
  assert.equal(speechSeconds("Ten words at two and a half each second: four."), 4);
});

test("as text only, words take their time and carry on into what follows", () => {
  const t = buildTimeline({ steps });
  let pos = advance(t, START, 1, false);
  assert.deepEqual(pos, { index: 0, elapsed: 1 });
  pos = advance(t, pos, 4, false);
  assert.deepEqual(pos, { index: 1, elapsed: 3 });
  // A long gap (the tab was in the background) runs on through several segments.
  pos = advance(t, pos, 20, false);
  assert.deepEqual(pos, { index: 3, elapsed: 11 });
  pos = advance(t, pos, 1000, false);
  assert.ok(isDone(t, pos));
  assert.equal(timeLeft(t, pos), 0);
});

test("read aloud, words wait for the voice to finish, however long it takes", () => {
  const t = buildTimeline({ steps });
  let pos = advance(t, START, 30, true);
  assert.deepEqual(pos, { index: 0, elapsed: 2 }, "held at the end of the words");
  pos = afterWords(t, pos);
  assert.deepEqual(pos, { index: 1, elapsed: 0 });
  pos = advance(t, pos, 4, true);
  assert.deepEqual(pos, { index: 1, elapsed: 4 });
  // Not words: afterWords leaves it be.
  assert.deepEqual(afterWords(t, pos), pos);
});

test("the time left counts down to the end", () => {
  const t = buildTimeline({ steps });
  assert.equal(timeLeft(t, START), 50);
  assert.equal(timeLeft(t, { index: 3, elapsed: 10 }), 50 - 14 - 10);
  assert.equal(clockText(65), "1:05");
  assert.equal(clockText(0.2), "0:01");
  assert.equal(clockText(0), "0:00");
  assert.equal(clockText(600), "10:00");
});

test("the pacer breathes in, holds and breathes out, round by round", () => {
  const b = { inSeconds: 4, holdSeconds: 2, outSeconds: 6, rounds: 2 };
  const start = pacerAt(b, 0);
  assert.equal(start.phase, "in");
  assert.equal(start.round, 1);
  assert.equal(start.secondsLeft, 4);
  assert.equal(start.scale, PACER_MIN);
  assert.equal(pacerAt(b, 2).phase, "in");
  assert.ok(Math.abs(pacerAt(b, 2).scale - (PACER_MIN + PACER_MAX) / 2) < 1e-9, "halfway in, halfway grown");
  assert.equal(pacerAt(b, 4.5).phase, "hold");
  assert.equal(pacerAt(b, 4.5).scale, PACER_MAX);
  assert.equal(pacerAt(b, 7).phase, "out");
  assert.equal(pacerAt(b, 7).secondsLeft, 5);
  assert.ok(pacerAt(b, 11.9).scale < PACER_MIN + 0.01);
  const second = pacerAt(b, 12);
  assert.equal(second.phase, "in");
  assert.equal(second.round, 2);
  // Done: resting small.
  const after = pacerAt(b, 24);
  assert.equal(after.phase, "out");
  assert.equal(after.scale, PACER_MIN);
  // No hold at all: straight from in to out.
  assert.equal(pacerAt({ ...b, holdSeconds: 0 }, 4).phase, "out");
});

test("words are read a sentence at a time, and a long sentence in parts", () => {
  assert.deepEqual(splitSentences("Welcome. Lie down or sit!  Is that comfortable? Good…   rest now"), [
    "Welcome.", "Lie down or sit!", "Is that comfortable?", "Good…", "rest now",
  ]);
  assert.deepEqual(splitSentences("“Yes, that happened.” Then set it down."), ["“Yes, that happened.”", "Then set it down."]);
  assert.deepEqual(splitSentences(""), []);
  const long = "Start at the top of your head, notice whatever is there, warmth or coolness or tingling, and then let your attention move slowly down to your forehead and your eyes, and your cheeks.";
  const parts = splitSentences(long, 80);
  assert.ok(parts.length > 1);
  assert.ok(parts.every((p) => p.length <= 80), parts.join(" | "));
  assert.equal(parts.join(" "), long);
});

const voice = (name: string, lang: string, localService: boolean, extra: Partial<VoiceLike> = {}): VoiceLike => ({ name, lang, localService, ...extra });

test("only a voice on this device reads: never a cloud one", () => {
  const cloud = [voice("Google US English", "en-US", false), voice("Google UK English Female", "en-GB", false), voice("Microsoft Aria Online (Natural)", "en-US", false)];
  assert.equal(chooseVoice(cloud, "en-US"), null, "nothing local: text only");
  const local = voice("Samantha", "en-US", true);
  assert.equal(chooseVoice([...cloud, local], "en-GB"), local);
  // A voice whose localService isn't plainly true doesn't count.
  assert.equal(chooseVoice([{ name: "Odd", lang: "en-US" } as unknown as VoiceLike], "en-US"), null);
});

test("the person's own English comes first, then better voices, then the default; never another language or a novelty voice", () => {
  const us = voice("Alex", "en-US", true, { default: true });
  const gb = voice("Daniel", "en-GB", true);
  const gbBetter = voice("Serena (Enhanced)", "en_GB", true);
  const fr = voice("Thomas", "fr-FR", true);
  const bells = voice("Bells", "en-US", true);
  assert.equal(chooseVoice([us, gb], "en-GB"), gb);
  assert.equal(chooseVoice([us, gb, gbBetter], "en-GB"), gbBetter);
  assert.equal(chooseVoice([gb, us], "en-US"), us);
  assert.equal(chooseVoice([gb, us], "en"), us, "no region: the default");
  // The meditations are English: a French page still gets an English voice, never a French one reading English.
  assert.equal(chooseVoice([fr, gb], "fr-FR"), gb);
  assert.equal(chooseVoice([fr], "fr-FR"), null);
  assert.equal(chooseVoice([bells], "en-US"), null);
  assert.equal(chooseVoice([voice("Bad News", "en-US", true), gb], "en-US"), gb);
});

const med = (id: string, kind: Meditation["kind"], title: string, minutes: number): Meditation => ({ id, kind, title, minutes, about: `${title}.`, steps: [] });
const LIST = [
  med("wind-down-5", "WIND_DOWN", "Wind down", 5), med("wind-down-10", "WIND_DOWN", "Wind down", 10), med("wind-down-15", "WIND_DOWN", "Wind down", 15),
  med("body-scan-10", "BODY_SCAN", "Body scan", 10), med("body-scan-5", "BODY_SCAN", "Body scan", 5),
  med("breathing-15", "BREATHING", "Slow breathing", 15),
];

test("the meditations by kind, the one used last first, at the length used last", () => {
  const plain = meditationGroups(LIST);
  assert.deepEqual(plain.map((g) => g.kind), ["WIND_DOWN", "BODY_SCAN", "BREATHING"]);
  assert.deepEqual(plain[1].lengths.map((l) => l.minutes), [5, 10]);
  const last = meditationGroups(LIST, "body-scan-5");
  assert.deepEqual(last.map((g) => g.kind), ["BODY_SCAN", "WIND_DOWN", "BREATHING"]);
  assert.equal(defaultLength(last[0], "body-scan-5"), "body-scan-5");
  assert.equal(defaultLength(last[1], "body-scan-5"), "wind-down-10");
  assert.equal(defaultLength(last[2], null), "breathing-15");
  assert.deepEqual(meditationGroups(LIST, "gone-10").map((g) => g.kind), ["WIND_DOWN", "BODY_SCAN", "BREATHING"]);
});

test("what's logged: a finished one, or one stopped after a minute; minutes within the server's limits", () => {
  assert.equal(worthLogging(20, true), true);
  assert.equal(worthLogging(59, false), false);
  assert.equal(worthLogging(60, false), true);
  assert.equal(loggedMinutes(90), 1.5);
  assert.equal(loggedMinutes(0), 0.01);
  assert.equal(loggedMinutes(700 * 60), 600);
  assert.equal(feltWord(5), "Very settled");
  assert.equal(feltWord(1), "Still unsettled");
  assert.equal(feltWord(undefined), null);
  assert.equal(feltWord(9), null);
});

test("this week: the days of the last seven with a session", () => {
  const sessions = [{ date: "2026-11-25" }, { date: "2026-11-25" }, { date: "2026-11-21" }, { date: "2026-11-19" }, { date: "2026-11-18" }, {}];
  assert.equal(daysThisWeek(sessions, "2026-11-25"), 3);
  assert.equal(daysThisWeek([], "2026-11-25"), 0);
  assert.equal(weekLine(34.6), "35 min this week");
  assert.equal(weekLine(0), "None yet this week");
});
