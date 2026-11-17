import type { ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { Activity, Clock, Droplets, Footprints, Gauge, HeartPulse, Moon, Thermometer, Wind } from "lucide-react";
import { ApiError, api } from "../lib/api";
import { hm, num, useUnits, when } from "../lib/format";
import {
  RECENT_HOURS, drawnAsLine, lastSynced, nightDevice, nightMinutes, nightSummary, nothingRecorded, readingStats, readingsByMetric, readingsSummary, signed, sleepSpans,
  stepsSummary, temperature, temperatureChange, temperatureChangeText, temperatureText, temperatureUnit, type RecentMetric,
} from "../lib/recent";
import { useSession } from "../lib/session";
import { clockTime, hourTicks } from "../lib/today";
import { layoutKey, type RecentDevice, type RecentReadings, type RecentSeries, type SleepSession, type Units } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { Hypnogram, StageLegend, TimeChart, type TimeAxis } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, PageHeader, Spinner, Stat, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";

/*
 * The last 24 hours, as the person's devices recorded them (GET /api/recent): last night's stages,
 * and heart rate, blood oxygen, HRV, stress, breathing, temperature and steps on one time axis,
 * with the sleep shaded behind each chart so the night lines up. One device or all of them
 * (?device=<id>). Only ever the person's own: the server never gives it to a share, and the page
 * isn't offered while viewing one.
 */

const oneDecimal = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 1 });

/** How each kind of reading is drawn and written. */
interface Kind {
  name: string;
  /** The number on its own and its unit, for the stats. */
  value: (v: number) => string;
  unit?: string;
  /** The number with its unit, for the tooltip and screen readers. */
  text: (v: number) => string;
  /** The chart's y axis labels. */
  tick?: (v: number) => string;
  /** Counts (steps) are bars; the rest a line when they came often, points when there were a few. */
  bars?: boolean;
  color: string;
  yMin?: number;
  yMax?: number;
  zero?: boolean;
  /** Turns the server's numbers into what's drawn (°C into °F), and writes those for the tooltip. */
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
    STEPS: { name: "Steps", value: (v) => num(v), unit: "steps", text: (v) => `${num(v)} steps`, bars: true, color: "var(--c1)" },
  };
}

export function Recent() {
  const [params, setParams] = useSearchParams();
  const device = params.get("device") || undefined;
  const readOnly = useReadOnly();
  const units = useUnits();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  // Never while viewing someone's share (the route isn't there then either): the server says no.
  const load = useLoad(async (): Promise<RecentReadings | "gone" | null> => {
    if (readOnly) return null;
    try {
      return await api.recent(RECENT_HOURS, device);
    } catch (e) {
      if (device && e instanceof ApiError && e.status === 404) return "gone";
      throw e;
    }
  }, [device, readOnly]);
  const data = load.data && load.data !== "gone" ? load.data : null;
  const empty = data ? nothingRecorded(data) : false;
  // Every device and its last sync: for "nothing recorded", and a chosen device with nothing in the window.
  const needDevices = !!data && (empty || (!!device && !data.devices.some((d) => d.id === device)));
  const known = useLoad(() => (needDevices ? api.devices().catch(() => null) : Promise.resolve(null)), [needDevices]);

  const choose = (id: string | null) => setParams(id ? { device: id } : {}, { replace: true });
  const time = (ms: number) => clockTime(ms, zone);
  const chosen: RecentDevice | undefined = device ? data?.devices.find((d) => d.id === device) ?? known.data?.find((d) => d.id === device) : undefined;
  const chips = data && (data.devices.length > 1 || device) ? [...data.devices, ...(chosen && !data.devices.includes(chosen) ? [chosen] : [])] : [];

  const axis: TimeAxis | null = data && {
    from: data.from,
    to: data.to,
    ticks: hourTicks(data.from, data.to, 6, zone).map((at) => ({ at, label: clockTime(at, zone, true) })),
    shade: sleepSpans(data.sleep, data.from, data.to),
    time,
  };
  const readings = data ? readingsByMetric(data.series) : {};
  const k = kinds(units);
  const shows = (data && !empty && axis) || null;

  /** A kind of reading: its numbers, then its chart. */
  const block = (metric: RecentMetric, s: RecentSeries, heading?: string) => {
    const kind = k[metric];
    return (
      <div className="recent-reading" key={metric}>
        {heading && <h4 className="recent-subhead">{heading}</h4>}
        <div className="stats recent-stats">
          {readingStats(metric, s).map((stat) => (
            <Stat key={stat.label} label={stat.label} value={kind.value(stat.value)} unit={kind.unit} sub={stat.at != null ? time(stat.at) : undefined} />
          ))}
        </div>
        {/* A single reading (a ring's nightly temperature) is its numbers alone: Latest has its time. */}
        {(kind.bars || s.points.length > 1) && (
          <TimeChart
            axis={axis!} kind={kind.bars ? "bars" : drawnAsLine(s.points) ? "line" : "points"} color={kind.color} yMin={kind.yMin} yMax={kind.yMax} zero={kind.zero}
            bucketMinutes={s.bucketMinutes ?? 60}
            points={kind.drawn ? s.points.map((p) => ({ at: p.at, value: kind.drawn!.convert(p.value) })) : s.points}
            formatY={kind.drawn?.text ?? kind.text}
            formatTick={kind.tick}
            label={metric === "STEPS" ? stepsSummary(s, (v) => num(v), time) : readingsSummary(kind.name, s, kind.text, time)}
          />
        )}
      </div>
    );
  };
  const card = (title: string, icon: ReactNode, body: ReactNode) => <Card title={title} icon={icon}>{body}</Card>;
  const one = (metric: RecentMetric, title: string, icon: ReactNode) => {
    const s = readings[metric];
    return shows && s ? card(title, icon, block(metric, s)) : null;
  };
  const skin = readings.SKIN_TEMPERATURE;
  const change = readings.TEMPERATURE_DEVIATION;

  const sections: Section[] = [
    { id: "sleep", label: "Sleep", node: shows && card("Sleep", <Moon size={18} />, data!.sleep.length === 0
      ? <p className="muted">No sleep recorded in the last 24 hours.</p>
      : data!.sleep.map((s) => <Night key={s.id ?? `${s.deviceId}-${s.start}`} session={s} device={nightDevice(s, data!)} zone={zone} />)) },
    { id: "heart", label: "Heart rate", node: one("HEART_RATE", "Heart rate", <HeartPulse size={18} />) },
    { id: "spo2", label: "Blood oxygen", node: one("SPO2", "Blood oxygen", <Droplets size={18} />) },
    { id: "hrv", label: "Heart rate variability", node: one("HRV", "Heart rate variability", <Activity size={18} />) },
    { id: "stress", label: "Stress", node: one("STRESS", "Stress", <Gauge size={18} />) },
    { id: "breathing", label: "Breathing rate", node: one("RESPIRATORY_RATE", "Breathing rate", <Wind size={18} />) },
    { id: "temperature", label: "Skin temperature", node: shows && (skin || change) && card("Skin temperature", <Thermometer size={18} />, <>
      {skin && block("SKIN_TEMPERATURE", skin)}
      {change && block("TEMPERATURE_DEVIATION", change, skin ? "Against your usual" : undefined)}
      {change && !skin && <p className="muted small">Against your usual.</p>}
    </>) },
    { id: "steps", label: "Steps", node: one("STEPS", "Steps", <Footprints size={18} />) },
  ];
  const arranged = useArrange(layoutKey("recent"), "Last 24 hours", sections);

  const synced = data && empty ? lastSynced([...data.devices, ...(known.data ?? [])], device) : null;
  const charted = Object.keys(readings).length > 0;

  return (
    <div className="page recent">
      <PageHeader title="Last 24 hours" subtitle={chosen ? `As ${chosen.name} recorded it` : "As your devices recorded it"} actions={shows ? arranged.button : null} />
      {(chips.length > 0 || (shows && charted && data!.sleep.length > 0)) && (
        <div className="recent-bar">
          {chips.length > 0 && (
            <div className="tag-list" role="radiogroup" aria-label="Which device">
              <button type="button" role="radio" aria-checked={!device} className={`chip ${!device ? "is-on" : ""}`} onClick={() => choose(null)}>All devices</button>
              {chips.map((d) => (
                <button key={d.id} type="button" role="radio" aria-checked={device === d.id} className={`chip ${device === d.id ? "is-on" : ""}`} onClick={() => choose(d.id)}>{d.name}</button>
              ))}
            </div>
          )}
          {shows && charted && data!.sleep.length > 0 && (
            <ul className="legend recent-key"><li><span className="legend-key sleep-key" />Sleep</li></ul>
          )}
        </div>
      )}
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
            {synced ? `${synced.name} last synced ${when(synced.at)}.` : known.loading && needDevices ? null : "Readings show here once a device syncs."}
          </Empty>
        </Card>
      )}
      {shows && <div className={load.loading ? "is-refreshing" : undefined} aria-busy={load.loading}>{arranged.render("grid recent-grid")}</div>}
    </div>
  );
}

/** One night or nap: when, how long asleep, its stages, and the minutes in each. */
function Night({ session, device, zone }: { session: SleepSession; device: string | null; zone?: string }) {
  const time = (ms: number) => clockTime(ms, zone);
  const n = nightMinutes(session);
  const sub = [`${hm(n.inBed)} in bed`, device].filter(Boolean).join(" · ");
  return (
    <div className="night">
      <div className="night-head">
        <div>
          <strong>{time(session.start)} – {time(session.end)}</strong>
          <div className="muted small">{sub}</div>
        </div>
        <div className="night-asleep"><strong>{hm(n.asleep)}</strong> <span className="muted small">asleep</span></div>
      </div>
      {session.stages.length > 0 && (
        <Hypnogram
          stages={session.stages} start={session.start} end={session.end} height={130} time={time}
          hours={hourTicks(session.start, session.end, 1, zone).map((at) => ({ at, label: clockTime(at, zone, true) }))}
          label={nightSummary(session, time, hm)}
        />
      )}
      {n.staged && <StageLegend deep={n.deep} rem={n.rem} light={n.light} awake={n.awake} />}
    </div>
  );
}
