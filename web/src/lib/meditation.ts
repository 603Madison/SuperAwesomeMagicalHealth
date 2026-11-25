import type { BreathPacing, Meditation, MeditationKind, MeditationSession } from "./types";

/*
 * Guided meditation (pages/Meditation.tsx), the parts that don't need a browser (npm test covers
 * them): how a meditation plays as a timeline, the time left, the breathing pacer, the words read
 * one sentence at a time, which voice may read them (only one on this device, never a cloud
 * voice), and the page's own bits. Type imports only, so node can run it as it is.
 *
 * Playing one: for each step, read `say` aloud and show it; then, with a `breath`, the pacer for
 * that many breaths; then quiet for `pauseSeconds` (docs/API.md, "Meditation").
 */

/** The calm pace the server's lengths assume: about 2.5 words a second. */
export const WORDS_PER_SECOND = 2.5;

/** The voice's pace and pitch: a little slower and lower than the device's usual. */
export const VOICE_RATE = 0.85;
export const VOICE_PITCH = 0.95;

/** A stretch of a meditation: its words, its breaths or a quiet pause. */
export type SegmentKind = "say" | "breath" | "pause";

export interface Segment {
  kind: SegmentKind;
  /** The step it belongs to (its words show for the whole step). */
  step: number;
  /** How long it takes: the words at the calm pace, the breaths, or the pause. */
  seconds: number;
  /** Where it starts, in seconds from the beginning. */
  start: number;
  breath?: BreathPacing;
}

/** How many words [text] has. */
export function wordCount(text: string): number {
  return text.trim().split(/\s+/).filter(Boolean).length;
}

/** About how long [text] takes to read at the calm pace (at least a second). */
export function speechSeconds(text: string): number {
  return Math.max(1, wordCount(text) / WORDS_PER_SECOND);
}

/** One round of a pacer: in, hold, out. */
export const breathCycle = (b: BreathPacing): number => Math.max(0, b.inSeconds) + Math.max(0, b.holdSeconds) + Math.max(0, b.outSeconds);

/** A meditation as one timeline: each step's words, its breaths (if any) and its pause, in order. Empty parts are left out. */
export function buildTimeline(meditation: Pick<Meditation, "steps">): Segment[] {
  const out: Segment[] = [];
  let at = 0;
  const add = (kind: SegmentKind, step: number, seconds: number, breath?: BreathPacing) => {
    if (!(seconds > 0)) return;
    out.push({ kind, step, seconds, start: at, ...(breath ? { breath } : {}) });
    at += seconds;
  };
  meditation.steps.forEach((s, i) => {
    add("say", i, speechSeconds(s.say));
    if (s.breath && s.breath.rounds > 0) add("breath", i, breathCycle(s.breath) * s.breath.rounds, s.breath);
    add("pause", i, s.pauseSeconds);
  });
  return out;
}

/** Where playing has got to: the segment, and how far into it (seconds). Past the last segment: done. */
export interface Position {
  index: number;
  elapsed: number;
}

export const START: Position = { index: 0, elapsed: 0 };

export const isDone = (timeline: Segment[], pos: Position): boolean => pos.index >= timeline.length;

/**
 * Moves [pos] on by [dt] seconds. Breaths and pauses take their time; words too, as text only. Read
 * aloud ([waitForVoice]), words last until the voice has finished them (`afterWords`): their
 * segment waits at its end rather than cutting the voice off.
 */
export function advance(timeline: Segment[], pos: Position, dt: number, waitForVoice: boolean): Position {
  let { index, elapsed } = pos;
  elapsed += Math.max(0, dt);
  while (index < timeline.length) {
    const seg = timeline[index];
    if (seg.kind === "say" && waitForVoice) return { index, elapsed: Math.min(elapsed, seg.seconds) };
    if (elapsed < seg.seconds) return { index, elapsed };
    elapsed -= seg.seconds;
    index++;
  }
  return { index: timeline.length, elapsed: 0 };
}

/** The voice finished a step's words: on to what comes after them. */
export function afterWords(timeline: Segment[], pos: Position): Position {
  const seg = timeline[pos.index];
  return seg?.kind === "say" ? { index: pos.index + 1, elapsed: 0 } : pos;
}

/** Seconds left from [pos] to the end. */
export function timeLeft(timeline: Segment[], pos: Position): number {
  if (isDone(timeline, pos)) return 0;
  let left = 0;
  for (let i = pos.index; i < timeline.length; i++) left += timeline[i].seconds;
  return Math.max(0, left - pos.elapsed);
}

/** The whole timeline's length, in seconds. */
export const totalSeconds = (timeline: Segment[]): number => timeline.reduce((s, x) => s + x.seconds, 0);

/** "4:05", rounding up (so it never says 0:00 before the end). */
export function clockText(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

// ---- The breathing pacer ----

export type PacerPhase = "in" | "hold" | "out";

/** The pacer's words for each part of a breath. */
export const PACER_WORDS: Record<PacerPhase, string> = { in: "Breathe in", hold: "Hold", out: "Breathe out" };

/** The circle's size at its smallest and largest (as a scale). */
export const PACER_MIN = 0.55;
export const PACER_MAX = 1;

export interface PacerState {
  phase: PacerPhase;
  /** Which breath, from 1. */
  round: number;
  rounds: number;
  /** Seconds left in this part of the breath (whole, rounded up). */
  secondsLeft: number;
  /** How far through this part (0 to 1). */
  progress: number;
  /** The circle's scale: growing on the in-breath, full while holding, shrinking on the out-breath. */
  scale: number;
}

/** A gentle ease, slow at each end like a breath. */
const ease = (p: number) => (1 - Math.cos(Math.PI * Math.min(1, Math.max(0, p)))) / 2;

/** Where the pacer is [t] seconds into [b]'s breaths. Past the last breath, it rests at the end of an out-breath. */
export function pacerAt(b: BreathPacing, t: number): PacerState {
  const cycle = breathCycle(b);
  const rounds = Math.max(1, b.rounds);
  if (cycle <= 0 || t >= cycle * rounds) return { phase: "out", round: rounds, rounds, secondsLeft: 0, progress: 1, scale: PACER_MIN };
  const time = Math.max(0, t);
  const round = Math.floor(time / cycle);
  let within = time - round * cycle;
  const parts: [PacerPhase, number][] = [["in", b.inSeconds], ["hold", b.holdSeconds], ["out", b.outSeconds]];
  for (const [phase, length] of parts) {
    if (length <= 0) continue;
    if (within < length) {
      const progress = within / length;
      const scale = phase === "in" ? PACER_MIN + (PACER_MAX - PACER_MIN) * ease(progress)
        : phase === "hold" ? PACER_MAX
        : PACER_MAX - (PACER_MAX - PACER_MIN) * ease(progress);
      return { phase, round: round + 1, rounds, secondsLeft: Math.ceil(length - within), progress, scale };
    }
    within -= length;
  }
  return { phase: "out", round: round + 1, rounds, secondsLeft: 0, progress: 1, scale: PACER_MIN };
}

// ---- Reading aloud ----

/** Longer than this, a sentence is read in parts (at a comma), as some browsers stop long utterances partway. */
export const MAX_UTTERANCE = 160;

/**
 * [text] as sentences, each read on its own: Chrome stops reading a long utterance after about
 * fifteen seconds, and one sentence at a time also lets a pause start again from the sentence it
 * stopped in. A very long sentence is split again at its commas.
 */
export function splitSentences(text: string, max = MAX_UTTERANCE): string[] {
  const clean = text.replace(/\s+/g, " ").trim();
  if (!clean) return [];
  // (No lookbehind: older Safari can't read it.)
  const sentences = (clean.match(/[^.!?…]+(?:[.!?…]+["'”’)]*|$)/g) ?? [clean]).map((s) => s.trim()).filter(Boolean);
  const out: string[] = [];
  for (const s of sentences) {
    if (s.length <= max) { out.push(s); continue; }
    let part = "";
    for (const piece of s.replace(/([,;:])\s+/g, "$1\n").split("\n")) {
      if (part && part.length + 1 + piece.length > max) { out.push(part); part = piece; }
      else part = part ? `${part} ${piece}` : piece;
    }
    if (part) out.push(part);
  }
  return out;
}

/** What a voice says about itself (SpeechSynthesisVoice). */
export interface VoiceLike {
  name: string;
  lang: string;
  localService: boolean;
  default?: boolean;
  voiceURI?: string;
}

/** Novelty voices some systems have (sound effects, singing): never for this. */
const NOVELTY = [
  "albert", "bad news", "bahh", "bells", "boing", "bubbles", "cellos", "deranged", "good news", "hysterical", "jester", "organ", "pipe organ",
  "superstar", "trinoids", "whisper", "wobble", "zarvox",
];

/** The meditations are written in English. */
export const TEXT_LANG = "en";

const primary = (lang: string) => lang.replace("_", "-").toLowerCase().split("-")[0];
const normal = (lang: string) => lang.replace("_", "-").toLowerCase();

/**
 * The voice to read with, or null for text only: only a voice this device speaks itself
 * (`localService`), never one that sends the words to a company's servers (Chrome's "Google …"
 * voices, Edge's "Online" ones). It has to speak the meditations' language; one for the person's
 * own ([lang], "en-GB") comes first, then the better-sounding ones, then the device's default.
 */
export function chooseVoice<T extends VoiceLike>(voices: readonly T[], lang: string, textLang = TEXT_LANG): T | null {
  const want = primary(lang) === textLang ? normal(lang) : textLang;
  const usable = voices.filter((v) => v.localService === true && primary(v.lang || "") === textLang && !isNovelty(v.name));
  if (usable.length === 0) return null;
  const score = (v: T) =>
    (normal(v.lang) === want ? 4 : 0)
    + (/\b(enhanced|premium|natural|neural)\b/i.test(v.name) ? 1 : 0)
    + (v.default ? 0.5 : 0);
  return usable.reduce((best, v) => (score(v) > score(best) ? v : best), usable[0]);
}

function isNovelty(name: string): boolean {
  const n = name.toLowerCase().replace(/^com\.apple\.\S*\./, "");
  return NOVELTY.some((x) => n === x || n.startsWith(`${x} `) || n.startsWith(`${x}(`));
}

// ---- The page ----

/** One kind of meditation, with its lengths. */
export interface MeditationGroup {
  kind: MeditationKind;
  title: string;
  about: string;
  /** Its lengths, shortest first: the id for each. */
  lengths: { minutes: number; id: string }[];
}

/** The meditations by kind, in the server's order, the one used last first. */
export function meditationGroups(list: readonly Meditation[], lastId?: string | null): MeditationGroup[] {
  const groups: MeditationGroup[] = [];
  for (const m of list) {
    let g = groups.find((x) => x.kind === m.kind);
    if (!g) { g = { kind: m.kind, title: m.title, about: m.about, lengths: [] }; groups.push(g); }
    g.lengths.push({ minutes: m.minutes, id: m.id });
  }
  groups.forEach((g) => g.lengths.sort((a, b) => a.minutes - b.minutes));
  const last = list.find((m) => m.id === lastId)?.kind;
  if (!last) return groups;
  return [...groups.filter((g) => g.kind === last), ...groups.filter((g) => g.kind !== last)];
}

/** The length to offer first for a kind: the one used last, else 10 minutes, else its shortest. */
export function defaultLength(group: MeditationGroup, lastId?: string | null): string {
  return group.lengths.find((l) => l.id === lastId)?.id ?? group.lengths.find((l) => l.minutes === 10)?.id ?? group.lengths[0]?.id ?? "";
}

/** How it felt afterwards, 1 to 5 (MeditationSession.feltAfter). */
export const FELT_WORDS = ["Still unsettled", "A little unsettled", "In between", "More settled", "Very settled"] as const;

export const feltWord = (felt?: number | null): string | null => (felt != null && felt >= 1 && felt <= 5 ? FELT_WORDS[Math.round(felt) - 1] : null);

/** Played long enough to keep: heard to the end, or a minute or more of it. */
export const worthLogging = (spentSeconds: number, finished: boolean): boolean => finished || spentSeconds >= 60;

/** The minutes to log for [spentSeconds] played: more than 0, at most 600, to the hundredth. */
export function loggedMinutes(spentSeconds: number): number {
  const minutes = Math.round((Math.max(0, spentSeconds) / 60) * 100) / 100;
  return Math.min(600, Math.max(0.01, minutes));
}

/** "2026-11-19" [days] days from [date]. */
export function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** How many days in the last 7 (to [today], included) had a session. */
export function daysThisWeek(sessions: readonly Pick<MeditationSession, "date">[], today: string): number {
  const from = shiftDate(today, -6);
  return new Set(sessions.map((s) => s.date).filter((d): d is string => !!d && d >= from && d <= today)).size;
}

/** "35 min this week", or that there's been none yet. */
export function weekLine(weekMinutes: number): string {
  const m = Math.round(weekMinutes);
  return m > 0 ? `${m} min this week` : "None yet this week";
}

/** "12 min" for a session's minutes (at least 1). */
export const minutesText = (minutes: number): string => `${Math.max(1, Math.round(minutes))} min`;
