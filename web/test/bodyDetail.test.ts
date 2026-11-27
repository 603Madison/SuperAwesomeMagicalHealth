// A card's details on Body (src/lib/bodyDetail.ts): which page a card opens, the spans each offers,
// the numbers (average, lowest and highest with when, the usual), each day's value from readings,
// the readings hour by hour, and the first few of a list (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DAY_DETAILS, DETAIL_SECTIONS, FIRST, againstText, againstUsual, byDay, byHour, dailyValues, detailOf, detailPath, detailSpan, detailSpans,
  firstFew, newestFirst, seriesPoints, spanDates, splitSpan, statsOf, usualOf,
} from "../src/lib/bodyDetail.ts";
import { BODY_DAY_SECTIONS } from "../src/lib/recent.ts";
import { backFor, tabOf } from "../src/lib/nav.ts";

const H = 3_600_000;

test("the sections' ids are saved: they stay as they are", () => {
  assert.deepEqual([...DETAIL_SECTIONS], ["headline", "chart", "stats", "list"]);
});

test("every card at 24 hours opens a detail with 24 hours", () => {
  for (const id of BODY_DAY_SECTIONS) {
    assert.ok((DAY_DETAILS as readonly string[]).includes(detailOf(id)), id);
    assert.deepEqual(detailSpans(detailOf(id)), ["day", "week", "month", "year"], id);
  }
});

test("time asleep and the nights open Sleep; the rest open their own", () => {
  assert.equal(detailOf("asleep"), "sleep");
  assert.equal(detailOf("nights"), "sleep");
  assert.equal(detailOf("sleep"), "sleep");
  assert.equal(detailOf("resting-hr"), "resting-hr");
  assert.equal(detailOf("workouts"), "workouts");
});

test("24 hours only where there are readings, and never while viewing a share", () => {
  assert.deepEqual(detailSpans("readiness"), ["week", "month", "year"]);
  assert.deepEqual(detailSpans("hrv", true), ["week", "month", "year"]);
  assert.equal(detailSpan(null, "hrv"), "day");
  assert.equal(detailSpan("month", "hrv"), "month");
  assert.equal(detailSpan("day", "hrv", true), "week");
  assert.equal(detailSpan(null, "hrv", true), "week");
  assert.equal(detailSpan(null, "readiness"), "week");
  assert.equal(detailSpan("year", "readiness"), "year");
  assert.equal(detailSpan("fortnight", "steps"), "day");
});

test("a detail's address keeps the span, and one device for 24 hours", () => {
  assert.equal(detailPath("hrv"), "/body/hrv");
  assert.equal(detailPath("hrv", "week"), "/body/hrv?span=week");
  assert.equal(detailPath("heart", "day", "ring 1"), "/body/heart?device=ring+1");
  assert.equal(detailPath("heart", "month", "ring1"), "/body/heart?span=month");
  assert.equal(detailPath("sleep-score", "year"), "/body/sleep-score?span=year");
});

test("a detail's back arrow goes to Body, and it lights Body's tab", () => {
  assert.deepEqual(backFor("/body/hrv"), { to: "/body", label: "Body" });
  assert.deepEqual(backFor("/body/sleep-score/"), { to: "/body", label: "Body" });
  assert.equal(tabOf("/body/hrv"), "body");
});

test("the average, and the lowest and highest with when (the latest of equals)", () => {
  const s = statsOf([{ at: 1, value: 60 }, { at: 2, value: 52 }, { at: 3, value: 71 }, { at: 4, value: 52 }, { at: 5, value: 64 }])!;
  assert.equal(s.average, 59.8);
  assert.equal(s.total, 299);
  assert.deepEqual(s.lowest, { at: 4, value: 52 });
  assert.deepEqual(s.highest, { at: 3, value: 71 });
  assert.deepEqual(s.latest, { at: 5, value: 64 });
  assert.equal(s.count, 5);
});

test("no numbers, no stats; ones that aren't numbers are left out", () => {
  assert.equal(statsOf([]), null);
  assert.equal(statsOf([{ value: Number.NaN }]), null);
  const s = statsOf([{ date: "2026-11-20", value: 7 }, { date: "2026-11-21", value: Number.NaN }])!;
  assert.equal(s.count, 1);
  assert.equal(s.latest.date, "2026-11-20");
});

test("a usual needs a few days, and says how far off it is", () => {
  assert.equal(usualOf([50, null, 46]), null);
  assert.equal(usualOf([50, 46, 48, undefined]), 48);
  assert.equal(usualOf([50], 1), 50);
  assert.deepEqual(againstUsual(55, 50), { share: 0.1, word: "above" });
  assert.equal(againstUsual(49, 50)?.word, "usual");
  assert.equal(againstUsual(40, 0), null);
  assert.equal(againstUsual(40, null), null);
  assert.equal(againstText(36, 48), "25% below your usual");
  assert.equal(againstText(49, 48), "About your usual");
  assert.equal(againstText(49, null), null);
});

test("each day's value, oldest first, only where there is one", () => {
  const days = [
    { date: "2026-11-22", hrv: 41, top: 120, bottom: 80 },
    { date: "2026-11-20", hrv: 48, top: 118 },
    { date: "2026-11-21", top: undefined },
  ];
  assert.deepEqual(dailyValues(days, (d) => d.hrv), [{ date: "2026-11-20", value: 48 }, { date: "2026-11-22", value: 41 }]);
  assert.deepEqual(dailyValues(days, (d) => d.top, (d) => d.bottom), [{ date: "2026-11-20", value: 118 }, { date: "2026-11-22", value: 120, extra: 80 }]);
});

test("the span is the last days; the ones before make the usual", () => {
  const days = ["2026-11-24", "2026-11-20", "2026-11-22", "2026-11-21", "2026-11-23"].map((date) => ({ date }));
  const { span, before } = splitSpan(days, 3);
  assert.deepEqual(span.map((d) => d.date), ["2026-11-22", "2026-11-23", "2026-11-24"]);
  assert.deepEqual(before.map((d) => d.date), ["2026-11-20", "2026-11-21"]);
  assert.deepEqual(splitSpan(days, 9).before, []);
});

test("a span's dates, across a month's end", () => {
  assert.deepEqual(spanDates("2026-12-02", 4), ["2026-11-29", "2026-11-30", "2026-12-01", "2026-12-02"]);
  assert.equal(spanDates("2026-11-24", 365).length, 365);
  assert.equal(spanDates("2026-11-24", 365)[0], "2025-11-25");
});

test("readings as each day's average, or total for counts", () => {
  const dateOf = (ms: number) => new Date(ms).toISOString().slice(0, 10);
  const base = Date.UTC(2026, 10, 20, 22);
  const points = [{ at: base, value: 60 }, { at: base + H, value: 70 }, { at: base + 3 * H, value: 50 }, { at: base + 4 * H, value: Number.NaN }];
  assert.deepEqual(byDay(points, dateOf), [{ date: "2026-11-20", value: 65 }, { date: "2026-11-21", value: 50 }]);
  assert.deepEqual(byDay(points, dateOf, "sum"), [{ date: "2026-11-20", value: 130 }, { date: "2026-11-21", value: 50 }]);
  assert.deepEqual(byDay([], dateOf), []);
});

test("readings hour by hour, newest first, with each hour's lowest and highest", () => {
  const hourOf = (ms: number) => String(Math.floor(ms / H));
  const t = Date.UTC(2026, 10, 24, 14);
  const rows = byHour([{ at: t + 50 * 60_000, value: 70 }, { at: t + 5 * 60_000, value: 60 }, { at: t + H + 60_000, value: 80 }, { at: t + 20 * 60_000, value: 65 }], hourOf);
  assert.deepEqual(rows, [
    { at: t + H + 60_000, value: 80, low: 80, high: 80, count: 1 },
    { at: t + 5 * 60_000, value: 65, low: 60, high: 70, count: 3 },
  ]);
  const steps = byHour([{ at: t, value: 300 }, { at: t + 30 * 60_000, value: 200 }], hourOf, "sum");
  assert.equal(steps[0].value, 500);
});

test("the first few of a list, or all of it, newest first", () => {
  const list = Array.from({ length: 10 }, (_, i) => i);
  assert.equal(firstFew(list, false).length, FIRST);
  assert.equal(firstFew(list, true).length, 10);
  assert.deepEqual(firstFew(list, false, 2), [0, 1]);
  assert.deepEqual(newestFirst([1, 2, 3]), [3, 2, 1]);
  assert.deepEqual(list.slice(0, 3), [0, 1, 2]);
});

test("an hour of points a day for a week or a month; at most 5,000 for a year", () => {
  assert.equal(seriesPoints(7), 168);
  assert.equal(seriesPoints(30), 720);
  assert.equal(seriesPoints(395), 5000);
});
