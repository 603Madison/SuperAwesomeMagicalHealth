import type { ContinenceEntry, ContinenceSettings, ContinenceWearing, Fullness, ProductStats, Supply, SupplyKind } from "./types";

/*
 * What's being worn, and a change as one step (npm test covers these): the products a change
 * offers and in what order, the Change sheet's starting point, and how a change, a wear and a
 * product's pattern read. Type imports only, so node can run it as it is.
 */

/** The kinds of supply that are worn: what the Change sheet and "What are you wearing?" offer. */
export const WORN_KINDS: SupplyKind[] = ["LINER", "PAD", "BOOSTER", "PULL_UP", "BRIEF", "BED_PAD", "REUSABLE_UNDERWEAR", "OTHER"];

const KIND_WORD: Partial<Record<SupplyKind, string>> = {
  LINER: "Liner", PAD: "Pad", BOOSTER: "Booster pad", PULL_UP: "Pull-up", BRIEF: "Brief", BED_PAD: "Bed pad", REUSABLE_UNDERWEAR: "Reusable underwear",
};

const FULLNESS_WORD: Record<Fullness, string> = { DRY: "Dry", DAMP: "Damp", WET: "Wet", FULL: "Full", SOAKED: "Soaked", LEAKED: "Leaked through" };

/** A product's kind in a word or two: "Brief", "Pull-up". */
export const kindWord = (kind?: SupplyKind): string | undefined => (kind ? KIND_WORD[kind] : undefined);

/** The active supplies that are worn. */
export function wearable(supplies: Supply[]): Supply[] {
  return supplies.filter((s) => s.active && s.id && WORN_KINDS.includes(s.kind));
}

/** "7 h 10 min", "45 min", "7 h". */
export function wornFor(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  if (m < 60) return `${m} min`;
  return m % 60 === 0 ? `${Math.floor(m / 60)} h` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

/** A usual time, roughly: to the quarter hour under two hours, the half hour after. */
export function rough(minutes: number): number {
  const step = minutes < 120 ? 15 : 30;
  return Math.max(step, Math.round(minutes / step) * step);
}

/** "7 h", "45 min": a short time for a row's title, to the nearest hour past one. */
export function hoursShort(minutes: number): string {
  return minutes < 60 ? `${Math.max(0, Math.round(minutes))} min` : `${Math.round(minutes / 60)} h`;
}

/** What a change put on: its put-on, or, for a change from before that was recorded, a fresh one of what it took off. */
export function putOnOf(e: ContinenceEntry): string | undefined {
  if (e.kind !== "PAD_CHANGE" || e.nothingOn) return undefined;
  return e.putOnSupplyId ?? e.supplyId;
}

/** The products put on lately, newest first, each once. */
export function recentPutOns(entries: ContinenceEntry[]): string[] {
  const out: string[] = [];
  for (const e of [...entries].sort((a, b) => b.at - a.at)) {
    const id = putOnOf(e);
    if (id && !out.includes(id)) out.push(id);
  }
  return out;
}

/** What a change offers to put on: the usual first, then the last used, then the rest as they're listed. */
export function putOnOrder(supplies: Supply[], usualId?: string, recent: string[] = []): Supply[] {
  const list = wearable(supplies);
  const rank = (s: Supply) => (s.id === usualId ? -1_000 : recent.includes(s.id!) ? recent.indexOf(s.id!) - 500 : list.indexOf(s));
  return [...list].sort((a, b) => rank(a) - rank(b));
}

const base = (at: number): ContinenceEntry => ({ at, kind: "PAD_CHANGE", triggers: [], pain: false, blood: false, night: false });

/**
 * The Change sheet's starting point: now, taking off what's on, and putting the same on again
 * (or, with nothing known, the usual or the last used).
 */
export function changeDraft(wearing: ContinenceWearing | undefined, settings: ContinenceSettings | undefined, supplies: Supply[], recent: string[], now = Date.now()): ContinenceEntry {
  const ids = new Set(wearable(supplies).map((s) => s.id!));
  const off = wearing?.supplyId && ids.has(wearing.supplyId) ? wearing.supplyId : undefined;
  const usual = settings?.defaultSupplyId && ids.has(settings.defaultSupplyId) ? settings.defaultSupplyId : undefined;
  const on = off ?? usual ?? recent.find((id) => ids.has(id));
  return { ...base(now), supplyId: off, putOnSupplyId: on, nothingOn: false };
}

/** "Put on" alone: what "What are you wearing?" logs. */
export function putOnEntry(supplyId: string, now = Date.now()): ContinenceEntry {
  return { ...base(now), putOnSupplyId: supplyId, nothingOn: false };
}

/** An existing change as the sheet edits it: an older change put on a fresh one of what it took off. */
export function asChange(e: ContinenceEntry): ContinenceEntry {
  if (e.kind !== "PAD_CHANGE" || e.putOnSupplyId || e.nothingOn || !e.supplyId) return { ...e, kind: "PAD_CHANGE" };
  return { ...e, putOnSupplyId: e.supplyId };
}

/** "Night briefs → Day pants", "Night briefs → nothing for now", "Put on Day pants"; "Change" with no products named. */
export function changeTitle(e: ContinenceEntry, supplies: Supply[]): string {
  const name = (id: string) => supplies.find((s) => s.id === id)?.name ?? "A product";
  const off = e.supplyId;
  const on = putOnOf(e);
  if (e.nothingOn) return off ? `${name(off)} → nothing for now` : "Took it off, nothing on for now";
  if (off && on) return `${name(off)} → ${name(on)}`;
  if (on) return `Put on ${name(on)}`;
  return "Change";
}

/** What a change says after its title: "Full", "worn 7 h", "leaked through". */
export function changeDetail(e: ContinenceEntry): string[] {
  return [
    e.fullness ? FULLNESS_WORD[e.fullness] : "",
    e.wornMinutes != null ? `worn ${wornFor(e.wornMinutes)}` : "",
    e.leakedThrough && e.fullness !== "LEAKED" ? "leaked through" : "",
  ].filter(Boolean);
}

/** The diary's line for a change: "Night briefs → Day pants · Full · worn 7 h". */
export function changeLine(e: ContinenceEntry, supplies: Supply[]): string {
  return [changeTitle(e, supplies), ...changeDetail(e)].join(" · ");
}

/** Whether what's on is known: something on, or nothing on. */
export const knows = (w?: ContinenceWearing): w is ContinenceWearing & { since: number } => w?.since != null;

/** How long it's been on, as of [now]. */
export const onFor = (w: ContinenceWearing, now = Date.now()) => (w.since != null ? Math.max(0, Math.round((now - w.since) / 60_000)) : 0);

/** "Since 11:20 pm · 7 h 10 min"; [clock] says a time the person's way. */
export function sinceLine(w: ContinenceWearing, clock: (ms: number) => string, now = Date.now()): string {
  return w.since == null ? "" : `Since ${clock(w.since)} · ${wornFor(onFor(w, now))}`;
}

/** "You usually change these after about 6 h": with enough wears to go on. */
export function usualLine(w: ContinenceWearing): string | null {
  return w.typicalMinutes != null && w.supplyId ? `You usually change these after about ${wornFor(rough(w.typicalMinutes))}` : null;
}

/** Whether a change reminder is due now (Up next's "Time to change"). */
export function changeDue(w: ContinenceWearing | undefined, now = Date.now()): boolean {
  return !!w?.supplyId && w.changeDueAt != null && w.changeDueAt <= now;
}

/** "usually 7 h, often full, leaked through 1 in 12": a product's pattern, without its name. */
export function productPattern(p: ProductStats): string {
  const parts = [
    p.avgWornMinutes != null ? `usually ${wornFor(rough(p.avgWornMinutes))}` : "",
    p.typicalFullness ? `often ${FULLNESS_WORD[p.typicalFullness].toLowerCase()}` : "",
    p.leakedThrough > 0 ? `leaked through ${p.leakedThrough} in ${p.changes}` : p.changes >= 3 ? "never leaked through" : "",
    p.nightShare >= 0.5 ? "mostly at night" : "",
  ].filter(Boolean);
  return parts.length ? parts.join(", ") : `${p.changes} ${p.changes === 1 ? "change" : "changes"}`;
}

/** "Night briefs: usually 7 h, often full, leaked through 1 in 12". */
export const productLine = (p: ProductStats) => `${p.name}: ${productPattern(p)}`;
