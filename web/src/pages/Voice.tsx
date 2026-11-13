import { useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, BookOpen, Check, ChevronRight, HeartHandshake, Lock, Mic, Pencil, Play, RotateCcw, Sparkles, Sun, Target, TrendingUp } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, parseDate, shortDate, when } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import { VOICE_GOALS, VOICE_SKILLS, layoutKey, type ExerciseKind, type VoiceExercise, type VoiceLesson, type VoicePlan, type VoiceProgram, type VoiceProgress, type VoiceSkill } from "../lib/types";
import { noteName } from "../lib/voice/dsp";
import { HEALTH_NOTES, dailyRoutine, hzText, isRestDay, lessonExercises, lessonsFor, openUnits, planProblem, resonanceWord, suits, todaysLesson, units } from "../lib/voice/program";
import { BarChart, LineChart } from "../components/charts";
import { Button, Card, CardAction, ErrorNotice, Modal, PageHeader, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { Trainer } from "../components/voice/Trainer";
import { PlanFields, VoiceSetup } from "../components/voice/Setup";
import { RecordingsCard } from "../components/voice/Recordings";

/*
 * Voice training (in Transition): a free, guided programme with live feedback, for whichever
 * direction the person chooses. Today's lesson and the daily routine, progress week by week,
 * units and lessons, free practice, recordings kept only in this browser, and the plan. The
 * listening happens in this browser; only the numbers are saved.
 */

export const KIND_LABEL: Record<ExerciseKind, string> = {
  GUIDED: "Guided", HOLD: "Hold a sound", GLIDE: "Glides", MATCH: "Match notes", RESONANCE: "Resonance",
  SAY: "Words and phrases", READ: "Reading aloud", SPEAK: "Talking freely",
};
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const minutes = (seconds: number) => `${Math.max(1, Math.round(seconds / 60))} min`;
const skillLabel = (id: VoiceSkill) => VOICE_SKILLS.find((s) => s.id === id)?.label ?? id;

interface Session {
  title: string;
  lessonId?: string;
  exercises: VoiceExercise[];
}

export function VoicePage() {
  const readOnly = useReadOnly();
  const program = useLoad(() => api.voiceProgram(), []);
  const progress = useLoad(() => api.voiceProgress(), []);
  const [setup, setSetup] = useState<"first" | "baseline" | null>(null);
  const [later, setLater] = useState(false);
  const [training, setTraining] = useState<Session | null>(null);
  const [editing, setEditing] = useState(false);
  const p = program.data;
  const g = progress.data;
  const plan = g?.plan;
  const firstRun = !!plan && plan.baselineAt == null && plan.targetLowHz == null;
  const settingUp = !readOnly && !!p && !!plan && (setup != null || (firstRun && !later));

  const startLesson = (lesson: VoiceLesson) => {
    if (!p || !plan) return;
    const exercises = lessonExercises(p, lesson, plan.goal);
    if (exercises.length === 0) { toast("This lesson has nothing for your goal: try the next one."); return; }
    setTraining({ title: lesson.title, lessonId: lesson.id, exercises });
  };

  const sections: Section[] = p && g && plan ? [
    { id: "today", label: "Today", node: <TodayCard program={p} progress={g} readOnly={readOnly} firstRun={firstRun} onSetUp={() => setSetup("first")} onStart={startLesson} /> },
    { id: "routine", label: "Daily routine", node: <RoutineCard program={p} plan={plan} readOnly={readOnly} onStart={(exercises) => setTraining({ title: "Daily routine", exercises })} /> },
    { id: "plan", label: "Your plan", node: <PlanCard plan={plan} readOnly={readOnly} onEdit={() => setEditing(true)} onBaseline={() => setSetup("baseline")} /> },
    { id: "progress", label: "Progress", node: <ProgressCard progress={g} /> },
    { id: "lessons", label: "Units and lessons", node: <LessonsCard program={p} progress={g} readOnly={readOnly} onStart={startLesson} /> },
    { id: "practice", label: "Free practice", when: !readOnly, node: <PracticeCard program={p} plan={plan} onStart={(e) => setTraining({ title: "Free practice", exercises: [e] })} /> },
    { id: "health", label: "Looking after your voice", node: (
      <Card title="Looking after your voice" icon={<HeartHandshake size={18} />}>
        <ul className="plain-list">{(p.health.length ? p.health : HEALTH_NOTES).map((h, i) => <li key={i}>{h}</li>)}</ul>
      </Card>
    ) },
    { id: "recordings", label: "Recordings", when: !readOnly, node: <RecordingsCard /> },
  ] : [];
  const arranged = useArrange(layoutKey("voice"), "Voice training", sections);
  const error = program.error ?? progress.error;

  return (
    <div className="page voice-page">
      <PageHeader title="Voice training" subtitle="A free, gentle programme with live feedback, at your own pace" actions={<>
        <Link to="/transition" className="btn btn-ghost"><ArrowLeft size={18} /> Transition</Link>
        {!settingUp && arranged.button}
      </>} />
      {error && <ErrorNotice message={error} onRetry={() => { program.reload(); progress.reload(); }} />}
      {(!p || !g) && !error && <div className="center-pad"><Spinner size={28} /></div>}
      {settingUp ? (
        <div className="grid grid-2">
          <VoiceSetup program={p!} plan={plan!} start={setup === "baseline" ? "baseline" : "goal"}
            onLater={() => { setSetup(null); setLater(true); }}
            onDone={(saved) => { setSetup(null); progress.setData({ ...g!, plan: saved }); progress.reload(); }} />
        </div>
      ) : arranged.render()}
      {training && plan && (
        <Trainer title={training.title} lessonId={training.lessonId} exercises={training.exercises} plan={plan}
          onClose={() => setTraining(null)} onSaved={progress.reload} onPlanChanged={(saved) => progress.setData({ ...g!, plan: saved })} />
      )}
      {editing && plan && <PlanDialog plan={plan} onClose={() => setEditing(false)} onSaved={(saved) => { progress.setData({ ...g!, plan: saved }); progress.reload(); }} />}
    </div>
  );
}

function TodayCard({ program, progress, readOnly, firstRun, onSetUp, onStart }: {
  program: VoiceProgram; progress: VoiceProgress; readOnly: boolean; firstRun: boolean; onSetUp: () => void; onStart: (l: VoiceLesson) => void;
}) {
  const plan = progress.plan;
  const lesson = todaysLesson(program, progress);
  const rest = isRestDay(plan);
  const practiceDays = 7 - plan.restDays.length;
  const count = lesson ? lessonExercises(program, lesson, plan.goal).length : 0;
  const path = lessonsFor(program, plan.goal);
  const unit = lesson && program.units?.find((u) => u.unit === lesson.unit);
  return (
    <Card title="Today" icon={<Sparkles size={18} />} className="span-2 voice-today">
      {progress.restNote && <p className="notice rest-note" role="status">{progress.restNote}</p>}
      <div className="stats">
        <Stat label="Streak" value={progress.streakDays} unit={progress.streakDays === 1 ? "day" : "days"} sub={progress.streakDays > 0 ? "keep it gentle" : "starts with today"} />
        <Stat label="This week" value={progress.minutesThisWeek} unit="min" sub={`of about ${plan.dailyMinutes * practiceDays}`} />
        <Stat label="Lessons done" value={path.filter((l) => progress.lessonsDone.includes(l.id)).length} sub={`of ${path.length} on your path`} />
      </div>
      {firstRun && !readOnly ? (
        <div className="today-lesson">
          <div>
            <h3>Set up your plan</h3>
            <p className="muted">Choose a goal, record where your voice is today, and pick gentle targets. It takes about five minutes.</p>
          </div>
          <Button variant="primary" onClick={onSetUp}><Play size={18} /> Set up</Button>
        </div>
      ) : lesson ? (
        <div className="today-lesson">
          <div>
            <span className="muted small">{rest ? "A rest day" : "Today's lesson"} · Unit {lesson.unit}{unit ? `: ${unit.title}` : ""}</span>
            <h3>{lesson.title}</h3>
            <p className="muted">{lesson.about}</p>
            <span className="muted small">{lesson.minutes} min · {count} {count === 1 ? "exercise" : "exercises"} · {skillLabel(lesson.skill)}</span>
          </div>
          {!readOnly && <Button variant={rest ? "tonal" : "primary"} onClick={() => onStart(lesson)}><Play size={18} /> {rest ? "Practise anyway" : "Start today's lesson"}</Button>}
        </div>
      ) : <p className="muted">The lessons will be here once the server has them.</p>}
      {rest && !firstRun && <p className="muted small">Today's one of your rest days. Your voice gets stronger while it rests, so there's nothing you need to do.</p>}
    </Card>
  );
}

function RoutineCard({ program, plan, readOnly, onStart }: { program: VoiceProgram; plan: VoicePlan; readOnly: boolean; onStart: (e: VoiceExercise[]) => void }) {
  const routine = dailyRoutine(program, plan.goal);
  const total = routine.exercises.reduce((t, e) => t + e.seconds, 0);
  return (
    <Card title="Daily routine" icon={<Sun size={18} />}>
      {routine.exercises.length > 0 && (
        <ol className="routine-list">
          {routine.exercises.map((e) => <li key={e.id}><span>{e.title}</span><span className="muted small">{minutes(e.seconds)}</span></li>)}
        </ol>
      )}
      {routine.lines.length > 0 && <ul className="plain-list">{routine.lines.map((l, i) => <li key={i}>{l}</li>)}</ul>}
      {routine.exercises.length === 0 && routine.lines.length === 0 && <p className="muted">A few minutes of warm-up most days keeps your voice easy.</p>}
      {!readOnly && routine.exercises.length > 0 && (
        <div className="button-row"><Button onClick={() => onStart(routine.exercises)}><Play size={16} /> Start the routine</Button><span className="muted small routine-time">about {minutes(total)}</span></div>
      )}
    </Card>
  );
}

function ProgressCard({ progress }: { progress: VoiceProgress }) {
  const weeks = progress.weeks;
  const x = (w: { weekStart: string }) => parseDate(w.weekStart).getTime();
  return (
    <Card title="Progress" icon={<TrendingUp size={18} />} className="span-2">
      {progress.highlights.length > 0 && <ul className="plain-list highlights">{progress.highlights.map((h, i) => <li key={i}>{h}</li>)}</ul>}
      {weeks.length > 0 ? (
        <div className="voice-charts">
          <div>
            <h4 className="subsection-title">Each week: time in your range, and resonance</h4>
            <LineChart label="Each week: the share of time in your range, and resonance, both out of 100" color="var(--c1)" name="In your range (%)" yMin={0} yMax={100} dots height={190}
              points={weeks.map((w) => ({ x: x(w), y: w.inRange ?? null }))}
              extra={[{ points: weeks.map((w) => ({ x: x(w), y: w.resonance ?? null })), color: "var(--c2)", name: "Resonance" }]}
              formatX={(v) => shortDate(isoDate(new Date(v)))} formatY={(y) => String(Math.round(y))} />
            <ul className="legend">
              <li><span className="legend-key" style={{ background: "var(--c1)" }} />In your range (%)</li>
              <li><span className="legend-key" style={{ background: "var(--c2)" }} />Resonance (0 darker, 100 brighter)</li>
            </ul>
          </div>
          <div>
            <h4 className="subsection-title">Minutes each week</h4>
            <BarChart label="Minutes of practice each week" color="var(--c3)" height={190} formatY={(v) => `${Math.round(v)} min`} labelEvery={Math.ceil(weeks.length / 6)}
              bars={weeks.map((w) => ({ key: w.weekStart, label: shortDate(w.weekStart), value: w.minutes, detail: `${w.sessions} ${w.sessions === 1 ? "session" : "sessions"}, week of ${shortDate(w.weekStart)}` }))} />
          </div>
        </div>
      ) : <p className="muted">Your weeks will show here after a few sessions. Little and often is the way.</p>}
    </Card>
  );
}

function LessonsCard({ program, progress, readOnly, onStart }: { program: VoiceProgram; progress: VoiceProgress; readOnly: boolean; onStart: (l: VoiceLesson) => void }) {
  const goal = progress.plan.goal;
  const open = openUnits(program, goal, progress.lessonsDone);
  const done = new Set(progress.lessonsDone);
  const next = todaysLesson(program, progress);
  const all = units(program, goal);
  return (
    <Card title="Units and lessons" icon={<BookOpen size={18} />} className="span-2">
      {all.length === 0 && <p className="muted">The lessons will be here once the server has them.</p>}
      <div className="units">
        {all.map((u, k) => {
          const doneHere = u.lessons.filter((l) => done.has(l.id)).length;
          // The unit with today's lesson starts open; the others fold away until wanted.
          const current = u.lessons.some((l) => l.id === next?.id);
          return (
          <details key={u.unit} className={`unit ${open.has(u.unit) ? "" : "is-locked"}`} open={current}>
            <summary className="unit-head">
              <ChevronRight size={18} className="unit-chevron" aria-hidden="true" />
              <span className="unit-text">
                <span className="unit-title">Unit {u.unit}{u.title ? `: ${u.title}` : ""}</span>
                {u.about && <span className="muted small">{u.about}</span>}
                <span className="muted small unit-lock">
                  {!open.has(u.unit) && k > 0 ? <><Lock size={12} aria-hidden="true" /> Opens once you've done unit {all[k - 1].unit}</> : `${doneHere} of ${u.lessons.length} done`}
                </span>
              </span>
            </summary>
            <ul className="rows">
              {u.lessons.map((l) => {
                const isDone = done.has(l.id);
                const isOpen = open.has(u.unit);
                return (
                  <li key={l.id} className={`row lesson-row ${l.id === next?.id ? "is-next" : ""}`}>
                    <div className={`row-icon ${isDone ? "is-done" : ""}`} aria-hidden="true">{isDone ? <Check size={18} /> : isOpen ? <Mic size={18} /> : <Lock size={18} />}</div>
                    <div className="row-main">
                      <div className="row-title">{l.title}{isDone && <span className="sr-only"> (done)</span>}</div>
                      <div className="row-sub wraps">{l.about} · {l.minutes} min</div>
                    </div>
                    {!readOnly && isOpen && (
                      <Button variant={l.id === next?.id ? "primary" : "tonal"} className="btn-small" onClick={() => onStart(l)} aria-label={`${isDone ? "Practise again" : "Start"}: ${l.title}`}>
                        {isDone ? "Again" : "Start"}
                      </Button>
                    )}
                  </li>
                );
              })}
            </ul>
          </details>
          );
        })}
      </div>
    </Card>
  );
}

function PracticeCard({ program, plan, onStart }: { program: VoiceProgram; plan: VoicePlan; onStart: (e: VoiceExercise) => void }) {
  const [skill, setSkill] = useState<VoiceSkill | null>(null);
  const mine = program.exercises.filter((e) => suits(e, plan.goal));
  const skills = VOICE_SKILLS.filter((s) => mine.some((e) => e.skill === s.id));
  const list = mine.filter((e) => !skill || e.skill === skill);
  return (
    <Card title="Free practice" icon={<Mic size={18} />}>
      <div className="tag-list" role="group" aria-label="Show exercises for">
        <button type="button" className={`chip small ${skill == null ? "is-on" : ""}`} aria-pressed={skill == null} onClick={() => setSkill(null)}>All</button>
        {skills.map((s) => <button key={s.id} type="button" className={`chip small ${skill === s.id ? "is-on" : ""}`} aria-pressed={skill === s.id} onClick={() => setSkill(s.id)}>{s.label}</button>)}
      </div>
      <ul className="rows practice-list">
        {list.map((e) => (
          <li key={e.id} className="row">
            <div className="row-main"><div className="row-title">{e.title}</div><div className="row-sub">{KIND_LABEL[e.kind]} · {minutes(e.seconds)}</div></div>
            <Button variant="tonal" className="btn-small" onClick={() => onStart(e)} aria-label={`Practise ${e.title}`}>Practise</Button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function PlanCard({ plan, readOnly, onEdit, onBaseline }: { plan: VoicePlan; readOnly: boolean; onEdit: () => void; onBaseline: () => void }) {
  const goal = VOICE_GOALS.find((x) => x.id === plan.goal);
  return (
    <Card title="Your plan" icon={<Target size={18} />} action={!readOnly && <CardAction label="Change your plan" onClick={onEdit}><Pencil size={18} /></CardAction>}>
      <dl className="plan-list">
        <div><dt>Goal</dt><dd>{goal?.label}</dd></div>
        <div><dt>Your range</dt><dd>{plan.targetLowHz && plan.targetHighHz ? `${Math.round(plan.targetLowHz)}–${Math.round(plan.targetHighHz)} Hz (${noteName(plan.targetLowHz)}–${noteName(plan.targetHighHz)})` : "Not set yet"}</dd></div>
        <div><dt>Resonance aim</dt><dd>{plan.targetResonance != null ? `${Math.round(plan.targetResonance)}, ${resonanceWord(plan.targetResonance)}` : "Not set yet"}</dd></div>
        <div><dt>Each day</dt><dd>{plan.dailyMinutes} min{plan.reminder ? `, a reminder at ${plan.reminder}` : ""}</dd></div>
        <div><dt>Rest days</dt><dd>{plan.restDays.length ? plan.restDays.map((d) => DAYS[d - 1]).join(", ") : "None"}</dd></div>
        <div><dt>Where you started</dt><dd>{plan.baselinePitchHz ? `${hzText(plan.baselinePitchHz)}${plan.baselineResonance != null ? `, resonance ${Math.round(plan.baselineResonance)}` : ""}${plan.baselineAt ? `, ${when(plan.baselineAt)}` : ""}` : "No baseline yet"}</dd></div>
      </dl>
      {!readOnly && <div><Button variant="ghost" className="btn-small" onClick={onBaseline}><RotateCcw size={14} /> {plan.baselinePitchHz ? "Record a new baseline" : "Record a baseline"}</Button></div>}
    </Card>
  );
}

function PlanDialog({ plan, onClose, onSaved }: { plan: VoicePlan; onClose: () => void; onSaved: (p: VoicePlan) => void }) {
  const [draft, setDraft] = useState(plan);
  const [busy, setBusy] = useState(false);
  const problem = planProblem(draft);
  const save = async () => {
    setBusy(true);
    try { onSaved(await api.saveVoicePlan(draft)); toast("Saved"); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title="Your plan" subtitle="Change anything, any time: these are yours." onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      <Button variant="primary" busy={busy} disabled={!!problem} onClick={save}>Save</Button>
    </>}>
      <PlanFields plan={draft} onChange={setDraft} />
    </Modal>
  );
}
