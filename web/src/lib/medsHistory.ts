import type { MedsHistoryDay } from "./types";

/*
 * The medication history's calendar (GET /api/meds/history, the History card on Medications), the
 * parts that don't need React (npm test covers them): a month's weeks, what each day shows, and the
 * month in a line. Type imports only, so node can run it as it is.
 */

/** "2026-11" from "2026-11-18". */
export const monthOf = (date: string): string => date.slice(0, 7);

/** The month [by] months from [month]. */
export function shiftMonth(month: string, by: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

const daysIn = (month: string): number => {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
};

/** The month's first and last days, to ask the server for. */
export function monthRange(month: string): { from: string; to: string } {
  return { from: `${month}-01`, to: `${month}-${String(daysIn(month)).padStart(2, "0")}` };
}

/** "November 2026". */
export function monthLabel(month: string, locale?: string): string {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(locale, { month: "long", year: "numeric", timeZone: "UTC" });
}

/** The month's weeks, Monday first, as the medication schedules count them: each day's date, or null outside the month. */
export function monthGrid(month: string): (string | null)[][] {
  const [y, m] = month.split("-").map(Number);
  const lead = (new Date(Date.UTC(y, m - 1, 1)).getUTCDay() + 6) % 7;
  const cells: (string | null)[] = Array(lead).fill(null);
  for (let d = 1; d <= daysIn(month); d++) cells.push(`${month}-${String(d).padStart(2, "0")}`);
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks: (string | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));
  return weeks;
}

/**
 * What a day shows: all taken; any missed (it's over and one was never marked); some skipped
 * (none missed); today with some still to take; only doses taken as needed; nothing; or a day
 * still to come.
 */
export type DayMark = "taken" | "missed" | "skipped" | "open" | "extra" | "none" | "future";

export function dayMark(d: MedsHistoryDay | undefined, today: string): DayMark {
  if (!d) return "none";
  if (d.date > today) return "future";
  if (d.scheduled === 0) return d.extra > 0 ? "extra" : "none";
  if (d.missed > 0) return "missed";
  if (d.open > 0) return "open";
  return d.taken >= d.scheduled ? "taken" : "skipped";
}

/** A day in words, for its button and screen readers: "2 of 2 taken", "1 of 2 taken · 1 missed", "1 as needed". */
export function dayText(d: MedsHistoryDay | undefined, today: string): string {
  if (!d || (d.scheduled === 0 && d.extra === 0)) return "Nothing scheduled";
  if (d.date > today) return `${d.scheduled} scheduled`;
  const parts: string[] = [];
  if (d.scheduled > 0) parts.push(`${d.taken} of ${d.scheduled} taken`);
  if (d.skipped > 0) parts.push(`${d.skipped} skipped`);
  if (d.missed > 0) parts.push(`${d.missed} missed`);
  if (d.open > 0) parts.push(`${d.open} still to take`);
  if (d.extra > 0) parts.push(`${d.extra} as needed`);
  return parts.join(" · ");
}

/**
 * The month in a line, over the doses whose time has come (today's still to take aside): "26 of 28
 * taken · 1 skipped · 1 missed". Null with none.
 */
export function monthSummary(days: MedsHistoryDay[], today: string): string | null {
  const done = days.filter((d) => d.date <= today);
  const taken = done.reduce((n, d) => n + d.taken, 0);
  const skipped = done.reduce((n, d) => n + d.skipped, 0);
  const missed = done.reduce((n, d) => n + d.missed, 0);
  const total = taken + skipped + missed;
  if (total === 0) return null;
  return [`${taken} of ${total} taken`, skipped > 0 && `${skipped} skipped`, missed > 0 && `${missed} missed`].filter(Boolean).join(" · ");
}
