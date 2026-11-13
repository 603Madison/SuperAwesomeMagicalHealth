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
 * training is over.
 */
export function nextTrip(t: BladderTraining, date: string, trips: number[], now: number): number | null {
  if (!t.enabled) return null;
  const from = new Date(`${date}T${t.from}`).getTime();
  const to = new Date(`${date}T${t.to}`).getTime();
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
