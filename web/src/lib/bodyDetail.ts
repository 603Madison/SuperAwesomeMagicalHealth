import type { BodySpan } from "./nav";

/*
 * A card's details, from Body (pages/BodyDetail.tsx, /body/hrv?span=week): which cards open which
 * page, the spans each offers, and the parts that don't need React (npm test covers them): the
 * average, lowest and highest (with when), the person's usual, each day's value from readings, the
 * readings hour by hour, and the first few of a long list. Type imports only, so node can run it
 * as it is.
 */

/** The page's sections in the default order. Their ids are saved (layoutKey("bodyDetail")): never rename one. */
export const DETAIL_SECTIONS = ["headline", "chart", "stats", "list"] as const;
export type DetailSection = (typeof DETAIL_SECTIONS)[number];

/** The details that have the last 24 hours as recorded (GET /api/recent): Body's cards at 24 hours. The rest are days only. */
export const DAY_DETAILS = ["sleep", "heart", "hrv", "spo2", "breathing", "temperature", "stress", "steps"] as const;

/** Cards at a week, a month or a year whose details are another's: time asleep and the nights are Sleep's. */
const SAME_AS: Record<string, string> = { asleep: "sleep", nights: "sleep" };

/** The detail a card on Body opens (its section's id, mostly). */
export const detailOf = (card: string): string => SAME_AS[card] ?? card;

const SPANS: BodySpan[] = ["day", "week", "month", "year"];

/** The spans a detail's switch offers: 24 hours only where it has readings, and never while viewing a share ([shared]). */
export function detailSpans(id: string, shared = false): BodySpan[] {
  const day = !shared && (DAY_DETAILS as readonly string[]).includes(id);
  return SPANS.filter((s) => s !== "day" || day);
}

/** The span an address asks for, if the detail offers it; otherwise 24 hours where it can, or a week. */
export function detailSpan(value: string | null | undefined, id: string, shared = false): BodySpan {
  const offered = detailSpans(id, shared);
  const asked = SPANS.find((s) => s === value) ?? "day";
  return offered.includes(asked) ? asked : offered[0];
}

/** A detail's address: /body/hrv, /body/hrv?span=week, /body/hrv?device=ring1 (one device's 24 hours). */
export function detailPath(id: string, span: BodySpan = "day", device?: string | null): string {
  const params = new URLSearchParams();
  if (span !== "day") params.set("span", span);
  if (device && span === "day") params.set("device", device);
  const query = params.toString();
  return `/body/${encodeURIComponent(id)}${query ? `?${query}` : ""}`;
}

// ---- The numbers ----

export interface Valued {
  value: number;
}

/** A span's numbers: the average and total, the lowest and highest (the latest of equals, for "when"), the latest, and how many. */
export interface SpanStats<T extends Valued> {
  average: number;
  total: number;
  lowest: T;
  highest: T;
  latest: T;
  count: number;
}

/** The numbers over [items] (oldest first); null with none. Values that aren't numbers are left out. */
export function statsOf<T extends Valued>(items: T[]): SpanStats<T> | null {
  const known = items.filter((i) => Number.isFinite(i.value));
  if (known.length === 0) return null;
  let lowest = known[0];
  let highest = known[0];
  let total = 0;
  for (const i of known) {
    total += i.value;
    if (i.value <= lowest.value) lowest = i;
    if (i.value >= highest.value) highest = i;
  }
  return { average: total / known.length, total, lowest, highest, latest: known[known.length - 1], count: known.length };
}

/** How many days make a usual: fewer, and there's no usual to speak of. */
export const USUAL_DAYS = 3;

/** The person's usual: the average of the [values] there are, from the days before. Null with fewer than [least]. */
export function usualOf(values: (number | null | undefined)[], least = USUAL_DAYS): number | null {
  const known = values.filter((v): v is number => v != null && Number.isFinite(v));
  return known.length < least ? null : known.reduce((a, b) => a + b, 0) / known.length;
}

/** How [value] stands against [usual]: the share off it, and the word. Within [near] (5%) it's the usual. Null without one. */
export function againstUsual(value: number, usual: number | null | undefined, near = 0.05): { share: number; word: "above" | "below" | "usual" } | null {
  if (usual == null || !(usual > 0) || !Number.isFinite(value)) return null;
  const share = (value - usual) / usual;
  return { share: Math.abs(share), word: Math.abs(share) < near ? "usual" : share > 0 ? "above" : "below" };
}

/** "12% above your usual", "About your usual"; null without a usual. */
export function againstText(value: number, usual: number | null | undefined): string | null {
  const a = againstUsual(value, usual);
  if (!a) return null;
  return a.word === "usual" ? "About your usual" : `${Math.round(a.share * 100)}% ${a.word} your usual`;
}

// ---- Days ----

/** One day's value ([extra]: blood pressure's bottom number). */
export interface DayValue extends Valued {
  date: string;
  extra?: number;
}

/** Each day's value, where there is one, oldest first. */
export function dailyValues<T extends { date: string }>(
  days: T[], value: (d: T) => number | null | undefined, extra?: (d: T) => number | null | undefined,
): DayValue[] {
  return [...days].sort((a, b) => a.date.localeCompare(b.date)).flatMap((d) => {
    const v = value(d);
    if (v == null || !Number.isFinite(v)) return [];
    const e = extra?.(d);
    return [e != null && Number.isFinite(e) ? { date: d.date, value: v, extra: e } : { date: d.date, value: v }];
  });
}

/** Days split into the last [count] (the span) and the ones before it (for the usual), each oldest first. */
export function splitSpan<T extends { date: string }>(days: T[], count: number): { span: T[]; before: T[] } {
  const sorted = [...days].sort((a, b) => a.date.localeCompare(b.date));
  const at = Math.max(0, sorted.length - count);
  return { span: sorted.slice(at), before: sorted.slice(0, at) };
}

/** The [count] dates ending on [last] (YYYY-MM-DD), oldest first. */
export function spanDates(last: string, count: number): string[] {
  const [y, m, d] = last.split("-").map(Number);
  return Array.from({ length: Math.max(0, count) }, (_, i) => new Date(Date.UTC(y, m - 1, d - (count - 1 - i))).toISOString().slice(0, 10));
}

export interface At extends Valued {
  at: number;
}

/** Readings as each day's average (or, for counts like steps, its total), oldest first. [dateOf] gives a moment's day where the person is. */
export function byDay(points: At[], dateOf: (ms: number) => string, how: "average" | "sum" = "average"): DayValue[] {
  const days = new Map<string, { sum: number; n: number }>();
  for (const p of points) {
    if (!Number.isFinite(p.value)) continue;
    const date = dateOf(p.at);
    const d = days.get(date) ?? { sum: 0, n: 0 };
    d.sum += p.value;
    d.n += 1;
    days.set(date, d);
  }
  return [...days.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([date, d]) => ({ date, value: how === "sum" ? d.sum : d.sum / d.n }));
}

// ---- Hours ----

/** An hour's readings: when the first came, their average (or total), lowest and highest, and how many. */
export interface HourRow extends At {
  low: number;
  high: number;
  count: number;
}

/** Readings hour by hour, newest first. [hourOf] names a moment's hour where the person is (its day and hour). */
export function byHour(points: At[], hourOf: (ms: number) => string, how: "average" | "sum" = "average"): HourRow[] {
  const hours = new Map<string, HourRow & { sum: number }>();
  for (const p of [...points].sort((a, b) => a.at - b.at)) {
    if (!Number.isFinite(p.value)) continue;
    const key = hourOf(p.at);
    const h = hours.get(key);
    if (!h) hours.set(key, { at: p.at, value: p.value, sum: p.value, low: p.value, high: p.value, count: 1 });
    else {
      h.sum += p.value;
      h.count += 1;
      h.low = Math.min(h.low, p.value);
      h.high = Math.max(h.high, p.value);
    }
  }
  return [...hours.values()]
    .map(({ sum, ...h }) => ({ ...h, value: how === "sum" ? sum : sum / h.count }))
    .sort((a, b) => b.at - a.at);
}

// ---- Lists ----

/** How many rows a long list shows before "Show all". */
export const FIRST = 7;

/** The first [n] of [list], or all of it. */
export const firstFew = <T>(list: T[], all: boolean, n = FIRST): T[] => (all ? list : list.slice(0, n));

/** Newest first, from oldest first. */
export const newestFirst = <T>(list: T[]): T[] => [...list].reverse();

/** How many points to ask /api/series for: an hour each (a week, a month), at most 5,000 (a year). */
export const seriesPoints = (days: number): number => Math.min(5000, Math.max(10, days * 24));
