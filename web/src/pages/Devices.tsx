import { useRef, useState } from "react";
import { Battery, Copy, FileUp, HeartPulse, KeyRound, Layers, MoreHorizontal, Pencil, Smartphone, Trash2, Watch } from "lucide-react";
import { api } from "../lib/api";
import { num, shiftDate, today, when } from "../lib/format";
import type { DaySummary, Device, DeviceChoices, Metric, SleepSummary } from "../lib/types";
import { Button, Card, ErrorNotice, Field, Menu, Modal, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { SignIns } from "./Settings";
import { ACTIVITY_METRICS, HEART_METRICS, readingName } from "../components/Sources";
import { METRIC_LABELS } from "../lib/types";

const KIND_LABEL: Record<string, string> = {
  COLMI_RING: "Colmi ring", PEBBLE: "Pebble", GARMIN: "Garmin", HEALTH_CONNECT: "Health Connect", GADGETBRIDGE: "Gadgetbridge",
  FIT_FILE: "FIT files", PHONE: "Phone", MANUAL: "Entered by hand", OTHER: "Other device",
};

export function Devices() {
  const { data, error, loading, reload, setData } = useLoad(() => api.devices(), []);
  const [renaming, setRenaming] = useState<Device | null>(null);
  const [forgetting, setForgetting] = useState<Device | null>(null);
  const [keys, setKeys] = useState(0);

  const sections: Section[] = [
    { id: "devices", label: "Your devices", node: data && (
      <Card title="Your devices" icon={<Watch size={18} />} className="span-2">
        {data.length === 0 ? (
          <p className="muted">Nothing connected yet. Devices appear here once they've sent data: from the phone app, an upload key or a FIT file.</p>
        ) : (
          <ul className="rows">
            {data.map((d) => (
              <li key={d.id} className="row">
                <div className={`device-icon kind-${d.kind}`}>{d.kind === "PHONE" || d.kind === "HEALTH_CONNECT" ? <Smartphone size={20} /> : d.kind === "MANUAL" ? <Pencil size={20} /> : <Watch size={20} />}</div>
                <div className="row-main">
                  <div className="row-title">{d.name}</div>
                  <div className="row-sub">
                    {KIND_LABEL[d.kind]}{d.model ? ` · ${d.model}` : ""}{d.firmware ? ` · firmware ${d.firmware}` : ""} · {d.lastSyncAt ? `synced ${when(d.lastSyncAt)}` : `added ${when(d.createdAt)}`}
                  </div>
                </div>
                <div className="row-end">
                  {d.battery != null && <div><Battery size={14} style={{ verticalAlign: -2 }} /> {d.battery}%</div>}
                  <div className="muted">{num(d.samples)} readings</div>
                </div>
                <div className="row-actions">
                  <Menu label={`More for ${d.name}`} trigger={<MoreHorizontal size={18} />} items={[
                    { label: "Rename", icon: <Pencil size={18} />, onSelect: () => setRenaming(d) },
                    { label: "Forget, with its data", icon: <Trash2 size={18} />, danger: true, onSelect: () => setForgetting(d) },
                  ]} />
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    ) },
    { id: "overlap", label: "When devices overlap", node: data && <Overlaps devices={data} /> },
    { id: "bring", label: "Bring in data", node: (
      <section className="block span-2" aria-label="Bring in data">
        <h2 className="section-title">Bring in data</h2>
        <div className="how-to">
          <Card title="Phone app (Android)" icon={<Smartphone size={18} />}>
            <p>Sign in to this server from the SuperAwesomeMagicalHealth app. It carries data in from your wearables in the background:</p>
            <p><strong>Colmi rings</strong> (R02–R12, Colmi Ring 2 and other QRing rings) directly over Bluetooth: heart rate, steps, calories and sleep stages.</p>
            <p><strong>Health Connect</strong>: anything that writes to it, like Garmin Connect, Fitbit, Samsung Health or Gadgetbridge.</p>
            <p><strong>Gadgetbridge</strong>'s database export, for the devices it supports.</p>
          </Card>
          <Card title="Pebble" icon={<Watch size={18} />}>
            <p>Install the SuperAwesomeMagicalHealth watch app and connect it from its settings in the Pebble app: it shows today and logs with a tap.</p>
            <p>For health data, pair the watch with <strong>Gadgetbridge</strong> and turn on its Health Connect sync or database export; the phone app picks it up from there.</p>
          </Card>
          <Card title="Garmin" icon={<HeartPulse size={18} />}>
            <p>Let Garmin Connect share with <strong>Health Connect</strong> on your phone, or pair the watch with Gadgetbridge.</p>
            <p>Or upload <strong>.FIT files</strong>: workouts from the watch's <code>GARMIN/Activity</code> folder, or exported from Garmin Connect.</p>
          </Card>
        </div>
      </section>
    ) },
    { id: "fit", label: "FIT files", node: <FitImport onImported={reload} /> },
    { id: "keys", label: "Upload keys", node: (
      <Card title="Upload keys" icon={<KeyRound size={18} />} className="span-2">
        <UploadKeys onMade={() => setKeys((k) => k + 1)} />
        <SignIns key={keys} only={["UPLOAD"]} />
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("devices"), "Devices", sections);

  return (
    <div className="page">
      <PageHeader title="Devices" subtitle="Where your data comes from, and how to bring in more" actions={arranged.button} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {arranged.render()}

      {renaming && <Rename device={renaming} onClose={() => setRenaming(null)} onSaved={(d) => { setData((data ?? []).map((x) => (x.id === d.id ? d : x))); setRenaming(null); }} />}
      {forgetting && (
        <Modal title={`Forget ${forgetting.name}?`} onClose={() => setForgetting(null)} footer={<>
          <Button variant="ghost" onClick={() => setForgetting(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try { await api.deleteDevice(forgetting.id); setData((data ?? []).filter((x) => x.id !== forgetting.id)); setForgetting(null); toast("Forgotten"); } catch (e) { errorToast(e); }
          }}>Forget it and its data</Button>
        </>}>
          <p>Everything it recorded goes too: {num(forgetting.samples)} readings, and its nights and workouts. If it sends data again, it comes back as new.</p>
        </Modal>
      )}
    </div>
  );
}

/** The readings, in the order the section lists them. */
const OVERLAP_ORDER: Metric[] = [...ACTIVITY_METRICS, ...HEART_METRICS, "STRESS"];
/** A reading's place in that order (any others last). */
const rank = (m: Metric) => { const i = OVERLAP_ORDER.indexOf(m); return i < 0 ? OVERLAP_ORDER.length : i; };

/**
 * When devices overlap: each reading more than one device has measured lately (the last three days,
 * and the last two weeks of nights), with whether the app combines them or uses one device.
 */
function Overlaps({ devices }: { devices: Device[] }) {
  const recent = useLoad(async () => {
    const days = await Promise.all([0, 1, 2].map((n) => api.day(shiftDate(today(), -n)).catch(() => null)));
    const nights = await api.sleep(14).catch(() => [] as SleepSummary[]);
    return { days: days.filter((d): d is DaySummary => d != null), nights };
  }, []);
  const choices = useLoad(() => api.deviceChoices().catch(() => ({ choices: [] }) as DeviceChoices), []);
  const [saving, setSaving] = useState<string | null>(null);
  const names = new Map(devices.map((d) => [d.id, d.name]));
  const byMetric = new Map<Metric, Map<string, string>>();
  const add = (m: Metric, id: string, name?: string) => {
    if (!byMetric.has(m)) byMetric.set(m, new Map());
    byMetric.get(m)!.set(id, names.get(id) ?? name ?? "A device");
  };
  for (const day of recent.data?.days ?? []) for (const c of day.combined ?? []) if (c.sources.length > 1) c.sources.forEach((s) => add(c.metric, s.deviceId, s.device));
  for (const c of choices.data?.choices ?? []) if (c.deviceId) add(c.metric, c.deviceId);
  const sleepDevices = new Map<string, string>();
  const nights = [...(recent.data?.nights ?? []), ...(recent.data?.days ?? []).map((d) => d.sleepSummary).filter((n): n is SleepSummary => !!n)];
  for (const n of nights) if (n.combined && n.combined.sources.length > 1) n.combined.sources.forEach((s) => sleepDevices.set(s.deviceId, names.get(s.deviceId) ?? s.device));
  const sleepChoice = choices.data?.sleepDeviceId;
  if (sleepChoice) sleepDevices.set(sleepChoice, names.get(sleepChoice) ?? "A device");
  const metrics = [...byMetric.keys()].filter((m) => byMetric.get(m)!.size > 1 || choices.data?.choices.some((c) => c.metric === m && c.deviceId))
    .sort((a, b) => rank(a) - rank(b));

  const save = async (key: string, next: DeviceChoices, said: string) => {
    setSaving(key);
    try {
      choices.setData(await api.saveDeviceChoices(next) ?? next);
      toast(said);
    } catch (e) { errorToast(e); }
    setSaving(null);
  };
  const current = choices.data ?? { choices: [] };
  const choose = (m: Metric, deviceId: string) => save(m, { ...current, choices: [...current.choices.filter((c) => c.metric !== m), ...(deviceId ? [{ metric: m, deviceId }] : [])] },
    deviceId ? `Using only ${byMetric.get(m)?.get(deviceId)} for ${readingName(m)}` : `Combining your devices for ${readingName(m)}`);
  const chooseSleep = (deviceId: string) => save("sleep", { ...current, sleepDeviceId: deviceId || undefined },
    deviceId ? `Using only ${sleepDevices.get(deviceId)} for sleep` : "Combining your devices for sleep");

  const loading = (recent.loading && !recent.data) || (choices.loading && !choices.data);
  const rows = [
    ...metrics.map((m) => ({ key: m as string, label: METRIC_LABELS[m], reading: readingName(m), devices: byMetric.get(m)!, value: current.choices.find((c) => c.metric === m)?.deviceId ?? "", onChange: (id: string) => void choose(m, id) })),
    ...(sleepDevices.size > 1 || sleepChoice ? [{ key: "sleep", label: "Sleep", reading: "sleep", devices: sleepDevices, value: sleepChoice ?? "", onChange: (id: string) => void chooseSleep(id) }] : []),
  ];
  return (
    <Card title="When devices overlap" icon={<Layers size={18} />} className="span-2">
      <p className="muted">When a ring and a watch both measure something, the app works out its own number from both: the same walk isn't counted twice, and the device that suits a reading best counts for more. Or choose one device for a reading.</p>
      {loading ? <Spinner /> : rows.length === 0 ? (
        <p className="muted small">Nothing overlaps at the moment: each reading comes from one device.</p>
      ) : (
        <ul className="rows overlap-list">
          {rows.map((r) => (
            <li key={r.key} className="row overlap-row">
              <div className="row-main">
                <div className="row-title">{r.label}</div>
                <div className="row-sub wraps">{[...r.devices.values()].join(" and ")}</div>
              </div>
              <label className="overlap-choice">
                <span className="sr-only">Which device for {r.reading}</span>
                <select value={r.value} disabled={saving === r.key} onChange={(e) => r.onChange(e.target.value)}>
                  <option value="">Combine them</option>
                  {[...r.devices.entries()].map(([id, name]) => <option key={id} value={id}>Only {name}</option>)}
                </select>
              </label>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function FitImport({ onImported }: { onImported: () => void }) {
  const [over, setOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const upload = async (files: File[]) => {
    if (!files.length) return;
    setBusy(true);
    setResult(null);
    try {
      const r = await api.importFit(files);
      setResult(r.message);
      onImported();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <Card title="Garmin and other .FIT files" icon={<FileUp size={18} />} className="span-2">
      <div
        className={`dropzone ${over ? "is-over" : ""}`}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); void upload([...e.dataTransfer.files]); }}
      >
        <FileUp size={32} />
        <div>Drop .FIT files here: workouts, all-day monitoring or sleep</div>
        <input ref={input} type="file" accept=".fit,.FIT" multiple hidden onChange={(e) => void upload([...(e.target.files ?? [])])} />
        <Button busy={busy} onClick={() => input.current?.click()}>Choose files</Button>
      </div>
      {result && <div className="notice notice-ok">{result}</div>}
    </Card>
  );
}

function UploadKeys({ onMade }: { onMade: () => void }) {
  const [name, setName] = useState("");
  const [made, setMade] = useState<{ token: string; url: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const example = made && `curl -X POST ${made.url} \\
  -H "Authorization: Bearer ${made.token}" -H "Content-Type: application/json" \\
  -d '{"device":{"kind":"OTHER","identifier":"my-script","name":"My script"},
       "samples":[{"metric":"HEART_RATE","start":${Date.now()},"value":62}]}'`;
  return (
    <div className="form-stack">
      <p className="muted">
        A key that can only send data in (a watch app, a smart scale's script, a home-automation hook). It can't read anything.
        The format is in docs/API.md: <code>POST /api/ingest</code> with a device and its readings, nights or workouts.
      </p>
      {made ? (
        <>
          <div className="copy-row"><code className="secret">{made.token}</code><Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(made.token); toast("Copied"); }}><Copy size={16} /> Copy</Button></div>
          <p className="muted small">Shown only now: keep it private. For example:</p>
          <pre className="copy-row secret" style={{ whiteSpace: "pre-wrap", margin: 0 }}>{example}</pre>
          <div><Button variant="ghost" onClick={() => setMade(null)}>Done</Button></div>
        </>
      ) : (
        <form className="inline-form" onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          try { setMade(await api.uploadKey(name.trim() || "Upload key")); setName(""); onMade(); } catch (err) { errorToast(err); }
          setBusy(false);
        }}>
          <Field label="What it's for"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Pebble watch app" /></Field>
          <Button busy={busy} type="submit"><KeyRound size={18} /> Make a key</Button>
        </form>
      )}
    </div>
  );
}

function Rename({ device, onClose, onSaved }: { device: Device; onClose: () => void; onSaved: (d: Device) => void }) {
  const [name, setName] = useState(device.name);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { onSaved(await api.renameDevice(device.id, name)); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Rename" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Name"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></Field>
      </form>
    </Modal>
  );
}
