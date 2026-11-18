// Energy and focus (src/lib/energy.ts): a check-in's draft, today's strip, blocks of hours, how far
// towards patterns, the ADHD medication in settings, reminder times, and Care's line (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BLOCK_MIN, MAX_REMINDERS, MIN_CHECKS, MIN_DAYS, addReminder, canSave, doseRange, emptyDraft, headlines, hourLabel, hourRange, isThin, medicationChoice,
  medicationOptions, mindBudgetText, neuroCareLine, patternProgress, ratingText, readTime, scaleX, soFar, stripMarks, stripX, toCheck, toggleTag,
} from "../src/lib/energy.ts";
import type { EnergyFactor, NeuroToday } from "../src/lib/types.ts";

const H = 3_600_000;
/** A day's clock with no time zone: the hour is just ms / H. */
const hourOf = (ms: number) => ms / H;
const say = { time: (ms: number) => `${Math.floor(ms / H) % 12 || 12}:${String(Math.round((ms % H) / 60_000)).padStart(2, "0")} ${ms / H >= 12 ? "PM" : "AM"}`, hm: (m: number) => `${m}m` };

test("hours read the same as the server's (EnergyPatterns.range), and doses count hours", () => {
  assert.equal(hourRange(9, 12), "9 AM – noon");
  assert.equal(hourRange(15, 18), "3 – 6 PM");
  assert.equal(hourRange(12, 15), "noon – 3 PM");
  assert.equal(hourRange(6, 9), "6 – 9 AM");
  assert.equal(hourRange(21, 24), "9 PM – midnight");
  assert.equal(hourRange(0, 6), "midnight – 6 AM");
  assert.equal(doseRange(0, 1), "First hour");
  assert.equal(doseRange(1, 3), "1–3 hours");
  assert.deepEqual([6, 9, 12, 15, 24].map(hourLabel), ["6 AM", "9 AM", "Noon", "3 PM", "Midnight"]);
});

test("a check-in needs energy or focus; tags and the note are optional", () => {
  const d = emptyDraft();
  assert.equal(canSave(d), false);
  assert.equal(canSave({ ...d, energy: 2 }), true);
  assert.equal(canSave({ ...d, focus: 5 }), true);
  assert.deepEqual(toCheck({ energy: 4, focus: 2, tags: ["STRESSED"], note: "  after the meeting " }, 1000), { at: 1000, energy: 4, focus: 2, tags: ["STRESSED"], note: "after the meeting" });
  assert.equal(toCheck({ ...d, energy: 3, note: "   " }, 1).note, undefined);
  assert.deepEqual(toggleTag(["UNWELL"], "STRESSED"), ["UNWELL", "STRESSED"]);
  assert.deepEqual(toggleTag(["UNWELL", "STRESSED"], "UNWELL"), ["STRESSED"]);
});

test("what a check-in rated, in words", () => {
  assert.equal(ratingText({ energy: 4, focus: 2 }), "Energy 4 · focus 2");
  assert.equal(ratingText({ focus: 3 }), "Focus 3");
  assert.equal(ratingText({ energy: 1 }), "Energy 1");
  assert.equal(ratingText({}), null);
});

test("today's strip runs 6 AM to midnight: check-ins as energy and focus, doses on their own", () => {
  assert.equal(stripX(6), 0);
  assert.equal(stripX(24), 1);
  assert.equal(stripX(15), 0.5);
  assert.equal(stripX(5.5), null);
  const marks = stripMarks(
    [
      { at: 15 * H, energy: 2, tags: [], id: "b" },
      { at: 9.75 * H, energy: 4, focus: 2, tags: [], id: "a" },
      { at: 2 * H, energy: 1, focus: 1, tags: [], id: "night" },
    ],
    [8 * H, 1 * H],
    hourOf,
  );
  assert.deepEqual(marks.map((m) => [m.kind, m.key, m.value ?? null]), [["energy", "a", 4], ["focus", "a", 2], ["energy", "b", 2], ["dose", `dose:${8 * H}`, null]]);
  assert.equal(marks[0].x, (9.75 - 6) / 18);
  assert.equal(marks[3].x, 2 / 18);
});

test("blocks with too few check-ins are quiet, and averages sit along the 1–5 track", () => {
  assert.equal(isThin({ count: BLOCK_MIN - 1 }), true);
  assert.equal(isThin({ count: BLOCK_MIN }), false);
  assert.equal(scaleX(1), 0);
  assert.equal(scaleX(3), 0.5);
  assert.equal(scaleX(5), 1);
  assert.equal(scaleX(7), 1);
});

test("every check-in moves the progress on; days count as much as check-ins", () => {
  assert.equal(patternProgress(0, 0), 0);
  assert.equal(patternProgress(MIN_CHECKS, MIN_DAYS), 1);
  assert.equal(patternProgress(MIN_CHECKS * 3, MIN_DAYS * 3), 1);
  assert.equal(patternProgress(MIN_CHECKS, 0), 0.5);
  assert.ok(patternProgress(6, 2) > patternProgress(5, 2));
  assert.equal(soFar(0, 0), "No check-ins yet.");
  assert.equal(soFar(1, 1), "So far: 1 check-in over 1 day.");
  assert.equal(soFar(6, 3), "So far: 6 check-ins over 3 days.");
});

test("the summary's own lines leave out the factors' sentences, which show with their titles", () => {
  const factor: EnergyFactor = {
    kind: "SLEEP", title: "Sleep", measure: "energy", with: 3.6, without: 2.4, withCount: 9, withoutCount: 5,
    sentence: "After 7 hours' sleep or more, your energy averaged 3.6, against 2.4 after less than 6 (9 and 5 days).",
  };
  const summary = ["Your focus is usually best 9 AM – noon: a good time for whatever needs it most.", factor.sentence];
  assert.deepEqual(headlines({ summary, factors: [factor] }), [summary[0]]);
  assert.deepEqual(headlines({ summary, factors: [] }), summary);
});

test("settings show the medication chosen, else the one found by its name, else none", () => {
  const meds = [
    { id: "m1", name: "Vitamin D", active: true },
    { id: "m2", name: "Ritalin", active: true },
    { id: "m3", name: "Elvanse", active: false },
  ];
  assert.deepEqual(medicationChoice({ settings: { medicationId: "m3", reminders: [] }, medication: "Elvanse" }, meds), { id: "m3", how: "chosen" });
  assert.deepEqual(medicationChoice({ settings: { reminders: [] }, medication: "Ritalin" }, meds), { id: "m2", how: "found" });
  // "None of these" sticks, even with a medication the names would find.
  assert.deepEqual(medicationChoice({ settings: { noMedication: true, reminders: [] }, medication: null }, meds), { id: "", how: "none" });
  assert.deepEqual(medicationChoice({ settings: { reminders: [] } }, meds), { id: "", how: "none" });
  // A choice that's since been deleted is no choice.
  assert.deepEqual(medicationChoice({ settings: { medicationId: "gone", reminders: [] } }, meds), { id: "", how: "none" });
  // Taken now, and the chosen one even once stopped; by name.
  assert.deepEqual(medicationOptions(meds).map((m) => m.id), ["m2", "m1"]);
  assert.deepEqual(medicationOptions(meds, "m3").map((m) => m.id), ["m3", "m2", "m1"]);
});

test("reminder times: read from a time input, each once, in order, six at most", () => {
  assert.equal(readTime("9:05"), "09:05");
  assert.equal(readTime("14:30:00"), "14:30");
  assert.equal(readTime("25:00"), null);
  assert.equal(readTime(""), null);
  assert.deepEqual(addReminder([], "14:00"), ["14:00"]);
  assert.deepEqual(addReminder(["14:00"], "09:30"), ["09:30", "14:00"]);
  assert.deepEqual(addReminder(["09:30"], "9:30"), ["09:30"]);
  assert.deepEqual(addReminder(["09:30"], "soon"), ["09:30"]);
  const full = ["08:00", "09:00", "10:00", "11:00", "12:00", "13:00"];
  assert.equal(addReminder(full, "14:00").length, MAX_REMINDERS);
});

const neuro = (n: Partial<NeuroToday>): NeuroToday => ({ overloadsToday: 0, routinesDue: [], focusMinutes: 0, ...n });
const text = (l: ReturnType<typeof neuroCareLine>) => (l ? [l.value, l.support].filter(Boolean).join(" · ") : null);

test("Care's Autism & ADHD line: the latest check-in and when focus is usually best", () => {
  const n = neuro({ lastCheck: { at: 9.75 * H, energy: 4, focus: 2, tags: [] }, bestFocus: "9 AM – noon" });
  assert.equal(text(neuroCareLine(n, say)), "Energy 4 · focus 2 at 9:45 AM · focus usually best 9 AM – noon");
  assert.equal(text(neuroCareLine(neuro({ bestFocus: "9 AM – noon" }), say)), "Focus usually best 9 AM – noon");
  assert.equal(text(neuroCareLine(neuro({ lastCheck: { at: 15 * H, focus: 3, tags: [] }, socialBattery: 4, focusMinutes: 50 }), say)),
    "Focus 3 at 3:00 PM · social battery 4 of 10 · 50m in focus sessions");
  assert.equal(text(neuroCareLine(neuro({ overloadsToday: 1 }), say)), "A hard moment today · Go gently. Calm mode is here if it helps");
  assert.equal(text(neuroCareLine(neuro({ overloadsToday: 1, bestFocus: "3 – 6 PM" }), say)), "Focus usually best 3 – 6 PM · a hard moment today, so go gently");
  assert.equal(neuroCareLine(neuro({}), say), null);
  assert.equal(neuroCareLine(undefined, say), null);
});

test("\"energy left\" is only Mind's own budget, never Autism & ADHD's line", () => {
  const n = neuro({ energyLeft: 6, lastCheck: { at: 10 * H, energy: 3, tags: [] } });
  assert.ok(!text(neuroCareLine(n, say))!.includes("energy left"));
  assert.equal(mindBudgetText(n), "6 energy left");
  assert.equal(mindBudgetText(neuro({ energyLeft: -2 })), "0 energy left");
  assert.equal(mindBudgetText(neuro({})), null);
  assert.equal(mindBudgetText(undefined), null);
});

test("no spoons, and patterns are never called causes", async () => {
  const { readFile } = await import("node:fs/promises");
  for (const file of ["../src/lib/energy.ts", "../src/pages/EnergyFocus.tsx", "../src/pages/Neuro.tsx", "../src/pages/Mind.tsx", "../src/pages/CareLines.tsx"]) {
    const source = await readFile(new URL(file, import.meta.url), "utf8");
    assert.ok(!/spoon/i.test(source), `${file} mentions spoons`);
  }
  const page = await readFile(new URL("../src/pages/EnergyFocus.tsx", import.meta.url), "utf8");
  assert.ok(page.includes("Patterns in your own check-ins, not proof of cause."));
  // What the page says (its comments aside) never puts a pattern down to a cause.
  const said = page.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "").replace(/not proof of cause/g, "");
  assert.ok(!/\b(causes?|caused by|because of|leads to|makes you)\b/i.test(said));
});
