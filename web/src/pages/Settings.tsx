import { useEffect, useState, type ReactNode } from "react";
import { ArrowUpDown, Bot, ChevronRight, Copy, Home, Inbox, KeyRound, Lock, LogOut, Monitor, Package, Shield, Smartphone, Watch } from "lucide-react";
import { useModules, useNavItems, useNavShown } from "../App";
import type { Module } from "../lib/types";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { api } from "../lib/api";
import { KG_PER_LB, toKg, when } from "../lib/format";
import { F, setField, useMicros, useWater } from "../lib/fields";
import { resetAllScreens, useLayoutStore, useScreenLayout } from "../lib/layout";
import { PALETTES, usePrefs } from "../lib/prefs";
import { session, useSession } from "../lib/session";
import { LAYOUT_NAV, SENSITIVE, layoutKey, type Goals, type Profile as ProfileModel } from "../lib/types";
import { Button, Field, IconButton, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { FEATURES } from "../components/features";
import { PlacePicker } from "../components/PlacePicker";
import { useArrange, type Section } from "../components/Arrange";
import { MealieSettings } from "./Mealie";
import { DigestSettings, IntegrationSettings, NotificationSettings, SharingSettings } from "./SettingsExtra";
import { AssistantSettingsCard, useAiStatus } from "./Assistant";
import { FieldsEditor } from "../components/FieldKit";
import { NutrientTargetsPanel } from "./NutrientTargets";

/** Features whose fields are chosen right here (bladder and bowel, and cycle, have their own pages). */
const RECORDED: { module: Module; name: string }[] = [
  { module: "NUTRITION", name: "Food" }, { module: "MIND", name: "Mind" }, { module: "NEURO", name: "Autism & ADHD" }, { module: "TRANSITION", name: "Transition" },
  { module: "BODY", name: "Body" },
];

/** Settings, in a few groups: each is a tab, and /settings#digest lands on the digest's. */
const GROUPS = [
  { id: "you", label: "You" },
  { id: "features", label: "Features" },
  { id: "reminders", label: "Reminders & sharing" },
  { id: "connections", label: "Connections" },
  { id: "appearance", label: "Appearance" },
] as const;
type Group = (typeof GROUPS)[number]["id"];
const label = (g: Group) => GROUPS.find((x) => x.id === g)!.label;

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

function Panel({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return <section className="panel" id={id}><h2>{title}</h2>{children}</section>;
}

export function Settings() {
  const ai = useAiStatus();
  const user = useSession((s) => s.user)!;
  const profile = useSession((s) => s.profile);
  const on = useModules();
  const water = useWater(on);
  // Vitamin and mineral targets: with Food on and its vitamins shown (never in eating disorder recovery).
  const micros = useMicros(on);
  const location = useLocation();
  const navigate = useNavigate();
  // A new connector link or upload key shows in the list of sign-ins at once.
  const [signIns, setSignIns] = useState(0);

  const sections: (Section & { group: string })[] = [
    { id: "profile", group: label("you"), label: "You and your goals", node: (
      <Panel id="profile" title="You and your goals">
        <p className="muted">Your profile works out resting energy and calorie targets; goals shape your scores.</p>
        {profile ? <ProfileForm initial={profile} water={water} /> : <Spinner />}
        {profile && (on.includes("SYMPTOMS") || profile.place) && (
          <>
            <h3>Weather</h3>
            <PlacePicker />
          </>
        )}
      </Panel>
    ) },
    { id: "nutrients", group: label("you"), label: "Nutrient targets", when: micros, node: (
      <Panel id="nutrients" title="Nutrient targets"><NutrientTargetsPanel /></Panel>
    ) },
    { id: "account", group: label("you"), label: "Account", node: (
      <Panel id="account" title="Account">
        <NameForm />
        {user.hasPassword && <PasswordChange />}
        {user.oidc && !user.hasPassword && <p className="muted">You sign in through single sign-on; your password is managed there.</p>}
      </Panel>
    ) },
    { id: "signins", group: label("you"), label: "Signed in on", node: (
      <Panel id="signins" title="Signed in on"><SignIns key={signIns} /></Panel>
    ) },
    { id: "tracking", group: label("features"), label: "What you're tracking", node: (
      <Panel id="tracking" title="What you're tracking"><TrackingSummary /></Panel>
    ) },
    { id: "features", group: label("features"), label: "Features", node: (
      <Panel id="features" title="Features">
        <p className="muted">Turn on the parts you want. The rest stay out of your way.</p>
        {profile ? <Features profile={profile} water={water} /> : <Spinner />}
      </Panel>
    ) },
    { id: "fields", group: label("features"), label: "What to record", when: RECORDED.some((m) => on.includes(m.module)) || on.includes("CONTINENCE") || on.includes("CYCLE"), node: (
      <Panel id="fields" title="What to record">
        <p className="muted">What you're tracking decides what each form asks for. Show or hide any of it here; it's saved straight away.</p>
        {on.includes("CONTINENCE") && <p><NavLink to="/bladder/settings">Bladder &amp; bowel settings</NavLink>: quick buttons, your own words and fields, bladder training.</p>}
        {on.includes("CYCLE") && <p><NavLink to="/cycle/settings">Cycle settings</NavLink>: your own symptoms, lengths, reminders and plain wording.</p>}
        {RECORDED.filter((m) => on.includes(m.module)).map((m) => (
          <details key={m.module} className="field-group">
            <summary>{m.name}</summary>
            <FieldsEditor module={m.module} />
          </details>
        ))}
      </Panel>
    ) },
    { id: "reminders", group: label("reminders"), label: "Reminders and notifications", node: (
      <Panel id="reminders" title="Reminders and notifications"><NotificationSettings /></Panel>
    ) },
    { id: "digest", group: label("reminders"), label: "Weekly digest", node: (
      <Panel id="digest" title="Weekly digest"><DigestSettings /></Panel>
    ) },
    { id: "sharing", group: label("reminders"), label: "Sharing", node: (
      <Panel id="sharing" title="Sharing"><SharingSettings /></Panel>
    ) },
    { id: "devices", group: label("connections"), label: "Devices", node: (
      <Panel id="devices" title="Devices">
        <p className="muted">Rings, watches, the phone app, FIT files and upload keys.</p>
        <div><Link className="btn btn-tonal" to="/devices"><Watch size={18} /> Devices</Link></div>
      </Panel>
    ) },
    { id: "mealie", group: label("connections"), label: "Mealie", when: on.includes("NUTRITION"), node: (
      <Panel id="mealie" title="Mealie"><MealieSettings /></Panel>
    ) },
    { id: "assistant", group: label("connections"), label: "AI assistant", when: !!ai?.available, node: <AssistantSettingsCard /> },
    { id: "connector", group: label("connections"), label: "AI connector (MCP)", node: (
      <Panel id="connector" title="AI connector (MCP)"><Connector water={water} onMade={() => setSignIns((n) => n + 1)} /></Panel>
    ) },
    { id: "integrations", group: label("connections"), label: "Webhooks and Home Assistant", node: (
      <Panel id="integrations" title="Webhooks and Home Assistant"><IntegrationSettings /></Panel>
    ) },
    { id: "watch", group: label("connections"), label: "Pebble watch", node: (
      <Panel id="watch" title="Pebble watch">
        <p className="muted">Install the SuperAwesomeMagicalHealth watch app, then open its settings in the Pebble app on your phone and sign in there. Your watch shows today's scores and doses, and logs {water ? "a mood, water, coffee or a toilet trip" : "a mood, coffee or a toilet trip"} with a tap. Connected watches are listed in You › Signed in on.</p>
      </Panel>
    ) },
    { id: "appearance", group: label("appearance"), label: "Appearance in this browser", node: (
      <Panel id="appearance" title="Appearance in this browser"><AppearanceSettings /></Panel>
    ) },
    { id: "screens", group: label("appearance"), label: "Arranged screens", node: (
      <Panel id="screens" title="Arranged screens"><ArrangedScreens /></Panel>
    ) },
  ];
  const arranged = useArrange(layoutKey("settings"), "Settings", sections, { groups: GROUPS.map((g) => g.label) });

  // The tab: from the address (#features, or a section's #digest), else the first.
  const hash = location.hash.slice(1);
  const ofSection = sections.find((x) => x.id === hash)?.group;
  const group: Group = GROUPS.find((g) => g.id === hash || g.label === ofSection)?.id ?? "you";
  const choose = (g: Group) => navigate({ hash: g === "you" ? "" : g }, { replace: true });
  useEffect(() => {
    // Links like /settings#digest land on their section.
    if (hash && sections.some((x) => x.id === hash)) setTimeout(() => document.getElementById(hash)?.scrollIntoView({ block: "start" }), 50);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hash]);
  const shown = arranged.shown.filter((x) => x.group === label(group));

  return (
    <div className="page settings">
      <PageHeader title="Settings" actions={arranged.button} />
      <MoreLinks admin={user.role === "ADMIN"} />
      <div className="settings-tabs">
        <div className="tabs" role="tablist" aria-label="Settings">
          {GROUPS.map((g) => (
            <button key={g.id} type="button" role="tab" id={`tab-${g.id}`} aria-selected={group === g.id} aria-controls="settings-panel" className={group === g.id ? "is-active" : ""} onClick={() => choose(g.id)}>{g.label}</button>
          ))}
        </div>
      </div>
      <div id="settings-panel" role="tabpanel" aria-labelledby={`tab-${group}`}>
        {shown.map((x) => <div key={x.id}>{x.node}</div>)}
        {shown.length === 0 && <p className="muted">Everything here is hidden. Use Arrange, at the top, to show it again.</p>}
      </div>
    </div>
  );
}

/** Phones show five tabs; the rest of the app is a tap away here, in the sidebar's order. */
function MoreLinks({ admin }: { admin: boolean }) {
  const on = useModules();
  const items = useNavShown(useNavItems()).filter((i) => !i.tab);
  return (
    <nav className="phone-only more-links" aria-label="More">
      {items.map((i) => <NavLink key={i.id} to={i.to} className="chip"><i.icon size={18} /> {i.label}</NavLink>)}
      {(on.includes("CONTINENCE") || on.includes("CYCLE")) && <NavLink to="/supplies" className="chip"><Package size={18} /> Supplies</NavLink>}
      <NavLink to="/digest" className="chip"><Inbox size={18} /> Your week</NavLink>
      {admin && <NavLink to="/admin" className="chip"><Shield size={18} /> Admin</NavLink>}
    </nav>
  );
}

/** How many screens the person has arranged, and a way to put them all back. */
function ArrangedScreens() {
  const count = useLayoutStore((s) => Object.keys(s.screens).length);
  const [busy, setBusy] = useState(false);
  return (
    <>
      <p className="muted">Each page has an <ArrowUpDown size={14} style={{ verticalAlign: -2 }} /> Arrange button at the top: move what matters to the top, and hide what you don't use. Today, its quick actions and the sidebar are arranged the same way in the phone app.</p>
      <div className="button-row">
        <Button busy={busy} disabled={count === 0} onClick={async () => {
          setBusy(true);
          try { await resetAllScreens(); toast("Every screen is back as it was"); } catch (e) { errorToast(e); }
          setBusy(false);
        }}>Put every screen back as it was</Button>
        <span className="muted small" style={{ alignSelf: "center" }}>{count === 0 ? "Nothing arranged yet." : `${count} ${count === 1 ? "screen" : "screens"} arranged.`}</span>
      </div>
    </>
  );
}

function Features({ profile, water }: { profile: ProfileModel; water: boolean }) {
  const [busy, setBusy] = useState(false);
  const [waterBusy, setWaterBusy] = useState(false);
  const nav = useScreenLayout(LAYOUT_NAV);
  const toggle = async (id: Module, enabled: boolean) => {
    setBusy(true);
    const modules = enabled ? [...profile.modules, id] : profile.modules.filter((m) => m !== id);
    try { session.setProfile(await api.saveProfile({ ...profile, modules })); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <div className="form-stack">
      <p className="muted small"><Lock size={12} style={{ verticalAlign: -1 }} /> Private features are encrypted on the server, and AI connectors only see them if you allow them.</p>
      {FEATURES.map((f) => {
        const isOn = profile.modules.includes(f.id);
        const navId = f.id === "NUTRITION" ? "FOOD" : f.id;
        const offSidebar = isOn && nav?.hidden.includes(navId);
        return (
          <div key={f.id} className="form-stack" style={{ gap: 10 }}>
            <div className="feature-row">
              <Toggle disabled={busy} checked={isOn} onChange={(v) => void toggle(f.id, v)}
                label={<>{f.name}{SENSITIVE.includes(f.id) && <Lock size={13} className="feature-lock" aria-label="Private" />}</>}
                help={<>{f.id === "NUTRITION" && !water ? "A food diary with calories, protein, carbs, fat, coffee and drinks." : f.about}{offSidebar && " Not in the sidebar: arrange it to show it again."}</>} />
              {isOn && f.route ? <Link className="icon-btn" to={f.route} aria-label={`Open ${f.name}`} title={`Open ${f.name}`}><ChevronRight size={20} /></Link> : <span className="icon-btn-space" aria-hidden="true" />}
            </div>
            {f.id === "NUTRITION" && isOn && (
              <div className="feature-row feature-sub">
                <Toggle disabled={waterBusy} checked={water} label="Water"
                  help="Your water goal, the quick +250 ml, water among drinks, in Trends and on the watch. Off, drinks still count caffeine and alcohol."
                  onChange={async (v) => {
                    setWaterBusy(true);
                    try { await setField(F.NUTRITION_WATER, v); toast(v ? "Water is back" : "Water is hidden"); } catch (e) { errorToast(e); }
                    setWaterBusy(false);
                  }} />
                <span className="icon-btn-space" aria-hidden="true" />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function ProfileForm({ initial, water }: { initial: ProfileModel; water: boolean }) {
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
        {water && <Field label="Water a day (ml)"><input inputMode="numeric" value={g.waterMl} onChange={(e) => setGoals({ waterMl: Number(e.target.value) || 0 })} /></Field>}
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
  const [all, setAll] = useState(false);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  const list = (data ?? []).filter((s) => !only || only.includes(s.kind));
  if (only && list.length === 0) return null;
  // The current one first; a long list shows its first few until asked.
  const sorted = [...list].sort((a, b) => Number(b.current ?? false) - Number(a.current ?? false));
  const SHOWN = 6;
  return (
    <>
      <ul className="device-list">
        {(all ? sorted : sorted.slice(0, SHOWN)).map((s) => (
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
      <div className="button-row">
        {sorted.length > SHOWN && <Button variant="ghost" onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${sorted.length}`}</Button>}
        {!only && <Button variant="ghost" onClick={() => session.signOut()}><LogOut size={18} /> Sign out here</Button>}
      </div>
    </>
  );
}

/**
 * A link that brings this account's data to an AI over the Model Context Protocol (Claude, or any
 * MCP client). Shown once; listed and removed with the sign-ins.
 */
function Connector({ water, onMade }: { water: boolean; onMade: () => void }) {
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
          <p className="muted">This link is the key: keep it private. It's shown only now; it's listed in You › Signed in on, where you can remove it.</p>
          <ul className="muted small" style={{ margin: 0, paddingLeft: 18, display: "flex", flexDirection: "column", gap: 4 }}>
            <li><strong>Claude</strong>: Settings › Connectors › Add custom connector, and paste the link.</li>
            <li><strong>Other MCP clients</strong>: add it as a remote (Streamable HTTP) server. The token can go in the URL as here, or as <code>Authorization: Bearer</code> to <code>/mcp</code>.</li>
            <li><strong>Clients that only run local servers</strong>: <code>npx mcp-remote {"<link>"}</code></li>
          </ul>
        </>
      ) : (
        <>
          <Toggle checked={logging} onChange={(v) => { setLogging(v); if (v) setEveryone(false); }} label="Let it log entries"
            help={`It may also log food, ${water ? "water, " : ""}weight, workouts and check-ins for you (say, from a photo of your lunch). Off: it only reads.`} />
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
