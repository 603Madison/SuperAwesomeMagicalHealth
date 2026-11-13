import { useState, type ReactNode } from "react";
import { api } from "../lib/api";
import { hm, isoDate, num, shortDate, useUnits, weightValue } from "../lib/format";
import { useSession } from "../lib/session";
import { DEFAULT_TARGETS, glucoseValue, unitLabel } from "../lib/vitals";
import type { TrendDay } from "../lib/types";
import { BarChart, LineChart, scoreFloor } from "../components/charts";
import { useModules } from "../App";
import { Card, ErrorNotice, PageHeader, Segmented, Spinner, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { useWater } from "../lib/fields";

interface Series {
  /** Saved in the person's arrangement: keep it. */
  id: string;
  title: string;
  value: (d: TrendDay) => number | null | undefined;
  format: (v: number) => string;
  kind: "line" | "bar";
  color: string;
  domain?: [number, number];
  score?: boolean;
  tick?: (v: number) => string;
  goal?: number;
  band?: [number, number];
  note?: string;
  /** A second line (blood pressure's bottom number). */
  extra?: { value: (d: TrendDay) => number | null | undefined; color: string; name: string };
  name?: string;
}

export function Trends() {
  const [days, setDays] = useState(30);
  const on = useModules();
  const units = useUnits();
  const goals = useSession((s) => s.profile?.goals);
  const { data, error, loading, reload } = useLoad(() => api.trends(days), [days]);
  const unit = units === "IMPERIAL" ? "lb" : "kg";
  const vt = useSession((s) => s.profile?.vitalsTargets) ?? DEFAULT_TARGETS;
  const water = useWater(on);
  const all: Series[] = [
    { id: "readiness", title: "Readiness", value: (d) => d.readiness, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c2)", score: true, band: [85, 100] },
    { id: "sleep-score", title: "Sleep score", value: (d) => d.sleepScore, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c1)", score: true, band: [85, 100] },
    { id: "activity-score", title: "Activity score", value: (d) => d.activityScore, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c3)", score: true, band: [85, 100] },
    { id: "resting-hr", title: "Resting heart rate", value: (d) => d.restingHeartRate, format: (v) => `${Math.round(v)} bpm`, kind: "line", color: "var(--c2)", note: "Lower usually means better recovered" },
    { id: "hrv", title: "Heart rate variability", value: (d) => d.hrv, format: (v) => `${Math.round(v)} ms`, kind: "line", color: "var(--c2)", note: "Higher usually means better recovered" },
    { id: "asleep", title: "Time asleep", value: (d) => (d.sleepMinutes != null ? d.sleepMinutes / 60 : null), format: (v) => hm(v * 60), kind: "bar", color: "var(--c1)", goal: goals ? goals.sleepMinutes / 60 : undefined, tick: (v) => `${v}h` },
    { id: "steps", title: "Steps", value: (d) => d.steps, format: (v) => num(v), kind: "bar", color: "var(--c3)", goal: goals?.steps },
    { id: "calories", title: "Calories eaten", value: (d) => d.calories, format: (v) => `${num(v)} kcal`, kind: "bar", color: "var(--c1)" },
    ...(water ? [{ id: "water", title: "Water", value: (d: TrendDay) => d.waterMl, format: (v: number) => `${num(v)} ml`, kind: "bar" as const, color: "var(--c3)", goal: goals?.waterMl }] : []),
    { id: "protein", title: "Protein", value: (d) => d.protein, format: (v) => `${num(v)} g`, kind: "bar", color: "var(--c1)" },
    { id: "weight", title: "Weight", value: (d) => (d.weightKg != null ? weightValue(d.weightKg, units) : null), format: (v) => `${v.toFixed(1)} ${unit}`, kind: "line", color: "var(--c1)" },
    { id: "temperature", title: "Temperature", value: (d) => d.temperatureDeviation, format: (v) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(1)} °C`, kind: "line", color: "var(--c3)", note: "Against your usual" },
    { id: "spo2", title: "Blood oxygen", value: (d) => d.spo2, format: (v) => `${v.toFixed(0)}%`, kind: "line", color: "var(--c2)" },
    ...(on.includes("MIND") ? [
      { id: "mood", title: "Mood", value: (d: TrendDay) => d.mood, format: (v: number) => `${v.toFixed(1)} of 5`, kind: "line" as const, color: "var(--c2)", domain: [1, 5] as [number, number] },
      { id: "anxiety", title: "Anxiety", value: (d: TrendDay) => d.anxiety, format: (v: number) => `${v.toFixed(1)} of 5`, kind: "line" as const, color: "var(--c3)", domain: [1, 5] as [number, number], note: "Lower is calmer" },
    ] : []),
    ...(on.includes("CONTINENCE") ? [
      { id: "trips", title: "Toilet trips", value: (d: TrendDay) => d.voids, format: (v: number) => `${v} trips`, kind: "bar" as const, color: "var(--c1)" },
      { id: "leaks", title: "Leaks", value: (d: TrendDay) => d.leaks, format: (v: number) => `${v} leaks`, kind: "bar" as const, color: "var(--c3)" },
    ] : []),
    ...(on.includes("MEDICATIONS") ? [
      { id: "doses", title: "Doses taken", value: (d: TrendDay) => (d.dosesDue ? Math.round(((d.dosesTaken ?? 0) / d.dosesDue) * 100) : null), format: (v: number) => `${Math.round(v)}%`, kind: "bar" as const, color: "var(--c2)", goal: 100 },
    ] : []),
    ...(on.includes("ACTIVITY") ? [
      { id: "stressed", title: "Stressed time", value: (d: TrendDay) => d.stressedMinutes, format: (v: number) => hm(v), kind: "bar" as const, color: "var(--c3)", note: "Daytime minutes your body showed stress" },
      { id: "restored", title: "Restored time", value: (d: TrendDay) => d.restoredMinutes, format: (v: number) => hm(v), kind: "bar" as const, color: "var(--c2)", note: "Calm, recovering time while awake" },
    ] : []),
    ...(on.includes("NUTRITION") ? [
      { id: "caffeine", title: "Caffeine", value: (d: TrendDay) => d.caffeineMg, format: (v: number) => `${num(v)} mg`, kind: "bar" as const, color: "var(--c3)", goal: 400, note: "400 mg a day is a common upper limit for adults" },
      { id: "alcohol", title: "Alcohol", value: (d: TrendDay) => d.standardDrinks, format: (v: number) => `${num(v, 1)} drinks`, kind: "bar" as const, color: "var(--c1)", note: "Standard drinks of 14 g" },
    ] : []),
    ...(on.includes("VITALS") ? [
      {
        id: "bp", title: "Blood pressure", name: "Top", value: (d: TrendDay) => d.systolic, format: (v: number) => `${Math.round(v)}`, kind: "line" as const, color: "var(--c1)",
        extra: { name: "Bottom", value: (d: TrendDay) => d.diastolic, color: "var(--c2)" },
        note: `Daily averages, mmHg: top and bottom numbers (your target: under ${vt.systolicHigh}/${vt.diastolicHigh})`,
      },
      { id: "glucose", title: "Glucose", value: (d: TrendDay) => (d.glucose != null ? glucoseValue(d.glucose, vt.glucoseUnit) : null), format: (v: number) => `${vt.glucoseUnit === "MG_DL" ? Math.round(v) : v.toFixed(1)} ${unitLabel(vt.glucoseUnit)}`, kind: "line" as const, color: "var(--c1)", band: [glucoseValue(vt.glucoseLow, vt.glucoseUnit), glucoseValue(vt.glucoseHigh, vt.glucoseUnit)] as [number, number] },
    ] : []),
    ...(on.includes("SYMPTOMS") ? [
      { id: "symptoms", title: "Symptoms", value: (d: TrendDay) => d.symptomWorst, format: (v: number) => `${num(v, v % 1 ? 1 : 0)} of 10`, kind: "bar" as const, color: "var(--c3)", domain: [0, 10] as [number, number], note: "The worst symptom each day" },
    ] : []),
    ...(on.includes("HABITS") ? [
      { id: "habits", title: "Habits done", value: (d: TrendDay) => (d.habitsDue ? Math.round(((d.habitsDone ?? 0) / d.habitsDue) * 100) : null), format: (v: number) => `${Math.round(v)}%`, kind: "bar" as const, color: "var(--c2)", goal: 100 },
    ] : []),
    ...(on.includes("CYCLE") ? [
      { id: "hot-flashes", title: "Hot flashes", value: (d: TrendDay) => d.hotFlashes, format: (v: number) => num(v, v % 1 ? 1 : 0), kind: "bar" as const, color: "var(--c3)" },
    ] : []),
  ];
  const series = all.filter((s) => (s.title !== "Steps" && s.title !== "Activity score") || on.includes("ACTIVITY"))
    .filter((s) => (s.title !== "Time asleep" && s.title !== "Sleep score") || on.includes("SLEEP"))
    .filter((s) => (s.title !== "Calories eaten" && s.title !== "Protein") || on.includes("NUTRITION"))
    .filter((s) => s.title !== "Weight" || on.includes("BODY"));

  const sections: Section[] = series.map((s) => {
    const values = data ? data.days.map(s.value).filter((v): v is number => v != null) : [];
    if (!data || values.length === 0) return { id: s.id, label: s.title, node: null };
    const avg = values.reduce((a, b) => a + b, 0) / values.length;
    const extraValues = s.extra ? data.days.map(s.extra.value).filter((v): v is number => v != null) : [];
    const average = extraValues.length
      ? `${s.format(avg)}/${s.format(extraValues.reduce((a, b) => a + b, 0) / extraValues.length)}`
      : s.format(avg);
    return {
      id: s.id, label: s.title, node: (
        <Card title={s.title} action={<span className="muted small">average <strong>{average}</strong></span>}>
          {s.kind === "line"
            ? <LineChart
                label={`${s.title} each day`} color={s.color} band={s.band} dots={data.days.length <= 31} area={!s.score && !s.extra} name={s.name}
                extra={s.extra ? [{ name: s.extra.name, color: s.extra.color, points: data.days.map((d) => ({ x: new Date(`${d.date}T12:00`).getTime(), y: s.extra!.value(d) ?? null })) }] : []}
                yMin={s.score ? scoreFloor(data.days.map(s.value)) : s.domain?.[0]} yMax={s.score ? 100 : s.domain?.[1]}
                points={data.days.map((d) => ({ x: new Date(`${d.date}T12:00`).getTime(), y: s.value(d) ?? null }))}
                formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={s.format}
              />
            : <BarChart
                label={`${s.title} each day`} color={s.color} goal={s.goal} labelEvery={Math.ceil(data.days.length / 8)} formatY={s.format} formatTick={s.tick}
                bars={data.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: s.value(d) ?? null, detail: shortDate(d.date) }))}
              />}
          {s.note && <Note>{s.note}</Note>}
        </Card>
      ),
    };
  });
  const arranged = useArrange(layoutKey("trends"), "Trends", sections);

  return (
    <div className="page">
      <PageHeader title="Trends" subtitle="Your numbers over weeks and months, against your own usual" actions={<>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 7, label: "Week" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }, { value: 365, label: "Year" }]} />
        {arranged.button}
      </>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && arranged.nodes.length === 0 && arranged.shown.length > 0 && <p className="muted">Nothing recorded in this time yet.</p>}
      <div style={{ opacity: loading ? 0.6 : 1 }}>{arranged.render()}</div>
    </div>
  );
}

function Note({ children }: { children: ReactNode }) {
  return <p className="muted small">{children}</p>;
}
