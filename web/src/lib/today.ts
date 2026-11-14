import type { Appointment, BladderTraining, DueDose, Meal, ScreenLayout, SupplyStatus } from "./types";

/*
 * Today's choices that don't need React (npm test covers them): what's up next and in what order,
 * the next bladder-training trip, which quick actions fit in the row, and the meals logged.
 * Type imports only, so node can run it as it is.
 */

/** How many quick actions fit in Today's row; the sixth place is More, which opens + Log. */
export const QUICK_ROW = 5;

/** A dose this close to its time counts as due now. */
export const DUE_SOON_MS = 60 * 60_000;

/** Something timed this close comes before a routine. */
const SOON_MS = 2 * 60 * 60_000;

/** One thing to do now: Up next's rows. */
export type UpNext =
  | { kind: "dose"; key: string; at: number; dose: DueDose; overdue: boolean }
  | { kind: "appointment"; key: string; at: number; appointment: Appointment }
  | { kind: "routine"; key: string; name: string }
  | { kind: "trip"; key: string; at: number; every: number }
  | { kind: "change"; key: string; at: number; name: string; since: number }
  | { kind: "restock"; key: string; supply: SupplyStatus };

/** The doses not taken or skipped yet that are due within the hour or overdue, earliest first. */
export function dosesDue(due: DueDose[], now: number): DueDose[] {
  return due.filter((d) => !d.log && d.scheduledFor <= now + DUE_SOON_MS).sort((a, b) => a.scheduledFor - b.scheduledFor);
}

/** The doses not taken or skipped yet that come later today (not due yet). */
export function dosesLater(due: DueDose[], now: number): DueDose[] {
  return due.filter((d) => !d.log && d.scheduledFor > now + DUE_SOON_MS).sort((a, b) => a.scheduledFor - b.scheduledFor);
}

/** Appointments between [from] and [to] (the day) that aren't over yet, earliest first. */
export function appointmentsToday(list: Appointment[], from: number, to: number, now: number): Appointment[] {
  return list.filter((a) => a.at >= from && a.at < to && a.at + (a.durationMinutes ?? 60) * 60_000 >= now).sort((a, b) => a.at - b.at);
}

/** The gap between trips on [date] (YYYY-MM-DD), in minutes: it grows by a step every few days, up to the goal. */
export function trainingMinutes(t: BladderTraining, date: string): number {
  if (!t.startedOn) return t.intervalMinutes;
  const days = Math.max(0, Math.floor((new Date(`${date}T12:00`).getTime() - new Date(`${t.startedOn}T12:00`).getTime()) / 86_400_000));
  return Math.min(t.goalMinutes, t.intervalMinutes + Math.floor(days / Math.max(1, t.stepEveryDays)) * t.stepMinutes);
}

/**
 * When the next bladder-training trip is due on [date]: the last trip ([trips], times) plus
 * today's gap, but not before the day's training starts; null when training is off or the day's
 * training is over. Its times are the clock in [zone] (the person's), or the browser's without one.
 */
export function nextTrip(t: BladderTraining, date: string, trips: number[], now: number, zone?: string | null): number | null {
  if (!t.enabled) return null;
  const from = zonedTime(date, t.from, zone);
  const to = zonedTime(date, t.to, zone);
  if (Number.isNaN(from) || Number.isNaN(to) || now > to) return null;
  const last = trips.filter((x) => x <= now).reduce((m, x) => Math.max(m, x), Number.NEGATIVE_INFINITY);
  const next = Math.max(from, last === Number.NEGATIVE_INFINITY ? now : last + trainingMinutes(t, date) * 60_000);
  return next > to ? null : next;
}

/**
 * Up next, most urgent first: what's overdue or due within two hours, by time; then routines;
 * then what's later today; then supplies to restock.
 */
export function orderUpNext(items: UpNext[], now: number): UpNext[] {
  const rank = (i: UpNext) => (i.kind === "restock" ? 3 : i.kind === "routine" ? 1 : i.at <= now + SOON_MS ? 0 : 2);
  const time = (i: UpNext) => ("at" in i ? i.at : 0);
  return [...items].sort((a, b) => rank(a) - rank(b) || time(a) - time(b));
}

/**
 * Today's quick row, from the offered actions in the person's order: until they arrange it, the
 * first [QUICK_ROW]; after, the ones they haven't put under More, still at most [QUICK_ROW]. The
 * rest are under More (+ Log has them all).
 */
export function quickRow<T>(ordered: T[], id: (t: T) => string, layout?: ScreenLayout): { shown: T[]; more: T[] } {
  const hidden = new Set(layout ? layout.hidden : ordered.slice(QUICK_ROW).map(id));
  const shown = ordered.filter((t) => !hidden.has(id(t))).slice(0, QUICK_ROW);
  const inRow = new Set(shown.map(id));
  return { shown, more: ordered.filter((t) => !inRow.has(id(t))) };
}

const MEALS: { id: Meal; word: string }[] = [
  { id: "BREAKFAST", word: "breakfast" }, { id: "LUNCH", word: "lunch" }, { id: "DINNER", word: "dinner" }, { id: "SNACK", word: "snacks" },
];

/** "Breakfast, lunch logged": the meals with something in them, in the day's order; null with none. */
export function mealsLogged(meals: Meal[]): string | null {
  const words = MEALS.filter((m) => meals.includes(m.id)).map((m) => m.word);
  if (words.length === 0) return null;
  const text = words.join(", ");
  return `${text.charAt(0).toUpperCase()}${text.slice(1)} logged`;
}

/*
 * Days in a time zone. The server's days follow the person's profile time zone, which may not be
 * the browser's: "today", the day a moment falls on and a clock time on a day are worked out in
 * that zone. Without a zone (or with one this browser doesn't know), in the browser's own.
 */

const clocks = new Map<string, Intl.DateTimeFormat | null>();

/** The clock in [zone]; null without a zone, or for one this browser doesn't know. */
function clockIn(zone?: string | null): Intl.DateTimeFormat | null {
  if (!zone) return null;
  if (!clocks.has(zone)) {
    try {
      clocks.set(zone, new Intl.DateTimeFormat("en-CA", {
        timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
      }));
    } catch {
      clocks.set(zone, null);
    }
  }
  return clocks.get(zone) ?? null;
}

const pad2 = (n: number) => String(n).padStart(2, "0");

/** What [clock] reads at [ms]: year, month (1–12), day, hour, minute, second. */
function reading(ms: number, clock: Intl.DateTimeFormat): number[] {
  const parts: Record<string, number> = {};
  for (const p of clock.formatToParts(ms)) if (p.type !== "literal") parts[p.type] = Number(p.value);
  return [parts.year, parts.month, parts.day, parts.hour % 24, parts.minute, parts.second];
}

/** The day (YYYY-MM-DD) [ms] falls on in [zone]. */
export function dateIn(ms: number, zone?: string | null): string {
  const clock = clockIn(zone);
  if (!clock || !Number.isFinite(ms)) {
    const d = new Date(ms);
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }
  const [y, m, d] = reading(ms, clock);
  return `${y}-${pad2(m)}-${pad2(d)}`;
}

/** Today's date in [zone], as YYYY-MM-DD. */
export function todayIn(zone?: string | null, now: number = Date.now()): string {
  return dateIn(now, zone);
}

/** How far [clock]'s zone is ahead of UTC at [ms], in ms. */
function aheadOfUtc(ms: number, clock: Intl.DateTimeFormat): number {
  const [y, m, d, h, min, s] = reading(ms, clock);
  return Date.UTC(y, m - 1, d, h, min, s) - Math.floor(ms / 1000) * 1000;
}

/** The moment the clock in [zone] reads [time] ("HH:mm") on [date] (YYYY-MM-DD); NaN for ones that can't be read. */
export function zonedTime(date: string, time: string, zone?: string | null): number {
  const clock = clockIn(zone);
  if (!clock) return new Date(`${date}T${time}`).getTime();
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const at = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(time);
  if (!day || !at) return Number.NaN;
  const asUtc = Date.UTC(Number(day[1]), Number(day[2]) - 1, Number(day[3]), Number(at[1]), Number(at[2]));
  if (Number.isNaN(asUtc)) return Number.NaN;
  // The zone's offset at about that time, then once more in case it changed in between (summer time).
  const guess = asUtc - aheadOfUtc(asUtc, clock);
  return asUtc - aheadOfUtc(guess, clock);
}

/** When [date] (YYYY-MM-DD) begins in [zone]. */
export const startOfDayIn = (date: string, zone?: string | null): number => zonedTime(date, "00:00", zone);
