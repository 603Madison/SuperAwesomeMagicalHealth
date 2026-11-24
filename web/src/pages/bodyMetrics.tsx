import { hm, isoDate, num, shortDate, useUnits, weightValue } from "../lib/format";
import { type RecentMetric, signed, temperature, temperatureChange, temperatureChangeText, temperatureText, temperatureUnit } from "../lib/recent";
import { useSession } from "../lib/session";
import { useWater } from "../lib/fields";
import { DEFAULT_TARGETS, glucoseValue, unitLabel } from "../lib/vitals";
import type { Metric, TrendDay, Units } from "../lib/types";
import { BarChart, LineChart, scoreFloor } from "../components/charts";
import { useModules } from "../App";

/*
 * What Body's cards show, shared by Body and a card's details (BodyDetail.tsx): how each kind of
 * reading from the last 24 hours is drawn and written, and each day's numbers at a week, a month
 * or a year (the scores, sleep, heart, steps, weight and the rest, for the features that are on).
 */

export const oneDecimal = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 1 });

/** How each kind of reading is drawn and written. */
export interface Kind {
  name: string;
  value: (v: number) => string;
  unit?: string;
  text: (v: number) => string;
  tick?: (v: number) => string;
  bars?: boolean;
  color: string;
  yMin?: number;
  yMax?: number;
  zero?: boolean;
  drawn?: { convert: (v: number) => number; text: (v: number) => string };
}

export function kinds(units: Units): Record<RecentMetric, Kind> {
  const deg = temperatureUnit(units);
  return {
    HEART_RATE: { name: "Heart rate", value: (v) => num(v), unit: "bpm", text: (v) => `${num(v)} bpm`, color: "var(--c1)" },
    SPO2: { name: "Blood oxygen", value: oneDecimal, unit: "%", text: (v) => `${oneDecimal(v)}%`, color: "var(--c2)", yMax: 100 },
    HRV: { name: "Heart rate variability", value: (v) => num(v), unit: "ms", text: (v) => `${num(v)} ms`, color: "var(--c1)" },
    STRESS: { name: "Stress", value: (v) => num(v), text: (v) => `${num(v)} of 100`, color: "var(--c3)", yMin: 0, yMax: 100 },
    RESPIRATORY_RATE: { name: "Breathing rate", value: oneDecimal, unit: "per min", text: (v) => `${oneDecimal(v)} breaths a minute`, color: "var(--c2)" },
    SKIN_TEMPERATURE: {
      name: "Skin temperature", value: (v) => temperature(v, units).toFixed(1), unit: deg, text: (v) => temperatureText(v, units), tick: oneDecimal, color: "var(--c3)",
      drawn: { convert: (v) => temperature(v, units), text: (v) => `${v.toFixed(1)} ${deg}` },
    },
    TEMPERATURE_DEVIATION: {
      name: "Temperature against your usual", value: (v) => temperatureChangeText(v, units), text: (v) => temperatureChangeText(v, units),
      tick: (v) => v.toLocaleString(undefined, { maximumFractionDigits: 2 }),
      color: "var(--c3)", zero: true, drawn: { convert: (v) => temperatureChange(v, units), text: (v) => signed(v, deg) },
    },
    STEPS: { name: "Steps", value: (v) => num(v), unit: "steps", text: (v) => `${num(v)} steps`, bars: true, color: "var(--c2)" },
  };
}

/** One of each day's numbers, as a card at a week, a month or a year draws it. */
export interface Series {
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
  /** Each day from the readings (GET /api/series) rather than the day's numbers: their average, or their total. */
  metric?: Metric;
  how?: "average" | "sum";
  /** Days that are a difference from the usual already (temperature): no usual of their own. */
  noUsual?: boolean;
}

/** Each day's numbers for the features that are on, in Body's order. */
export function useTrendSeries(): Series[] {
  const on = useModules();
  const units = useUnits();
  const goals = useSession((s) => s.profile?.goals);
  const vt = useSession((s) => s.profile?.vitalsTargets) ?? DEFAULT_TARGETS;
  const water = useWater(on);
  const unit = units === "IMPERIAL" ? "lb" : "kg";
  const deg = temperatureUnit(units);
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
    {
      id: "temperature", title: "Temperature", value: (d) => (d.temperatureDeviation != null ? temperatureChange(d.temperatureDeviation, units) : null),
      format: (v) => signed(v, deg), kind: "line", color: "var(--c3)", note: "Against your usual", noUsual: true,
    },
    { id: "spo2", title: "Blood oxygen", value: (d) => d.spo2, format: (v) => `${v.toFixed(0)}%`, kind: "line", color: "var(--c2)" },
    ...(on.includes("MIND") ? [
      { id: "mood", title: "Mood", value: (d: TrendDay) => d.mood, format: (v: number) => `${v.toFixed(1)} of 5`, kind: "line" as const, color: "var(--c2)", domain: [1, 5] as [number, number] },
      { id: "anxiety", title: "Anxiety", value: (d: TrendDay) => d.anxiety, format: (v: number) => `${v.toFixed(1)} of 5`, kind: "line" as const, color: "var(--c3)", domain: [1, 5] as [number, number], note: "Lower is calmer" },
    ] : []),
    ...(on.includes("CONTINENCE") ? [
      { id: "trips", title: "Toilet trips", value: (d: TrendDay) => d.voids, format: (v: number) => `${num(v, v % 1 ? 1 : 0)} trips`, kind: "bar" as const, color: "var(--c1)" },
      { id: "leaks", title: "Leaks", value: (d: TrendDay) => d.leaks, format: (v: number) => `${num(v, v % 1 ? 1 : 0)} leaks`, kind: "bar" as const, color: "var(--c3)" },
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
  return all.filter((s) => (s.title !== "Steps" && s.title !== "Activity score") || on.includes("ACTIVITY"))
    .filter((s) => (s.title !== "Time asleep" && s.title !== "Sleep score") || on.includes("SLEEP"))
    .filter((s) => (s.title !== "Calories eaten" && s.title !== "Protein") || on.includes("NUTRITION"))
    .filter((s) => s.title !== "Weight" || on.includes("BODY"));
}

/** The readings-only days for the details of Body's cards at 24 hours that have no number of the day: heart rate, breathing and stress. */
export function readingSeries(units: Units): Series[] {
  const k = kinds(units);
  return [
    { id: "heart", title: "Heart rate", metric: "HEART_RATE", how: "average", value: () => null, format: k.HEART_RATE.text, kind: "line", color: "var(--c1)", note: "Each day's average" },
    { id: "breathing", title: "Breathing rate", metric: "RESPIRATORY_RATE", how: "average", value: () => null, format: (v) => `${oneDecimal(v)} a min`, kind: "line", color: "var(--c2)", note: "Each day's average, breaths a minute" },
    { id: "stress", title: "Stress", metric: "STRESS", how: "average", value: () => null, format: k.STRESS.text, kind: "line", color: "var(--c3)", domain: [0, 100], note: "Each day's average, of 100" },
  ];
}

/** The day's numbers each day of a span: a line or columns, with the person's [usual] where known. */
export function DailyChart({ series: s, dates, value, extra, height, usual }: {
  series: Series; dates: string[]; value: (date: string) => number | null; extra?: (date: string) => number | null; height?: number; usual?: number | null;
}) {
  const at = (date: string) => new Date(`${date}T12:00`).getTime();
  const values = dates.map(value);
  return s.kind === "line"
    ? <LineChart
        label={`${s.title} each day`} color={s.color} band={s.band} dots={dates.length <= 31} area={!s.score && !s.extra} name={s.name} height={height}
        extra={s.extra && extra ? [{ name: s.extra.name, color: s.extra.color, points: dates.map((d) => ({ x: at(d), y: extra(d) })) }] : []}
        yMin={s.score ? scoreFloor(values) : s.domain?.[0]} yMax={s.score ? 100 : s.domain?.[1]}
        points={dates.map((d, i) => ({ x: at(d), y: values[i] }))} usual={usual ?? undefined}
        formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={s.format}
      />
    : <BarChart
        label={`${s.title} each day`} color={s.color} goal={s.goal} labelEvery={Math.ceil(dates.length / 8)} formatY={s.format} formatTick={s.tick} height={height}
        bars={dates.map((d, i) => ({ key: d, label: shortDate(d), value: values[i], detail: shortDate(d) }))} usual={usual ?? undefined}
      />;
}
