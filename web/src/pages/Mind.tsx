import { useEffect, useRef, useState } from "react";
import { BookOpen, Brain, ClipboardList, Heart, LifeBuoy, LineChart as LineChartIcon, Phone, Plus, Sparkles, Trash2, Wind } from "lucide-react";
import { api } from "../lib/api";
import { clock, isoDate, num, shortDate, when } from "../lib/format";
import type { CrisisResource, JournalEntry, JournalKind, MoodEntry, ScreenerDefinition, ScreenerResult } from "../lib/types";
import { LineChart } from "../components/charts";
import { Button, Card, CardAction, ErrorNotice, Field, Modal, PageHeader, Segmented, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, useHashScroll, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { INSIGHT_ICONS } from "../components/LogDialogs";
import { useModules } from "../App";
import { useReadOnly } from "../lib/viewing";
import { NavLink } from "react-router-dom";
import { F, useFields } from "../lib/fields";
import { EnergyBudgetCard } from "./Neuro";

export const FACES = ["😣", "🙁", "😐", "🙂", "😄"];
export const FACE_LABELS = ["Very low", "Low", "Okay", "Good", "Great"];
const FEELINGS = ["calm", "happy", "grateful", "content", "hopeful", "proud", "loved", "excited", "tired", "bored", "anxious", "stressed", "overwhelmed", "sad", "lonely", "irritable", "angry", "numb", "ashamed"];
const CONTEXT = ["work", "school", "family", "partner", "friends", "health", "sleep", "exercise", "money", "weather", "news", "alone time"];
const PROMPTS = [
  "What's taking up space in your head today?",
  "What went well today, however small?",
  "What would you tell a friend who felt the way you do?",
  "What do you need more of this week? Less of?",
  "When did you feel most like yourself lately?",
];

export function Mind() {
  const [days, setDays] = useState(30);
  const summary = useLoad(() => api.mindSummary(days), [days]);
  const moods = useLoad(() => api.moods(14), []);
  const journal = useLoad(() => api.journal(90), []);
  const screeners = useLoad(() => api.screeners(), []);
  const [logging, setLogging] = useState<number | null>(null);
  const [support, setSupport] = useState(false);
  const [tool, setTool] = useState<Tool | null>(null);
  const [screener, setScreener] = useState<ScreenerDefinition | null>(null);
  const [writing, setWriting] = useState<JournalEntry | "new" | null>(null);
  const readOnly = useReadOnly();
  const modules = useModules();
  const cycleOn = modules.includes("CYCLE");
  const neuroOn = modules.includes("NEURO");
  const { has } = useFields();
  // The EPDS is offered while pregnant and after a birth (from Cycle's life stage).
  const stage = useLoad(() => (cycleOn ? api.lifeStage().then((i) => i.stage).catch(() => null) : Promise.resolve(null)), [cycleOn]);
  const perinatal = stage.data === "PREGNANT" || stage.data === "POSTPARTUM";
  const reload = () => { summary.reload(); moods.reload(); };
  const s = summary.data;

  const sections: Section[] = [
    { id: "feeling", label: "How are you feeling?", when: !readOnly, node: (
      <Card title="How are you feeling?" icon={<Heart size={18} />} className="span-2">
        <div className="scale scale-large" role="group" aria-label="Log your mood">
          {FACES.map((f, i) => (
            <button key={i} type="button" onClick={() => setLogging(i + 1)} aria-label={`${FACE_LABELS[i]}: log it`}>
              <span>{f}</span><small>{FACE_LABELS[i]}</small>
            </button>
          ))}
        </div>
      </Card>
    ) },
    { id: "energy", label: "Energy today", when: has(F.MIND_ENERGY_BUDGET), node: <EnergyBudgetCard readOnly={readOnly} /> },
    { id: "calm", label: "Calm tools", when: !readOnly, node: (
      <Card id="calm" title="Calm tools" icon={<Wind size={18} />} className="span-2">
        <div className="tool-grid">
          {TOOLS.map((t) => (
            <button key={t.id} className="tool-card" onClick={() => setTool(t)}>
              <span className="tool-icon">{t.kind === "breathing" ? <Wind size={22} /> : t.kind === "grounding" ? <Sparkles size={22} /> : <Brain size={22} />}</span>
              <strong>{t.name}</strong>
              <span>{t.blurb}</span>
            </button>
          ))}
        </div>
      </Card>
    ) },
    { id: "trend", label: "Your mood", node: s && (
      <Card title="Your mood" icon={<LineChartIcon size={18} />} className="span-2"
        action={<Segmented label="Range" value={days} onChange={setDays} options={[{ value: 14, label: "2 weeks" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }]} />}>
        {s.days.some((d) => d.mood != null) ? (
          <LineChart
            label="Average mood each day" color="var(--c2)" yMin={1} yMax={5} area={false} dots={days <= 31}
            points={s.days.map((d) => ({ x: new Date(`${d.date}T12:00`).getTime(), y: d.mood ?? null }))}
            formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${y.toFixed(1)} · ${FACE_LABELS[Math.round(y) - 1] ?? ""}`}
          />
        ) : <p className="muted">Log your mood once or twice a day and patterns start to show.</p>}
        {(s.averageMood != null || s.mindfulMinutes > 0) && (
          <p className="muted small">{[s.averageMood != null ? `Average ${num(s.averageMood, 1)} of 5` : null, s.mindfulMinutes > 0 ? `${num(s.mindfulMinutes)} minutes of calm practice` : null].filter(Boolean).join(" · ")}</p>
        )}
      </Card>
    ) },
    { id: "helps", label: "What seems to help", node: s && s.patterns.length > 0 && (
      <Card title="What seems to help" icon={<Sparkles size={18} />} className="span-2">
        <div className="insights" style={{ margin: 0 }}>
          {s.patterns.map((p, i) => {
            const Icon = INSIGHT_ICONS[p.icon] ?? Sparkles;
            return <article key={i} className={`insight tone-${p.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{p.title}</h4><p>{p.body}</p></div></article>;
          })}
        </div>
      </Card>
    ) },
    { id: "named", label: "What you named most", node: s && (s.feelings.length > 0 || s.context.length > 0) && (
      <Card title="What you named most">
        <div className="tag-list">{s.feelings.map((f) => <span key={f.name} className="chip is-static">{f.name} <span className="muted">{f.count}</span></span>)}</div>
        {s.context.length > 0 && <div className="tag-list">{s.context.map((f) => <span key={f.name} className="chip is-static">{f.name} <span className="muted">{f.count}</span></span>)}</div>}
      </Card>
    ) },
    { id: "recent", label: "Recent moods", node: moods.data && moods.data.length > 0 && (
      <Card title="Recent">
        <ul className="rows">
          {moods.data.slice(0, 8).map((m) => (
            <li key={m.id} className="row">
              <div className="row-icon mood-face">{FACES[m.mood - 1]}</div>
              <div className="row-main">
                <div className="row-title">{[...m.feelings, ...m.context].join(", ") || FACE_LABELS[m.mood - 1]}</div>
                <div className="row-sub">{when(m.at)} · {clock(m.at)}{m.note ? ` · ${m.note}` : ""}</div>
              </div>
              {!readOnly && (
                <div className="row-actions">
                  <Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteMood(m.id!); reload(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      </Card>
    ) },
    { id: "checkins", label: "Check-ins", node: screeners.data && (
      <>
        {screeners.data.filter((d) => d.id !== "EPDS" || perinatal).map((d) => {
          const latest = s?.latest.find((r) => r.screener === d.id);
          return (
            <Card key={d.id} title={d.title} icon={<ClipboardList size={18} />} action={!readOnly && <Button variant="tonal" className="btn-small" onClick={() => setScreener(d)}>Take it</Button>}>
              <p className="muted small">{d.about}</p>
              {latest ? <p><strong>{latest.score}</strong> · {latest.severity} <span className="muted small">{shortDate(latest.date!)}</span></p> : <p className="muted small">Not taken yet. It takes about two minutes.</p>}
            </Card>
          );
        })}
      </>
    ) },
    { id: "journal", label: "Journal", node: journal.data && (
      <Card title="Journal" icon={<BookOpen size={18} />} className="span-2"
        action={!readOnly && <CardAction label="Write in your journal" onClick={() => setWriting("new")}><Plus size={18} /></CardAction>}>
        {journal.data.length === 0 ? <p className="muted">A few lines a day is plenty. Private and encrypted on the server.</p> : (
          <ul className="rows">
            {journal.data.map((j) => (
              <li key={j.id}>
                <button className="row" onClick={() => setWriting(j)}>
                  <div className="row-icon"><BookOpen size={18} /></div>
                  <div className="row-main">
                    <div className="row-title">{j.title ?? (j.kind === "GRATITUDE" ? "Gratitude" : j.kind === "THOUGHT_RECORD" ? j.thought?.thought || "Thought record" : j.text.split("\n")[0])}</div>
                    <div className="row-sub">{new Date(j.at).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })} · {j.kind === "GRATITUDE" ? j.gratitude.join(" · ") : j.kind === "THOUGHT_RECORD" ? j.thought?.balancedThought ?? "" : j.text.slice(0, 120)}</div>
                  </div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("mind"), "Mind", sections);
  useHashScroll(true);

  return (
    <div className="page">
      <PageHeader title="Mind" subtitle="How you feel, and what helps" actions={<>
        <Button variant="tonal" onClick={() => setSupport(true)}><LifeBuoy size={18} /> I need support</Button>
        {arranged.button}
      </>} />
      {neuroOn && (
        <nav className="tabs" aria-label="Mind sections">
          <NavLink to="/mind" end className={({ isActive }) => (isActive ? "is-active" : "")}>Mind</NavLink>
          <NavLink to="/neuro" className={({ isActive }) => (isActive ? "is-active" : "")}>Autism &amp; ADHD</NavLink>
        </nav>
      )}
      {summary.error && <ErrorNotice message={summary.error} onRetry={summary.reload} />}
      {!s && summary.loading && <Spinner />}
      {arranged.render()}

      {logging != null && <MoodDialog initial={logging} onClose={() => setLogging(null)} onSaved={reload} />}
      {support && <SupportDialog onClose={() => setSupport(false)} />}
      {tool && <ToolDialog tool={tool} onClose={() => setTool(null)} onLogged={reload} />}
      {screener && <ScreenerDialog definition={screener} onClose={() => setScreener(null)} onDone={(r) => { summary.reload(); if (r.flagged) setSupport(true); }} />}
      {writing && <JournalDialog entry={writing === "new" ? undefined : writing} onClose={() => setWriting(null)} onSaved={journal.reload} />}
    </div>
  );
}

export function MoodDialog({ initial, onClose, onSaved }: { initial: number; onClose: () => void; onSaved: () => void }) {
  const [entry, setEntry] = useState<MoodEntry>({ at: Date.now(), mood: initial, feelings: [], context: [] });
  const [busy, setBusy] = useState(false);
  const toggle = (key: "feelings" | "context", tag: string) =>
    setEntry({ ...entry, [key]: entry[key].includes(tag) ? entry[key].filter((t) => t !== tag) : [...entry[key], tag] });
  const scale = (key: "mood" | "energy" | "anxiety", label: string, faces: string[]) => (
    <Field label={label}>
      <div className="scale" role="radiogroup" aria-label={label}>
        {faces.map((f, i) => (
          <button key={i} type="button" role="radio" aria-checked={entry[key] === i + 1} aria-label={`${i + 1} of 5`} className={entry[key] === i + 1 ? "is-on" : ""}
            onClick={() => setEntry({ ...entry, [key]: key !== "mood" && entry[key] === i + 1 ? undefined : i + 1 })}>{f}</button>
        ))}
      </div>
    </Field>
  );
  const save = async () => {
    setBusy(true);
    try { await api.saveMood(entry); toast("Mood logged"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="How are you feeling?" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      {scale("mood", "Mood", FACES)}
      {scale("energy", "Energy (optional)", ["🪫", "😴", "🙂", "💪", "⚡"])}
      {scale("anxiety", "Anxiety (optional)", ["😌", "🙂", "😐", "😬", "😰"])}
      <Field label="Feelings">
        <div className="tag-list">{FEELINGS.map((t) => <button key={t} type="button" className={`chip ${entry.feelings.includes(t) ? "is-on" : ""}`} aria-pressed={entry.feelings.includes(t)} onClick={() => toggle("feelings", t)}>{t}</button>)}</div>
      </Field>
      <Field label="About">
        <div className="tag-list">{CONTEXT.map((t) => <button key={t} type="button" className={`chip ${entry.context.includes(t) ? "is-on" : ""}`} aria-pressed={entry.context.includes(t)} onClick={() => toggle("context", t)}>{t}</button>)}</div>
      </Field>
      <Field label="Note"><textarea rows={2} value={entry.note ?? ""} onChange={(e) => setEntry({ ...entry, note: e.target.value })} /></Field>
    </Modal>
  );
}

export function SupportDialog({ onClose }: { onClose: () => void }) {
  const { data, error } = useLoad(() => api.resources(), []);
  const href = (r: CrisisResource) => {
    const digits = r.contact.match(/\d[\d\s-]{1,14}\d/);
    if (/text/i.test(r.contact) && !/call/i.test(r.contact) && digits) return `sms:${digits[0].replace(/\D/g, "")}`;
    return digits ? `tel:${digits[0].replace(/\D/g, "")}` : r.url;
  };
  return (
    <Modal title="You don't have to go through this alone" onClose={onClose}>
      <p>If you're in immediate danger, call your local emergency number (911, 999, 112).</p>
      <p className="muted">These people are there to listen, any time of day:</p>
      {error && <div className="notice notice-error">{error}</div>}
      <ul className="rows">
        {(data ?? []).map((r, i) => (
          <li key={i} className="row">
            <div className="row-icon"><Phone size={18} /></div>
            <div className="row-main">
              <div className="row-title">{r.name}</div>
              <div className="row-sub">{r.contact}{r.region ? ` · ${r.region}` : ""}{r.note ? ` · ${r.note}` : ""}</div>
            </div>
            {href(r) && <a className="btn btn-tonal" href={href(r)} target={href(r)?.startsWith("http") ? "_blank" : undefined} rel="noreferrer">{href(r)?.startsWith("http") ? "Open" : "Contact"}</a>}
          </li>
        ))}
      </ul>
      <p className="muted small">Reaching out to someone you trust helps too: a friend, family, your doctor or therapist.</p>
    </Modal>
  );
}

interface Tool {
  id: string;
  name: string;
  blurb: string;
  kind: "breathing" | "grounding" | "timer";
  /** Breathing: phases of [label, seconds, circle size]. */
  phases?: [string, number, number][];
}

const TOOLS: Tool[] = [
  { id: "box-breathing", name: "Box breathing", blurb: "4 in, 4 hold, 4 out, 4 hold. Steadies you fast.", kind: "breathing", phases: [["Breathe in", 4, 1], ["Hold", 4, 1], ["Breathe out", 4, 0.55], ["Hold", 4, 0.55]] },
  { id: "breathing-478", name: "4-7-8 breathing", blurb: "A long, slow out-breath to settle into sleep.", kind: "breathing", phases: [["Breathe in", 4, 1], ["Hold", 7, 1], ["Breathe out", 8, 0.55]] },
  { id: "coherent-breathing", name: "Coherent breathing", blurb: "Even, slow breaths: about 5.5 seconds each way.", kind: "breathing", phases: [["Breathe in", 5.5, 1], ["Breathe out", 5.5, 0.55]] },
  { id: "grounding", name: "5-4-3-2-1 grounding", blurb: "Come back to the room when your mind races.", kind: "grounding" },
  { id: "meditation", name: "Quiet timer", blurb: "Sit, breathe, notice. A soft chime at the end.", kind: "timer" },
];

const GROUNDING = [
  ["5", "things you can see", "Look around. Name them, out loud or in your head."],
  ["4", "things you can touch", "Your feet on the floor, the chair, your clothes."],
  ["3", "things you can hear", "Near and far. Traffic, a fan, your breath."],
  ["2", "things you can smell", "Or two smells you like, if nothing's around."],
  ["1", "thing you can taste", "Or one kind thing to say to yourself."],
];

function ToolDialog({ tool, onClose, onLogged }: { tool: Tool; onClose: () => void; onLogged: () => void }) {
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [length, setLength] = useState(tool.kind === "timer" ? 5 : 3);
  const started = useRef(0);
  useEffect(() => {
    if (!running) return;
    started.current = Date.now() - elapsed * 1000;
    const id = setInterval(() => {
      const secs = (Date.now() - started.current) / 1000;
      setElapsed(secs);
      if (tool.kind !== "grounding" && secs >= length * 60) { setRunning(false); setDone(true); }
    }, 100);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running]);
  const log = async () => {
    try {
      await api.saveSession({ at: Date.now(), minutes: Math.max(0.5, Math.round(elapsed / 6) / 10), kind: tool.id });
      toast("Logged. Well done for taking the time.");
      onLogged();
      onClose();
    } catch (e) { errorToast(e); }
  };
  return (
    <Modal title={tool.name} onClose={onClose} footer={done || (tool.kind === "grounding" && elapsed > 20)
      ? <><Button variant="ghost" onClick={onClose}>Close</Button><Button variant="primary" onClick={log}>Log it</Button></>
      : <Button variant="ghost" onClick={onClose}>Close</Button>}>
      {tool.kind === "breathing" && <Breathing phases={tool.phases!} elapsed={elapsed} running={running} />}
      {tool.kind === "grounding" && <Grounding onStep={() => { if (!running) setRunning(true); }} />}
      {tool.kind === "timer" && (
        <div className="timer-face" aria-live="polite">
          <div className="breath-circle" style={{ transform: `scale(${running ? 0.85 + 0.15 * Math.sin(elapsed / 3) : 0.85})` }} />
          <div className="timer-text">{Math.floor(Math.max(0, length * 60 - elapsed) / 60)}:{String(Math.floor(Math.max(0, length * 60 - elapsed) % 60)).padStart(2, "0")}</div>
        </div>
      )}
      {tool.kind !== "grounding" && (
        <div className="button-row" style={{ justifyContent: "center" }}>
          {!running && !done && (
            <>
              <select value={length} onChange={(e) => setLength(Number(e.target.value))} aria-label="How long" style={{ width: "auto" }}>
                {[1, 2, 3, 5, 10, 15, 20].map((m) => <option key={m} value={m}>{m} min</option>)}
              </select>
              <Button variant="primary" onClick={() => setRunning(true)}>{elapsed > 0 ? "Carry on" : "Start"}</Button>
            </>
          )}
          {running && <Button onClick={() => setRunning(false)}>Pause</Button>}
          {done && <p className="muted">Done. How do you feel?</p>}
        </div>
      )}
    </Modal>
  );
}

function Breathing({ phases, elapsed, running }: { phases: [string, number, number][]; elapsed: number; running: boolean }) {
  const cycle = phases.reduce((s, p) => s + p[1], 0);
  let t = elapsed % cycle;
  let index = 0;
  while (t >= phases[index][1]) { t -= phases[index][1]; index++; }
  const [label, secs, size] = phases[index];
  const left = Math.ceil(secs - t);
  return (
    <div className="breath" aria-live="polite">
      <div className="breath-circle" style={{ transform: `scale(${running ? size : 0.7})`, transitionDuration: `${secs}s` }} />
      <div className="breath-text">
        <strong>{running ? label : "Ready when you are"}</strong>
        {running && <span>{left}</span>}
      </div>
    </div>
  );
}

function Grounding({ onStep }: { onStep: () => void }) {
  const [step, setStep] = useState(0);
  if (step >= GROUNDING.length) return <div className="empty"><h3>Well done</h3><p className="empty-text">Notice your breathing now, and how your feet feel on the floor.</p></div>;
  const [n, what, hint] = GROUNDING[step];
  return (
    <div className="grounding">
      <div className="grounding-number">{n}</div>
      <h3>{what}</h3>
      <p className="muted">{hint}</p>
      <Button variant="primary" onClick={() => { onStep(); setStep(step + 1); }}>{step === GROUNDING.length - 1 ? "Finish" : "Next"}</Button>
    </div>
  );
}

function ScreenerDialog({ definition, onClose, onDone }: { definition: ScreenerDefinition; onClose: () => void; onDone: (r: ScreenerResult) => void }) {
  const [answers, setAnswers] = useState<(number | null)[]>(definition.items.map(() => null));
  const [result, setResult] = useState<ScreenerResult | null>(null);
  const [busy, setBusy] = useState(false);
  const history = useLoad(() => api.screenerResults(365), []);
  const submit = async () => {
    setBusy(true);
    try {
      const r = await api.submitScreener(definition.id, answers as number[]);
      setResult(r);
      onDone(r);
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  if (result) {
    return (
      <Modal title={definition.title} onClose={onClose} footer={<Button variant="primary" onClick={onClose}>Done</Button>}>
        <div className="big-number">{result.score}<small>{result.severity}</small></div>
        <p className="muted">{definition.bands.map((b, i) => `${b.from}${definition.bands[i + 1] ? `–${definition.bands[i + 1].from - 1}` : "+"} ${b.label.toLowerCase()}`).join(" · ")}</p>
        <p>{result.score >= 10
          ? definition.id === "EPDS"
            ? "That's a lot to carry, and you don't have to carry it alone. It's worth talking to your midwife, health visitor or doctor about how you've been feeling: they see this often, and it gets better with support."
            : "That's a lot to carry. It's worth talking to your doctor or a therapist about how you've been feeling; this score is a good way to start that conversation."
          : "Thanks for checking in. Doing it every few weeks shows how things change."}</p>
        <p className="muted small">A check-in isn't a diagnosis. {definition.source}</p>
      </Modal>
    );
  }
  const past = (history.data ?? []).filter((r) => r.screener === definition.id);
  return (
    <Modal wide title={definition.title} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={answers.some((a) => a == null)} onClick={submit}>See my score</Button></>}>
      <p>{definition.question}</p>
      <ol className="screener">
        {definition.items.map((item, i) => (
          <li key={i}>
            <p>{item}</p>
            <div className="screener-options" role="radiogroup" aria-label={item}>
              {(definition.itemOptions?.[i] ?? definition.options).map((o, v) => (
                <button key={v} type="button" role="radio" aria-checked={answers[i] === v} className={`chip ${answers[i] === v ? "is-on" : ""}`} onClick={() => setAnswers(answers.map((a, k) => (k === i ? v : a)))}>{o}</button>
              ))}
            </div>
          </li>
        ))}
      </ol>
      {past.length > 0 && <p className="muted small">Before: {past.slice(0, 6).map((r) => `${shortDate(r.date!)} ${r.score}`).join(" · ")}</p>}
    </Modal>
  );
}

function JournalDialog({ entry, onClose, onSaved }: { entry?: JournalEntry; onClose: () => void; onSaved: () => void }) {
  const [j, setJ] = useState<JournalEntry>(entry ?? { at: Date.now(), kind: "FREE", text: "", gratitude: ["", "", ""], prompt: PROMPTS[new Date().getDay() % PROMPTS.length] });
  const [busy, setBusy] = useState(false);
  const t = j.thought ?? { situation: "", thought: "" };
  const setThought = (change: Partial<typeof t>) => setJ({ ...j, thought: { ...t, ...change } });
  const save = async () => {
    setBusy(true);
    try { await api.saveJournal({ ...j, gratitude: j.gratitude.filter((g) => g.trim()) }); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title={entry ? "Journal" : "Write"} onClose={onClose} footer={<>
      {entry?.id && <Button variant="ghost" onClick={async () => { try { await api.deleteJournal(entry.id!); onSaved(); onClose(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button>
    </>}>
      {!entry && <Segmented<JournalKind> label="Kind" value={j.kind} onChange={(kind) => setJ({ ...j, kind })} options={[{ value: "FREE", label: "Journal" }, { value: "GRATITUDE", label: "Gratitude" }, { value: "THOUGHT_RECORD", label: "Thought record" }]} />}
      {j.kind === "FREE" && (
        <>
          {j.prompt && <p className="muted">{j.prompt} <button className="link-btn" onClick={() => setJ({ ...j, prompt: PROMPTS[(PROMPTS.indexOf(j.prompt!) + 1) % PROMPTS.length] })}>Another prompt</button></p>}
          <Field label="Title (optional)"><input value={j.title ?? ""} onChange={(e) => setJ({ ...j, title: e.target.value })} /></Field>
          <textarea rows={10} autoFocus value={j.text} onChange={(e) => setJ({ ...j, text: e.target.value })} aria-label="Your entry" />
        </>
      )}
      {j.kind === "GRATITUDE" && (
        <>
          <p className="muted">Three things you're grateful for today, big or small.</p>
          {j.gratitude.map((g, i) => <input key={i} value={g} placeholder={`${i + 1}.`} onChange={(e) => setJ({ ...j, gratitude: j.gratitude.map((x, k) => (k === i ? e.target.value : x)) })} aria-label={`Thing ${i + 1}`} />)}
          <Field label="Anything else"><textarea rows={3} value={j.text} onChange={(e) => setJ({ ...j, text: e.target.value })} /></Field>
        </>
      )}
      {j.kind === "THOUGHT_RECORD" && (
        <div className="form-stack">
          <p className="muted">Catch a hard thought, look at it, and find a more balanced one.</p>
          <Field label="What happened?"><textarea rows={2} value={t.situation} onChange={(e) => setThought({ situation: e.target.value })} /></Field>
          <Field label="What went through your mind?"><textarea rows={2} value={t.thought} onChange={(e) => setThought({ thought: e.target.value })} /></Field>
          <div className="two-col">
            <Field label="How did you feel?"><input value={t.feelings ?? ""} onChange={(e) => setThought({ feelings: e.target.value })} /></Field>
            <Field label={`How much did you believe it? ${t.beliefBefore ?? 50}%`}><input type="range" min={0} max={100} value={t.beliefBefore ?? 50} onChange={(e) => setThought({ beliefBefore: Number(e.target.value) })} style={{ ["--fill" as string]: `${t.beliefBefore ?? 50}%` }} /></Field>
          </div>
          <div className="two-col">
            <Field label="What supports it?"><textarea rows={2} value={t.evidenceFor ?? ""} onChange={(e) => setThought({ evidenceFor: e.target.value })} /></Field>
            <Field label="What doesn't?"><textarea rows={2} value={t.evidenceAgainst ?? ""} onChange={(e) => setThought({ evidenceAgainst: e.target.value })} /></Field>
          </div>
          <Field label="A more balanced thought"><textarea rows={2} value={t.balancedThought ?? ""} onChange={(e) => setThought({ balancedThought: e.target.value })} /></Field>
          <Field label={`How much do you believe the first thought now? ${t.beliefAfter ?? 50}%`}><input type="range" min={0} max={100} value={t.beliefAfter ?? 50} onChange={(e) => setThought({ beliefAfter: Number(e.target.value) })} style={{ ["--fill" as string]: `${t.beliefAfter ?? 50}%` }} /></Field>
        </div>
      )}
    </Modal>
  );
}
