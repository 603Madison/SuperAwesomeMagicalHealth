import { useEffect, useState } from "react";
import { Bot, Copy, FileText, Home, Inbox, KeyRound, Lock, LogOut, Monitor, Shield, Smartphone, Watch } from "lucide-react";
import { useModules } from "../App";
import type { Module } from "../lib/types";
import { NavLink } from "react-router-dom";
import { api } from "../lib/api";
import { KG_PER_LB, toKg, when } from "../lib/format";
import { PALETTES, usePrefs } from "../lib/prefs";
import { session, useSession } from "../lib/session";
import { SENSITIVE, type Goals, type Profile as ProfileModel } from "../lib/types";
import { Button, Field, IconButton, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { FEATURES } from "../components/features";
import { PlacePicker } from "../components/PlacePicker";
import { DigestSettings, IntegrationSettings, NotificationSettings, SharingSettings } from "./SettingsExtra";
import { AssistantSettingsCard, useAiStatus } from "./Assistant";
import { FieldsEditor } from "../components/FieldKit";
import { Package } from "lucide-react";

/** The features phones show as tabs; the rest are links at the top of Settings. */
const TABS: Module[] = ["NUTRITION", "MIND"];

/** Features whose fields are chosen right here (bladder and bowel, and cycle, have their own pages). */
const RECORDED: { module: Module; name: string }[] = [
  { module: "MIND", name: "Mind" }, { module: "NEURO", name: "Autism & ADHD" }, { module: "TRANSITION", name: "Transition" }, { module: "BODY", name: "Body" },
];

/** The conditions and goals picked, with a way to change them. */
function TrackingSummary() {
  const mine = useLoad(() => api.conditions().catch(() => ({ conditions: [] })), []);
  const list = mine.data?.conditions ?? [];
  return (
    <>
      <p className="muted">Your conditions and goals decide which features and details the app shows.</p>
      {list.length > 0 && <div className="tag-list">{list.map((c) => <span key={c.id} className="chip is-static">{c.name}</span>)}</div>}
      <NavLink to="/conditions" className="btn btn-tonal">{list.length ? "Change" : "Choose"}</NavLink>
    </>
  );
}

export function Settings() {
  const ai = useAiStatus();
  const user = useSession((s) => s.user)!;
  const profile = useSession((s) => s.profile);
  const on = useModules();
  // A new connector link or upload key shows in the list of sign-ins at once.
  const [signIns, setSignIns] = useState(0);
  useEffect(() => {
    // Links like /settings#digest land on their section.
    const id = window.location.hash.slice(1);
    if (id) setTimeout(() => document.getElementById(id)?.scrollIntoView({ block: "start" }), 50);
  }, []);
  return (
    <div className="page settings">
      <PageHeader title="Settings" />
      {/* Phones show five tabs; the rest of the app is a tap away here. */}
      <nav className="phone-only more-links" aria-label="More">
        {FEATURES.filter((f) => on.includes(f.id) && f.route && !TABS.includes(f.id)).map((f) => (
          <NavLink key={f.id} to={f.route!} className="chip"><f.icon size={18} /> {f.name}</NavLink>
        ))}
        {(on.includes("CONTINENCE") || on.includes("CYCLE")) && <NavLink to="/supplies" className="chip"><Package size={18} /> Supplies</NavLink>}
        {ai?.available && <NavLink to="/assistant" className="chip"><Bot size={18} /> Assistant</NavLink>}
        <NavLink to="/reports" className="chip"><FileText size={18} /> Reports</NavLink>
        <NavLink to="/digest" className="chip"><Inbox size={18} /> Your week</NavLink>
        <NavLink to="/devices" className="chip"><Watch size={18} /> Devices</NavLink>
        {user.role === "ADMIN" && <NavLink to="/admin" className="chip"><Shield size={18} /> Admin</NavLink>}
      </nav>

      <section className="panel" id="tracking">
        <h2>What you're tracking</h2>
        <TrackingSummary />
      </section>

      <section className="panel" id="features">
        <h2>Features</h2>
        <p className="muted">Turn on the parts you want. The rest stay out of your way.</p>
        {profile ? <Features profile={profile} /> : <Spinner />}
      </section>

      {RECORDED.some((m) => on.includes(m.module)) && (
        <section className="panel" id="fields">
          <h2>What to record</h2>
          <p className="muted">What you're tracking decides what each form asks for. Show or hide any of it here; it's saved straight away.</p>
          {on.includes("CONTINENCE") && <p><NavLink to="/bladder/settings">Bladder &amp; bowel settings</NavLink>: quick buttons, your own words and fields, bladder training.</p>}
          {on.includes("CYCLE") && <p><NavLink to="/cycle/settings">Cycle settings</NavLink>: your own symptoms, lengths, reminders and plain wording.</p>}
          {RECORDED.filter((m) => on.includes(m.module)).map((m) => (
            <details key={m.module} className="field-group">
              <summary>{m.name}</summary>
              <FieldsEditor module={m.module} />
            </details>
          ))}
        </section>
      )}

      <AssistantSettingsCard />

      <section className="panel" id="profile">
        <h2>You and your goals</h2>
        <p className="muted">Your profile works out resting energy and calorie targets; goals shape your scores.</p>
        {profile ? <ProfileForm initial={profile} /> : <Spinner />}
        {profile && (on.includes("SYMPTOMS") || profile.place) && (
          <>
            <h3>Weather</h3>
            <PlacePicker />
          </>
        )}
      </section>

      <section className="panel" id="reminders">
        <h2>Reminders and notifications</h2>
        <NotificationSettings />
      </section>

      <section className="panel" id="digest">
        <h2>Weekly digest</h2>
        <DigestSettings />
      </section>

      <section className="panel" id="sharing">
        <h2>Sharing</h2>
        <SharingSettings />
      </section>

      <section className="panel">
        <h2>Appearance in this browser</h2>
        <AppearanceSettings />
      </section>

      <section className="panel" id="connector">
        <h2>AI connector (MCP)</h2>
        <Connector onMade={() => setSignIns((n) => n + 1)} />
      </section>

      <section className="panel" id="integrations">
        <h2>Webhooks and Home Assistant</h2>
        <IntegrationSettings />
      </section>

      <section className="panel" id="watch">
        <h2>Pebble watch</h2>
        <p className="muted">Install the SuperAwesomeMagicalHealth watch app, then open its settings in the Pebble app on your phone and sign in there. Your watch shows today's scores and doses, and logs a mood, water, coffee or a toilet trip with a tap. Connected watches are listed under "Signed in on".</p>
      </section>

      <section className="panel">
        <h2>Account</h2>
        <NameForm />
        {user.hasPassword && <PasswordChange />}
        {user.oidc && !user.hasPassword && <p className="muted">You sign in through single sign-on; your password is managed there.</p>}
      </section>

      <section className="panel">
        <h2>Signed in on</h2>
        <SignIns key={signIns} />
      </section>
    </div>
  );
}

function Features({ profile }: { profile: ProfileModel }) {
  const [busy, setBusy] = useState(false);
  const toggle = async (id: Module, enabled: boolean) => {
    setBusy(true);
    const modules = enabled ? [...profile.modules, id] : profile.modules.filter((m) => m !== id);
    try { session.setProfile(await api.saveProfile({ ...profile, modules })); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <div className="form-stack">
      {FEATURES.map((f) => (
        <Toggle key={f.id} disabled={busy} checked={profile.modules.includes(f.id)} onChange={(v) => void toggle(f.id, v)}
          label={f.name} help={<>{f.about}{SENSITIVE.includes(f.id) && <><br /><Lock size={11} style={{ verticalAlign: -1 }} /> Encrypted on the server; AI connectors only see it if you allow them.</>}</>} />
      ))}
    </div>
  );
}

function ProfileForm({ initial }: { initial: ProfileModel }) {
  const [p, setP] = useState(initial);
  const [busy, setBusy] = useState(false);
  useEffect(() => setP(initial), [initial]);
  const imperial = p.units === "IMPERIAL";
  const g = p.goals;
  const setGoals = (change: Partial<Goals>) => setP({ ...p, goals: { ...g, ...change } });
  const zones = (() => {
    try { return (Intl as unknown as { supportedValuesOf: (k: string) => string[] }).supportedValuesOf("timeZone"); } catch { return [p.timezone].filter(Boolean); }
  })();
  const numOrNull = (v: string) => (v.trim() === "" ? null : Number(v.replace(",", ".")));
  const heightFeet = p.heightCm ? Math.floor(p.heightCm / 2.54 / 12) : "";
  const heightInches = p.heightCm ? Math.round(p.heightCm / 2.54) % 12 : "";
  const save = async () => {
    setBusy(true);
    try {
      const saved = await api.saveProfile(p);
      session.setProfile(saved);
      toast("Saved");
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <div className="two-col">
        <Field label="Units">
          <select value={p.units} onChange={(e) => setP({ ...p, units: e.target.value as ProfileModel["units"] })}>
            <option value="METRIC">Metric (kg, cm, km)</option>
            <option value="IMPERIAL">Imperial (lb, ft, mi)</option>
          </select>
        </Field>
        <Field label="Time zone" help="Where your days begin and end">
          <select value={p.timezone} onChange={(e) => setP({ ...p, timezone: e.target.value })}>
            {!p.timezone && <option value="">Choose…</option>}
            {zones.map((z) => <option key={z} value={z}>{z.replace(/_/g, " ")}</option>)}
          </select>
        </Field>
      </div>
      <div className="two-col">
        <Field label="Birth year"><input inputMode="numeric" value={p.birthYear ?? ""} onChange={(e) => setP({ ...p, birthYear: numOrNull(e.target.value) })} placeholder="e.g. 1994" /></Field>
        {imperial ? (
          <Field label="Height (ft, in)">
            <div className="pair">
              <input inputMode="numeric" aria-label="Feet" value={heightFeet} onChange={(e) => setP({ ...p, heightCm: ((Number(e.target.value) || 0) * 12 + (Number(heightInches) || 0)) * 2.54 || null })} />
              <input inputMode="numeric" aria-label="Inches" value={heightInches} onChange={(e) => setP({ ...p, heightCm: ((Number(heightFeet) || 0) * 12 + (Number(e.target.value) || 0)) * 2.54 || null })} />
            </div>
          </Field>
        ) : (
          <Field label="Height (cm)"><input inputMode="decimal" value={p.heightCm != null ? Math.round(p.heightCm) : ""} onChange={(e) => setP({ ...p, heightCm: numOrNull(e.target.value) })} /></Field>
        )}
      </div>
      <Field label="Calorie estimates use" help="Resting-energy formulas differ for female and male bodies. Pick what fits your body best (for example after hormone therapy), or the average of both.">
        <select value={p.energyProfile} onChange={(e) => setP({ ...p, energyProfile: e.target.value as ProfileModel["energyProfile"] })}>
          <option value="FEMALE">The female formula</option>
          <option value="MALE">The male formula</option>
          <option value="AVERAGE">The average of both</option>
        </select>
      </Field>

      <h3>Goals</h3>
      <div className="two-col">
        <Field label="Steps a day"><input inputMode="numeric" value={g.steps} onChange={(e) => setGoals({ steps: Number(e.target.value) || 0 })} /></Field>
        <Field label="Active calories a day"><input inputMode="numeric" value={g.activeCalories} onChange={(e) => setGoals({ activeCalories: Number(e.target.value) || 0 })} /></Field>
      </div>
      <div className="two-col">
        <Field label="Sleep (hours)"><input inputMode="decimal" value={g.sleepMinutes / 60} onChange={(e) => setGoals({ sleepMinutes: Math.round((Number(e.target.value) || 8) * 60) })} /></Field>
        <Field label="Workouts a week"><input inputMode="numeric" value={g.workoutsPerWeek} onChange={(e) => setGoals({ workoutsPerWeek: Number(e.target.value) || 0 })} /></Field>
      </div>
      <div className="two-col">
        <Field label={imperial ? "Target weight (lb)" : "Target weight (kg)"} help="Optional">
          <input inputMode="decimal" value={g.targetWeightKg != null ? (imperial ? (g.targetWeightKg / KG_PER_LB).toFixed(1) : g.targetWeightKg.toFixed(1)) : ""}
            onChange={(e) => { const v = numOrNull(e.target.value); setGoals({ targetWeightKg: v == null ? null : Math.round(toKg(v, p.units) * 10) / 10 }); }} />
        </Field>
        <Field label="Aim to">
          <select value={g.weeklyChangeKg} onChange={(e) => setGoals({ weeklyChangeKg: Number(e.target.value) })}>
            {[-1, -0.75, -0.5, -0.25, 0, 0.25, 0.5].map((v) => (
              <option key={v} value={v}>{v === 0 ? "Keep my weight" : `${v < 0 ? "Lose" : "Gain"} ${imperial ? `${(Math.abs(v) / KG_PER_LB).toFixed(1)} lb` : `${Math.abs(v)} kg`} a week`}</option>
            ))}
          </select>
        </Field>
      </div>
      <div className="two-col">
        <Field label="Calories a day" help="Leave empty to work it out from what you burn and your aim"><input inputMode="numeric" value={g.calories ?? ""} onChange={(e) => setGoals({ calories: numOrNull(e.target.value) })} placeholder="Automatic" /></Field>
        <Field label="Water a day (ml)"><input inputMode="numeric" value={g.waterMl} onChange={(e) => setGoals({ waterMl: Number(e.target.value) || 0 })} /></Field>
      </div>
      <div className="two-col">
        <Field label="Protein (g)" help="Empty: about 1.4 g per kg of body weight"><input inputMode="numeric" value={g.protein ?? ""} onChange={(e) => setGoals({ protein: numOrNull(e.target.value) })} placeholder="Automatic" /></Field>
        <Field label="Carbs and fat (g)" help="Empty: fat 30% of calories, carbs the rest">
          <div className="pair">
            <input inputMode="numeric" aria-label="Carbs" value={g.carbs ?? ""} onChange={(e) => setGoals({ carbs: numOrNull(e.target.value) })} placeholder="Carbs" />
            <input inputMode="numeric" aria-label="Fat" value={g.fat ?? ""} onChange={(e) => setGoals({ fat: numOrNull(e.target.value) })} placeholder="Fat" />
          </div>
        </Field>
      </div>
      <div><Button variant="primary" busy={busy} type="submit">Save</Button></div>
    </form>
  );
}

function AppearanceSettings() {
  const boldText = usePrefs((p) => p.boldText);
  const palette = usePrefs((p) => p.palette);
  const scheme = usePrefs((p) => p.scheme);
  return (
    <div className="form-stack">
      <Field label="Theme">
        <div className="theme-picker" role="radiogroup" aria-label="Theme">
          {PALETTES.map((p) => (
            <button key={p.id} type="button" role="radio" aria-checked={palette === p.id} className={`theme-choice ${palette === p.id ? "is-selected" : ""}`} onClick={() => usePrefs.setState({ palette: p.id })}>
              <span className="theme-swatch" style={{ background: `linear-gradient(135deg, ${p.swatch[0]} 0 50%, ${p.swatch[1]} 50% 100%)` }} />
              {p.name}
            </button>
          ))}
        </div>
      </Field>
      <Field label="Style">
        <select value={scheme} onChange={(e) => usePrefs.setState({ scheme: e.target.value as "system" | "light" | "dark" })}>
          <option value="system">As the system is</option>
          <option value="light">Light</option>
          <option value="dark">Dark</option>
        </select>
      </Field>
      <Toggle label="Bold text" help="All text in Lexend Bold, the way a phone's Bold text setting draws it." checked={boldText} onChange={(boldText) => usePrefs.setState({ boldText })} />
    </div>
  );
}

function NameForm() {
  const user = useSession((s) => s.user)!;
  const [name, setName] = useState(user.displayName);
  const [busy, setBusy] = useState(false);
  return (
    <form className="inline-form" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try { session.setUser(await api.updateName(name)); toast("Saved"); } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <Field label="Name" help={`Username: ${user.username}${user.role === "ADMIN" ? " · Admin" : ""}`}>
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
      </Field>
      <Button busy={busy} disabled={name.trim() === user.displayName} type="submit">Save</Button>
    </form>
  );
}

function PasswordChange() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form className="form-stack" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try {
        await api.changePassword(current, next);
        setCurrent("");
        setNext("");
        toast("Password changed. Other devices were signed out.");
      } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <h3>Change password</h3>
      <div className="two-col">
        <Field label="Current password"><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
        <Field label="New password" help="At least 8 characters."><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required /></Field>
      </div>
      <div><Button busy={busy} type="submit">Change password</Button></div>
    </form>
  );
}

const KIND_TEXT: Record<string, string> = {
  WEB: "Signed in", APP: "Signed in", UPLOAD: "Key made", MCP: "Link made", MCP_WRITE: "Link made", MCP_ALL: "Link made",
  INTEGRATION: "Key made", WATCH: "Connected",
};

export function SignIns({ only }: { only?: string[] }) {
  const { data, error, loading, setData } = useLoad(() => api.sessions(), []);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  const list = (data ?? []).filter((s) => !only || only.includes(s.kind));
  if (only && list.length === 0) return null;
  return (
    <>
      <ul className="device-list">
        {list.map((s) => (
          <li key={s.id} className="device-row">
            {s.kind === "APP" ? <Smartphone size={20} /> : s.kind.startsWith("MCP") ? <Bot size={20} /> : s.kind === "UPLOAD" ? <KeyRound size={20} />
              : s.kind === "WATCH" ? <Watch size={20} /> : s.kind === "INTEGRATION" ? <Home size={20} /> : <Monitor size={20} />}
            <div className="source-text">
              <div className="source-name">{s.name}{s.current && <span className="badge">This browser</span>}</div>
              <div className="source-sub">{KIND_TEXT[s.kind]} {when(s.createdAt)} · last used {when(s.lastSeenAt)}{s.scope?.length ? ` · can see ${s.scope.map((m) => FEATURES.find((f) => f.id === m)?.name).join(", ")}` : ""}</div>
            </div>
            {!s.current && (
              <IconButton label={s.kind === "WEB" || s.kind === "APP" ? "Sign out this device" : "Remove this key"} onClick={async () => {
                try { await api.revokeSession(s.id); setData((data ?? []).filter((x) => x.id !== s.id)); } catch (e) { errorToast(e); }
              }}><LogOut size={18} /></IconButton>
            )}
          </li>
        ))}
      </ul>
      {!only && <Button variant="ghost" onClick={() => session.signOut()}><LogOut size={18} /> Sign out here</Button>}
    </>
  );
}

/**
 * A link that brings this account's data to an AI over the Model Context Protocol (Claude, or any
 * MCP client). Shown once; listed and removed with the sign-ins.
 */
function Connector({ onMade }: { onMade: () => void }) {
  const admin = useSession((s) => s.user)?.role === "ADMIN";
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [logging, setLogging] = useState(false);
  const [everyone, setEveryone] = useState(false);
  const on = useModules();
  const [scope, setScope] = useState<Module[]>([]);
  const sensitive = FEATURES.filter((f) => SENSITIVE.includes(f.id) && on.includes(f.id));
  return (
    <div className="form-stack">
      <p className="muted">
        Bring your data to the AI of your choice: it can read your days, scores, sleep, workouts, food and trends to answer questions
        and spot patterns, and check your devices' syncs when something looks wrong. It sees only your account.
      </p>
      {link ? (
        <>
          <div className="copy-row">
            <code>{link}</code>
            <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(link); toast("Copied"); }}><Copy size={16} /> Copy</Button>
          </div>
          <p className="muted">This link is the key: keep it private. It's shown only now; it's listed under "Signed in on", where you can remove it.</p>
          <ul className="muted small" style={{ margin: 0, paddingLeft: 18, display: "flex", flexDirection: "column", gap: 4 }}>
            <li><strong>Claude</strong>: Settings › Connectors › Add custom connector, and paste the link.</li>
            <li><strong>Other MCP clients</strong>: add it as a remote (Streamable HTTP) server. The token can go in the URL as here, or as <code>Authorization: Bearer</code> to <code>/mcp</code>.</li>
            <li><strong>Clients that only run local servers</strong>: <code>npx mcp-remote {"<link>"}</code></li>
          </ul>
        </>
      ) : (
        <>
          <Toggle checked={logging} onChange={(v) => { setLogging(v); if (v) setEveryone(false); }} label="Let it log entries"
            help="It may also log food, water, weight, workouts and check-ins for you (say, from a photo of your lunch). Off: it only reads." />
          {sensitive.length > 0 && !everyone && (
            <Field label="Also let it see" help="Off unless you choose: these stay private otherwise.">
              <div className="tag-list">
                {sensitive.map((f) => (
                  <button key={f.id} type="button" className={`chip ${scope.includes(f.id) ? "is-on" : ""}`} aria-pressed={scope.includes(f.id)}
                    onClick={() => setScope(scope.includes(f.id) ? scope.filter((x) => x !== f.id) : [...scope, f.id])}><Lock size={14} /> {f.name}</button>
                ))}
              </div>
            </Field>
          )}
          {admin && (
            <Toggle checked={everyone} onChange={(v) => { setEveryone(v); if (v) setLogging(false); }} label="See every account (admins)"
              help="Reads every account's activity, sleep, food and devices, and the server's log, to troubleshoot anyone's devices. Read-only, and never the private modules." />
          )}
          <div>
            <Button busy={busy} onClick={async () => {
              setBusy(true);
              try { setLink((await api.connector(logging, admin && everyone, everyone ? [] : scope)).url); onMade(); } catch (e) { errorToast(e); }
              setBusy(false);
            }}><Bot size={18} /> Make a connector link</Button>
          </div>
        </>
      )}
    </div>
  );
}
