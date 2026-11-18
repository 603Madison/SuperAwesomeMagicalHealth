// The medication history's calendar (src/lib/medsHistory.ts): a month's weeks, what each day shows,
// and the month in a line (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { dayMark, dayText, monthGrid, monthLabel, monthOf, monthRange, monthSummary, shiftMonth } from "../src/lib/medsHistory.ts";
import type { MedsHistoryDay } from "../src/lib/types.ts";

const day = (date: string, change: Partial<MedsHistoryDay> = {}): MedsHistoryDay =>
  ({ date, scheduled: 2, taken: 2, skipped: 0, missed: 0, open: 0, extra: 0, ...change });
const today = "2026-11-18";

test("months: their days, Monday first, and their neighbours", () => {
  assert.equal(monthOf(today), "2026-11");
  assert.deepEqual(monthRange("2026-11"), { from: "2026-11-01", to: "2026-11-30" });
  assert.deepEqual(monthRange("2028-02"), { from: "2028-02-01", to: "2028-02-29" });
  assert.equal(shiftMonth("2026-01", -1), "2025-12");
  assert.equal(shiftMonth("2026-12", 1), "2027-01");
  assert.equal(monthLabel("2026-11", "en-US"), "November 2026");
  // 1 November 2026 is a Sunday: six blanks first.
  const weeks = monthGrid("2026-11");
  assert.deepEqual(weeks[0], [null, null, null, null, null, null, "2026-11-01"]);
  assert.equal(weeks[1][0], "2026-11-02");
  assert.ok(weeks.every((w) => w.length === 7));
  assert.equal(weeks.flat().filter(Boolean).length, 30);
  assert.equal(weeks.at(-1)!.filter(Boolean).at(-1), "2026-11-30");
});

test("each day says what happened, with missed first", () => {
  assert.equal(dayMark(day("2026-11-10"), today), "taken");
  assert.equal(dayMark(day("2026-11-11", { taken: 1, missed: 1 }), today), "missed");
  assert.equal(dayMark(day("2026-11-12", { taken: 0, skipped: 1, missed: 1 }), today), "missed");
  assert.equal(dayMark(day("2026-11-13", { taken: 1, skipped: 1 }), today), "skipped");
  assert.equal(dayMark(day(today, { taken: 1, open: 1 }), today), "open");
  assert.equal(dayMark(day("2026-11-14", { scheduled: 0, taken: 0, extra: 1 }), today), "extra");
  assert.equal(dayMark(day("2026-11-15", { scheduled: 0, taken: 0 }), today), "none");
  assert.equal(dayMark(day("2026-11-19", { taken: 0 }), today), "future");
  assert.equal(dayMark(undefined, today), "none");
});

test("each day in words", () => {
  assert.equal(dayText(day("2026-11-10"), today), "2 of 2 taken");
  assert.equal(dayText(day("2026-11-12", { taken: 0, skipped: 1, missed: 1, extra: 1 }), today), "0 of 2 taken · 1 skipped · 1 missed · 1 as needed");
  assert.equal(dayText(day(today, { taken: 1, open: 1 }), today), "1 of 2 taken · 1 still to take");
  assert.equal(dayText(day("2026-11-14", { scheduled: 0, taken: 0, extra: 2 }), today), "2 as needed");
  assert.equal(dayText(day("2026-11-15", { scheduled: 0, taken: 0 }), today), "Nothing scheduled");
  assert.equal(dayText(day("2026-11-20", { taken: 0 }), today), "2 scheduled");
});

test("the month in a line: only doses whose time has come", () => {
  const days = [
    day("2026-11-16"), day("2026-11-17", { taken: 1, missed: 1 }), day(today, { taken: 1, open: 1 }),
    day("2026-11-19", { taken: 0 }), day("2026-11-13", { taken: 1, skipped: 1 }),
  ];
  assert.equal(monthSummary(days, today), "5 of 7 taken · 1 skipped · 1 missed");
  assert.equal(monthSummary([day("2026-11-16")], today), "2 of 2 taken");
  assert.equal(monthSummary([day("2026-11-19", { taken: 0 })], today), null);
  assert.equal(monthSummary([], today), null);
});
