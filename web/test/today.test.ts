// Today's choices (src/lib/today.ts): Up next's doses and order, the next bladder-training trip,
// the quick row, the meals logged, and days in the person's time zone (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  QUICK_ROW, appointmentsToday, dateIn, dosesDue, dosesLater, mealsLogged, nextTrip, orderUpNext, quickRow, startOfDayIn, todayIn, trainingMinutes, zonedTime,
  type UpNext,
} from "../src/lib/today.ts";
import type { Appointment, BladderTraining, DueDose, SupplyStatus } from "../src/lib/types.ts";

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

test("Up next: due now by time, then routines, then later today, then restocks", () => {
  const supply = { supply: { name: "Night pads" }, low: true } as SupplyStatus;
  const items: UpNext[] = [
    { kind: "restock", key: "r", supply },
    { kind: "appointment", key: "a", at: at("15:30"), appointment: { title: "Clinic", at: at("15:30"), questions: [], remindBefore: [] } },
    { kind: "routine", key: "m", name: "Morning routine" },
    { kind: "trip", key: "t", at: at("11:35"), every: 90 },
    { kind: "dose", key: "d2", at: at("09:00"), dose: dose("Spironolactone", "09:00"), overdue: true },
    { kind: "dose", key: "d1", at: at("08:00"), dose: dose("Estradiol", "08:00"), overdue: true },
  ];
  assert.deepEqual(orderUpNext(items, NOW).map((i) => i.key), ["d1", "d2", "t", "m", "a", "r"]);
  // A change that's come due goes by its time, with the rest of what's due now.
  const change: UpNext = { kind: "change", key: "c", at: at("08:30"), name: "Night briefs", since: at("02:30") };
  assert.deepEqual(orderUpNext([...items, change], NOW).map((i) => i.key), ["d1", "c", "d2", "t", "m", "a", "r"]);
});

test("the quick row: the first five until arranged, then the ones not under More, never more than five", () => {
  const ids = ["FOOD", "WATER", "MOOD", "PERIOD", "BLADDER", "DOSE", "WEIGHT"];
  const id = (s: string) => s;
  assert.equal(QUICK_ROW, 5);
  assert.deepEqual(quickRow(ids, id), { shown: ["FOOD", "WATER", "MOOD", "PERIOD", "BLADDER"], more: ["DOSE", "WEIGHT"] });
  assert.deepEqual(quickRow(ids, id, { order: [], hidden: ["WATER", "PERIOD"] }).shown, ["FOOD", "MOOD", "BLADDER", "DOSE", "WEIGHT"]);
  assert.deepEqual(quickRow(ids, id, { order: [], hidden: [] }).shown, ["FOOD", "WATER", "MOOD", "PERIOD", "BLADDER"]);
  assert.deepEqual(quickRow(ids, id, { order: [], hidden: [] }).more, ["DOSE", "WEIGHT"]);
  assert.deepEqual(quickRow(["FOOD", "WATER"], id), { shown: ["FOOD", "WATER"], more: [] });
});

test("meals logged, in the day's order", () => {
  assert.equal(mealsLogged(["LUNCH", "BREAKFAST", "LUNCH"]), "Breakfast, lunch logged");
  assert.equal(mealsLogged(["SNACK"]), "Snacks logged");
  assert.equal(mealsLogged([]), null);
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
