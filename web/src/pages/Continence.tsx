import { useEffect, useRef, useState } from "react";
import { Activity, Download, Droplet, Moon, Plus, Printer, Sparkles, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, isoDate, num, shortDate, today } from "../lib/format";
import type { ContinenceEntry, ContinenceKind, LeakSize } from "../lib/types";
import { BarChart } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { INSIGHT_ICONS } from "../components/LogDialogs";

export const KINDS: { id: ContinenceKind; label: string; short: string }[] = [
  { id: "URINATION", label: "Toilet trip", short: "Trip" },
  { id: "LEAK", label: "Leak", short: "Leak" },
  { id: "BOWEL", label: "Bowel movement", short: "Bowel" },
  { id: "PAD_CHANGE", label: "Pad change", short: "Pad" },
];
const TRIGGERS = ["cough", "sneeze", "laugh", "lifting", "exercise", "urge", "on the way", "running water", "asleep", "standing up"];
const BRISTOL = ["Separate hard lumps", "Lumpy and sausage-like", "A sausage with cracks", "Smooth and soft", "Soft blobs", "Mushy, ragged edges", "Liquid"];
const LEAKS: { id: LeakSize; label: string }[] = [{ id: "DROPS", label: "A few drops" }, { id: "SMALL", label: "Small" }, { id: "MEDIUM", label: "Medium" }, { id: "LARGE", label: "Large" }];

export function Continence() {
  const entries = useLoad(() => api.continence(3), []);
  const summary = useLoad(() => api.continenceSummary(14), []);
  const [detail, setDetail] = useState<ContinenceEntry | null>(null);
  const [exercise, setExercise] = useState(false);
  const [diary, setDiary] = useState(false);
  const reload = () => { entries.reload(); summary.reload(); };
  const quick = async (kind: ContinenceKind) => {
    try { await api.saveContinence({ at: Date.now(), kind, triggers: [], pain: false, blood: false, night: false }); toast(`${KINDS.find((k) => k.id === kind)!.label} logged`); reload(); } catch (e) { errorToast(e); }
  };
  const todays = (entries.data ?? []).filter((e) => e.date === today());
  const s = summary.data;
  return (
    <div className="page">
      <PageHeader title="Bladder & bowel" subtitle="A private diary: what happens, when, and what helps" actions={
        <Button variant="tonal" onClick={() => setDiary(true)}><Printer size={18} /> 3-day diary</Button>
      } />
      <div className="quick">
        {KINDS.map((k) => <button key={k.id} className="chip" onClick={() => quick(k.id)}><Plus size={18} /> {k.label}</button>)}
        <button className="chip" onClick={() => setDetail({ at: Date.now(), kind: "URINATION", triggers: [], pain: false, blood: false, night: false })}><Plus size={18} /> With details</button>
        <button className="chip" onClick={() => setExercise(true)}><Activity size={18} /> Pelvic floor exercise</button>
      </div>
      <div className="grid grid-2">
        <Card title="Today" icon={<Droplet size={18} />}>
          {entries.error && <ErrorNotice message={entries.error} onRetry={entries.reload} />}
          {todays.length === 0 ? <p className="muted">Nothing logged today. A tap on the buttons above is all it takes.</p> : (
            <ul className="rows">
              {todays.map((e) => (
                <li key={e.id} className="row">
                  <div className="row-icon">{e.night ? <Moon size={18} /> : e.kind === "PELVIC_FLOOR" ? <Activity size={18} /> : <Droplet size={18} />}</div>
                  <button className="row-main link-row" onClick={() => setDetail(e)}>
                    <div className="row-title">{KINDS.find((k) => k.id === e.kind)?.label ?? "Pelvic floor exercise"}</div>
                    <div className="row-sub">{clock(e.at)}{describe(e)}</div>
                  </button>
                  <div className="row-actions">
                    <Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteContinence(e.id!); reload(); } catch (err) { errorToast(err); } }}><Trash2 size={16} /></Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="The last two weeks">
          {!s && summary.loading && <Spinner />}
          {s && (
            <>
              <div className="stats">
                <Stat label="Trips a day" value={s.averageVoids != null ? num(s.averageVoids, 1) : "–"} />
                <Stat label="At night" value={s.averageNightVoids != null ? num(s.averageNightVoids, 1) : "–"} />
                <Stat label="Leaks a day" value={s.averageLeaks != null ? num(s.averageLeaks, 1) : "–"} />
                <Stat label="Longest gap" value={s.averageInterval != null ? `${Math.floor(s.averageInterval / 60)}h ${s.averageInterval % 60}m` : "–"} sub="while awake, on average" />
              </div>
              {s.triggers.length > 0 && <div className="tag-list">{s.triggers.map((t) => <span key={t.name} className="chip is-static">{t.name} <span className="muted">{t.count}</span></span>)}</div>}
            </>
          )}
        </Card>
        {s && s.days.some((d) => d.voids + d.leaks > 0) && (
          <>
            <Card title="Toilet trips">
              <BarChart label="Toilet trips each day" color="var(--c1)" labelEvery={3} formatY={(v) => `${v} trips`}
                bars={s.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: d.voids || null, detail: `${shortDate(d.date)} · ${d.nightVoids} at night · ${num(d.fluidMl)} ml water` }))} />
            </Card>
            <Card title="Leaks">
              <BarChart label="Leaks each day" color="var(--c3)" labelEvery={3} formatY={(v) => `${v} leaks`}
                bars={s.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: d.leaks || null, detail: shortDate(d.date) }))} />
            </Card>
          </>
        )}
        {s && s.patterns.length > 0 && (
          <Card title="Worth knowing" className="span-2">
            <div className="insights" style={{ margin: 0 }}>
              {s.patterns.map((p, i) => {
                const Icon = INSIGHT_ICONS[p.icon] ?? Sparkles;
                return <article key={i} className={`insight tone-${p.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{p.title}</h4><p>{p.body}</p></div></article>;
              })}
            </div>
            <p className="muted small">This diary doesn't diagnose anything, but it's exactly what a doctor, continence nurse or pelvic health physiotherapist will want to see.</p>
          </Card>
        )}
        {s && !s.days.some((d) => d.voids + d.leaks + d.bowel > 0) && (
          <Card className="span-2"><Empty icon={<Droplet size={36} />} title="Start your diary">Log every toilet trip and any leaks for three days or more: it's the first thing any specialist asks for, and it shows patterns quickly.</Empty></Card>
        )}
      </div>
      {detail && <EntryDialog entry={detail} onClose={() => setDetail(null)} onSaved={reload} />}
      {exercise && <PelvicFloor onClose={() => setExercise(false)} onLogged={reload} />}
      {diary && <Diary onClose={() => setDiary(false)} />}
    </div>
  );
}

function describe(e: ContinenceEntry): string {
  const parts = [
    e.night ? "at night" : "",
    e.volumeMl ? `${e.volumeMl} ml` : "",
    e.urgency != null ? ["no urgency", "mild urgency", "strong urgency", "couldn't wait"][e.urgency] : "",
    e.leak ? `leak: ${LEAKS.find((l) => l.id === e.leak)?.label.toLowerCase()}` : "",
    e.triggers.join(", "),
    e.bristol ? `type ${e.bristol}` : "",
    e.reps ? `${e.reps} × ${e.holdSeconds ?? 5}s` : "",
    e.pain ? "pain" : "", e.blood ? "blood" : "", e.note ?? "",
  ].filter(Boolean);
  return parts.length ? ` · ${parts.join(" · ")}` : "";
}

function EntryDialog({ entry, onClose, onSaved }: { entry: ContinenceEntry; onClose: () => void; onSaved: () => void }) {
  const [e, setE] = useState(entry);
  const [busy, setBusy] = useState(false);
  const toggle = (t: string) => setE({ ...e, triggers: e.triggers.includes(t) ? e.triggers.filter((x) => x !== t) : [...e.triggers, t] });
  const save = async () => {
    setBusy(true);
    try { await api.saveContinence(e); toast("Saved"); onSaved(); onClose(); } catch (err) { errorToast(err); setBusy(false); }
  };
  const local = (ms: number) => { const d = new Date(ms); return `${isoDate(d)}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };
  return (
    <Modal title={entry.id ? "Edit entry" : "Add to the diary"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <div className="tag-list">{KINDS.map((k) => <button key={k.id} type="button" className={`chip ${e.kind === k.id ? "is-on" : ""}`} onClick={() => setE({ ...e, kind: k.id })}>{k.label}</button>)}</div>
      <Field label="When"><input type="datetime-local" value={local(e.at)} onChange={(ev) => setE({ ...e, at: new Date(ev.target.value).getTime() })} /></Field>
      {(e.kind === "URINATION" || e.kind === "LEAK") && (
        <Field label="Urgency">
          <div className="tag-list">{["None", "Mild", "Strong", "Couldn't wait"].map((u, i) => <button key={u} type="button" className={`chip ${e.urgency === i ? "is-on" : ""}`} onClick={() => setE({ ...e, urgency: e.urgency === i ? undefined : i })}>{u}</button>)}</div>
        </Field>
      )}
      {e.kind === "URINATION" && <Field label="Volume (ml)" help="If you measure it, with a jug or a hat"><input inputMode="numeric" value={e.volumeMl ?? ""} onChange={(ev) => setE({ ...e, volumeMl: Number(ev.target.value) || undefined })} /></Field>}
      {(e.kind === "LEAK" || e.kind === "URINATION") && (
        <Field label={e.kind === "LEAK" ? "How much" : "Any leak on the way?"}>
          <div className="tag-list">{LEAKS.map((l) => <button key={l.id} type="button" className={`chip ${e.leak === l.id ? "is-on" : ""}`} onClick={() => setE({ ...e, leak: e.leak === l.id ? undefined : l.id })}>{l.label}</button>)}</div>
        </Field>
      )}
      {(e.kind === "LEAK" || e.leak) && (
        <Field label="What came before it">
          <div className="tag-list">{TRIGGERS.map((t) => <button key={t} type="button" className={`chip ${e.triggers.includes(t) ? "is-on" : ""}`} aria-pressed={e.triggers.includes(t)} onClick={() => toggle(t)}>{t}</button>)}</div>
        </Field>
      )}
      {e.kind === "BOWEL" && (
        <Field label="Type (Bristol scale)">
          <div className="bristol">{BRISTOL.map((b, i) => <button key={i} type="button" className={`chip ${e.bristol === i + 1 ? "is-on" : ""}`} onClick={() => setE({ ...e, bristol: i + 1 })}><strong>{i + 1}</strong> {b}</button>)}</div>
        </Field>
      )}
      {(e.kind === "URINATION" || e.kind === "BOWEL") && (
        <div className="two-col">
          <Toggle label="Pain or burning" checked={e.pain} onChange={(pain) => setE({ ...e, pain })} />
          <Toggle label="Blood" checked={e.blood} onChange={(blood) => setE({ ...e, blood })} />
        </div>
      )}
      {(e.pain || e.blood) && <p className="muted small">Pain, burning or blood is worth checking with a doctor soon.</p>}
      <Field label="Note"><input value={e.note ?? ""} onChange={(ev) => setE({ ...e, note: ev.target.value || undefined })} /></Field>
    </Modal>
  );
}

/** Squeeze and relax, with a count: a common routine is 10 slow holds and 10 quick squeezes. */
function PelvicFloor({ onClose, onLogged }: { onClose: () => void; onLogged: () => void }) {
  const [reps, setReps] = useState(10);
  const [hold, setHold] = useState(5);
  const [running, setRunning] = useState(false);
  const [rep, setRep] = useState(0);
  const [phase, setPhase] = useState<"squeeze" | "relax">("squeeze");
  const [left, setLeft] = useState(hold);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (!running) return;
    timer.current = window.setInterval(() => {
      setLeft((l) => {
        if (l > 1) return l - 1;
        setPhase((p) => {
          if (p === "squeeze") return "relax";
          setRep((r) => r + 1);
          return "squeeze";
        });
        return hold;
      });
    }, 1000);
    return () => window.clearInterval(timer.current);
  }, [running, hold]);
  const done = rep >= reps;
  useEffect(() => { if (done) setRunning(false); }, [done]);
  const log = async () => {
    try {
      await api.saveContinence({ at: Date.now(), kind: "PELVIC_FLOOR", reps: Math.max(1, rep), holdSeconds: hold, triggers: [], pain: false, blood: false, night: false });
      toast("Exercise logged");
      onLogged();
      onClose();
    } catch (e) { errorToast(e); }
  };
  return (
    <Modal title="Pelvic floor exercise" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Close</Button>{(done || rep > 0) && <Button variant="primary" onClick={log}>Log it</Button>}</>}>
      <p className="muted">Squeeze as if stopping wind and the flow of urine at the same time, lift, hold, then let go fully. Keep breathing, and keep your stomach, thighs and bottom relaxed.</p>
      <div className="breath" aria-live="polite">
        <div className="breath-circle" style={{ transform: `scale(${running && phase === "squeeze" ? 1 : 0.6})`, transitionDuration: "0.8s" }} />
        <div className="breath-text">
          <strong>{done ? "Done!" : running ? (phase === "squeeze" ? "Squeeze and hold" : "Relax") : "Ready when you are"}</strong>
          {running && <span>{left}</span>}
          <small>{Math.min(rep, reps)} of {reps}</small>
        </div>
      </div>
      {!running && !done && (
        <div className="two-col">
          <Field label="Repetitions"><input inputMode="numeric" value={reps} onChange={(e) => setReps(Number(e.target.value) || 10)} /></Field>
          <Field label="Hold (seconds)"><input inputMode="numeric" value={hold} onChange={(e) => { setHold(Number(e.target.value) || 5); setLeft(Number(e.target.value) || 5); }} /></Field>
        </div>
      )}
      <div className="button-row" style={{ justifyContent: "center" }}>
        {!done && <Button variant="primary" onClick={() => setRunning(!running)}>{running ? "Pause" : rep > 0 ? "Carry on" : "Start"}</Button>}
      </div>
      <p className="muted small">A pelvic health physiotherapist can check you're doing them right; it makes a big difference.</p>
    </Modal>
  );
}

/** The last three days as a bladder diary, to print or download for a doctor. */
function Diary({ onClose }: { onClose: () => void }) {
  const entries = useLoad(() => api.continence(3), []);
  const summary = useLoad(() => api.continenceSummary(3), []);
  const rows = [...(entries.data ?? [])].reverse();
  const csv = () => {
    const lines = [["Date", "Time", "Type", "Volume (ml)", "Urgency (0-3)", "Leak", "Triggers", "Bristol", "Night", "Note"].join(",")];
    rows.forEach((e) => lines.push([e.date, clock(e.at), KINDS.find((k) => k.id === e.kind)?.label ?? "Pelvic floor exercise", e.volumeMl ?? "", e.urgency ?? "", e.leak ?? "", e.triggers.join("; "), e.bristol ?? "", e.night ? "yes" : "", (e.note ?? "").replace(/[",\n]/g, " ")].join(",")));
    (summary.data?.days ?? []).forEach((d) => lines.push(["", "", `Water on ${d.date}`, d.fluidMl, "", "", "", "", "", ""].join(",")));
    const blob = new Blob([lines.join("\n")], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `bladder-diary-${today()}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  };
  return (
    <Modal wide title="3-day bladder and bowel diary" onClose={onClose} footer={<>
      <Button variant="ghost" onClick={csv}><Download size={16} /> Download CSV</Button>
      <Button variant="primary" onClick={() => window.print()}><Printer size={16} /> Print</Button>
    </>}>
      {entries.loading ? <Spinner /> : rows.length === 0 ? <p className="muted">Nothing logged in the last three days.</p> : (
        <div className="table-wrap printable">
          <table className="table">
            <thead><tr><th>Date</th><th>Time</th><th>Type</th><th>ml</th><th>Urgency</th><th>Leak</th><th>Triggers</th><th>Night</th></tr></thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td>{shortDate(e.date!)}</td><td>{clock(e.at)}</td><td>{KINDS.find((k) => k.id === e.kind)?.label ?? "Pelvic floor"}{e.bristol ? ` (type ${e.bristol})` : ""}</td>
                  <td>{e.volumeMl ?? ""}</td><td>{e.urgency ?? ""}</td><td>{e.leak ? LEAKS.find((l) => l.id === e.leak)?.label : ""}</td><td>{e.triggers.join(", ")}</td><td>{e.night ? "✓" : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {summary.data && <p className="muted small">Water logged: {summary.data.days.map((d) => `${shortDate(d.date)} ${num(d.fluidMl)} ml`).join(" · ")}</p>}
        </div>
      )}
    </Modal>
  );
}
