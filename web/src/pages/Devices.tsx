import { useRef, useState } from "react";
import { Battery, Copy, FileUp, HeartPulse, KeyRound, MoreHorizontal, Pencil, Smartphone, Trash2, Watch } from "lucide-react";
import { api } from "../lib/api";
import { num, when } from "../lib/format";
import type { Device } from "../lib/types";
import { Button, Card, Empty, ErrorNotice, Field, Menu, Modal, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { SignIns } from "./Settings";

const KIND_LABEL: Record<string, string> = {
  COLMI_RING: "Colmi ring", PEBBLE: "Pebble", GARMIN: "Garmin", HEALTH_CONNECT: "Health Connect", GADGETBRIDGE: "Gadgetbridge",
  FIT_FILE: "FIT files", PHONE: "Phone", MANUAL: "Entered by hand", OTHER: "Other device",
};

export function Devices() {
  const { data, error, loading, reload, setData } = useLoad(() => api.devices(), []);
  const [renaming, setRenaming] = useState<Device | null>(null);
  const [forgetting, setForgetting] = useState<Device | null>(null);
  const [keys, setKeys] = useState(0);

  return (
    <div className="page">
      <PageHeader title="Devices" subtitle="Where your data comes from, and how to bring in more" />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <Card title="Your devices" icon={<Watch size={18} />}>
          {data.length === 0 ? (
            <Empty icon={<Watch size={36} />} title="Nothing connected yet">Devices appear here once they've sent data, from the phone app, an upload key or a FIT file.</Empty>
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
      )}

      <h2 className="section-title">Bring in data</h2>
      <div className="how-to">
        <Card title="Phone app (Android)" icon={<Smartphone size={18} />}>
          <p>Sign in to this server from the SuperAwesomeMagicalHealth app. It carries data in from your wearables in the background:</p>
          <p><strong>Colmi rings</strong> (R02–R12, Colmi Ring 2 and other QRing rings) directly over Bluetooth: heart rate, steps, calories and sleep stages.</p>
          <p><strong>Health Connect</strong>: anything that writes to it, like Garmin Connect, Fitbit, Samsung Health or Gadgetbridge.</p>
          <p><strong>Gadgetbridge</strong>'s database export, for the devices it supports.</p>
        </Card>
        <Card title="Pebble Time 2" icon={<Watch size={18} />}>
          <p>Pair it with <strong>Gadgetbridge</strong>, which supports Core Devices' new Pebbles, and turn on its Health Connect sync or database export; the phone app picks it up from there.</p>
          <p>A Pebble watch app that sends straight to this server with an upload key is planned.</p>
        </Card>
        <Card title="Garmin" icon={<HeartPulse size={18} />}>
          <p>Let Garmin Connect share with <strong>Health Connect</strong> on your phone, or pair the watch with Gadgetbridge.</p>
          <p>Or upload <strong>.FIT files</strong> below: workouts from the watch's <code>GARMIN/Activity</code> folder, or exported from Garmin Connect.</p>
        </Card>
      </div>

      <h2 className="section-title">Garmin and other .FIT files</h2>
      <FitImport onImported={reload} />

      <h2 className="section-title">Upload keys</h2>
      <Card title="For watch apps and scripts" icon={<KeyRound size={18} />}>
        <UploadKeys onMade={() => setKeys((k) => k + 1)} />
        <SignIns key={keys} only={["UPLOAD"]} />
      </Card>

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
    <Card>
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
