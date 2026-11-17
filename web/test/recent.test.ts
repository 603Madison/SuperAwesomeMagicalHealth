// The last 24 hours (src/lib/recent.ts, and the time axis in src/lib/today.ts): which readings show,
// the numbers above each chart, a night's minutes, the sleep shaded, temperatures in °F, what each
// chart says to screen readers, and ticks and times in the person's zone (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  RECENT_METRICS, RECENT_SECTIONS, drawnAsLine, lastSynced, nightDevice, nightMinutes, nightSummary, nothingRecorded, readingStats, readingsByMetric,
  readingsSummary, signed, sleepSpans, stepsSummary, temperature, temperatureChangeText, temperatureText,
} from "../src/lib/recent.ts";
import { clockTime, hourTicks } from "../src/lib/today.ts";
import type { Metric, RecentReadings, RecentSeries, SleepSession, SleepStage, StageSpan } from "../src/lib/types.ts";

const H = 3_600_000;
const M = 60_000;
const utc = (y: number, mo: number, d: number, h = 0, mi = 0) => Date.UTC(y, mo - 1, d, h, mi);
/** Times as en-US writes them, with plain spaces (newer ICU puts a narrow one before AM and PM). */
const plain = (s: string) => s.replace(/\s/g, " ");
const hours = (ticks: number[], zone: string) => ticks.map((t) => plain(clockTime(t, zone, true, "en-US")));
const gaps = (ticks: number[]) => ticks.slice(1).map((t, i) => (t - ticks[i]) / H);

// ---- The time axis, in the person's zone ----

test("ticks every 6 hours, on the clock where the person is, across midnight", () => {
  const zone = "America/New_York";
  // 3:20 PM to 3:20 PM (EST, UTC−5).
  const to = utc(2026, 11, 17, 20, 20);
  const ticks = hourTicks(to - 24 * H, to, 6, zone);
  assert.deepEqual(hours(ticks, zone), ["6 PM", "12 AM", "6 AM", "12 PM"]);
  assert.deepEqual(ticks, [utc(2026, 11, 16, 23), utc(2026, 11, 17, 5), utc(2026, 11, 17, 11), utc(2026, 11, 17, 17)]);
  // The same window where it's 8 hours later: its own midnight.
  assert.deepEqual(hours(hourTicks(to - 24 * H, to, 6, "Europe/London"), "Europe/London"), ["12 AM", "6 AM", "12 PM", "6 PM"]);
});

test("ticks follow the clock when summer time ends: a 7-hour gap after midnight", () => {
  const zone = "America/New_York";
  // Clocks go back at 2 AM on Sunday 1 November 2026; the window ends at 2 PM EST.
  const to = utc(2026, 11, 1, 19);
  const ticks = hourTicks(to - 24 * H, to, 6, zone);
  assert.deepEqual(hours(ticks, zone), ["6 PM", "12 AM", "6 AM", "12 PM"]);
  assert.deepEqual(gaps(ticks), [6, 7, 6]);
});

test("ticks follow the clock when summer time starts: a 5-hour gap after midnight", () => {
  const zone = "Europe/London";
  // Clocks go forward at 1 AM on Sunday 29 March 2026; the window ends at 1:20 PM BST.
  const to = utc(2026, 3, 29, 12, 20);
  const ticks = hourTicks(to - 24 * H, to, 6, zone);
  assert.deepEqual(hours(ticks, zone), ["6 PM", "12 AM", "6 AM", "12 PM"]);
  assert.deepEqual(gaps(ticks), [6, 5, 6]);
});

test("ticks on the hour in a half-hour zone, and none outside the window", () => {
  const zone = "Asia/Kolkata";
  const to = utc(2026, 11, 17, 12);
  const ticks = hourTicks(to - 24 * H, to, 6, zone);
  assert.deepEqual(ticks, [utc(2026, 11, 16, 12, 30), utc(2026, 11, 16, 18, 30), utc(2026, 11, 17, 0, 30), utc(2026, 11, 17, 6, 30)]);
  assert.deepEqual(hours(ticks, zone), ["6 PM", "12 AM", "6 AM", "12 PM"]);
  // A tick right on the window's edge counts; an empty or backwards window has none.
  assert.deepEqual(hourTicks(utc(2026, 11, 17, 5), utc(2026, 11, 17, 11), 6, "Europe/London"), [utc(2026, 11, 17, 6)]);
  assert.deepEqual(hourTicks(utc(2026, 11, 17, 6), utc(2026, 11, 17, 6), 6, "Europe/London"), [utc(2026, 11, 17, 6)]);
  assert.deepEqual(hourTicks(utc(2026, 11, 17, 7), utc(2026, 11, 17, 6), 6, "Europe/London"), []);
});

test("every hour of a night, across midnight", () => {
  const zone = "America/New_York";
  // 11:08 PM to 6:30 AM.
  const ticks = hourTicks(utc(2026, 11, 17, 4, 8), utc(2026, 11, 17, 11, 30), 1, zone);
  assert.deepEqual(hours(ticks, zone), ["12 AM", "1 AM", "2 AM", "3 AM", "4 AM", "5 AM", "6 AM"]);
});

test("a night's times on the clock where the person is", () => {
  const start = utc(2026, 11, 17, 4, 8);
  const end = utc(2026, 11, 17, 11, 30);
  assert.equal(plain(`${clockTime(start, "America/New_York", false, "en-US")} – ${clockTime(end, "America/New_York", false, "en-US")}`), "11:08 PM – 6:30 AM");
  assert.equal(plain(clockTime(start, "Europe/London", false, "en-US")), "4:08 AM");
  // Written the way the person's locale writes times: a 24-hour clock in Britain.
  assert.match(clockTime(start, "Europe/London", false, "en-GB"), /^0?4:08$/);
  assert.match(clockTime(end, "America/New_York", false, "en-GB"), /^0?6:30$/);
  // A zone this browser doesn't know: the browser's own, rather than nothing.
  assert.match(plain(clockTime(start, "Mars/Olympus_Mons", false, "en-US")), /^\d{1,2}:\d{2} (AM|PM)$/);
});

// ---- A night ----

/** Her night: 11:08 PM to 6:30 AM in 17 spans of light, deep, REM and a few minutes awake. */
const NIGHT_START = utc(2026, 11, 17, 4, 8);
const SPANS: [SleepStage, number][] = [
  ["LIGHT", 22], ["DEEP", 48], ["LIGHT", 31], ["REM", 18], ["AWAKE", 3], ["LIGHT", 40], ["DEEP", 37], ["LIGHT", 26], ["REM", 29],
  ["LIGHT", 35], ["AWAKE", 4], ["LIGHT", 28], ["DEEP", 12], ["REM", 36], ["LIGHT", 41], ["REM", 27], ["AWAKE", 5],
];
function night(spans = SPANS, start = NIGHT_START): SleepSession {
  let at = start;
  const stages: StageSpan[] = spans.map(([stage, minutes]) => {
    const span = { stage, start: at, end: at + minutes * M };
    at = span.end;
    return span;
  });
  return { start, end: at, stages, id: "n1", deviceId: "ring", source: "COLMI R02_C301" };
}

test("a night's minutes, from its stages as the server works them out", () => {
  const n = nightMinutes(night());
  assert.equal(n.inBed, 442);
  assert.equal(n.deep, 48 + 37 + 12);
  assert.equal(n.rem, 18 + 29 + 36 + 27);
  assert.equal(n.light, 22 + 31 + 40 + 26 + 35 + 28 + 41);
  assert.equal(n.asleep, 442 - 12);
  assert.equal(n.awake, 12);
  assert.equal(n.staged, true);
});

test("asleep with the stage unknown counts as light; time between spans counts as awake; no stages: all asleep", () => {
  const s = night([["ASLEEP", 60], ["LIGHT", 30]]);
  s.end += 15 * M;
  assert.deepEqual(nightMinutes(s), { inBed: 105, asleep: 90, deep: 0, rem: 0, light: 90, awake: 15, staged: true });
  assert.equal(nightMinutes(night([["ASLEEP", 60]])).staged, false);
  const byHand: SleepSession = { start: NIGHT_START, end: NIGHT_START + 7 * H + 20 * M, stages: [], manual: true };
  assert.deepEqual(nightMinutes(byHand), { inBed: 440, asleep: 440, deep: 0, rem: 0, light: 0, awake: 0, staged: false });
});

test("the sleep shaded behind the charts: within the window, overlapping nights as one", () => {
  const from = utc(2026, 11, 16, 12);
  const to = from + 24 * H;
  const ring = night();
  const phone: SleepSession = { start: ring.start - 20 * M, end: ring.end - 30 * M, stages: [], deviceId: "phone" };
  const nap: SleepSession = { start: utc(2026, 11, 16, 19, 40), end: utc(2026, 11, 16, 20, 10), stages: [] };
  const before: SleepSession = { start: from - 3 * H, end: from + H, stages: [] };
  const gone: SleepSession = { start: from - 9 * H, end: from - 2 * H, stages: [] };
  assert.deepEqual(sleepSpans([ring, nap, phone, before, gone], from, to), [
    [from, from + H],
    [nap.start, nap.end],
    [phone.start, ring.end],
  ]);
  assert.deepEqual(sleepSpans([], from, to), []);
});

test("a night is named by its device only with every device showing and more than one", () => {
  const devices = [{ id: "ring", name: "COLMI R02_C301" }, { id: "phone", name: "Pixel 8" }];
  assert.equal(nightDevice(night(), { devices }), "COLMI R02_C301");
  assert.equal(nightDevice(night(), { devices, device: "ring" }), null);
  assert.equal(nightDevice(night(), { devices: devices.slice(0, 1) }), null);
  // A device no longer listed: the name the night came with.
  assert.equal(nightDevice({ ...night(), deviceId: "old" }, { devices }), "COLMI R02_C301");
});

// ---- Readings ----

const series = (metric: Metric | string | undefined, values: number[], extra: Partial<RecentSeries> = {}): RecentSeries => ({
  metric: metric as Metric | undefined,
  points: values.map((value, i) => ({ at: NIGHT_START + i * 5 * M, value })),
  ...extra,
});

test("readings by kind: one without a metric, with one this version doesn't know, or with nothing in it is left out", () => {
  const byMetric = readingsByMetric([
    series("HEART_RATE", [62, 58, 55]),
    series(undefined, [1, 2]),
    series("BLOOD_ALCOHOL", [0.1]),
    series("SPO2", []),
    series("STEPS", [120, 0, 840], { total: 960, bucketMinutes: 60 }),
  ]);
  assert.deepEqual(Object.keys(byMetric), ["HEART_RATE", "STEPS"]);
  assert.equal(byMetric.STEPS?.total, 960);
  assert.deepEqual(RECENT_METRICS, ["HEART_RATE", "SPO2", "HRV", "STRESS", "RESPIRATORY_RATE", "SKIN_TEMPERATURE", "TEMPERATURE_DEVIATION", "STEPS"]);
});

test("a line for readings that came often (a ring's heart rate), points for a few", () => {
  const every = (minutes: number, count: number) => Array.from({ length: count }, (_, i) => ({ at: NIGHT_START + i * minutes * M, value: 60 }));
  assert.equal(drawnAsLine(every(5, 200)), true);
  // An hour off to charge doesn't change that.
  const charging = every(5, 40).concat(every(5, 40).map((p) => ({ ...p, at: p.at + 5 * H })));
  assert.equal(drawnAsLine(charging), true);
  assert.equal(drawnAsLine(every(30, 15)), false);
  assert.equal(drawnAsLine(every(5, 7)), false);
  assert.equal(drawnAsLine([]), false);
});

test("nothing recorded: no night and no reading the page knows", () => {
  const base: RecentReadings = { from: 0, to: 24 * H, hours: 24, devices: [], sleep: [], series: [] };
  assert.equal(nothingRecorded(base), true);
  assert.equal(nothingRecorded({ ...base, series: [series(undefined, [1]), series("HEART_RATE", [])] }), true);
  assert.equal(nothingRecorded({ ...base, series: [series("HRV", [41])] }), false);
  assert.equal(nothingRecorded({ ...base, sleep: [night()] }), false);
});

test("the numbers above each chart, in the order they show", () => {
  const latest = { at: NIGHT_START + 3 * H, value: 74 };
  const s = series("HEART_RATE", [62, 52, 118, 74], { min: 52, max: 118, average: 68.4, latest });
  const labels = (m: Parameters<typeof readingStats>[0]) => readingStats(m, s).map((x) => x.label);
  assert.deepEqual(readingStats("HEART_RATE", s), [
    { label: "Latest", value: 74, at: latest.at }, { label: "Lowest", value: 52 }, { label: "Highest", value: 118 }, { label: "Average", value: 68.4 },
  ]);
  assert.deepEqual(labels("SPO2"), ["Lowest", "Average", "Latest"]);
  assert.deepEqual(labels("HRV"), ["Average", "Latest"]);
  assert.deepEqual(labels("STRESS"), ["Average", "Highest", "Latest"]);
  assert.deepEqual(labels("RESPIRATORY_RATE"), ["Average", "Latest"]);
  assert.deepEqual(labels("SKIN_TEMPERATURE"), ["Latest", "Lowest", "Highest"]);
  // Steps: the total, worked out from the hours if the server didn't give one.
  assert.deepEqual(readingStats("STEPS", series("STEPS", [120, 0, 840], { total: 960 })), [{ label: "Total", value: 960 }]);
  assert.deepEqual(readingStats("STEPS", series("STEPS", [120, 0, 840])), [{ label: "Total", value: 960 }]);
  // Missing numbers are left out rather than shown as nothing.
  assert.deepEqual(readingStats("SPO2", series("SPO2", [97])).map((x) => x.label), []);
  // One reading (a ring's nightly temperature): just the latest, not an average of one.
  const once = series("TEMPERATURE_DEVIATION", [-0.3], { min: -0.3, max: -0.3, average: -0.3, latest: { at: NIGHT_START, value: -0.3 } });
  assert.deepEqual(readingStats("TEMPERATURE_DEVIATION", once), [{ label: "Latest", value: -0.3, at: NIGHT_START }]);
});

// ---- Temperatures ----

test("°C as the server gives it; °F for imperial units", () => {
  assert.ok(Math.abs(temperature(37, "IMPERIAL") - 98.6) < 1e-9);
  assert.equal(temperature(0, "IMPERIAL"), 32);
  assert.equal(temperature(34.5, "METRIC"), 34.5);
  assert.equal(temperatureText(34.5, "IMPERIAL"), "94.1 °F");
  assert.equal(temperatureText(34.5, "METRIC"), "34.5 °C");
  // A difference from the usual is scaled, not shifted.
  assert.equal(temperatureChangeText(-0.5, "IMPERIAL"), "−0.9 °F");
  assert.equal(temperatureChangeText(0.3, "METRIC"), "+0.3 °C");
  assert.equal(temperatureChangeText(0.02, "METRIC"), "±0.0 °C");
  assert.equal(signed(1.26, "°F"), "+1.3 °F");
});

// ---- What the charts say to screen readers ----

test("each chart's summary: the lowest, highest, average and latest with its time", () => {
  const latest = { at: utc(2026, 11, 17, 19, 10), value: 74 };
  const s = series("HEART_RATE", [62, 52, 118, 74], { min: 52, max: 118, average: 68, latest });
  const time = (ms: number) => plain(clockTime(ms, "America/New_York", false, "en-US"));
  assert.equal(readingsSummary("Heart rate", s, (v) => `${v} bpm`, time),
    "Heart rate over the last 24 hours: lowest 52 bpm, highest 118 bpm, average 68 bpm, latest 74 bpm at 2:10 PM.");
  assert.equal(readingsSummary("Blood oxygen", series("SPO2", [97]), (v) => `${v}%`, time), "Blood oxygen over the last 24 hours.");
});

test("steps' summary: the total and the busiest hour", () => {
  // Hours from the window's start (3:20 PM), as the server counts them.
  const from = utc(2026, 11, 16, 20, 20);
  const steps: RecentSeries = {
    metric: "STEPS", bucketMinutes: 60, total: 4920,
    points: [{ at: from, value: 1200 }, { at: from + H, value: 1320 }, { at: from + 2 * H, value: 2400 }],
  };
  const time = (ms: number) => plain(clockTime(ms, "America/New_York", false, "en-US"));
  assert.equal(stepsSummary(steps, String, time), "Steps over the last 24 hours: 4920 in all, the most 2400 from 5:20 PM to 6:20 PM.");
  assert.equal(stepsSummary({ metric: "STEPS", points: [{ at: from, value: 0 }], total: 0 }, String, time), "Steps over the last 24 hours: 0 in all.");
});

test("a night's summary: when, how long asleep, and each stage", () => {
  const time = (ms: number) => plain(clockTime(ms, "America/New_York", false, "en-US"));
  const minutes = (m: number) => `${m}m`;
  assert.equal(nightSummary(night(), time, minutes), "Sleep from 11:08 PM to 6:30 AM: 430m asleep, deep 97m, REM 110m, light 223m, awake 12m.");
  const byHand: SleepSession = { start: NIGHT_START, end: NIGHT_START + 7 * H, stages: [] };
  assert.equal(nightSummary(byHand, time, minutes), "Sleep from 11:08 PM to 6:08 AM: 420m asleep.");
});

// ---- Devices ----

test("nothing recorded: the chosen device's last sync, or the latest of them all", () => {
  const devices = [
    { id: "ring", name: "COLMI R02_C301", lastSyncAt: utc(2026, 11, 15, 9) },
    { id: "phone", name: "Pixel 8", lastSyncAt: utc(2026, 11, 16, 9) },
    { id: "scale", name: "Scale" },
  ];
  assert.deepEqual(lastSynced(devices), { name: "Pixel 8", at: utc(2026, 11, 16, 9) });
  assert.deepEqual(lastSynced(devices, "ring"), { name: "COLMI R02_C301", at: utc(2026, 11, 15, 9) });
  assert.equal(lastSynced(devices, "scale"), null);
  assert.equal(lastSynced([], null), null);
});

test("the sections' ids are saved, so they stay as they are", () => {
  assert.deepEqual(RECENT_SECTIONS, ["sleep", "heart", "spo2", "hrv", "stress", "breathing", "temperature", "steps"]);
});
