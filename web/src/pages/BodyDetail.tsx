import { createContext, useContext, useState, type ReactNode } from "react";
import { Navigate, useParams, useSearchParams } from "react-router-dom";
import { Plus } from "lucide-react";
import { ApiError, api } from "../lib/api";
import { dayLabel, dayOf, dayStart, hm, num, shortDate, today, useUnits, workoutLabel } from "../lib/format";
import { BODY_SPANS, NAMES, bodyPath, type BodySpan } from "../lib/nav";
import {
  FIRST, againstUsual, byDay, byHour, dailyValues, detailSpan, detailSpans, firstFew, newestFirst, seriesPoints, spanDates, splitSpan,
  statsOf, usualOf, type DayValue,
} from "../lib/bodyDetail";
import {
  RECENT_HOURS, drawnAsLine, mainNight, nightDevice, nightMinutes, readingsByMetric, readingsSummary, sleepSpans, stepsSummary, type RecentMetric,
} from "../lib/recent";
import { useSession } from "../lib/session";
import { clockTime, dateIn, hourIn, hourTicks } from "../lib/today";
import { layoutKey, type RecentPoint, type RecentReadings, type SleepSession, type TrendDay, type Workout } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { useFields, useMicros } from "../lib/fields";
import { GROUPS, NUTRIENTS, infoOf, targetOf, trendValue, withUnit, type NutrientKey } from "../lib/micros";
import { useModules } from "../App";
import { TimeChart, type TimeAxis } from "../components/charts";
import { useArrange, type Section } from "../components/Arrange";
import { SleepDialog, WorkoutDialog } from "../components/LogDialogs";
import { useLogged } from "../components/LogSheet";
import { Button, Empty, ErrorNotice, PageHeader, Spinner, Stat, useLoad } from "../components/ui";
import { NightBody } from "./BodyDay";
import { NightRow, Note, ShowAll, WorkoutRow, chosenNutrient, keepNutrient } from "./BodyTrends";
import { DailyChart, kinds, readingSeries, useTrendSeries, type Kind, type Series } from "./bodyMetrics";

/*
 * A card's details, from Body (/body/hrv, /body/hrv?span=week): its name, a switch for 24 hours, a
 * week, a month or a year (starting where Body was; 24 hours only where there are readings, and
 * only ever the person's own), then the latest, a large chart with the person's usual, the average,
 * lowest and highest with when, and the list: each reading hour by hour, each day, each night or
 * each workout, newest first. Every card on Body opens one (Weight opens its own page). The sections
 * can be arranged (layoutKey("bodyDetail"); their ids are saved: never rename one).
 *
 * 24 hours: GET /api/recent (one device, as Body had it, or all). Longer: GET /api/trends, or each
 * day's readings from GET /api/series for heart rate, breathing and stress. The usual: the 30 days
 * before.
 */

/** How many days before make the usual. */
const USUAL_DAYS = 30;
const USUAL_FROM = `the ${USUAL_DAYS} days before`;

/** What a detail page shows. */
interface Detail {
  id: string;
  title: string;
  /** Each day: from the day's numbers, or (with a metric) the readings. */
  daily: Series;
  /** At 24 hours: the readings it's about, the main one first. */
  readings?: RecentMetric[];
  /** At 24 hours, the day's number to take the usual from. */
  dayUsual?: (d: TrendDay) => number | null | undefined;
  /** Sleep, workouts and nutrients have lists of their own. */
  special?: "sleep" | "workouts" | "nutrient";
  /** The nutrient shown, for its target. */
  nutrient?: NutrientKey;
}

export function BodyDetail() {
  const { id = "" } = useParams();
  const units = useUnits();
  const on = useModules();
  const micros = useMicros(on);
  const trend = useTrendSeries();
  const goals = useSession((s) => s.profile?.goals);
  const [nutrient, setNutrient] = useState<NutrientKey>(chosenNutrient);
  // Which features are on is known once the profile (or the share) and the fields are in.
  const viewing = useViewing((s) => !!s.share);
  const profiled = useSession((s) => !!s.profile);
  const fields = useFields().layout != null;
  // Weight has its own page, with body fat and measurements.
  if (id === "weight") return <Navigate to="/body/weight" replace />;

  const find = (sid: string) => trend.find((s) => s.id === sid);
  const series = (sid: string, fallback: Series): Series => find(sid) ?? fallback;
  const reading = readingSeries(units);
  let detail: Detail | null = null;
  switch (id) {
    case "sleep":
      detail = {
        id, title: "Sleep", special: "sleep", dayUsual: (d) => d.sleepMinutes,
        daily: series("asleep", { id: "asleep", title: "Time asleep", value: (d) => (d.sleepMinutes != null ? d.sleepMinutes / 60 : null), format: (v) => hm(v * 60), kind: "bar", color: "var(--c1)", goal: goals ? goals.sleepMinutes / 60 : undefined, tick: (v) => `${v}h` }),
      };
      break;
    case "heart": case "breathing": case "stress": {
      const daily = reading.find((s) => s.id === id)!;
      detail = { id, title: daily.title, daily, readings: [daily.metric as RecentMetric] };
      break;
    }
    case "hrv": detail = { id, title: "Heart rate variability", daily: find("hrv")!, readings: ["HRV"], dayUsual: (d) => d.hrv }; break;
    case "spo2": detail = { id, title: "Blood oxygen", daily: find("spo2")!, readings: ["SPO2"], dayUsual: (d) => d.spo2 }; break;
    case "temperature": detail = { id, title: "Temperature", daily: find("temperature")!, readings: ["TEMPERATURE_DEVIATION", "SKIN_TEMPERATURE"] }; break;
    case "steps":
      detail = {
        id, title: "Steps", readings: ["STEPS"], dayUsual: (d) => d.steps,
        daily: series("steps", { id: "steps", title: "Steps", value: (d) => d.steps, format: (v) => num(v), kind: "bar", color: "var(--c3)", goal: goals?.steps }),
      };
      break;
    case "workouts":
      detail = {
        id, title: "Workouts", special: "workouts",
        daily: { id, title: "Workout time", value: (d) => (d.workouts > 0 ? d.workoutMinutes : null), format: (v) => hm(v), kind: "bar", color: "var(--c3)" },
      };
      break;
    case "nutrient": {
      if (!micros) break;
      const info = infoOf(nutrient);
      detail = {
        id, title: "Vitamins & minerals", special: "nutrient", nutrient,
        daily: { id, title: info.label, value: (d) => trendValue(d, nutrient), format: (v) => withUnit(v, info.unit), kind: "bar", color: info.aim === "AT_MOST" ? "var(--c1)" : "var(--c2)" },
      };
      break;
    }
    default: {
      const s = find(id);
      if (s) detail = { id, title: s.title, daily: s };
    }
  }
  if (!detail && (!(viewing || profiled) || !fields)) return <div className="page body-page"><div className="center-pad"><Spinner size={28} /></div></div>;
  if (!detail) return <Navigate to="/body" replace />;
  return <DetailPage key={id} detail={detail} nutrient={detail.special === "nutrient" ? { key: nutrient, choose: (k) => { setNutrient(k); keepNutrient(k); } } : undefined} />;
}

function DetailPage({ detail, nutrient }: { detail: Detail; nutrient?: { key: NutrientKey; choose: (k: NutrientKey) => void } }) {
  const [params, setParams] = useSearchParams();
  const viewing = useViewing((s) => !!s.share);
  const readOnly = useReadOnly();
  const span = detailSpan(params.get("span"), detail.id, viewing);
  const spans = detailSpans(detail.id, viewing);
  const device = span === "day" ? params.get("device") || undefined : undefined;
  const setSpan = (s: BodySpan) => setParams(s === "day" ? (device ? { device } : {}) : { span: s }, { replace: true });
  const [adding, setAdding] = useState<"night" | Workout | "workout" | null>(null);
  const logged = useLogged();
  const [changed, setChanged] = useState(0);
  const reload = () => setChanged((c) => c + 1);
  const view = span === "day"
    ? <DayView detail={detail} device={device} version={logged + changed} />
    : <DaysView detail={detail} days={BODY_SPANS.find((s) => s.id === span)!.days} version={logged + changed} reload={reload} onEdit={(w) => setAdding(w)} />;

  const top = (arrange: ReactNode) => (
    <>
      <PageHeader title={detail.title} back={{ to: bodyPath(span, device), label: NAMES.body }}
        subtitle={device ? <DeviceName id={device} /> : undefined}
        actions={<>
          {!readOnly && detail.special === "sleep" && <Button variant="ghost" className="detail-add" aria-label="Add a night" onClick={() => setAdding("night")}><Plus size={18} /><span>Add a night</span></Button>}
          {!readOnly && detail.special === "workouts" && <Button variant="ghost" className="detail-add" aria-label="Log a workout" onClick={() => setAdding("workout")}><Plus size={18} /><span>Log a workout</span></Button>}
          {arrange}
        </>} />
      <div className="span-switch" role="radiogroup" aria-label="How far back" style={{ gridTemplateColumns: `repeat(${spans.length}, minmax(0, 1fr))` }}>
        {spans.map((s) => (
          <button key={s} type="button" role="radio" aria-checked={s === span} className={s === span ? "is-on" : ""} onClick={() => setSpan(s)}>
            {BODY_SPANS.find((x) => x.id === s)!.label}
          </button>
        ))}
      </div>
      {nutrient && (
        <label className="nutrient-pick detail-pick">
          <span className="sr-only">Nutrient</span>
          <select value={nutrient.key} onChange={(e) => nutrient.choose(e.target.value as NutrientKey)}>
            {GROUPS.map((g) => (
              <optgroup key={g.id} label={g.label}>
                {NUTRIENTS.filter((x) => x.group === g.id).map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}
              </optgroup>
            ))}
          </select>
        </label>
      )}
    </>
  );

  return (
    <div className="page body-page body-detail">
      <Top.Provider value={top}>{view}</Top.Provider>
      {adding === "night" && <SleepDialog onClose={() => setAdding(null)} onSaved={reload} />}
      {adding != null && adding !== "night" && <WorkoutDialog workout={adding === "workout" ? undefined : adding} onClose={() => setAdding(null)} onSaved={reload} />}
    </div>
  );
}

/** The page's top (its header, with the Arrange button the sections give it, and the span switch). */
const Top = createContext<(arrange: ReactNode) => ReactNode>(() => null);

/** The device a 24-hour view is from, by name. */
function DeviceName({ id }: { id: string }) {
  const { data } = useLoad(() => api.devices().catch(() => []), [id]);
  const name = data?.find((d) => d.id === id)?.name;
  return <>{name ? `From ${name}` : null}</>;
}

// ---- The parts every view has ----

/**
 * The page under its top: the sections in the person's order (the latest, the chart, the numbers
 * and the list), or [empty] when there's nothing in the span, or a [notice] (loading, an error).
 */
function Sections({ headline, chart, stats, list, empty, loading, notice }: {
  headline?: ReactNode; chart?: ReactNode; stats?: ReactNode; list?: ReactNode; empty?: string | null; loading?: boolean; notice?: ReactNode;
}) {
  const top = useContext(Top);
  const sections: Section[] = [
    { id: "headline", label: "The latest", node: !empty && headline },
    { id: "chart", label: "Chart", node: !empty && chart },
    { id: "stats", label: "Average, lowest and highest", node: !empty && stats },
    { id: "list", label: "Each one", node: !empty && list },
  ];
  const arranged = useArrange(layoutKey("bodyDetail"), "Details", sections);
  return (
    <>
      {top(arranged.button)}
      {notice ?? (empty
        ? <section className="panel-card body-card"><Empty title={empty} /></section>
        : <div className={loading ? "is-refreshing" : ""} aria-busy={loading}>{arranged.render("body-stack")}</div>)}
    </>
  );
}

const Loading = () => <Sections notice={<div className="center-pad"><Spinner size={28} /></div>} />;

/** The latest: a big number, and when. */
function Headline({ label, value, when }: { label: string; value: string; when?: string | null }) {
  return (
    <section className="panel-card body-card detail-headline" aria-label={label}>
      <span className="detail-label">{label}</span>
      <p className="detail-big">{value}</p>
      {when && <span className="muted">{when}</span>}
    </section>
  );
}

/** The numbers over the span: average, lowest and highest with when, and against the usual. */
function Stats({ children }: { children: ReactNode }) {
  return <section className="panel-card body-card" aria-label="Average, lowest and highest"><div className="stats detail-stats">{children}</div></section>;
}

/** "Your usual" (from [from]), and how [value] ([what]: this span's average, the last 24 hours, last night) stands against it. */
function UsualStat({ usual, value, what = "Average", format, from }: { usual: number | null; value?: number; what?: string; format: (v: number) => string; from: string }) {
  if (usual == null) return null;
  const a = value != null ? againstUsual(value, usual) : null;
  const sub = a ? (a.word === "usual" ? `${what} about the same` : `${what} ${Math.round(a.share * 100)}% ${a.word}`) : null;
  return <Stat label="Your usual" value={format(usual)} sub={<>{sub && <span className="usual-against">{sub}</span>}<span>From {from}</span></>} />;
}

function ChartCard({ title, children, note }: { title?: string; children: ReactNode; note?: ReactNode }) {
  return (
    <section className="panel-card body-card detail-chart" aria-label={title ?? "Chart"}>
      {children}
      {note && <Note>{note}</Note>}
    </section>
  );
}

/** A list card: its rows, then "Show all" past the first few. */
function ListCard<T>({ title, items, row, rows = true }: { title: string; items: T[]; row: (item: T) => ReactNode; rows?: boolean }) {
  const [all, setAll] = useState(false);
  const shown = firstFew(items, all);
  return (
    <section className="panel-card body-card" aria-labelledby="detail-list-title">
      <div className="body-card-head"><h2 id="detail-list-title">{title}</h2></div>
      {rows ? <ul className="rows">{shown.map(row)}</ul> : shown.map(row)}
      {items.length > FIRST && <ShowAll all={all} count={items.length} onToggle={() => setAll(!all)} />}
    </section>
  );
}

function ValueRow({ title, sub, value }: { title: string; sub?: string | null; value: string }) {
  return (
    <li className="row detail-row">
      <div className="row-main">
        <div className="row-title">{title}</div>
        {sub && <div className="row-sub">{sub}</div>}
      </div>
      <div className="row-end"><strong>{value}</strong></div>
    </li>
  );
}

// ---- 24 hours, as recorded ----

function DayView({ detail, device, version }: { detail: Detail; device?: string; version: number }) {
  const units = useUnits();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const load = useLoad(async (): Promise<RecentReadings | null> => {
    try {
      return await api.recent(RECENT_HOURS, device);
    } catch (e) {
      if (device && e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  }, [device, version]);
  // The usual: the 30 days before today, from every device (so not with one chosen).
  const date = today();
  const usual = useLoad(async (): Promise<number | null> => {
    if (device) return null;
    if (detail.dayUsual) {
      const t = await api.trends(USUAL_DAYS + 1);
      return usualOf(t.days.filter((d) => d.date < date).map(detail.dayUsual));
    }
    if (detail.daily.metric) {
      const to = dayStart(date);
      const points = await api.series(detail.daily.metric, to - USUAL_DAYS * 86_400_000, to, seriesPoints(USUAL_DAYS));
      return usualOf(byDay(points.map((p) => ({ at: p.t, value: p.v })), dayOf).map((d) => d.value));
    }
    return null;
  }, [device, date, detail.id]);

  const time = (ms: number) => clockTime(ms, zone);
  const data = load.data;
  if (load.error) return <Sections notice={<ErrorNotice message={load.error} onRetry={load.reload} />} />;
  if (!data) return load.loading ? <Loading /> : <Sections empty="That device isn't here any more." />;
  const axis: TimeAxis = {
    from: data.from, to: data.to,
    ticks: hourTicks(data.from, data.to, 6, zone).map((at) => ({ at, label: clockTime(at, zone, true) })),
    shade: sleepSpans(data.sleep, data.from, data.to), time,
  };
  const usualValue = usual.data ?? null;
  if (detail.special === "sleep") return <SleepDay data={data} usual={usualValue} time={time} zone={zone} loading={load.loading} />;

  const k = kinds(units);
  const readings = readingsByMetric(data.series);
  const main = detail.readings![0];
  const s = readings[main];
  const others = detail.readings!.slice(1).filter((m) => readings[m]);
  // Temperature: against the usual if the device knows it, the skin's own otherwise.
  const shownMetric = s ? main : others[0];
  if (!shownMetric) return <Sections empty="No readings in this time." loading={load.loading} />;
  const shown = readings[shownMetric]!;
  const shownKind = k[shownMetric];
  const steps = shownMetric === "STEPS";
  const points: RecentPoint[] = shown.points;
  const stats = statsOf(points);
  const total = shown.total ?? points.reduce((sum, p) => sum + p.value, 0);
  const hourOf = (ms: number) => `${dateIn(ms, zone)} ${hourIn(ms, zone)}`;
  const hours = byHour(points, hourOf, steps ? "sum" : "average");
  const bucket = (shown.bucketMinutes ?? 60) * 60_000;
  const draw = (m: RecentMetric, height: number, heading?: string) => {
    const r = readings[m]!;
    const kk: Kind = k[m];
    const convert = kk.drawn?.convert ?? ((v: number) => v);
    return (
      <div className="recent-reading" key={m}>
        {heading && <h3 className="recent-subhead">{heading}</h3>}
        {(kk.bars || r.points.length > 1)
          ? <TimeChart
              axis={axis} kind={kk.bars ? "bars" : drawnAsLine(r.points) ? "line" : "points"} color={kk.color} yMin={kk.yMin} yMax={kk.yMax} zero={kk.zero}
              bucketMinutes={r.bucketMinutes ?? 60} height={height}
              points={kk.drawn ? r.points.map((p) => ({ at: p.at, value: convert(p.value) })) : r.points}
              formatY={kk.drawn?.text ?? kk.text} formatTick={kk.tick}
              usual={m === main && !kk.bars && usualValue != null && !detail.daily.noUsual ? convert(usualValue) : undefined}
              label={m === "STEPS" ? stepsSummary(r, (v) => num(v), time) : readingsSummary(kk.name, r, kk.text, time)}
            />
          : <p className="muted">One reading, at {time(r.points[0].at)}.</p>}
      </div>
    );
  };

  const headline = steps
    ? <Headline label="In the last 24 hours" value={shownKind.text(total)} />
    : shown.latest && <Headline label="Latest" value={shownKind.text(shown.latest.value)} when={`at ${time(shown.latest.at)}`} />;
  const chart = (
    <ChartCard title={shownKind.name}>
      {draw(shownMetric, 240, others.length > 0 && shownMetric === main ? shownKind.name : undefined)}
      {shownMetric === main && others.map((m) => draw(m, 160, k[m].name))}
    </ChartCard>
  );
  const busiest = steps ? hours.reduce<(typeof hours)[number] | null>((b, h) => (!b || h.value > b.value ? h : b), null) : null;
  const statsNode = stats && (
    <Stats>
      {steps ? (
        <>
          <Stat label="In all" value={num(total)} unit="steps" />
          {busiest && busiest.value > 0 && <Stat label="Busiest hour" value={num(busiest.value)} unit="steps" sub={`${clockTime(busiest.at, zone, true)} – ${clockTime(busiest.at + bucket, zone, true)}`} />}
          <UsualStat usual={usualValue} value={total} what="These 24 hours" format={(v) => `${num(v)} a day`} from={USUAL_FROM} />
        </>
      ) : (
        <>
          <Stat label="Average" value={shownKind.text(stats.average)} sub={stats.count === 1 ? "One reading" : `${num(stats.count)} readings`} />
          {stats.count > 1 && <Stat label="Lowest" value={shownKind.text(stats.lowest.value)} sub={`at ${time(stats.lowest.at)}`} />}
          {stats.count > 1 && <Stat label="Highest" value={shownKind.text(stats.highest.value)} sub={`at ${time(stats.highest.at)}`} />}
          {!detail.daily.noUsual && shownMetric === main && <UsualStat usual={usualValue} value={stats.average} format={shownKind.text} from={USUAL_FROM} />}
        </>
      )}
    </Stats>
  );
  const list = (
    <ListCard title="Hour by hour" items={steps ? hours.filter((h) => h.value > 0) : hours} row={(h) => (
      <ValueRow key={h.at}
        title={steps ? `${clockTime(h.at, zone, true)} – ${clockTime(h.at + bucket, zone, true)}` : clockTime(h.at, zone, true)}
        sub={steps ? null : h.count === 1 ? `At ${time(h.at)}`
          : shownKind.text(h.low) === shownKind.text(h.high) ? `${h.count} readings` : `${h.count} readings, from ${shownKind.text(h.low)} to ${shownKind.text(h.high)}`}
        value={shownKind.text(h.value)} />
    )} />
  );
  return <Sections headline={headline} chart={chart} stats={statsNode} list={list} loading={load.loading} />;
}

/** Sleep at 24 hours: last night large, with its stages, and each night or nap in the window. */
function SleepDay({ data, usual, time, zone, loading }: { data: RecentReadings; usual: number | null; time: (ms: number) => string; zone?: string; loading: boolean }) {
  const night = mainNight(data.sleep);
  if (!night) return <Sections empty="No sleep in this time." loading={loading} />;
  const n = nightMinutes(night);
  const sessions = [...data.sleep].sort((a, b) => b.start - a.start);
  const efficiency = n.inBed > 0 ? Math.round((n.asleep / n.inBed) * 100) : null;
  const stages = (s: SleepSession) => {
    const m = nightMinutes(s);
    return m.staged ? `Deep ${hm(m.deep)} · REM ${hm(m.rem)} · light ${hm(m.light)} · awake ${hm(m.awake)}` : "No stages from this device";
  };
  return (
    <Sections loading={loading}
      headline={<Headline label="Last night" value={`${hm(n.asleep)} asleep`} when={`${time(night.start)} – ${time(night.end)}`} />}
      chart={<ChartCard title="Last night's stages"><NightBody session={night} zone={zone} device={nightDevice(night, data)} total={false} /></ChartCard>}
      stats={(
        <Stats>
          <Stat label="Asleep" value={hm(n.asleep)} />
          <Stat label="In bed" value={hm(n.inBed)} sub={efficiency != null ? `${efficiency}% of it asleep` : undefined} />
          <UsualStat usual={usual} value={n.asleep} what="Last night" format={hm} from={USUAL_FROM} />
        </Stats>
      )}
      list={(
        <ListCard title="Each night and nap" items={sessions} row={(s) => (
          <ValueRow key={s.id ?? `${s.deviceId}-${s.start}`} title={`${time(s.start)} – ${time(s.end)}${nightDevice(s, data) ? ` · ${nightDevice(s, data)}` : ""}`}
            sub={stages(s)} value={`${hm(nightMinutes(s).asleep)}`} />
        )} />
      )} />
  );
}

// ---- A week, a month or a year of days ----

function DaysView({ detail, days, version, reload, onEdit }: { detail: Detail; days: number; version: number; reload: () => void; onEdit: (w: Workout) => void }) {
  const s = detail.daily;
  const date = today();
  const dates = spanDates(date, days);
  const first = dates[0];
  // Each day's numbers, with the 30 days before for the usual.
  const load = useLoad(async (): Promise<{ span: DayValue[]; before: DayValue[]; target?: number }> => {
    if (s.metric) {
      const from = dayStart(first) - USUAL_DAYS * 86_400_000;
      const points = await api.series(s.metric, from, Date.now(), seriesPoints(days + USUAL_DAYS));
      const all = byDay(points.map((p) => ({ at: p.t, value: p.v })), dayOf, s.how ?? "average");
      return { span: all.filter((d) => d.date >= first), before: all.filter((d) => d.date < first) };
    }
    const t = await api.trends(days + USUAL_DAYS);
    const { span, before } = splitSpan(t.days, days);
    const target = detail.nutrient ? targetOf(t.nutritionTarget, detail.nutrient)?.amount : undefined;
    return { span: dailyValues(span, s.value, s.extra?.value), before: dailyValues(before, s.value), target };
  }, [detail.id, s.title, days, version]);
  const nights = useLoad(() => (detail.special === "sleep" ? api.sleep(days) : Promise.resolve(null)), [detail.special, days, version]);
  const workouts = useLoad(() => (detail.special === "workouts" ? api.workouts(days) : Promise.resolve(null)), [detail.special, days, version]);

  if (load.error) return <Sections notice={<ErrorNotice message={load.error} onRetry={load.reload} />} />;
  if (!load.data) return <Loading />;
  const values = load.data.span;
  const usual = s.noUsual ? null : usualOf(load.data.before.map((d) => d.value));
  const stats = statsOf(values);
  const series: Series = load.data.target != null ? { ...s, goal: load.data.target } : s;
  const byDate = new Map(values.map((d) => [d.date, d]));
  const text = (d: DayValue) => (d.extra != null ? `${s.format(d.value)}/${s.format(d.extra)}` : s.format(d.value));
  const soFar = (d: string) => (d === date && s.kind === "bar" ? " so far" : "");
  const extraStats = s.extra ? statsOf(values.filter((d) => d.extra != null).map((d) => ({ ...d, value: d.extra! }))) : null;

  const chart = (
    <ChartCard title={s.title} note={s.note}>
      <DailyChart series={series} dates={dates} height={240} usual={usual}
        value={(d) => byDate.get(d)?.value ?? null} extra={s.extra ? (d) => byDate.get(d)?.extra ?? null : undefined} />
      {usual != null && <span className="sr-only">Your usual, from {USUAL_FROM}: {s.format(usual)}.</span>}
    </ChartCard>
  );
  const statsNode = stats && (
    <Stats>
      <Stat label="Average" value={extraStats ? `${s.format(stats.average)}/${s.format(extraStats.average)}` : s.format(stats.average)} sub={`${stats.count} of ${days} days`} />
      {stats.count > 1 && <Stat label="Lowest" value={text(stats.lowest)} sub={dayLabel(stats.lowest.date)} />}
      {stats.count > 1 && <Stat label="Highest" value={text(stats.highest)} sub={dayLabel(stats.highest.date)} />}
      <UsualStat usual={usual} value={stats.average} format={s.format} from={USUAL_FROM} />
    </Stats>
  );

  if (detail.special === "sleep") {
    const list = nights.data ?? [];
    const latest = list[0];
    if (nights.data && list.length === 0 && values.length === 0) return <Sections empty="No nights in this time." />;
    return (
      <Sections loading={load.loading || nights.loading}
        headline={latest && <Headline label="Last night" value={`${hm(latest.asleepMinutes)} asleep`} when={`${new Date(latest.end).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" })}`} />}
        chart={values.length > 0 && chart} stats={statsNode}
        list={nights.error ? <ErrorNotice message={nights.error} onRetry={nights.reload} /> : list.length > 0 && <ListCard title="Each night" rows={false} items={list} row={(n) => <NightRow key={n.sessionId ?? n.start} night={n} onChanged={reload} />} />} />
    );
  }

  if (detail.special === "workouts") {
    const list = workouts.data ?? [];
    if (workouts.data && list.length === 0) return <Sections empty="No workouts in this time." />;
    if (!workouts.data) return workouts.error ? <Sections notice={<ErrorNotice message={workouts.error} onRetry={workouts.reload} />} /> : <Loading />;
    const latest = list[0];
    const minutes = (w: Workout) => (w.end - w.start) / 60_000;
    const longest = list.reduce((b, w) => (minutes(w) > minutes(b) ? w : b), list[0]);
    const name = (w: Workout) => w.name ?? workoutLabel(w.type);
    return (
      <Sections loading={workouts.loading}
        headline={<Headline label="The latest" value={name(latest)} when={`${new Date(latest.start).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" })} · ${hm(minutes(latest))}`} />}
        chart={values.length > 0 && chart}
        stats={(
          <Stats>
            <Stat label="Workouts" value={num(list.length)} />
            <Stat label="Time in all" value={hm(list.reduce((sum, w) => sum + minutes(w), 0))} />
            <Stat label="Longest" value={hm(minutes(longest))} sub={`${name(longest)}, ${shortDate(dayOf(longest.start))}`} />
          </Stats>
        )}
        list={<ListCard title="Each workout" items={list} row={(w) => <WorkoutRow key={w.id ?? w.start} workout={w} full onEdit={() => onEdit(w)} onChanged={reload} />} />} />
    );
  }

  if (values.length === 0) return <Sections empty="No readings in this time." loading={load.loading} />;
  const latest = values[values.length - 1];
  return (
    <Sections loading={load.loading}
      headline={<Headline label={latest.date === date ? `Today${soFar(latest.date)}` : "The latest"} value={text(latest)} when={latest.date === date ? null : dayLabel(latest.date)} />}
      chart={chart} stats={statsNode}
      list={<ListCard title="Each day" items={newestFirst(values)} row={(d) => <ValueRow key={d.date} title={`${dayLabel(d.date)}${soFar(d.date)}`} value={text(d)} />} />} />
  );
}

