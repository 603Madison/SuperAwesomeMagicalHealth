import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Bell, Copy, Eye, Home, Lock, LogOut, Mail, Pencil, Plus, Send, Trash2, UserPlus, Webhook as WebhookIcon } from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import { disablePush, enablePush, pushEnabledHere, pushPermission, pushSupported, thisBrowserPushId } from "../lib/push";
import { session, useSession } from "../lib/session";
import { startViewing } from "../lib/viewing";
import { when } from "../lib/format";
import { SENSITIVE, type DigestPrefs, type IntegrationKey, type Module, type ReminderPrefs, type Share, type Webhook, type WebhookEvent } from "../lib/types";
import { FEATURES, featureName } from "../components/features";
import { Button, Field, IconButton, Menu, Modal, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { WEEKDAYS } from "./Digest";

const DEFAULT_REMINDERS: ReminderPrefs = {
  remindAgainMinutes: 30, remindAgainTimes: 2, missedAfterMinutes: 120, refillDays: 7, refillTime: "10:00",
  showNames: false, quietFrom: "22:00", quietTo: "07:00", habits: true, appointments: true,
};
const DEFAULT_DIGEST: DigestPrefs = { enabled: true, day: 7, hour: 18, emailEnabled: false, emailModules: [] };

/** Saves one change to the profile, keeping the rest. */
async function saveProfile(change: (p: NonNullable<ReturnType<typeof useSession.getState>["profile"]>) => object) {
  const profile = useSession.getState().profile;
  if (!profile) return;
  session.setProfile(await api.saveProfile({ ...profile, ...change(profile) }));
}

// ---------------------------------------------------------------- Reminders and notifications

export function NotificationSettings() {
  const profile = useSession((s) => s.profile);
  const [p, setP] = useState<ReminderPrefs>(profile?.reminders ?? DEFAULT_REMINDERS);
  const [busy, setBusy] = useState(false);
  const on = useModules();
  useEffect(() => { if (profile?.reminders) setP(profile.reminders); }, [profile?.reminders]);
  const save = async () => {
    setBusy(true);
    try { await saveProfile(() => ({ reminders: p })); toast("Saved"); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const quiet = p.quietFrom != null && p.quietTo != null;
  return (
    <div className="form-stack">
      <BrowserPush />
      <h3>How reminders behave</h3>
      <p className="muted small">The same on your phone, in your browser, on your watch and for webhooks.</p>
      {on.includes("MEDICATIONS") && (
        <>
          <div className="two-col">
            <Field label="If a dose isn't marked, remind again after">
              <select value={p.remindAgainMinutes} onChange={(e) => setP({ ...p, remindAgainMinutes: Number(e.target.value) })}>
                <option value={0}>Don't remind again</option>
                {[10, 15, 30, 60].map((m) => <option key={m} value={m}>{m} minutes</option>)}
              </select>
            </Field>
            <Field label="At most">
              <select value={p.remindAgainTimes} disabled={p.remindAgainMinutes === 0} onChange={(e) => setP({ ...p, remindAgainTimes: Number(e.target.value) })}>
                {[1, 2, 3].map((n) => <option key={n} value={n}>{n === 1 ? "Once more" : `${n} more times`}</option>)}
              </select>
            </Field>
          </div>
          <div className="two-col">
            <Field label="Count a dose as missed after" help="For the “missed” webhook">
              <select value={p.missedAfterMinutes} onChange={(e) => setP({ ...p, missedAfterMinutes: Number(e.target.value) })}>
                {[60, 120, 240, 480].map((m) => <option key={m} value={m}>{m / 60} {m === 60 ? "hour" : "hours"}</option>)}
              </select>
            </Field>
            <Field label="Remind me to refill" help="When doses left would last this long (for medications you count)">
              <div className="pair">
                <select aria-label="Days left" value={p.refillDays} onChange={(e) => setP({ ...p, refillDays: Number(e.target.value) })}>
                  <option value={0}>Never</option>
                  {[3, 5, 7, 10, 14].map((d) => <option key={d} value={d}>{d} days left</option>)}
                </select>
                <input aria-label="At" type="time" value={p.refillTime} disabled={p.refillDays === 0} onChange={(e) => setP({ ...p, refillTime: e.target.value || "10:00" })} />
              </div>
            </Field>
          </div>
          <Toggle label="Show medication names on a locked screen" help={p.showNames ? "e.g. “Time for Estradiol 2 mg”." : "Off: “Time for your 8:00 medications”, so nobody nearby sees what you take."} checked={p.showNames} onChange={(showNames) => setP({ ...p, showNames })} />
        </>
      )}
      {on.includes("HABITS") && <Toggle label="Habit nudges" help="At a habit's nudge time, if it isn't done yet." checked={p.habits} onChange={(habits) => setP({ ...p, habits })} />}
      {on.includes("APPOINTMENTS") && <Toggle label="Appointment reminders" help="At the times you choose for each appointment." checked={p.appointments} onChange={(appointments) => setP({ ...p, appointments })} />}
      <Toggle label="Quiet hours" help="Nudges other than doses wait until morning." checked={quiet} onChange={(v) => setP({ ...p, quietFrom: v ? "22:00" : null, quietTo: v ? "07:00" : null })} />
      {quiet && (
        <div className="two-col">
          <Field label="From"><input type="time" value={p.quietFrom ?? ""} onChange={(e) => setP({ ...p, quietFrom: e.target.value || "22:00" })} /></Field>
          <Field label="To"><input type="time" value={p.quietTo ?? ""} onChange={(e) => setP({ ...p, quietTo: e.target.value || "07:00" })} /></Field>
        </div>
      )}
      <div><Button variant="primary" busy={busy} onClick={save}>Save</Button></div>
    </div>
  );
}

function BrowserPush() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const browsers = useLoad(() => api.pushSubscriptions().catch(() => []), []);
  const here = thisBrowserPushId();
  useEffect(() => { void pushEnabledHere().then(setEnabled); }, []);
  const supported = pushSupported();
  const blocked = pushPermission() === "denied";
  const toggle = async (on: boolean) => {
    setBusy(true);
    try {
      if (on) { await enablePush(); toast("Reminders on in this browser"); } else { await disablePush(); toast("Reminders off in this browser"); }
      setEnabled(on);
      browsers.reload();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <div className="form-stack" style={{ gap: 10 }}>
      <Toggle
        label="Remind me in this browser" disabled={!supported || busy || enabled == null}
        help={!supported ? "This browser can't show reminders when the page is closed. On iPhone, add this site to your Home Screen first." : blocked ? "Notifications are blocked for this site: allow them in the browser's site settings." : "Doses, refills, appointments and habit nudges, even when this tab is closed."}
        checked={!!enabled} onChange={(v) => void toggle(v)}
      />
      {enabled && <div><Button variant="ghost" onClick={async () => { try { await api.testPush(); toast("Sent: it should arrive in a moment"); } catch (e) { errorToast(e); } }}><Send size={16} /> Send a test</Button></div>}
      {browsers.data && browsers.data.length > 0 && (
        <ul className="device-list">
          {browsers.data.map((b) => (
            <li key={b.id} className="device-row">
              <Bell size={20} />
              <div className="source-text">
                <div className="source-name">{b.name || "A browser"}{b.id === here && <span className="badge">This browser</span>}</div>
                <div className="source-sub">Added {when(b.createdAt)}{b.lastOkAt ? ` · last reminder ${when(b.lastOkAt)}` : ""}</div>
              </div>
              <IconButton label="Stop reminders there" onClick={async () => {
                try {
                  if (b.id === here) { await disablePush(); setEnabled(false); } else await api.deletePushSubscription(b.id);
                  browsers.reload();
                } catch (e) { errorToast(e); }
              }}><Trash2 size={18} /></IconButton>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Weekly digest

export function DigestSettings() {
  const profile = useSession((s) => s.profile);
  const [d, setD] = useState<DigestPrefs>(profile?.digest ?? DEFAULT_DIGEST);
  const [busy, setBusy] = useState(false);
  const on = useModules();
  const navigate = useNavigate();
  const mail = useLoad(() => api.smtpStatus().catch(() => ({ configured: true })), []);
  useEffect(() => { if (profile?.digest) setD(profile.digest); }, [profile?.digest]);
  const sensitive = FEATURES.filter((f) => SENSITIVE.includes(f.id) && on.includes(f.id));
  const save = async () => {
    setBusy(true);
    try { await saveProfile(() => ({ digest: { ...d, email: d.email?.trim() || null } })); toast("Saved"); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <div className="form-stack">
      <Toggle label="A weekly digest" help="Your week in health against the week before: sleep, activity, and every feature you use." checked={d.enabled} onChange={(enabled) => setD({ ...d, enabled })} />
      {d.enabled && (
        <>
          <div className="two-col">
            <Field label="On">
              <select value={d.day} onChange={(e) => setD({ ...d, day: Number(e.target.value) })}>{WEEKDAYS.map((w, i) => <option key={w} value={i + 1}>{w}</option>)}</select>
            </Field>
            <Field label="At">
              <select value={d.hour} onChange={(e) => setD({ ...d, hour: Number(e.target.value) })}>
                {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{new Date(2000, 0, 1, h).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}</option>)}
              </select>
            </Field>
          </div>
          <Toggle label="Email it to me too" help="If your server's admin has set up mail. The app and notifications always show it." checked={d.emailEnabled} onChange={(emailEnabled) => setD({ ...d, emailEnabled })} />
          {d.emailEnabled && (
            <>
              {mail.data && !mail.data.configured && <p className="notice small">Your server can't send email yet: its admin sets that up. Until then the digest shows here and as a notification.</p>}
              <Field label="Email address"><input type="email" autoComplete="email" value={d.email ?? ""} onChange={(e) => setD({ ...d, email: e.target.value })} placeholder="you@example.com" /></Field>
              {sensitive.length > 0 && (
                <Field label="Private features in the email" help="Email is less private than the app: these stay out of it unless you choose.">
                  <div className="tag-list">
                    {sensitive.map((f) => {
                      const isOn = d.emailModules.includes(f.id);
                      return <button key={f.id} type="button" className={`chip ${isOn ? "is-on" : ""}`} aria-pressed={isOn} onClick={() => setD({ ...d, emailModules: isOn ? d.emailModules.filter((m) => m !== f.id) : [...d.emailModules, f.id] })}><Lock size={14} /> {f.name}</button>;
                    })}
                  </div>
                </Field>
              )}
            </>
          )}
        </>
      )}
      <div className="button-row">
        <Button variant="primary" busy={busy} onClick={save}>Save</Button>
        <Button variant="ghost" onClick={() => navigate("/digest")}>See the latest</Button>
        {d.enabled && d.emailEnabled && profile?.digest?.emailEnabled && <Button variant="ghost" onClick={async () => { try { await api.emailDigest(); toast("Sent"); } catch (e) { errorToast(e); } }}><Mail size={16} /> Email it now</Button>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Sharing

function FeaturePicker({ value, onChange }: { value: Module[]; onChange: (m: Module[]) => void }) {
  const on = useModules();
  const features = FEATURES.filter((f) => on.includes(f.id));
  return (
    <div className="tag-list" role="group" aria-label="Features to share">
      {features.map((f) => {
        const isOn = value.includes(f.id);
        const sensitive = SENSITIVE.includes(f.id);
        return (
          <button key={f.id} type="button" className={`chip ${isOn ? "is-on" : ""}`} aria-pressed={isOn} onClick={() => onChange(isOn ? value.filter((m) => m !== f.id) : [...value, f.id])}>
            {sensitive ? <Lock size={14} /> : <f.icon size={14} />} {f.name}
          </button>
        );
      })}
    </div>
  );
}

export function SharingSettings() {
  const { data, error, loading, reload } = useLoad(() => api.shares(), []);
  const [username, setUsername] = useState("");
  const [modules, setModules] = useState<Module[]>([]);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<Share | null>(null);
  const navigate = useNavigate();
  const sensitiveChosen = modules.filter((m) => SENSITIVE.includes(m));
  const share = async () => {
    if (!username.trim() || modules.length === 0) return;
    setBusy(true);
    try {
      const s = await api.share(username.trim(), modules);
      toast(`${s.viewerName} can now see what you chose`);
      setUsername("");
      setModules([]);
      reload();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <div className="form-stack">
      <p className="muted">Let someone you trust (a partner, a parent, a carer) see parts of your health. They need an account on this server. They can only look: nothing they do changes your data, and you can stop sharing at any time.</p>
      {error && <div className="notice notice-error">{error}</div>}
      {loading && !data && <Spinner />}
      {data && data.given.length > 0 && (
        <ul className="device-list">
          {data.given.map((s) => (
            <li key={s.id} className="device-row">
              <Eye size={20} />
              <div className="source-text">
                <div className="source-name">{s.viewerName}</div>
                <div className="source-sub">Sees {s.modules.map(featureName).join(", ")} · {s.lastViewedAt ? `last looked ${when(s.lastViewedAt)}` : "hasn't looked yet"}</div>
              </div>
              <Menu label={`Sharing with ${s.viewerName}`} trigger={<Pencil size={18} />} items={[
                { label: "Change what they see", icon: <Pencil size={18} />, onSelect: () => setEditing(s) },
                { label: "Stop sharing", icon: <LogOut size={18} />, danger: true, onSelect: async () => { try { await api.deleteShare(s.id); toast(`Stopped sharing with ${s.viewerName}`); reload(); } catch (e) { errorToast(e); } } },
              ]} />
            </li>
          ))}
        </ul>
      )}
      <h3>Share with someone</h3>
      <Field label="Their username"><input value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="none" autoComplete="off" placeholder="e.g. sam" /></Field>
      <Field label="What they can see" help="Choose each one. Private features (with a lock) are only shared if you pick them here.">
        <FeaturePicker value={modules} onChange={setModules} />
      </Field>
      {sensitiveChosen.length > 0 && <p className="notice small"><Lock size={13} style={{ verticalAlign: -2 }} /> They'll see your {sensitiveChosen.map((m) => featureName(m).toLowerCase()).join(", ")} entries, notes included.</p>}
      <div><Button busy={busy} disabled={!username.trim() || modules.length === 0} onClick={share}><UserPlus size={18} /> Share</Button></div>

      {data && data.received.length > 0 && (
        <>
          <h3>Shared with you</h3>
          <ul className="device-list">
            {data.received.map((s) => (
              <li key={s.id} className="device-row">
                <Eye size={20} />
                <div className="source-text">
                  <div className="source-name">{s.ownerName}</div>
                  <div className="source-sub">{s.modules.map(featureName).join(", ")}</div>
                </div>
                <Button onClick={() => { startViewing(s); navigate("/"); }}><Eye size={16} /> View</Button>
                <IconButton label={`Stop seeing ${s.ownerName}'s health`} onClick={async () => { try { await api.deleteShare(s.id); reload(); } catch (e) { errorToast(e); } }}><Trash2 size={18} /></IconButton>
              </li>
            ))}
          </ul>
        </>
      )}
      {editing && <ShareDialog share={editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

function ShareDialog({ share, onClose, onSaved }: { share: Share; onClose: () => void; onSaved: () => void }) {
  const [modules, setModules] = useState<Module[]>(share.modules);
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={`What ${share.viewerName} sees`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={modules.length === 0} onClick={async () => {
      setBusy(true);
      try { await api.changeShare(share.id, modules); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
    }}>Save</Button></>}>
      <FeaturePicker value={modules} onChange={setModules} />
    </Modal>
  );
}

// ---------------------------------------------------------------- Webhooks and Home Assistant

const EVENT_GROUPS: { title: string; module?: Module; events: { id: WebhookEvent; label: string }[] }[] = [
  { title: "Your day", events: [{ id: "MORNING", label: "Morning: sleep and readiness are ready" }, { id: "READINESS_LOW", label: "Readiness is low" }, { id: "DIGEST", label: "The weekly digest is ready" }] },
  { title: "Medications", module: "MEDICATIONS", events: [{ id: "DOSE_DUE", label: "A dose is due" }, { id: "DOSE_MISSED", label: "A dose was missed" }, { id: "DOSE_TAKEN", label: "A dose was taken" }, { id: "REFILL", label: "Time to refill" }] },
  { title: "Blood pressure & glucose", module: "VITALS", events: [{ id: "BP_HIGH", label: "Blood pressure is high" }, { id: "GLUCOSE_LOW", label: "Glucose is low" }, { id: "GLUCOSE_HIGH", label: "Glucose is high" }] },
  { title: "Symptoms", module: "SYMPTOMS", events: [{ id: "MIGRAINE", label: "A migraine started" }] },
  { title: "Habits", module: "HABITS", events: [{ id: "HABIT_DUE", label: "A habit isn't done yet" }] },
  { title: "Appointments", module: "APPOINTMENTS", events: [{ id: "APPOINTMENT_SOON", label: "An appointment is coming up" }] },
  { title: "Devices", events: [{ id: "DEVICE_BATTERY_LOW", label: "A device's battery is low" }, { id: "DEVICE_QUIET", label: "A device hasn't synced for a day" }] },
];

export const EVENT_LABEL = Object.fromEntries(EVENT_GROUPS.flatMap((g) => g.events.map((e) => [e.id, e.label]))) as Record<WebhookEvent, string>;

export function IntegrationSettings() {
  const hooks = useLoad(() => api.webhooks(), []);
  const deliveries = useLoad(() => api.webhookDeliveries().catch(() => []), []);
  const [editing, setEditing] = useState<Webhook | "new" | null>(null);
  const [secret, setSecret] = useState<{ name: string; secret: string } | null>(null);
  const reload = () => { hooks.reload(); deliveries.reload(); };
  return (
    <div className="form-stack">
      <h3><WebhookIcon size={16} style={{ verticalAlign: -2 }} /> Webhooks</h3>
      <p className="muted small">The server calls a web address when something happens: a dose is due, you woke up, a device's battery is low. Home Assistant's webhook trigger works as it is, and so do most automation tools.</p>
      {hooks.error && <div className="notice notice-error">{hooks.error}</div>}
      {hooks.data && hooks.data.length > 0 && (
        <ul className="device-list">
          {hooks.data.map((w) => (
            <li key={w.id} className={`device-row ${w.enabled ? "" : "is-off"}`}>
              <WebhookIcon size={20} />
              <div className="source-text">
                <div className="source-name">{w.name}{!w.enabled && <span className="badge">Off</span>}</div>
                <div className="source-sub">{hostOf(w.url)} · {w.events.length} {w.events.length === 1 ? "event" : "events"}{w.lastDelivery ? ` · ${w.lastDelivery.ok ? "last call worked" : "last call failed"} ${when(w.lastDelivery.at)}` : ""}</div>
              </div>
              <Menu label={`More for ${w.name}`} trigger={<Pencil size={18} />} items={[
                { label: "Edit", icon: <Pencil size={18} />, onSelect: () => setEditing(w) },
                { label: "Send a test", icon: <Send size={18} />, onSelect: async () => { try { await api.testWebhook(w.id!); toast("Test sent"); setTimeout(reload, 1500); } catch (e) { errorToast(e); } } },
                { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => { try { await api.deleteWebhook(w.id!); reload(); } catch (e) { errorToast(e); } } },
              ]} />
            </li>
          ))}
        </ul>
      )}
      <div><Button onClick={() => setEditing("new")}><Plus size={18} /> Add a webhook</Button></div>
      {secret && (
        <div className="notice">
          <p><strong>{secret.name}</strong>'s signing secret, shown only now:</p>
          <div className="copy-row"><code>{secret.secret}</code><Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(secret.secret); toast("Copied"); }}><Copy size={16} /> Copy</Button></div>
          <p className="small">Each call carries <code>X-SAMH-Signature: sha256=…</code>, the HMAC-SHA256 of the body with this secret, so the receiver can check it came from your server.</p>
        </div>
      )}
      {deliveries.data && deliveries.data.length > 0 && (
        <details className="deliveries">
          <summary>Recent calls</summary>
          <ul className="rows">
            {deliveries.data.slice(0, 12).map((d, i) => (
              <li key={i} className="row">
                <span className={`tone-chip tone-${d.ok ? "good" : "warn"}`}>{d.ok ? "OK" : "Failed"}</span>
                <div className="row-main"><div className="row-title">{EVENT_LABEL[d.event] ?? d.event}</div><div className="row-sub">{when(d.at)}{d.status ? ` · answered ${d.status}` : ""}{d.error ? ` · ${d.error}` : ""}</div></div>
              </li>
            ))}
          </ul>
        </details>
      )}

      <HomeAssistant />
      {editing && <WebhookDialog hook={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} onSaved={(w) => { reload(); if (w.secret) setSecret({ name: w.name, secret: w.secret }); }} />}
    </div>
  );
}

function hostOf(url: string): string {
  try { return new URL(url).host; } catch { return url; }
}

function WebhookDialog({ hook, onClose, onSaved }: { hook?: Webhook; onClose: () => void; onSaved: (w: Webhook) => void }) {
  const on = useModules();
  const [w, setW] = useState<Webhook>(hook ?? { name: "", url: "", events: ["DOSE_MISSED", "MORNING"], enabled: true, includeDetails: false });
  const [busy, setBusy] = useState(false);
  const groups = EVENT_GROUPS.filter((g) => !g.module || on.includes(g.module));
  const valid = w.name.trim() && /^https?:\/\/\S+$/i.test(w.url.trim()) && w.events.length > 0;
  const toggle = (id: WebhookEvent) => setW({ ...w, events: w.events.includes(id) ? w.events.filter((e) => e !== id) : [...w.events, id] });
  return (
    <Modal wide title={hook ? `Edit ${hook.name}` : "Add a webhook"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!valid} onClick={async () => {
      setBusy(true);
      try { const saved = await api.saveWebhook({ ...w, name: w.name.trim(), url: w.url.trim() }); toast("Saved"); onSaved(saved); onClose(); } catch (e) { errorToast(e); setBusy(false); }
    }}>Save</Button></>}>
      <div className="two-col">
        <Field label="Name"><input autoFocus value={w.name} onChange={(e) => setW({ ...w, name: e.target.value })} placeholder="e.g. Home Assistant" /></Field>
        <Field label="Address" help="e.g. http://homeassistant.local:8123/api/webhook/…"><input type="url" value={w.url} onChange={(e) => setW({ ...w, url: e.target.value })} placeholder="https://" /></Field>
      </div>
      <Field label="When to call it">
        <div className="event-groups">
          {groups.map((g) => (
            <fieldset key={g.title} className="event-group">
              <legend>{g.title}{g.module && SENSITIVE.includes(g.module) && <Lock size={12} />}</legend>
              {g.events.map((e) => (
                <label key={e.id} className="check-row"><input type="checkbox" checked={w.events.includes(e.id)} onChange={() => toggle(e.id)} /> {e.label}</label>
              ))}
            </fieldset>
          ))}
        </div>
      </Field>
      <Toggle label="Include names" help={w.includeDetails ? "Medication, symptom and appointment names go in each call." : "Off: calls say only what happened and when (“a dose was missed at 8:00”), not what."} checked={w.includeDetails} onChange={(includeDetails) => setW({ ...w, includeDetails })} />
      {hook && <Toggle label="On" checked={w.enabled} onChange={(enabled) => setW({ ...w, enabled })} />}
    </Modal>
  );
}

function HomeAssistant() {
  const keys = useLoad(() => api.integrationKeys().catch(() => [] as IntegrationKey[]), []);
  const [made, setMade] = useState<IntegrationKey | null>(null);
  const [yaml, setYaml] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const make = async () => {
    setBusy(true);
    try {
      const key = await api.addIntegrationKey("Home Assistant");
      setMade(key);
      setYaml(await yamlFor(key));
      keys.reload();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <>
      <h3><Home size={16} style={{ verticalAlign: -2 }} /> Home Assistant sensors</h3>
      <p className="muted small">A key that can read today's numbers (readiness, sleep, steps, the next dose…) for Home Assistant's REST sensors, and nothing else.</p>
      {keys.data && keys.data.length > 0 && (
        <ul className="device-list">
          {keys.data.map((k) => (
            <li key={k.id} className="device-row">
              <Home size={20} />
              <div className="source-text"><div className="source-name">{k.name}</div></div>
              <IconButton label="Remove this key" onClick={async () => { try { await api.deleteIntegrationKey(k.id); keys.reload(); } catch (e) { errorToast(e); } }}><Trash2 size={18} /></IconButton>
            </li>
          ))}
        </ul>
      )}
      {made && yaml ? (
        <div className="form-stack" style={{ gap: 8 }}>
          <p className="small">Add this to Home Assistant's <code>configuration.yaml</code>, then restart it. The key is in it, so keep it private; it's shown only now.</p>
          <pre className="code-block">{yaml}</pre>
          <div><Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(yaml); toast("Copied"); }}><Copy size={16} /> Copy</Button></div>
        </div>
      ) : <div><Button busy={busy} onClick={make}><Plus size={18} /> Make a Home Assistant key</Button></div>}
    </>
  );
}

/** Home Assistant REST sensors for each of the numbers the server gives (or a starting set). */
async function yamlFor(key: IntegrationKey): Promise<string> {
  const url = key.url || `${window.location.origin}/api/integrations/state`;
  let fields = ["readiness", "sleep_score", "activity_score", "steps", "next_dose"];
  try {
    const response = await fetch(url, { headers: { Authorization: `Bearer ${key.token}` } });
    if (response.ok) {
      const state = (await response.json()) as Record<string, unknown>;
      const flat = Object.entries(state).filter(([, v]) => v == null || ["number", "string", "boolean"].includes(typeof v)).map(([k]) => k);
      if (flat.length) fields = flat;
    }
  } catch {
    // The starting set it is.
  }
  const title = (k: string) => k.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
  return [
    "rest:",
    `  - resource: ${url}`,
    "    headers:",
    `      Authorization: Bearer ${key.token}`,
    "    scan_interval: 300",
    "    sensor:",
    ...fields.flatMap((f) => [`      - name: "Health ${title(f)}"`, `        value_template: "{{ value_json.${f} }}"`]),
  ].join("\n");
}
