import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { CalendarClock, FlaskConical, History, Pill, Plus, Settings2, Syringe, Trash2, X } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, num, parseDate, shortDate, today } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import type { HormoneMed, HormoneOverview, HormoneSettings, LabSeries, LabTarget } from "../lib/types";
import { LineChart } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { LabDialog, MedDialog, scheduleText } from "./Meds";

/** "1 year, 3 months", "5 weeks", "4 days". */
export function timeSince(date: string): string {
  const from = parseDate(date);
  const now = new Date();
  let months = (now.getFullYear() - from.getFullYear()) * 12 + now.getMonth() - from.getMonth();
  if (now.getDate() < from.getDate()) months -= 1;
  if (months >= 1) {
    const y = Math.floor(months / 12);
    const m = months % 12;
    return [y && `${y} ${y === 1 ? "year" : "years"}`, m && `${m} ${m === 1 ? "month" : "months"}`].filter(Boolean).join(", ");
  }
  const days = Math.max(0, Math.round((now.getTime() - from.getTime()) / 86_400_000));
  return days >= 14 ? `${Math.floor(days / 7)} weeks` : `${days} ${days === 1 ? "day" : "days"}`;
}

function daysUntil(date: string): number {
  return Math.round((parseDate(date).getTime() - parseDate(today()).getTime()) / 86_400_000);
}

export function Hormones() {
  const readOnly = useReadOnly();
  const { data, error, loading, reload } = useLoad(() => api.hormones(), []);
  const [open, setOpen] = useState<"settings" | "change" | "med" | null>(null);
  const [lab, setLab] = useState<{ name: string; unit: string } | null>(null);
  const navigate = useNavigate();

  return (
    <div className="page">
      <PageHeader title="Hormone therapy" subtitle="Your doses over time, and your levels against your own targets" actions={!readOnly && <>
        <Button onClick={() => setOpen("settings")}><Settings2 size={18} /> Targets</Button>
        <Button variant="primary" onClick={() => setLab({ name: "", unit: "" })}><Plus size={18} /> Lab result</Button>
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div className="grid grid-2" style={{ opacity: loading ? 0.6 : 1 }}>
          <LabsDue data={data} />
          {data.medications.length === 0 ? (
            <Card className="span-2">
              <Empty icon={<Syringe size={36} />} title="No hormone medications yet">
                Mark a medication as part of hormone therapy when you add or edit it. Its dose changes are kept here from then on.
                {!readOnly && <div className="button-row" style={{ justifyContent: "center", marginTop: 12 }}>
                  <Button variant="primary" onClick={() => setOpen("med")}><Plus size={18} /> Add one</Button>
                  <Button onClick={() => navigate("/meds")}><Pill size={18} /> Medications</Button>
                </div>}
              </Empty>
            </Card>
          ) : data.medications.map((m) => <HormoneMedCard key={m.medication.id} med={m} readOnly={readOnly} onChanged={reload} />)}
          {!readOnly && data.medications.length > 0 && (
            <div className="span-2 button-row">
              <Button variant="ghost" onClick={() => setOpen("change")}><History size={16} /> Add an earlier dose change</Button>
              <Button variant="ghost" onClick={() => setOpen("med")}><Plus size={16} /> Another medication</Button>
            </div>
          )}

          <div className="span-2 section-head" style={{ margin: "12px 0 0" }}>
            <h2 className="section-title">Levels</h2>
          </div>
          {data.labs.length === 0 ? (
            <Card className="span-2"><Empty icon={<FlaskConical size={32} />} title="No lab results yet">Add results as they come back, and set the target ranges your clinician gave you to see where you are.</Empty></Card>
          ) : data.labs.map((series) => <LevelCard key={series.name} series={series} readOnly={readOnly} onAdd={() => setLab({ name: series.name, unit: series.unit })} />)}
        </div>
      )}
      {open === "settings" && data && <SettingsDialog settings={data.settings} labs={data.labs} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "change" && data && <ChangeDialog meds={data.medications} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "med" && <MedDialog hormone onClose={() => setOpen(null)} onSaved={reload} />}
      {lab && <LabDialog name={lab.name} unit={lab.unit} onClose={() => setLab(null)} onSaved={reload} />}
    </div>
  );
}

function LabsDue({ data }: { data: HormoneOverview }) {
  const due = data.nextLabsDue;
  const until = due ? daysUntil(due) : null;
  return (
    <Card title="Labs" icon={<CalendarClock size={18} />} className="span-2">
      <div className="stats">
        <Stat label="Next due" value={due ? (until! <= 0 ? "Now" : until! < 14 ? `In ${until} days` : `In ${Math.round(until! / 7)} weeks`) : "–"} sub={due ? `around ${shortDate(due)}` : "Add a result to start"} />
        <Stat label="Last labs" value={data.lastLabs ? shortDate(data.lastLabs) : "–"} sub={data.lastLabs ? `${timeSince(data.lastLabs)} ago` : undefined} />
        {data.settings.labsEveryWeeks ? <Stat label="Every" value={data.settings.labsEveryWeeks} unit="weeks" /> : null}
      </div>
      {until != null && until <= 14 && <p className={until <= 0 ? "notice" : "muted small"}>{until <= 0 ? "Your labs are due. If you haven't booked them yet, now's a good time." : "Your labs are due soon: worth booking."}</p>}
    </Card>
  );
}

function HormoneMedCard({ med, readOnly, onChanged }: { med: HormoneMed; readOnly: boolean; onChanged: () => void }) {
  const m = med.medication;
  const changes = [...med.changes].sort((a, b) => b.at - a.at);
  return (
    <Card title={m.name} icon={<Syringe size={18} />}>
      <div className="stats">
        <Stat label="Now" value={m.dose ?? "–"} sub={scheduleText(m)} />
        {med.since && <Stat label="On it for" value={timeSince(med.since)} sub={`since ${parseDate(med.since).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}`} />}
      </div>
      {changes.length > 0 ? (
        <ol className="timeline" aria-label={`${m.name}: dose changes`}>
          {changes.map((c) => (
            <li key={c.id ?? c.at}>
              <span className="timeline-dot" aria-hidden />
              <div>
                <strong>{c.from ? `${c.from} → ${c.to}` : `Started on ${c.to}`}</strong>
                <span className="muted small">{new Date(c.at).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}{c.note ? ` · ${c.note}` : ""}</span>
              </div>
              {!readOnly && c.id && <button type="button" className="icon-btn" aria-label="Delete this change" onClick={async () => { try { await api.deleteDoseChange(c.id!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></button>}
            </li>
          ))}
        </ol>
      ) : <p className="muted small">Dose changes are kept here when you edit the dose in Medications.</p>}
    </Card>
  );
}

function rangeText(t?: LabTarget): string | null {
  if (!t || (t.low == null && t.high == null)) return null;
  if (t.low != null && t.high != null) return `${num(t.low, 1)}–${num(t.high, 1)} ${t.unit}`;
  return t.low != null ? `over ${num(t.low, 1)} ${t.unit}` : `under ${num(t.high!, 1)} ${t.unit}`;
}

function LevelCard({ series, readOnly, onAdd }: { series: LabSeries; readOnly: boolean; onAdd: () => void }) {
  const results = [...series.results].sort((a, b) => a.at - b.at);
  const latest = results[results.length - 1];
  const t = series.target;
  const where = latest && t ? (t.low != null && latest.value < t.low ? "below" : t.high != null && latest.value > t.high ? "above" : "inside") : null;
  const band: [number, number] | undefined = t && (t.low != null || t.high != null)
    ? [t.low ?? Math.min(...results.map((r) => r.value)) * 0.9, t.high ?? Math.max(...results.map((r) => r.value)) * 1.1] : undefined;
  return (
    <Card title={series.name} icon={<FlaskConical size={18} />} action={!readOnly && <Button variant="ghost" onClick={onAdd}><Plus size={16} /> Result</Button>}>
      {latest && (
        <div className="level-head">
          <div className="big-number">{num(latest.value, latest.value % 1 ? 1 : 0)}<small>{series.unit}</small></div>
          {where && <span className={`tone-chip tone-${where === "inside" ? "good" : "info"}`}>{where === "inside" ? "Inside your target" : `${where[0].toUpperCase()}${where.slice(1)} your target`}</span>}
        </div>
      )}
      <p className="muted small">{latest ? `${shortDate(isoDate(new Date(latest.at)))}` : ""}{rangeText(t) ? ` · your target ${rangeText(t)}` : " · set a target range to compare"}</p>
      {results.length > 1 && (
        <LineChart label={`${series.name} over time`} color="var(--c1)" dots area={false} height={150} band={band}
          points={results.map((r) => ({ x: r.at, y: r.value }))}
          formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${num(y, 1)} ${series.unit}`} />
      )}
      {t?.note && <p className="muted small">{t.note}</p>}
    </Card>
  );
}

function SettingsDialog({ settings, labs, onClose, onSaved }: { settings: HormoneSettings; labs: LabSeries[]; onClose: () => void; onSaved: () => void }) {
  const [every, setEvery] = useState(settings.labsEveryWeeks ? String(settings.labsEveryWeeks) : "");
  const known = new Map(settings.targets.map((t) => [t.name.toLowerCase(), t]));
  // A row for every target, and for every lab without one yet.
  const [targets, setTargets] = useState<LabTarget[]>([
    ...settings.targets,
    ...labs.filter((l) => !known.has(l.name.toLowerCase())).map((l) => ({ name: l.name, unit: l.unit })),
  ]);
  const [busy, setBusy] = useState(false);
  const n = (v: string) => (v.trim() === "" ? undefined : Number(v.replace(",", ".")));
  const set = (i: number, change: Partial<LabTarget>) => setTargets(targets.map((t, k) => (k === i ? { ...t, ...change } : t)));
  const save = async () => {
    setBusy(true);
    try {
      await api.saveHormoneSettings({
        labsEveryWeeks: n(every) ?? null,
        targets: targets.filter((t) => t.name.trim() && (t.low != null || t.high != null)).map((t) => ({ ...t, name: t.name.trim() })),
      });
      toast("Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title="Targets and labs" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <Field label="Labs every (weeks)" help="Often every 3 months at first, then less often. Empty: no reminder.">
        <input inputMode="numeric" value={every} onChange={(e) => setEvery(e.target.value)} placeholder="e.g. 12" />
      </Field>
      <h3>Your target ranges</h3>
      <p className="muted small">Use the ranges your clinician gave you. They differ from person to person and from lab to lab.</p>
      <div className="target-rows">
        {targets.map((t, i) => (
          <div key={i} className="target-row">
            <input aria-label="Test" value={t.name} onChange={(e) => set(i, { name: e.target.value })} placeholder="e.g. Estradiol" />
            <input aria-label="Lowest" inputMode="decimal" value={t.low ?? ""} onChange={(e) => set(i, { low: n(e.target.value) })} placeholder="From" />
            <input aria-label="Highest" inputMode="decimal" value={t.high ?? ""} onChange={(e) => set(i, { high: n(e.target.value) })} placeholder="To" />
            <input aria-label="Unit" value={t.unit} onChange={(e) => set(i, { unit: e.target.value })} placeholder="pg/mL" />
            <button type="button" className="icon-btn" aria-label={`Remove ${t.name || "this target"}`} onClick={() => setTargets(targets.filter((_, k) => k !== i))}><X size={16} /></button>
          </div>
        ))}
      </div>
      <div><Button variant="ghost" onClick={() => setTargets([...targets, { name: "", unit: "" }])}><Plus size={16} /> Add a target</Button></div>
    </Modal>
  );
}

function ChangeDialog({ meds, onClose, onSaved }: { meds: HormoneMed[]; onClose: () => void; onSaved: () => void }) {
  const [medicationId, setMed] = useState(meds[0]?.medication.id ?? "");
  const [date, setDate] = useState(today());
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    if (!to.trim()) return;
    setBusy(true);
    try {
      await api.addDoseChange({ medicationId, at: new Date(`${date}T12:00`).getTime(), from: from.trim() || undefined, to: to.trim(), note: note.trim() || undefined });
      toast("Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="An earlier dose change" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!to.trim()} onClick={save}>Save</Button></>}>
      <p className="muted small">For changes from before you used the app. Changes you make in Medications are kept by themselves.</p>
      <div className="two-col">
        <Field label="Medication">
          <select value={medicationId} onChange={(e) => setMed(e.target.value)}>{meds.map((m) => <option key={m.medication.id} value={m.medication.id}>{m.medication.name}</option>)}</select>
        </Field>
        <Field label="When"><input type="date" value={date} max={today()} onChange={(e) => setDate(e.target.value)} /></Field>
      </div>
      <div className="two-col">
        <Field label="From" help="Empty if this was the start"><input value={from} onChange={(e) => setFrom(e.target.value)} placeholder="e.g. 2 mg" /></Field>
        <Field label="To"><input value={to} onChange={(e) => setTo(e.target.value)} placeholder="e.g. 4 mg" /></Field>
      </div>
      <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
    </Modal>
  );
}
