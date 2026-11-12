import { useSession } from "./session";
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

/** Today's date where the person is, as YYYY-MM-DD. */
export function today(): string {
  return isoDate(new Date());
}

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
