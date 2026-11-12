import { useEffect, useState } from "react";
import { CloudSun, Pencil, Plus, Sparkles, Square, Stethoscope, Trash2, Zap } from "lucide-react";
import { api } from "../lib/api";
import { clock, dayLabel, hm, isoDate, localInput, shortDate } from "../lib/format";
import { useSession } from "../lib/session";
import { useReadOnly } from "../lib/viewing";
import type { Migraine, SymptomEntry } from "../lib/types";
import { BarChart } from "../components/charts";
import { PlacePicker } from "../components/PlacePicker";
import { Button, Card, Empty, ErrorNotice, Field, Menu, Modal, PageHeader, Segmented, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";

export const SYMPTOMS = ["headache", "back pain", "joint pain", "stomach ache", "fatigue", "nausea", "dizziness", "brain fog", "muscle pain", "pelvic pain", "bloating", "heartburn", "cough", "sore throat", "anxiety", "trouble sleeping"];
const AREAS = ["head", "neck", "shoulders", "upper back", "lower back", "chest", "stomach", "pelvis", "arms", "hands", "hips", "legs", "knees", "feet", "joints", "all over"];
const TRIGGERS = ["poor sleep", "stress", "skipped a meal", "caffeine", "alcohol", "period", "weather", "screens", "bright light", "strong smells", "dehydration", "exercise", "long sitting", "certain food", "travel"];
const RELIEF = ["rest", "dark room", "water", "food", "ibuprofen", "paracetamol", "naproxen", "triptan", "heat", "cold", "stretching", "massage", "sleep", "fresh air"];
const MIGRAINE_SYMPTOMS = ["nausea", "vomiting", "light sensitivity", "sound sensitivity", "smell sensitivity", "dizziness", "neck pain", "blurred vision", "tingling", "trouble finding words"];
const HELPED = ["Not at all", "A little", "A lot", "Completely"];

export function severityWord(n: number): string {
  return n === 0 ? "None" : n <= 3 ? "Mild" : n <= 6 ? "Moderate" : "Severe";
}

export function Symptoms() {
  const [days, setDays] = useState(90);
  const readOnly = useReadOnly();
  const profile = useSession((s) => s.profile);
  const entries = useLoad(() => api.symptoms(30), []);
  const migraines = useLoad(() => api.migraines(90), []);
  const summary = useLoad(() => api.symptomsSummary(days), [days]);
  const [logging, setLogging] = useState<SymptomEntry | { symptom: string } | null>(null);
  const [migraine, setMigraine] = useState<Migraine | null>(null);
  const open = (migraines.data ?? []).find((m) => m.end == null);
  const reload = () => { entries.reload(); migraines.reload(); summary.reload(); };
  const s = summary.data;

  const startMigraine = async () => {
    try { await api.saveMigraine({ start: Date.now(), severity: 5, aura: false, symptoms: [], triggers: [], medications: [], missedPlans: false }); toast("Started. Look after yourself; stop the timer when it's over."); reload(); } catch (e) { errorToast(e); }
  };

  const rows = [
    ...(entries.data ?? []).map((e) => ({ at: e.at, e })),
    ...(migraines.data ?? []).filter((m) => m.end != null).map((m) => ({ at: m.start, m })),
  ].sort((a, b) => b.at - a.at);

  return (
    <div className="page">
      <PageHeader title="Symptoms & pain" subtitle="What you feel, and what seems to go with it" actions={!readOnly && <>
        <Button variant="primary" onClick={() => setLogging({ symptom: "" })}><Plus size={18} /> Log a symptom</Button>
        {!open && <Button onClick={startMigraine}><Zap size={18} /> Migraine started</Button>}
      </>} />

      {open && <OpenMigraine migraine={open} readOnly={readOnly} onStop={() => setMigraine({ ...open, end: Date.now() })} />}

      {!readOnly && (
        <Card title="Quick log" icon={<Stethoscope size={18} />}>
          <div className="tag-list">
            {SYMPTOMS.slice(0, 10).map((x) => <button key={x} type="button" className="chip" onClick={() => setLogging({ symptom: x })}>{x}</button>)}
          </div>
        </Card>
      )}

      <div className="section-head">
        <h2 className="section-title">Patterns</h2>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 30, label: "Month" }, { value: 90, label: "3 months" }, { value: 365, label: "Year" }]} />
      </div>
      {summary.error && <ErrorNotice message={summary.error} onRetry={summary.reload} />}
      {!s && summary.loading && <Spinner />}
      {s && (
        <div className="grid grid-2">
          <Card title="At a glance" className="span-2">
            <div className="stats">
              <Stat label="Days with symptoms" value={s.days.filter((d) => d.worst != null || d.entries.length > 0 || d.migraines.length > 0).length} sub={`in the last ${days} days`} />
              <Stat label="Migraine days" value={s.migraineDays} />
              {s.counts[0] && <Stat label="Most often" value={s.counts[0].name} sub={`${s.counts[0].count} times`} />}
            </div>
            {s.days.some((d) => d.worst != null) && (
              <BarChart
                label="The worst symptom each day" color="var(--c3)" height={130} labelEvery={Math.ceil(s.days.length / 8)}
                formatY={(v) => `${v} of 10 · ${severityWord(v)}`} formatTick={(v) => String(v)}
                bars={s.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: d.worst ?? null, detail: `${shortDate(d.date)}${d.migraines.length ? " · migraine" : ""}` }))}
              />
            )}
          </Card>
          {s.correlations.length > 0 ? (
            <Card title="What seems to go with them" icon={<Sparkles size={18} />} className="span-2">
              <ul className="correlations">
                {s.correlations.map((c) => (
                  <li key={c.factor}>
                    <p>{c.text}</p>
                    <div className="correlation-bars" aria-hidden>
                      <span className="small muted">With</span><span className="cbar"><i style={{ width: `${Math.round(c.withRate * 100)}%`, background: "var(--c3)" }} /></span><span className="small">{Math.round(c.withRate * 100)}%</span>
                      <span className="small muted">Without</span><span className="cbar"><i style={{ width: `${Math.round(c.withoutRate * 100)}%`, background: "var(--c2)" }} /></span><span className="small">{Math.round(c.withoutRate * 100)}%</span>
                    </div>
                  </li>
                ))}
              </ul>
              <p className="muted small">From your own days. Things that go together don't always cause each other, but they're worth noticing, and worth mentioning to your doctor.</p>
            </Card>
          ) : (
            <Card title="What seems to go with them" icon={<Sparkles size={18} />} className="span-2">
              <p className="muted">After a few weeks of logging, this compares days with symptoms against your sleep, cycle, caffeine, alcohol, stress{s.weather ? " and air pressure" : ""}.</p>
            </Card>
          )}
          {(s.triggers.length > 0 || s.counts.length > 0) && (
            <Card title="What you noted">
              {s.counts.length > 0 && <div className="tag-list">{s.counts.slice(0, 10).map((c) => <span key={c.name} className="chip is-static">{c.name} <span className="muted">{c.count}</span></span>)}</div>}
              {s.triggers.length > 0 && <><p className="muted small">Triggers</p><div className="tag-list">{s.triggers.slice(0, 10).map((c) => <span key={c.name} className="chip is-static">{c.name} <span className="muted">{c.count}</span></span>)}</div></>}
            </Card>
          )}
          {!readOnly && (
            <Card title="Weather" icon={<CloudSun size={18} />}>
              <p className="muted small">{profile?.place ? "Air pressure is compared with your migraines and headaches." : "For some people, migraines follow drops in air pressure. Add your area to compare."}</p>
              <PlacePicker />
            </Card>
          )}
        </div>
      )}

      <h2 className="section-title">Recent</h2>
      {(entries.error || migraines.error) && <ErrorNotice message={(entries.error ?? migraines.error)!} onRetry={reload} />}
      <Card>
        {rows.length === 0 ? <Empty icon={<Stethoscope size={32} />} title="Nothing logged in the last month">Log symptoms as they come, with how bad they were and what helped.</Empty> : (
          <ul className="rows">
            {rows.slice(0, 40).map((r) => "e" in r ? (
              <li key={`e${r.e.id}`} className="row">
                <div className={`row-icon severity-${severityWord(r.e.severity).toLowerCase()}`}>{r.e.severity}</div>
                <div className="row-main">
                  <div className="row-title">{r.e.symptom} · {severityWord(r.e.severity).toLowerCase()}</div>
                  <div className="row-sub">{dayLabel(isoDate(new Date(r.at)))}, {clock(r.at)}{r.e.bodyAreas.length ? ` · ${r.e.bodyAreas.join(", ")}` : ""}{r.e.durationMinutes ? ` · ${hm(r.e.durationMinutes)}` : ""}{r.e.triggers.length ? ` · after ${r.e.triggers.join(", ")}` : ""}{r.e.relief.length ? ` · tried ${r.e.relief.join(", ")}${r.e.reliefHelped != null ? ` (${HELPED[r.e.reliefHelped].toLowerCase()})` : ""}` : ""}</div>
                </div>
                {!readOnly && <div className="row-actions">
                  <Menu label={`More for ${r.e.symptom}`} trigger={<Pencil size={16} />} items={[
                    { label: "Edit", icon: <Pencil size={18} />, onSelect: () => setLogging(r.e) },
                    { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteSymptom(r.e.id!); reload(); } catch (e) { errorToast(e); } } },
                  ]} />
                </div>}
              </li>
            ) : (
              <li key={`m${r.m.id}`} className="row">
                <div className="row-icon severity-severe"><Zap size={18} /></div>
                <div className="row-main">
                  <div className="row-title">Migraine · {r.m.severity} of 10{r.m.aura ? " · with aura" : ""}</div>
                  <div className="row-sub">{dayLabel(isoDate(new Date(r.m.start)))}, {clock(r.m.start)} · {hm((r.m.end! - r.m.start) / 60_000)}{r.m.triggers.length ? ` · after ${r.m.triggers.join(", ")}` : ""}{r.m.medications.length ? ` · took ${r.m.medications.join(", ")}` : ""}</div>
                </div>
                {!readOnly && <div className="row-actions">
                  <Menu label="More for this migraine" trigger={<Pencil size={16} />} items={[
                    { label: "Edit", icon: <Pencil size={18} />, onSelect: () => setMigraine(r.m) },
                    { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteMigraine(r.m.id!); reload(); } catch (e) { errorToast(e); } } },
                  ]} />
                </div>}
              </li>
            ))}
          </ul>
        )}
      </Card>

      {logging && <SymptomDialog initial={logging} onClose={() => setLogging(null)} onSaved={reload} />}
      {migraine && <MigraineDialog migraine={migraine} onClose={() => setMigraine(null)} onSaved={reload} />}
    </div>
  );
}

function OpenMigraine({ migraine, readOnly, onStop }: { migraine: Migraine; readOnly: boolean; onStop: () => void }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);
  return (
    <div className="migraine-open" role="status">
      <div className="migraine-pulse" aria-hidden><Zap size={22} /></div>
      <div>
        <strong>Migraine since {clock(migraine.start)}</strong>
        <span className="muted small">{hm((now - migraine.start) / 60_000)} so far. A dark, quiet room and water can help.</span>
      </div>
      {!readOnly && <Button variant="primary" onClick={onStop}><Square size={16} /> It's over</Button>}
    </div>
  );
}

function Chips({ options, value, onChange, label }: { options: string[]; value: string[]; onChange: (v: string[]) => void; label: string }) {
  const [custom, setCustom] = useState("");
  const add = () => { const t = custom.trim().toLowerCase(); if (t && !value.includes(t)) onChange([...value, t]); setCustom(""); };
  return (
    <Field label={label}>
      <div className="tag-list">
        {[...new Set([...options, ...value])].map((o) => (
          <button key={o} type="button" className={`chip ${value.includes(o) ? "is-on" : ""}`} aria-pressed={value.includes(o)} onClick={() => onChange(value.includes(o) ? value.filter((x) => x !== o) : [...value, o])}>{o}</button>
        ))}
        <span className="chip-add">
          <input value={custom} onChange={(e) => setCustom(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} placeholder="Add your own" aria-label={`Add to ${label.toLowerCase()}`} />
        </span>
      </div>
    </Field>
  );
}

function Severity({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  return (
    <Field label={`How bad: ${value} of 10 · ${severityWord(value)}`}>
      <input type="range" min={0} max={10} value={value} onChange={(e) => onChange(Number(e.target.value))} style={{ ["--fill" as string]: `${value * 10}%` }} aria-label="How bad, 0 to 10" />
    </Field>
  );
}

function Helped({ value, onChange }: { value?: number; onChange: (n?: number) => void }) {
  return (
    <Field label="Did it help?">
      <div className="tag-list" role="radiogroup" aria-label="Did it help?">
        {HELPED.map((h, i) => <button key={h} type="button" role="radio" aria-checked={value === i} className={`chip ${value === i ? "is-on" : ""}`} onClick={() => onChange(value === i ? undefined : i)}>{h}</button>)}
      </div>
    </Field>
  );
}

export function SymptomDialog({ initial, onClose, onSaved }: { initial: SymptomEntry | { symptom: string }; onClose: () => void; onSaved: () => void }) {
  const existing = "at" in initial ? initial : undefined;
  const [e, setE] = useState<SymptomEntry>(existing ?? { at: Date.now(), symptom: initial.symptom, severity: 4, bodyAreas: [], triggers: [], relief: [] });
  const [at, setAt] = useState(localInput(e.at));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!e.symptom.trim()) return;
    setBusy(true);
    try { await api.saveSymptom({ ...e, symptom: e.symptom.trim().toLowerCase(), at: new Date(at).getTime() }); toast("Saved"); onSaved(); onClose(); } catch (err) { errorToast(err); setBusy(false); }
  };
  return (
    <Modal wide title={existing ? "Edit symptom" : "Log a symptom"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!e.symptom.trim()} onClick={save}>Save</Button></>}>
      <div className="two-col">
        <Field label="What"><input autoFocus={!e.symptom} list="symptom-names" value={e.symptom} onChange={(x) => setE({ ...e, symptom: x.target.value })} placeholder="e.g. headache" /></Field>
        <Field label="When"><input type="datetime-local" value={at} onChange={(x) => setAt(x.target.value)} /></Field>
      </div>
      <datalist id="symptom-names">{SYMPTOMS.map((x) => <option key={x} value={x} />)}</datalist>
      <Severity value={e.severity} onChange={(severity) => setE({ ...e, severity })} />
      <Chips label="Where" options={AREAS} value={e.bodyAreas} onChange={(bodyAreas) => setE({ ...e, bodyAreas })} />
      <Chips label="What might have set it off" options={TRIGGERS} value={e.triggers} onChange={(triggers) => setE({ ...e, triggers })} />
      <Chips label="What you tried" options={RELIEF} value={e.relief} onChange={(relief) => setE({ ...e, relief })} />
      {e.relief.length > 0 && <Helped value={e.reliefHelped} onChange={(reliefHelped) => setE({ ...e, reliefHelped })} />}
      <div className="two-col">
        <Field label="How long (minutes)" help="Optional"><input inputMode="numeric" value={e.durationMinutes ?? ""} onChange={(x) => setE({ ...e, durationMinutes: Number(x.target.value) || undefined })} /></Field>
        <Field label="Note"><input value={e.note ?? ""} onChange={(x) => setE({ ...e, note: x.target.value || undefined })} /></Field>
      </div>
    </Modal>
  );
}

function MigraineDialog({ migraine, onClose, onSaved }: { migraine: Migraine; onClose: () => void; onSaved: () => void }) {
  const [m, setM] = useState<Migraine>(migraine);
  const [start, setStart] = useState(localInput(m.start));
  const [end, setEnd] = useState(localInput(m.end ?? Date.now()));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.saveMigraine({ ...m, start: new Date(start).getTime(), end: new Date(end).getTime() });
      toast("Saved. Rest up.");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title="The migraine" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <div className="two-col">
        <Field label="Started"><input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} /></Field>
        <Field label="Ended"><input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} /></Field>
      </div>
      <Severity value={m.severity} onChange={(severity) => setM({ ...m, severity })} />
      <Toggle label="With aura" help="Flashing lights, zigzags, blind spots or tingling before or during it." checked={m.aura} onChange={(aura) => setM({ ...m, aura })} />
      <Chips label="It came with" options={MIGRAINE_SYMPTOMS} value={m.symptoms} onChange={(symptoms) => setM({ ...m, symptoms })} />
      <Chips label="What might have set it off" options={TRIGGERS} value={m.triggers} onChange={(triggers) => setM({ ...m, triggers })} />
      <Chips label="Medication taken" options={["ibuprofen", "paracetamol", "naproxen", "aspirin", "triptan", "anti-sickness"]} value={m.medications} onChange={(medications) => setM({ ...m, medications })} />
      {m.medications.length > 0 && <Helped value={m.reliefHelped} onChange={(reliefHelped) => setM({ ...m, reliefHelped })} />}
      <Toggle label="It meant missing work, school or plans" checked={m.missedPlans} onChange={(missedPlans) => setM({ ...m, missedPlans })} />
      <Field label="Note"><textarea rows={2} value={m.note ?? ""} onChange={(e) => setM({ ...m, note: e.target.value || undefined })} /></Field>
    </Modal>
  );
}
