import { useMemo, useState } from "react";
import { CalendarHeart, ChevronLeft, ChevronRight, Droplets, Sprout, Thermometer } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, num, shortDate, today } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import type { CycleDay, CycleOverview, CyclePhase, Flow, LifeStage, LifeStageInfo, Mucus, OvulationTest } from "../lib/types";
import { StageDialog, StagePanels, predicts, stageSubtitle } from "./LifeStage";
import { LineChart } from "../components/charts";
import { Button, Card, ErrorNotice, Field, IconButton, Modal, PageHeader, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";

const FLOWS: { id: Flow; label: string }[] = [
  { id: "SPOTTING", label: "Spotting" }, { id: "LIGHT", label: "Light" }, { id: "MEDIUM", label: "Medium" }, { id: "HEAVY", label: "Heavy" },
];
const SYMPTOMS = ["cramps", "headache", "migraine", "bloating", "breast tenderness", "acne", "back pain", "fatigue", "nausea", "cravings",
  "trouble sleeping", "mood swings", "irritability", "anxiety", "low mood", "hot flashes", "night sweats", "dizziness"];
const PHASE_TEXT: Record<CyclePhase, string> = {
  PERIOD: "Period", FOLLICULAR: "Follicular phase", FERTILE: "Fertile window (estimate)", LUTEAL: "Luteal phase", UNKNOWN: "Not enough data yet",
};

export function Cycle() {
  const { data: raw, error, loading, reload } = useLoad(() => api.cycle(24), []);
  const readOnly = useReadOnly();
  const stageInfo = useLoad<LifeStageInfo>(() => api.lifeStage().catch(() => ({ stage: "CYCLING" as LifeStage })), []);
  const overview = useLoad(() => api.lifeStageOverview(30).catch(() => undefined), []);
  const [editing, setEditing] = useState<string | null>(null);
  const [choosing, setChoosing] = useState(false);
  const [month, setMonth] = useState(() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); });
  const stage = stageInfo.data?.stage ?? "CYCLING";
  const predict = predicts(stage);
  // No period predictions while pregnant, after a birth or after menopause: the calendar still logs bleeding.
  const data = raw && !predict ? { ...raw, prediction: undefined, daysUntilPeriod: undefined } : raw;
  const reloadAll = () => { reload(); stageInfo.reload(); overview.reload(); };
  return (
    <div className="page">
      <PageHeader title="Cycle" subtitle={stageInfo.data ? stageSubtitle(stageInfo.data) : "Your periods, symptoms and what comes next"} actions={!readOnly && <>
        <Button onClick={() => setChoosing(true)}><Sprout size={18} /> {stage === "CYCLING" ? "Life stage" : "Change stage"}</Button>
        <Button variant="primary" onClick={() => setEditing(today())}><Droplets size={18} /> Log today</Button>
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <Spinner />}
      {data && (
        <div className="grid grid-2">
          {overview.data && <StagePanels overview={overview.data} readOnly={readOnly} onChanged={overview.reload} />}
          {predict && <Card title="Now" icon={<CalendarHeart size={18} />}>
            <div className="stats">
              <Stat label="Cycle day" value={data.cycleDay ?? "–"} sub={PHASE_TEXT[data.phase]} />
              <Stat label="Next period" value={untilText(data.daysUntilPeriod)} sub={data.prediction ? `${shortDate(data.prediction.earliest)} – ${shortDate(data.prediction.latest)}` : "Log a period to start"} />
            </div>
            {data.prediction && <p className="muted small">{data.prediction.confidence === "low" ? "Low confidence: predictions get better after three cycles." : `${data.prediction.confidence[0].toUpperCase()}${data.prediction.confidence.slice(1)} confidence.`} {data.caveat}</p>}
            {stage === "PERIMENOPAUSE" && <p className="muted small">Cycles often vary a lot in perimenopause, so the range is wider and worth taking loosely.</p>}
          </Card>}
          {predict && <Card title="Averages">
            <div className="stats">
              <Stat label="Cycle" value={data.averageCycle != null ? num(data.averageCycle, 0) : "–"} unit="days" sub={data.variation != null ? `± ${num(data.variation, 1)} days` : undefined} />
              <Stat label="Period" value={data.averagePeriod != null ? num(data.averagePeriod, 0) : "–"} unit="days" />
              <Stat label="Cycles logged" value={data.cycles.length} />
            </div>
          </Card>}
          <Card className="span-2" title={month.toLocaleDateString(undefined, { month: "long", year: "numeric" })} action={<>
            <IconButton label="Previous month" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}><ChevronLeft size={20} /></IconButton>
            <IconButton label="Next month" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}><ChevronRight size={20} /></IconButton>
          </>}>
            <Calendar month={month} data={data} onPick={setEditing} />
            <ul className="legend">
              <li><span className="legend-key flow-key" />{predict ? "Period" : "Bleeding"}</li>
              {predict && <li><span className="legend-key predicted-key" />Predicted period</li>}
              {predict && <li><span className="legend-key fertile-key" />Fertile window (estimate)</li>}
              <li><span className="legend-key symptom-key" />Symptoms logged</li>
            </ul>
          </Card>
          {data.temperatures.length > 4 && (
            <Card title="Temperature" icon={<Thermometer size={18} />} className="span-2">
              <LineChart
                label="Nightly temperature" color="var(--c3)" dots
                points={data.temperatures.map((t) => ({ x: new Date(`${t.date}T12:00`).getTime(), y: t.value }))}
                formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${y > 30 ? y.toFixed(2) : (y >= 0 ? "+" : "−") + Math.abs(y).toFixed(2)} °C`}
              />
              <p className="muted small">A rise that lasts three days or more usually means ovulation has happened.</p>
            </Card>
          )}
          {data.cycles.length > 0 && (
            <Card title="Past cycles" className="span-2">
              <ul className="rows">
                {[...data.cycles].reverse().slice(0, 12).map((c) => (
                  <li key={c.start} className="row">
                    <div className="row-main">
                      <div className="row-title">{new Date(`${c.start}T12:00`).toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" })}</div>
                      <div className="row-sub">Period {c.periodLength} days{c.ovulation ? ` · temperature rise around ${shortDate(c.ovulation)}` : ""}</div>
                    </div>
                    <div className="row-end">{c.length ? `${c.length} days` : "Now"}</div>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      )}
      {editing && !readOnly && <DayDialog date={editing} day={data?.days.find((d) => d.date === editing)} onClose={() => setEditing(null)} onSaved={reload} />}
      {choosing && stageInfo.data && <StageDialog info={stageInfo.data} onClose={() => setChoosing(false)} onSaved={reloadAll} />}
    </div>
  );
}

function untilText(days?: number): string {
  if (days == null) return "–";
  if (days === 0) return "Today";
  if (days === 1) return "Tomorrow";
  if (days > 0) return `In ${days} days`;
  return `${-days} days late`;
}

function Calendar({ month, data, onPick }: { month: Date; data: CycleOverview; onPick: (date: string) => void }) {
  const byDate = useMemo(() => new Map(data.days.map((d) => [d.date, d])), [data]);
  const p = data.prediction;
  const inRange = (d: string, a?: string, b?: string) => !!a && !!b && d >= a && d <= b;
  const predictedEnd = p ? isoDate(new Date(new Date(`${p.nextPeriod}T12:00`).getTime() + ((data.averagePeriod ?? 5) - 1) * 86_400_000)) : undefined;
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const offset = (first.getDay() + 6) % 7; // Monday first
  const length = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const cells = Array.from({ length: offset + length }, (_, i) => (i < offset ? null : new Date(month.getFullYear(), month.getMonth(), i - offset + 1)));
  const now = today();
  return (
    <div className="calendar" role="grid" aria-label="Cycle calendar">
      {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => <div key={d} className="calendar-head">{d}</div>)}
      {cells.map((date, i) => {
        if (!date) return <div key={i} />;
        const key = isoDate(date);
        const day = byDate.get(key);
        const classes = [
          "calendar-day",
          day?.flow ? `flow-${day.flow.toLowerCase()}` : "",
          !day?.flow && key > now && inRange(key, p?.nextPeriod, predictedEnd) ? "is-predicted" : "",
          !day?.flow && inRange(key, p?.fertileStart, p?.fertileEnd) ? "is-fertile" : "",
          key === now ? "is-today" : "",
        ].join(" ");
        return (
          <button key={key} className={classes} onClick={() => onPick(key)} aria-label={`${date.toLocaleDateString(undefined, { day: "numeric", month: "long" })}${day?.flow ? `, ${day.flow.toLowerCase()} flow` : ""}`}>
            <span>{date.getDate()}</span>
            {day && (day.symptoms.length > 0 || day.pain != null) && <i className="symptom-dot" />}
          </button>
        );
      })}
    </div>
  );
}

function DayDialog({ date, day, onClose, onSaved }: { date: string; day?: CycleDay; onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState<CycleDay>(day ?? { date, symptoms: [] });
  const [custom, setCustom] = useState("");
  const [busy, setBusy] = useState(false);
  const toggle = (s: string) => setD({ ...d, symptoms: d.symptoms.includes(s) ? d.symptoms.filter((x) => x !== s) : [...d.symptoms, s] });
  const save = async () => {
    setBusy(true);
    try { await api.saveCycleDay(d); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title={new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })} onClose={onClose} footer={<>
      {day && <Button variant="ghost" onClick={async () => { try { await api.deleteCycleDay(date); onSaved(); onClose(); } catch (e) { errorToast(e); } }}>Clear the day</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button>
    </>}>
      <Field label="Bleeding">
        <div className="tag-list">
          <button type="button" className={`chip ${!d.flow ? "is-on" : ""}`} onClick={() => setD({ ...d, flow: undefined })}>None</button>
          {FLOWS.map((f) => <button key={f.id} type="button" className={`chip ${d.flow === f.id ? "is-on" : ""}`} onClick={() => setD({ ...d, flow: f.id })}>{f.label}</button>)}
        </div>
      </Field>
      <Field label="Symptoms">
        <div className="tag-list">
          {[...new Set([...SYMPTOMS, ...d.symptoms])].map((s) => <button key={s} type="button" className={`chip ${d.symptoms.includes(s) ? "is-on" : ""}`} aria-pressed={d.symptoms.includes(s)} onClick={() => toggle(s)}>{s}</button>)}
        </div>
      </Field>
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); if (custom.trim()) { toggle(custom.trim().toLowerCase()); setCustom(""); } }}>
        <Field label="Another symptom"><input value={custom} onChange={(e) => setCustom(e.target.value)} /></Field>
        <Button type="submit">Add</Button>
      </form>
      <Field label={`Pain${d.pain != null ? `: ${d.pain} of 10` : ""}`}>
        <input type="range" min={0} max={10} value={d.pain ?? 0} onChange={(e) => setD({ ...d, pain: Number(e.target.value) })} style={{ ["--fill" as string]: `${(d.pain ?? 0) * 10}%` }} aria-label="Pain, 0 to 10" />
      </Field>
      <div className="two-col">
        <Field label="Ovulation test">
          <select value={d.ovulationTest ?? ""} onChange={(e) => setD({ ...d, ovulationTest: (e.target.value || undefined) as OvulationTest | undefined })}>
            <option value="">Not taken</option><option value="NEGATIVE">Negative</option><option value="POSITIVE">Positive</option><option value="PEAK">Peak</option>
          </select>
        </Field>
        <Field label="Cervical mucus">
          <select value={d.mucus ?? ""} onChange={(e) => setD({ ...d, mucus: (e.target.value || undefined) as Mucus | undefined })}>
            <option value="">Not noted</option><option value="DRY">Dry</option><option value="STICKY">Sticky</option><option value="CREAMY">Creamy</option><option value="WATERY">Watery</option><option value="EGG_WHITE">Egg white</option>
          </select>
        </Field>
      </div>
      <div className="two-col">
        <Field label="Basal temperature (°C)" help="Taken on waking, before getting up"><input inputMode="decimal" value={d.temperature ?? ""} onChange={(e) => setD({ ...d, temperature: e.target.value ? Number(e.target.value.replace(",", ".")) : undefined })} /></Field>
        <Field label="Note"><input value={d.note ?? ""} onChange={(e) => setD({ ...d, note: e.target.value || undefined })} /></Field>
      </div>
    </Modal>
  );
}
