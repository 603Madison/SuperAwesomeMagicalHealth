import { useState } from "react";
import { Ruler } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, localInput, num, shortDate } from "../lib/format";
import type { MeasurementSet, Metric, Sample } from "../lib/types";
import { BODY_MEASUREMENTS } from "../lib/types";
import { LineChart } from "./charts";
import { Button, Card, Field, Modal, Segmented, errorToast, toast, useLoad } from "./ui";

const CM_PER_IN = 2.54;

/** Body measurements (waist, hips, chest…) in cm or inches, charted over time like weight. */
export function MeasurementsCard({ imperial, readOnly, metrics = BODY_MEASUREMENTS.map((m) => m.id), sets, onSaved }: {
  imperial: boolean; readOnly: boolean; metrics?: Metric[];
  /** Already loaded (TransitionOverview.measurements): used instead of asking for each metric. */
  sets?: MeasurementSet[];
  onSaved?: () => void;
}) {
  const [metric, setMetric] = useState<Metric>(metrics[0]);
  const [adding, setAdding] = useState(false);
  const all = useLoad(() => (sets ? Promise.resolve([]) : Promise.all(metrics.map((m) => api.measurements(m, 365).then((s) => [m, s] as const).catch(() => [m, [] as Sample[]] as const)))), [metrics.join(), !!sets]);
  const byMetric = new Map<Metric, Sample[]>(sets
    ? metrics.map((m) => [m, sets.filter((s) => s.values[m] != null).map((s) => ({ metric: m, start: s.at, value: s.values[m]! }) as Sample)])
    : all.data ?? []);
  const series = byMetric.get(metric) ?? [];
  const show = (cm: number) => (imperial ? `${num(cm / CM_PER_IN, 1)} in` : `${num(cm, 1)} cm`);
  const latest = metrics.map((m) => ({ m, s: [...(byMetric.get(m) ?? [])].sort((a, b) => b.start - a.start)[0] })).filter((x) => x.s);
  return (
    <Card title="Measurements" icon={<Ruler size={18} />} className="span-2" action={!readOnly && <Button variant="tonal" onClick={() => setAdding(true)}>Measure</Button>}>
      {latest.length === 0 ? <p className="muted">Measure with a soft tape, in the same place and at the same time of day, every week or two. Changes in shape often show here before they show on the scale.</p> : (
        <>
          <div className="stats">
            {latest.map(({ m, s }) => <button key={m} className={`stat stat-button ${metric === m ? "is-on" : ""}`} onClick={() => setMetric(m)}>
              <span className="stat-label">{BODY_MEASUREMENTS.find((x) => x.id === m)?.label}</span>
              <span className="stat-value">{show(s!.value)}</span>
              <span className="stat-sub">{shortDate(isoDate(new Date(s!.start)))}</span>
            </button>)}
          </div>
          {series.length > 1 && (
            <LineChart label={BODY_MEASUREMENTS.find((x) => x.id === metric)?.label ?? ""} color="var(--c2)" dots
              points={[...series].sort((a, b) => a.start - b.start).map((s) => ({ x: s.start, y: imperial ? s.value / CM_PER_IN : s.value }))}
              formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${num(y, 1)} ${imperial ? "in" : "cm"}`} />
          )}
        </>
      )}
      {adding && <MeasureDialog imperial={imperial} metrics={metrics} onClose={() => setAdding(false)} onSaved={() => { all.reload(); onSaved?.(); }} />}
    </Card>
  );
}

function MeasureDialog({ imperial, metrics, onClose, onSaved }: { imperial: boolean; metrics: Metric[]; onClose: () => void; onSaved: () => void }) {
  const [unit, setUnit] = useState<"cm" | "in">(imperial ? "in" : "cm");
  const [values, setValues] = useState<Record<string, string>>({});
  const [at, setAt] = useState(localInput(Date.now()));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    const when = new Date(at).getTime();
    const entries = Object.entries(values).map(([m, v]) => [m, Number(v.replace(",", "."))] as const).filter(([, v]) => v > 0);
    if (entries.length === 0) return;
    setBusy(true);
    try {
      for (const [m, v] of entries) await api.addMeasurement(m as Metric, Math.round((unit === "in" ? v * CM_PER_IN : v) * 10) / 10, when);
      toast("Measurements saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Measurements" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <Segmented label="Unit" value={unit} onChange={setUnit} options={[{ value: "cm", label: "cm" }, { value: "in", label: "inches" }]} />
      <div className="measure-grid">
        {metrics.map((m) => (
          <Field key={m} label={BODY_MEASUREMENTS.find((x) => x.id === m)?.label ?? m}>
            <input inputMode="decimal" value={values[m] ?? ""} onChange={(e) => setValues({ ...values, [m]: e.target.value })} />
          </Field>
        ))}
      </div>
      <Field label="When"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
    </Modal>
  );
}
