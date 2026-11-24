import { Fragment, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Clock } from "lucide-react";
import { ApiError, api } from "../lib/api";
import { hm, num, today, useUnits, when } from "../lib/format";
import type { BodySpan } from "../lib/nav";
import { detailOf, detailPath } from "../lib/bodyDetail";
import {
  BODY_DAY_SECTIONS, BODY_DAY_TILES, RECENT_HOURS, againstUsual, devicesTogether, drawnAsLine, lastSynced, lowestPoint, mainNight, nightDevice,
  nightMinutes, nightSummary, nothingRecorded, readingsByMetric, readingsSummary, sleepSpans, stepsSummary,
  temperatureChangeText, temperatureText, usualOf, type RecentMetric, type RecentSection,
} from "../lib/recent";
import { useSession } from "../lib/session";
import { clockTime, hourTicks } from "../lib/today";
import { layoutKey, type RecentReadings, type SleepSession } from "../lib/types";
import { Hypnogram, TimeChart, type TimeAxis } from "../components/charts";
import { useLogged } from "../components/LogSheet";
import { Button, Card, CardCover, Empty, ErrorNotice, Spinner, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { BodyFrame, DeviceFilter, useReadingDevices } from "./Body";
import { kinds, oneDecimal } from "./bodyMetrics";

/*
 * Body at 24 hours, as the person's devices recorded it (GET /api/recent): last night large, with
 * its stages; heart rate with the night shaded; small tiles for HRV, blood oxygen, breathing,
 * temperature and stress, each against the person's usual where that's known (the last 30 days);
 * then steps by the hour. Every card opens its details (BodyDetail.tsx). One device or all of them.
 * The sections keep Last 24 hours' saved arrangement (layoutKey("recent")) and ids.
 */

/** A tile: its name, number, one line under it (notable when it's well off the usual), and the readings it's about. */
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
  /** Where a card's details are, and their name for screen readers. */
  const details = (id: RecentSection, name: string) => ({ to: detailPath(detailOf(id), "day", device), label: `${name}: details` });

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
      <Link to={details(id, t.name).to} className="body-tile" aria-label={`${t.name}: ${t.value}${t.sub ? `, ${t.sub}` : ""}. Details`}>
        <span className="body-tile-name">{t.name}</span>
        <span className="body-tile-value">{t.value}</span>
        {t.sub && <span className={`body-tile-sub ${t.notable ? "is-notable" : ""}`}>{t.sub}</span>}
      </Link>
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
        <section className="panel-card body-card night-card is-linked" aria-labelledby="night-title">
          <CardCover {...details("sleep", "Last night")} />
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
        <section className="panel-card body-card is-linked" aria-labelledby="heart-title">
          <CardCover {...details("heart", "Heart rate")} />
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
        <section className="panel-card body-card is-linked" aria-labelledby="steps-title">
          <CardCover {...details("steps", "Steps")} />
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
export function NightBody({ session, zone, device, total = true }: { session: SleepSession; zone?: string; device: string | null; total?: boolean }) {
  const time = (ms: number) => clockTime(ms, zone);
  const n = nightMinutes(session);
  return (
    <>
      {total && <p className="body-big">{hm(n.asleep)} <span className="body-big-unit">asleep{device ? ` · ${device}` : ""}</span></p>}
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
