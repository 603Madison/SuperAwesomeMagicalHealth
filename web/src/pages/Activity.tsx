import { useState } from "react";
import { Activity as ActivityIcon, Dumbbell, MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, distance, hm, isoDate, num, shortDate, useToday, useUnits, workoutLabel } from "../lib/format";
import { useSession } from "../lib/session";
import type { Workout } from "../lib/types";
import { BarChart, LineChart, scoreFloor } from "../components/charts";
import { Button, Card, ErrorNotice, Menu, PageHeader, Segmented, Spinner, Stat, errorToast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { WorkoutDialog } from "../components/LogDialogs";
import { ACTIVITY_METRICS, SourcesLink, combinedFor } from "../components/Sources";

export function Activity() {
  const [days, setDays] = useState(30);
  const units = useUnits();
  const goals = useSession((s) => s.profile?.goals);
  const trends = useLoad(() => api.trends(days), [days]);
  const workouts = useLoad(() => api.workouts(days), [days]);
  // Today's counts, for where they came from when more than one device counted them.
  const now = useToday();
  const day = useLoad(() => api.day(now).catch(() => null), [now]);
  const [editing, setEditing] = useState<Workout | "new" | null>(null);
  const reload = () => { trends.reload(); workouts.reload(); day.reload(); };
  const rows = trends.data?.days ?? [];
  const total = (f: (r: (typeof rows)[number]) => number | undefined) => rows.reduce((s, r) => s + (f(r) ?? 0), 0);
  const withSteps = rows.filter((r) => r.steps != null);

  const sections: Section[] = [
    { id: "totals", label: "Totals", node: trends.data && (
      <Card title="Totals" icon={<ActivityIcon size={18} />} className="span-2">
        <div className="stats">
          <Stat label="Steps a day" value={num(withSteps.length ? total((r) => r.steps) / withSteps.length : undefined)} sub={goals ? `goal ${num(goals.steps)}` : undefined} />
          <Stat label="Active calories" value={num(total((r) => r.activeCalories))} unit="kcal" />
          <Stat label="Workouts" value={num(total((r) => r.workouts))} sub={hm(total((r) => r.workoutMinutes))} />
          <Stat label="Goal days" value={rows.filter((r) => goals && (r.steps ?? 0) >= goals.steps).length} sub={`of ${rows.length}`} />
        </div>
        <SourcesLink values={combinedFor(day.data?.combined, ACTIVITY_METRICS)} onChanged={reload} prefix="Today's steps and calories:" />
      </Card>
    ) },
    { id: "steps", label: "Steps", node: trends.data && (
      <Card title="Steps">
        <BarChart
          label="Steps each day" color="var(--c3)" goal={goals?.steps} labelEvery={Math.ceil(rows.length / 8)} formatY={(v) => `${num(v)} steps`}
          bars={rows.map((r) => ({ key: r.date, label: shortDate(r.date), value: r.steps ?? null, detail: shortDate(r.date) }))}
        />
      </Card>
    ) },
    { id: "score", label: "Activity score", node: trends.data && (
      <Card title="Activity score">
        <LineChart
          label="Activity score each day" color="var(--c3)" yMin={scoreFloor(rows.map((r) => r.activityScore))} yMax={100} band={[85, 100]} area={false} dots={rows.length <= 31}
          points={rows.map((r) => ({ x: new Date(`${r.date}T12:00`).getTime(), y: r.activityScore ?? null }))}
          formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => String(Math.round(y))}
        />
      </Card>
    ) },
    { id: "workouts", label: "Workouts", node: workouts.data && (
      <Card title="Workouts" icon={<Dumbbell size={18} />} className="span-2">
        {workouts.data.length === 0 ? <p className="muted">None in this time. They come from your watch, or log one here.</p> : (
          <ul className="rows">
            {workouts.data.map((w) => (
              <li key={w.id} className="row">
                <div className="row-icon"><Dumbbell size={18} /></div>
                <div className="row-main">
                  <div className="row-title">{w.name ?? workoutLabel(w.type)}</div>
                  <div className="row-sub">
                    {new Date(w.start).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })} {clock(w.start)} · {hm((w.end - w.start) / 60_000)}
                    {w.distanceM ? ` · ${distance(w.distanceM, units)}` : ""}{w.avgHeartRate ? ` · ${num(w.avgHeartRate)} bpm avg` : ""}{w.effort ? ` · effort ${w.effort}/10` : ""}{w.source ? ` · ${w.source}` : ""}
                  </div>
                </div>
                <div className="row-end">{w.activeCalories ? `${num(w.activeCalories)} kcal` : ""}</div>
                {w.manual && (
                  <div className="row-actions">
                    <Menu label={`More for ${w.name ?? workoutLabel(w.type)}`} trigger={<MoreHorizontal size={18} />} items={[
                      { label: "Edit", icon: <Pencil size={18} />, onSelect: () => setEditing(w) },
                      { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteWorkout(w.id!); reload(); } catch (e) { errorToast(e); } } },
                    ]} />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("activity"), "Activity", sections);

  return (
    <div className="page">
      <PageHeader title="Activity" subtitle="Steps, calories and workouts" actions={<>
        <Segmented label="Range" value={days} onChange={setDays} options={[{ value: 7, label: "Week" }, { value: 30, label: "Month" }, { value: 90, label: "3 months" }]} />
        <Button onClick={() => setEditing("new")}><Plus size={18} /> Log a workout</Button>
        {arranged.button}
      </>} />
      {trends.error && <ErrorNotice message={trends.error} onRetry={trends.reload} />}
      {workouts.error && <ErrorNotice message={workouts.error} onRetry={workouts.reload} />}
      {!trends.data && trends.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {arranged.render()}
      {editing && <WorkoutDialog workout={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}
