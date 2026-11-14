import { useState } from "react";
import { create } from "zustand";
import { api } from "./api";
import { useSession } from "./session";
import { dateIn, startOfDayIn, todayIn, zonedTime } from "./today";
import { useViewing } from "./viewing";
import type { Units } from "./types";

/** The person's units: metric until they choose otherwise. */
export function useUnits(): Units {
  return useSession((s) => s.profile?.units ?? "METRIC");
}

export const KG_PER_LB = 0.45359237;

export function weight(kg: number | undefined | null, units: Units, digits = 1): string {
  if (kg == null) return "–";
  return units === "IMPERIAL" ? `${(kg / KG_PER_LB).toFixed(digits)} lb` : `${kg.toFixed(digits)} kg`;
}

export function weightValue(kg: number, units: Units): number {
  return units === "IMPERIAL" ? kg / KG_PER_LB : kg;
}

export function toKg(value: number, units: Units): number {
  return units === "IMPERIAL" ? value * KG_PER_LB : value;
}

export function distance(m: number | undefined | null, units: Units): string {
  if (m == null) return "–";
  if (units === "IMPERIAL") return `${(m / 1609.344).toFixed(m < 16_000 ? 2 : 1)} mi`;
  return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(m < 10_000 ? 2 : 1)} km`;
}

export function height(cm: number | undefined | null, units: Units): string {
  if (cm == null) return "–";
  if (units === "IMPERIAL") {
    const inches = Math.round(cm / 2.54);
    return `${Math.floor(inches / 12)}′ ${inches % 12}″`;
  }
  return `${Math.round(cm)} cm`;
}

export const num = (n: number | undefined | null, digits = 0) =>
  n == null || Number.isNaN(n) ? "–" : n.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });

/** "7h 32m", "45m". */
export function hm(minutes: number | undefined | null): string {
  if (minutes == null) return "–";
  const m = Math.round(minutes);
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}

export function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

/*
 * Days follow the person's time zone (their profile's, as the server's do), or, while viewing
 * someone's share, that person's once it's known. "Today" moves on by itself: checked every
 * minute, and straight away when the tab comes back, so nothing is logged to yesterday.
 */

/** While viewing a share: the owner's time zone, once it's known. */
let shownZone: { owner: string; zone: string | null } | null = null;

/** The time zone the days showing are in; undefined for the browser's own. */
export function dayZone(): string | undefined {
  const share = useViewing.getState().share;
  if (share && shownZone?.owner === share.ownerId && shownZone.zone) return shownZone.zone;
  return useSession.getState().profile?.timezone || undefined;
}

/** Today's date where the person is, as YYYY-MM-DD. In a component, [useToday] follows it as it changes. */
export function today(): string {
  return todayIn(dayZone());
}

/** The day [ms] falls on where the person is, as YYYY-MM-DD. */
export const dayOf = (ms: number): string => dateIn(ms, dayZone());

/** When [date] begins where the person is. */
export const dayStart = (date: string): number => startOfDayIn(date, dayZone());

/** The moment it's [time] ("HH:mm") on [date] where the person is. */
export const timeOn = (date: string, time: string): number => zonedTime(date, time, dayZone());

const useDay = create<{ today: string }>(() => ({ today: today() }));

function checkDay() {
  const now = today();
  if (now !== useDay.getState().today) useDay.setState({ today: now });
}

/** Today's date (YYYY-MM-DD), changing at midnight where the person is. */
export function useToday(): string {
  return useDay((s) => s.today);
}

/**
 * A page's day: today until another is picked. Left on today, it moves on to the new day by
 * itself; a picked day stays. Returns the day, a way to pick one, and today.
 */
export function useDayPicker(): [string, (date: string) => void, string] {
  const now = useToday();
  const [picked, setPicked] = useState<string | null>(null);
  return [picked ?? now, (date: string) => setPicked(date === now ? null : date), now];
}

if (typeof window !== "undefined") {
  window.addEventListener("focus", checkDay);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") checkDay(); });
  window.setInterval(checkDay, 60_000);
}
useSession.subscribe((s, before) => { if (s.profile?.timezone !== before.profile?.timezone) checkDay(); });

// While viewing someone's share, their days: their time zone comes with their profile as shared.
let zoneAsk = 0;
let zoneFor: string | null = null;
function followShown() {
  const share = useViewing.getState().share;
  const user = useSession.getState().user;
  const owner = share && user && share.viewerId === user.id ? share.ownerId : null;
  if (owner === zoneFor) return;
  zoneFor = owner;
  const mine = ++zoneAsk;
  shownZone = null;
  checkDay();
  if (!owner) return;
  api.viewedProfile().then((p) => {
    if (mine !== zoneAsk) return;
    shownZone = { owner, zone: p.timezone || null };
    checkDay();
  }, () => undefined);
}
useViewing.subscribe(followShown);
useSession.subscribe((s, before) => { if (s.user?.id !== before.user?.id) followShown(); });

export function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return isoDate(new Date(y, m - 1, d + days));
}

export function parseDate(date: string): Date {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(y, m - 1, d);
}

/** "Today", "Yesterday", or "Tue 3 Mar". */
export function dayLabel(date: string): string {
  if (date === today()) return "Today";
  if (date === shiftDate(today(), -1)) return "Yesterday";
  return parseDate(date).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
}

export function shortDate(date: string): string {
  return parseDate(date).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

/** "3 minutes ago", "yesterday", "on 3 Mar". */
export function when(ms: number): string {
  const seconds = (Date.now() - ms) / 1000;
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)} h ago`;
  if (seconds < 2 * 86_400) return "yesterday";
  if (seconds < 7 * 86_400) return `${Math.floor(seconds / 86_400)} days ago`;
  return `on ${new Date(ms).toLocaleDateString(undefined, { day: "numeric", month: "short", year: seconds > 300 * 86_400 ? "numeric" : undefined })}`;
}

/** A score's word, as on the server. */
export function scoreLabel(score: number): string {
  if (score >= 85) return "Optimal";
  if (score >= 70) return "Good";
  if (score >= 60) return "Fair";
  return "Pay attention";
}

export const WORKOUT_TYPES: { id: string; label: string }[] = [
  { id: "WALK", label: "Walk" }, { id: "RUN", label: "Run" }, { id: "CYCLE", label: "Ride" }, { id: "SWIM", label: "Swim" },
  { id: "STRENGTH", label: "Strength" }, { id: "HIIT", label: "HIIT" }, { id: "YOGA", label: "Yoga" }, { id: "PILATES", label: "Pilates" },
  { id: "HIKE", label: "Hike" }, { id: "ROW", label: "Row" }, { id: "ELLIPTICAL", label: "Elliptical" }, { id: "DANCE", label: "Dance" },
  { id: "SPORT", label: "Sport" }, { id: "CLIMB", label: "Climb" }, { id: "SKI", label: "Ski" }, { id: "OTHER", label: "Other" },
];

export const workoutLabel = (type: string) => WORKOUT_TYPES.find((t) => t.id === type)?.label ?? "Workout";

/** A datetime-local input's value for [ms], in local time. */
export function localInput(ms: number): string {
  const d = new Date(ms);
  return `${isoDate(d)}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
