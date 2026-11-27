import { useState, type ReactNode } from "react";
import { Dumbbell, Moon, MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { clock, distance, hm, num, shortDate, useToday, useUnits, workoutLabel } from "../lib/format";
import { BODY_SPANS, NAMES, type BodySpan } from "../lib/nav";
import { FIRST, detailOf, detailPath, firstFew } from "../lib/bodyDetail";
import type { NutritionTarget, SleepSummary, TrendDay, Workout } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { BarChart, Hypnogram, StageLegend } from "../components/charts";
import { useModules } from "../App";
import { Button, Card, CardAction, ErrorNotice, Menu, Spinner, errorToast, useLoad } from "../components/ui";
import { useArrange, useHashScroll, type Section } from "../components/Arrange";
import { SleepDialog, WorkoutDialog } from "../components/LogDialogs";
import { useLogged } from "../components/LogSheet";
import { ACTIVITY_METRICS, NIGHT_METRICS, SourcesLink, combinedFor } from "../components/Sources";
import { layoutKey } from "../lib/types";
import { BodyFrame } from "./Body";
import { useMicros } from "../lib/fields";
import { DailyChart, useTrendSeries } from "./bodyMetrics";
import { GROUPS, NUTRIENTS, amountText, infoOf, targetOf, trendValue, withUnit, type NutrientKey } from "../lib/micros";

/*
 * Body at a week, a month or a year: each day's numbers, as Trends had them (the scores, sleep,
 * heart, steps, weight and the rest for the features that are on), with the nights and the
 * workouts in that time. The sections keep Trends' saved arrangement (layoutKey("trends")) and ids;
 * the nights and workouts are new ones.
 */
export function BodyTrends({ span, setSpan }: { span: Exclude<BodySpan, "day">; setSpan: (s: BodySpan) => void }) {
  const days = BODY_SPANS.find((s) => s.id === span)!.days;
  const on = useModules();
  const logged = useLogged();
  const { data, error, loading, reload } = useLoad(() => api.trends(days), [days, logged]);
  const micros = useMicros(on);
  const series = useTrendSeries();

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
        <Card title={s.title} className="body-card" action={<span className="muted small">average <strong>{average}</strong></span>}
          open={s.id === "weight" ? { to: "/body/weight", label: `${NAMES.weight}: details` } : { to: detailPath(detailOf(s.id), span), label: `${s.title}: details` }}>
          <DailyChart series={s} dates={data.days.map((d) => d.date)}
            value={(date) => { const d = data.days.find((x) => x.date === date); return d ? s.value(d) ?? null : null; }}
            extra={s.extra ? (date) => { const d = data.days.find((x) => x.date === date); return d ? s.extra!.value(d) ?? null : null; } : undefined} />
          {s.note && <Note>{s.note}</Note>}
        </Card>
      ),
    };
  });
  // A nutrient's daily intake against its target, after protein (its id is saved: keep it).
  if (micros) {
    const nutrient: Section = { id: "nutrient", label: "Vitamins & minerals", node: data && <NutrientChart days={data.days} target={data.nutritionTarget} open={{ to: detailPath("nutrient", span), label: "Vitamins & minerals: details" }} /> };
    const after = sections.findIndex((x) => x.id === "protein");
    sections.splice(after >= 0 ? after + 1 : sections.length, 0, nutrient);
  }
  // Each night and each workout in the time, after their charts (new ids: nothing saved had them).
  const place = (section: Section, after: string) => {
    const at = sections.findIndex((x) => x.id === after);
    sections.splice(at >= 0 ? at + 1 : sections.length, 0, section);
  };
  if (on.includes("SLEEP")) place({ id: "nights", label: "Nights", node: <NightsCard days={days} open={{ to: detailPath(detailOf("nights"), span), label: "Nights: details" }} /> }, "asleep");
  if (on.includes("ACTIVITY")) place({ id: "workouts", label: "Workouts", node: <WorkoutsCard days={days} open={{ to: detailPath("workouts", span), label: "Workouts: details" }} /> }, "steps");
  const arranged = useArrange(layoutKey("trends"), "Body", sections);
  useHashScroll(!!data);

  return (
    <BodyFrame span={span} setSpan={setSpan} actions={arranged.button}>
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && arranged.nodes.length === 0 && arranged.shown.length > 0 && <p className="muted">Nothing recorded in this time yet.</p>}
      <div style={{ opacity: loading ? 0.6 : 1 }}>{arranged.render("grid grid-2 body-grid")}</div>
    </BodyFrame>
  );
}

/** Each night in the time (up to a month), with its stages; one entered by hand can go, and a night can be added. */
function NightsCard({ days, open }: { days: number; open: { to: string; label: string } }) {
  const readOnly = useReadOnly();
  const logged = useLogged();
  const nights = useLoad(() => api.sleep(Math.min(days, 30)), [days, logged]);
  // Last night's readings, for where they came from when more than one device measured them.
  const now = useToday();
  const day = useLoad(() => api.day(now).catch(() => null), [now, logged]);
  const [adding, setAdding] = useState(false);
  const [all, setAll] = useState(false);
  const reload = () => { nights.reload(); day.reload(); };
  const list = nights.data ?? [];
  return (
    <Card id="nights" title="Nights" icon={<Moon size={18} />} className="span-2 body-card" open={open}
      action={!readOnly && <CardAction label="Add a night" onClick={() => setAdding(true)}><Plus size={18} /></CardAction>}>
      {nights.error && <p className="muted">{nights.error}</p>}
      {nights.data && <SourcesLink values={combinedFor(day.data?.combined, NIGHT_METRICS)} onChanged={reload} prefix="Last night's readings:" />}
      {nights.data && list.length === 0 && <p className="muted">No nights yet. Wear your ring or watch to bed, or add a night by hand.</p>}
      {firstFew(list, all).map((n) => <NightRow key={n.sessionId ?? n.start} night={n} onChanged={reload} />)}
      {list.length > FIRST && <ShowAll all={all} count={list.length} onToggle={() => setAll(!all)} />}
      {adding && <SleepDialog onClose={() => setAdding(false)} onSaved={reload} />}
    </Card>
  );
}

/** "Show all 12" or "Show fewer", under a long list. */
export function ShowAll({ all, count, onToggle }: { all: boolean; count: number; onToggle: () => void }) {
  return <button type="button" className="link-btn show-all" aria-expanded={all} onClick={onToggle}>{all ? "Show fewer" : `Show all ${count}`}</button>;
}

/** One night: its date, times, time asleep and the rest, its stages, and (entered by hand) Delete. */
export function NightRow({ night: n, onChanged }: { night: SleepSummary; onChanged: () => void }) {
  const readOnly = useReadOnly();
  return (
    <div className="night">
      <div className="night-head">
        <div>
          <strong>{new Date(n.end).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" })}</strong>
          <div className="muted small">{clock(n.start)} – {clock(n.end)} · {hm(n.asleepMinutes)} asleep · {n.efficiency}% efficiency{n.lowestHeartRate ? ` · lowest ${num(n.lowestHeartRate)} bpm` : ""}{n.source ? ` · ${n.source}` : ""}</div>
        </div>
        {!readOnly && n.source === "Entered by hand" && n.sessionId && (
          <Button variant="ghost" onClick={async () => { try { await api.deleteSleep(n.sessionId!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Delete</Button>
        )}
      </div>
      {n.stages.length > 0 && <Hypnogram stages={n.stages} start={n.start} end={n.end} height={120} />}
      {n.staged && <StageLegend deep={n.deepMinutes} rem={n.remMinutes} light={n.lightMinutes} awake={n.awakeMinutes} />}
      {n.combined && <SourcesLink sleep={n.combined} onChanged={onChanged} />}
    </div>
  );
}

/** Each workout in the time; one entered by hand can be changed or deleted, and one can be logged. */
function WorkoutsCard({ days, open }: { days: number; open: { to: string; label: string } }) {
  const readOnly = useReadOnly();
  const logged = useLogged();
  const workouts = useLoad(() => api.workouts(days), [days, logged]);
  const now = useToday();
  const day = useLoad(() => api.day(now).catch(() => null), [now, logged]);
  const [editing, setEditing] = useState<Workout | "new" | null>(null);
  const [all, setAll] = useState(false);
  const reload = () => { workouts.reload(); day.reload(); };
  const list = workouts.data ?? [];
  return (
    <Card id="workouts" title="Workouts" icon={<Dumbbell size={18} />} className="span-2 body-card" open={open}
      action={!readOnly && <CardAction label="Log a workout" onClick={() => setEditing("new")}><Plus size={18} /></CardAction>}>
      {workouts.error && <p className="muted">{workouts.error}</p>}
      {workouts.data && <SourcesLink values={combinedFor(day.data?.combined, ACTIVITY_METRICS)} onChanged={reload} prefix="Today's steps and calories:" />}
      {workouts.data && list.length === 0 && <p className="muted">None in this time. They come from your watch, or log one with +.</p>}
      {list.length > 0 && (
        <ul className="rows">
          {firstFew(list, all).map((w) => <WorkoutRow key={w.id} workout={w} onEdit={() => setEditing(w)} onChanged={reload} />)}
        </ul>
      )}
      {list.length > FIRST && <ShowAll all={all} count={list.length} onToggle={() => setAll(!all)} />}
      {editing && <WorkoutDialog workout={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </Card>
  );
}

/** One workout: its name, when, how long and the rest ([full]: every detail there is), and (entered by hand) Change and Delete. */
export function WorkoutRow({ workout: w, onEdit, onChanged, full }: { workout: Workout; onEdit: () => void; onChanged: () => void; full?: boolean }) {
  const readOnly = useReadOnly();
  const units = useUnits();
  const name = w.name ?? workoutLabel(w.type);
  const details = [
    w.distanceM ? distance(w.distanceM, units) : null,
    w.avgHeartRate ? `${num(w.avgHeartRate)} bpm avg` : null,
    full && w.maxHeartRate ? `${num(w.maxHeartRate)} bpm highest` : null,
    full && w.steps ? `${num(w.steps)} steps` : null,
    w.effort ? `effort ${w.effort}/10` : null,
    full && w.activeCalories ? `${num(w.activeCalories)} kcal` : null,
    w.source ?? null,
  ].filter((x): x is string => !!x);
  return (
    <li className={`row ${full ? "workout-row" : ""}`}>
      <div className="row-icon"><Dumbbell size={18} /></div>
      <div className="row-main">
        <div className="row-title">{name}</div>
        <div className={`row-sub ${full ? "wraps" : ""}`}>
          {new Date(w.start).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })} {clock(w.start)}{full ? ` – ${clock(w.end)}` : ""} · {hm((w.end - w.start) / 60_000)}
          {details.length > 0 ? ` · ${details.join(" · ")}` : ""}
        </div>
        {full && w.note && <div className="row-sub wraps">{w.note}</div>}
      </div>
      {!full && <div className="row-end">{w.activeCalories ? `${num(w.activeCalories)} kcal` : ""}</div>}
      {!readOnly && w.manual && (
        <div className="row-actions">
          <Menu label={`More for ${name}`} trigger={<MoreHorizontal size={18} />} items={[
            { label: "Change", icon: <Pencil size={18} />, onSelect: onEdit },
            { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteWorkout(w.id!); onChanged(); } catch (e) { errorToast(e); } } },
          ]} />
        </div>
      )}
    </li>
  );
}

/** Which nutrient the chart shows, remembered in this browser. */
const NUTRIENT_KEY = "samh.trends.nutrient";

/** Remembers the nutrient chosen, for the chart and its details. */
export function keepNutrient(k: NutrientKey) {
  try { localStorage.setItem(NUTRIENT_KEY, k); } catch { /* fine without */ }
}

export function chosenNutrient(): NutrientKey {
  try {
    const saved = localStorage.getItem(NUTRIENT_KEY);
    if (saved && NUTRIENTS.some((x) => x.key === saved)) return saved as NutrientKey;
  } catch { /* no storage: the default */ }
  return "FIBER";
}

/**
 * A chosen nutrient's daily intake against its target (or limit). A day nothing carried it is a
 * gap, never a zero; only foods that came with it count.
 */
export function NutrientChart({ days, target, open }: { days: TrendDay[]; target?: NutritionTarget; open: { to: string; label: string } }) {
  const [key, setKey] = useState<NutrientKey>(chosenNutrient);
  const info = infoOf(key);
  const t = targetOf(target, key);
  const values = days.map((d) => trendValue(d, key));
  const known = values.filter((v): v is number => v != null);
  const choose = (k: NutrientKey) => {
    setKey(k);
    keepNutrient(k);
  };
  return (
    <Card id="nutrient" title="Vitamins & minerals" className="body-card" open={open} action={known.length > 0 && <span className="muted small">average <strong>{withUnit(known.reduce((a, b) => a + b, 0) / known.length, info.unit)}</strong></span>}>
      <label className="nutrient-pick">
        <span className="sr-only">Nutrient</span>
        <select value={key} onChange={(e) => choose(e.target.value as NutrientKey)}>
          {GROUPS.map((g) => (
            <optgroup key={g.id} label={g.label}>
              {NUTRIENTS.filter((x) => x.group === g.id).map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}
            </optgroup>
          ))}
        </select>
      </label>
      {known.length === 0
        ? <p className="muted small">No food in this time came with {info.label.toLowerCase()} numbers yet.</p>
        : <BarChart
            label={`${info.label} each day`} color={info.aim === "AT_MOST" ? "var(--c1)" : "var(--c2)"} goal={t?.amount} labelEvery={Math.ceil(days.length / 8)}
            formatY={(v) => withUnit(v, info.unit)} formatTick={(v) => amountText(v)}
            bars={days.map((d, i) => ({ key: d.date, label: shortDate(d.date), value: values[i] ?? null, detail: shortDate(d.date) }))}
          />}
      <Note>
        {t ? `${info.aim === "AT_MOST" ? "The line is your limit" : "The line is your target"}: ${withUnit(t.amount, info.unit)} a day. ` : "No target for this one. "}
        {info.label} came with food on {known.length} of {days.length} days; only the foods that carried it count.
      </Note>
    </Card>
  );
}

export function Note({ children }: { children: ReactNode }) {
  return <p className="muted small">{children}</p>;
}
