import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Check, HeartHandshake, Mic, RotateCcw } from "lucide-react";
import { api } from "../../lib/api";
import { useSession } from "../../lib/session";
import { VOICE_GOALS, type VoiceGoal, type VoicePlan, type VoiceProgram } from "../../lib/types";
import { noteName, summarise, type VoiceFrame } from "../../lib/voice/dsp";
import { MIC_PROBLEM_TEXT, useMic, useWakeLock } from "../../lib/voice/mic";
import { HEALTH_NOTES, baselineTexts, hzText, planProblem, resonanceWord, suggestion } from "../../lib/voice/program";
import { saveRecording, useKeepRecordings } from "../../lib/voice/recordings";
import { Button, Field, Toggle, errorToast, toast } from "../ui";
import { PitchTrace, ResonanceMeter, usePitchTrace } from "./Meters";

/*
 * Setting up voice training, once: a goal (in the person's words, with no assumptions), looking
 * after the voice, a baseline (a passage read aloud, then twenty seconds of talking), and gentle
 * targets for the goal, always editable. Saved with PUT /api/transition/voice/plan.
 */

type Step = "goal" | "health" | "baseline" | "targets";
const STEPS: Step[] = ["goal", "health", "baseline", "targets"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const SPEAK_SECONDS = 20;

export function VoiceSetup({ program, plan, start = "goal", onDone, onLater }: {
  program: VoiceProgram; plan: VoicePlan; start?: Step; onDone: (plan: VoicePlan) => void; onLater: () => void;
}) {
  const [step, setStep] = useState<Step>(start);
  const [draft, setDraft] = useState<VoicePlan>(plan);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const redo = start === "baseline";
  const steps = redo ? (["baseline", "targets"] as Step[]) : STEPS;
  const at = steps.indexOf(step);
  useEffect(() => { heading.current?.focus(); }, [step]);

  const pickGoal = (goal: VoiceGoal) => setDraft((d) => ({ ...d, goal }));
  const toTargets = (baseline?: { hz?: number; resonance?: number }) => {
    setDraft((d) => {
      const next = { ...d, ...(baseline ? { baselinePitchHz: baseline.hz, baselineResonance: baseline.resonance, baselineAt: Date.now() } : {}) };
      if (redo && d.targetLowHz) return next;
      const s = suggestion(next.goal, next.baselinePitchHz, next.baselineResonance);
      return { ...next, targetLowHz: s.low, targetHighHz: s.high, targetResonance: s.resonance };
    });
    setStep("targets");
  };

  const save = async () => {
    setBusy(true);
    try {
      const saved = await api.saveVoicePlan(draft);
      toast(redo ? "Saved your new baseline" : "Your plan is ready. Lovely to have you here.");
      onDone(saved);
    } catch (e) {
      errorToast(e);
      setBusy(false);
    }
  };

  return (
    <section className="voice-setup panel-card span-2" aria-labelledby="setup-title">
      <div className="setup-progress" aria-hidden="true">{steps.map((s, i) => <span key={s} className={i <= at ? "is-on" : ""} />)}</div>
      <p className="muted small">Step {at + 1} of {steps.length}</p>

      {step === "goal" && (
        <>
          <h2 id="setup-title" ref={heading} tabIndex={-1}>What would you like from your voice?</h2>
          <p className="muted">There's no right answer, and you can change it whenever you like.</p>
          <div className="goal-options" role="radiogroup" aria-label="Your goal">
            {VOICE_GOALS.map((g) => (
              <button key={g.id} type="button" role="radio" aria-checked={draft.goal === g.id} className={`goal-option ${draft.goal === g.id ? "is-on" : ""}`} onClick={() => pickGoal(g.id)}>
                <strong>{g.label}</strong>
                <span>{g.about}</span>
                {draft.goal === g.id && <Check size={18} className="goal-check" aria-hidden="true" />}
              </button>
            ))}
          </div>
          <div className="button-row">
            <Button variant="primary" onClick={() => setStep("health")}>Next <ArrowRight size={18} /></Button>
            <Button variant="ghost" onClick={onLater}>Look around first</Button>
          </div>
        </>
      )}

      {step === "health" && (
        <>
          <h2 id="setup-title" ref={heading} tabIndex={-1}><HeartHandshake size={22} aria-hidden="true" /> Looking after your voice</h2>
          <p className="muted">Your voice is yours to look after. A few things that keep practice kind to it:</p>
          <ul className="plain-list health-list">{(program.health.length ? program.health : HEALTH_NOTES).map((h, i) => <li key={i}>{h}</li>)}</ul>
          <div className="button-row">
            <Button variant="ghost" onClick={() => setStep("goal")}><ArrowLeft size={18} /> Back</Button>
            <Button variant="primary" onClick={() => setStep("baseline")}>Got it <ArrowRight size={18} /></Button>
          </div>
        </>
      )}

      {step === "baseline" && <Baseline program={program} heading={heading} onBack={redo ? onLater : () => setStep("health")} onDone={toTargets} onSkip={() => toTargets()} />}

      {step === "targets" && (
        <>
          <h2 id="setup-title" ref={heading} tabIndex={-1}>Your targets</h2>
          {draft.baselinePitchHz != null && (
            <p>Today your voice sits around <strong>{hzText(draft.baselinePitchHz)}</strong>{draft.baselineResonance != null ? <>, with resonance <strong>{Math.round(draft.baselineResonance)}</strong> ({resonanceWord(draft.baselineResonance)})</> : null}. That's your starting point, not a score.</p>
          )}
          <PlanFields plan={draft} onChange={setDraft} />
          <div className="button-row">
            <Button variant="ghost" onClick={() => setStep("baseline")}><ArrowLeft size={18} /> Back</Button>
            <Button variant="primary" busy={busy} disabled={!!planProblem(draft)} onClick={save}><Check size={18} /> {redo ? "Save" : "Save my plan"}</Button>
          </div>
        </>
      )}
    </section>
  );
}

/** The baseline: a passage read aloud, then talking freely for twenty seconds. */
function Baseline({ program, heading, onBack, onDone, onSkip }: {
  program: VoiceProgram; heading: React.RefObject<HTMLHeadingElement | null>; onBack: () => void; onDone: (b: { hz?: number; resonance?: number }) => void; onSkip: () => void;
}) {
  const user = useSession((s) => s.user)!;
  const [keep, setKeep] = useKeepRecordings(user.id);
  const mic = useMic();
  const trace = usePitchTrace(mic);
  const texts = baselineTexts(program);
  const [part, setPart] = useState<"intro" | "read" | "speak" | "result">("intro");
  const [left, setLeft] = useState(SPEAK_SECONDS);
  const [result, setResult] = useState<{ hz?: number; resonance?: number; seconds: number } | null>(null);
  const frames = useRef<VoiceFrame[]>([]);
  const collecting = useRef(false);
  useWakeLock(part === "read" || part === "speak");
  useEffect(() => { heading.current?.focus(); }, [part, heading]);
  useEffect(() => mic.subscribe((list) => { if (collecting.current) frames.current.push(...list); }), [mic.subscribe]);

  const begin = async () => {
    if (!(await mic.start())) return;
    frames.current = [];
    collecting.current = true;
    if (keep) mic.startRecording();
    setPart("read");
  };

  const finish = async () => {
    collecting.current = false;
    const s = summarise(frames.current);
    setResult({ hz: s.pitchAvgHz, resonance: s.resonance, seconds: s.voicedSeconds });
    setPart("result");
    if (keep) {
      const sound = await mic.stopRecording();
      if (sound && s.pitchAvgHz) {
        await saveRecording(user.id, { at: Date.now(), kind: "baseline", title: "Your baseline", pitchAvgHz: s.pitchAvgHz, resonance: s.resonance }, sound.pcm, sound.sampleRate)
          .catch(() => errorToast("Couldn't keep the recording in this browser."));
      }
    }
    mic.stop();
  };

  useEffect(() => {
    if (part !== "speak") return;
    setLeft(SPEAK_SECONDS);
    const started = Date.now();
    const id = setInterval(() => {
      const remaining = Math.max(0, SPEAK_SECONDS - Math.floor((Date.now() - started) / 1000));
      setLeft(remaining);
      if (remaining === 0) { clearInterval(id); void finish(); }
    }, 250);
    return () => clearInterval(id);
  }, [part]); // eslint-disable-line react-hooks/exhaustive-deps

  if (part === "intro") {
    return (
      <>
        <h2 id="setup-title" ref={heading} tabIndex={-1}>Where your voice is today</h2>
        <p>First, a short recording to start from: read a passage aloud, then talk about anything for twenty seconds. Use your everyday voice; there's nothing to get right.</p>
        <div className="mic-explain">
          <p className="small"><Mic size={14} aria-hidden="true" /> The trainer listens through your microphone. It all happens in this browser: no sound is sent anywhere, and only the numbers are saved.</p>
          <Toggle checked={keep} onChange={setKeep} label="Keep a recording to compare with later" help="Only in this browser, on this device. Never uploaded; you can delete it any time." />
        </div>
        {mic.status === "problem" && mic.problem && <p className="notice" role="alert">{MIC_PROBLEM_TEXT[mic.problem]}</p>}
        <div className="button-row">
          <Button variant="ghost" onClick={onBack}><ArrowLeft size={18} /> Back</Button>
          <Button variant="primary" busy={mic.status === "starting"} onClick={() => void begin()}><Mic size={18} /> {mic.status === "problem" ? "Try again" : "Turn on the microphone and begin"}</Button>
          <Button variant="ghost" onClick={onSkip}>Skip this for now</Button>
        </div>
      </>
    );
  }
  if (part === "result" && result) {
    return (
      <>
        <h2 id="setup-title" ref={heading} tabIndex={-1}>Your voice today</h2>
        {result.hz ? (
          <div className="stats">
            <div className="stat"><div className="stat-label">Pitch</div><div className="stat-value">{Math.round(result.hz)}<span className="stat-unit">Hz</span></div><div className="stat-sub">around {noteName(result.hz)}</div></div>
            {result.resonance != null && <div className="stat"><div className="stat-label">Resonance</div><div className="stat-value">{Math.round(result.resonance)}</div><div className="stat-sub">{resonanceWord(result.resonance)}</div></div>}
          </div>
        ) : <p className="notice">We didn't hear enough to measure this time. Try once more a little closer to the microphone, or skip it for now.</p>}
        <p className="muted">That's where you're starting from. Everything from here is about what feels like you.</p>
        <div className="button-row">
          <Button variant="ghost" onClick={() => setPart("intro")}><RotateCcw size={16} /> Do it again</Button>
          {result.hz ? <Button variant="primary" onClick={() => onDone({ hz: result.hz, resonance: result.resonance })}>Next <ArrowRight size={18} /></Button>
            : <Button variant="primary" onClick={onSkip}>Skip for now <ArrowRight size={18} /></Button>}
        </div>
      </>
    );
  }
  return (
    <>
      <h2 id="setup-title" ref={heading} tabIndex={-1}>{part === "read" ? "Read this aloud, at your own pace" : "Now just talk"}</h2>
      <p className="baseline-text">{part === "read" ? texts.passage : texts.prompt}</p>
      <div className="voice-meters">
        <PitchTrace now={mic.now} trace={trace} height={140} />
        <ResonanceMeter value={mic.now?.resonance} />
      </div>
      <div className="button-row">
        {part === "read" ? <Button variant="primary" onClick={() => setPart("speak")}>Done reading <ArrowRight size={18} /></Button>
          : <span className="baseline-left" aria-live="off"><strong>{left}</strong> seconds to go</span>}
        {part === "speak" && <Button variant="ghost" onClick={() => void finish()}>That's enough</Button>}
      </div>
    </>
  );
}

/** The plan's settings: goal, range, resonance aim, time a day, rest days and a reminder. */
export function PlanFields({ plan, onChange }: { plan: VoicePlan; onChange: (p: VoicePlan) => void }) {
  const s = suggestion(plan.goal, plan.baselinePitchHz, plan.baselineResonance);
  const number = (v: string) => (v.trim() === "" ? undefined : Number(v) || undefined);
  const problem = planProblem(plan);
  return (
    <div className="form-stack plan-fields">
      <div className="field">
        <span className="field-label" id="plan-goal">Your goal</span>
        <div className="tabs plan-goal" role="radiogroup" aria-labelledby="plan-goal">
          {VOICE_GOALS.map((g) => <button key={g.id} type="button" role="radio" aria-checked={plan.goal === g.id} className={plan.goal === g.id ? "is-active" : ""} onClick={() => onChange({ ...plan, goal: g.id })}>{g.label}</button>)}
        </div>
      </div>
      <div className="notice suggestion">
        <p>{s.why}</p>
        <p className="small">A gentle start for you: <strong>{s.low}–{s.high} Hz</strong> ({noteName(s.low)}–{noteName(s.high)}), resonance around <strong>{s.resonance}</strong>.</p>
        <div><Button variant="tonal" className="btn-small" onClick={() => onChange({ ...plan, targetLowHz: s.low, targetHighHz: s.high, targetResonance: s.resonance })}>Use these</Button></div>
      </div>
      <div className="two-col">
        <Field label="Range from (Hz)" help={plan.targetLowHz ? noteName(plan.targetLowHz) : undefined}>
          <input inputMode="numeric" value={plan.targetLowHz ?? ""} onChange={(e) => onChange({ ...plan, targetLowHz: number(e.target.value) })} />
        </Field>
        <Field label="Range to (Hz)" help={plan.targetHighHz ? noteName(plan.targetHighHz) : undefined}>
          <input inputMode="numeric" value={plan.targetHighHz ?? ""} aria-invalid={!!problem} onChange={(e) => onChange({ ...plan, targetHighHz: number(e.target.value) })} />
        </Field>
      </div>
      {problem && <p className="notice notice-error" role="alert">{problem}</p>}
      <Field label={`Resonance to aim for: ${plan.targetResonance != null ? `${Math.round(plan.targetResonance)}, ${resonanceWord(plan.targetResonance)}` : "none"}`} help="0 is darker, 100 brighter">
        <input type="range" min={0} max={100} step={1} value={plan.targetResonance ?? 50} onChange={(e) => onChange({ ...plan, targetResonance: Number(e.target.value) })}
          aria-valuetext={plan.targetResonance != null ? `${Math.round(plan.targetResonance)}, ${resonanceWord(plan.targetResonance)}` : undefined} />
      </Field>
      <div className="field">
        <span className="field-label" id="plan-minutes">Time a day</span>
        <div className="tabs" role="radiogroup" aria-labelledby="plan-minutes">
          {[5, 10, 15, 20].map((m) => <button key={m} type="button" role="radio" aria-checked={plan.dailyMinutes === m} className={plan.dailyMinutes === m ? "is-active" : ""} onClick={() => onChange({ ...plan, dailyMinutes: m })}>{m} min</button>)}
        </div>
      </div>
      <div className="field">
        <span className="field-label" id="plan-rest">Rest days</span>
        <div className="tag-list" role="group" aria-labelledby="plan-rest">
          {DAYS.map((d, i) => {
            const on = plan.restDays.includes(i + 1);
            return <button key={d} type="button" aria-pressed={on} className={`chip small ${on ? "is-on" : ""}`} onClick={() => onChange({ ...plan, restDays: on ? plan.restDays.filter((x) => x !== i + 1) : [...plan.restDays, i + 1].sort() })}>{d}</button>;
          })}
        </div>
        <span className="field-help">Your voice gets stronger while it rests: one or two days a week is kind to it.</span>
      </div>
      <Field label="A gentle reminder (optional)">
        <input type="time" value={plan.reminder ?? ""} onChange={(e) => onChange({ ...plan, reminder: e.target.value || undefined })} />
      </Field>
    </div>
  );
}
