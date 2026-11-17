import { Fragment, useState, type ReactNode } from "react";
import { Clock } from "lucide-react";
import { ApiError, api } from "../lib/api";
import { hm, num, today, useUnits, when } from "../lib/format";
import type { BodySpan } from "../lib/nav";
import {
  BODY_DAY_SECTIONS, BODY_DAY_TILES, RECENT_HOURS, againstUsual, devicesTogether, drawnAsLine, lastSynced, lowestPoint, mainNight, nightDevice,
  nightMinutes, nightSummary, nothingRecorded, readingStats, readingsByMetric, readingsSummary, signed, sleepSpans, stepsSummary, temperature,
  temperatureChange, temperatureChangeText, temperatureText, temperatureUnit, usualOf, type RecentMetric, type RecentSection,
} from "../lib/recent";
import { useSession } from "../lib/session";
import { clockTime, hourTicks } from "../lib/today";
import { layoutKey, type RecentReadings, type RecentSeries, type SleepSession, type Units } from "../lib/types";
import { Hypnogram, TimeChart, type TimeAxis } from "../components/charts";
import { useLogged } from "../components/LogSheet";
import { Button, Card, Empty, ErrorNotice, Modal, Spinner, Stat, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { BodyFrame, DeviceFilter, useReadingDevices } from "./Body";

/*
 * Body at 24 hours, as the person's devices recorded it (GET /api/recent): last night large, with
 * its stages; heart rate with the night shaded; small tiles for HRV, blood oxygen, breathing,
 * temperature and stress, each against the person's usual where that's known (the last 30 days)
 * and each opening its chart; then steps by the hour. One device or all of them. The sections keep
 * Last 24 hours' saved arrangement (layoutKey("recent")) and ids.
 */

const oneDecimal = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 1 });

/** How each kind of reading is drawn and written. */
interface Kind {
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

function kinds(units: Units): Record<RecentMetric, Kind> {
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

/** A tile: its name, number, one line under it (notable when it's well off the usual), and the readings its chart shows. */
interface Tile {
  name: string;
  value: string;
  sub?: string;
  notable?: boolean;
  metrics: RecentMetric[];
}

export function BodyDay({ device, choose, setSpan }: { device?: string; choose: (id: string | null) => void; setSpan: (s: BodySpan) => void }) {
  const units = useUnits();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const logged = useLogged();
  const load = useLoad(async (): Promise<RecentReadings | "gone"> => {
    try {
      return await api.recent(RECENT_HOURS, device);
    } catch (e) {
      if (device && e instanceof ApiError && e.status === 404) return "gone";
      throw e;
    }
  }, [device, logged]);
  // The person's usual, from the last 30 days (combined, so only with every device showing).
  const usual = useLoad(() => (device ? Promise.resolve(null) : api.trends(30).catch(() => null)), [device]);
  // Today's resting heart rate, worked out from every device.
  const date = today();
  const day = useLoad(() => (device ? Promise.resolve(null) : api.day(date).catch(() => null)), [device, date]);
  const known = useReadingDevices();
  const [open, setOpen] = useState<RecentSection | null>(null);

  const data = load.data && load.data !== "gone" ? load.data : null;
  const empty = data ? nothingRecorded(data) : false;
  const time = (ms: number) => clockTime(ms, zone);
  const k = kinds(units);
  const readings = data ? readingsByMetric(data.series) : {};
  const axis: TimeAxis | null = data && {
    from: data.from, to: data.to,
    ticks: hourTicks(data.from, data.to, 6, zone).map((at) => ({ at, label: clockTime(at, zone, true) })),
    shade: sleepSpans(data.sleep, data.from, data.to), time,
  };
  const before = (usual.data?.days ?? []).filter((d) => d.date < date);

  /** A kind of reading: its numbers, then its chart. */
  const chart = (metric: RecentMetric, s: RecentSeries, heading?: string) => {
    const kind = k[metric];
    return (
      <div className="recent-reading" key={metric}>
        {heading && <h4 className="recent-subhead">{heading}</h4>}
        <div className="stats recent-stats">
          {readingStats(metric, s).map((stat) => (
            <Stat key={stat.label} label={stat.label} value={kind.value(stat.value)} unit={kind.unit} sub={stat.at != null ? time(stat.at) : undefined} />
          ))}
        </div>
        {(kind.bars || s.points.length > 1) && axis && (
          <TimeChart
            axis={axis} kind={kind.bars ? "bars" : drawnAsLine(s.points) ? "line" : "points"} color={kind.color} yMin={kind.yMin} yMax={kind.yMax} zero={kind.zero}
            bucketMinutes={s.bucketMinutes ?? 60}
            points={kind.drawn ? s.points.map((p) => ({ at: p.at, value: kind.drawn!.convert(p.value) })) : s.points}
            formatY={kind.drawn?.text ?? kind.text} formatTick={kind.tick}
            label={metric === "STEPS" ? stepsSummary(s, (v) => num(v), time) : readingsSummary(kind.name, s, kind.text, time)}
          />
        )}
      </div>
    );
  };

  // ---- The tiles ----
  const tiles: Partial<Record<RecentSection, Tile | null>> = {};
  const hrv = readings.HRV;
  if (hrv?.average != null) {
    const against = againstUsual(hrv.average, usualOf(before.map((d) => d.hrv)));
    tiles.hrv = {
      name: "HRV", value: `${num(hrv.average)} ms`, metrics: ["HRV"], notable: against?.word === "below" && against.share >= 0.15,
      sub: against ? (against.word === "usual" ? "About your usual" : `${Math.round(against.share * 100)}% ${against.word} your usual ${num(usualOf(before.map((d) => d.hrv))!)}`)
        : hrv.latest ? `Latest ${num(hrv.latest.value)} ms at ${time(hrv.latest.at)}` : undefined,
    };
  }
  const spo2 = readings.SPO2;
  if (spo2?.average != null) {
    const low = lowestPoint(spo2);
    tiles.spo2 = { name: "Blood oxygen", value: `${Math.round(spo2.average)}%`, metrics: ["SPO2"], sub: low ? `Lowest ${oneDecimal(low.value)}% at ${time(low.at)}` : undefined };
  }
  const breathing = readings.RESPIRATORY_RATE;
  if (breathing?.average != null) {
    tiles.breathing = {
      name: "Breathing", value: `${oneDecimal(breathing.average)} a min`, metrics: ["RESPIRATORY_RATE"],
      sub: breathing.min != null && breathing.max != null && breathing.min !== breathing.max ? `From ${oneDecimal(breathing.min)} to ${oneDecimal(breathing.max)}` : undefined,
    };
  }
  const change = readings.TEMPERATURE_DEVIATION;
  const skin = readings.SKIN_TEMPERATURE;
  if (change?.latest) {
    const c = change.latest.value;
    tiles.temperature = {
      name: "Temperature", value: temperatureChangeText(c, units), metrics: ["TEMPERATURE_DEVIATION", ...(skin ? ["SKIN_TEMPERATURE" as const] : [])], notable: c >= 0.3,
      sub: Math.abs(c) < 0.1 ? "About your usual" : c > 0 ? "Warmer than usual" : "Cooler than usual",
    };
  } else if (skin?.latest) {
    tiles.temperature = {
      name: "Temperature", value: temperatureText(skin.latest.value, units), metrics: ["SKIN_TEMPERATURE"],
      sub: skin.min != null && skin.max != null ? `From ${temperatureText(skin.min, units)} to ${temperatureText(skin.max, units)}` : undefined,
    };
  }
  const stress = readings.STRESS;
  if (stress?.average != null) {
    tiles.stress = { name: "Stress", value: `${num(stress.average)} of 100`, metrics: ["STRESS"], sub: stress.max != null ? `Highest ${num(stress.max)}` : undefined };
  }

  const tile = (id: RecentSection) => {
    const t = tiles[id];
    if (!t) return null;
    return (
      <button type="button" className="body-tile" onClick={() => setOpen(id)} aria-haspopup="dialog" aria-label={`${t.name}: ${t.value}${t.sub ? `, ${t.sub}` : ""}. Its chart`}>
        <span className="body-tile-name">{t.name}</span>
        <span className="body-tile-value">{t.value}</span>
        {t.sub && <span className={`body-tile-sub ${t.notable ? "is-notable" : ""}`}>{t.sub}</span>}
      </button>
    );
  };

  // ---- The night, the heart and the steps ----
  const night = data ? mainNight(data.sleep) : null;
  const heart = readings.HEART_RATE;
  const steps = readings.STEPS;
  const resting = !device ? day.data?.restingHeartRate : undefined;

  const shows = !!data && !empty;
  const sections: (Section & { id: RecentSection })[] = BODY_DAY_SECTIONS.map((id): Section & { id: RecentSection } => {
    switch (id) {
      case "sleep": return { id, label: "Last night", node: shows && (
        <section className="panel-card body-card night-card" aria-labelledby="night-title">
          <div className="body-card-head">
            <h2 id="night-title">Last night</h2>
            {night && <span>{time(night.start)} – {time(night.end)}</span>}
          </div>
          {night ? <NightBody session={night} zone={zone} device={nightDevice(night, data!)} /> : <p className="muted">No sleep recorded in the last 24 hours.</p>}
          {data!.sleep.filter((s) => s !== night).map((s) => (
            <div key={s.id ?? `${s.deviceId}-${s.start}`} className="night-more">
              <span>{nightDevice(s, data!) ?? (s.end - s.start < 3 * 3_600_000 ? "A nap" : "Also")}: {time(s.start)} – {time(s.end)}</span>
              <strong>{hm(nightMinutes(s).asleep)}</strong>
            </div>
          ))}
        </section>
      ) };
      case "heart": return { id, label: "Heart rate", node: shows && heart && (
        <section className="panel-card body-card" aria-labelledby="heart-title">
          <div className="body-card-head">
            <h2 id="heart-title">Heart rate</h2>
            <span>{devicesTogether(data!.devices, device)}</span>
          </div>
          <div className="body-figures">
            {resting != null
              ? <Figure value={num(resting)} label="resting" />
              : heart.min != null && <Figure value={num(heart.min)} label="lowest" />}
            {heart.average != null && <Figure value={num(heart.average)} label="average" />}
            {heart.max != null && <Figure value={num(heart.max)} label="highest" />}
          </div>
          {heart.points.length > 1 && axis && (
            <TimeChart axis={axis} kind={drawnAsLine(heart.points) ? "line" : "points"} color="var(--c1)" points={heart.points} bucketMinutes={heart.bucketMinutes ?? 60}
              formatY={k.HEART_RATE.text} label={readingsSummary("Heart rate", heart, k.HEART_RATE.text, time)} height={140} />
          )}
        </section>
      ) };
      case "steps": return { id, label: "Steps", node: shows && steps && (
        <section className="panel-card body-card" aria-labelledby="steps-title">
          <div className="body-card-head">
            <h2 id="steps-title">Steps</h2>
            <strong className="body-card-total">{num(steps.total ?? steps.points.reduce((s, p) => s + p.value, 0))}</strong>
          </div>
          {axis && <TimeChart axis={axis} kind="bars" color="var(--c2)" points={steps.points} bucketMinutes={steps.bucketMinutes ?? 60} formatY={k.STEPS.text}
            label={stepsSummary(steps, (v) => num(v), time)} height={110} />}
        </section>
      ) };
      default: return { id, label: tileLabel(id), node: shows && tile(id) };
    }
  });
  const arranged = useArrange(layoutKey("recent"), "Body at 24 hours", sections);

  // Full-width cards one under another; the tiles that come together in a grid.
  const blocks: ReactNode[] = [];
  let run: ReactNode[] = [];
  const flush = () => {
    if (run.length > 0) blocks.push(<div key={`tiles-${blocks.length}`} className="body-tiles">{run}</div>);
    run = [];
  };
  for (const s of arranged.shown as (Section & { id: RecentSection })[]) {
    if (!s.node) continue;
    if (BODY_DAY_TILES.includes(s.id)) run.push(<Fragment key={s.id}>{s.node}</Fragment>);
    else { flush(); blocks.push(<Fragment key={s.id}>{s.node}</Fragment>); }
  }
  flush();

  const devices = data ? [...data.devices, ...known.filter((d) => !data.devices.some((x) => x.id === d.id))] : known;
  const synced = data && empty ? lastSynced([...data.devices, ...known], device) : null;
  const shown = open ? tiles[open] : null;

  return (
    <BodyFrame span="day" setSpan={setSpan} actions={<>
      {(devices.length > 1 || device) && <DeviceFilter devices={devices} device={device} choose={choose} />}
      {shows && arranged.button}
    </>}>
      {load.error && <ErrorNotice message={load.error} onRetry={load.reload} />}
      {load.data === "gone" && (
        <div className="notice notice-row" role="status">
          <span>That device isn't here any more.</span>
          <Button variant="ghost" onClick={() => choose(null)}>Show every device</Button>
        </div>
      )}
      {!load.data && load.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && empty && (
        <Card>
          <Empty icon={<Clock />} title="Nothing recorded in the last 24 hours.">
            {synced ? `${synced.name} last synced ${when(synced.at)}.` : "Readings show here once a device syncs."}
          </Empty>
        </Card>
      )}
      {shows && (
        <div className={`body-stack ${load.loading ? "is-refreshing" : ""}`} aria-busy={load.loading}>
          {blocks}
          {arranged.shown.length === 0 && <p className="muted arranged-empty">Everything here is hidden. Use Arrange, at the top, to show it again.</p>}
        </div>
      )}
      {shown && open && (
        <Modal title={k[shown.metrics[0]].name} subtitle="The last 24 hours" onClose={() => setOpen(null)} wide>
          {shown.metrics.map((m, i) => readings[m] && <Fragment key={m}>{chart(m, readings[m]!, shown.metrics.length > 1 && i > 0 ? k[m].name : undefined)}</Fragment>)}
        </Modal>
      )}
    </BodyFrame>
  );
}

const TILE_LABELS: Partial<Record<RecentSection, string>> = {
  hrv: "Heart rate variability", spo2: "Blood oxygen", breathing: "Breathing rate", temperature: "Temperature", stress: "Stress",
};
const tileLabel = (id: RecentSection) => TILE_LABELS[id] ?? id;

/** A big number with its word under it: "54", "resting". */
function Figure({ value, label }: { value: string; label: string }) {
  return (
    <span className="body-figure">
      <span className="body-figure-value">{value}</span>
      <span className="body-figure-label">{label}</span>
    </span>
  );
}

/** Last night: how long asleep, its stages, and the minutes in each. */
function NightBody({ session, zone, device }: { session: SleepSession; zone?: string; device: string | null }) {
  const time = (ms: number) => clockTime(ms, zone);
  const n = nightMinutes(session);
  return (
    <>
      <p className="body-big">{hm(n.asleep)} <span className="body-big-unit">asleep{device ? ` · ${device}` : ""}</span></p>
      {session.stages.length > 0 && (
        <Hypnogram stages={session.stages} start={session.start} end={session.end} height={128} time={time}
          hours={hourTicks(session.start, session.end, 2, zone).map((at) => ({ at, label: clockTime(at, zone, true) }))}
          label={nightSummary(session, time, hm)} />
      )}
      {n.staged && (
        <div className="stage-tiles">
          <StageTile name="Deep" minutes={n.deep} stage="deep" />
          <StageTile name="REM" minutes={n.rem} stage="rem" />
          <StageTile name="Light" minutes={n.light} stage="light" />
          <StageTile name="Awake" minutes={n.awake} stage="awake" />
        </div>
      )}
    </>
  );
}

function StageTile({ name, minutes, stage }: { name: string; minutes: number; stage: string }) {
  return (
    <span className="stage-tile">
      <span className="stage-tile-name"><span className={`stage-dot stage-${stage}`} aria-hidden="true" />{name}</span>
      <strong>{hm(minutes)}</strong>
    </span>
  );
}
