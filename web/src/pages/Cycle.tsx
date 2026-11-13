import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { CalendarHeart, ChevronLeft, ChevronRight, Droplets, Minus, Plus, Settings as SettingsIcon, Sprout, Thermometer, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, num, shortDate, today } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import { F, useFields } from "../lib/fields";
import type {
  CycleDay, CycleOverview, CyclePhase, CycleSettings, Flow, LifeStage, LifeStageInfo, Mucus, OvulationTest, ProductUse, Supply, SupplyKind,
} from "../lib/types";
import { CLOTS, PREGNANCY_TESTS, SUPPLY_KINDS, supplyKindLabel } from "../lib/types";
import { StageDialog, StagePanels, predicts, stageSubtitle } from "./LifeStage";
import { LineChart } from "../components/charts";
import { Button, Card, ErrorNotice, Field, IconButton, Modal, PageHeader, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { ChipPicker, CustomFieldInputs, CustomFieldsBuilder, FieldsEditor, FullnessPicker, OneOf, ScaleRow, WordList } from "../components/FieldKit";

const FLOWS: { id: Flow; label: string }[] = [
  { id: "SPOTTING", label: "Spotting" }, { id: "LIGHT", label: "Light" }, { id: "MEDIUM", label: "Medium" }, { id: "HEAVY", label: "Heavy" },
];
const SYMPTOMS = ["cramps", "headache", "migraine", "bloating", "breast tenderness", "acne", "back pain", "fatigue", "nausea", "cravings",
  "trouble sleeping", "mood swings", "irritability", "anxiety", "low mood", "hot flashes", "night sweats", "dizziness"];
const MOODS = ["calm", "happy", "energetic", "sensitive", "irritable", "anxious", "low", "tearful", "foggy"];
const PAIN_AREAS = ["lower belly", "lower back", "pelvis", "thighs", "rectum", "chest", "head", "joints"];
const COLORS = ["bright red", "dark red", "brown", "pink", "black"];
const DISCHARGE = ["none", "creamy", "watery", "sticky", "unusual colour", "unusual smell", "itchy"];
const PHASE_TEXT: Record<CyclePhase, string> = {
  PERIOD: "Period", FOLLICULAR: "Follicular phase", FERTILE: "Fertile window (estimate)", LUTEAL: "Luteal phase", UNKNOWN: "Not enough data yet",
};
/** Plain words for the phases, for people who'd rather not see fertility terms. */
const NEUTRAL_PHASE_TEXT: Record<CyclePhase, string> = {
  PERIOD: "Period", FOLLICULAR: "First half", FERTILE: "Mid-cycle", LUTEAL: "Second half", UNKNOWN: "Not enough data yet",
};
export const DEFAULT_CYCLE_SETTINGS: CycleSettings = {
  symptoms: [], hiddenSymptoms: [], moods: [], painAreas: [], customFields: [], remindBeforePeriod: 2, remindLateAfter: 3, neutralWording: false,
};

export function Cycle() {
  const { has } = useFields();
  const { data: raw, error, loading, reload } = useLoad(() => api.cycle(24), []);
  const readOnly = useReadOnly();
  const stageInfo = useLoad<LifeStageInfo>(() => api.lifeStage().catch(() => ({ stage: "CYCLING" as LifeStage })), []);
  const overview = useLoad(() => api.lifeStageOverview(30).catch(() => undefined), []);
  const settings = useLoad(() => api.cycleSettings().catch(() => DEFAULT_CYCLE_SETTINGS), []);
  // Loaded whatever the fields say: the field layout may still be loading, and the list is small.
  const supplies = useLoad(() => api.supplies().catch(() => [] as Supply[]), []);
  const [editing, setEditing] = useState<string | null>(null);
  const [choosing, setChoosing] = useState(false);
  const [month, setMonth] = useState(() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); });
  const stage = stageInfo.data?.stage ?? "CYCLING";
  const predict = predicts(stage);
  const s = settings.data ?? DEFAULT_CYCLE_SETTINGS;
  // Fertility words and the fertile window show only when wanted.
  const fertility = has(F.CYCLE_FERTILITY) && !s.neutralWording;
  const phaseText = fertility ? PHASE_TEXT : NEUTRAL_PHASE_TEXT;
  // No period predictions while pregnant, after a birth or after menopause: the calendar still logs bleeding.
  const data = raw && !predict ? { ...raw, prediction: undefined, daysUntilPeriod: undefined } : raw;
  const reloadAll = () => { reload(); stageInfo.reload(); overview.reload(); };
  return (
    <div className="page">
      <PageHeader title="Cycle" subtitle={stageInfo.data ? stageSubtitle(stageInfo.data) : "Your periods, symptoms and what comes next"} actions={!readOnly && <>
        <Button onClick={() => setChoosing(true)}><Sprout size={18} /> {stage === "CYCLING" ? "Life stage" : "Change stage"}</Button>
        <Button variant="primary" onClick={() => setEditing(today())}><Droplets size={18} /> Log today</Button>
        <Link className="btn btn-ghost" to="/cycle/settings" aria-label="Cycle settings"><SettingsIcon size={18} /></Link>
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <Spinner />}
      {data && (
        <div className="grid grid-2">
          {overview.data && <StagePanels overview={overview.data} readOnly={readOnly} onChanged={overview.reload} />}
          {predict && <Card title="Now" icon={<CalendarHeart size={18} />}>
            <div className="stats">
              <Stat label="Cycle day" value={data.cycleDay ?? "–"} sub={phaseText[data.phase]} />
              <Stat label="Next period" value={untilText(data.daysUntilPeriod)} sub={data.prediction ? `${shortDate(data.prediction.earliest)} – ${shortDate(data.prediction.latest)}` : "Log a period to start"} />
            </div>
            {data.prediction && <p className="muted small">{data.prediction.confidence === "low" ? "Low confidence: predictions get better after three cycles." : `${data.prediction.confidence[0].toUpperCase()}${data.prediction.confidence.slice(1)} confidence.`} {fertility ? data.caveat : ""}</p>}
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
            <Calendar month={month} data={data} fertility={fertility} onPick={(d) => !readOnly && setEditing(d)} />
            <ul className="legend">
              <li><span className="legend-key flow-key" />{predict ? "Period" : "Bleeding"}</li>
              {predict && <li><span className="legend-key predicted-key" />Predicted period</li>}
              {predict && fertility && <li><span className="legend-key fertile-key" />Fertile window (estimate)</li>}
              <li><span className="legend-key symptom-key" />Symptoms logged</li>
            </ul>
          </Card>
          {data.temperatures.length > 4 && (has(F.CYCLE_BBT) || fertility) && (
            <Card title="Temperature" icon={<Thermometer size={18} />} className="span-2">
              <LineChart
                label="Nightly temperature" color="var(--c3)" dots
                points={data.temperatures.map((t) => ({ x: new Date(`${t.date}T12:00`).getTime(), y: t.value }))}
                formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${y > 30 ? y.toFixed(2) : (y >= 0 ? "+" : "−") + Math.abs(y).toFixed(2)} °C`}
              />
              <p className="muted small">{fertility ? "A rise that lasts three days or more usually means ovulation has happened." : "Temperature usually rises a little in the second half of a cycle."}</p>
            </Card>
          )}
          {data.cycles.length > 0 && (
            <Card title="Past cycles" className="span-2">
              <ul className="rows">
                {[...data.cycles].reverse().slice(0, 12).map((c) => (
                  <li key={c.start} className="row">
                    <div className="row-main">
                      <div className="row-title">{new Date(`${c.start}T12:00`).toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" })}</div>
                      <div className="row-sub">Period {c.periodLength} days{c.ovulation && fertility ? ` · temperature rise around ${shortDate(c.ovulation)}` : ""}</div>
                    </div>
                    <div className="row-end">{c.length ? `${c.length} days` : predict ? "Now" : ""}</div>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      )}
      {editing && !readOnly && (
        <DayDialog date={editing} day={data?.days.find((d) => d.date === editing)} settings={s} supplies={supplies.data ?? []} fertility={fertility}
          onClose={() => setEditing(null)} onSaved={reload} />
      )}
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

function Calendar({ month, data, fertility, onPick }: { month: Date; data: CycleOverview; fertility: boolean; onPick: (date: string) => void }) {
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
          fertility && !day?.flow && inRange(key, p?.fertileStart, p?.fertileEnd) ? "is-fertile" : "",
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

const PERIOD_KINDS = SUPPLY_KINDS.filter((k) => k.category === "PERIOD").map((k) => k.id);

/** A day, in as much detail as the person wants: only the fields they use show. */
function DayDialog({ date, day, settings, supplies, fertility, onClose, onSaved }: {
  date: string; day?: CycleDay; settings: CycleSettings; supplies: Supply[]; fertility: boolean; onClose: () => void; onSaved: () => void;
}) {
  const { has } = useFields();
  const [d, setD] = useState<CycleDay>(day ?? { date, symptoms: [] });
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await api.saveCycleDay(d); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  const symptoms = [...new Set([...settings.symptoms, ...SYMPTOMS])].filter((x) => !settings.hiddenSymptoms.includes(x));
  const periodSupplies = supplies.filter((x) => x.active && PERIOD_KINDS.includes(x.kind));
  const products = d.products ?? [];
  const setProducts = (p: ProductUse[]) => setD({ ...d, products: p });
  const addProduct = (kind: SupplyKind, supplyId?: string) => setProducts([...products, { kind, count: 1, supplyId }]);
  return (
    <Modal wide title={new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })} onClose={onClose} footer={<>
      {day && <Button variant="ghost" onClick={async () => { try { await api.deleteCycleDay(date); onSaved(); onClose(); } catch (e) { errorToast(e); } }}>Clear the day</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button>
    </>}>
      {has(F.CYCLE_FLOW) && (
        <Field label="Bleeding">
          <div className="tag-list">
            <button type="button" className={`chip ${!d.flow ? "is-on" : ""}`} onClick={() => setD({ ...d, flow: undefined })}>None</button>
            {FLOWS.map((f) => <button key={f.id} type="button" className={`chip ${d.flow === f.id ? "is-on" : ""}`} onClick={() => setD({ ...d, flow: f.id })}>{f.label}</button>)}
          </div>
        </Field>
      )}
      {d.flow && has(F.CYCLE_CLOTS) && <OneOf label="Clots" options={CLOTS} value={d.clots} onChange={(clots) => setD({ ...d, clots })} />}
      {d.flow && has(F.CYCLE_COLOR) && <ChipPicker label="Colour" multi={false} options={COLORS} value={d.color ? [d.color] : []} onChange={(v) => setD({ ...d, color: v[0] })} />}
      {has(F.CYCLE_PRODUCTS) && (
        <div className="subsection">
          <h4 className="subsection-title">Products used</h4>
          {products.map((p, i) => {
            const supply = supplies.find((x) => x.id === p.supplyId);
            return (
              <div key={i} className="product-row">
                <span className="product-name">{supply?.name ?? supplyKindLabel(p.kind)}</span>
                <div className="stepper">
                  <Button variant="tonal" aria-label="One fewer" onClick={() => setProducts(p.count <= 1 ? products.filter((_, j) => j !== i) : products.map((x, j) => (j === i ? { ...x, count: x.count - 1 } : x)))}><Minus size={14} /></Button>
                  <strong>{p.count}</strong>
                  <Button variant="tonal" aria-label="One more" onClick={() => setProducts(products.map((x, j) => (j === i ? { ...x, count: x.count + 1 } : x)))}><Plus size={14} /></Button>
                </div>
                <FullnessPicker label="How full, at its fullest" value={p.fullness} onChange={(fullness) => setProducts(products.map((x, j) => (j === i ? { ...x, fullness } : x)))} />
                <Button variant="ghost" aria-label="Remove" onClick={() => setProducts(products.filter((_, j) => j !== i))}><Trash2 size={16} /></Button>
              </div>
            );
          })}
          <div className="tag-list">
            {periodSupplies.map((x) => <button key={x.id} type="button" className="chip" onClick={() => addProduct(x.kind, x.id)}><Plus size={14} /> {x.name}</button>)}
            {periodSupplies.length === 0 && SUPPLY_KINDS.filter((k) => k.category === "PERIOD").map((k) => <button key={k.id} type="button" className="chip" onClick={() => addProduct(k.id)}><Plus size={14} /> {k.label}</button>)}
          </div>
          {periodSupplies.length === 0 && <p className="muted small">Add yours on <Link to="/supplies">Supplies</Link> to count them down.</p>}
        </div>
      )}
      <ChipPicker label="Symptoms" options={symptoms} value={d.symptoms} onChange={(list) => {
        const severity = Object.fromEntries(Object.entries(d.symptomSeverity ?? {}).filter(([k]) => list.includes(k)));
        setD({ ...d, symptoms: list, symptomSeverity: severity });
      }} />
      {has(F.CYCLE_SYMPTOM_SEVERITY) && d.symptoms.length > 0 && (
        <Field label="How bad">
          <div className="severity-list">
            {d.symptoms.map((sym) => (
              <div key={sym} className="severity-row">
                <span>{sym}</span>
                <div className="scale" role="radiogroup" aria-label={`How bad: ${sym}`}>
                  {["Mild", "Moderate", "Severe"].map((l, i) => {
                    const v = i + 1;
                    const on = d.symptomSeverity?.[sym] === v;
                    return <button key={l} type="button" role="radio" aria-checked={on} className={on ? "is-on" : ""}
                      onClick={() => setD({ ...d, symptomSeverity: { ...(d.symptomSeverity ?? {}), [sym]: v } })}>{l}</button>;
                  })}
                </div>
              </div>
            ))}
          </div>
        </Field>
      )}
      {has(F.CYCLE_PAIN) && (
        <Field label={`Pain${d.pain != null ? `: ${d.pain} of 10` : ""}`}>
          <input type="range" min={0} max={10} value={d.pain ?? 0} onChange={(e) => setD({ ...d, pain: Number(e.target.value) })} style={{ ["--fill" as string]: `${(d.pain ?? 0) * 10}%` }} aria-label="Pain, 0 to 10" />
        </Field>
      )}
      {has(F.CYCLE_PAIN_AREAS) && (d.pain ?? 0) > 0 && (
        <ChipPicker label="Where it hurts" options={[...new Set([...settings.painAreas, ...PAIN_AREAS])]} value={d.painAreas ?? []} onChange={(painAreas) => setD({ ...d, painAreas })} />
      )}
      {has(F.CYCLE_MOOD) && <ChipPicker label="Mood" options={[...new Set([...settings.moods, ...MOODS])]} value={d.mood ?? []} onChange={(mood) => setD({ ...d, mood })} />}
      {has(F.CYCLE_ENERGY) && <ScaleRow label="Energy" low="Drained" high="Lots" value={d.energy} onChange={(energy) => setD({ ...d, energy })} />}
      {has(F.CYCLE_LIBIDO) && <ScaleRow label="Sex drive" low="Low" high="High" value={d.libido} onChange={(libido) => setD({ ...d, libido })} />}
      {has(F.CYCLE_SEX) && (
        <div className="two-col">
          <Toggle label="Had sex" checked={!!d.sex?.had} onChange={(had) => setD({ ...d, sex: had ? { had, protected: d.sex?.protected } : undefined })} />
          {d.sex?.had && <Toggle label="Protected" checked={!!d.sex.protected} onChange={(p) => setD({ ...d, sex: { ...d.sex!, protected: p } })} />}
        </div>
      )}
      {(has(F.CYCLE_OVULATION_TEST) || has(F.CYCLE_MUCUS)) && (
        <div className="two-col">
          {has(F.CYCLE_OVULATION_TEST) && (
            <Field label={fertility ? "Ovulation test" : "LH test"}>
              <select value={d.ovulationTest ?? ""} onChange={(e) => setD({ ...d, ovulationTest: (e.target.value || undefined) as OvulationTest | undefined })}>
                <option value="">Not taken</option><option value="NEGATIVE">Negative</option><option value="POSITIVE">Positive</option><option value="PEAK">Peak</option>
              </select>
            </Field>
          )}
          {has(F.CYCLE_MUCUS) && (
            <Field label="Cervical mucus">
              <select value={d.mucus ?? ""} onChange={(e) => setD({ ...d, mucus: (e.target.value || undefined) as Mucus | undefined })}>
                <option value="">Not noted</option><option value="DRY">Dry</option><option value="STICKY">Sticky</option><option value="CREAMY">Creamy</option><option value="WATERY">Watery</option><option value="EGG_WHITE">Egg white</option>
              </select>
            </Field>
          )}
        </div>
      )}
      {has(F.CYCLE_LH) && <Field label="LH level" help="If your reader shows a number"><input inputMode="decimal" value={d.lh ?? ""} onChange={(e) => setD({ ...d, lh: e.target.value ? Number(e.target.value.replace(",", ".")) : undefined })} /></Field>}
      {has(F.CYCLE_CERVIX) && (
        <div className="three-col">
          <Field label="Cervix position"><select value={d.cervix?.position ?? ""} onChange={(e) => setD({ ...d, cervix: { ...d.cervix, position: e.target.value || undefined } })}><option value="">–</option><option>low</option><option>medium</option><option>high</option></select></Field>
          <Field label="Firmness"><select value={d.cervix?.firmness ?? ""} onChange={(e) => setD({ ...d, cervix: { ...d.cervix, firmness: e.target.value || undefined } })}><option value="">–</option><option>firm</option><option>medium</option><option>soft</option></select></Field>
          <Field label="Opening"><select value={d.cervix?.opening ?? ""} onChange={(e) => setD({ ...d, cervix: { ...d.cervix, opening: e.target.value || undefined } })}><option value="">–</option><option>closed</option><option>a little</option><option>open</option></select></Field>
        </div>
      )}
      {has(F.CYCLE_PREGNANCY_TEST) && <OneOf label="Pregnancy test" options={PREGNANCY_TESTS} value={d.pregnancyTest} onChange={(pregnancyTest) => setD({ ...d, pregnancyTest })} />}
      {has(F.CYCLE_DISCHARGE) && <ChipPicker label="Discharge" multi={false} options={DISCHARGE} value={d.discharge ? [d.discharge] : []} onChange={(v) => setD({ ...d, discharge: v[0] })} />}
      <div className="two-col">
        {has(F.CYCLE_BBT) && <Field label="Basal temperature (°C)" help="Taken on waking, before getting up"><input inputMode="decimal" value={d.temperature ?? ""} onChange={(e) => setD({ ...d, temperature: e.target.value ? Number(e.target.value.replace(",", ".")) : undefined })} /></Field>}
        <Field label="Note"><input value={d.note ?? ""} onChange={(e) => setD({ ...d, note: e.target.value || undefined })} /></Field>
      </div>
      <CustomFieldInputs fields={settings.customFields} values={d.details ?? {}} onChange={(details) => setD({ ...d, details })} />
    </Modal>
  );
}

/** Settings › Cycle: your own symptoms and words, lengths, reminders, neutral wording, and which details show. */
export function CycleSettingsPage() {
  const settings = useLoad(() => api.cycleSettings().catch(() => DEFAULT_CYCLE_SETTINGS), []);
  const [s, setS] = useState<CycleSettings | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (settings.data && !s) setS(settings.data); }, [settings.data, s]);
  if (!s) return <div className="page"><PageHeader title="Cycle settings" /><Spinner /></div>;
  const numberOrUndefined = (v: string) => (v.trim() ? Number(v) : undefined);
  const save = async () => {
    setBusy(true);
    try { setS(await api.saveCycleSettings(s)); toast("Settings saved"); } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  return (
    <div className="page">
      <PageHeader title="Cycle settings" subtitle="Your words, your lengths, your reminders, and what to record"
        actions={<><Link className="btn btn-ghost" to="/cycle">Back</Link><Button variant="primary" busy={busy} onClick={save}>Save</Button></>} />
      <div className="grid grid-2">
        <Card title="Your own words">
          <WordList label="Symptoms you get" words={s.symptoms} onChange={(symptoms) => setS({ ...s, symptoms })} placeholder="e.g. jaw pain" help="Shown first" />
          <WordList label="Symptoms to leave out" words={s.hiddenSymptoms} onChange={(hiddenSymptoms) => setS({ ...s, hiddenSymptoms })} placeholder="e.g. hot flashes" />
          <WordList label="Moods" words={s.moods} onChange={(moods) => setS({ ...s, moods })} />
          <WordList label="Where pain can be" words={s.painAreas} onChange={(painAreas) => setS({ ...s, painAreas })} placeholder="e.g. left hip" />
        </Card>
        <Card title="Lengths and reminders">
          <p className="muted small">Leave these empty to use your own averages.</p>
          <div className="three-col">
            <Field label="Cycle (days)"><input inputMode="numeric" value={s.cycleLength ?? ""} onChange={(e) => setS({ ...s, cycleLength: numberOrUndefined(e.target.value) })} /></Field>
            <Field label="Period (days)"><input inputMode="numeric" value={s.periodLength ?? ""} onChange={(e) => setS({ ...s, periodLength: numberOrUndefined(e.target.value) })} /></Field>
            <Field label="Second half (days)" help="14 to start"><input inputMode="numeric" value={s.lutealLength ?? ""} onChange={(e) => setS({ ...s, lutealLength: numberOrUndefined(e.target.value) })} /></Field>
          </div>
          <div className="three-col">
            <Field label="Remind before (days)"><input inputMode="numeric" value={s.remindBeforePeriod ?? ""} onChange={(e) => setS({ ...s, remindBeforePeriod: numberOrUndefined(e.target.value) })} /></Field>
            <Field label="When late by (days)"><input inputMode="numeric" value={s.remindLateAfter ?? ""} onChange={(e) => setS({ ...s, remindLateAfter: numberOrUndefined(e.target.value) })} /></Field>
            <Field label="Daily nudge"><input type="time" value={s.dailyReminder ?? ""} onChange={(e) => setS({ ...s, dailyReminder: e.target.value || undefined })} /></Field>
          </div>
          <Toggle label="Plain wording" help="No 'fertile window' or 'ovulation' anywhere: for when you're tracking for your health, not to conceive or avoid it"
            checked={s.neutralWording} onChange={(neutralWording) => setS({ ...s, neutralWording })} />
        </Card>
        <Card title="Fields of your own" className="span-2">
          <CustomFieldsBuilder fields={s.customFields} onChange={(customFields) => setS({ ...s, customFields })} />
        </Card>
        <Card title="What to record" className="span-2">
          <p className="muted small">Your conditions decide what shows to start with; show or hide any of it. Saved straight away.</p>
          <FieldsEditor module="CYCLE" />
        </Card>
      </div>
    </div>
  );
}
