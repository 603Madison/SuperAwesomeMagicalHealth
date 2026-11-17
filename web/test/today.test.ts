// Today's choices (src/lib/today.ts): reading the server's day feed (Right now's actions and order,
// the alert that leads, Your day's dots, the Now row), the bladder-training trip, the line under
// the scores, For you, the meal it is, and days in the person's time zone (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  NOW_ACTIONS, actionIds, alertLevel, dateIn, dosesDue, dosesLater, dotOf, forYou, hourIn, knownAction, leadAlert, leadFromFeed, mealAt, mealsLogged, nextTrip,
  nowLine, rightNow, scoreSentence, startOfDayIn, todayIn, trainingMinutes, tripItem, zonedTime, type Say,
} from "../src/lib/today.ts";
import type { BladderTraining, DueDose, Insight, NowItem } from "../src/lib/types.ts";

const DATE = "2026-11-13";
const at = (hhmm: string) => new Date(`${DATE}T${hhmm}`).getTime();
const NOW = at("11:20");

const dose = (name: string, time: string, log?: "TAKEN" | "SKIPPED"): DueDose => ({
  medicationId: name, name, form: "TABLET", scheduledFor: at(time),
  log: log ? { medicationId: name, at: at(time), status: log } : undefined,
});

test("doses due: overdue and within the hour, earliest first; taken, skipped and later ones aren't", () => {
  const due = [dose("Progesterone", "22:00"), dose("Spironolactone", "09:00"), dose("Estradiol", "08:00"), dose("Vitamin D", "07:00", "TAKEN"), dose("Iron", "07:30", "SKIPPED"), dose("Soon", "12:10")];
  assert.deepEqual(dosesDue(due, NOW).map((d) => d.name), ["Estradiol", "Spironolactone", "Soon"]);
  assert.deepEqual(dosesLater(due, NOW).map((d) => d.name), ["Progesterone"]);
});


const training: BladderTraining = { enabled: true, intervalMinutes: 90, from: "08:00", to: "21:00", stepMinutes: 15, stepEveryDays: 7, goalMinutes: 180, startedOn: DATE };

test("bladder training: the gap grows a step every few days, up to the goal", () => {
  assert.equal(trainingMinutes(training, DATE), 90);
  assert.equal(trainingMinutes(training, "2026-11-20"), 105);
  assert.equal(trainingMinutes(training, "2027-06-01"), 180);
  assert.equal(trainingMinutes({ ...training, startedOn: undefined }, "2027-06-01"), 90);
});

test("the next trip: the last one plus the gap, not before the day starts, none after it ends or when off", () => {
  assert.equal(nextTrip(training, DATE, [at("10:05")], NOW), at("11:35"));
  // Overdue stays overdue: it's due now.
  assert.equal(nextTrip(training, DATE, [at("09:00")], NOW), at("10:30"));
  // A trip in the night doesn't count before the day's training starts.
  assert.equal(nextTrip(training, DATE, [at("03:00")], at("06:00")), at("08:00"));
  // No trip yet today: now, or when training starts.
  assert.equal(nextTrip(training, DATE, [], NOW), NOW);
  assert.equal(nextTrip(training, DATE, [], at("07:00")), at("08:00"));
  // Trips logged after now (a mistake, or another device's clock) aren't the last one.
  assert.equal(nextTrip(training, DATE, [at("10:05"), at("12:00")], NOW), at("11:35"));
  assert.equal(nextTrip(training, DATE, [at("20:00")], at("20:30")), null);
  assert.equal(nextTrip(training, DATE, [], at("21:30")), null);
  assert.equal(nextTrip({ ...training, enabled: false }, DATE, [], NOW), null);
});


// ---- Right now and Your day, from the server's feed ----

const say: Say = {
  time: (ms) => { const d = new Date(ms); return `${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`; },
  hm: (m) => `${Math.floor(m / 60)}h ${Math.round(m % 60)}m`,
  num: (n) => String(Math.round(n)),
};

const item = (id: string, kind: NowItem["kind"], priority: number, change: Partial<NowItem> = {}): NowItem => ({ id, kind, title: id, priority, ...change });

test("an action this version knows gets a button; a newer server's doesn't", () => {
  assert.equal(knownAction(item("a", "WEARING", 45, { action: { label: "Change", action: "continence.change", ids: { supplyId: "s1" } } }))?.label, "Change");
  assert.equal(knownAction(item("b", "OTHER", 40, { action: { label: "Do", action: "something.new" as never } })), null);
  assert.equal(knownAction(item("c", "FOOD_TODAY", 30)), null);
  assert.deepEqual(NOW_ACTIONS, ["continence.change", "dose.take", "food.add", "energy.add", "voice.start", "appointment.open", "alert.open"]);
});

test("an action's ids, read: times as numbers, meals checked, an appointment's or alert's id by either name", () => {
  assert.deepEqual(actionIds({ ids: { medicationId: "m1", scheduledFor: "1763380800000" } }), {
    medicationId: "m1", scheduledFor: 1763380800000, supplyId: undefined, meal: undefined, date: undefined, lessonId: undefined, appointmentId: undefined, alertId: undefined,
  });
  assert.equal(actionIds({ ids: { meal: "LUNCH", date: "2026-11-13" } }).meal, "LUNCH");
  assert.equal(actionIds({ ids: { meal: "ELEVENSES" } }).meal, undefined);
  assert.equal(actionIds({ ids: { scheduledFor: "soon" } }).scheduledFor, undefined);
  assert.equal(actionIds({ ids: { appointmentId: "a1" } }).appointmentId, "a1");
  assert.equal(actionIds({ ids: { id: "a2" } }).appointmentId, "a2");
  assert.equal(actionIds({ ids: { alertId: "x" } }).alertId, "x");
  assert.equal(actionIds({}).supplyId, undefined);
});

test("the alert Today leads with comes from Right now: the most pressing, and it isn't repeated below", () => {
  const now = [
    item("wearing", "WEARING", 45, { at: at("06:40") }),
    item("alert:2", "HEALTH_ALERT", 90, { title: "Oxygen dips", detail: "14 low readings" }),
    item("dose:x", "DOSE_OVERDUE", 85, { at: at("08:00") }),
    item("alert:1", "HEALTH_ALERT", 100, { title: "Fast, irregular rhythm", detail: "Call now if…" }),
  ];
  const lead = leadFromFeed(now)!;
  assert.deepEqual(lead, { level: "URGENT", title: "Fast, irregular rhythm", body: "Call now if…", id: "alert:1" });
  assert.deepEqual(rightNow(now, [], lead.id).map((i) => i.id), ["alert:2", "dose:x", "wearing"]);
  assert.equal(leadFromFeed([item("food", "FOOD_TODAY", 30)]), null);
  assert.deepEqual([100, 95, 90, 70, 60].map(alertLevel), ["URGENT", "DOCTOR", "DOCTOR", "WATCH", "WATCH"]);
});

test("Right now: most pressing first, the soonest at the same priority, with the trip the page adds", () => {
  const now = [item("food", "FOOD_TODAY", 30), item("dose:b", "DOSE_DUE", 80, { at: at("12:10") }), item("dose:a", "DOSE_DUE", 80, { at: at("11:50") }), item("energy", "ENERGY_TODAY", 20)];
  const due = tripItem({ at: at("11:00"), every: 105 }, NOW, say);
  assert.deepEqual([due.title, due.detail, due.priority], ["Toilet trip", "Now · every 1h 45m", 75]);
  const later = tripItem({ at: at("13:30"), every: 90 }, NOW, say);
  assert.deepEqual([later.detail, later.priority], ["Around 13:30 · every 1h 30m", 35]);
  assert.deepEqual(rightNow(now, [due]).map((i) => i.id), ["dose:a", "dose:b", "trip", "food", "energy"]);
  assert.deepEqual(rightNow(now, [later]).map((i) => i.id), ["dose:a", "dose:b", "trip", "food", "energy"]);
});

test("Your day's dots: a colour family for each kind, a plain one for a kind this version doesn't know", () => {
  assert.equal(dotOf("SLEEP"), "sleep");
  assert.equal(dotOf("MEAL"), "food");
  assert.equal(dotOf("WORKOUT"), "activity");
  assert.equal(dotOf("BATHROOM"), "care");
  assert.equal(dotOf("WEIGHT"), "body");
  assert.equal(dotOf("HEALTH_ALERT"), "alert");
  assert.equal(dotOf("OTHER"), "other");
  assert.equal(dotOf("SOMETHING_NEW"), "other");
});

test("the Now row on top of today: a fresh heart rate and the steps so far", () => {
  assert.equal(nowLine({ heartRate: [{ t: at("11:00"), v: 72 }], steps: 6240 }, NOW, say), "Heart rate 72 · 6240 steps so far");
  // An hour and a half old is too old to say "now".
  assert.equal(nowLine({ heartRate: [{ t: at("09:30"), v: 72 }], steps: 6240 }, NOW, say), "6240 steps so far");
  assert.equal(nowLine({ heartRate: [], steps: 0 }, NOW, say), null);
});

// ---- The line under the scores, the alert that leads, For you ----

const score = (n: number) => ({ score: n, label: "", contributors: [] });

test("the line under the scores: plain, and never alarming", () => {
  assert.equal(scoreSentence({}), null);
  assert.equal(scoreSentence({ readiness: score(90), sleep: score(87) }), "Well rested and well recovered. A good day to do more.");
  assert.equal(scoreSentence({ readiness: score(76), sleep: score(70) }), "A fair night. A steady day: keep an even pace.");
  assert.equal(scoreSentence({ readiness: score(55), sleep: score(50) }), "A short night, so your body could use an easier day. Go gently.");
  assert.equal(scoreSentence({ readiness: score(90), sleep: score(87) }, true), "Well rested, but your body may be working on something. Go easy today.");
  assert.equal(scoreSentence({ sleep: score(82) }), "Well rested. Make the most of it.");
  assert.equal(scoreSentence({ activity: score(40) }), "There's still time to move a little today.");
  for (const r of [10, 40, 69]) assert.doesNotMatch(scoreSentence({ readiness: score(r) })!, /warning|danger|risk|bad/i);
});

const insight = (title: string, change: Partial<Insight> = {}): Insight => ({ tone: "info", title, body: `${title}.`, icon: "moon", ...change });

test("the alert Today leads with: could need care now first, then how serious the list says", () => {
  assert.equal(leadAlert([insight("Steady sleep")]), null);
  const insights = [insight("Oxygen dips", { tone: "warn", link: "ALERTS" }), insight("Fast, irregular rhythm", { tone: "alert", link: "ALERTS" }), insight("Steady sleep")];
  assert.deepEqual(leadAlert(insights), { level: "URGENT", title: "Fast, irregular rhythm", body: "Fast, irregular rhythm." });
  const two = [insight("Fighting something off", { tone: "warn", link: "ALERTS" }), insight("Oxygen dips", { tone: "warn", link: "ALERTS" })];
  const list = [{ title: "Fighting something off", level: "WATCH" as const }, { title: "Oxygen dips", level: "DOCTOR" as const }];
  assert.equal(leadAlert(two, list)!.title, "Oxygen dips");
  assert.equal(leadAlert(two, list)!.level, "DOCTOR");
  // Without the list, a warning is taken as worth a doctor's look.
  assert.equal(leadAlert(two)!.level, "DOCTOR");
});

test("For you: at most two, never an alert, never for a feature that's off", () => {
  const list = [
    insight("Rhythm", { tone: "alert", link: "ALERTS" }), insight("Dips", { tone: "warn", link: "ALERTS" }), insight("Mood", { module: "MIND" }),
    insight("Steady sleep"), insight("Protein", { module: "NUTRITION" }), insight("Steps"),
  ];
  assert.deepEqual(forYou(list, ["NUTRITION"]).map((i) => i.title), ["Steady sleep", "Protein"]);
  assert.deepEqual(forYou(list, ["NUTRITION", "MIND"], 1).map((i) => i.title), ["Mood"]);
});

test("the meal it is, by the person's clock", () => {
  assert.deepEqual([6, 10, 11, 14, 15, 16, 17, 20, 21, 2].map((h) => mealAt(h).name), [
    "Breakfast", "Breakfast", "Lunch", "Lunch", "Afternoon snack", "Afternoon snack", "Dinner", "Dinner", "A snack", "A snack",
  ]);
  assert.equal(mealAt(15).meal, "SNACK");
  // The meals logged, in the day's order (Care's Food card, in recovery).
  assert.equal(mealsLogged(["LUNCH", "BREAKFAST", "LUNCH"]), "Breakfast, lunch logged");
  assert.equal(mealsLogged(["SNACK"]), "Snacks logged");
  assert.equal(mealsLogged([]), null);
  // The hour where the person is, not the browser's.
  assert.equal(hourIn(Date.UTC(2026, 10, 13, 20, 30), "America/New_York"), 15);
  assert.equal(hourIn(Date.UTC(2026, 10, 13, 20, 30), "Pacific/Auckland"), 9);
});

// ---- Days in the person's time zone (the server's days follow the profile's, not the browser's) ----

const utc = (y: number, m: number, d: number, h = 0, min = 0) => Date.UTC(y, m - 1, d, h, min);

test("today in a zone: the person's date, wherever the browser is", () => {
  // 12:00 UTC on 13 November is already the 14th in Auckland (UTC+13 in summer), and still the morning of the 13th in Los Angeles.
  const noonUtc = utc(2026, 11, 13, 12);
  assert.equal(todayIn("Pacific/Auckland", noonUtc), "2026-11-14");
  assert.equal(todayIn("America/Los_Angeles", noonUtc), "2026-11-13");
  assert.equal(todayIn("Pacific/Kiritimati", utc(2026, 11, 13, 10)), "2026-11-14");
  assert.equal(todayIn("Pacific/Pago_Pago", utc(2026, 11, 14, 10)), "2026-11-13");
  // Midnight is where the zone's clock says it is.
  assert.equal(dateIn(utc(2026, 11, 13, 23, 59), "Europe/London"), "2026-11-13");
  assert.equal(dateIn(utc(2026, 11, 14, 0, 0), "Europe/London"), "2026-11-14");
  assert.equal(dateIn(utc(2026, 11, 13, 10, 59), "Pacific/Auckland"), "2026-11-13");
  assert.equal(dateIn(utc(2026, 11, 13, 11, 0), "Pacific/Auckland"), "2026-11-14");
});

test("today without a zone, or with one the browser doesn't know: the browser's own", () => {
  const at = utc(2026, 11, 13, 23, 30);
  const d = new Date(at);
  const local = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  assert.equal(todayIn(undefined, at), local);
  assert.equal(todayIn("", at), local);
  assert.equal(todayIn(null, at), local);
  assert.equal(todayIn("Mars/Olympus_Mons", at), local);
  assert.match(todayIn("Europe/Paris"), /^\d{4}-\d{2}-\d{2}$/);
});

test("a clock time on a day in a zone, through summer time", () => {
  assert.equal(startOfDayIn("2026-11-14", "Pacific/Auckland"), utc(2026, 11, 13, 11));
  assert.equal(zonedTime("2026-11-14", "08:00", "America/New_York"), utc(2026, 11, 14, 13));
  // London's clocks went forward on 29 March 2026: midnight was still GMT, noon was BST.
  assert.equal(startOfDayIn("2026-03-29", "Europe/London"), utc(2026, 3, 29, 0));
  assert.equal(zonedTime("2026-03-29", "12:00", "Europe/London"), utc(2026, 3, 29, 11));
  // New York's went back on 1 November 2026: that day had 25 hours.
  assert.equal(startOfDayIn("2026-11-02", "America/New_York") - startOfDayIn("2026-11-01", "America/New_York"), 25 * 3_600_000);
  assert.equal(startOfDayIn("2026-11-02", "America/New_York"), utc(2026, 11, 2, 5));
  // Each day's start reads as that day, and the moment before as the day before.
  for (const zone of ["Pacific/Auckland", "Asia/Kolkata", "America/St_Johns", "Australia/Lord_Howe"]) {
    const start = startOfDayIn("2026-11-14", zone);
    assert.equal(dateIn(start, zone), "2026-11-14", zone);
    assert.equal(dateIn(start - 1, zone), "2026-11-13", zone);
  }
  assert.ok(Number.isNaN(zonedTime("14/11/2026", "08:00", "Europe/London")));
  assert.ok(Number.isNaN(zonedTime("2026-11-14", "eight", "Europe/London")));
});

test("the next trip follows the clock in the person's zone", () => {
  const zone = "Pacific/Auckland";
  const nz = (hhmm: string) => zonedTime("2026-11-14", hhmm, zone);
  // 07:00 there: training starts at 08:00 there.
  assert.equal(nextTrip(training, "2026-11-14", [], nz("07:00"), zone), nz("08:00"));
  assert.equal(nextTrip(training, "2026-11-14", [nz("10:05")], nz("11:20"), zone), nz("11:35"));
  assert.equal(nextTrip(training, "2026-11-14", [], nz("21:30"), zone), null);
});
