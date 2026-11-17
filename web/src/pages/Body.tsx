import type { ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { ChevronDown, Watch } from "lucide-react";
import { api } from "../lib/api";
import { useAlertBadge } from "../lib/alertBadge";
import { alertsLine } from "../lib/alerts";
import { compareLine } from "../lib/compare";
import { shiftDate, useUnits } from "../lib/format";
import { BODY_SPANS, NAMES, NO_READINGS, spanOf, type BodySpan } from "../lib/nav";
import { useSession } from "../lib/session";
import { clockTime, zonedTime } from "../lib/today";
import type { RecentDevice } from "../lib/types";
import { useViewing } from "../lib/viewing";
import { useCompareOffered } from "../components/Navigation";
import { Menu, MenuGroup, MenuRow, PageHeader, useLoad } from "../components/ui";
import { BodyDay } from "./BodyDay";
import { BodyTrends } from "./BodyTrends";

/*
 * Body: what the devices measured. One switch: the last 24 hours as recorded (the night, heart
 * rate, the tiles, steps; one device or all of them), or a week, a month or a year of days (the
 * scores, sleep, heart, steps, weight and the rest, with the nights and workouts). At the bottom,
 * Health alerts and Compare devices. While viewing someone's share: their days only (the last 24
 * hours as recorded is only ever the person's own).
 *
 * The address keeps the span and device: /body?span=week, /body?device=ring1.
 */

export function Body() {
  const [params, setParams] = useSearchParams();
  const viewing = useViewing((s) => !!s.share);
  const span = spanOf(params.get("span"), viewing);
  const device = params.get("device") || undefined;
  const setSpan = (s: BodySpan) => setParams(s === "day" ? (device ? { device } : {}) : { span: s }, { replace: true });
  const choose = (id: string | null) => setParams(id ? { device: id } : {}, { replace: true });
  return span === "day"
    ? <BodyDay device={device} choose={choose} setSpan={setSpan} />
    : <BodyTrends span={span} setSpan={setSpan} />;
}

/** Around both views: the title, its actions (the device, Arrange), the span switch, and the links at the bottom. */
export function BodyFrame({ span, setSpan, actions, children }: { span: BodySpan; setSpan: (s: BodySpan) => void; actions?: ReactNode; children: ReactNode }) {
  const viewing = useViewing((s) => !!s.share);
  const spans = viewing ? BODY_SPANS.filter((s) => s.id !== "day") : BODY_SPANS;
  return (
    <div className="page body-page">
      <PageHeader title={NAMES.body} actions={actions} />
      <div className="span-switch" role="radiogroup" aria-label="How far back" style={{ gridTemplateColumns: `repeat(${spans.length}, minmax(0, 1fr))` }}>
        {spans.map((s) => (
          <button key={s.id} type="button" role="radio" aria-checked={s.id === span} className={s.id === span ? "is-on" : ""} onClick={() => setSpan(s.id)}>{s.label}</button>
        ))}
      </div>
      {children}
      {!viewing && <BodyLinks />}
    </div>
  );
}

/** "All devices ▾": the last 24 hours from one device, or from all of them. */
export function DeviceFilter({ devices, device, choose }: { devices: Pick<RecentDevice, "id" | "name">[]; device?: string; choose: (id: string | null) => void }) {
  const chosen = devices.find((d) => d.id === device);
  return (
    <Menu label="Which device" buttonClass="device-filter" trigger={<><Watch size={16} aria-hidden="true" /><span>{chosen?.name ?? "All devices"}</span><ChevronDown size={16} aria-hidden="true" /></>} items={[
      { label: "All devices", onSelect: () => choose(null) },
      ...devices.map((d) => ({ label: d.name, onSelect: () => choose(d.id) })),
    ]} />
  );
}

/** The person's devices that record readings, for the filter (the ones in the window come with the readings). */
export function useReadingDevices(): RecentDevice[] {
  const { data } = useLoad(() => api.devices().catch(() => []), []);
  return (data ?? []).filter((d) => !NO_READINGS.includes(d.kind)).map((d) => ({ id: d.id, name: d.name, kind: d.kind, lastSyncAt: d.lastSyncAt }));
}

/** Health alerts and Compare devices, each with one line. */
function BodyLinks() {
  const unseen = useAlertBadge();
  const alerts = useLoad(() => api.alerts(30).catch(() => null), []);
  const compare = useCompareOffered();
  const units = useUnits();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const pair = useLoad(() => (compare ? api.compareDevices(14).catch(() => null) : Promise.resolve(null)), [compare]);
  const line = pair.data ? compareLine(pair.data, { time: (ms) => clockTime(ms, zone), evening: (date) => zonedTime(shiftDate(date, -1), "18:00", zone), units }) : null;
  return (
    <MenuGroup>
      <MenuRow to="/alerts" title={NAMES.alerts} sub={alertsLine(unseen, alerts.data)} />
      {compare && <MenuRow to="/devices/compare" title={NAMES.compare} sub={line ?? "How far two devices read from each other, night by night"} />}
    </MenuGroup>
  );
}
