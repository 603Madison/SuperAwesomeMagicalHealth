import { useState, type ReactNode } from "react";
import { api } from "../lib/api";
import { hm, isoDate, num, shortDate, useUnits, weightValue } from "../lib/format";
import { useSession } from "../lib/session";
import type { TrendDay } from "../lib/types";
import { BarChart, LineChart, scoreFloor } from "../components/charts";
import { Card, ErrorNotice, PageHeader, Segmented, Spinner, useLoad } from "../components/ui";

interface Series {
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
}

export function Trends() {
  const [days, setDays] = useState(30);
  const units = useUnits();
  const goals = useSession((s) => s.profile?.goals);
  const { data, error, loading, reload } = useLoad(() => api.trends(days), [days]);
  const unit = units === "IMPERIAL" ? "lb" : "kg";
  const series: Series[] = [
    { title: "Readiness", value: (d) => d.readiness, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c2)", score: true, band: [85, 100] },
    { title: "Sleep score", value: (d) => d.sleepScore, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c1)", score: true, band: [85, 100] },
    { title: "Activity score", value: (d) => d.activityScore, format: (v) => String(Math.round(v)), kind: "line", color: "var(--c3)", score: true, band: [85, 100] },
    { title: "Resting heart rate", value: (d) => d.restingHeartRate, format: (v) => `${Math.round(v)} bpm`, kind: "line", color: "var(--c2)", note: "Lower usually means better recovered" },
    { title: "Heart rate variability", value: (d) => d.hrv, format: (v) => `${Math.round(v)} ms`, kind: "line", color: "var(--c2)", note: "Higher usually means better recovered" },
    { title: "Time asleep", value: (d) => (d.sleepMinutes != null ? d.sleepMinutes / 60 : null), format: (v) => hm(v * 60), kind: "bar", color: "var(--c1)", goal: goals ? goals.sleepMinutes / 60 : undefined, tick: (v) => `${v}h` },
    { title: "Steps", value: (d) => d.steps, format: (v) => num(v), kind: "bar", color: "var(--c3)", goal: goals?.steps },
    { title: "Calories eaten", value: (d) => d.calories, format: (v) => `${num(v)} kcal`, kind: "bar", color: "var(--c1)" },
    { title: "Protein", value: (d) => d.protein, format: (v) => `${num(v)} g`, kind: "bar", color: "var(--c1)" },
    { title: "Weight", value: (d) => (d.weightKg != null ? weightValue(d.weightKg, units) : null), format: (v) => `${v.toFixed(1)} ${unit}`, kind: "line", color: "var(--c1)" },
    { title: "Temperature", value: (d) => d.temperatureDeviation, format: (v) => `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(1)} °C`, kind: "line", color: "var(--c3)", note: "Against your usual" },
    { title: "Blood oxygen", value: (d) => d.spo2, format: (v) => `${v.toFixed(0)}%`, kind: "line", color: "var(--c2)" },
  ];

  return (
    <div className="page">
      <PageHeader title="Trends" subtitle="Your numbers over weeks and months, against your own usual" actions={
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 7, label: "Week" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }, { value: 365, label: "Year" }]} />
      } />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div className="grid grid-2" style={{ opacity: loading ? 0.6 : 1 }}>
          {series.map((s) => {
            const values = data.days.map(s.value).filter((v): v is number => v != null);
            if (values.length === 0) return null;
            const avg = values.reduce((a, b) => a + b, 0) / values.length;
            return (
              <Card key={s.title} title={s.title} action={<span className="muted small">average <strong>{s.format(avg)}</strong></span>}>
                {s.kind === "line"
                  ? <LineChart
                      label={`${s.title} each day`} color={s.color} band={s.band} dots={data.days.length <= 31} area={!s.score}
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
            );
          })}
        </div>
      )}
    </div>
  );
}

function Note({ children }: { children: ReactNode }) {
  return <p className="muted small">{children}</p>;
}
