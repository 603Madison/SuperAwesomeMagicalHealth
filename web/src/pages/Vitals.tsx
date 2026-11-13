import { useState } from "react";
import { Droplets, HeartPulse, List as ListIcon, Plus, Settings2, Sparkles, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, dayLabel, isoDate, localInput, num, shortDate } from "../lib/format";
import { session, useSession } from "../lib/session";
import { useReadOnly } from "../lib/viewing";
import type { BloodPressure, GlucoseContext, GlucoseReading, GlucoseUnit, VitalsTargets } from "../lib/types";
import { BP_TEXT, DEFAULT_TARGETS, GLUCOSE_CONTEXTS, bpCategory, bpTone, glucoseText, glucoseValue, toMmol, unitLabel } from "../lib/vitals";
import { LineChart } from "../components/charts";
import { Button, Card, CardAction, ErrorNotice, Field, Modal, PageHeader, Segmented, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { INSIGHT_ICONS } from "../components/LogDialogs";

export function Vitals() {
  const [days, setDays] = useState(30);
  const readOnly = useReadOnly();
  const profile = useSession((s) => s.profile);
  const { data, error, loading, reload } = useLoad(() => api.vitalsSummary(days), [days]);
  // The unit: the person's own; while viewing someone else's, theirs (from their targets), switchable here.
  const [unitOverride, setUnitOverride] = useState<GlucoseUnit | null>(null);
  const targets: VitalsTargets = data?.targets ?? profile?.vitalsTargets ?? DEFAULT_TARGETS;
  const unit = unitOverride ?? (readOnly ? targets.glucoseUnit : profile?.vitalsTargets?.glucoseUnit ?? targets.glucoseUnit);
  const [open, setOpen] = useState<"bp" | "glucose" | "targets" | null>(null);

  const setUnit = async (u: GlucoseUnit) => {
    setUnitOverride(u);
    if (readOnly || !profile) return;
    try { session.setProfile(await api.saveProfile({ ...profile, vitalsTargets: { ...(profile.vitalsTargets ?? DEFAULT_TARGETS), glucoseUnit: u } })); } catch (e) { errorToast(e); }
  };

  const bp = data ? [...data.bloodPressure].sort((a, b) => a.at - b.at) : [];
  const glucose = data ? [...data.glucose].sort((a, b) => a.at - b.at) : [];
  const category = data?.category ?? (data?.systolicAverage != null && data.diastolicAverage != null ? bpCategory(data.systolicAverage, data.diastolicAverage) : undefined);

  const sections: Section[] = [
    { id: "bp", label: "Blood pressure", node: data && (
      <Card title="Blood pressure" icon={<HeartPulse size={18} />} className="span-2" action={category && <span className={`tone-chip tone-${bpTone(category)}`}>{BP_TEXT[category]}</span>}>
        {bp.length === 0 ? (
          <p className="muted">No readings yet. Sit quietly for five minutes, then measure: two readings a minute apart, morning and evening, give the clearest picture.</p>
        ) : (
          <>
            <div className="stats">
              <Stat label="Average" value={`${num(data.systolicAverage)}/${num(data.diastolicAverage)}`} unit="mmHg" sub={`${bp.length} ${bp.length === 1 ? "reading" : "readings"}`} />
              <Stat label="Latest" value={`${bp[bp.length - 1].systolic}/${bp[bp.length - 1].diastolic}`} unit="mmHg" sub={`${dayLabel(isoDate(new Date(bp[bp.length - 1].at)))}, ${clock(bp[bp.length - 1].at)}`} />
              <Stat label="Your target" value={`under ${targets.systolicHigh}/${targets.diastolicHigh}`} />
            </div>
            <LineChart
              label="Blood pressure readings" name="Top" color="var(--c1)" dots={bp.length <= 40} area={false}
              points={bp.map((r) => ({ x: r.at, y: r.systolic }))}
              extra={[{ name: "Bottom", color: "var(--c2)", points: bp.map((r) => ({ x: r.at, y: r.diastolic })) }]}
              formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${Math.round(y)}`}
            />
            <ul className="legend">
              <li><span className="legend-key" style={{ background: "var(--c1)" }} />Systolic (top number)</li>
              <li><span className="legend-key" style={{ background: "var(--c2)" }} />Diastolic (bottom number)</li>
            </ul>
          </>
        )}
      </Card>
    ) },
    { id: "glucose", label: "Glucose", node: data && (
      <Card title="Glucose" icon={<Droplets size={18} />} className="span-2" action={
        <Segmented label="Glucose unit" value={unit} onChange={(u) => void setUnit(u)} options={[{ value: "MMOL_L", label: "mmol/L" }, { value: "MG_DL", label: "mg/dL" }]} />
      }>
        {glucose.length === 0 ? (
          <p className="muted">No glucose readings yet. Readings from a meter or sensor show here, with time in your target range.</p>
        ) : (
          <>
            <div className="stats">
              <Stat label="Average" value={glucoseValue(data.glucoseAverage ?? 0, unit)} unit={unitLabel(unit)} />
              {data.fastingAverage != null && <Stat label="Fasting average" value={glucoseValue(data.fastingAverage, unit)} unit={unitLabel(unit)} />}
              {data.timeInRange != null && <Stat label="In your range" value={data.timeInRange} unit="%" sub={`${glucoseValue(targets.glucoseLow, unit)}–${glucoseValue(targets.glucoseHigh, unit)} ${unitLabel(unit)}`} />}
              {data.gmi != null && <Stat label="A1c estimate (GMI)" value={num(data.gmi, 1)} unit="%" sub="From 14+ days of sensor data" />}
            </div>
            {data.timeInRange != null && <RangeBar below={data.timeBelow ?? 0} inRange={data.timeInRange} above={data.timeAbove ?? 0} />}
            <LineChart
              label="Glucose readings" color="var(--c1)" dots={glucose.length <= 40} area={false}
              band={[glucoseValue(targets.glucoseLow, unit), glucoseValue(targets.glucoseHigh, unit)]}
              points={glucose.map((g) => ({ x: g.at, y: glucoseValue(g.mmolL, unit) }))}
              formatX={(x) => `${shortDate(isoDate(new Date(x)))} ${clock(x)}`} formatY={(y) => (unit === "MG_DL" ? `${Math.round(y)}` : y.toFixed(1))}
            />
          </>
        )}
      </Card>
    ) },
    { id: "patterns", label: "Worth knowing", node: data && data.patterns.length > 0 && (
      <Card title="Worth knowing" icon={<Sparkles size={18} />} className="span-2">
        <div className="insights" style={{ margin: 0 }}>
          {data.patterns.map((p, i) => {
            const Icon = INSIGHT_ICONS[p.icon] ?? Sparkles;
            return <article key={i} className={`insight tone-${p.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{p.title}</h4><p>{p.body}</p></div></article>;
          })}
        </div>
      </Card>
    ) },
    { id: "readings", label: "Recent readings", node: data && (bp.length > 0 || glucose.length > 0) && (
      <Card title="Recent readings" icon={<ListIcon size={18} />} className="span-2" action={!readOnly && <CardAction label="Your targets" onClick={() => setOpen("targets")}><Settings2 size={18} /></CardAction>}>
        <Readings bp={[...bp].reverse()} glucose={[...glucose].reverse()} unit={unit} readOnly={readOnly} onChanged={reload} />
        <p className="muted small">Categories follow the American Heart Association. One reading isn't a diagnosis: patterns over days are what count. Your clinician may set different targets for you.</p>
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("vitals"), "Blood pressure & glucose", sections);

  return (
    <div className="page">
      <PageHeader title="Blood pressure & glucose" subtitle="Your readings, against your own targets" actions={<>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 7, label: "Week" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }]} />
        {!readOnly && <Button variant="primary" onClick={() => setOpen("bp")}><Plus size={18} /> Blood pressure</Button>}
        {!readOnly && <Button onClick={() => setOpen("glucose")}><Plus size={18} /> Glucose</Button>}
        {arranged.button}
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && data.bloodPressure.some((r) => r.systolic > 180 || r.diastolic > 120) && <CrisisNotice />}
      <div style={{ opacity: loading ? 0.6 : 1 }}>{arranged.render()}</div>
      {open === "bp" && <BloodPressureDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "glucose" && <GlucoseDialog unit={unit} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "targets" && profile && <TargetsDialog unit={unit} onClose={() => setOpen(null)} onSaved={reload} />}
    </div>
  );
}

function CrisisNotice() {
  return (
    <div className="notice notice-error" role="alert">
      A reading was above 180/120. Wait a few minutes and measure again. If it's still that high, contact your doctor straight away;
      with chest pain, shortness of breath, weakness, or trouble seeing or speaking, call emergency services.
    </div>
  );
}

/** Time below, in and above the range, as one bar. */
export function RangeBar({ below, inRange, above }: { below: number; inRange: number; above: number }) {
  return (
    <div className="range-bar-wrap">
      <div className="range-bar" role="img" aria-label={`${below}% below range, ${inRange}% in range, ${above}% above range`}>
        {below > 0 && <span style={{ width: `${below}%`, background: "var(--c3)" }} />}
        {inRange > 0 && <span style={{ width: `${inRange}%`, background: "var(--c2)" }} />}
        {above > 0 && <span style={{ width: `${above}%`, background: "var(--c1)" }} />}
      </div>
      <ul className="legend">
        <li><span className="legend-key" style={{ background: "var(--c3)" }} />Below <strong>{below}%</strong></li>
        <li><span className="legend-key" style={{ background: "var(--c2)" }} />In range <strong>{inRange}%</strong></li>
        <li><span className="legend-key" style={{ background: "var(--c1)" }} />Above <strong>{above}%</strong></li>
      </ul>
    </div>
  );
}

function Readings({ bp, glucose, unit, readOnly, onChanged }: { bp: BloodPressure[]; glucose: GlucoseReading[]; unit: GlucoseUnit; readOnly: boolean; onChanged: () => void }) {
  const rows = [
    ...bp.map((r) => ({ kind: "bp" as const, at: r.at, r })),
    ...glucose.filter((g) => g.context !== "CGM").map((g) => ({ kind: "glucose" as const, at: g.at, g })),
  ].sort((a, b) => b.at - a.at).slice(0, 20);
  if (rows.length === 0) return <p className="muted">Nothing logged in this range.</p>;
  const remove = async (fn: () => Promise<unknown>) => { try { await fn(); onChanged(); } catch (e) { errorToast(e); } };
  return (
    <ul className="rows">
      {rows.map((row) => row.kind === "bp" ? (
        <li key={`bp-${row.r.id ?? row.at}`} className="row">
          <div className="row-icon"><HeartPulse size={18} /></div>
          <div className="row-main">
            <div className="row-title">{row.r.systolic}/{row.r.diastolic} mmHg{row.r.pulse ? ` · pulse ${row.r.pulse}` : ""}</div>
            <div className="row-sub">{dayLabel(isoDate(new Date(row.at)))}, {clock(row.at)} · {BP_TEXT[bpCategory(row.r.systolic, row.r.diastolic)]}{row.r.arm ? ` · ${row.r.arm} arm` : ""}{row.r.source ? ` · ${row.r.source}` : ""}{row.r.note ? ` · ${row.r.note}` : ""}</div>
          </div>
          {!readOnly && row.r.id && <div className="row-actions"><Button variant="ghost" aria-label="Delete this reading" onClick={() => remove(() => api.deleteBloodPressure(row.r.id!))}><Trash2 size={16} /></Button></div>}
        </li>
      ) : (
        <li key={`g-${row.g.id ?? row.at}`} className="row">
          <div className="row-icon"><Droplets size={18} /></div>
          <div className="row-main">
            <div className="row-title">{glucoseText(row.g.mmolL, unit)}</div>
            <div className="row-sub">{dayLabel(isoDate(new Date(row.at)))}, {clock(row.at)} · {GLUCOSE_CONTEXTS.find((c) => c.id === row.g.context)?.label}{row.g.source ? ` · ${row.g.source}` : ""}{row.g.note ? ` · ${row.g.note}` : ""}</div>
          </div>
          {!readOnly && row.g.id && <div className="row-actions"><Button variant="ghost" aria-label="Delete this reading" onClick={() => remove(() => api.deleteGlucose(row.g.id!))}><Trash2 size={16} /></Button></div>}
        </li>
      ))}
    </ul>
  );
}

export function BloodPressureDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [sys, setSys] = useState("");
  const [dia, setDia] = useState("");
  const [pulse, setPulse] = useState("");
  const [arm, setArm] = useState<string>("");
  const [at, setAt] = useState(localInput(Date.now()));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const s = Number(sys);
  const d = Number(dia);
  const valid = s >= 50 && s <= 260 && d >= 30 && d <= 180 && s > d;
  const save = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      await api.addBloodPressure({ at: new Date(at).getTime(), systolic: s, diastolic: d, pulse: Number(pulse) || undefined, arm: arm || undefined, note: note.trim() || undefined });
      toast(s > 180 || d > 120 ? "Saved. That's very high: measure again in a few minutes." : "Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Blood pressure" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!valid} onClick={save}>Save</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="two-col">
          <Field label="Top number (systolic)"><input autoFocus inputMode="numeric" value={sys} onChange={(e) => setSys(e.target.value)} placeholder="e.g. 122" /></Field>
          <Field label="Bottom number (diastolic)"><input inputMode="numeric" value={dia} onChange={(e) => setDia(e.target.value)} placeholder="e.g. 78" /></Field>
        </div>
        <div className="two-col">
          <Field label="Pulse" help="Optional"><input inputMode="numeric" value={pulse} onChange={(e) => setPulse(e.target.value)} /></Field>
          <Field label="Arm">
            <select value={arm} onChange={(e) => setArm(e.target.value)}><option value="">Not noted</option><option value="left">Left</option><option value="right">Right</option></select>
          </Field>
        </div>
        <div className="two-col">
          <Field label="When"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. after coffee" /></Field>
        </div>
        {valid && <p className="muted small">That's <strong>{BP_TEXT[bpCategory(s, d)].toLowerCase()}</strong>.</p>}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

export function GlucoseDialog({ unit, onClose, onSaved }: { unit: GlucoseUnit; onClose: () => void; onSaved: () => void }) {
  const [value, setValue] = useState("");
  const [context, setContext] = useState<GlucoseContext>("FASTING");
  const [at, setAt] = useState(localInput(Date.now()));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const n = Number(value.replace(",", "."));
  const mmol = n ? toMmol(n, unit) : 0;
  const valid = mmol >= 1 && mmol <= 35;
  const save = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      await api.addGlucose({ at: new Date(at).getTime(), mmolL: mmol, context, note: note.trim() || undefined });
      toast(mmol < 3.9 ? "Saved. That's low: have something sugary, and check again in 15 minutes." : "Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Glucose" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!valid} onClick={save}>Save</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="two-col">
          <Field label={`Reading (${unitLabel(unit)})`}><input autoFocus inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} placeholder={unit === "MG_DL" ? "e.g. 95" : "e.g. 5.4"} /></Field>
          <Field label="When it was">
            <select value={context} onChange={(e) => setContext(e.target.value as GlucoseContext)}>{GLUCOSE_CONTEXTS.filter((c) => c.id !== "CGM").map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}</select>
          </Field>
        </div>
        <div className="two-col">
          <Field label="Time"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
          <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        </div>
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

function TargetsDialog({ unit, onClose, onSaved }: { unit: GlucoseUnit; onClose: () => void; onSaved: () => void }) {
  const profile = useSession((s) => s.profile)!;
  const t = profile.vitalsTargets ?? DEFAULT_TARGETS;
  const [sys, setSys] = useState(String(t.systolicHigh));
  const [dia, setDia] = useState(String(t.diastolicHigh));
  const [low, setLow] = useState(String(glucoseValue(t.glucoseLow, unit)));
  const [high, setHigh] = useState(String(glucoseValue(t.glucoseHigh, unit)));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      const next: VitalsTargets = {
        systolicHigh: Number(sys) || 130, diastolicHigh: Number(dia) || 80,
        glucoseLow: toMmol(Number(low.replace(",", ".")) || glucoseValue(3.9, unit), unit), glucoseHigh: toMmol(Number(high.replace(",", ".")) || glucoseValue(10, unit), unit),
        glucoseUnit: unit,
      };
      session.setProfile(await api.saveProfile({ ...profile, vitalsTargets: next }));
      toast("Targets saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Your targets" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <p className="muted">Use the targets your clinician gave you; these are the usual ones to start.</p>
      <div className="two-col">
        <Field label="Top number under"><input inputMode="numeric" value={sys} onChange={(e) => setSys(e.target.value)} /></Field>
        <Field label="Bottom number under"><input inputMode="numeric" value={dia} onChange={(e) => setDia(e.target.value)} /></Field>
      </div>
      <div className="two-col">
        <Field label={`Glucose from (${unitLabel(unit)})`}><input inputMode="decimal" value={low} onChange={(e) => setLow(e.target.value)} /></Field>
        <Field label={`Glucose to (${unitLabel(unit)})`}><input inputMode="decimal" value={high} onChange={(e) => setHigh(e.target.value)} /></Field>
      </div>
    </Modal>
  );
}
