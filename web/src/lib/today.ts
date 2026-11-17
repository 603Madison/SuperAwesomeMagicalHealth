import type {
  AlertLevel, Appointment, BladderTraining, DaySummary, DueDose, FoodEntry, HealthAlert, Insight, Meal, Module, StageSpan, SupplyStatus,
} from "./types";

/*
 * Today's choices that don't need React (npm test covers them): what's live right now and in what
 * order, the day as one timeline, the line under the scores, the health alert that leads, the few
 * insights below, the next bladder-training trip, and the meal it is. Type imports only, so node
 * can run it as it is.
 *
 * Right now and Your day are built here from what the web already loads (the day's summary, the
 * food diary, the bladder diary), until the server's GET /api/feed sends them ready-made.
 */

/** A dose this close to its time counts as due now. */
export const DUE_SOON_MS = 60 * 60_000;

/** Something timed this close counts as now: it goes first. */
const SOON_MS = 2 * 60 * 60_000;

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

/** How the rows write times, durations and numbers (the page's own ways, so they match the rest of the app). */
export interface Say {
  time: (ms: number) => string;
  hm: (minutes: number) => string;
  num: (n: number) => string;
}

// ---- Right now: only what's live, each with one thing to do ----

/** What a Right now row is about, and so what its button does. */
export type NowKind = "wearing" | "dose" | "food" | "appointment" | "routine" | "energy" | "trip" | "restock" | "voice";

/** One row of Right now: a title, one line, and the one button's word. */
export interface NowRow {
  key: string;
  kind: NowKind;
  title: string;
  detail?: string;
  /** How far along (food against its target), 0 to 1. */
  progress?: number;
  /** The button's word ("Change", "Take", "Add"). */
  action?: string;
  /** When it's due, for the order. */
  at?: number;
  /** Overdue: the row says so quietly. */
  late?: boolean;
  /** "Take": the dose to log. */
  dose?: DueDose;
  /** "Restock": the supply. */
  supply?: SupplyStatus;
  /** "Add": the meal to add to. */
  meal?: Meal;
}

/** What Right now is made from. */
export interface NowInput {
  day: DaySummary;
  on: Module[];
  now: number;
  /** The person's clock, 0–23, for the meal it is. */
  hour: number;
  /** When the day starts and ends, in the person's zone. */
  dayFrom: number;
  dayTo: number;
  /** Food's amounts can show (never in eating disorder recovery). */
  foodNumbers: boolean;
  /** How what's on is, already said: "dry", "wet · 1 accident". */
  wearingState?: string | null;
  /** Bladder training's next trip, when it's on. */
  trip?: { at: number; every: number } | null;
  /** The meal Mealie plans for tonight, when it's connected: "chili". */
  plan?: string | null;
  /** The meals with food in them, for "Breakfast, lunch logged" without amounts. */
  meals?: Meal[];
}

/**
 * Right now's rows, most pressing first ([orderNow]): what's on, doses due (or the next one today),
 * today's appointments still to come, the next bladder-training trip, routines and energy, food so
 * far, and supplies running low. Nothing live, no rows.
 */
export function nowRows(input: NowInput, say: Say): NowRow[] {
  const { day, on, now } = input;
  const m = day.modules;
  const rows: NowRow[] = [];
  const is = (id: Module) => on.includes(id);

  const w = m.continence?.wearing;
  if (is("CONTINENCE") && w?.supplyId && !w.nothingOn) {
    const name = w.name ?? "something";
    const due = w.changeDueAt != null && w.changeDueAt <= now;
    rows.push({
      key: "wearing", kind: "wearing", action: "Change", at: due ? w.changeDueAt : undefined, late: due,
      title: due ? `Time to change: ${name}` : `Wearing ${name}`,
      detail: [w.since != null && `On since ${say.time(w.since)}`, input.wearingState].filter(Boolean).join(" · ") || undefined,
    });
  }

  if (is("MEDICATIONS")) {
    const due = m.meds?.due ?? [];
    const taken = due.filter((d) => d.log?.status === "TAKEN").length;
    const soon = dosesDue(due, now);
    for (const d of soon.length > 0 ? soon : dosesLater(due, now).slice(0, 1)) {
      const overdue = d.scheduledFor < now - 15 * 60_000;
      rows.push({
        key: `dose-${d.medicationId}-${d.scheduledFor}`, kind: "dose", action: "Take", at: d.scheduledFor, late: overdue, dose: d,
        title: d.dose ? `${d.name}, ${d.dose}` : d.name,
        detail: [`${overdue ? "Was due" : "Due"} at ${say.time(d.scheduledFor)}`, taken > 0 && `${taken} of ${due.length} taken today`].filter(Boolean).join(" · "),
      });
    }
  }

  if (is("APPOINTMENTS")) {
    for (const a of appointmentsToday(m.appointments ?? [], input.dayFrom, input.dayTo, now)) {
      const questions = a.questions.length;
      rows.push({
        key: `appointment-${a.id ?? a.at}`, kind: "appointment", action: "Details", at: a.at, title: a.title,
        detail: [say.time(a.at), a.with, questions > 0 && `${questions} ${questions === 1 ? "question" : "questions"} ready`].filter(Boolean).join(" · "),
      });
    }
  }

  if (input.trip) {
    rows.push({
      key: "trip", kind: "trip", action: "Log trip", at: input.trip.at, late: input.trip.at < now - 15 * 60_000, title: "Toilet trip",
      detail: `${input.trip.at <= now ? "Now" : `Around ${say.time(input.trip.at)}`} · every ${say.hm(input.trip.every)}`,
    });
  }

  if (is("NEURO")) {
    for (const name of m.neuro?.routinesDue ?? []) rows.push({ key: `routine-${name}`, kind: "routine", action: "Start", title: name, detail: "A routine, still to do today" });
    const left = m.neuro?.energyLeft;
    if (left != null) {
      rows.push({
        key: "energy", kind: "energy", action: "Update", title: `${say.num(left)} ${left === 1 ? "spoon" : "spoons"} left`,
        detail: (m.neuro?.overloadsToday ?? 0) > 0 ? "A hard moment today. Go gently" : "Your energy today",
      });
    }
  }

  if (is("NUTRITION")) {
    const meal = mealAt(input.hour).meal;
    const plan = input.plan ? `tonight's plan: ${input.plan}` : null;
    if (input.foodNumbers) {
      const eaten = day.nutrition;
      const target = day.nutritionTarget;
      rows.push({
        key: "food", kind: "food", action: "Add", meal,
        title: target ? `${say.num(eaten.calories)} of ${say.num(target.calories)} kcal` : `${say.num(eaten.calories)} kcal so far`,
        progress: target && target.calories > 0 ? Math.min(1, eaten.calories / target.calories) : undefined,
        detail: [`${say.num(eaten.protein)} g protein`, plan].filter(Boolean).join(" · "),
      });
    } else {
      rows.push({
        key: "food", kind: "food", action: "Add", meal, title: mealsLogged(input.meals ?? []) ?? "Nothing logged yet",
        detail: plan ? `${plan.charAt(0).toUpperCase()}${plan.slice(1)}` : undefined,
      });
    }
  }

  if (is("CONTINENCE") || is("CYCLE")) {
    for (const s of m.lowSupplies ?? []) {
      rows.push({
        key: `restock-${s.supply.id ?? s.supply.name}`, kind: "restock", action: s.supply.reusable ? undefined : "Restock", supply: s,
        title: `${s.supply.name} running low`,
        detail: [`${say.num(s.supply.onHand)} left`, s.daysLeft != null && `about ${s.daysLeft} ${s.daysLeft === 1 ? "day" : "days"}`].filter(Boolean).join(" · "),
      });
    }
  }
  return orderNow(rows, now);
}

/**
 * Right now's order: what's overdue or due within two hours, by time; then what's on now; then what's
 * later today, by time; then routines and energy; then food so far; then supplies to restock.
 */
export function orderNow(rows: NowRow[], now: number): NowRow[] {
  const rank = (r: NowRow): number => {
    if (r.at != null && (r.late || r.at <= now + SOON_MS)) return 0;
    if (r.kind === "wearing") return 1;
    if (r.at != null) return 2;
    if (r.kind === "routine" || r.kind === "energy" || r.kind === "voice") return 3;
    if (r.kind === "food") return 4;
    return 5;
  };
  return [...rows].sort((a, b) => rank(a) - rank(b) || (a.at ?? 0) - (b.at ?? 0));
}

// ---- Your day: one timeline, newest first ----

/** What a row of Your day is about: its dot's colour, and where a tap goes. */
export type DayKind = "now" | "sleep" | "food" | "drink" | "activity" | "dose" | "bladder" | "mind" | "symptom" | "vitals" | "appointment";

/** One row of Your day. */
export interface DayRow {
  key: string;
  kind: DayKind;
  /** When; null for the "Now" row at the top. */
  at: number | null;
  title: string;
  detail?: string;
  /** A night's stages, drawn as a thin bar. */
  stages?: StageSpan[];
  /** Where a tap opens it. */
  to?: string;
  /** A workout entered by hand: a tap opens it to change. */
  workoutId?: string;
}

/** What the day's timeline is made from: the day's summary, and what the page loads besides. */
export interface DayInput {
  day: DaySummary;
  isToday: boolean;
  now: number;
  /** When the day starts, in the person's zone (a meal logged without a time sits at its usual hour). */
  dayFrom: number;
  foodNumbers: boolean;
  /** The food diary's entries for the day. */
  food?: FoodEntry[];
  /** Rows the page made itself (the bladder diary, moods, symptoms), each already said. */
  extra?: DayRow[];
}

/** How many times someone woke in the night: the awake spans between sleep, not the ones at the edges. */
export function wakings(stages: StageSpan[]): number {
  return stages.slice(1, -1).filter((s) => s.stage === "AWAKE").length;
}

const times = (n: number) => (n === 1 ? "once" : n === 2 ? "twice" : `${n} times`);
const firstFew = (names: string[], n = 3) => {
  const unique = [...new Set(names)];
  return unique.length > n ? `${unique.slice(0, n).join(", ")} and ${unique.length - n} more` : unique.join(", ");
};

/**
 * Your day, newest first: for today a "Now" row on top (the latest heart rate and the steps so
 * far), then the night, naps, workouts, each meal as one row, drinks, doses taken or skipped, blood
 * pressure, the appointments that have been, and the page's own rows ([extra]).
 */
export function dayRows(input: DayInput, say: Say): DayRow[] {
  const { day, now } = input;
  const rows: DayRow[] = [];

  const night = day.sleepSummary;
  if (night) {
    const wakes = night.staged ? wakings(night.stages) : 0;
    rows.push({
      key: "sleep", kind: "sleep", at: night.end, to: "/body",
      title: `Woke up · slept ${say.hm(night.asleepMinutes)}`,
      detail: night.staged ? [`${say.hm(night.deepMinutes)} deep`, `${say.hm(night.remMinutes)} REM`, wakes > 0 && `awake ${times(wakes)}`].filter(Boolean).join(" · ") : undefined,
      stages: night.staged && night.stages.length > 0 ? night.stages : undefined,
    });
  }
  for (const nap of day.naps) rows.push({ key: `nap-${nap.start}`, kind: "sleep", at: nap.start, title: `Nap · ${say.hm(nap.asleepMinutes)}`, to: "/body" });
  for (const w of day.workouts) {
    rows.push({
      key: `workout-${w.id ?? w.start}`, kind: "activity", at: w.start, workoutId: w.manual ? w.id : undefined, to: "/body?span=week#workouts",
      title: `${w.name ?? workoutName(w.type)} · ${say.hm((w.end - w.start) / 60_000)}`,
      detail: [w.steps && `${say.num(w.steps)} steps`, w.avgHeartRate && `heart rate ${say.num(w.avgHeartRate)} on average`, input.foodNumbers && w.activeCalories && `${say.num(w.activeCalories)} kcal`]
        .filter(Boolean).join(" · ") || undefined,
    });
  }
  for (const meal of MEALS) {
    const entries = (input.food ?? []).filter((e) => e.meal === meal.id);
    if (entries.length === 0) continue;
    const logged = entries.map((e) => e.loggedAt).filter((t): t is number => t != null);
    const kcal = entries.reduce((s, e) => s + e.nutrients.calories, 0);
    rows.push({
      key: `food-${meal.id}`, kind: "food", at: logged.length ? Math.min(...logged) : input.dayFrom + meal.hour * 3_600_000,
      to: `/food?date=${day.date}#${meal.id.toLowerCase()}`, title: input.foodNumbers ? `${meal.name} · ${say.num(kcal)} kcal` : meal.name,
      detail: firstFew(entries.map((e) => e.name)),
    });
  }
  for (const d of day.drinks?.drinks ?? []) {
    if (d.kind === "WATER") continue;
    rows.push({ key: `drink-${d.id ?? d.at}`, kind: "drink", at: d.at, title: d.name ?? drinkName(d.kind), to: `/food?date=${day.date}#drinks` });
  }
  const meds = day.modules.meds;
  for (const d of meds?.due ?? []) {
    if (!d.log) continue;
    const late = d.log.status === "TAKEN" && d.log.at - d.scheduledFor > 30 * 60_000;
    rows.push({
      key: `dose-${d.medicationId}-${d.scheduledFor}`, kind: "dose", at: d.log.at, to: "/meds", title: d.name,
      detail: d.log.status === "SKIPPED" ? "Skipped" : late ? `Taken, due at ${say.time(d.scheduledFor)}` : "Taken, on time",
    });
  }
  for (const log of meds?.extra ?? []) {
    const name = meds?.asNeeded.find((x) => x.id === log.medicationId)?.name ?? "A dose";
    rows.push({ key: `extra-${log.id ?? log.at}`, kind: "dose", at: log.at, to: "/meds", title: name, detail: log.status === "SKIPPED" ? "Skipped" : "Taken" });
  }
  const bp = day.modules.vitals?.bloodPressure;
  if (bp) rows.push({ key: `bp-${bp.at}`, kind: "vitals", at: bp.at, to: "/vitals", title: `Blood pressure ${bp.systolic}/${bp.diastolic}`, detail: bp.pulse ? `Pulse ${bp.pulse}` : undefined });
  for (const a of day.modules.appointments ?? []) {
    if (a.at < input.dayFrom || a.at > now || a.at >= input.dayFrom + 86_400_000 + 3_600_000) continue;
    rows.push({ key: `appointment-${a.id ?? a.at}`, kind: "appointment", at: a.at, to: "/appointments", title: a.title, detail: a.with });
  }
  rows.push(...(input.extra ?? []));
  // Something logged ahead (a meal for tonight) isn't in the timeline until it's happened.
  const sorted = rows.filter((r) => r.at != null && (!input.isToday || r.at <= now + 60_000)).sort((a, b) => b.at! - a.at!);

  if (input.isToday) {
    const latest = day.heartRate.length > 0 ? day.heartRate[day.heartRate.length - 1] : null;
    const fresh = latest && now - latest.t <= 90 * 60_000 ? latest : null;
    const parts = [fresh && `Heart rate ${say.num(fresh.v)}`, day.steps > 0 && `${say.num(day.steps)} steps so far`].filter(Boolean) as string[];
    if (parts.length > 0) sorted.unshift({ key: "now", kind: "now", at: null, title: parts.join(" · "), to: "/body" });
  }
  return sorted;
}

const WORKOUT_NAMES: Record<string, string> = {
  WALK: "Walk", RUN: "Run", CYCLE: "Ride", SWIM: "Swim", STRENGTH: "Strength", HIIT: "HIIT", YOGA: "Yoga", PILATES: "Pilates", HIKE: "Hike",
  ROW: "Row", ELLIPTICAL: "Elliptical", DANCE: "Dance", SPORT: "Sport", CLIMB: "Climb", SKI: "Ski",
};
const workoutName = (type: string) => WORKOUT_NAMES[type] ?? "Workout";

const DRINK_NAMES: Record<string, string> = {
  COFFEE: "Coffee", ESPRESSO: "Espresso", TEA: "Tea", GREEN_TEA: "Green tea", ENERGY_DRINK: "Energy drink", SODA: "Soda", DECAF: "Decaf",
  BEER: "Beer", WINE: "Wine", SPIRITS: "Spirits", COCKTAIL: "Cocktail",
};
const drinkName = (kind: string) => DRINK_NAMES[kind] ?? "A drink";

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

/** The alert Today leads with: how soon to act, its title and its first sentence. */
export interface LeadAlert {
  level: AlertLevel;
  title: string;
  body: string;
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
