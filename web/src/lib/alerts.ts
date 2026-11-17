import type { AlertLevel, AlertSettings, AlertWatch, HealthAlert, HealthAlerts } from "./types";

/*
 * Health alerts (GET /api/alerts, pages/Alerts.tsx), the parts that don't need React (npm test
 * covers them): each level's words, when an alert happened, what's new, what putting one away
 * says, what it's watching, and the settings kept within the server's limits. Type imports only,
 * so node can run it as it is.
 */

/** The page's sections in the default order. Their ids are saved (layoutKey("alerts")): never rename one. */
export const ALERT_SECTIONS = ["alerts", "watching", "settings"] as const;
export type AlertSection = (typeof ALERT_SECTIONS)[number];

/** How far back the page looks. */
export const ALERT_DAYS = 30;

/** How soon to act, in the words the badge uses. */
export const ALERT_LEVEL_TEXT: Record<AlertLevel, string> = {
  URGENT: "Could need care now",
  DOCTOR: "Worth a doctor's look",
  WATCH: "Keep an eye on",
};

/** The settings as the server starts them (AlertSettings()). */
export const DEFAULT_ALERT_SETTINGS: AlertSettings = {
  enabled: true, highHeartRate: 120, lowHeartRate: 40, lowOxygen: 90, illness: true, rhythm: true, notify: true,
};

/** The numbers the person can set, within what the server keeps (AlertSettings.clamped), and how far each step moves. */
export const ALERT_RANGES = {
  highHeartRate: { min: 100, max: 180, step: 5, unit: "bpm" },
  lowHeartRate: { min: 30, max: 60, step: 1, unit: "bpm" },
  lowOxygen: { min: 85, max: 94, step: 1, unit: "%" },
} as const;
export type AlertNumber = keyof typeof ALERT_RANGES;

/** [value] as a whole number within [key]'s range (anything that isn't a number: the default). */
export function clampAlertNumber(key: AlertNumber, value: number): number {
  const r = ALERT_RANGES[key];
  if (!Number.isFinite(value)) return DEFAULT_ALERT_SETTINGS[key];
  return Math.min(r.max, Math.max(r.min, Math.round(value)));
}

/** The settings with every number within its range, as the server will keep them. */
export function clampAlertSettings(s: AlertSettings): AlertSettings {
  return {
    ...s,
    highHeartRate: clampAlertNumber("highHeartRate", s.highHeartRate),
    lowHeartRate: clampAlertNumber("lowHeartRate", s.lowHeartRate),
    lowOxygen: clampAlertNumber("lowOxygen", s.lowOxygen),
  };
}

/** One step up (1) or down (−1) from [value], within range, landing on the step's own numbers (115, 120…). */
export function stepAlertNumber(key: AlertNumber, value: number, direction: 1 | -1): number {
  const { step } = ALERT_RANGES[key];
  const on = direction > 0 ? Math.floor(value / step) * step + step : Math.ceil(value / step) * step - step;
  return clampAlertNumber(key, on);
}

/** Whether two settings differ (for the Save button). */
export function settingsChanged(a: AlertSettings, b: AlertSettings): boolean {
  return (Object.keys(DEFAULT_ALERT_SETTINGS) as (keyof AlertSettings)[]).some((k) => a[k] !== b[k]);
}

/**
 * Newest first, as the server sends them, except that one that could need care now (and isn't put
 * away) comes before the rest, as it does on Today: a newer, milder one never pushes it down.
 */
export function newestFirst(alerts: HealthAlert[]): HealthAlert[] {
  const urgent = (a: HealthAlert) => (a.level === "URGENT" && a.dismissedAt == null ? 0 : 1);
  return [...alerts].sort((x, y) => urgent(x) - urgent(y) || y.createdAt - x.createdAt);
}

/** Put away by the person: it stays in the list, quieter. */
export const isAway = (a: Pick<HealthAlert, "dismissedAt">): boolean => a.dismissedAt != null;

/** The ones not seen yet (and not put away): "New" on this visit. */
export function unseenIds(alerts: Pick<HealthAlert, "id" | "seenAt" | "dismissedAt">[]): string[] {
  return alerts.filter((a) => a.seenAt == null && a.dismissedAt == null).map((a) => a.id);
}

/** The list once [changed] (put away, say) is in it in place of its old self. */
export function replaceAlert(alerts: HealthAlert[], changed: HealthAlert): HealthAlert[] {
  return alerts.map((a) => (a.id === changed.id ? changed : a));
}

/** How the page writes a moment and its day: "2:10 AM", and "Today", "Yesterday" or "Mon 16 Nov". */
export interface AlertClock {
  time: (ms: number) => string;
  day: (ms: number) => string;
}

const LONG_MS = 20 * 3_600_000;

/**
 * When it happened: "Today, 2:10 AM" (a moment), "Today, 2:10 AM – 2:48 AM" (a spell), across
 * midnight "Yesterday 11:40 PM – Today 12:25 AM", and over days "Sat 14 Nov – Today".
 */
export function alertWhen(a: Pick<HealthAlert, "start" | "end">, clock: AlertClock): string {
  const startDay = clock.day(a.start);
  if (a.end == null || a.end <= a.start) return `${startDay}, ${clock.time(a.start)}`;
  const endDay = clock.day(a.end);
  if (a.end - a.start >= LONG_MS) return startDay === endDay ? startDay : `${startDay} – ${endDay}`;
  if (endDay === startDay) return `${startDay}, ${clock.time(a.start)} – ${clock.time(a.end)}`;
  return `${startDay} ${clock.time(a.start)} – ${endDay} ${clock.time(a.end)}`;
}

/** What a put-away alert says about it: "Put away: I was active", or just "Put away". */
export function awayText(a: Pick<HealthAlert, "feedback">, labels: Record<string, string>): string {
  return a.feedback ? `Put away: ${labels[a.feedback] ?? a.feedback}` : "Put away";
}

/** "From Polar H10 and Colmi R02"; null without devices. */
export function devicesText(devices?: string[]): string | null {
  const list = (devices ?? []).filter((d) => d.trim());
  if (list.length === 0) return null;
  const named = list.length === 1 ? list[0] : `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]}`;
  return `From ${named}`;
}

/** Where one thing it can watch stands: on, off (the person turned it off), or not yet (it needs a device, or is learning their usual). */
export function watchState(w: AlertWatch): "on" | "off" | "not-yet" {
  if (w.watching) return "on";
  return /^turned off/i.test(w.how) ? "off" : "not-yet";
}

export const WATCH_STATE_TEXT: Record<ReturnType<typeof watchState>, string> = { on: "Watching", off: "Off", "not-yet": "Not yet" };

/**
 * Health alerts in one line, for Body's row: how many are new, and how much it's watching. Without
 * the list yet ([alerts] null), just what's new.
 */
export function alertsLine(unseen: number, alerts?: Pick<HealthAlerts, "settings" | "watching"> | null): string {
  const fresh = unseen > 0 ? `${unseen} new` : "Nothing new";
  if (!alerts) return unseen > 0 ? fresh : "A heads-up when your readings look out of the ordinary";
  if (!alerts.settings.enabled) return "Turned off";
  const n = alerts.watching.filter((w) => w.watching).length;
  return `${fresh} · ${n === 0 ? "nothing to watch yet" : `watching ${n} ${n === 1 ? "thing" : "things"}`}`;
}

/** The empty list's words: alerts off, nothing to watch yet, or a quiet spell (then what it's watching follows). */
export function emptyText(settings: Pick<AlertSettings, "enabled">, watching: AlertWatch[], days = ALERT_DAYS): string {
  if (!settings.enabled) return "Health alerts are off, so nothing is being watched. Turn them on below to get a heads-up.";
  if (!watching.some((w) => w.watching)) return "There's nothing to watch yet: a device that measures heart rate or blood oxygen brings it in.";
  return `No alerts in the last ${days} days. Here's what it's watching:`;
}
