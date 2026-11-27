import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft, Pause, Play, Square } from "lucide-react";
import { api } from "../lib/api";
import { F, setField, useFields } from "../lib/fields";
import {
  FELT_WORDS, PACER_WORDS, START, advance, afterWords, buildTimeline, clockText, isDone, loggedMinutes, pacerAt, splitSentences, timeLeft,
  totalSeconds, worthLogging, type Position,
} from "../lib/meditation";
import { audioContext, chime, chooseTextOnly, hush, speak, speechSupported, textOnlyChosen, useLocalVoice, useReducedMotion, useWakeLock } from "../lib/speech";
import type { Meditation, MeditationSession } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { loggedSomething } from "../components/LogSheet";
import { Spinner, errorToast, useLoad } from "../components/ui";
import { loadMeditations } from "./Meditation";

/*
 * Playing a guided meditation (/meditation/play/<id>), filling the screen: always dark and dim,
 * whatever the theme. For each step its words show, large and calm, and are read aloud by a voice
 * on this device (never an online one; text only where there's none, or by choice); then the
 * breathing pacer, a slowly growing and shrinking circle ("Breathe in", "Breathe out"; just the
 * words for anyone who'd rather less moved); then quiet. A soft chime at the start and the end,
 * made right here. Pause, carry on or stop, with the time left; the screen stays on.
 *
 * At the end (or stopped after a minute or more), the session is logged; then, while the field
 * shows, a gentle "How do you feel?" that can be skipped.
 */

const TICK_MS = 200;
/** Between sentences, a breath's quiet. */
const SENTENCE_GAP_MS = 350;
/** How long past its expected length the voice may run before it's taken as stuck (the browser lost its "done"). */
const VOICE_GRACE_S = 12;

type Phase = "ready" | "playing" | "ended";

export function MeditationPlayer() {
  const location = useLocation();
  const navigate = useNavigate();
  const readOnly = useReadOnly();
  const id = decodeURIComponent(location.pathname.split("/")[3] ?? "");
  const list = useLoad(() => loadMeditations(), []);
  const meditation = list.data?.find((m) => m.id === id);

  const leave = useCallback(() => (location.key !== "default" ? navigate(-1) : navigate("/meditation", { replace: true })), [location.key, navigate]);

  if (readOnly) return <Navigate to="/meditation" replace />;
  return (
    <div className="med-player" role="main" aria-label="Meditation">
      {meditation ? <Player key={meditation.id} meditation={meditation} onLeave={leave} />
        : list.error ? <Gone text="This meditation couldn't load. Check the connection, then try again." onLeave={leave} />
        : list.data ? <Gone text="That meditation isn't here any more." onLeave={leave} />
        : <div className="med-center"><Spinner size={28} /></div>}
    </div>
  );
}

function Gone({ text, onLeave }: { text: string; onLeave: () => void }) {
  return (
    <>
      <div className="med-top"><BackButton onClick={onLeave} /></div>
      <div className="med-center"><p className="med-say is-small">{text}</p></div>
    </>
  );
}

function BackButton({ onClick, label = "Back" }: { onClick: () => void; label?: string }) {
  return <button type="button" className="med-icon-btn" onClick={onClick} aria-label={label} title={label}><ArrowLeft size={22} /></button>;
}

function Player({ meditation, onLeave }: { meditation: Meditation; onLeave: () => void }) {
  const timeline = useMemo(() => buildTimeline(meditation), [meditation]);
  const { voice, ready: voicesReady } = useLocalVoice();
  const reduced = useReducedMotion();
  const { has } = useFields();
  const [phase, setPhase] = useState<Phase>("ready");
  const [running, setRunning] = useState(false);
  const [pos, setPos] = useState<Position>(START);
  const [textOnly, setTextOnly] = useState(textOnlyChosen);
  const [finished, setFinished] = useState(false);
  const voiceOn = !textOnly && !!voice;

  // The engine's own state, read by its timers.
  const posRef = useRef<Position>(START);
  const spent = useRef(0);
  const last = useRef(0);
  const startedAt = useRef(0);
  const sentence = useRef(0);
  const spokenFor = useRef(-1);
  const wordsDone = useRef(false);
  const overrun = useRef(0);
  const stopVoice = useRef<(() => void) | null>(null);
  const gap = useRef<number | undefined>(undefined);
  const audio = useRef<AudioContext | null>(null);
  const runningRef = useRef(false);
  const voiceOnRef = useRef(voiceOn);
  voiceOnRef.current = voiceOn;
  // The timers read the voice as it is now (the browser may list its voices a moment late).
  const voiceRef = useRef(voice);
  voiceRef.current = voice;
  const logged = useRef(false);
  const phaseRef = useRef<Phase>("ready");
  phaseRef.current = phase;

  // ---- Logging ----
  const [saved, setSaved] = useState<MeditationSession | null>(null);
  const [saveFailed, setSaveFailed] = useState(false);
  const session = (done: boolean): MeditationSession => ({ startedAt: startedAt.current, minutes: loggedMinutes(spent.current), finished: done, meditationId: meditation.id });
  const log = useCallback(async (done: boolean) => {
    if (logged.current) return;
    logged.current = true;
    setSaveFailed(false);
    try {
      setSaved(await api.saveMeditationSession(session(done)));
      loggedSomething();
    } catch {
      logged.current = false;
      setSaveFailed(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [meditation.id]);

  // ---- The voice ----
  const silence = () => {
    stopVoice.current?.();
    stopVoice.current = null;
    window.clearTimeout(gap.current);
    hush();
  };
  const readSentence = () => {
    const voice = voiceRef.current;
    if (!voice) { wordsDone.current = true; return; }
    const seg = timeline[posRef.current.index];
    if (seg?.kind !== "say") return;
    const parts = splitSentences(meditation.steps[seg.step].say);
    if (sentence.current >= parts.length) { wordsDone.current = true; return; }
    const index = posRef.current.index;
    stopVoice.current = speak(parts[sentence.current], voice, () => {
      stopVoice.current = null;
      if (posRef.current.index !== index) return;
      sentence.current++;
      if (sentence.current >= parts.length) { wordsDone.current = true; return; }
      gap.current = window.setTimeout(() => { if (runningRef.current && posRef.current.index === index) readSentence(); }, SENTENCE_GAP_MS);
    });
  };

  // ---- The clock ----
  const finish = () => {
    runningRef.current = false;
    setRunning(false);
    silence();
    setFinished(true);
    setPhase("ended");
    chime(audio.current, "end");
    void log(true);
  };

  const tick = () => {
    const now = performance.now();
    // (A long gap, a laptop asleep say, counts for at most a minute and a half.)
    const dt = Math.min(90, (now - last.current) / 1000);
    last.current = now;
    spent.current += dt;
    let p = posRef.current;
    const before = p.index;
    const seg = timeline[p.index];
    if (seg?.kind === "say" && voiceOnRef.current) {
      if (wordsDone.current) p = afterWords(timeline, p);
      else {
        const next = advance(timeline, p, dt, true);
        overrun.current += Math.max(0, p.elapsed + dt - seg.seconds);
        p = next;
        // The browser never said it was done: go on rather than wait for ever.
        if (overrun.current > seg.seconds + VOICE_GRACE_S) { silence(); p = afterWords(timeline, p); }
      }
    } else {
      p = advance(timeline, p, dt, voiceOnRef.current);
    }
    if (p.index !== before) { sentence.current = 0; wordsDone.current = false; overrun.current = 0; spokenFor.current = -1; }
    posRef.current = p;
    if (isDone(timeline, p)) { setPos(p); finish(); return; }
    const now2 = timeline[p.index];
    if (now2.kind === "say" && voiceOnRef.current && spokenFor.current !== p.index) {
      spokenFor.current = p.index;
      readSentence();
    }
    setPos(p);
  };

  useEffect(() => {
    if (!running) return;
    last.current = performance.now();
    const timer = window.setInterval(tick, TICK_MS);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running]);

  const begin = () => {
    audio.current = audioContext();
    chime(audio.current, "start");
    startedAt.current = Date.now();
    spent.current = 0;
    posRef.current = START;
    setPos(START);
    setPhase("playing");
    // A moment for the chime before the first words.
    window.setTimeout(() => { if (phaseRef.current !== "ended") resume(); }, 1200);
  };
  const pause = () => {
    runningRef.current = false;
    setRunning(false);
    silence();
    // The sentence it stopped in is read again from its start.
    spokenFor.current = -1;
  };
  const resume = () => {
    if (runningRef.current) return;
    if (speechSupported()) hush();
    runningRef.current = true;
    setRunning(true);
  };
  const stop = () => {
    pause();
    if (worthLogging(spent.current, false)) {
      setPhase("ended");
      void log(false);
    } else onLeave();
  };
  const toggleVoice = (text: boolean) => {
    setTextOnly(text);
    chooseTextOnly(text);
    voiceOnRef.current = !text && !!voiceRef.current;
    if (text) silence();
    // Back to the voice: it reads the sentence it's at.
    spokenFor.current = -1;
    wordsDone.current = false;
  };

  // Leaving partway (the browser's back, a link): keep a session of a minute or more, and stop the voice.
  useEffect(() => () => {
    silence();
    if (phaseRef.current === "playing" && !logged.current && worthLogging(spent.current, false)) {
      logged.current = true;
      void api.saveMeditationSession(session(false)).then(loggedSomething, () => undefined);
    }
    const ctx = audio.current;
    window.setTimeout(() => void ctx?.close().catch(() => undefined), 4500);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Space pauses and carries on.
  useEffect(() => {
    if (phase !== "playing") return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== " " || (e.target as HTMLElement)?.closest("button, input, select, textarea")) return;
      e.preventDefault();
      if (runningRef.current) pause(); else resume();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase]);

  useWakeLock(phase === "playing" && running);

  const seg = timeline[Math.min(pos.index, timeline.length - 1)];
  const say = seg ? meditation.steps[seg.step].say : "";
  const left = phase === "ready" ? totalSeconds(timeline) : timeLeft(timeline, pos);
  const noVoice = !speechSupported() || (voicesReady && !voice);

  if (phase === "ready") {
    return (
      <>
        <div className="med-top">
          <BackButton onClick={onLeave} />
          {voice && <VoiceSwitch textOnly={textOnly} onChange={toggleVoice} />}
        </div>
        <div className="med-center med-ready">
          <p className="med-kicker">{meditation.minutes} min</p>
          <h1 className="med-title">{meditation.title}</h1>
          <p className="med-about">{meditation.about}</p>
          <button type="button" className="med-begin" onClick={begin}><Play size={22} aria-hidden="true" /> Begin</button>
          <p className="med-voice-note">
            {noVoice ? "This device has no offline voice, so the words show here to read along. Nothing is sent anywhere for a voice."
              : voice && !textOnly ? `Read by ${voice.name}, a voice on this device. Find a comfortable spot, and turn the sound to low.`
              : voice ? "Text only: the words show here to read along." : ""}
          </p>
        </div>
      </>
    );
  }

  if (phase === "ended") {
    return (
      <Ended finished={finished} saved={saved} saveFailed={saveFailed} ask={has(F.MEDITATION_FELT_AFTER)} onRetry={() => void log(finished)} onDone={onLeave} />
    );
  }

  const pacer = seg?.kind === "breath" && seg.breath ? pacerAt(seg.breath, pos.elapsed) : null;
  return (
    <>
      <div className="med-top">
        <BackButton onClick={stop} label="Stop and go back" />
        <span className="med-top-title">{meditation.title}</span>
        {voice ? <VoiceSwitch textOnly={textOnly} onChange={toggleVoice} /> : <span className="med-top-spacer" />}
      </div>
      <div className="med-center">
        <p key={seg?.step} className={`med-say ${pacer ? "is-small" : ""}`} aria-live="polite">{say}</p>
        {pacer && (
          <div className={`med-pacer ${reduced ? "is-still" : ""}`} aria-live="polite">
            {!reduced && <span className="med-circle" style={{ transform: `scale(${pacer.scale})` }} aria-hidden="true" />}
            <span className="med-pacer-words">
              <strong>{PACER_WORDS[pacer.phase]}</strong>
              <small>{reduced ? `${pacer.secondsLeft} · breath ${pacer.round} of ${pacer.rounds}` : `breath ${pacer.round} of ${pacer.rounds}`}</small>
            </span>
          </div>
        )}
        {noVoice && <p className="med-voice-note">Text only: there's no offline voice on this device.</p>}
      </div>
      <div className="med-bottom">
        <span className="med-left" aria-label={`${clockText(left)} left`}>{clockText(left)} left</span>
        <div className="med-controls">
          {running
            ? <button type="button" className="med-ctl" onClick={pause}><Pause size={20} aria-hidden="true" /> Pause</button>
            : <button type="button" className="med-ctl" onClick={resume}><Play size={20} aria-hidden="true" /> Carry on</button>}
          <button type="button" className="med-ctl is-quiet" onClick={stop}><Square size={18} aria-hidden="true" /> Stop</button>
        </div>
      </div>
    </>
  );
}

/** Voice or text only, remembered in this browser. */
function VoiceSwitch({ textOnly, onChange }: { textOnly: boolean; onChange: (textOnly: boolean) => void }) {
  return (
    <div className="med-switch" role="radiogroup" aria-label="Read aloud">
      <button type="button" role="radio" aria-checked={!textOnly} className={!textOnly ? "is-on" : ""} onClick={() => onChange(false)}>Voice</button>
      <button type="button" role="radio" aria-checked={textOnly} className={textOnly ? "is-on" : ""} onClick={() => onChange(true)}>Text only</button>
    </div>
  );
}

/** The end: logged, then (while the field shows) how it feels now, which can be skipped. */
function Ended({ finished, saved, saveFailed, ask, onRetry, onDone }: {
  finished: boolean; saved: MeditationSession | null; saveFailed: boolean; ask: boolean; onRetry: () => void; onDone: () => void;
}) {
  const [felt, setFelt] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const answer = async (n: number) => {
    if (!saved?.id) return;
    setBusy(true);
    try {
      await api.updateMeditationSession(saved.id, { ...saved, feltAfter: n });
      setFelt(n);
      loggedSomething();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const asking = ask && saved?.id && felt == null;
  return (
    <>
      <div className="med-top"><BackButton onClick={onDone} label="Done" /></div>
      <div className="med-center med-ended">
        <h1 className="med-title">{finished ? "That's the end. Rest well." : "Stopped. Every quiet minute counts."}</h1>
        {saveFailed && (
          <p className="med-voice-note">Couldn't save this one just now. <button type="button" className="med-link" onClick={onRetry}>Try again</button></p>
        )}
        {asking && (
          <section className="med-felt" aria-labelledby="med-felt-q">
            <h2 id="med-felt-q">How do you feel?</h2>
            <div className="med-felt-options">
              {FELT_WORDS.map((w, i) => (
                <button key={w} type="button" disabled={busy} onClick={() => void answer(i + 1)} aria-label={`${i + 1} of 5: ${w}`}>
                  <span className="med-felt-n" aria-hidden="true">{i + 1}</span>
                  <span>{w}</span>
                </button>
              ))}
            </div>
            <div className="med-felt-foot">
              <button type="button" className="med-link" onClick={onDone}>Skip</button>
              <button type="button" className="med-link" onClick={() => { void setField(F.MEDITATION_FELT_AFTER, false).catch(errorToast); onDone(); }}>Don't ask again</button>
            </div>
          </section>
        )}
        {felt != null && <p className="med-about">Thank you. Noted as “{FELT_WORDS[felt - 1].toLowerCase()}”.</p>}
        {!asking && <button type="button" className="med-begin" onClick={onDone}>Done</button>}
      </div>
    </>
  );
}
