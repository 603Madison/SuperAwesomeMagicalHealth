// Today's choices (src/lib/today.ts): Up next's doses and order, the next bladder-training trip,
// the quick row and the meals logged (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { QUICK_ROW, appointmentsToday, dosesDue, dosesLater, mealsLogged, nextTrip, orderUpNext, quickRow, trainingMinutes, type UpNext } from "../src/lib/today.ts";
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
