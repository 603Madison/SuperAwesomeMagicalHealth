import { useState } from "react";
import { Check, FlaskConical, MoreHorizontal, Pencil, Pill, Plus, Trash2, X } from "lucide-react";
import { api } from "../lib/api";
import { clock, dayLabel, isoDate, num, shortDate, today } from "../lib/format";
import type { DueDose, LabResult, MedForm, Medication, ScheduleKind } from "../lib/types";
import { LineChart } from "../components/charts";
import { Button, Card, DateNav, Empty, ErrorNotice, Field, Menu, Modal, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useModules } from "../App";
import { useReadOnly } from "../lib/viewing";

const FORMS: { id: MedForm; label: string }[] = [
  { id: "PILL", label: "Pill" }, { id: "CAPSULE", label: "Capsule" }, { id: "LIQUID", label: "Liquid" }, { id: "INJECTION", label: "Injection" },
  { id: "PATCH", label: "Patch" }, { id: "GEL", label: "Gel" }, { id: "SPRAY", label: "Spray" }, { id: "INHALER", label: "Inhaler" },
  { id: "CREAM", label: "Cream" }, { id: "DROPS", label: "Drops" }, { id: "IMPLANT", label: "Implant" }, { id: "SUPPLEMENT", label: "Supplement" }, { id: "OTHER", label: "Other" },
];
const SITE_SETS: Partial<Record<MedForm, string[]>> = {
  INJECTION: ["left thigh", "right thigh", "left abdomen", "right abdomen", "left glute", "right glute", "left arm", "right arm"],
  PATCH: ["left abdomen", "right abdomen", "left buttock", "right buttock", "left upper arm", "right upper arm"],
  GEL: ["left arm", "right arm", "left thigh", "right thigh"],
};
const LABS = ["Estradiol", "Testosterone", "Progesterone", "Prolactin", "SHBG", "Ferritin", "Hemoglobin", "Vitamin D", "TSH", "HbA1c", "LDL", "Potassium", "Creatinine", "ALT"];
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export function Meds() {
  const [date, setDate] = useState(today());
  const day = useLoad(() => api.medsDay(date), [date]);
  const meds = useLoad(() => api.meds(true), []);
  const labs = useLoad(() => api.labs(), []);
  const [editing, setEditing] = useState<Medication | "new" | null>(null);
  const [addingLab, setAddingLab] = useState(false);
  const readOnly = useReadOnly();
  const reload = () => { day.reload(); meds.reload(); };
  const d = day.data;

  return (
    <div className="page">
      <PageHeader title="Medications" subtitle="What you take, when, and how it's going" actions={<>
        <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={today()} />
        {!readOnly && <Button variant="primary" onClick={() => setEditing("new")}><Plus size={18} /> Add</Button>}
      </>} />
      {day.error && <ErrorNotice message={day.error} onRetry={day.reload} />}
      {!d && day.loading && <Spinner />}
      {d && (
        <div className="grid grid-2">
          <Card title="Doses" icon={<Pill size={18} />} className="span-2" action={d.adherence7d != null && <span className="muted small">taken <strong>{d.adherence7d}%</strong> this week</span>}>
            {d.due.length === 0 && d.asNeeded.length === 0 && <Empty icon={<Pill size={36} />} title="Nothing scheduled">Add a medication or supplement, and its doses show here with a reminder on your phone.</Empty>}
            <ul className="rows">
              {d.due.map((dose) => <DoseRow key={`${dose.medicationId}-${dose.scheduledFor}`} dose={dose} onChanged={reload} />)}
              {d.asNeeded.map((m) => (
                <li key={m.id} className="row">
                  <div className="row-icon"><Pill size={18} /></div>
                  <div className="row-main"><div className="row-title">{m.name}{m.dose ? ` · ${m.dose}` : ""}</div><div className="row-sub">As needed{d.extra.filter((e) => e.medicationId === m.id).map((e) => ` · took one at ${clock(e.at)}`).join("")}</div></div>
                  <Button className="write-only" onClick={async () => { try { await api.logDose({ medicationId: m.id!, at: Date.now(), status: "TAKEN" }); toast(`${m.name} logged`); reload(); } catch (e) { errorToast(e); } }}>Took one</Button>
                </li>
              ))}
            </ul>
            {d.lowStock.map((m) => <div key={m.id} className="notice">{m.stock === 0 ? `Out of ${m.name}: time to order more.` : `Running low on ${m.name}: ${m.stock} left.`}</div>)}
          </Card>
        </div>
      )}

      <h2 className="section-title">Your medications</h2>
      {meds.data && meds.data.length > 0 && (
        <Card>
          <ul className="rows">
            {meds.data.map((m) => (
              <li key={m.id} className={`row ${m.active ? "" : "is-off"}`}>
                <div className="row-icon"><Pill size={18} /></div>
                <div className="row-main">
                  <div className="row-title">{m.name}{m.dose ? ` · ${m.dose}` : ""}{!m.active && <span className="badge">Stopped</span>}</div>
                  <div className="row-sub">{FORMS.find((f) => f.id === m.form)?.label} · {scheduleText(m)}{m.purpose ? ` · ${m.purpose}` : ""}{m.stock != null ? ` · ${m.stock} left` : ""}</div>
                </div>
                <div className="row-actions">
                  <Menu label={`More for ${m.name}`} trigger={<MoreHorizontal size={18} />} items={[
                    { label: "Edit", icon: <Pencil size={18} />, onSelect: () => setEditing(m) },
                    { label: m.active ? "Stop taking" : "Start again", icon: m.active ? <X size={18} /> : <Check size={18} />, onSelect: async () => { try { await api.saveMed({ ...m, active: !m.active }); reload(); } catch (e) { errorToast(e); } } },
                    { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteMed(m.id!); reload(); } catch (e) { errorToast(e); } } },
                  ]} />
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <div className="section-head">
        <h2 className="section-title">Lab results</h2>
        {!readOnly && <Button onClick={() => setAddingLab(true)}><Plus size={18} /> Add a result</Button>}
      </div>
      {labs.data && labs.data.length === 0 && <Card><Empty icon={<FlaskConical size={36} />} title="No results yet">Keep blood test results here (hormone levels, iron, thyroid, cholesterol…) to see how they change over time.</Empty></Card>}
      {labs.data && labs.data.length > 0 && (
        <div className="grid grid-2">
          {Object.entries(groupBy(labs.data, (l) => l.name)).map(([name, results]) => <LabCard key={name} name={name} results={results} onChanged={labs.reload} />)}
        </div>
      )}

      {editing && <MedDialog med={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} onSaved={reload} />}
      {addingLab && <LabDialog onClose={() => setAddingLab(false)} onSaved={labs.reload} />}
    </div>
  );
}

function groupBy<T>(list: T[], key: (t: T) => string): Record<string, T[]> {
  return list.reduce<Record<string, T[]>>((acc, t) => { (acc[key(t)] ??= []).push(t); return acc; }, {});
}

export function scheduleText(m: Medication): string {
  const s = m.schedule;
  const times = s.times.join(", ");
  switch (s.kind) {
    case "DAILY": return s.times.length > 1 ? `${s.times.length} times a day (${times})` : `Daily at ${times}`;
    case "WEEKLY": return `${(s.weekdays.length ? s.weekdays : [1]).map((d) => WEEKDAYS[d - 1]).join(", ")} at ${times}`;
    case "EVERY_N_DAYS": return `Every ${s.everyDays} days at ${times}`;
    default: return "As needed";
  }
}

function DoseRow({ dose, onChanged }: { dose: DueDose; onChanged: () => void }) {
  const [site, setSite] = useState(dose.nextSite);
  const meds = useLoad(() => (dose.nextSite ? api.meds() : Promise.resolve([] as Medication[])), [dose.nextSite]);
  const sites = meds.data?.find((m) => m.id === dose.medicationId)?.sites ?? [];
  const mark = async (status: "TAKEN" | "SKIPPED") => {
    try {
      await api.logDose({ medicationId: dose.medicationId, at: Date.now(), status, scheduledFor: dose.scheduledFor, site: status === "TAKEN" ? site : undefined });
      onChanged();
    } catch (e) { errorToast(e); }
  };
  const log = dose.log;
  return (
    <li className="row dose-row">
      <div className={`row-icon ${log?.status === "TAKEN" ? "is-done" : ""}`}>{log?.status === "TAKEN" ? <Check size={18} /> : <Pill size={18} />}</div>
      <div className="row-main">
        <div className="row-title">{dose.name}{dose.dose ? ` · ${dose.dose}` : ""}</div>
        <div className="row-sub">
          {clock(dose.scheduledFor)}
          {log ? ` · ${log.status === "TAKEN" ? "taken" : "skipped"} at ${clock(log.at)}${log.site ? ` · ${log.site}` : ""}` : dose.scheduledFor < Date.now() ? " · not marked yet" : ""}
        </div>
      </div>
      <div className="dose-actions">
        {!log && sites.length > 0 && (
          <select value={site} onChange={(e) => setSite(e.target.value)} aria-label="Site" style={{ width: "auto", minHeight: 36 }}>
            {sites.map((s) => <option key={s} value={s}>{s}{s === dose.nextSite ? " (next)" : ""}</option>)}
          </select>
        )}
        {log ? (
          <Button variant="ghost" onClick={async () => { try { await api.deleteDose(log.id!); onChanged(); } catch (e) { errorToast(e); } }}>Undo</Button>
        ) : (
          <>
            <Button variant="primary" onClick={() => mark("TAKEN")}><Check size={16} /> Take</Button>
            <Button variant="ghost" onClick={() => mark("SKIPPED")}>Skip</Button>
          </>
        )}
      </div>
    </li>
  );
}

export function MedDialog({ med, onClose, onSaved, hormone }: { med?: Medication; onClose: () => void; onSaved: () => void; hormone?: boolean }) {
  const [m, setM] = useState<Medication>(med ?? { name: "", form: "PILL", schedule: { kind: "DAILY", times: ["09:00"], everyDays: 1, weekdays: [] }, sites: [], reminders: true, active: true, hormone });
  const [busy, setBusy] = useState(false);
  const hormonesOn = useModules().includes("HORMONES");
  const s = m.schedule;
  const setS = (change: Partial<typeof s>) => setM({ ...m, schedule: { ...s, ...change } });
  const suggested = SITE_SETS[m.form] ?? [];
  const save = async () => {
    setBusy(true);
    try { await api.saveMed(m); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title={med ? `Edit ${med.name}` : "Add a medication"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <div className="two-col">
        <Field label="Name"><input autoFocus value={m.name} onChange={(e) => setM({ ...m, name: e.target.value })} placeholder="e.g. Vitamin D" /></Field>
        <Field label="Dose" help="As on the label, e.g. 2 mg"><input value={m.dose ?? ""} onChange={(e) => setM({ ...m, dose: e.target.value || undefined })} /></Field>
      </div>
      <div className="two-col">
        <Field label="Form">
          <select value={m.form} onChange={(e) => setM({ ...m, form: e.target.value as MedForm })}>{FORMS.map((f) => <option key={f.id} value={f.id}>{f.label}</option>)}</select>
        </Field>
        <Field label="What it's for" help="Optional"><input value={m.purpose ?? ""} onChange={(e) => setM({ ...m, purpose: e.target.value || undefined })} /></Field>
      </div>
      <Field label="When">
        <select value={s.kind} onChange={(e) => setS({ kind: e.target.value as ScheduleKind })}>
          <option value="DAILY">Every day</option><option value="WEEKLY">On certain weekdays</option><option value="EVERY_N_DAYS">Every few days</option><option value="AS_NEEDED">As needed</option>
        </select>
      </Field>
      {s.kind === "WEEKLY" && (
        <div className="tag-list">{WEEKDAYS.map((d, i) => <button key={d} type="button" className={`chip ${s.weekdays.includes(i + 1) ? "is-on" : ""}`} onClick={() => setS({ weekdays: s.weekdays.includes(i + 1) ? s.weekdays.filter((x) => x !== i + 1) : [...s.weekdays, i + 1] })}>{d}</button>)}</div>
      )}
      {s.kind === "EVERY_N_DAYS" && (
        <div className="two-col">
          <Field label="Every how many days"><input inputMode="numeric" value={s.everyDays} onChange={(e) => setS({ everyDays: Number(e.target.value) || 1 })} /></Field>
          <Field label="Starting"><input type="date" value={s.startDate ?? isoDate(new Date())} onChange={(e) => setS({ startDate: e.target.value })} /></Field>
        </div>
      )}
      {s.kind !== "AS_NEEDED" && (
        <Field label="At">
          <div className="tag-list">
            {s.times.map((t, i) => (
              <span key={i} className="time-chip">
                <input type="time" value={t} onChange={(e) => setS({ times: s.times.map((x, k) => (k === i ? e.target.value : x)) })} aria-label={`Time ${i + 1}`} />
                {s.times.length > 1 && <button type="button" className="link-btn" aria-label="Remove this time" onClick={() => setS({ times: s.times.filter((_, k) => k !== i) })}><X size={14} /></button>}
              </span>
            ))}
            <button type="button" className="chip" onClick={() => setS({ times: [...s.times, "21:00"] })}><Plus size={14} /> Another time</button>
          </div>
        </Field>
      )}
      {suggested.length > 0 && (
        <Field label="Sites to rotate through" help="The app suggests the one used longest ago">
          <div className="tag-list">{[...new Set([...suggested, ...m.sites])].map((site) => <button key={site} type="button" className={`chip ${m.sites.includes(site) ? "is-on" : ""}`} aria-pressed={m.sites.includes(site)} onClick={() => setM({ ...m, sites: m.sites.includes(site) ? m.sites.filter((x) => x !== site) : [...m.sites, site] })}>{site}</button>)}</div>
        </Field>
      )}
      <div className="two-col">
        <Field label="Doses left" help="Optional: counts down as you take them"><input inputMode="numeric" value={m.stock ?? ""} onChange={(e) => setM({ ...m, stock: e.target.value === "" ? undefined : Number(e.target.value) })} /></Field>
        <Toggle label="Remind me" help="On your phone, in your browser and on your watch, at each time" checked={m.reminders} onChange={(reminders) => setM({ ...m, reminders })} />
      </div>
      {hormonesOn && (
        <div className="two-col">
          <Toggle label="Part of hormone therapy" help="Shown in Hormone therapy, with its dose history." checked={!!m.hormone} onChange={(hormone) => setM({ ...m, hormone })} />
          <Field label="Started on" help="Optional"><input type="date" value={m.startedOn ?? ""} max={today()} onChange={(e) => setM({ ...m, startedOn: e.target.value || undefined })} /></Field>
        </div>
      )}
      <Field label="Notes"><textarea rows={2} value={m.notes ?? ""} onChange={(e) => setM({ ...m, notes: e.target.value || undefined })} /></Field>
    </Modal>
  );
}

function LabCard({ name, results, onChanged }: { name: string; results: LabResult[]; onChanged: () => void }) {
  const latest = results[0];
  const outside = (latest.low != null && latest.value < latest.low) || (latest.high != null && latest.value > latest.high);
  return (
    <Card title={name} icon={<FlaskConical size={18} />} action={<span className="muted small">{shortDate(latest.date!)}</span>}>
      <div className="big-number">{num(latest.value, latest.value % 1 ? 1 : 0)}<small>{latest.unit}</small></div>
      {(latest.low != null || latest.high != null) && <p className={`small ${outside ? "" : "muted"}`}>Reference range {latest.low ?? "?"}–{latest.high ?? "?"} {latest.unit}{outside ? ": outside it" : ""}</p>}
      {results.length > 1 && (
        <LineChart label={`${name} over time`} color="var(--c1)" dots area={false} height={140}
          points={[...results].reverse().map((r) => ({ x: r.at, y: r.value }))}
          formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${num(y, 1)} ${latest.unit}`}
          band={latest.low != null && latest.high != null ? [latest.low, latest.high] : undefined} />
      )}
      <ul className="rows">
        {results.slice(0, 4).map((r) => (
          <li key={r.id} className="row">
            <div className="row-main"><div className="row-title">{num(r.value, 2)} {r.unit}</div><div className="row-sub">{new Date(r.at).toLocaleDateString()}{r.note ? ` · ${r.note}` : ""}</div></div>
            <div className="row-actions"><Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteLab(r.id!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button></div>
          </li>
        ))}
      </ul>
    </Card>
  );
}

export function LabDialog({ onClose, onSaved, name = "", unit = "" }: { onClose: () => void; onSaved: () => void; name?: string; unit?: string }) {
  const [lab, setLab] = useState({ name, value: "", unit, date: today(), low: "", high: "", note: "" });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof lab) => (e: React.ChangeEvent<HTMLInputElement>) => setLab({ ...lab, [k]: e.target.value });
  const n = (v: string) => (v.trim() === "" ? undefined : Number(v.replace(",", ".")));
  const save = async () => {
    setBusy(true);
    try {
      await api.saveLab({ name: lab.name, value: n(lab.value) ?? NaN, unit: lab.unit, at: new Date(`${lab.date}T09:00`).getTime(), low: n(lab.low), high: n(lab.high), note: lab.note || undefined });
      toast("Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Add a lab result" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <Field label="Test"><input autoFocus list="lab-names" value={lab.name} onChange={set("name")} /></Field>
      <datalist id="lab-names">{LABS.map((l) => <option key={l} value={l} />)}</datalist>
      <div className="two-col">
        <Field label="Result"><input inputMode="decimal" value={lab.value} onChange={set("value")} /></Field>
        <Field label="Unit" help="e.g. pg/mL, nmol/L"><input value={lab.unit} onChange={set("unit")} /></Field>
      </div>
      <div className="two-col">
        <Field label="Range low" help="From the report, optional"><input inputMode="decimal" value={lab.low} onChange={set("low")} /></Field>
        <Field label="Range high"><input inputMode="decimal" value={lab.high} onChange={set("high")} /></Field>
      </div>
      <div className="two-col">
        <Field label="Date"><input type="date" value={lab.date} onChange={set("date")} /></Field>
        <Field label="Note"><input value={lab.note} onChange={set("note")} /></Field>
      </div>
    </Modal>
  );
}
