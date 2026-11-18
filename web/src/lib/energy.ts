import type { CheckTag, EnergyCheck, EnergyFocus, Medication, NeuroToday } from "./types";

/*
 * Energy and focus (pages/EnergyFocus.tsx), the parts that don't need React (npm test covers
 * them): a check-in's draft, today's time strip, the words for blocks of hours, how far towards
 * patterns, the ADHD medication shown in settings, reminder times, and Care's line. Type imports
 * only, so node can run it as it is. The numbers mirror the server's EnergyPatterns.
 */

/** Each scale's ends, in plain words. */
export const ENERGY_ENDS = { low: "Running on empty", high: "Plenty" } as const;
export const FOCUS_ENDS = { low: "All over the place", high: "Locked in" } as const;

/** Patterns start to show from this many check-ins over this many days (EnergyPatterns.MIN_CHECKS, MIN_DAYS). */
export const MIN_CHECKS = 12;
export const MIN_DAYS = 5;
/** A block of hours with fewer check-ins than this says little (EnergyPatterns.BLOCK_MIN). */
export const BLOCK_MIN = 3;
/** Reminder times the server keeps at most. */
export const MAX_REMINDERS = 6;

// ---- A check-in ----

/** A check-in being filled in: two taps (energy and focus), and the optional tags and note. */
export interface CheckDraft {
  energy?: number;
  focus?: number;
  tags: CheckTag[];
  note: string;
}

export const emptyDraft = (): CheckDraft => ({ tags: [], note: "" });

/** Saving needs energy or focus (the server's rule). */
export const canSave = (d: CheckDraft): boolean => d.energy != null || d.focus != null;

/** The check-in to send, at [at]: the note trimmed, and left out when there isn't one. */
export function toCheck(d: CheckDraft, at: number): EnergyCheck {
  const note = d.note.trim();
  return { at, energy: d.energy, focus: d.focus, tags: [...d.tags], note: note || undefined };
}

/** Tag on, or off again. */
export const toggleTag = (tags: CheckTag[], tag: CheckTag): CheckTag[] => (tags.includes(tag) ? tags.filter((t) => t !== tag) : [...tags, tag]);

/** "Energy 4 · focus 2", "Focus 3": what a check-in rated; null with neither. */
export function ratingText(c: Pick<EnergyCheck, "energy" | "focus">): string | null {
  const parts = [c.energy != null ? `energy ${c.energy}` : null, c.focus != null ? `focus ${c.focus}` : null].filter(Boolean) as string[];
  return parts.length ? capitalise(parts.join(" · ")) : null;
}

// ---- Today's strip ----

/** The strip runs from 6 AM to midnight. */
export const STRIP_FROM = 6;
export const STRIP_TO = 24;

/** A mark on the strip: a check-in's energy or focus (1–5), or a dose; [x] is 0 (6 AM) … 1 (midnight). */
export interface StripMark {
  kind: "energy" | "focus" | "dose";
  at: number;
  x: number;
  value?: number;
  /** The check-in's id (or its time), so energy and focus from one check-in go together. */
  key: string;
}

/** Where [hour] (with minutes as a fraction) falls on the strip, or null outside it. */
export function stripX(hour: number, from = STRIP_FROM, to = STRIP_TO): number | null {
  if (!Number.isFinite(hour) || hour < from || hour > to) return null;
  return (hour - from) / (to - from);
}

/**
 * Today's marks: each check-in's energy and focus, and each dose of the ADHD medication, placed by
 * [hourOf] (the hour on the person's clock, minutes as a fraction), in time order. Anything before
 * 6 AM is left off the strip (the list under it still has it).
 */
export function stripMarks(checks: EnergyCheck[], doses: number[], hourOf: (ms: number) => number): StripMark[] {
  const out: StripMark[] = [];
  for (const c of [...checks].sort((a, b) => a.at - b.at)) {
    const x = stripX(hourOf(c.at));
    if (x == null) continue;
    const key = c.id ?? String(c.at);
    if (c.energy != null) out.push({ kind: "energy", at: c.at, x, value: c.energy, key });
    if (c.focus != null) out.push({ kind: "focus", at: c.at, x, value: c.focus, key });
  }
  for (const d of [...doses].sort((a, b) => a - b)) {
    const x = stripX(hourOf(d));
    if (x != null) out.push({ kind: "dose", at: d, x, key: `dose:${d}` });
  }
  return out;
}

/** The strip's hour labels: "6 AM", "9 AM", "Noon" … "Midnight". */
export function hourLabel(h: number): string {
  const hour = ((h % 24) + 24) % 24;
  if (hour === 0) return "Midnight";
  if (hour === 12) return "Noon";
  return hour < 12 ? `${hour} AM` : `${hour - 12} PM`;
}

// ---- Blocks of hours ----

/** "9 AM – noon", "3 – 6 PM", "midnight – 6 AM": the server's EnergyPatterns.range, word for word. */
export function hourRange(from: number, to: number): string {
  const clock = (h: number) => {
    const hour = h % 24;
    if (hour === 0) return "midnight";
    if (hour === 12) return "noon";
    return hour < 12 ? `${hour} AM` : `${hour - 12} PM`;
  };
  const a = clock(from);
  const b = clock(to);
  const samePart = (from >= 13 && from <= 23 && to >= 13 && to <= 24 && to !== 24) || (from >= 1 && from <= 11 && to >= 1 && to <= 11);
  return samePart ? `${a.split(" ")[0]} – ${b}` : `${a} – ${b}`;
}

/** Hours since a dose: "First hour", "1–3 hours". */
export const doseRange = (from: number, to: number): string => (from === 0 && to === 1 ? "First hour" : `${from}–${to} hours`);

/** A block with too few check-ins to say much: shown, but quietly. */
export const isThin = (b: { count: number }): boolean => b.count < BLOCK_MIN;

/** Where a 1–5 average sits along a track, 0 … 1. */
export const scaleX = (v: number): number => Math.min(1, Math.max(0, (v - 1) / 4));

// ---- Patterns ----

/** How far towards patterns, 0 … 1: check-ins and days count half each, so every check-in moves it on. */
export function patternProgress(checks: number, days: number): number {
  const part = (n: number, of: number) => Math.min(1, Math.max(0, n) / of);
  return (part(checks, MIN_CHECKS) + part(days, MIN_DAYS)) / 2;
}

/** "So far: 5 check-ins over 2 days." */
export function soFar(checks: number, days: number): string {
  if (checks === 0) return "No check-ins yet.";
  return `So far: ${checks} check-in${checks === 1 ? "" : "s"} over ${days} day${days === 1 ? "" : "s"}.`;
}

/** The summary's own lines: the factors' sentences show with their titles instead, so they're left out here. */
export function headlines(ef: Pick<EnergyFocus, "summary" | "factors">): string[] {
  const factorLines = new Set(ef.factors.map((f) => f.sentence));
  return ef.summary.filter((line) => !factorLines.has(line));
}

// ---- Settings ----

/** The ADHD medication as settings show it: the one chosen, else the one found by its name, else none. */
export function medicationChoice(
  ef: Pick<EnergyFocus, "settings" | "medication">,
  meds: Pick<Medication, "id" | "name" | "active">[],
): { id: string; how: "chosen" | "found" | "none" } {
  const chosen = ef.settings.medicationId;
  if (chosen && meds.some((m) => m.id === chosen)) return { id: chosen, how: "chosen" };
  if (ef.medication) {
    const found = meds.find((m) => m.active && m.name === ef.medication) ?? meds.find((m) => m.name === ef.medication);
    if (found?.id) return { id: found.id, how: "found" };
  }
  return { id: "", how: "none" };
}

/** The medications to choose from: the ones taken now, and the chosen one even if it's been stopped. */
export function medicationOptions<T extends Pick<Medication, "id" | "name" | "active">>(meds: T[], chosen?: string): T[] {
  return meds.filter((m) => m.id && (m.active || m.id === chosen)).sort((a, b) => a.name.localeCompare(b.name));
}

/** "9:05" from what a time input gives; null for anything else. */
export function readTime(text: string): string | null {
  const m = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(text.trim());
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return `${String(h).padStart(2, "0")}:${m[2]}`;
}

/** The reminder times with [time] added: each once, in order, at most six (as the server keeps them). */
export function addReminder(times: string[], time: string): string[] {
  const t = readTime(time);
  if (!t) return times;
  return [...new Set([...times, t])].sort().slice(0, MAX_REMINDERS);
}

// ---- Care ----

/** A Care line (pages/CareLines.tsx's Headline, as text). */
export interface Line {
  value: string;
  unit?: string;
  text?: boolean;
  support?: string;
}

const capitalise = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const join = (parts: (string | null | undefined | false)[]) => parts.filter(Boolean).join(" · ");

/**
 * Autism & ADHD's line in Care: the latest check-in and when focus is usually best ("Energy 4 ·
 * focus 2 at 9:45 AM · focus usually best 9 AM – noon"), then the social battery and time in focus
 * sessions. Never "energy left": that's Mind's own budget, in Mind's line.
 */
export function neuroCareLine(n: NeuroToday | undefined, say: { time: (ms: number) => string; hm: (minutes: number) => string }): Line | null {
  if (!n) return null;
  const check = n.lastCheck ? ratingText(n.lastCheck) : null;
  const parts = [
    check ? `${check} at ${say.time(n.lastCheck!.at)}` : null,
    n.bestFocus ? `focus usually best ${n.bestFocus}` : null,
    n.socialBattery != null ? `social battery ${n.socialBattery} of 10` : null,
    n.focusMinutes > 0 ? `${say.hm(n.focusMinutes)} in focus sessions` : null,
  ].filter(Boolean) as string[];
  const hard = n.overloadsToday > 0;
  if (parts.length === 0) return hard ? { value: "A hard moment today", text: true, support: "Go gently. Calm mode is here if it helps" } : null;
  const [first, ...rest] = parts;
  return { value: capitalise(first), text: true, support: join([hard && "a hard moment today, so go gently", ...rest]) || undefined };
}

/** Mind's own energy budget, for Mind's line in Care: "6 energy left"; null without one today. */
export function mindBudgetText(n: NeuroToday | undefined): string | null {
  return n?.energyLeft != null ? `${Math.max(0, n.energyLeft)} energy left` : null;
}
