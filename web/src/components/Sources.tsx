import { useState } from "react";
import { Layers, Pencil, Smartphone, Watch } from "lucide-react";
import { api } from "../lib/api";
import { clock, distance, hm, num, useUnits } from "../lib/format";
import { useReadOnly, useViewing } from "../lib/viewing";
import { METRIC_LABELS, type CombinedSleep, type CombinedValue, type DeviceChoices, type DeviceKind, type Metric, type Units } from "../lib/types";
import { Meter } from "./charts";
import { Button, Modal, errorToast, toast, useLoad } from "./ui";

/*
 * Where a number came from, when more than one device measured it (Devices.kt): a small "From 2
 * devices" link that opens each device's value and share, how the app worked out its own, and a
 * way to use just one device for that reading instead (PUT /api/devices/choices). While viewing
 * someone's share it only explains: the choice is theirs, and /api/devices/choices is never asked.
 */

/** The readings each Today card shows. */
export const HEART_METRICS: Metric[] = ["RESTING_HEART_RATE", "HEART_RATE", "HRV", "SPO2", "RESPIRATORY_RATE", "SKIN_TEMPERATURE", "TEMPERATURE_DEVIATION"];
export const ACTIVITY_METRICS: Metric[] = ["STEPS", "DISTANCE", "ACTIVE_CALORIES", "FLOORS"];
export const NIGHT_METRICS: Metric[] = ["HRV", "SPO2", "RESPIRATORY_RATE", "SKIN_TEMPERATURE", "TEMPERATURE_DEVIATION"];

/** The combined values for [metrics], in that order. */
export const combinedFor = (all: CombinedValue[] | undefined, metrics: Metric[]) =>
  metrics.map((m) => (all ?? []).find((c) => c.metric === m)).filter((c): c is CombinedValue => !!c && c.sources.length > 1);

/** "resting heart rate", for the middle of a sentence. */
export const readingName = (m: Metric) => {
  const label = METRIC_LABELS[m] ?? m;
  return label.charAt(0).toLowerCase() + label.slice(1);
};

const oneDecimal = (v: number) => v.toLocaleString(undefined, { maximumFractionDigits: 1 });

/** A reading's value with its unit. */
export function metricText(metric: Metric, v: number, units: Units): string {
  switch (metric) {
    case "STEPS": return `${num(v)} steps`;
    case "DISTANCE": return distance(v, units);
    case "ACTIVE_CALORIES": return `${num(v)} kcal`;
    case "FLOORS": return `${num(v)} floors`;
    case "HEART_RATE": case "RESTING_HEART_RATE": return `${num(v)} bpm`;
    case "HRV": return `${num(v)} ms`;
    case "SPO2": return `${oneDecimal(v)}%`;
    case "RESPIRATORY_RATE": return `${oneDecimal(v)} a minute`;
    case "SKIN_TEMPERATURE": return `${v.toFixed(1)} °C`;
    case "TEMPERATURE_DEVIATION": return `${v >= 0 ? "+" : "−"}${Math.abs(v).toFixed(1)} °C`;
    default: return oneDecimal(v);
  }
}

function deviceCount(values: CombinedValue[], sleep?: CombinedSleep): number {
  const ids = new Set<string>();
  values.forEach((v) => v.sources.forEach((s) => ids.add(s.deviceId)));
  sleep?.sources.forEach((s) => ids.add(s.deviceId));
  return ids.size;
}

/** The small link under a card's numbers; nothing when one device measured everything. */
export function SourcesLink({ values = [], sleep, onChanged, prefix }: { values?: CombinedValue[]; sleep?: CombinedSleep; onChanged: () => void; prefix?: string }) {
  const [open, setOpen] = useState(false);
  const shown = values.filter((v) => v.sources.length > 1);
  const count = deviceCount(shown, sleep && sleep.sources.length > 1 ? sleep : undefined);
  if (count < 2) return null;
  const chosen = shown.length > 0 && shown.every((v) => v.chosenDeviceId) && !sleep ? shown[0].sources.find((s) => s.deviceId === shown[0].chosenDeviceId)?.device : undefined;
  return (
    <>
      <button type="button" className="link-btn sources-link" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        <Layers size={14} aria-hidden="true" />{prefix ? `${prefix} ` : ""}{chosen ? `From ${chosen}, your choice` : `From ${count} devices`}
      </button>
      {open && <SourcesPanel values={shown} sleep={sleep && sleep.sources.length > 1 ? sleep : undefined} onClose={() => setOpen(false)} onChanged={onChanged} />}
    </>
  );
}

const icon = (kind?: DeviceKind) => (kind === "PHONE" || kind === "HEALTH_CONNECT" ? <Smartphone size={18} /> : kind === "MANUAL" ? <Pencil size={18} /> : <Watch size={18} />);

export function SourcesPanel({ values, sleep, onClose, onChanged }: { values: CombinedValue[]; sleep?: CombinedSleep; onClose: () => void; onChanged: () => void }) {
  const readOnly = useReadOnly();
  const owner = useViewing((s) => s.share?.ownerName);
  const units = useUnits();
  const [busy, setBusy] = useState(false);
  // Only the person's own choices, and only for sleep (a reading's choice comes with the reading).
  const choices = useLoad(() => (readOnly || !sleep ? Promise.resolve(null) : api.deviceChoices().catch(() => null)), [readOnly, !!sleep]);

  const change = async (patch: (c: DeviceChoices) => DeviceChoices, said: string) => {
    if (readOnly) return;
    setBusy(true);
    try {
      const current = await api.deviceChoices().catch(() => ({ choices: [] }) as DeviceChoices);
      await api.saveDeviceChoices(patch({ choices: current.choices ?? [], sleepDeviceId: current.sleepDeviceId }));
      toast(said);
      onChanged();
      onClose();
    } catch (e) {
      errorToast(e);
      setBusy(false);
    }
  };
  const chooseFor = (metric: Metric, deviceId: string | null, device?: string) => change(
    (c) => ({ ...c, choices: [...c.choices.filter((x) => x.metric !== metric), ...(deviceId ? [{ metric, deviceId }] : [])] }),
    deviceId ? `Using only ${device} for ${readingName(metric)}` : `Combining your devices for ${readingName(metric)}`,
  );
  const chooseForSleep = (deviceId: string | null, device?: string) => change(
    (c) => ({ ...c, sleepDeviceId: deviceId ?? undefined }),
    deviceId ? `Using only ${device} for sleep` : "Combining your devices for sleep",
  );
  const title = values.length === 1 && !sleep ? `${METRIC_LABELS[values[0].metric]}: where it came from` : sleep && values.length === 0 ? "This night: where it came from" : "Where these numbers came from";
  const sleepChosen = choices.data?.sleepDeviceId;

  return (
    <Modal wide sheet title={title} onClose={onClose} subtitle="When more than one device measures something, the app works out its own number from them all.">
      {readOnly && <p className="muted small">{owner ? `Only ${owner} can choose` : "Only they can choose"} which device to use.</p>}
      {values.map((v) => {
        const reading = readingName(v.metric);
        return (
          <section key={v.metric} className="sources" aria-label={METRIC_LABELS[v.metric]}>
            <div className="sources-head">
              <h3>{METRIC_LABELS[v.metric]}</h3>
              <strong>{metricText(v.metric, v.value, units)}</strong>
            </div>
            <p className="sources-how">{v.how}</p>
            <ul className="rows">
              {v.sources.map((s) => {
                const share = Math.round(s.weight * 100);
                return (
                  <li key={s.deviceId} className={`row source-row ${s.weight === 0 ? "is-off" : ""}`}>
                    <div className={`device-icon kind-${s.kind ?? "OTHER"}`}>{icon(s.kind)}</div>
                    <div className="row-main">
                      <div className="row-title">{s.device}{v.chosenDeviceId === s.deviceId && <span className="badge badge-accent">Your choice</span>}</div>
                      <div className="row-sub wraps">{metricText(v.metric, s.value, units)}{s.readings ? ` · ${num(s.readings)} ${s.readings === 1 ? "reading" : "readings"}` : ""}{s.note ? ` · ${s.note}` : ""}</div>
                      <Meter value={share} max={100} color="var(--c1)" label={`${s.device}'s share of ${reading}: ${share}%`} />
                    </div>
                    <div className="row-end"><strong>{share}%</strong><div className="muted">share</div></div>
                  </li>
                );
              })}
            </ul>
            {!readOnly && (
              <div className="button-row sources-choices">
                {v.chosenDeviceId && <Button variant="tonal" className="btn-small" busy={busy} onClick={() => void chooseFor(v.metric, null)}>Combine them</Button>}
                {v.sources.filter((s) => s.deviceId !== v.chosenDeviceId).map((s) => (
                  <Button key={s.deviceId} variant="ghost" className="btn-small" busy={busy} onClick={() => void chooseFor(v.metric, s.deviceId, s.device)}>Use only {s.device} for {reading}</Button>
                ))}
              </div>
            )}
          </section>
        );
      })}
      {sleep && (
        <section className="sources" aria-label="Sleep">
          <div className="sources-head"><h3>Sleep</h3></div>
          <p className="sources-how">{sleep.how}</p>
          <ul className="rows">
            {sleep.sources.map((s) => (
              <li key={s.deviceId} className="row source-row">
                <div className="device-icon kind-OTHER">{icon()}</div>
                <div className="row-main">
                  <div className="row-title">{s.device}{sleepChosen === s.deviceId && <span className="badge badge-accent">Your choice</span>}{sleep.stagesFrom && (sleep.stagesFrom === s.deviceId || sleep.stagesFrom === s.device) && <span className="badge">Stages from this one</span>}</div>
                  <div className="row-sub wraps">{clock(s.start)} – {clock(s.end)} · {hm(s.asleepMinutes)} asleep{s.staged ? " · tells the stages apart" : ""}</div>
                </div>
              </li>
            ))}
          </ul>
          {!readOnly && (
            <div className="button-row sources-choices">
              {sleepChosen && <Button variant="tonal" className="btn-small" busy={busy} onClick={() => void chooseForSleep(null)}>Combine them</Button>}
              {sleep.sources.filter((s) => s.deviceId !== sleepChosen).map((s) => (
                <Button key={s.deviceId} variant="ghost" className="btn-small" busy={busy} onClick={() => void chooseForSleep(s.deviceId, s.device)}>Use only {s.device} for sleep</Button>
              ))}
            </div>
          )}
        </section>
      )}
      {!readOnly && <p className="muted small">You can change this any time, here or under Devices › When devices overlap.</p>}
    </Modal>
  );
}
