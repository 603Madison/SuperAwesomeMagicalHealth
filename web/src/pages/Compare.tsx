import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Activity, ArrowLeftRight, ChevronLeft, GitCompareArrows, Moon } from "lucide-react";
import { ApiError, api } from "../lib/api";
import {
  COMPARE_DEFAULT_NIGHTS, COMPARE_KEYS, COMPARE_SPANS, asleepMinutes, called, drawnText, duration, knownMetrics, metricSentence, nightRows, nightSpan,
  nightsParam, placement, shortNames, tickText, title, trendKeys, trendPoints, valueText, type CompareFormat, type ShortNames,
} from "../lib/compare";
import { isoDate, parseDate, shiftDate, shortDate, useUnits } from "../lib/format";
import { nightSummary } from "../lib/recent";
import { useSession } from "../lib/session";
import { clockTime, hourTicks, zonedTime } from "../lib/today";
import { layoutKey, type CompareKey, type ComparedNight, type DeviceComparison, type RecentDevice, type SleepSession } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { Hypnogram, LineChart } from "../components/charts";
import { Button, Card, Empty, ErrorNotice, Field, IconButton, PageHeader, Segmented, Spinner, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";

/*
 * Two devices side by side (GET /api/devices/compare?a=&b=&days=): how far one reads from the other
 * over the nights both measured (a ring against a chest strap, say), then each night with both
 * devices' stages on one time axis and their numbers. Only ever the person's own: the server never
 * gives it to a share, and the page isn't offered while viewing one.
 */

const A_COLOR = "var(--c1)";
const B_COLOR = "var(--c2)";

export function Compare() {
  const [params, setParams] = useSearchParams();
  const askedA = params.get("a") || undefined;
  const askedB = params.get("b") || undefined;
  const days = nightsParam(params.get("days"));
  const readOnly = useReadOnly();
  const units = useUnits();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  // Never while viewing someone's share (the route isn't there then either): the server says no.
  const load = useLoad(async (): Promise<DeviceComparison | "gone" | null> => {
    if (readOnly) return null;
    try {
      // The same device twice (an address typed by hand): the server picks the other.
      return await api.compareDevices(days, askedA, askedB === askedA ? undefined : askedB);
    } catch (e) {
      if ((askedA || askedB) && e instanceof ApiError && e.status === 404) return "gone";
      throw e;
    }
  }, [askedA, askedB, days, readOnly]);
  const data = load.data && load.data !== "gone" ? load.data : null;

  const fmt: CompareFormat = {
    time: (ms) => clockTime(ms, zone),
    evening: (date) => zonedTime(shiftDate(date, -1), "18:00", zone),
    units,
  };
  const names = shortNames(data?.a, data?.b);
  const a = data?.a;
  const b = data?.b;
  const ready = !!data && !!a && !!b;
  const metrics = data ? knownMetrics(data.metrics) : [];

  /** Shows [nextA] against [nextB] over [nextDays]; the address keeps the choice. */
  const show = (nextA: string | undefined, nextB: string | undefined, nextDays = days) => {
    const p: Record<string, string> = {};
    if (nextA) p.a = nextA;
    if (nextB) p.b = nextB;
    if (nextDays !== COMPARE_DEFAULT_NIGHTS) p.days = String(nextDays);
    setParams(p, { replace: true });
  };
  // Choosing the other one's device swaps them: two different devices, always.
  const chooseA = (id: string) => show(id, id === b?.id ? a?.id : b?.id);
  const chooseB = (id: string) => show(id === a?.id ? b?.id : a?.id, id);
  const choices: RecentDevice[] = data ? [...data.devices, ...[a, b].filter((d): d is RecentDevice => !!d && !data.devices.some((x) => x.id === d.id))] : [];

  const sections: Section[] = [
    { id: "summary", label: "How far apart", node: ready && data.nights.length > 0 && (
      <Card title="How far apart" icon={<GitCompareArrows size={18} />} className="span-2">
        <DeviceKey names={names} a={a!} b={b!} />
        {metrics.length === 0 ? (
          <p className="muted">They haven't measured the same night yet. Wear both to bed together, and once both have synced, this shows how far apart they read.</p>
        ) : (
          <ul className="rows compare-metrics">
            {metrics.map((m) => (
              <li key={m.key} className="row compare-metric">
                <div className="row-main">
                  <div className="row-title">{COMPARE_KEYS[m.key].label}</div>
                  <div className="row-sub wraps">{metricSentence(m, names, fmt).join(" · ")}</div>
                </div>
                <dl className="compare-averages" aria-label="On average">
                  {(["a", "b"] as const).map((w) => (
                    <div key={w}>
                      <dt><span className="legend-key" style={{ background: w === "a" ? A_COLOR : B_COLOR }} aria-hidden="true" />{title(names, w)}</dt>
                      <dd>{valueText(m.key, m[w], fmt, data!.to)}</dd>
                    </div>
                  ))}
                </dl>
              </li>
            ))}
          </ul>
        )}
        <p className="muted small">
          Readings are set side by side only where both were measuring: heart rate five minutes at a time, the rest half an hour at a time. Sleep and steps are each device's own.
          {placement(a!.kind, a!.name) === "strap" && " A chest strap reads the heart's own electrical beat, which makes it a good one to compare against."}
        </p>
      </Card>
    ) },
    { id: "trend", label: "Night by night", node: ready && trendKeys(data.metrics).length > 0 && (
      <Trend data={data} names={names} fmt={fmt} />
    ) },
    { id: "nights", label: "Each night", node: ready && data.nights.length > 0 && (
      <>
        <h2 className="section-title compare-nights-title span-2">Each night</h2>
        {data.nights.map((n) => <NightCard key={n.date} night={n} names={names} fmt={fmt} zone={zone} />)}
      </>
    ) },
  ];
  const arranged = useArrange(layoutKey("compare"), "Compare devices", sections);

  const empty = data && (!a || !b);
  const quiet = ready && data.nights.length === 0;

  return (
    <div className="page compare">
      <PageHeader title="Compare devices" subtitle="How far two devices read from each other, night by night" actions={<>
        <Link className="btn btn-ghost" to="/devices"><ChevronLeft size={18} /> Devices</Link>
        {ready && !quiet ? arranged.button : null}
      </>} />
      {data && (
        <div className="compare-bar">
          {ready && (
            <div className="compare-pick">
              <Field label="Compare against">
                <select value={a!.id} onChange={(e) => chooseA(e.target.value)}>
                  {choices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </Field>
              <IconButton label="Swap them" className="compare-swap" onClick={() => show(b!.id, a!.id)}><ArrowLeftRight size={18} /></IconButton>
              <Field label="Checking">
                <select value={b!.id} onChange={(e) => chooseB(e.target.value)}>
                  {choices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </Field>
            </div>
          )}
          <Segmented label="How many nights" value={days} onChange={(n) => show(askedA, askedB, n)}
            options={COMPARE_SPANS.map((n) => ({ value: n, label: `${n} nights` }))} />
        </div>
      )}
      {load.error && <ErrorNotice message={load.error} onRetry={load.reload} />}
      {load.data === "gone" && (
        <div className="notice notice-row" role="status">
          <span>One of those devices isn't here any more.</span>
          <Button variant="ghost" onClick={() => show(undefined, undefined)}>Choose for me</Button>
        </div>
      )}
      {!load.data && load.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {empty && (
        <Card>
          <Empty icon={<GitCompareArrows />} title="Two devices to compare">
            {data.devices.length === 1
              ? `Only ${data.devices[0].name} has readings in the last ${days} nights. Wear another device to bed alongside it, and once both have synced, each night shows here side by side.`
              : `Nothing has synced in the last ${days} nights. Once two devices have, each night shows here side by side.`}
          </Empty>
        </Card>
      )}
      {quiet && (
        <Card>
          <Empty icon={<Moon />} title="Nothing to compare yet">
            {`Neither ${a!.name} nor ${b!.name} recorded anything in the last ${days} nights. Wear both to bed, and they show here once they've synced.`}
          </Empty>
        </Card>
      )}
      {ready && !quiet && <div className={load.loading ? "is-refreshing" : undefined} aria-busy={load.loading}>{arranged.render()}</div>}
    </div>
  );
}

/** Which colour is which device, and their names where the page calls them "ring" and "strap". */
function DeviceKey({ names, a, b }: { names: ShortNames; a: RecentDevice; b: RecentDevice }) {
  const label = (which: "a" | "b", d: RecentDevice) => (names.words ? `${title(names, which)}: ${d.name}` : d.name);
  return (
    <ul className="legend compare-key">
      <li><span className="legend-key" style={{ background: A_COLOR }} aria-hidden="true" /><span><strong>{label("a", a)}</strong>, compared against</span></li>
      <li><span className="legend-key" style={{ background: B_COLOR }} aria-hidden="true" /><span><strong>{label("b", b)}</strong>, being checked</span></li>
    </ul>
  );
}

/** One thing night by night: both devices' values as two lines. */
function Trend({ data, names, fmt }: { data: DeviceComparison; names: ShortNames; fmt: CompareFormat }) {
  const keys = trendKeys(data.metrics);
  const [chosen, setChosen] = useState<CompareKey>(keys[0]);
  const key = keys.includes(chosen) ? chosen : keys[0];
  const points = trendPoints(data.nights, key, fmt);
  const noon = (date: string) => parseDate(date).getTime() + 12 * 3_600_000;
  const label = COMPARE_KEYS[key].label;
  return (
    <Card title="Night by night" icon={<Activity size={18} />} className="span-2">
      <div className="compare-trend-bar">
        <label className="compare-trend-pick">
          <span className="sr-only">What to show</span>
          <select value={key} onChange={(e) => setChosen(e.target.value as CompareKey)}>
            {keys.map((k) => <option key={k} value={k}>{COMPARE_KEYS[k].label}</option>)}
          </select>
        </label>
        <ul className="legend">
          <li><span className="legend-key" style={{ background: A_COLOR }} />{title(names, "a")}</li>
          <li><span className="legend-key" style={{ background: B_COLOR }} />{title(names, "b")}</li>
        </ul>
      </div>
      <LineChart
        points={points.map((p) => ({ x: noon(p.date), y: p.a }))}
        extra={[{ points: points.map((p) => ({ x: noon(p.date), y: p.b })), color: B_COLOR, name: title(names, "b") }]}
        name={title(names, "a")} color={A_COLOR} area={false} dots={points.length <= 31} height={200}
        formatX={(x) => shortDate(isoDate(new Date(x)))}
        formatY={(v) => drawnText(key, v, fmt)}
        formatTick={(v) => tickText(key, v, fmt)}
        label={`${label}, night by night: ${called(names, "a")} and ${called(names, "b")}.`}
      />
    </Card>
  );
}

/** "Tuesday 17 Nov": the morning a night ended. */
const nightTitle = (date: string) => parseDate(date).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" });

/** One night: both devices' stages on one time axis, then their numbers side by side. */
function NightCard({ night, names, fmt, zone }: { night: ComparedNight; names: ShortNames; fmt: CompareFormat; zone?: string }) {
  const span = nightSpan(night);
  const rows = nightRows(night, fmt);
  const time = (ms: number) => clockTime(ms, zone);
  const hours = span ? hourTicks(span.start, span.end, 1, zone).map((at) => ({ at, label: clockTime(at, zone, true) })) : [];
  const slept = (["a", "b"] as const).filter((w) => night[w]);
  const missing = (["a", "b"] as const).filter((w) => !night[w]);
  return (
    <Card title={nightTitle(night.date)} icon={<Moon size={18} />} className="compare-night">
      {span && slept.map((w) => (
        <DeviceSleep key={w} who={title(names, w)} session={night[w]!} start={span.start} end={span.end} hours={hours} time={time} />
      ))}
      {missing.length === 2 && <p className="muted small">Neither recorded sleep this night.</p>}
      {missing.length === 1 && <p className="muted small">{called(names, missing[0], true)} recorded no sleep this night.</p>}
      {rows.length > 0 ? (
        <div className="table-wrap">
          <table className="table compare-table">
            <thead>
              <tr><th scope="col"><span className="sr-only">Reading</span></th><th scope="col">{title(names, "a")}</th><th scope="col">{title(names, "b")}</th><th scope="col">Difference</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key}>
                  <th scope="row">{r.label}</th>
                  <td>{r.a}</td>
                  <td>{r.b}</td>
                  <td className="compare-difference">{r.difference}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted small">Nothing they both measured this night.</p>
      )}
    </Card>
  );
}

/** One device's night on the shared axis, or a line when it doesn't tell the stages apart. */
function DeviceSleep({ who, session, start, end, hours, time }: {
  who: string; session: SleepSession; start: number; end: number; hours: { at: number; label: string }[]; time: (ms: number) => string;
}) {
  return (
    <div className="compare-sleep">
      <div className="compare-sleep-head">
        <strong>{who}</strong>
        <span className="muted small">{time(session.start)} – {time(session.end)} · {duration(asleepMinutes(session))} asleep</span>
      </div>
      {session.stages.length > 0 ? (
        <Hypnogram stages={session.stages} start={start} end={end} height={108} time={time} hours={hours} label={`${who}: ${nightSummary(session, time, duration)}`} />
      ) : (
        <p className="muted small">It doesn't tell the stages apart.</p>
      )}
    </div>
  );
}
