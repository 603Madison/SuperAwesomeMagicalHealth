// Today's choices (src/lib/today.ts): Right now's rows and order, Your day's timeline, the line
// under the scores, the alert that leads, For you, the next bladder-training trip, the meal it
// is, and days in the person's time zone (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  appointmentsToday, dateIn, dayRows, dosesDue, dosesLater, forYou, hourIn, leadAlert, mealAt, mealsLogged, nextTrip, nowRows, orderNow, scoreSentence,
  startOfDayIn, todayIn, trainingMinutes, wakings, zonedTime, type NowInput, type NowRow, type Say,
} from "../src/lib/today.ts";
import type { Appointment, BladderTraining, DaySummary, DueDose, FoodEntry, Insight, Module, StageSpan, SupplyStatus } from "../src/lib/types.ts";

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

test("today's appointments: on the day and not over yet", () => {
  const a = (title: string, time: string, durationMinutes?: number): Appointment => ({ title, at: at(time), durationMinutes, questions: [], remindBefore: [] });
  const from = at("00:00");
  const to = from + 86_400_000;
  const list = [a("Later", "15:30"), a("Over", "09:00", 30), a("Still on", "10:40", 60), a("Tomorrow", "00:00")];
  list[3].at = to + 9 * 3_600_000;
  assert.deepEqual(appointmentsToday(list, from, to, NOW).map((x) => x.title), ["Still on", "Later"]);
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

// ---- Right now ----

const say: Say = {
  time: (ms) => { const d = new Date(ms); return `${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`; },
  hm: (m) => `${Math.floor(m / 60)}h ${Math.round(m % 60)}m`,
  num: (n) => String(Math.round(n)),
};

function day(change: Partial<DaySummary> = {}, modules: DaySummary["modules"] = {}): DaySummary {
  return {
    date: DATE, steps: 0, distanceM: 0, activeCalories: 0, naps: [], workouts: [], nutrition: { calories: 0, protein: 0, carbs: 0, fat: 0 }, waterMl: 0,
    insights: [], hourlySteps: [], heartRate: [], hasData: true, modules, combined: [],
    goals: { steps: 8000, activeCalories: 400, sleepMinutes: 480, waterMl: 2000, workoutsPerWeek: 3, weeklyChangeKg: 0 } as DaySummary["goals"],
    ...change,
  };
}

const input = (d: DaySummary, on: Module[], change: Partial<NowInput> = {}): NowInput => ({
  day: d, on, now: NOW, hour: 11, dayFrom: at("00:00"), dayTo: at("00:00") + 86_400_000, foodNumbers: true, ...change,
});

const wearing = { supplyId: "s1", name: "Night briefs", since: at("06:40"), changeDueAt: at("14:40") };
const lowPads = { supply: { name: "Night pads", onHand: 3, reusable: false }, daysLeft: 2, low: true } as SupplyStatus;

test("Right now: only what's live, and nothing from a feature that's off", () => {
  const d = day({}, { meds: { date: DATE, due: [dose("Estradiol", "08:00")], asNeeded: [], extra: [], lowStock: [] }, continence: { wearing } as never });
  assert.deepEqual(nowRows(input(d, []), say), []);
  const rows = nowRows(input(d, ["CONTINENCE"], { wearingState: "dry so far" }), say);
  assert.deepEqual(rows.map((r) => [r.kind, r.title, r.detail, r.action]), [["wearing", "Wearing Night briefs", "On since 6:40 · dry so far", "Change"]]);
  // Nothing on: no row.
  assert.deepEqual(nowRows(input(day({}, { continence: { wearing: { nothingOn: true, since: at("06:40") } } as never }), ["CONTINENCE"]), say), []);
});

test("Right now: what's due first by time, then what's on, later today, routines, food, restocks", () => {
  const d = day({ nutrition: { calories: 1240, protein: 62, carbs: 0, fat: 0 }, nutritionTarget: { calories: 1900, protein: 90 } as DaySummary["nutritionTarget"] }, {
    meds: { date: DATE, due: [dose("Estradiol", "08:00"), dose("Soon", "12:10"), dose("Later", "20:00"), dose("Vitamin D", "07:00", "TAKEN")], asNeeded: [], extra: [], lowStock: [] },
    continence: { wearing } as never,
    neuro: { overloadsToday: 0, routinesDue: ["Evening routine"], focusMinutes: 0, energyLeft: 6 },
    lowSupplies: [lowPads],
  });
  const rows = nowRows(input(d, ["MEDICATIONS", "CONTINENCE", "NEURO", "NUTRITION"], { plan: "chili" }), say);
  assert.deepEqual(rows.map((r) => r.key), ["dose-Estradiol-" + at("08:00"), "dose-Soon-" + at("12:10"), "wearing", "routine-Evening routine", "energy", "food", "restock-Night pads"]);
  const overdue = rows[0];
  assert.equal(overdue.late, true);
  assert.equal(overdue.detail, "Was due at 8:00 · 1 of 4 taken today");
  assert.equal(rows[1].late, false);
  const food = rows.find((r) => r.kind === "food")!;
  assert.equal(food.title, "1240 of 1900 kcal");
  assert.equal(food.detail, "62 g protein · tonight's plan: chili");
  assert.ok(Math.abs(food.progress! - 1240 / 1900) < 1e-9);
  assert.equal(food.meal, "LUNCH");
  assert.equal(rows[rows.length - 1].action, "Restock");
});

test("Right now: with nothing due, the next dose today; none once they're all done", () => {
  const meds = (due: DueDose[]) => day({}, { meds: { date: DATE, due, asNeeded: [], extra: [], lowStock: [] } });
  const later = nowRows(input(meds([dose("Evening", "20:00"), dose("Night", "22:00"), dose("Morning", "08:00", "TAKEN")]), ["MEDICATIONS"]), say);
  assert.deepEqual(later.map((r) => [r.title, r.detail, r.late]), [["Evening", "Due at 20:00 · 1 of 3 taken today", false]]);
  assert.deepEqual(nowRows(input(meds([dose("Morning", "08:00", "TAKEN")]), ["MEDICATIONS"]), say), []);
});

test("Right now: food without amounts in recovery, and the change once it's due", () => {
  const d = day({ nutrition: { calories: 900, protein: 40, carbs: 0, fat: 0 } }, { continence: { wearing: { ...wearing, changeDueAt: at("10:40") } } as never });
  const rows = nowRows(input(d, ["NUTRITION", "CONTINENCE"], { foodNumbers: false, meals: ["LUNCH", "BREAKFAST"], plan: "chili" }), say);
  assert.deepEqual(rows.map((r) => [r.kind, r.title, r.detail ?? null, r.progress ?? null]), [
    ["wearing", "Time to change: Night briefs", "On since 6:40", null],
    ["food", "Breakfast, lunch logged", "Tonight's plan: chili", null],
  ]);
  assert.equal(rows[0].late, true);
});

test("Right now's order on its own: overdue and soon by time, then on now, then later", () => {
  const rows: NowRow[] = [
    { key: "food", kind: "food", title: "" },
    { key: "later", kind: "appointment", title: "", at: at("16:00") },
    { key: "wear", kind: "wearing", title: "" },
    { key: "soon", kind: "trip", title: "", at: at("12:30") },
    { key: "late", kind: "dose", title: "", at: at("09:00"), late: true },
  ];
  assert.deepEqual(orderNow(rows, NOW).map((r) => r.key), ["late", "soon", "wear", "later", "food"]);
});

// ---- Your day ----

const stage = (s: StageSpan["stage"], from: string, to: string): StageSpan => ({ stage: s, start: at(from), end: at(to) });

test("a night's wakings: the awake spans between sleep, not at its edges", () => {
  assert.equal(wakings([stage("AWAKE", "00:00", "00:10"), stage("LIGHT", "00:10", "02:00"), stage("AWAKE", "02:00", "02:05"), stage("DEEP", "02:05", "03:00"), stage("AWAKE", "03:00", "03:05"), stage("REM", "03:05", "06:00"), stage("AWAKE", "06:00", "06:10")]), 2);
  assert.equal(wakings([]), 0);
});

test("Your day: newest first, a Now row on top, meals as one row each, what's ahead left out", () => {
  const food = (meal: FoodEntry["meal"], name: string, kcal: number, time?: string): FoodEntry => ({
    date: DATE, meal, name, nutrients: { calories: kcal, protein: 0, carbs: 0, fat: 0 }, servings: 1, serving: "1", loggedAt: time ? at(time) : undefined,
  });
  const d = day({
    steps: 6240, heartRate: [{ t: at("11:00"), v: 72 }],
    sleepSummary: {
      start: at("00:00") - 3_600_000, end: at("06:50"), asleepMinutes: 462, inBedMinutes: 480, efficiency: 96, deepMinutes: 80, remMinutes: 112, lightMinutes: 250, awakeMinutes: 20, staged: true,
      stages: [stage("LIGHT", "00:00", "02:00"), stage("AWAKE", "02:00", "02:05"), stage("DEEP", "02:05", "06:50")],
    },
    workouts: [{ type: "WALK", start: at("10:05"), end: at("10:43"), steps: 4120, avgHeartRate: 108, activeCalories: 150, id: "w1", manual: true }],
  }, { meds: { date: DATE, due: [{ ...dose("Morning", "08:00"), log: { medicationId: "Morning", at: at("08:02"), status: "TAKEN" } }, dose("Evening", "20:00")], asNeeded: [], extra: [], lowStock: [] } });
  const entries = [food("BREAKFAST", "Oatmeal", 300, "07:30"), food("BREAKFAST", "Coffee", 120, "07:32"), food("DINNER", "Chili", 700, "19:00"), food("LUNCH", "Salad", 610)];
  const extra = [{ key: "b1", kind: "bladder" as const, at: at("09:10"), title: "Bathroom" }];
  const rows = dayRows({ day: d, isToday: true, now: NOW, dayFrom: at("00:00"), foodNumbers: true, food: entries, extra }, say);
  assert.deepEqual(rows.map((r) => r.title), [
    "Heart rate 72 · 6240 steps so far", "Walk · 0h 38m", "Bathroom", "Morning", "Breakfast · 420 kcal", "Woke up · slept 7h 42m",
  ]);
  assert.equal(rows[0].at, null);
  assert.equal(rows[1].detail, "4120 steps · heart rate 108 on average · 150 kcal");
  assert.equal(rows[1].workoutId, "w1");
  assert.equal(rows[3].detail, "Taken, on time");
  assert.equal(rows[4].detail, "Oatmeal, Coffee");
  assert.equal(rows[5].detail, "1h 20m deep · 1h 52m REM · awake once");
  assert.equal(rows[5].stages?.length, 3);
  // Another day: no Now row, and a meal without a time sits at its usual hour.
  const past = dayRows({ day: d, isToday: false, now: NOW + 86_400_000, dayFrom: at("00:00"), foodNumbers: false, food: entries }, say);
  assert.equal(past[0].title, "Dinner");
  assert.ok(past.some((r) => r.title === "Lunch" && r.at === at("12:30")));
  assert.ok(!past.some((r) => r.kind === "now"));
  // Workouts say no calories in recovery.
  assert.equal(past.find((r) => r.kind === "activity")!.detail, "4120 steps · heart rate 108 on average");
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
