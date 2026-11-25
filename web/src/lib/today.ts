import type {
  AlertLevel, BladderTraining, DaySummary, DueDose, HealthAlert, Insight, Meal, Module, NowAction, NowActionKey, NowItem, TimelineKind,
} from "./types";

/*
 * Today's choices that don't need React (npm test covers them): reading the server's day feed
 * (GET /api/feed: Right now and Your day), the bladder-training trip the feed doesn't carry, the
 * line under the scores, the health alert that leads, the few insights below, and the meal it is.
 * Type imports only, so node can run it as it is.
 */

/** A dose this close to its time counts as due now. */
export const DUE_SOON_MS = 60 * 60_000;

/** The doses not taken or skipped yet that are due within the hour or overdue, earliest first. */
export function dosesDue(due: DueDose[], now: number): DueDose[] {
  return due.filter((d) => !d.log && d.scheduledFor <= now + DUE_SOON_MS).sort((a, b) => a.scheduledFor - b.scheduledFor);
}

/** The doses not taken or skipped yet that come later today (not due yet). */
export function dosesLater(due: DueDose[], now: number): DueDose[] {
  return due.filter((d) => !d.log && d.scheduledFor > now + DUE_SOON_MS).sort((a, b) => a.scheduledFor - b.scheduledFor);
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

const MEALS: { id: Meal; word: string; name: string; hour: number }[] = [
  { id: "BREAKFAST", word: "breakfast", name: "Breakfast", hour: 8 }, { id: "LUNCH", word: "lunch", name: "Lunch", hour: 12.5 },
  { id: "DINNER", word: "dinner", name: "Dinner", hour: 18.5 }, { id: "SNACK", word: "snacks", name: "Snacks", hour: 15.5 },
];

/** "Breakfast, lunch logged": the meals with something in them, in the day's order; null with none. */
export function mealsLogged(meals: Meal[]): string | null {
  const words = MEALS.filter((m) => meals.includes(m.id)).map((m) => m.word);
  if (words.length === 0) return null;
  const text = words.join(", ");
  return `${text.charAt(0).toUpperCase()}${text.slice(1)} logged`;
}

/** The meal it is at [hour] (0–23, the person's clock), and what to call it: "Afternoon snack". */
export function mealAt(hour: number): { meal: Meal; name: string } {
  if (hour >= 5 && hour < 11) return { meal: "BREAKFAST", name: "Breakfast" };
  if (hour >= 11 && hour < 15) return { meal: "LUNCH", name: "Lunch" };
  if (hour >= 15 && hour < 17) return { meal: "SNACK", name: "Afternoon snack" };
  if (hour >= 17 && hour < 21) return { meal: "DINNER", name: "Dinner" };
  return { meal: "SNACK", name: "A snack" };
}


/** How the page writes times, durations and numbers (its own ways, so they match the rest of the app). */
export interface Say {
  time: (ms: number) => string;
  hm: (minutes: number) => string;
  num: (n: number) => string;
}

// ---- Right now, from the feed ----

/** The actions this version knows how to do; a newer server's others show no button. */
export const NOW_ACTIONS: NowActionKey[] = [
  "continence.change", "dose.take", "food.add", "energy.add", "voice.start", "appointment.open", "alert.open", "meditation.open",
];

/** An item's action, if this version knows it; null otherwise (no button). */
export function knownAction(item: Pick<NowItem, "action">): NowAction | null {
  const a = item.action;
  return a && (NOW_ACTIONS as string[]).includes(a.action) ? a : null;
}

/** What an action carries, read: the ids by name (an appointment's or an alert's also as plain "id"), times as numbers. */
export interface ActionIds {
  medicationId?: string;
  scheduledFor?: number;
  supplyId?: string;
  meal?: Meal;
  date?: string;
  lessonId?: string;
  appointmentId?: string;
  alertId?: string;
  meditationId?: string;
}

const MEAL_IDS: Meal[] = ["BREAKFAST", "LUNCH", "DINNER", "SNACK"];

export function actionIds(a: Pick<NowAction, "ids">): ActionIds {
  const ids = a.ids ?? {};
  const at = Number(ids.scheduledFor);
  return {
    medicationId: ids.medicationId || undefined,
    scheduledFor: ids.scheduledFor && Number.isFinite(at) ? at : undefined,
    supplyId: ids.supplyId || undefined,
    meal: (MEAL_IDS as string[]).includes(ids.meal) ? (ids.meal as Meal) : undefined,
    date: ids.date || undefined,
    lessonId: ids.lessonId || undefined,
    appointmentId: ids.appointmentId || ids.id || undefined,
    alertId: ids.alertId || ids.id || undefined,
    meditationId: ids.meditationId || undefined,
  };
}

/** How soon a health alert in Right now asks to act, from the server's priority: 100 could need care now, 90 worth a doctor's look. */
export function alertLevel(priority: number): AlertLevel {
  return priority >= 100 ? "URGENT" : priority >= 90 ? "DOCTOR" : "WATCH";
}

/** The alert Today leads with: how soon to act, its title and a line. */
export interface LeadAlert {
  level: AlertLevel;
  title: string;
  body: string;
  /** The Right now item it came from, left out of the rows below it. */
  id?: string;
}

/** The health alert at the top of Today: the most pressing one in Right now (the server sends them most pressing first). */
export function leadFromFeed(now: NowItem[]): LeadAlert | null {
  const alerts = now.filter((i) => i.kind === "HEALTH_ALERT").sort((a, b) => b.priority - a.priority);
  const top = alerts[0];
  return top ? { level: alertLevel(top.priority), title: top.title, body: top.detail ?? "", id: top.id } : null;
}

/** The bladder-training trip the feed doesn't carry, as a Right now item: due now (or late) it presses; later today, gently. */
export function tripItem(trip: { at: number; every: number }, now: number, say: Say): NowItem {
  const due = trip.at <= now;
  return {
    id: "trip", kind: "OTHER", title: "Toilet trip", priority: due ? 75 : 35, at: trip.at, module: "CONTINENCE", page: "CONTINENCE",
    detail: `${due ? "Now" : `Around ${say.time(trip.at)}`} · every ${say.hm(trip.every)}`,
  };
}

/**
 * Right now's rows: the feed's, most pressing first (and at the same priority, the soonest), with
 * any the page adds ([extra]: the trip), less the alert that leads above them ([leadId]).
 */
export function rightNow(now: NowItem[], extra: NowItem[] = [], leadId?: string | null): NowItem[] {
  return [...now, ...extra]
    .filter((i) => i.id !== leadId)
    .sort((a, b) => b.priority - a.priority || (a.at ?? Number.MAX_SAFE_INTEGER) - (b.at ?? Number.MAX_SAFE_INTEGER));
}

// ---- Your day, from the feed ----

/** A timeline item's dot: the colour family it belongs to. A kind this version doesn't know gets a plain one. */
export type DayDot = "sleep" | "food" | "activity" | "care" | "body" | "alert" | "other";

const DOTS: Partial<Record<TimelineKind, DayDot>> = {
  SLEEP: "sleep", NAP: "sleep",
  MEAL: "food", DRINK: "food",
  WORKOUT: "activity", FOCUS: "activity", ROUTINE: "activity", HABIT: "activity", VOICE_PRACTICE: "activity",
  BLOOD_PRESSURE: "body", GLUCOSE: "body", WEIGHT: "body", MEASUREMENT: "body", PHOTO: "body",
  DOSE_TAKEN: "care", DOSE_SKIPPED: "care", DOSE_CHANGE: "care", BATHROOM: "care", LEAK: "care", PRODUCT_CHANGE: "care", PELVIC_FLOOR: "care",
  MOOD: "care", CHECK_IN: "care", CALM: "care", JOURNAL: "care", ENERGY: "care", OVERLOAD: "care", SYMPTOM: "care", APPOINTMENT: "care",
  TRANSITION_MOMENT: "care", CYCLE_DAY: "care",
  HEALTH_ALERT: "alert",
};

export const dotOf = (kind: string): DayDot => DOTS[kind as TimelineKind] ?? "other";

/** The "Now" row on top of today's timeline: the latest heart rate while it's fresh, and the steps so far; null with neither. */
export function nowLine(day: Pick<DaySummary, "heartRate" | "steps">, now: number, say: Say): string | null {
  const latest = day.heartRate.length > 0 ? day.heartRate[day.heartRate.length - 1] : null;
  const fresh = latest && now - latest.t <= 90 * 60_000 ? latest : null;
  const parts = [fresh && `Heart rate ${say.num(fresh.v)}`, day.steps > 0 && `${say.num(day.steps)} steps so far`].filter(Boolean) as string[];
  return parts.length > 0 ? parts.join(" · ") : null;
}

// ---- The line under the scores, the alert that leads, and For you ----

/**
 * One plain sentence under the scores, from readiness and the night (or the night alone, then
 * activity). [unwell]: an active alert says the body may be fighting something off. Never
 * alarming: a low score asks for an easier day, nothing more. Null without scores.
 */
export function scoreSentence(day: Pick<DaySummary, "readiness" | "sleep" | "activity">, unwell = false): string | null {
  const r = day.readiness?.score;
  const s = day.sleep?.score;
  const a = day.activity?.score;
  if (r == null && s == null && a == null) return null;
  const night = s == null ? null : s >= 80 ? "Well rested" : s >= 65 ? "A fair night" : "A short night";
  if (unwell) return `${night ? `${night}, but your` : "Your"} body may be working on something. Go easy today.`;
  if (r != null) {
    if (r >= 85) return night === "Well rested" ? "Well rested and well recovered. A good day to do more." : "Well recovered. A good day to do more.";
    if (r >= 70) return `${night ? `${night}. ` : ""}A steady day: keep an even pace.`;
    return night === "A short night" ? "A short night, so your body could use an easier day. Go gently." : "Your body could use an easier day. Go gently.";
  }
  if (night) return night === "Well rested" ? "Well rested. Make the most of it." : `${night}. Go easy on yourself today.`;
  return a! >= 85 ? "A good, active day so far." : "There's still time to move a little today.";
}

/**
 * The most serious health alert that's still active, for the card at the top of Today: the day's
 * insights carry the active ones (most serious first, as the server sorts them), and the alert list,
 * when it's loaded, says how serious each is. Null without one.
 */
export function leadAlert(insights: Insight[], alerts?: Pick<HealthAlert, "title" | "level" | "dismissedAt">[] | null): LeadAlert | null {
  const active = insights.filter((i) => i.link === "ALERTS");
  if (active.length === 0) return null;
  const rank: Record<AlertLevel, number> = { URGENT: 0, DOCTOR: 1, WATCH: 2 };
  const levelOf = (i: Insight): AlertLevel => {
    if (i.tone === "alert") return "URGENT";
    return alerts?.find((a) => a.title === i.title && a.dismissedAt == null)?.level ?? "DOCTOR";
  };
  const best = active.map((i) => ({ i, level: levelOf(i) })).sort((x, y) => rank[x.level] - rank[y.level])[0];
  return { level: best.level, title: best.i.title, body: best.i.body };
}

/** Whether an active alert says the body may be fighting something off (the server's thermometer). */
export const fightingSomething = (insights: Insight[]): boolean => insights.some((i) => i.link === "ALERTS" && i.icon === "thermometer");

/** For you: at most [max] insights, never a health alert (the card at the top has that), none for a feature that's off. */
export function forYou(insights: Insight[], on: Module[], max = 2): Insight[] {
  return insights.filter((i) => i.link !== "ALERTS" && i.tone !== "alert" && (!i.module || on.includes(i.module))).slice(0, max);
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

/** The hour (0–23) the clock in [zone] shows at [ms]. */
export function hourIn(ms: number, zone?: string | null): number {
  const clock = clockIn(zone);
  return clock ? reading(ms, clock)[3] : new Date(ms).getHours();
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

/** The day after [date] (YYYY-MM-DD). */
function nextDay(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
}

/**
 * A time axis's ticks: the moments from [from] to [to] when the clock in [zone] reads a whole
 * multiple of [every] hours (with 6: midnight, 6 AM, noon and 6 PM). They follow the clock, so
 * across a change to or from summer time two ticks can be 5 or 7 hours apart.
 */
export function hourTicks(from: number, to: number, every: number, zone?: string | null): number[] {
  if (!(to >= from) || !(every > 0)) return [];
  const out = new Set<number>();
  const last = dateIn(to, zone);
  // From the day before, in case [from] is just after a midnight the zone's clock skipped.
  let date = dateIn(from - 86_400_000, zone);
  for (let days = 0; date <= last && days < 400; days++, date = nextDay(date)) {
    for (let hour = 0; hour < 24; hour += every) {
      const at = zonedTime(date, `${pad2(hour)}:00`, zone);
      if (at >= from && at <= to) out.add(at);
    }
  }
  return [...out].sort((a, b) => a - b);
}

const timeFormats = new Map<string, Intl.DateTimeFormat>();

/**
 * [ms] as the clock in [zone] shows it ("11:08 PM", or "11 PM" with [hourOnly]), written the way
 * [locale] (the browser's, without one) writes times. In the browser's own zone without a zone,
 * or with one this browser doesn't know.
 */
export function clockTime(ms: number, zone?: string | null, hourOnly = false, locale?: string): string {
  const key = `${locale ?? ""}|${zone ?? ""}|${hourOnly}`;
  let format = timeFormats.get(key);
  if (!format) {
    const options: Intl.DateTimeFormatOptions = hourOnly ? { hour: "numeric" } : { hour: "numeric", minute: "2-digit" };
    try {
      format = new Intl.DateTimeFormat(locale, zone ? { ...options, timeZone: zone } : options);
    } catch {
      format = new Intl.DateTimeFormat(locale, options);
    }
    timeFormats.set(key, format);
  }
  return format.format(ms);
}
