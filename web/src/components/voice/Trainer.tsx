import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, Check, ChevronLeft, ChevronRight, Lightbulb, Mic, Pause, Play, RotateCcw, SkipForward, Volume2, X } from "lucide-react";
import { api } from "../../lib/api";
import { useSession } from "../../lib/session";
import { VOICE_SKILLS, type ExerciseResult, type VoiceExercise, type VoiceMeter, type VoicePlan } from "../../lib/types";
import { LoudnessWatch, intonationSpread, noteName, semitones, shiftHz, type VoiceFrame } from "../../lib/voice/dsp";
import { MIC_PROBLEM_TEXT, micPermission, useMic, useWakeLock, type Mic as MicHandle } from "../../lib/voice/mic";
import { BASELINE_ID, hzText, intonationWord, kindWords, middleHz, rangeOf, resonanceWord, resultFor, sessionFrom, weightWord } from "../../lib/voice/program";
import { saveRecording, useKeepRecordings } from "../../lib/voice/recordings";
import { playTone, type Playing } from "../../lib/voice/sound";
import { Button, IconButton, Modal, Spinner, Stat, errorToast } from "../ui";
import { Announcer, IntonationMeter, LoudnessMeter, PitchTrace, ResonanceMeter, WeightMeter, usePitchTrace, type TracePoint } from "./Meters";

/*
 * The trainer: one exercise at a time, with its steps, a timer and big live meters, then a kind
 * word about how it went, "how did that feel?" and "any strain?", and on to the next. Finishing
 * saves a VoiceSession (only the numbers: the sound never leaves the browser). Strain stops
 * things gently. The screen stays awake while it's open.
 */

const FEELINGS = ["Hard", "Not easy", "Okay", "Good", "Great"];
const TIMED = new Set(["GUIDED", "HOLD", "GLIDE", "RESONANCE", "SPEAK"]);
export const REST_NOTE = "Thank you for saying. Let's stop here for today and let your voice rest: sip some water, and keep talking soft and easy. If strain keeps coming back, or anything hurts, a speech therapist (SLP) or a doctor is worth seeing.";

type Phase = "intro" | "exercise" | "review" | "done";

const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;

export function Trainer({ title, lessonId, exercises, plan, onClose, onSaved, onPlanChanged }: {
  title: string; lessonId?: string; exercises: VoiceExercise[]; plan: VoicePlan; onClose: () => void; onSaved: () => void;
  /** The plan changed here (a new starting point from the "baseline" exercise). */
  onPlanChanged?: (plan: VoicePlan) => void;
}) {
  const user = useSession((s) => s.user)!;
  const [keep] = useKeepRecordings(user.id);
  const mic = useMic();
  const trace = usePitchTrace(mic);
  useWakeLock(true);
  const needsMic = exercises.some((e) => e.kind !== "GUIDED");
  const [phase, setPhase] = useState<Phase>("intro");
  const [index, setIndex] = useState(0);
  const [running, setRunning] = useState(false);
  const [started, setStarted] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [textIndex, setTextIndex] = useState(0);
  const [results, setResults] = useState<ExerciseResult[]>([]);
  const [review, setReview] = useState<{ result: ExerciseResult; lines: string[]; heard: boolean } | null>(null);
  const [strain, setStrain] = useState<boolean | null>(null);
  const [anyStrain, setAnyStrain] = useState(false);
  const [saving, setSaving] = useState<"no" | "saving" | "saved" | "failed">("no");
  const [leaving, setLeaving] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [granted, setGranted] = useState(false);
  const exercise = exercises[index];
  const heading = useRef<HTMLHeadingElement>(null);
  const collecting = useRef(false);
  const matchCollect = useRef(true);
  const frames = useRef<VoiceFrame[]>([]);
  const allFrames = useRef<VoiceFrame[]>([]);
  const resonanceFrames = useRef<VoiceFrame[]>([]);
  const totalSeconds = useRef(0);
  const watch = useRef(new LoudnessWatch());
  const recordingOn = useRef(false);

  useEffect(() => { void micPermission().then((p) => setGranted(p === "granted")); }, []);
  // Each phase and exercise starts with its heading, for keyboards and screen readers.
  useEffect(() => { heading.current?.focus(); }, [phase, index]);

  // The frames while practising count towards this exercise.
  useEffect(() => mic.subscribe((list) => {
    let loud = false;
    for (const f of list) loud = watch.current.push(f);
    setPushing((was) => (was === loud ? was : loud));
    if (!collecting.current || !matchCollect.current) return;
    frames.current.push(...list);
  }), [mic.subscribe]);

  // The exercise's clock: time spent running, from timestamps (the display ticks; the count doesn't depend on it).
  const clockRef = useRef<{ before: number; since: number | null }>({ before: 0, since: null });
  const activeSeconds = () => clockRef.current.before + (clockRef.current.since != null ? (performance.now() - clockRef.current.since) / 1000 : 0);
  const run = (on: boolean) => {
    const c = clockRef.current;
    if (on && c.since == null) c.since = performance.now();
    if (!on && c.since != null) { c.before += (performance.now() - c.since) / 1000; c.since = null; }
    collecting.current = on;
    setRunning(on);
    setElapsed(activeSeconds());
  };
  const resetClock = () => {
    clockRef.current = { before: 0, since: null };
    setElapsed(0);
  };
  useEffect(() => {
    if (!running) return;
    const id = setInterval(() => setElapsed(activeSeconds()), 250);
    return () => clearInterval(id);
  }, [running]); // eslint-disable-line react-hooks/exhaustive-deps

  const timed = exercise && TIMED.has(exercise.kind);
  const remaining = exercise ? Math.max(0, exercise.seconds - elapsed) : 0;

  const finish = useCallback(async (match?: { matched: number; of: number }) => {
    if (!exercise) return;
    run(false);
    const seconds = activeSeconds();
    const list = frames.current;
    const result = resultFor(exercise, list, seconds, plan, match);
    allFrames.current.push(...list);
    if (exercise.meters.includes("RESONANCE") || ["SAY", "READ", "SPEAK", "RESONANCE"].includes(exercise.kind)) resonanceFrames.current.push(...list);
    totalSeconds.current += seconds;
    const heard = list.some((f) => f.voiced);
    setReview({ result, lines: kindWords(exercise, result, plan, heard || mic.status !== "on"), heard });
    setStrain(null);
    setPhase("review");
    if (recordingOn.current) {
      recordingOn.current = false;
      const sound = await mic.stopRecording();
      if (sound && sound.pcm.length > sound.sampleRate) {
        await saveRecording(user.id, { at: Date.now(), kind: exercise.id === BASELINE_ID ? "baseline" : "practice", title: exercise.title, lessonId, exerciseId: exercise.id, pitchAvgHz: result.pitchAvgHz, resonance: result.resonance }, sound.pcm, sound.sampleRate)
          .catch(() => errorToast("Couldn't keep that recording in this browser."));
      }
    }
  }, [exercise, plan, mic, user.id, lessonId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Timed exercises end on their own.
  useEffect(() => { if (running && timed && remaining <= 0) void finish(); }, [running, timed, remaining, finish]);

  const begin = async (withMic: boolean) => {
    if (withMic && needsMic) await mic.start();
    setPhase("exercise");
  };

  const startExercise = () => {
    if (!started) {
      frames.current = [];
      setStarted(true);
      if (keep && mic.status === "on" && exercise.kind !== "GUIDED") { mic.startRecording(); recordingOn.current = true; }
    }
    run(true);
  };

  const keepResult = (r: ExerciseResult) => setResults((list) => [...list.filter((x) => x.exerciseId !== r.exerciseId), r]);

  const nextExercise = (result: ExerciseResult) => {
    keepResult(result);
    if (index + 1 < exercises.length) {
      setIndex(index + 1);
      resetClock();
      setTextIndex(0);
      setStarted(false);
      frames.current = [];
      setPhase("exercise");
    } else {
      void save([...results.filter((x) => x.exerciseId !== result.exerciseId), result], anyStrain || strain === true);
    }
  };

  const again = () => {
    setAttempt((a) => a + 1);
    resetClock();
    setTextIndex(0);
    setStarted(false);
    frames.current = [];
    setPhase("exercise");
  };

  const save = async (all: ExerciseResult[], withStrain: boolean, partial = false) => {
    setPhase("done");
    mic.stop();
    if (all.length === 0) { setSaving("no"); return; }
    setSaving("saving");
    try {
      await api.addVoiceSession(sessionFrom({
        lessonId: partial ? undefined : lessonId, exercises, results: all, frames: allFrames.current, resonanceFrames: resonanceFrames.current,
        seconds: totalSeconds.current, strain: withStrain, plan,
      }));
      setSaving("saved");
      onSaved();
    } catch (e) {
      setSaving("failed");
      errorToast(e);
    }
  };

  const leave = () => {
    if (phase === "done" || (results.length === 0 && !started)) { onClose(); return; }
    setLeaving(true);
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape" && !leaving) { e.preventDefault(); leave(); } };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const band = rangeOf(plan);
  const now = mic.status === "on" ? mic.now : null;
  const recent = trace.current ?? [];
  const lastT = recent.length ? recent[recent.length - 1].t : 0;
  const spread = useMemo(() => intonationSpread(recent.filter((p) => p.hz != null && p.t > lastT - 6).map((p) => p.hz!)), [now, lastT]); // eslint-disable-line react-hooks/exhaustive-deps
  const announcement = running && now?.pitchHz
    ? [`${Math.round(now.pitchHz)} hertz${band ? (now.pitchHz < band.low ? ", under your range" : now.pitchHz > band.high ? ", over your range" : ", in your range") : ""}.`,
      exercise.meters.includes("RESONANCE") && now.resonance != null ? `Resonance ${Math.round(now.resonance)}, ${resonanceWord(now.resonance)}.` : "",
      exercise.meters.includes("WEIGHT") && now.weight != null ? `Weight ${Math.round(now.weight)}, ${weightWord(now.weight)}.` : "",
      exercise.meters.includes("INTONATION") && spread != null ? `Intonation ${intonationWord(spread)}.` : ""].filter(Boolean).join(" ")
    : "";

  return (
    <div className="fullscreen voice-trainer" role="dialog" aria-modal="true" aria-label={title}>
      <header className="trainer-top">
        <div className="trainer-title">
          <span className="muted small">{title}</span>
          <ol className="trainer-dots" aria-label={`Exercise ${Math.min(index + 1, exercises.length)} of ${exercises.length}`}>
            {exercises.map((e, i) => <li key={e.id} className={i < index || (i === index && phase === "done") ? "is-done" : i === index ? "is-now" : ""} title={e.title} />)}
          </ol>
        </div>
        <IconButton label={phase === "done" ? "Close" : "Leave the lesson"} onClick={leave}><X size={22} /></IconButton>
      </header>

      <main className="trainer-body">
        {phase === "intro" && (
          <section className="trainer-intro">
            <h2 ref={heading} tabIndex={-1}>{title}</h2>
            <ol className="trainer-plan">
              {exercises.map((e) => <li key={e.id}><span>{e.title}</span><span className="muted small">{VOICE_SKILLS.find((s) => s.id === e.skill)?.label} · {Math.max(1, Math.round(e.seconds / 60))} min</span></li>)}
            </ol>
            {needsMic && mic.status !== "on" && (
              <div className="panel-card mic-explain">
                <h3><Mic size={18} /> Before you start</h3>
                <p>The trainer listens through your microphone to show your pitch, resonance and more as you practise. It all happens here in this browser: no sound is sent anywhere{keep ? ", and recordings stay only in this browser" : ""}.</p>
                {!granted && <p className="muted small">Your browser will ask whether to share the microphone.</p>}
                {mic.status === "problem" && mic.problem && <p className="notice" role="alert">{MIC_PROBLEM_TEXT[mic.problem]}</p>}
              </div>
            )}
            <p className="muted small">Stop at any point if something feels uncomfortable. Little and often is the way.</p>
            <div className="button-row">
              {needsMic && mic.status !== "on" ? <>
                <Button variant="primary" busy={mic.status === "starting"} onClick={() => void begin(true)}><Mic size={18} /> {mic.status === "problem" ? "Try the microphone again" : granted ? "Start" : "Turn on the microphone and start"}</Button>
                <Button variant="ghost" onClick={() => void begin(false)}>Start without it</Button>
              </> : <Button variant="primary" onClick={() => void begin(false)}><Play size={18} /> Start</Button>}
            </div>
          </section>
        )}

        {phase === "exercise" && exercise && (
          <section className="trainer-exercise">
            <div className="trainer-head">
              <span className="chip is-static small">{VOICE_SKILLS.find((s) => s.id === exercise.skill)?.label}</span>
              <h2 ref={heading} tabIndex={-1}>{exercise.title}</h2>
              <span className="muted small">Exercise {index + 1} of {exercises.length}</span>
            </div>
            <div className="trainer-grid">
              <div className="trainer-steps">
                <ol className="steps">{exercise.steps.map((s, i) => <li key={i}>{s}</li>)}</ol>
                {exercise.tip && <p className="trainer-tip"><Lightbulb size={16} aria-hidden="true" /> {exercise.tip}</p>}
                {(exercise.kind === "SAY" || exercise.kind === "READ" || exercise.kind === "SPEAK") && exercise.texts.length > 0 && (
                  <TextCard exercise={exercise} at={textIndex} onAt={setTextIndex} />
                )}
              </div>
              <div className="trainer-live">
                {exercise.kind === "MATCH" ? (
                  <MatchPanel key={`${exercise.id}-${attempt}`} exercise={exercise} plan={plan} mic={mic} trace={trace}
                    onStart={() => startExercise()} onCollect={(on) => { matchCollect.current = on; }} onDone={(m) => { matchCollect.current = true; void finish(m); }} />
                ) : exercise.kind !== "GUIDED" && (
                  <LiveMeters exercise={exercise} mic={mic} trace={trace} plan={plan} spread={spread} pushing={pushing} />
                )}
                {exercise.kind === "GUIDED" && <div className="trainer-breath" aria-hidden="true"><span className={running ? "is-on" : ""} /></div>}
              </div>
            </div>
            {pushing && !exercise.meters.includes("LOUDNESS") && <p className="notice voice-push" role="status">You've been quite loud for a while. Try easing off: the microphone hears you well at a comfortable level.</p>}
            <Announcer text={announcement} />
          </section>
        )}

        {phase === "review" && review && exercise && (
          <section className="trainer-review">
            <h2 ref={heading} tabIndex={-1}>{exercise.title}: done</h2>
            <ul className="plain-list highlights">{review.lines.map((l, i) => <li key={i}>{l}</li>)}</ul>
            <ResultStats result={review.result} />
            {exercise.id === BASELINE_ID && review.result.pitchAvgHz != null && <StartingPoint result={review.result} plan={plan} onPlanChanged={onPlanChanged} />}
            <div className="field">
              <span className="field-label" id="feel-label">How did that feel?</span>
              <div className="scale feelings" role="radiogroup" aria-labelledby="feel-label">
                {FEELINGS.map((f, i) => (
                  <button key={f} type="button" role="radio" aria-checked={review.result.feeling === i + 1} className={review.result.feeling === i + 1 ? "is-on" : ""}
                    onClick={() => setReview({ ...review, result: { ...review.result, feeling: review.result.feeling === i + 1 ? undefined : i + 1 } })}>{f}</button>
                ))}
              </div>
            </div>
            <div className="field">
              <span className="field-label" id="strain-label">Any strain or discomfort?</span>
              <div className="scale" role="radiogroup" aria-labelledby="strain-label">
                <button type="button" role="radio" aria-checked={strain === false} className={strain === false ? "is-on" : ""} onClick={() => setStrain(false)}>No, it felt fine</button>
                <button type="button" role="radio" aria-checked={strain === true} className={strain === true ? "is-on" : ""} onClick={() => { setStrain(true); setAnyStrain(true); }}>A little, yes</button>
              </div>
            </div>
            {strain === true && <p className="notice rest-note" role="status">{REST_NOTE}</p>}
            <div className="button-row">
              {strain === true ? <>
                <Button variant="primary" onClick={() => { const all = [...results.filter((x) => x.exerciseId !== review.result.exerciseId), review.result]; void save(all, true, index + 1 < exercises.length); }}><Check size={18} /> Finish for today</Button>
                {index + 1 < exercises.length && <Button variant="ghost" onClick={() => nextExercise(review.result)}>Carry on gently</Button>}
              </> : <>
                <Button variant="primary" onClick={() => nextExercise(review.result)}>
                  {index + 1 < exercises.length ? <>Next: {exercises[index + 1].title} <ArrowRight size={18} /></> : <><Check size={18} /> Finish</>}
                </Button>
                <Button variant="ghost" onClick={again}><RotateCcw size={16} /> Do it again</Button>
              </>}
            </div>
          </section>
        )}

        {phase === "done" && (
          <section className="trainer-done">
            <h2 ref={heading} tabIndex={-1}>{anyStrain ? "That's enough for today" : results.length === exercises.length ? "All done. Well done, you." : "Saved what you did"}</h2>
            <SessionSummary results={results} seconds={totalSeconds.current} />
            {anyStrain && <p className="notice rest-note">{REST_NOTE}</p>}
            {saving === "saving" && <p className="muted"><Spinner size={16} /> Saving…</p>}
            {saving === "saved" && <p className="muted small"><Check size={14} /> Saved. Only the numbers were kept{keep ? "; recordings stay in this browser" : ""}.</p>}
            {saving === "failed" && <Button onClick={() => void save(results, anyStrain)}>Try saving again</Button>}
            <div className="button-row"><Button variant="primary" onClick={onClose}>Back to voice training</Button></div>
          </section>
        )}
      </main>

      {phase === "exercise" && exercise && exercise.kind !== "MATCH" && (
        <footer className="trainer-controls">
          <div className="trainer-timer" aria-live="off">
            <span className="trainer-time">{timed ? clock(remaining) : clock(elapsed)}</span>
            <span className="muted small">{timed ? (running ? "left" : started ? "paused" : `${clock(exercise.seconds)} in all`) : started ? "so far" : `about ${Math.max(1, Math.round(exercise.seconds / 60))} min`}</span>
          </div>
          <div className="button-row">
            {!running ? (
              <Button variant="primary" onClick={startExercise}><Play size={18} /> {started ? "Carry on" : "Start"}</Button>
            ) : (
              <Button variant="tonal" onClick={() => run(false)}><Pause size={18} /> Pause</Button>
            )}
            {started && <Button variant="ghost" onClick={() => void finish()}>{timed ? "I'm done" : "Finish"}</Button>}
            {!started && <Button variant="ghost" onClick={() => { resetClock(); void finish(); }}><SkipForward size={16} /> Skip</Button>}
          </div>
        </footer>
      )}

      {leaving && (
        <Modal title="Leave the lesson?" onClose={() => setLeaving(false)} footer={<>
          <Button variant="ghost" onClick={() => setLeaving(false)}>Keep going</Button>
          {results.length > 0 && <Button variant="tonal" onClick={() => { setLeaving(false); void save(results, anyStrain, true); }}>Save what I did</Button>}
          <Button variant={results.length > 0 ? "ghost" : "primary"} onClick={() => { mic.stop(); onClose(); }}>Leave</Button>
        </>}>
          <p>{results.length > 0 ? `You've done ${results.length} of ${exercises.length}. You can keep that as practice, or leave without saving.` : "Nothing's been saved yet. That's fine: come back whenever you like."}</p>
        </Modal>
      )}
    </div>
  );
}

/** The words, phrases, passages or prompts of SAY, READ and SPEAK, one at a time. */
function TextCard({ exercise, at, onAt }: { exercise: VoiceExercise; at: number; onAt: (i: number) => void }) {
  const texts = exercise.texts;
  const i = Math.min(at, texts.length - 1);
  // A long text is a passage to read, whatever the exercise (the starting recording has one, then a prompt).
  const long = texts[i].length > 160;
  const label = long || exercise.kind === "READ" ? "Read aloud" : exercise.kind === "SPEAK" ? "Prompt" : "Say";
  return (
    <div className={`trainer-text kind-${exercise.kind.toLowerCase()} ${long ? "is-long" : ""}`}>
      <div className="trainer-text-head">
        <span className="muted small">{label}{texts.length > 1 ? ` · ${i + 1} of ${texts.length}` : ""}</span>
        {texts.length > 1 && (
          <span className="button-row">
            <IconButton label={exercise.kind === "SPEAK" ? "The prompt before" : "The one before"} disabled={i === 0} onClick={() => onAt(i - 1)}><ChevronLeft size={20} /></IconButton>
            <IconButton label={exercise.kind === "SPEAK" ? "Another prompt" : "The next one"} disabled={i === texts.length - 1} onClick={() => onAt(i + 1)}><ChevronRight size={20} /></IconButton>
          </span>
        )}
      </div>
      <p className="trainer-say" aria-live="polite">{texts[i]}</p>
    </div>
  );
}

/** The meters an exercise asks for, live. */
function LiveMeters({ exercise, mic, trace, plan, spread, pushing }: {
  exercise: VoiceExercise; mic: MicHandle; trace: React.RefObject<TracePoint[]>; plan: VoicePlan; spread: number | null; pushing: boolean;
}) {
  if (mic.status !== "on") {
    return (
      <div className="panel-card meters-off">
        <p className="muted">{mic.status === "problem" && mic.problem ? MIC_PROBLEM_TEXT[mic.problem] : "The meters are resting: the microphone is off. The steps and the timer work just the same."}</p>
        {mic.status !== "problem" || mic.problem !== "insecure" ? <div><Button variant="tonal" busy={mic.status === "starting"} onClick={() => void mic.start()}><Mic size={16} /> Turn on the microphone</Button></div> : null}
      </div>
    );
  }
  const meters: VoiceMeter[] = exercise.meters.length ? exercise.meters : ["PITCH"];
  const now = mic.now;
  return (
    <div className="voice-meters">
      {meters.includes("PITCH") && <PitchTrace now={now} trace={trace} band={rangeOf(plan)} />}
      <div className="voice-meter-grid">
        {meters.includes("RESONANCE") && <ResonanceMeter value={now?.resonance} aim={plan.targetResonance} />}
        {meters.includes("WEIGHT") && <WeightMeter value={now?.weight} />}
        {meters.includes("INTONATION") && <IntonationMeter spread={spread} />}
        {meters.includes("LOUDNESS") && <LoudnessMeter db={now?.db ?? null} pushing={pushing} />}
      </div>
    </div>
  );
}

/** MATCH: the app plays a note, the person sings or hums it back; within a semitone counts. */
function MatchPanel({ exercise, plan, mic, trace, onStart, onCollect, onDone }: {
  exercise: VoiceExercise; plan: VoicePlan; mic: MicHandle; trace: React.RefObject<TracePoint[]>;
  onStart: () => void; onCollect: (on: boolean) => void; onDone: (m: { matched: number; of: number }) => void;
}) {
  const middle = middleHz(plan) ?? 165;
  const targets = useMemo(() => (exercise.notes.length ? exercise.notes : [0, 2, 4, 2, 0]).map((n) => shiftHz(middle, n)), [exercise.notes, middle]);
  const [i, setI] = useState(0);
  const [state, setState] = useState<"ready" | "listen" | "sing" | "matched" | "missed">("ready");
  const matched = useRef(0);
  const hold = useRef(0);
  const stateRef = useRef(state);
  stateRef.current = state;
  const playing = useRef<Playing | null>(null);
  const listening = mic.status === "on";
  const target = targets[i];
  // The trainer re-renders with every animation frame: its callbacks are read from here, so the timers below keep running.
  const calls = useRef({ onCollect, onDone });
  calls.current = { onCollect, onDone };

  const play = useCallback(async (n: number) => {
    playing.current?.stop();
    setState("listen");
    calls.current.onCollect(false);
    try {
      playing.current = await playTone(targets[n], 1.2);
      await playing.current.ended;
    } catch {
      // No sound out: carry on to the singing.
    }
    // A moment for the speaker's sound to fade, so the microphone doesn't hear the note itself.
    await new Promise((r) => setTimeout(r, 250));
    hold.current = 0;
    if (stateRef.current === "listen") { setState("sing"); calls.current.onCollect(true); }
  }, [targets]);

  useEffect(() => () => playing.current?.stop(), []);

  useEffect(() => mic.subscribe((_, smooth) => {
    if (stateRef.current !== "sing") return;
    for (const s of smooth) {
      if (s.pitchHz && Math.abs(semitones(target, s.pitchHz)) <= 1) hold.current += 0.02;
      else hold.current = Math.max(0, hold.current - 0.01);
    }
    if (hold.current >= 0.4) { matched.current++; setState("matched"); }
  }), [mic.subscribe, target]);

  // Seven seconds to find each note; then the next, whichever way it went.
  useEffect(() => {
    if (state !== "sing" || !listening) return;
    const id = setTimeout(() => setState("missed"), 7000);
    return () => clearTimeout(id);
  }, [state, i, listening]);

  const next = useCallback(() => {
    calls.current.onCollect(false);
    if (i + 1 < targets.length) { setI(i + 1); void play(i + 1); } else calls.current.onDone({ matched: matched.current, of: targets.length });
  }, [i, targets.length, play]);

  useEffect(() => {
    if (state !== "matched" && state !== "missed") return;
    const id = setTimeout(next, 1100);
    return () => clearTimeout(id);
  }, [state, next]);

  const now = listening ? mic.now : null;
  const off = now?.pitchHz ? semitones(target, now.pitchHz) : null;
  const hint = off == null ? null
    : Math.abs(off) <= 1 ? "Right there. Hold it…"
      : Math.abs(Math.abs(off) - 12) <= 1 ? `That's the same note an octave ${off > 0 ? "higher" : "lower"}. Try it ${off > 0 ? "lower" : "higher"}.`
        : off < 0 ? "A little higher" : "A little lower";
  const words = { ready: "Press Start to hear the first note.", listen: "Listen…", sing: listening ? "Your turn: sing or hum it back." : "Sing or hum it back, then go on.", matched: "Matched!", missed: "Let's try the next one." }[state];

  return (
    <div className="match">
      <div className="match-note" aria-live="polite">
        <span className="muted small">Note {i + 1} of {targets.length}</span>
        <strong>{noteName(target)}</strong>
        <span className="muted">{Math.round(target)} Hz</span>
        <span className={`match-state is-${state}`}>{state === "matched" && <Check size={18} />}{words}</span>
        {state === "sing" && hint && <span className="match-hint">{hint}</span>}
      </div>
      {listening && <PitchTrace now={now} trace={trace} band={{ low: shiftHz(target, -1), high: shiftHz(target, 1) }} bandName="the note" target={target} label="Your note" height={150} />}
      {!listening && <p className="muted small">With the microphone off we can't check the notes, so just listen and sing along.</p>}
      <div className="button-row">
        {state === "ready" ? (
          <Button variant="primary" onClick={() => { onStart(); void play(0); }}><Play size={18} /> Start</Button>
        ) : <>
          <Button variant="tonal" disabled={state === "listen"} onClick={() => void play(i)}><Volume2 size={18} /> Play it again</Button>
          <Button variant="ghost" onClick={() => { setState("missed"); }} disabled={state === "matched" || state === "missed" || state === "listen"}>
            <SkipForward size={16} /> {listening ? "Skip this note" : "Next note"}
          </Button>
        </>}
      </div>
    </div>
  );
}

/** After the starting recording: keep it as the plan's starting point, if the person likes. */
function StartingPoint({ result, plan, onPlanChanged }: { result: ExerciseResult; plan: VoicePlan; onPlanChanged?: (p: VoicePlan) => void }) {
  const [state, setState] = useState<"ask" | "saving" | "kept">("ask");
  const keepIt = async () => {
    setState("saving");
    try {
      const saved = await api.saveVoicePlan({ ...plan, baselinePitchHz: result.pitchAvgHz, baselineResonance: result.resonance, baselineAt: Date.now() });
      onPlanChanged?.(saved);
      setState("kept");
    } catch (e) {
      errorToast(e);
      setState("ask");
    }
  };
  if (state === "kept") return <p className="muted small"><Check size={14} /> Kept as your starting point.</p>;
  return (
    <div className="notice starting-point">
      <p>{plan.baselinePitchHz ? `Your starting point is ${hzText(plan.baselinePitchHz)} from before. Use today's instead?` : "Keep this as your starting point, to look back on later?"}</p>
      <div className="button-row"><Button variant="tonal" className="btn-small" busy={state === "saving"} onClick={() => void keepIt()}>{plan.baselinePitchHz ? "Use today's" : "Keep it"}</Button></div>
    </div>
  );
}

function ResultStats({ result: r }: { result: ExerciseResult }) {
  const stats = [
    r.pitchAvgHz != null && <Stat key="p" label="Average pitch" value={`${Math.round(r.pitchAvgHz)} Hz`} sub={noteName(r.pitchAvgHz)} />,
    r.inRange != null && <Stat key="r" label="In your range" value={r.inRange} unit="%" />,
    r.resonance != null && <Stat key="res" label="Resonance" value={Math.round(r.resonance)} sub={resonanceWord(r.resonance)} />,
    r.weight != null && <Stat key="w" label="Vocal weight" value={Math.round(r.weight)} sub={weightWord(r.weight)} />,
    r.intonation != null && <Stat key="i" label="Intonation" value={r.intonation.toFixed(1)} unit="st" sub={intonationWord(r.intonation)} />,
    r.of != null && <Stat key="m" label="Notes matched" value={`${r.matched ?? 0} of ${r.of}`} />,
    <Stat key="t" label="Time" value={clock(r.seconds)} />,
  ].filter(Boolean);
  return <div className="stats">{stats}</div>;
}

function SessionSummary({ results, seconds }: { results: ExerciseResult[]; seconds: number }) {
  const pitch = results.map((r) => r.pitchAvgHz).filter((v): v is number => v != null);
  const ranged = results.map((r) => r.inRange).filter((v): v is number => v != null);
  const res = results.map((r) => r.resonance).filter((v): v is number => v != null);
  const avg = (v: number[]) => v.reduce((a, b) => a + b, 0) / v.length;
  return (
    <div className="stats">
      <Stat label="Time" value={`${Math.max(1, Math.round(seconds / 60))} min`} sub={`${results.length} ${results.length === 1 ? "exercise" : "exercises"}`} />
      {pitch.length > 0 && <Stat label="Average pitch" value={hzText(avg(pitch)).split(" (")[0]} sub={noteName(avg(pitch))} />}
      {ranged.length > 0 && <Stat label="In your range" value={Math.round(avg(ranged))} unit="%" />}
      {res.length > 0 && <Stat label="Resonance" value={Math.round(avg(res))} sub={resonanceWord(avg(res))} />}
    </div>
  );
}
