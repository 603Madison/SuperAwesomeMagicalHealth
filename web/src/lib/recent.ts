import type { Metric, RecentDevice, RecentPoint, RecentReadings, RecentSeries, SleepSession, SleepStage, Units } from "./types";

/*
 * The last 24 hours (GET /api/recent, pages/Recent.tsx), the parts that don't need React (npm test
 * covers them): which readings to show, the numbers above each chart, each night's minutes from its
 * stages, the sleep shaded behind the charts, temperatures in the person's units, and what each
 * chart says to screen readers. Type imports only, so node can run it as it is.
 */

export const RECENT_HOURS = 24;

/** The page's sections in the default order. Their ids are saved (layoutKey("recent")): never rename one. */
export const RECENT_SECTIONS = ["sleep", "heart", "spo2", "hrv", "stress", "breathing", "temperature", "steps"] as const;
export type RecentSection = (typeof RECENT_SECTIONS)[number];

/** The kinds of reading the page shows, in the server's order. */
export const RECENT_METRICS = [
  "HEART_RATE", "SPO2", "HRV", "STRESS", "RESPIRATORY_RATE", "SKIN_TEMPERATURE", "TEMPERATURE_DEVIATION", "STEPS",
] as const satisfies readonly Metric[];
export type RecentMetric = (typeof RECENT_METRICS)[number];

/**
 * Each kind of reading by its metric. One without a metric, or with one this version doesn't
 * know (a newer server's), is left out, and so is one with nothing in it.
 */
export function readingsByMetric(series: RecentSeries[]): Partial<Record<RecentMetric, RecentSeries>> {
  const known: readonly string[] = RECENT_METRICS;
  const out: Partial<Record<RecentMetric, RecentSeries>> = {};
  for (const s of series) {
    const metric = s.metric as string | undefined;
    if (!metric || !known.includes(metric) || !s.points?.length) continue;
    out[metric as RecentMetric] ??= s;
  }
  return out;
}

/** Whether nothing at all was recorded in the window: no night and no reading. */
export function nothingRecorded(r: RecentReadings): boolean {
  return r.sleep.length === 0 && Object.keys(readingsByMetric(r.series)).length === 0;
}

/**
 * Whether readings came often enough to draw as a line (mostly within [minutes] of each other, like
 * a ring's heart rate) rather than as points (a few blood oxygen or stress readings).
 */
export function drawnAsLine(points: RecentPoint[], minutes = 15): boolean {
  if (points.length < 12) return false;
  const gaps = points.slice(1).map((p, i) => p.at - points[i].at).sort((a, b) => a - b);
  return gaps[Math.floor(gaps.length / 2)] <= minutes * 60_000;
}

/** One number above a chart; [at] for the latest reading's time. */
export interface RecentStat {
  label: string;
  value: number;
  at?: number;
}

/** The numbers above a kind of reading's chart, in the order they show; just the latest when there's one reading. */
export function readingStats(metric: RecentMetric, s: RecentSeries): RecentStat[] {
  const latest: RecentStat[] = s.latest ? [{ label: "Latest", value: s.latest.value, at: s.latest.at }] : [];
  const stat = (label: string, value?: number): RecentStat[] => (value == null ? [] : [{ label, value }]);
  if (metric !== "STEPS" && s.points.length === 1 && latest.length) return latest;
  switch (metric) {
    case "HEART_RATE": return [...latest, ...stat("Lowest", s.min), ...stat("Highest", s.max), ...stat("Average", s.average)];
    case "SPO2": return [...stat("Lowest", s.min), ...stat("Average", s.average), ...latest];
    case "HRV": return [...stat("Average", s.average), ...latest];
    case "STRESS": return [...stat("Average", s.average), ...stat("Highest", s.max), ...latest];
    case "RESPIRATORY_RATE": return [...stat("Average", s.average), ...latest];
    case "SKIN_TEMPERATURE": return [...latest, ...stat("Lowest", s.min), ...stat("Highest", s.max)];
    case "TEMPERATURE_DEVIATION": return [...latest, ...stat("Average", s.average)];
    case "STEPS": return stat("Total", s.total ?? s.points.reduce((sum, p) => sum + p.value, 0));
  }
}

/** A night's minutes, from its stages the way the server works them out (DayService.summarize). */
export interface NightMinutes {
  inBed: number;
  asleep: number;
  deep: number;
  rem: number;
  /** Light, and asleep with the stage unknown. */
  light: number;
  awake: number;
  /** Whether the device knew the stages. */
  staged: boolean;
}

export function nightMinutes(s: SleepSession): NightMinutes {
  const minutes = (ms: number) => Math.floor(ms / 60_000);
  const stages = s.stages ?? [];
  const time = (keep: (stage: SleepStage) => boolean) => stages.filter((x) => keep(x.stage)).reduce((sum, x) => sum + (x.end - x.start), 0);
  const inBed = minutes(s.end - s.start);
  const asleep = stages.length === 0 ? inBed : minutes(time((x) => x !== "AWAKE"));
  return {
    inBed,
    asleep,
    deep: minutes(time((x) => x === "DEEP")),
    rem: minutes(time((x) => x === "REM")),
    light: minutes(time((x) => x === "LIGHT")) + minutes(time((x) => x === "ASLEEP")),
    awake: Math.max(0, inBed - asleep),
    staged: stages.some((x) => x.stage === "DEEP" || x.stage === "REM" || x.stage === "LIGHT"),
  };
}

/**
 * The sleep to shade behind the charts: each night or nap within [from, to], ones that overlap
 * (two devices recording the same night) as one.
 */
export function sleepSpans(sessions: SleepSession[], from: number, to: number): [number, number][] {
  const spans = sessions
    .map((s): [number, number] => [Math.max(from, s.start), Math.min(to, s.end)])
    .filter(([a, b]) => b > a)
    .sort((x, y) => x[0] - y[0]);
  const out: [number, number][] = [];
  for (const [a, b] of spans) {
    const last = out[out.length - 1];
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else out.push([a, b]);
  }
  return out;
}

/** The device to name with a night: only with every device showing and more than one in the window. */
export function nightDevice(s: SleepSession, r: Pick<RecentReadings, "device" | "devices">): string | null {
  if (r.device || r.devices.length < 2) return null;
  return r.devices.find((d) => d.id === s.deviceId)?.name ?? s.source ?? null;
}

/** For "nothing recorded": the chosen device's last sync, or, for every device, the latest one's. */
export function lastSynced(devices: Pick<RecentDevice, "id" | "name" | "lastSyncAt">[], chosen?: string | null): { name: string; at: number } | null {
  let best: { name: string; at: number } | null = null;
  for (const d of devices) {
    if ((chosen && d.id !== chosen) || d.lastSyncAt == null) continue;
    if (!best || d.lastSyncAt > best.at) best = { name: d.name, at: d.lastSyncAt };
  }
  return best;
}

// ---- Temperatures: the server's are °C; imperial units read °F, as the phone app writes them ----

export const temperatureUnit = (units: Units): string => (units === "IMPERIAL" ? "°F" : "°C");

/** A temperature in °C, in the person's units. */
export const temperature = (celsius: number, units: Units): number => (units === "IMPERIAL" ? celsius * 1.8 + 32 : celsius);

/** A difference in °C (from the person's usual), in their units. */
export const temperatureChange = (celsius: number, units: Units): number => (units === "IMPERIAL" ? celsius * 1.8 : celsius);

/** "34.5 °C", "94.1 °F". */
export function temperatureText(celsius: number, units: Units): string {
  return `${temperature(celsius, units).toFixed(1)} ${temperatureUnit(units)}`;
}

/** "+0.3 °C", "−0.9 °F", "±0.0 °C". */
export function temperatureChangeText(celsius: number, units: Units): string {
  return signed(temperatureChange(celsius, units), temperatureUnit(units));
}

/** A difference already in its [unit], with its sign: "+0.5 °F". */
export function signed(value: number, unit: string): string {
  const sign = value > 0.049 ? "+" : value < -0.049 ? "−" : "±";
  return `${sign}${Math.abs(value).toFixed(1)} ${unit}`;
}

// ---- What each chart says to screen readers ----

/** "Heart rate over the last 24 hours: lowest 52 bpm, highest 118 bpm, average 68 bpm, latest 74 bpm at 2:10 PM." */
export function readingsSummary(name: string, s: RecentSeries, value: (v: number) => string, time: (ms: number) => string): string {
  const parts = [
    s.min != null && `lowest ${value(s.min)}`,
    s.max != null && `highest ${value(s.max)}`,
    s.average != null && `average ${value(s.average)}`,
    s.latest && `latest ${value(s.latest.value)} at ${time(s.latest.at)}`,
  ].filter((p): p is string => !!p);
  return `${name} over the last 24 hours${parts.length ? `: ${parts.join(", ")}` : ""}.`;
}

/** "Steps over the last 24 hours: 6,240 in all, the most 1,320 from 5 PM to 6 PM." */
export function stepsSummary(s: RecentSeries, count: (v: number) => string, time: (ms: number) => string): string {
  const total = s.total ?? s.points.reduce((sum, p) => sum + p.value, 0);
  const most = s.points.reduce<RecentPoint | null>((m, p) => (!m || p.value > m.value ? p : m), null);
  const bucket = (s.bucketMinutes ?? 60) * 60_000;
  const busiest = most && most.value > 0 ? `, the most ${count(most.value)} from ${time(most.at)} to ${time(most.at + bucket)}` : "";
  return `Steps over the last 24 hours: ${count(total)} in all${busiest}.`;
}

/** "Sleep from 11:08 PM to 6:30 AM: 7h 4m asleep, deep 1h 32m, REM 1h 41m, light 3h 51m, awake 18m." */
export function nightSummary(s: SleepSession, time: (ms: number) => string, duration: (minutes: number) => string): string {
  const n = nightMinutes(s);
  const stages = n.staged ? `, deep ${duration(n.deep)}, REM ${duration(n.rem)}, light ${duration(n.light)}, awake ${duration(n.awake)}` : "";
  return `Sleep from ${time(s.start)} to ${time(s.end)}: ${duration(n.asleep)} asleep${stages}.`;
}
