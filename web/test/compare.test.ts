// Two devices side by side (src/lib/compare.ts): which device is which, each value in its unit, how
// far apart they read in plain words, each night's span and rows, and night by night (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import * as compare from "../src/lib/compare.ts";
import {
  COMPARE_DEFAULT_NIGHTS, COMPARE_SECTIONS, asleepMinutes, called, drawnValue, knownMetrics, nightSpan, nightsParam,
  noDifference, placement, shortNames, tickText, title, trendKeys, trendPoints, type CompareFormat,
} from "../src/lib/compare.ts";
import { clockTime, zonedTime } from "../src/lib/today.ts";
import type { CompareKey, ComparedMetric, ComparedNight, SleepSession } from "../src/lib/types.ts";

const H = 3_600_000;
/** Times as en-US writes them, with plain spaces (newer ICU puts a narrow one before AM and PM). */
const plain = (s: string) => s.replace(/\s/g, " ");

// Lengths of time keep each number with its unit (no-break spaces); the tests read them plainly.
const duration = (m: number) => plain(compare.duration(m));
const valueText = (...a: Parameters<typeof compare.valueText>) => plain(compare.valueText(...a));
const signedText = (...a: Parameters<typeof compare.signedText>) => plain(compare.signedText(...a));
const drawnText = (...a: Parameters<typeof compare.drawnText>) => plain(compare.drawnText(...a));
const metricSentence = (...a: Parameters<typeof compare.metricSentence>) => compare.metricSentence(...a).map(plain);
const nightRows = (...a: Parameters<typeof compare.nightRows>) => compare.nightRows(...a).map((r) => ({ ...r, a: plain(r.a), b: plain(r.b), difference: plain(r.difference) }));

test("a length of time never breaks between a number and its unit", () => {
  assert.equal(compare.duration(432), "7 h 12 min");
  assert.equal(compare.duration(45), "45 min");
});
const dayBefore = (date: string) => new Date(Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)) - 1, Number(date.slice(8, 10)) - 1)).toISOString().slice(0, 10);

function format(zone = "America/New_York", units: "METRIC" | "IMPERIAL" = "METRIC"): CompareFormat {
  return {
    time: (ms) => plain(clockTime(ms, zone, false, "en-US")),
    evening: (date) => zonedTime(dayBefore(date), "18:00", zone),
    units,
    locale: "en-US",
  };
}
const fmt = format();

const strap = { id: "s", name: "Polar H10", kind: "HEART_RATE_STRAP" as const };
const ring = { id: "r", name: "Colmi R02", kind: "COLMI_RING" as const };
const words = shortNames(strap, ring);

function metric(key: CompareKey, change: Partial<ComparedMetric> = {}): ComparedMetric {
  return { key, count: 9, a: 58, b: 61, difference: 3, apart: 4, lowest: -1, highest: 7, ...change };
}

// ---- The address ----

test("nights from the address: the default, whole numbers within 1 to 90", () => {
  assert.equal(nightsParam(null), COMPARE_DEFAULT_NIGHTS);
  assert.equal(nightsParam(""), COMPARE_DEFAULT_NIGHTS);
  assert.equal(nightsParam("lots"), COMPARE_DEFAULT_NIGHTS);
  assert.equal(nightsParam("7"), 7);
  assert.equal(nightsParam("30"), 30);
  assert.equal(nightsParam("0"), 1);
  assert.equal(nightsParam("365"), 90);
  assert.equal(nightsParam("6.6"), 7);
});

test("the page's sections keep their saved ids", () => {
  assert.deepEqual([...COMPARE_SECTIONS], ["summary", "trend", "nights"]);
});

// ---- Which device is which ----

test("where a device is worn: by its kind, else by its name, a strap before a watch", () => {
  assert.equal(placement("COLMI_RING", "R02"), "ring");
  assert.equal(placement("HEART_RATE_STRAP", "Anything"), "strap");
  assert.equal(placement("PEBBLE", "Pebble Time 2"), "watch");
  assert.equal(placement("PHONE", "Pixel"), "phone");
  assert.equal(placement("HEALTH_CONNECT", "Polar H10"), "strap");
  assert.equal(placement("GADGETBRIDGE", "Polar Pacer Pro watch"), "watch");
  assert.equal(placement("HEALTH_CONNECT", "Oura"), "ring");
  assert.equal(placement("GADGETBRIDGE", "Coospo H808S"), "strap");
  assert.equal(placement("OTHER", "Wahoo TICKR"), "strap");
  assert.equal(placement("HEALTH_CONNECT", "Health Connect"), null);
  assert.equal(placement("HEALTH_CONNECT", "Phone"), "phone");
  assert.equal(placement(undefined, "My script"), null);
});

test("short names: 'the ring' and 'the strap' where that tells them apart, otherwise their names", () => {
  assert.deepEqual(words, { a: "strap", b: "ring", words: true });
  assert.equal(called(words, "b", true), "The ring");
  assert.equal(called(words, "a"), "the strap");
  assert.equal(title(words, "a"), "Strap");
  // Two rings: their names.
  const rings = shortNames({ name: "Colmi R02", kind: "COLMI_RING" }, { name: "Colmi R10", kind: "COLMI_RING" });
  assert.deepEqual(rings, { a: "Colmi R02", b: "Colmi R10", words: false });
  assert.equal(called(rings, "b", true), "Colmi R10");
  assert.equal(title(rings, "a"), "Colmi R02");
  // One the app can't place: names too.
  assert.equal(shortNames(strap, { name: "Health Connect", kind: "HEALTH_CONNECT" }).words, false);
  // The same name twice: numbered, so they can be told apart.
  assert.deepEqual(shortNames({ name: "Ring" }, { name: "Ring" }), { a: "Ring (1)", b: "Ring (2)", words: false });
});

// ---- Values in their units ----

test("lengths of time in hours and minutes", () => {
  assert.equal(duration(432), "7 h 12 min");
  assert.equal(duration(45), "45 min");
  assert.equal(duration(480), "8 h");
  assert.equal(duration(0), "0 min");
  assert.equal(duration(-65), "1 h 5 min");
  assert.equal(duration(59.6), "1 h");
});

test("each unit written its way", () => {
  assert.equal(valueText("ASLEEP", 432, fmt, "2026-11-17"), "7 h 12 min");
  assert.equal(valueText("NIGHT_HEART_RATE", 57.6, fmt, "2026-11-17"), "58 bpm");
  assert.equal(valueText("HRV", 41.2, fmt, "2026-11-17"), "41 ms");
  assert.equal(valueText("SPO2", 96.44, fmt, "2026-11-17"), "96.4%");
  assert.equal(valueText("RESPIRATORY_RATE", 14.2, fmt, "2026-11-17"), "14.2 per min");
  assert.equal(valueText("SKIN_TEMPERATURE", 34.06, fmt, "2026-11-17"), "34.1 °C");
  assert.equal(valueText("STEPS", 12_480, fmt, "2026-11-17"), "12,480 steps");
});

test("a temperature in °F for imperial units", () => {
  const imperial = format("America/New_York", "IMPERIAL");
  assert.equal(valueText("SKIN_TEMPERATURE", 34, imperial, "2026-11-17"), "93.2 °F");
  // A difference of 0.5 °C is 0.9 °F.
  assert.equal(signedText("SKIN_TEMPERATURE", 0.5, imperial), "+0.9");
});

test("a time: minutes after 6 pm the evening before, on the person's clock", () => {
  // 330 minutes after 6 pm on 16 November.
  assert.equal(valueText("BEDTIME", 330, fmt, "2026-11-17"), "11:30 PM");
  assert.equal(valueText("WAKE", 790, fmt, "2026-11-17"), "7:10 AM");
  // Where the person is: the same minutes, their own evening.
  assert.equal(valueText("WAKE", 790, format("Europe/London"), "2026-11-17"), "7:10 AM");
  // Clocks went back at 2 AM on 1 November: ten hours after 6 pm EDT is 3 AM EST.
  assert.equal(valueText("WAKE", 600, fmt, "2026-11-01"), "3:00 AM");
});

test("differences with their sign, and nothing at all as ±0", () => {
  assert.equal(signedText("NIGHT_HEART_RATE", 3.2, fmt), "+3");
  assert.equal(signedText("NIGHT_HEART_RATE", -1, fmt), "−1");
  assert.equal(signedText("NIGHT_HEART_RATE", 0.4, fmt), "±0");
  assert.equal(signedText("SPO2", -0.4, fmt), "−0.4");
  assert.equal(signedText("ASLEEP", 65, fmt), "+1 h 5 min");
  assert.equal(signedText("BEDTIME", -12, fmt), "−12 min");
  assert.equal(signedText("STEPS", 1240, fmt), "+1,240");
  assert.equal(noDifference("HRV", 0.4, fmt), true);
  assert.equal(noDifference("SPO2", 0.04, fmt), true);
  assert.equal(noDifference("SPO2", 0.1, fmt), false);
});

// ---- In plain words ----

test("how far apart, in plain words", () => {
  assert.equal(metricSentence(metric("NIGHT_HEART_RATE"), words, fmt).join(" · "),
    "The ring read 3 bpm higher than the strap · 4 apart on average · from −1 to +7 · 9 nights");
  assert.equal(metricSentence(metric("LOWEST_HEART_RATE", { difference: -2.4, apart: 2.6, lowest: -4, highest: 1, count: 4 }), words, fmt).join(" · "),
    "The ring read 2 bpm lower than the strap · 3 apart on average · from −4 to +1 · 4 nights");
});

test("HRV also as a share of the one compared against", () => {
  const hrv = metric("HRV", { a: 50, b: 44, difference: -6, apart: 7, lowest: -12, highest: 2 });
  assert.equal(metricSentence(hrv, words, fmt)[0], "The ring read 6 ms (12%) lower than the strap");
});

test("time asleep, times, blood oxygen, breathing and steps each in their own words", () => {
  assert.equal(metricSentence(metric("ASLEEP", { a: 400, b: 424, difference: 24, apart: 31, lowest: -12, highest: 58 }), words, fmt).join(" · "),
    "The ring counted 24 min more than the strap · 31 min apart on average · from −12 min to +58 min · 9 nights");
  assert.equal(metricSentence(metric("BEDTIME", { difference: -12, apart: 15, lowest: -30, highest: 5 }), words, fmt)[0], "The ring put it 12 min earlier than the strap");
  assert.equal(metricSentence(metric("SPO2", { difference: -0.8, apart: 1, lowest: -1.5, highest: 0.2 }), words, fmt)[0], "The ring read 0.8 points lower than the strap");
  assert.equal(metricSentence(metric("RESPIRATORY_RATE", { difference: 1.2 }), words, fmt)[0], "The ring read 1.2 breaths a minute higher than the strap");
  const steps = shortNames({ name: "Pebble Time 2", kind: "PEBBLE" }, { name: "Pixel 9", kind: "PHONE" });
  // Steps: as a share, over only the hours both were counting; a day's totals say nothing.
  assert.equal(metricSentence(metric("STEPS", { count: 6, a: 4000, b: 2760, difference: -1240, apart: 1300, lowest: -2100, highest: 340, pairs: 41 }), steps, fmt).join(" · "),
    "In the 41 hours both were counting, the phone counted 31% fewer steps than the watch · 6 days");
  assert.equal(metricSentence(metric("STEPS", { count: 6, a: 4000, b: 2760, difference: -1240, pairs: 1 }), steps, fmt)[0],
    "In the one hour both were counting, the phone counted 31% fewer steps than the watch");
  // An older server, without the hours.
  assert.equal(metricSentence(metric("STEPS", { count: 6, a: 4000, b: 2760, difference: -1240 }), steps, fmt)[0], "The phone counted 31% fewer steps than the watch");
  assert.equal(metricSentence(metric("STEPS", { count: 6, a: 4000, b: 4001, difference: 1, pairs: 30 }), steps, fmt)[0],
    "In the 30 hours both were counting, the phone and the watch counted about the same steps");
});

test("a night with no difference at the end of the range is plain 0", () => {
  assert.equal(metricSentence(metric("NIGHT_HEART_RATE", { lowest: 0.2, highest: 6 }), words, fmt)[2], "from 0 to +6");
  assert.equal(metricSentence(metric("ASLEEP", { difference: 31, apart: 31, lowest: 0, highest: 74 }), words, fmt)[2], "from 0 min to +1 h 14 min");
});

test("the same on average; too few nights say it's early, not how far apart they usually are", () => {
  assert.equal(metricSentence(metric("NIGHT_HEART_RATE", { difference: 0.3 }), words, fmt)[0], "The ring and the strap read the same on average");
  assert.deepEqual(metricSentence(metric("NIGHT_HEART_RATE", { count: 1, lowest: 3, highest: 3 }), words, fmt),
    ["The ring read 3 bpm higher than the strap", "only 1 night so far: a few more will show what's usual"]);
  assert.deepEqual(metricSentence(metric("NIGHT_HEART_RATE", { count: 2 }), words, fmt),
    ["The ring read 3 bpm higher than the strap", "only 2 nights so far: a few more will show what's usual"]);
  const phone = shortNames({ name: "COLMI R02_C301", kind: "COLMI_RING" }, { name: "Phone", kind: "HEALTH_CONNECT" });
  assert.deepEqual(metricSentence(metric("STEPS", { count: 1, a: 1054, b: 576, difference: -478, pairs: 5 }), phone, fmt),
    ["In the 5 hours both were counting, the phone counted 45% fewer steps than the ring", "only 1 day so far: a few more will show what's usual"]);
  // The same every night: no range.
  assert.deepEqual(metricSentence(metric("NIGHT_HEART_RATE", { count: 3, apart: 3, lowest: 3, highest: 3 }), words, fmt),
    ["The ring read 3 bpm higher than the strap", "3 apart on average", "3 nights"]);
});

test("with names, as they are", () => {
  const rings = shortNames({ name: "Colmi R02", kind: "COLMI_RING" }, { name: "Colmi R10", kind: "COLMI_RING" });
  assert.equal(metricSentence(metric("HRV", { a: 40, difference: 2 }), rings, fmt)[0], "Colmi R10 read 2 ms (5%) higher than Colmi R02");
});

test("a newer server's things are left out; the rest in the server's order", () => {
  const unknown = { ...metric("HRV"), key: "STRESS" as unknown as CompareKey };
  assert.deepEqual(knownMetrics([metric("STEPS"), unknown, metric("ASLEEP"), metric("HRV")]).map((m) => m.key), ["ASLEEP", "HRV", "STEPS"]);
});

// ---- Each night ----

const at = (h: number, m = 0) => Date.UTC(2026, 10, 17, h, m);
function session(start: number, end: number, stages = true): SleepSession {
  return { start, end, stages: stages ? [{ stage: "LIGHT", start, end: start + H }, { stage: "DEEP", start: start + H, end }] : [] };
}

test("a night's span: from the earlier start to the later end, either device alone, or none", () => {
  assert.deepEqual(nightSpan({ a: session(at(3, 30), at(11)), b: session(at(4), at(11, 40)) }), { start: at(3, 30), end: at(11, 40) });
  assert.deepEqual(nightSpan({ b: session(at(4), at(11, 40)) }), { start: at(4), end: at(11, 40) });
  assert.equal(nightSpan({}), null);
});

test("a night's rows: what both had, in the server's order (one device's own sleep alone is left out)", () => {
  const night: ComparedNight = {
    date: "2026-11-17",
    values: [
      { key: "NIGHT_HEART_RATE", a: 57.4, b: 60.6, pairs: 80 },
      { key: "ASLEEP", b: 424 },
      { key: "BEDTIME", a: 300, b: 312 },
      { key: "STRESS" as unknown as CompareKey, a: 1, b: 2 },
      { key: "HRV" },
      { key: "LOWEST_HEART_RATE", a: 50, b: 50.2 },
    ],
  };
  assert.deepEqual(nightRows(night, fmt), [
    { key: "BEDTIME", label: "Fell asleep", a: "11:00 PM", b: "11:12 PM", difference: "+12 min" },
    { key: "NIGHT_HEART_RATE", label: "Heart rate overnight", a: "57 bpm", b: "61 bpm", difference: "+3" },
    { key: "LOWEST_HEART_RATE", label: "Lowest heart rate overnight", a: "50 bpm", b: "50 bpm", difference: "±0" },
  ]);
  assert.deepEqual(nightRows({ date: "2026-11-17", values: [{ key: "ASLEEP", b: 424 }] }, fmt), []);
});

test("minutes asleep as the server counts them: every stage but awake, or the whole night without stages", () => {
  const s: SleepSession = {
    start: at(4), end: at(12),
    stages: [{ stage: "AWAKE", start: at(4), end: at(4, 10) }, { stage: "LIGHT", start: at(4, 10), end: at(8, 0) + 30_000 }, { stage: "REM", start: at(8, 0) + 30_000, end: at(12) }],
  };
  assert.equal(asleepMinutes(s), 470);
  // 469.5 minutes reads as 7 h 50 min, as the table's Time asleep does.
  assert.equal(duration(asleepMinutes({ ...s, stages: [{ stage: "LIGHT", start: at(4), end: at(11, 49) + 30_000 }] })), "7 h 50 min");
  assert.equal(asleepMinutes({ start: at(4), end: at(11), stages: [] }), 420);
});

// ---- Night by night ----

test("what to follow night by night: two nights or more, no times, heart rate and HRV first", () => {
  const metrics = [metric("ASLEEP"), metric("BEDTIME"), metric("SPO2", { count: 1 }), metric("HRV"), metric("STEPS"), metric("NIGHT_HEART_RATE")];
  assert.deepEqual(trendKeys(metrics), ["NIGHT_HEART_RATE", "HRV", "ASLEEP", "STEPS"]);
  assert.deepEqual(trendKeys([]), []);
});

test("night by night: oldest first, as drawn, a gap where one had none", () => {
  const nights: ComparedNight[] = [
    { date: "2026-11-17", values: [{ key: "ASLEEP", a: 420, b: 450 }] },
    { date: "2026-11-15", values: [{ key: "ASLEEP", b: 390 }] },
    { date: "2026-11-16", values: [{ key: "HRV", a: 40, b: 44 }] },
  ];
  assert.deepEqual(trendPoints(nights, "ASLEEP", fmt), [
    { date: "2026-11-15", a: null, b: 6.5 },
    { date: "2026-11-17", a: 7, b: 7.5 },
  ]);
  assert.equal(drawnValue("SKIN_TEMPERATURE", 34, format("UTC", "IMPERIAL")), 93.2);
  assert.equal(drawnText("ASLEEP", 7.25, fmt), "7 h 15 min");
  assert.equal(drawnText("NIGHT_HEART_RATE", 61, fmt), "61 bpm");
  assert.equal(drawnText("SKIN_TEMPERATURE", 93.24, format("UTC", "IMPERIAL")), "93.2 °F");
  assert.equal(tickText("ASLEEP", 6.5, fmt), "6.5 h");
  assert.equal(tickText("NIGHT_HEART_RATE", 60, fmt), "60");
  assert.equal(tickText("SPO2", 96.5, fmt), "96.5");
  assert.equal(tickText("STEPS", 12_000, fmt), "12K");
  assert.equal(tickText("STEPS", 4_000, fmt), "4,000");
});

test("Body's line for Compare devices: heart rate at night first, else the first thing both measured", () => {
  const line = (c: Parameters<typeof compare.compareLine>[0]) => plain(compare.compareLine(c, fmt) ?? "");
  assert.equal(line({ a: strap, b: ring, metrics: [metric("ASLEEP"), metric("NIGHT_HEART_RATE")] }), "The ring read 3 bpm higher than the strap");
  assert.equal(line({ a: strap, b: ring, metrics: [metric("HRV", { difference: -6, a: 50 })] }), "The ring read 6 ms (12%) lower than the strap");
  assert.equal(line({ a: strap, b: ring, metrics: [metric("NIGHT_HEART_RATE", { count: 1 })] }), "Only 1 night together so far");
  assert.equal(line({ a: strap, b: ring, metrics: [metric("STEPS", { count: 2 })] }), "Only 2 days together so far");
  assert.equal(compare.compareLine({ a: strap, b: ring, metrics: [] }, fmt), null);
  assert.equal(compare.compareLine({ a: strap, metrics: [metric("ASLEEP")] }, fmt), null);
});
