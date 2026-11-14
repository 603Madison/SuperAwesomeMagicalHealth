// What's being worn and a change as one step (src/lib/continence.ts): the products offered and
// their order, the Change sheet's starting point, and how a change, a wear and a product read (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  asChange, changeAfter, changeCount, changeDetail, changeDraft, changeDue, changeLine, changeTitle, diaryRows, hoursShort, inWhatsOn, kindWord, newEntry,
  productLine, putOnAlone, putOnEntry, putOnOrder, recentPutOns, rough, sinceLine, usualLine, wearable, wearLine, wearState, wornFor,
} from "../src/lib/continence.ts";
import type { ContinenceDiary, ContinenceEntry, ContinenceSettings, ContinenceWear, ContinenceWearing, ProductStats, Supply } from "../src/lib/types.ts";

const supply = (id: string, name: string, kind: Supply["kind"], active = true): Supply => ({ id, name, kind, onHand: 10, reusable: false, active });
const NIGHT = supply("n", "Night briefs", "BRIEF");
const DAY = supply("d", "Day pants", "PULL_UP");
const LINER = supply("l", "Liners", "LINER");
const WIPES = supply("w", "Wipes", "WIPES");
const OLD = supply("o", "Old pads", "PAD", false);
const ALL = [LINER, DAY, NIGHT, WIPES, OLD];
const settings = (defaultSupplyId?: string): ContinenceSettings => ({
  quickButtons: [], triggers: [], activities: [], locations: [], customFields: [], defaultSupplyId,
  bladderTraining: { enabled: false, intervalMinutes: 60, from: "08:00", to: "21:00", stepMinutes: 15, stepEveryDays: 7, goalMinutes: 180 },
});
const change = (at: number, off?: string, on?: string, extra: Partial<ContinenceEntry> = {}): ContinenceEntry =>
  ({ at, kind: "PAD_CHANGE", triggers: [], pain: false, blood: false, night: false, supplyId: off, putOnSupplyId: on, ...extra });
const NOW = new Date("2026-11-13T06:30").getTime();
const SINCE = new Date("2026-11-12T23:20").getTime();

test("only active, worn products are offered", () => {
  assert.deepEqual(wearable(ALL).map((s) => s.name), ["Liners", "Day pants", "Night briefs"]);
});

test("putting on: the usual first, then the last used, then the rest as listed", () => {
  assert.deepEqual(putOnOrder(ALL, "n", ["d"]).map((s) => s.id), ["n", "d", "l"]);
  assert.deepEqual(putOnOrder(ALL, undefined, ["n", "d"]).map((s) => s.id), ["n", "d", "l"]);
  assert.deepEqual(putOnOrder(ALL).map((s) => s.id), ["l", "d", "n"]);
  // The usual put away isn't offered.
  assert.deepEqual(putOnOrder(ALL, "o").map((s) => s.id), ["l", "d", "n"]);
});

test("recent put-ons: newest first, older changes counting what they took off, nothing-on skipped", () => {
  const entries = [
    change(1, undefined, "n"),
    change(3, "n", "d"),
    change(2, "l", undefined),
    change(4, "d", undefined, { nothingOn: true }),
    { ...change(5), kind: "LEAK" as const, supplyId: "l" },
  ];
  assert.deepEqual(recentPutOns(entries), ["d", "l", "n"]);
});

test("the Change sheet starts from what's on, putting the same on again", () => {
  const wearing: ContinenceWearing = { supplyId: "n", name: "Night briefs", since: SINCE };
  const d = changeDraft(wearing, settings("d"), ALL, ["l"], NOW);
  assert.equal(d.kind, "PAD_CHANGE");
  assert.equal(d.at, NOW);
  assert.equal(d.supplyId, "n");
  assert.equal(d.putOnSupplyId, "n");
  assert.equal(d.nothingOn, false);
  // Nothing known: nothing to take off, and the usual (or else the last used) to put on.
  const fresh = changeDraft(undefined, settings("d"), ALL, ["l"], NOW);
  assert.equal(fresh.supplyId, undefined);
  assert.equal(fresh.putOnSupplyId, "d");
  assert.equal(changeDraft({}, settings(), ALL, ["l"], NOW).putOnSupplyId, "l");
  // Nothing on: nothing to take off.
  assert.equal(changeDraft({ since: SINCE, nothingOn: true }, settings("d"), ALL, [], NOW).supplyId, undefined);
  // A product put away since isn't picked.
  assert.equal(changeDraft({ supplyId: "o", since: SINCE }, settings(), ALL, [], NOW).supplyId, undefined);
  assert.deepEqual(putOnEntry("d", NOW), { at: NOW, kind: "PAD_CHANGE", triggers: [], pain: false, blood: false, night: false, putOnSupplyId: "d", nothingOn: false });
});

test("an older change opens as a change into a fresh one of the same", () => {
  assert.equal(asChange(change(1, "n")).putOnSupplyId, "n");
  assert.equal(asChange(change(1, "n", "d")).putOnSupplyId, "d");
  assert.equal(asChange(change(1, "n", undefined, { nothingOn: true })).putOnSupplyId, undefined);
});

test("a change reads as what came off and what went on", () => {
  assert.equal(changeLine(change(1, "n", "d", { fullness: "FULL", wornMinutes: 420 }), ALL), "Night briefs → Day pants · Full · worn 7 h");
  assert.equal(changeTitle(change(1, "n", undefined, { nothingOn: true }), ALL), "Night briefs → nothing for now");
  assert.equal(changeTitle(change(1, undefined, "d"), ALL), "Put on Day pants");
  assert.equal(changeTitle(change(1, "n"), ALL), "Night briefs → Night briefs");
  assert.equal(changeTitle(change(1), ALL), "Change");
  assert.equal(changeTitle(change(1, "gone", "d"), ALL), "A product → Day pants");
  assert.deepEqual(changeDetail(change(1, "n", "d", { fullness: "SOAKED", leakedThrough: true, wornMinutes: 430 })), ["Soaked", "worn 7 h 10 min", "leaked through"]);
  assert.deepEqual(changeDetail(change(1, "n", "d", { fullness: "LEAKED", leakedThrough: true })), ["Leaked through"]);
});

test("times read plainly", () => {
  assert.equal(wornFor(430), "7 h 10 min");
  assert.equal(wornFor(45), "45 min");
  assert.equal(wornFor(360), "6 h");
  assert.equal(rough(372), 360);
  assert.equal(rough(100), 105);
  assert.equal(hoursShort(375), "6 h");
  assert.equal(hoursShort(395), "7 h");
  assert.equal(hoursShort(40), "40 min");
  assert.equal(kindWord("PULL_UP"), "Pull-up");
  assert.equal(kindWord("BRIEF"), "Brief");
});

test("what's on: since when, for how long, and the usual", () => {
  const w: ContinenceWearing = { supplyId: "n", name: "Night briefs", since: SINCE, typicalMinutes: 372 };
  const clock = (ms: number) => new Date(ms).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }).toLowerCase();
  assert.equal(sinceLine(w, clock, NOW), "Since 11:20 pm · 7 h 10 min");
  assert.equal(usualLine(w), "You usually change these after about 6 h");
  assert.equal(usualLine({ ...w, typicalMinutes: undefined }), null);
  // Due once the time's passed, and only with something on.
  assert.equal(changeDue({ ...w, changeDueAt: NOW - 1 }, NOW), true);
  assert.equal(changeDue({ ...w, changeDueAt: NOW + 60_000 }, NOW), false);
  assert.equal(changeDue({ since: SINCE, nothingOn: true, changeDueAt: NOW - 1 }, NOW), false);
  assert.equal(changeDue(undefined, NOW), false);
});

test("a product's pattern in a line", () => {
  const p: ProductStats = { supplyId: "n", name: "Night briefs", changes: 12, avgWornMinutes: 425, typicalFullness: "FULL", leakedThroughPct: 8, nightShare: 1, leakedThrough: 1 };
  assert.equal(productLine(p), "Night briefs: usually 7 h, often full, leaked through 1 in 12, mostly at night");
  assert.equal(productLine({ ...p, name: "Day pants", avgWornMinutes: 300, typicalFullness: "WET", leakedThrough: 0, nightShare: 0 }), "Day pants: usually 5 h, often wet, never leaked through");
  assert.equal(productLine({ supplyId: "l", name: "Liners", changes: 1, leakedThroughPct: 0, nightShare: 0, leakedThrough: 0 }), "Liners: 1 change");
});

// ---- Accidents under what was on

const leak = (at: number, extra: Partial<ContinenceEntry> = {}): ContinenceEntry => ({ at, kind: "LEAK", triggers: [], pain: false, blood: false, night: false, ...extra });
const ON: ContinenceWearing = {
  supplyId: "n", name: "Night briefs", kind: "BRIEF", since: SINCE, minutes: 430,
  wear: { supplyId: "n", name: "Night briefs", current: true, start: SINCE, fullness: "WET", accidents: [leak(SINCE + 3_600_000, { id: "a1", fullness: "WET" })] },
};
const at12 = (h: number, m = 0) => new Date(`2026-11-13T${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`).getTime();
const time = (ms: number) => { const d = new Date(ms); return `${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`; };

test("an accident logged now goes with what's on, starting at how full it already is", () => {
  const e = newEntry("LEAK", ON, settings("d"), NOW);
  assert.equal(e.supplyId, "n");
  assert.equal(e.fullness, "WET");
  assert.ok(inWhatsOn(e, ON));
  // Not this: a different product, or an entry already saved, reads as before.
  assert.ok(!inWhatsOn({ ...e, supplyId: "d" }, ON));
  assert.ok(!inWhatsOn({ ...e, id: "x" }, ON));
  // A toilet trip carries no product; with nothing on, an accident carries none either.
  assert.equal(newEntry("URINATION", ON, settings("d"), NOW).supplyId, undefined);
  assert.equal(newEntry("LEAK", { since: SINCE, nothingOn: true }, settings("d"), NOW).supplyId, undefined);
  // Nothing known: the usual.
  assert.equal(newEntry("BEDWETTING", undefined, settings("d"), NOW).supplyId, "d");
  assert.deepEqual(Object.keys(newEntry("LEAK", undefined, settings(), NOW)).sort(), ["at", "blood", "kind", "night", "pain", "triggers"]);
});

test("a change starts from how what's coming off is, and Save and change from the accident", () => {
  const draft = changeDraft({ ...ON, wear: { ...ON.wear!, leakedThrough: true } }, settings(), ALL, [], NOW);
  assert.equal(draft.fullness, "WET");
  assert.equal(draft.leakedThrough, true);
  const after = changeAfter(leak(NOW, { supplyId: "n", fullness: "SOAKED" }), ON, settings(), ALL, [], NOW);
  assert.equal(after.supplyId, "n");
  assert.equal(after.putOnSupplyId, "n");
  assert.equal(after.fullness, "SOAKED");
  // Nothing known about how it is: nothing preset.
  assert.ok(!("fullness" in changeDraft({ supplyId: "n", since: SINCE }, settings(), ALL, [], NOW)));
});

test("a put-on alone isn't a change", () => {
  assert.equal(changeCount([change(NOW, undefined, "n"), change(NOW, "n", "d"), change(NOW, "d", undefined, { nothingOn: true }), leak(NOW)]), 2);
});

test("a worn product reads as how it came off, how long, and what came next", () => {
  const off: ContinenceWear = { supplyId: "n", name: "Night briefs", start: at12(1), end: at12(7), minutes: 432, fullness: "FULL", off: change(at12(7), "n", "d"), nextName: "Day pants" };
  assert.equal(wearLine(off, time), "Full · worn 7 h 12 min · then Day pants");
  assert.equal(wearLine({ ...off, off: change(at12(7), "n", undefined, { nothingOn: true }), fullness: "LEAKED", leakedThrough: true }, time), "Leaked through · worn 7 h 12 min · then nothing on");
  assert.equal(wearLine({ ...off, off: undefined, end: undefined, fullness: undefined }, time), "on at 1:00");
  assert.equal(wearState(ON.wear), "Wet · 1 accident");
  assert.equal(wearState({ supplyId: "n", current: true, accidents: [] }), "Dry so far");
  assert.equal(putOnAlone({ ...off, on: change(at12(1), undefined, "n") })?.at, at12(1));
  assert.equal(putOnAlone({ ...off, on: change(at12(1), "d", "n") }), undefined);
});

test("what's on now is left out of the day's list while Wearing now shows it", () => {
  const diary: ContinenceDiary = { date: "2026-11-13", items: [
    { at: NOW, wear: { supplyId: "n", current: true } },
    { at: at12(5), entry: { at: at12(5), kind: "URINATION", triggers: [], pain: false, blood: false, night: false } },
  ] };
  assert.equal(diaryRows(diary, true).length, 1);
  assert.equal(diaryRows(diary, false).length, 2);
});
