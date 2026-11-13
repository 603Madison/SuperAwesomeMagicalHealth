import type { ExerciseResult, VoiceExercise, VoiceGoal, VoiceLesson, VoicePlan, VoiceProgram, VoiceProgress, VoiceSession } from "../types";
import { median, noteName, summarise, type VoiceFrame } from "./dsp";

/*
 * The voice programme, as the trainer uses it: which lessons are open, today's lesson, the daily
 * routine, gentle starting points for each goal, and the kind words after each exercise. The
 * lessons and exercises themselves come from the server (GET /api/transition/voice/program).
 */

/** Read aloud for the baseline when the programme doesn't have its own (it does: the "baseline" exercise). */
export const BASELINE_PASSAGE =
  "Morning light came slowly over the hills. I made a cup of tea, opened the window and listened to the birds for a while. " +
  "Later, a friend called to ask if I'd like to walk by the river. We talked about books, about music, and about the little " +
  "things that made the week feel lighter. By evening the sky had turned a soft shade of orange, and everything felt calm.";

export const BASELINE_PROMPT = "Now talk about anything at all: your morning, a favourite place, or what you'd cook for a friend.";

/** The programme's starting recording ("baseline": a passage, then a prompt), read again at each check-in so they compare fairly. */
export const BASELINE_ID = "baseline";
export const CHECK_IN_ID = "checkin";

export function baselineTexts(program?: VoiceProgram): { passage: string; prompt: string } {
  const texts = program?.exercises.find((e) => e.id === BASELINE_ID)?.texts ?? [];
  return { passage: texts[0] ?? BASELINE_PASSAGE, prompt: texts[1] ?? BASELINE_PROMPT };
}

/** Looking after your voice, when the programme doesn't say. */
export const HEALTH_NOTES = [
  "Warm up gently first, and stop if anything hurts, tickles or feels scratchy.",
  "Little and often: ten minutes most days does more than an hour once a week.",
  "Sip water as you go, and rest your voice after a long day of talking.",
  "Rest days help: your voice gets stronger while it rests.",
  "If your voice stays hoarse for more than two weeks, or anything worries you, a speech therapist (SLP) or a doctor is worth seeing.",
];

export const suits = (e: VoiceExercise, goal: VoiceGoal) => e.goals.length === 0 || e.goals.includes(goal);
export const suitsLesson = (l: VoiceLesson, goal: VoiceGoal) => (l.goals ?? []).length === 0 || l.goals.includes(goal);

/** The person's path: the lessons for their goal, in order. */
export const lessonsFor = (program: VoiceProgram, goal: VoiceGoal) => program.lessons.filter((l) => suitsLesson(l, goal));

export function exercisesById(program: VoiceProgram): Map<string, VoiceExercise> {
  return new Map(program.exercises.map((e) => [e.id, e]));
}

/** A lesson's exercises, without the ones meant for another goal. */
export function lessonExercises(program: VoiceProgram, lesson: VoiceLesson, goal: VoiceGoal): VoiceExercise[] {
  const byId = exercisesById(program);
  return lesson.exerciseIds.map((id) => byId.get(id)).filter((e): e is VoiceExercise => !!e && suits(e, goal));
}

export interface Unit {
  unit: number;
  title?: string;
  about?: string;
  lessons: VoiceLesson[];
}

/** The person's lessons by unit, in order, with each unit's name. */
export function units(program: VoiceProgram, goal: VoiceGoal): Unit[] {
  const mine = lessonsFor(program, goal);
  const numbers = [...new Set(mine.map((l) => l.unit))].sort((a, b) => a - b);
  return numbers.map((unit) => {
    const u = (program.units ?? []).find((x) => x.unit === unit);
    return { unit, title: u?.title, about: u?.about, lessons: mine.filter((l) => l.unit === unit) };
  });
}

/** The units open to start: the first, and each one after a unit whose lessons have all been done once. */
export function openUnits(program: VoiceProgram, goal: VoiceGoal, done: string[]): Set<number> {
  const open = new Set<number>();
  const finished = new Set(done);
  for (const u of units(program, goal)) {
    open.add(u.unit);
    if (!u.lessons.every((l) => finished.has(l.id))) break;
  }
  return open;
}

/** Today's lesson: the server's choice, or the first open one not done yet; once all are done, the last unit's first (keeping it going). */
export function todaysLesson(program: VoiceProgram, progress?: VoiceProgress | null): VoiceLesson | undefined {
  const goal = progress?.plan.goal ?? "EXPLORING";
  const mine = lessonsFor(program, goal);
  const chosen = progress?.nextLessonId && mine.find((l) => l.id === progress.nextLessonId);
  if (chosen) return chosen;
  const open = openUnits(program, goal, progress?.lessonsDone ?? []);
  const done = new Set(progress?.lessonsDone ?? []);
  const last = units(program, goal).at(-1);
  return mine.find((l) => open.has(l.unit) && !done.has(l.id)) ?? last?.lessons[0] ?? mine[0];
}

/** The daily routine: exercises, where the programme names them, or plain lines to follow. */
export function dailyRoutine(program: VoiceProgram, goal: VoiceGoal): { exercises: VoiceExercise[]; lines: string[] } {
  const byId = exercisesById(program);
  const exercises = program.daily.map((id) => byId.get(id)).filter((e): e is VoiceExercise => !!e && suits(e, goal));
  const lines = program.daily.filter((d) => !byId.has(d));
  return { exercises, lines };
}

/** ISO day of the week, 1 Monday … 7 Sunday. */
export const isoDay = (d = new Date()) => ((d.getDay() + 6) % 7) + 1;
export const isRestDay = (plan: VoicePlan, d = new Date()) => plan.restDays.includes(isoDay(d));

/** The middle of the target range (where MATCH's notes are counted from). */
export function middleHz(plan: VoicePlan): number | undefined {
  if (plan.targetLowHz && plan.targetHighHz) return Math.sqrt(plan.targetLowHz * plan.targetHighHz);
  return plan.targetLowHz ?? plan.targetHighHz ?? plan.baselinePitchHz;
}

export const rangeOf = (plan: VoicePlan) => (plan.targetLowHz && plan.targetHighHz ? { low: plan.targetLowHz, high: plan.targetHighHz } : undefined);

/** Where a target range can be (as the server checks it). */
export const RANGE_MIN_HZ = 60;
export const RANGE_MAX_HZ = 500;

/** A plain reason the plan can't be saved yet, or null. */
export function planProblem(plan: VoicePlan): string | null {
  const { targetLowHz: low, targetHighHz: high } = plan;
  if ((low == null) !== (high == null)) return "A range needs both a bottom and a top.";
  if (low != null && high != null) {
    if (low < RANGE_MIN_HZ || high > RANGE_MAX_HZ || low > RANGE_MAX_HZ || high < RANGE_MIN_HZ) return `A range sits between ${RANGE_MIN_HZ} and ${RANGE_MAX_HZ} Hz.`;
    if (low >= high) return "The bottom of your range should be below the top.";
  }
  if (plan.dailyMinutes < 1 || plan.dailyMinutes > 60) return "Daily practice can be 1 to 60 minutes.";
  return null;
}

/** Gentle starting points for each goal, from where the voice is today (always the person's to change). */
export function suggestion(goal: VoiceGoal, baselineHz?: number, baselineResonance?: number): { low: number; high: number; resonance: number; why: string } {
  const r = (v: number) => Math.round(v);
  const within = (v: number) => Math.min(RANGE_MAX_HZ, Math.max(RANGE_MIN_HZ, v));
  if (goal === "LIGHTER") {
    // From two semitones under today's voice (never under 160 Hz), so the range starts where the voice can already go.
    const low = r(Math.min(440, Math.max(160, baselineHz ? baselineHz / 2 ** (2 / 12) : 165)));
    return {
      low, high: r(within(Math.max(220, low * 1.33))), resonance: r(Math.min(85, Math.max(65, (baselineResonance ?? 0) + 10))),
      why: "Many people aiming for a lighter voice settle somewhere around 160–220 Hz. Brighter resonance and a lighter weight usually matter more than pitch alone, so there's no need to push high.",
    };
  }
  if (goal === "DEEPER") {
    // To two semitones over today's voice (never over 150 Hz), the mirror of the above.
    const high = r(Math.max(75, Math.min(150, baselineHz ? baselineHz * 2 ** (2 / 12) : 140)));
    return {
      low: r(Math.max(RANGE_MIN_HZ, high / 1.33)), high, resonance: r(Math.max(15, Math.min(35, (baselineResonance ?? 100) - 10))),
      why: "Many people aiming for a deeper voice settle somewhere around 100–150 Hz. Pushing pitch down hard can strain; fuller, darker resonance and a relaxed throat do a lot of the work.",
    };
  }
  const middle = Math.min(440, Math.max(75, baselineHz ?? 160));
  return {
    low: r(within(middle / 2 ** (3 / 12))), high: r(within(middle * 2 ** (3 / 12))), resonance: r(baselineResonance ?? 50),
    why: "A range around where your voice sits today, with room to try things in either direction.",
  };
}

/** "165 Hz (E3)". */
export const hzText = (hz?: number) => (hz ? `${Math.round(hz)} Hz (${noteName(hz)})` : "–");

/** Words for resonance, without judging either end. */
export function resonanceWord(value: number): string {
  if (value < 20) return "darker";
  if (value < 40) return "a little darker";
  if (value < 60) return "in the middle";
  if (value < 80) return "a little brighter";
  return "brighter";
}

export function weightWord(value: number): string {
  if (value < 30) return "lighter";
  if (value < 45) return "a little lighter";
  if (value < 60) return "in the middle";
  if (value < 75) return "a little heavier";
  return "heavier";
}

export function intonationWord(semitones: number): string {
  if (semitones < 1) return "quite steady";
  if (semitones < 2.5) return "gently moving";
  if (semitones < 4) return "lively";
  return "very lively";
}

const round = (v: number | undefined, digits = 1) => (v == null ? undefined : Math.round(v * 10 ** digits) / 10 ** digits);

/** How an exercise went, from its frames: only what it measured. */
export function resultFor(e: VoiceExercise, frames: VoiceFrame[], seconds: number, plan: VoicePlan, match?: { matched: number; of: number }, feeling?: number): ExerciseResult {
  const s = summarise(frames, rangeOf(plan));
  const shows = (m: string) => e.meters.includes(m as never);
  const speech = e.kind === "SAY" || e.kind === "READ" || e.kind === "SPEAK";
  const ranged = e.kind !== "GLIDE" && e.kind !== "MATCH" && e.kind !== "GUIDED";
  return {
    exerciseId: e.id,
    seconds: Math.round(seconds),
    pitchAvgHz: round(s.pitchAvgHz),
    pitchMinHz: round(s.pitchMinHz),
    pitchMaxHz: round(s.pitchMaxHz),
    inRange: ranged ? s.inRange : undefined,
    resonance: shows("RESONANCE") || speech ? round(s.resonance) : undefined,
    weight: shows("WEIGHT") ? round(s.weight) : undefined,
    intonation: shows("INTONATION") || speech ? round(s.intonation, 2) : undefined,
    matched: match?.matched,
    of: match?.of,
    feeling,
  };
}

/** A few kind lines about how an exercise went. */
export function kindWords(e: VoiceExercise, r: ExerciseResult, plan: VoicePlan, heard: boolean): string[] {
  const lines: string[] = [];
  if (e.kind === "GUIDED") return ["Nicely done. A calm start makes everything after it easier."];
  if (r.of) {
    lines.push(r.matched === r.of ? `You matched all ${r.of} notes. Lovely listening.`
      : r.matched ? `You matched ${r.matched} of ${r.of} notes. Your ear and your voice are learning to work together.`
        : "Matching notes takes practice, and listening closely is half of it. It gets easier.");
  }
  if (!heard) {
    lines.push("We didn't hear much this time, and that's fine: doing the steps still counts.");
    return lines;
  }
  if (r.inRange != null) {
    lines.push(r.inRange >= 70 ? `Your voice sat in your range ${r.inRange}% of the time. Lovely.`
      : r.inRange >= 40 ? `In your range ${r.inRange}% of the time. That's real progress taking shape.`
        : `In your range ${r.inRange}% of the time. Every try teaches your voice something new.`);
  }
  if (r.resonance != null && plan.targetResonance != null) {
    const gap = r.resonance - plan.targetResonance;
    lines.push(Math.abs(gap) <= 8 ? "Resonance right around your aim." : `Resonance ${Math.round(r.resonance)}, with your aim at ${Math.round(plan.targetResonance)}: ${gap < 0 ? "a touch brighter" : "a touch darker"} next time.`);
  }
  if (e.id === CHECK_IN_ID && plan.baselinePitchHz && r.pitchAvgHz) {
    const res = (v?: number) => (v != null ? `, resonance ${Math.round(v)}` : "");
    lines.push(`When you started: ${hzText(plan.baselinePitchHz)}${res(plan.baselineResonance)}. Today: ${hzText(r.pitchAvgHz)}${res(r.resonance)}.`);
    const range = rangeOf(plan);
    if (range) {
      const gap = (hz: number) => (hz < range.low ? range.low - hz : hz > range.high ? hz - range.high : 0);
      if (gap(r.pitchAvgHz) < gap(plan.baselinePitchHz)) lines.push(gap(r.pitchAvgHz) === 0 ? "Right inside your range now." : "That's nearer your range than when you started.");
    }
    if (plan.targetResonance != null && plan.baselineResonance != null && r.resonance != null
      && Math.abs(r.resonance - plan.targetResonance) + 3 < Math.abs(plan.baselineResonance - plan.targetResonance)) lines.push("And your resonance is nearer your aim.");
    lines.push("Notice what has changed, not only what hasn't yet.");
  }
  if (e.kind === "GLIDE" && r.pitchMinHz && r.pitchMaxHz) {
    lines.push(`You glided from ${hzText(r.pitchMinHz)} to ${hzText(r.pitchMaxHz)}.`);
  }
  if (lines.length === 0) lines.push("Nicely done. Each bit of practice adds up.");
  return lines;
}

/** The session to save when a lesson (or practice) ends. */
export function sessionFrom(args: {
  lessonId?: string; exercises: VoiceExercise[]; results: ExerciseResult[]; frames: VoiceFrame[]; resonanceFrames: VoiceFrame[];
  seconds: number; strain: boolean; plan: VoicePlan;
}): VoiceSession {
  const s = summarise(args.frames, rangeOf(args.plan));
  const resonance = args.resonanceFrames.map((f) => f.resonance).filter((v): v is number => v != null);
  const weights = args.frames.map((f) => f.weight).filter((v): v is number => v != null);
  const feelings = args.results.map((r) => r.feeling).filter((v): v is number => v != null);
  return {
    at: Date.now(),
    minutes: Math.max(1, Math.round(args.seconds / 60)),
    exercises: args.exercises.filter((e) => args.results.some((r) => r.exerciseId === e.id)).map((e) => e.title),
    pitchAvgHz: round(s.pitchAvgHz),
    pitchMinHz: round(s.pitchMinHz),
    pitchMaxHz: round(s.pitchMaxHz),
    feeling: feelings.length ? Math.round(feelings.reduce((a, b) => a + b, 0) / feelings.length) : undefined,
    lessonId: args.lessonId,
    results: args.results,
    resonance: resonance.length >= 3 ? round(median(resonance)) : undefined,
    weight: weights.length >= 3 ? round(median(weights)) : undefined,
    strain: args.strain,
  };
}
