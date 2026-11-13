import { useEffect, useMemo, useRef, useState } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import { useModules } from "../App";
import {
  BatteryMedium, Brain, Check, CircleUserRound, Gauge, ListChecks, MessageSquareText, Pause, Pencil, Play, Plus, Printer, Sparkles, Square,
  Timer, Trash2, Waves, Wand2, X,
} from "lucide-react";
import { api } from "../lib/api";
import { num, today, when } from "../lib/format";
import type {
  CommunicationCard, EnergyDay, FocusKind, FocusSession, NeuroCheckIn, NeuroProfile, OverloadEvent, OverloadKind, Routine, RoutineRun,
  RoutineStep, Sense, Wording,
} from "../lib/types";
import { FOCUS_KINDS, OVERLOAD_KINDS, SENSES, senseLabel } from "../lib/types";
import { F, useFields } from "../lib/fields";
import { useReadOnly } from "../lib/viewing";
import { Button, Card, Empty, Field, Modal, PageHeader, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { ChipPicker, OneOf, ScaleRow, WordList } from "../components/FieldKit";
import { INSIGHT_ICONS } from "../components/LogDialogs";
import { useAiAvailable } from "./Assistant";

/*
 * Autism and ADHD, for anyone autistic or ADHD, diagnosed or not. Experiences, not symptoms:
 * overwhelm is information, stims and special interests are things that help, and nothing here
 * is about looking less autistic or less ADHD.
 */

const EMPTY_PROFILE: NeuroProfile = {
  identities: [], wording: "IDENTITY_FIRST", senses: [], communication: [], whatHelps: [], accommodations: [], warningSigns: [], interests: [], stims: [],
};
const IDENTITIES = ["Autistic", "ADHD", "AuDHD", "Dyspraxic", "Dyslexic", "Exploring"];
const TRIGGERS = ["change of plans", "too many demands", "noise", "bright light", "crowds", "social", "hunger", "tired", "transitions", "being rushed", "uncertainty"];
const HELPS = ["quiet space", "headphones", "dim lights", "weighted blanket", "stimming", "time alone", "movement", "a snack", "water", "deep pressure", "routine", "special interest"];
const WARNING_SIGNS = ["irritable", "can't find words", "covering ears", "restless", "shutting down", "tearful", "headache", "can't think"];

const DEFAULT_CARDS: CommunicationCard[] = [
  { text: "I'm overwhelmed and need a quiet minute.", detail: "I'm OK. Please give me some space and don't touch me." },
  { text: "I can't talk right now.", detail: "Please text me or write it down." },
  { text: "Please speak slowly and give me time.", detail: "I'm processing what you said." },
  { text: "I need to leave.", detail: "It's not about you. I'll be in touch." },
];

type Tab = "about" | "overwhelm" | "energy" | "checkins" | "focus" | "routines" | "cards" | "patterns";

export function Neuro() {
  const { has } = useFields();
  const readOnly = useReadOnly();
  const navigate = useNavigate();
  const mindOn = useModules().includes("MIND");
  const tabs = ([
    { id: "about", label: "About me", on: has(F.NEURO_ABOUT_ME) || has(F.NEURO_SENSORY) },
    { id: "overwhelm", label: "Overwhelm", on: has(F.NEURO_OVERLOAD) || has(F.NEURO_SENSORY) },
    { id: "energy", label: "Energy", on: has(F.MIND_ENERGY_BUDGET) || has(F.NEURO_SOCIAL) || has(F.NEURO_BURNOUT) },
    { id: "checkins", label: "Check-ins", on: has(F.NEURO_MASKING) || has(F.NEURO_EXECUTIVE) || has(F.NEURO_BURNOUT) || has(F.NEURO_MED_EFFECT) },
    { id: "focus", label: "Focus", on: has(F.NEURO_FOCUS) || has(F.NEURO_TIME) || has(F.NEURO_INTERESTS) },
    { id: "routines", label: "Routines", on: has(F.NEURO_ROUTINES) },
    { id: "cards", label: "Cards", on: has(F.NEURO_CARDS) },
    { id: "patterns", label: "Patterns", on: true },
  ] as { id: Tab; label: string; on: boolean }[]).filter((t) => t.on);
  const [chosen, setTab] = useState<Tab>(tabs[0]?.id ?? "patterns");
  // The chosen tab, or the first that shows if the field layout has since hidden it.
  const tab: Tab = tabs.some((t) => t.id === chosen) ? chosen : tabs[0]?.id ?? "patterns";
  return (
    <div className="page">
      <PageHeader title="Autism & ADHD" subtitle="Your sensory life, energy, focus and what helps, in your words"
        actions={has(F.NEURO_CALM) && <Button variant="primary" onClick={() => navigate("/calm")}><Waves size={18} /> Calm mode</Button>} />
      {mindOn && (
        <nav className="tabs" aria-label="Mind sections">
          <NavLink to="/mind" end className={({ isActive }) => (isActive ? "is-active" : "")}>Mind</NavLink>
          <NavLink to="/neuro" className={({ isActive }) => (isActive ? "is-active" : "")}>Autism &amp; ADHD</NavLink>
        </nav>
      )}
      <div className="tabs tabs-sub" role="tablist" aria-label="Sections">
        {tabs.map((t) => <button key={t.id} role="tab" aria-selected={tab === t.id} className={tab === t.id ? "is-active" : ""} onClick={() => setTab(t.id)}>{t.label}</button>)}
      </div>
      {tab === "about" && <AboutMe readOnly={readOnly} />}
      {tab === "overwhelm" && <Overwhelm readOnly={readOnly} />}
      {tab === "energy" && <div className="grid grid-2"><EnergyBudgetCard readOnly={readOnly} /></div>}
      {tab === "checkins" && <CheckIns readOnly={readOnly} />}
      {tab === "focus" && <Focus readOnly={readOnly} />}
      {tab === "routines" && <Routines readOnly={readOnly} />}
      {tab === "cards" && <Cards readOnly={readOnly} />}
      {tab === "patterns" && <Patterns />}
    </div>
  );
}

/** "I'm autistic" or "I'm a person with autism", as the person prefers. */
export function identityLine(p: NeuroProfile): string {
  if (p.identities.length === 0) return "";
  const list = p.identities.map((i) => i.toLowerCase());
  if (p.wording === "PERSON_FIRST") {
    const words = list.map((i) => (i === "autistic" ? "autism" : i === "adhd" ? "ADHD" : i === "audhd" ? "autism and ADHD" : i));
    return `I'm a person with ${joinAnd(words)}.`;
  }
  const words = list.map((i) => (i === "adhd" ? "ADHD" : i === "audhd" ? "AuDHD" : i));
  return `I'm ${joinAnd(words)}.`;
}

const joinAnd = (w: string[]) => (w.length <= 1 ? w.join("") : `${w.slice(0, -1).join(", ")} and ${w[w.length - 1]}`);

function AboutMe({ readOnly }: { readOnly: boolean }) {
  const { has } = useFields();
  const load = useLoad(() => api.neuroProfile().catch(() => EMPTY_PROFILE), []);
  const [p, setP] = useState<NeuroProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [card, setCard] = useState(false);
  useEffect(() => { if (load.data && !p) setP(load.data); }, [load.data, p]);
  if (!p) return <Spinner />;
  const save = async () => {
    setBusy(true);
    try { setP(await api.saveNeuroProfile(p)); toast("Saved"); } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  const sense = (s: Sense) => p.senses.find((x) => x.sense === s) ?? { sense: s, response: 0, helps: [] };
  const setSense = (s: Sense, response: number) => setP({ ...p, senses: [...p.senses.filter((x) => x.sense !== s), { ...sense(s), response }] });
  return (
    <div className="grid grid-2">
      <Card title="About me" icon={<CircleUserRound size={18} />} action={<Button variant="ghost" onClick={() => setCard(true)}><Printer size={16} /> Card</Button>}>
        <ChipPicker label="How I describe myself" options={IDENTITIES} value={p.identities} onChange={(identities) => setP({ ...p, identities })} />
        <OneOf<Wording> label="Words I prefer" options={[{ id: "IDENTITY_FIRST", label: "Autistic person / ADHDer" }, { id: "PERSON_FIRST", label: "Person with autism / ADHD" }]}
          value={p.wording} onChange={(wording) => setP({ ...p, wording: wording ?? "IDENTITY_FIRST" })} />
        <Field label="In my own words"><textarea rows={3} value={p.aboutMe ?? ""} placeholder="If I'm overwhelmed, please…" onChange={(e) => setP({ ...p, aboutMe: e.target.value || undefined })} /></Field>
        <WordList label="How I like to communicate" words={p.communication} onChange={(communication) => setP({ ...p, communication })} placeholder="e.g. text over calls" />
        <WordList label="What helps" words={p.whatHelps} onChange={(whatHelps) => setP({ ...p, whatHelps })} placeholder="e.g. written instructions" />
        <WordList label="Accommodations" words={p.accommodations} onChange={(accommodations) => setP({ ...p, accommodations })} placeholder="e.g. a quiet desk" />
        <WordList label="Early signs I'm getting overwhelmed" words={p.warningSigns} onChange={(warningSigns) => setP({ ...p, warningSigns })} />
        {has(F.NEURO_INTERESTS) && <WordList label="Special interests" words={p.interests} onChange={(interests) => setP({ ...p, interests })} />}
        {has(F.NEURO_STIMS) && <WordList label="Stims that help" words={p.stims} onChange={(stims) => setP({ ...p, stims })} />}
        {!readOnly && <Button variant="primary" busy={busy} onClick={save}>Save</Button>}
      </Card>
      {has(F.NEURO_SENSORY) && (
        <Card title="My senses" icon={<Sparkles size={18} />}>
          <p className="muted small">For each: do you tend to avoid it, or seek it out? Most people are a mix.</p>
          <ul className="sense-list">
            {SENSES.map((s) => {
              const v = sense(s.id).response;
              return (
                <li key={s.id} className="sense-row">
                  <span className="sense-name">{s.label}</span>
                  <input type="range" min={-2} max={2} step={1} value={v} aria-label={`${s.label}: avoid to seek`} aria-valuetext={["Avoid a lot", "Avoid a little", "In between", "Seek a little", "Seek a lot"][v + 2]}
                    onChange={(e) => setSense(s.id, Number(e.target.value))} style={{ ["--fill" as string]: `${(v + 2) * 25}%` }} />
                  <span className="sense-value">{["Avoid a lot", "Avoid a little", "In between", "Seek a little", "Seek a lot"][v + 2]}</span>
                </li>
              );
            })}
          </ul>
          {!readOnly && <Button variant="tonal" busy={busy} onClick={save}>Save</Button>}
        </Card>
      )}
      {card && <AboutCard profile={p} onClose={() => setCard(false)} />}
    </div>
  );
}

/** A card to print or show: who I am, how to talk with me, what helps. */
function AboutCard({ profile, onClose }: { profile: NeuroProfile; onClose: () => void }) {
  const avoid = profile.senses.filter((s) => s.response < 0).map((s) => senseLabel(s.sense).toLowerCase());
  return (
    <Modal wide title="About me" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Close</Button><Button variant="primary" onClick={() => window.print()}><Printer size={16} /> Print</Button></>}>
      <div className="about-card printable">
        {identityLine(profile) && <p className="about-lead">{identityLine(profile)}</p>}
        {profile.aboutMe && <p>{profile.aboutMe}</p>}
        {profile.communication.length > 0 && <><h4>Talking with me</h4><ul>{profile.communication.map((c) => <li key={c}>{c}</li>)}</ul></>}
        {profile.whatHelps.length > 0 && <><h4>What helps</h4><ul>{profile.whatHelps.map((c) => <li key={c}>{c}</li>)}</ul></>}
        {avoid.length > 0 && <><h4>Things that can be a lot</h4><p>{joinAnd(avoid)}</p></>}
        {profile.warningSigns.length > 0 && <><h4>If I'm getting overwhelmed, you might notice</h4><p>{joinAnd(profile.warningSigns)}</p></>}
        {profile.accommodations.length > 0 && <><h4>Accommodations</h4><ul>{profile.accommodations.map((c) => <li key={c}>{c}</li>)}</ul></>}
      </div>
    </Modal>
  );
}

function Overwhelm({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.overloads(90).catch(() => [] as OverloadEvent[]), []);
  const [draft, setDraft] = useState<OverloadEvent | null>(null);
  const items = [...(list.data ?? [])].sort((a, b) => b.start - a.start);
  return (
    <div className="grid grid-2">
      <Card title="When things get too much" icon={<Waves size={18} />} className="span-2"
        action={!readOnly && <Button variant="primary" onClick={() => setDraft({ start: Date.now(), kind: "OVERWHELM", intensity: 3, senses: [], triggers: [], warningSigns: [], whatHelped: [] })}><Plus size={16} /> Log one</Button>}>
        <p className="muted small">Overwhelm, meltdowns and shutdowns aren't anyone's fault: they're what a nervous system does when it's had too much. Logging them, when you're ready, shows what sets them off and what helps.</p>
        {list.loading && !list.data ? <Spinner /> : items.length === 0 ? <p className="muted">Nothing logged.</p> : (
          <ul className="rows">
            {items.slice(0, 20).map((e) => (
              <li key={e.id} className="row">
                <div className="row-icon"><Waves size={18} /></div>
                <button className="row-main link-row" disabled={readOnly} onClick={() => setDraft(e)}>
                  <div className="row-title">{OVERLOAD_KINDS.find((k) => k.id === e.kind)?.label} · {e.intensity} of 5</div>
                  <div className="row-sub">{when(e.start)}{e.triggers.length ? ` · ${e.triggers.join(", ")}` : ""}{e.whatHelped.length ? ` · helped: ${e.whatHelped.join(", ")}` : ""}{e.recoveryMinutes ? ` · settled in ${hm(e.recoveryMinutes)}` : ""}</div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {draft && <OverloadDialog event={draft} onClose={() => setDraft(null)} onSaved={list.reload} />}
    </div>
  );
}

const hm = (m: number) => (m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `${m} min`);

function OverloadDialog({ event, onClose, onSaved }: { event: OverloadEvent; onClose: () => void; onSaved: () => void }) {
  const [e, setE] = useState(event);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await api.saveOverload(e); toast("Saved. Be gentle with yourself."); onSaved(); onClose(); } catch (err) { errorToast(err); setBusy(false); }
  };
  return (
    <Modal wide title={event.id ? "Edit" : "When things got too much"} onClose={onClose} footer={<>
      {event.id && <Button variant="ghost" onClick={async () => { try { await api.deleteOverload(event.id!); onSaved(); onClose(); } catch (err) { errorToast(err); } }}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button>
    </>}>
      <OneOf<OverloadKind> label="What it was" options={OVERLOAD_KINDS} value={e.kind} onChange={(kind) => setE({ ...e, kind: kind ?? "OVERWHELM" })} />
      <ScaleRow label="How intense" low="Mild" high="Intense" value={e.intensity} onChange={(intensity) => setE({ ...e, intensity: intensity ?? 3 })} />
      <ChipPicker label="Senses involved" allowOwn={false} options={SENSES.map((s) => s.label)} value={e.senses.map(senseLabel)}
        onChange={(labels) => setE({ ...e, senses: SENSES.filter((s) => labels.includes(s.label)).map((s) => s.id) })} />
      <ChipPicker label="What set it off" options={TRIGGERS} value={e.triggers} onChange={(triggers) => setE({ ...e, triggers })} />
      <ChipPicker label="Signs beforehand" options={WARNING_SIGNS} value={e.warningSigns} onChange={(warningSigns) => setE({ ...e, warningSigns })} />
      <ChipPicker label="What helped" options={HELPS} value={e.whatHelped} onChange={(whatHelped) => setE({ ...e, whatHelped })} />
      <div className="two-col">
        <Field label="Where"><input value={e.whereAt ?? ""} onChange={(ev) => setE({ ...e, whereAt: ev.target.value || undefined })} /></Field>
        <Field label="Time to feel settled (minutes)"><input inputMode="numeric" value={e.recoveryMinutes ?? ""} onChange={(ev) => setE({ ...e, recoveryMinutes: Number(ev.target.value) || undefined })} /></Field>
      </div>
      <Field label="Note"><textarea rows={2} value={e.note ?? ""} onChange={(ev) => setE({ ...e, note: ev.target.value || undefined })} /></Field>
    </Modal>
  );
}

/** Spoons (or any unit): what the day started with, what used them, and the social battery. */
export function EnergyBudgetCard({ readOnly, compact }: { readOnly: boolean; compact?: boolean }) {
  const { has } = useFields();
  const list = useLoad(() => api.energy(14).catch(() => [] as EnergyDay[]), []);
  const todayDay = list.data?.find((d) => d.date === today()) ?? { date: today(), spends: [] };
  const [what, setWhat] = useState("");
  const [amount, setAmount] = useState(1);
  const save = async (d: EnergyDay) => {
    try { await api.saveEnergy(d); list.reload(); } catch (e) { errorToast(e); }
  };
  const used = todayDay.spends.reduce((t, s) => t + s.amount, 0);
  const left = todayDay.startWith != null ? todayDay.startWith - used : undefined;
  return (
    <Card title="Energy today" icon={<BatteryMedium size={18} />} className={compact ? "" : "span-2"}>
      {todayDay.startWith == null ? (
        !readOnly ? (
          <div className="form-stack">
            <p className="muted">How many spoons (or units of energy) do you have today?</p>
            <div className="tag-list">{[6, 8, 10, 12, 15].map((n) => <button key={n} type="button" className="chip" onClick={() => save({ ...todayDay, startWith: n })}>{n}</button>)}</div>
          </div>
        ) : <p className="muted">Not set today.</p>
      ) : (
        <>
          <div className="spoons" aria-label={`${left} of ${todayDay.startWith} left`}>
            {Array.from({ length: todayDay.startWith }, (_, i) => <span key={i} className={`spoon ${i < (left ?? 0) ? "is-left" : ""}`} aria-hidden="true" />)}
          </div>
          <div className="stats">
            <Stat label="Left" value={left ?? "–"} sub={`of ${todayDay.startWith}`} />
            {has(F.NEURO_SOCIAL) && <Stat label="Social battery" value={todayDay.socialBattery != null ? `${todayDay.socialBattery * 10}%` : "–"} />}
          </div>
          {todayDay.spends.length > 0 && <div className="tag-list">{todayDay.spends.map((s, i) => <span key={i} className="chip is-static">{s.what} <span className="muted">{s.amount}</span></span>)}</div>}
          {!readOnly && (
            <form className="inline-form" onSubmit={(e) => { e.preventDefault(); if (what.trim()) { void save({ ...todayDay, spends: [...todayDay.spends, { what: what.trim(), amount, at: Date.now() }] }); setWhat(""); } }}>
              <Field label="What used some"><input value={what} onChange={(e) => setWhat(e.target.value)} placeholder="e.g. a phone call" /></Field>
              <Field label="How many"><select value={amount} onChange={(e) => setAmount(Number(e.target.value))}>{[1, 2, 3, 4, 5, -1, -2].map((n) => <option key={n} value={n}>{n > 0 ? n : `${n} (gave some back)`}</option>)}</select></Field>
              <Button type="submit">Add</Button>
            </form>
          )}
          {has(F.NEURO_SOCIAL) && !readOnly && (
            <ScaleRow label="Social battery" min={0} max={10} low="Empty" high="Full" value={todayDay.socialBattery} onChange={(socialBattery) => save({ ...todayDay, socialBattery })} />
          )}
        </>
      )}
    </Card>
  );
}

function CheckIns({ readOnly }: { readOnly: boolean }) {
  const { has } = useFields();
  const list = useLoad(() => api.neuroCheckIns(30).catch(() => [] as NeuroCheckIn[]), []);
  const [c, setC] = useState<NeuroCheckIn>({ at: Date.now() });
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await api.addNeuroCheckIn({ ...c, at: Date.now() }); toast("Checked in"); setC({ at: Date.now() }); list.reload(); } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  const recent = [...(list.data ?? [])].sort((a, b) => b.at - a.at).slice(0, 10);
  return (
    <div className="grid grid-2">
      {!readOnly && (
        <Card title="How it's going" icon={<Gauge size={18} />}>
          <p className="muted small">Each is 1 to 5. Skip anything you like.</p>
          {has(F.NEURO_MASKING) && <ScaleRow label="Masking" low="Not at all" high="All day" value={c.masking} onChange={(masking) => setC({ ...c, masking })} />}
          {has(F.NEURO_MASKING) && <ScaleRow label="Social load" low="Light" high="Heavy" value={c.socialLoad} onChange={(socialLoad) => setC({ ...c, socialLoad })} />}
          {has(F.NEURO_SENSORY) && <ScaleRow label="Sensory load" low="Light" high="Heavy" value={c.sensoryLoad} onChange={(sensoryLoad) => setC({ ...c, sensoryLoad })} />}
          {has(F.NEURO_EXECUTIVE) && (
            <>
              <ScaleRow label="Getting started" low="Stuck" high="Easy" value={c.starting} onChange={(starting) => setC({ ...c, starting })} />
              <ScaleRow label="Focus" low="Scattered" high="Sharp" value={c.focus} onChange={(focus) => setC({ ...c, focus })} />
              <ScaleRow label="Organising" low="Hard" high="Easy" value={c.organising} onChange={(organising) => setC({ ...c, organising })} />
              <ScaleRow label="Remembering" low="Hard" high="Easy" value={c.remembering} onChange={(remembering) => setC({ ...c, remembering })} />
              <ScaleRow label="Switching tasks" low="Hard" high="Easy" value={c.switching} onChange={(switching) => setC({ ...c, switching })} />
              <ScaleRow label="Restlessness" low="Calm" high="Very" value={c.restlessness} onChange={(restlessness) => setC({ ...c, restlessness })} />
              <ScaleRow label="Emotions" low="Steady" high="Big" value={c.emotions} onChange={(emotions) => setC({ ...c, emotions })} />
            </>
          )}
          {has(F.NEURO_BURNOUT) && <ScaleRow label="Depleted" low="Fine" high="In burnout" value={c.burnout} onChange={(burnout) => setC({ ...c, burnout })} />}
          {has(F.NEURO_MED_EFFECT) && (
            <div className="two-col">
              <ScaleRow label="Medication worked" low="Not at all" high="Very well" value={c.medEffect} onChange={(medEffect) => setC({ ...c, medEffect })} />
              <Field label="Wore off around"><input type="time" value={c.woreOffAt ?? ""} onChange={(e) => setC({ ...c, woreOffAt: e.target.value || undefined })} /></Field>
            </div>
          )}
          <Field label="Note"><input value={c.note ?? ""} onChange={(e) => setC({ ...c, note: e.target.value || undefined })} /></Field>
          <Button variant="primary" busy={busy} onClick={save}>Save</Button>
        </Card>
      )}
      <Card title="Lately">
        {recent.length === 0 ? <p className="muted">No check-ins yet.</p> : (
          <ul className="rows">
            {recent.map((r) => (
              <li key={r.id ?? r.at} className="row">
                <div className="row-main">
                  <div className="row-title">{when(r.at)}</div>
                  <div className="row-sub">{[
                    r.masking != null ? `masking ${r.masking}` : "", r.focus != null ? `focus ${r.focus}` : "", r.burnout != null ? `depleted ${r.burnout}` : "",
                    r.medEffect != null ? `medication ${r.medEffect}${r.woreOffAt ? `, wore off ${r.woreOffAt}` : ""}` : "", r.note ?? "",
                  ].filter(Boolean).join(" · ")}</div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function Focus({ readOnly }: { readOnly: boolean }) {
  const { has } = useFields();
  const list = useLoad(() => api.focusSessions(30).catch(() => [] as FocusSession[]), []);
  const [timer, setTimer] = useState<FocusSession | null>(null);
  const [task, setTask] = useState("");
  const [estimate, setEstimate] = useState("");
  const [kind, setKind] = useState<FocusKind>("FOCUS");
  const recent = [...(list.data ?? [])].sort((a, b) => b.start - a.start).slice(0, 12);
  const start = () => setTimer({ start: Date.now(), kind, task: task.trim() || undefined, estimateMinutes: Number(estimate) || undefined, breaks: 0 });
  return (
    <div className="grid grid-2">
      {!readOnly && (
        <Card title="Start a timer" icon={<Timer size={18} />}>
          <OneOf<FocusKind> label="What kind" options={FOCUS_KINDS.filter((k) => k.id !== "INTEREST" || has(F.NEURO_INTERESTS))} value={kind} onChange={(k) => setKind(k ?? "FOCUS")} />
          <Field label="What you're doing"><input value={task} onChange={(e) => setTask(e.target.value)} placeholder="e.g. answer three emails" /></Field>
          {has(F.NEURO_TIME) && <Field label="How long you think it'll take (minutes)" help="Comparing the guess with what really happens helps with time"><input inputMode="numeric" value={estimate} onChange={(e) => setEstimate(e.target.value)} /></Field>}
          <div className="button-row">
            <Button variant="primary" onClick={start}><Play size={16} /> Start</Button>
            {task.trim() && <BreakdownButton task={task} />}
          </div>
          {kind === "BODY_DOUBLING" && <p className="muted small">Body doubling: someone working alongside you, in person or on a call. The timer keeps you company too.</p>}
        </Card>
      )}
      <Card title="Lately">
        {recent.length === 0 ? <p className="muted">No sessions yet.</p> : (
          <ul className="rows">
            {recent.map((s) => {
              const minutes = s.end ? Math.round((s.end - s.start) / 60_000) : null;
              return (
                <li key={s.id ?? s.start} className="row">
                  <div className="row-main">
                    <div className="row-title">{s.task ?? FOCUS_KINDS.find((k) => k.id === s.kind)?.label}</div>
                    <div className="row-sub">{when(s.start)}{minutes != null ? ` · ${hm(minutes)}` : ""}{s.estimateMinutes ? ` · guessed ${hm(s.estimateMinutes)}` : ""}</div>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
      {timer && <FocusTimer session={timer} onClose={() => setTimer(null)} onSaved={list.reload} />}
    </div>
  );
}

/**
 * A calm full-screen timer: the time counts up gently (no alarms, no flashing), with the guess
 * alongside; breaks pause it. With reduced motion, nothing moves at all.
 */
function FocusTimer({ session, onClose, onSaved }: { session: FocusSession; onClose: () => void; onSaved: () => void }) {
  const [elapsed, setElapsed] = useState(0);
  const [running, setRunning] = useState(true);
  const [breaks, setBreaks] = useState(0);
  const [done, setDone] = useState(false);
  const [rating, setRating] = useState<number | undefined>(undefined);
  const last = useRef(Date.now());
  useEffect(() => {
    if (!running) return;
    last.current = Date.now();
    const t = window.setInterval(() => { const now = Date.now(); setElapsed((e) => e + (now - last.current)); last.current = now; }, 1000);
    return () => window.clearInterval(t);
  }, [running]);
  const minutes = Math.floor(elapsed / 60_000);
  const seconds = Math.floor((elapsed % 60_000) / 1000);
  const estimate = session.estimateMinutes;
  const progress = estimate ? Math.min(1, elapsed / (estimate * 60_000)) : null;
  const finish = async () => {
    try {
      await api.saveFocus({ ...session, end: session.start + Math.max(elapsed, 60_000), breaks, rating });
      toast(estimate ? `${minutes} min, against a guess of ${estimate}` : "Saved");
      onSaved(); onClose();
    } catch (e) { errorToast(e); }
  };
  return (
    <div className="fullscreen focus-screen" role="dialog" aria-modal="true" aria-label="Focus timer">
      <button className="fullscreen-close icon-btn" aria-label="Close without saving" onClick={onClose}><X size={22} /></button>
      <div className="focus-body">
        <p className="focus-task">{session.task ?? FOCUS_KINDS.find((k) => k.id === session.kind)?.label}</p>
        <div className="focus-time" aria-live="off">{String(minutes).padStart(2, "0")}:{String(seconds).padStart(2, "0")}</div>
        {estimate != null && (
          <div className="focus-estimate">
            <div className="meter"><div className="meter-fill" style={{ width: `${(progress ?? 0) * 100}%` }} /></div>
            <span className="muted">{elapsed > estimate * 60_000 ? `A little past your ${estimate}-minute guess. That's useful to know.` : `Your guess: ${estimate} minutes`}</span>
          </div>
        )}
        {!done ? (
          <div className="button-row center">
            <Button variant="tonal" onClick={() => { if (running) setBreaks((b) => b + 1); setRunning(!running); }}>{running ? <><Pause size={18} /> Take a break</> : <><Play size={18} /> Back to it</>}</Button>
            <Button variant="primary" onClick={() => { setRunning(false); setDone(true); }}><Square size={18} /> Done</Button>
          </div>
        ) : (
          <div className="form-stack focus-done">
            <ScaleRow label="How it went" low="Hard" high="Great" value={rating} onChange={setRating} />
            <Button variant="primary" onClick={finish}><Check size={18} /> Save</Button>
          </div>
        )}
        {!running && !done && <p className="muted">Paused. Stretch, drink some water; it'll wait for you.</p>}
      </div>
    </div>
  );
}

/** Small steps from a task's text, through the AI assistant, if it's on and you've said yes. */
export function BreakdownButton({ task, onSteps }: { task: string; onSteps?: (steps: RoutineStep[]) => void }) {
  const ai = useAiAvailable();
  const [warn, setWarn] = useState(false);
  const [steps, setSteps] = useState<RoutineStep[] | null>(null);
  const [busy, setBusy] = useState(false);
  if (!ai) return null;
  const go = async () => {
    setBusy(true);
    try {
      const reply = await api.aiBreakdown(task);
      setSteps(reply.steps);
      onSteps?.(reply.steps);
    } catch (e) { errorToast(e); } finally { setBusy(false); setWarn(false); }
  };
  return (
    <>
      <Button variant="ghost" onClick={() => setWarn(true)}><Wand2 size={16} /> Break this down</Button>
      {warn && (
        <Modal title="Break it down with the assistant" onClose={() => setWarn(false)} footer={<><Button variant="ghost" onClick={() => setWarn(false)}>Cancel</Button><Button variant="primary" busy={busy} onClick={go}>Send</Button></>}>
          <p>Only this task's words go to <strong>Anthropic</strong>, a company outside this server, to suggest small steps: no health data.</p>
          <p className="quote">“{task}”</p>
        </Modal>
      )}
      {steps && (
        <Modal title="Small steps" onClose={() => setSteps(null)}>
          <ol className="steps">{steps.map((s, i) => <li key={i}>{s.text}{s.minutes ? <span className="muted"> · {s.minutes} min</span> : null}</li>)}</ol>
        </Modal>
      )}
    </>
  );
}

function Routines({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.routines().catch(() => [] as Routine[]), []);
  const runs = useLoad(() => api.routineRuns(14).catch(() => [] as RoutineRun[]), []);
  const [editing, setEditing] = useState<Routine | null>(null);
  const todayRuns = (runs.data ?? []).filter((r) => r.date === today());
  const tick = async (r: Routine, i: number) => {
    const run = todayRuns.find((x) => x.routineId === r.id) ?? { routineId: r.id!, date: today(), done: [] };
    const done = run.done.includes(i) ? run.done.filter((x) => x !== i) : [...run.done, i];
    try { await api.saveRoutineRun({ ...run, done, at: Date.now() }); runs.reload(); } catch (e) { errorToast(e); }
  };
  const active = (list.data ?? []).filter((r) => r.active);
  return (
    <div className="grid grid-2">
      {active.length === 0 && !list.loading && (
        <Card className="span-2"><Empty icon={<ListChecks size={32} />} title="No routines yet">A routine as a short checklist takes the planning out of mornings, evenings and leaving the house.</Empty></Card>
      )}
      {active.map((r) => {
        const run = todayRuns.find((x) => x.routineId === r.id);
        const done = run?.done.length ?? 0;
        return (
          <Card key={r.id} title={r.name} icon={<ListChecks size={18} />} action={!readOnly && <Button variant="ghost" aria-label={`Edit ${r.name}`} onClick={() => setEditing(r)}><Pencil size={16} /></Button>}>
            <div className="meter" aria-label={`${done} of ${r.steps.length} done`}><div className="meter-fill" style={{ width: `${r.steps.length ? (done / r.steps.length) * 100 : 0}%` }} /></div>
            <ul className="checklist">
              {r.steps.map((s, i) => {
                const on = !!run?.done.includes(i);
                return (
                  <li key={i}>
                    <button className={`check-item ${on ? "is-done" : ""}`} disabled={readOnly} aria-pressed={on} onClick={() => tick(r, i)}>
                      <span className="check-box">{on && <Check size={14} />}</span>
                      <span>{s.text}</span>{s.minutes ? <span className="muted small">{s.minutes} min</span> : null}
                    </button>
                  </li>
                );
              })}
            </ul>
            {r.reminder && <p className="muted small">Nudge at {r.reminder}</p>}
          </Card>
        );
      })}
      {!readOnly && <div className="span-2"><Button variant="primary" onClick={() => setEditing({ name: "", steps: [], days: [], icon: "list-checks", active: true })}><Plus size={16} /> New routine</Button></div>}
      {editing && <RoutineDialog routine={editing} onClose={() => setEditing(null)} onSaved={list.reload} />}
    </div>
  );
}

function RoutineDialog({ routine, onClose, onSaved }: { routine: Routine; onClose: () => void; onSaved: () => void }) {
  const [r, setR] = useState(routine);
  const [text, setText] = useState("");
  const [minutes, setMinutes] = useState("");
  const save = async () => {
    if (!r.name.trim() || r.steps.length === 0) return;
    try { await api.saveRoutine(r); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title={routine.id ? "Edit routine" : "New routine"} onClose={onClose} footer={<>
      {routine.id && <Button variant="ghost" onClick={async () => { try { await api.deleteRoutine(routine.id!); onSaved(); onClose(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!r.name.trim() || r.steps.length === 0} onClick={save}>Save</Button>
    </>}>
      <Field label="Name"><input autoFocus value={r.name} onChange={(e) => setR({ ...r, name: e.target.value })} placeholder="e.g. Leaving the house" /></Field>
      <ol className="steps editable">
        {r.steps.map((s, i) => (
          <li key={i}>{s.text}{s.minutes ? <span className="muted"> · {s.minutes} min</span> : null}
            <button type="button" className="chip-x" aria-label={`Remove ${s.text}`} onClick={() => setR({ ...r, steps: r.steps.filter((_, j) => j !== i) })}><X size={14} /></button>
          </li>
        ))}
      </ol>
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); if (text.trim()) { setR({ ...r, steps: [...r.steps, { text: text.trim(), minutes: Number(minutes) || undefined }] }); setText(""); setMinutes(""); } }}>
        <Field label="A step"><input value={text} onChange={(e) => setText(e.target.value)} placeholder="e.g. keys, wallet, phone" /></Field>
        <Field label="Minutes"><input inputMode="numeric" value={minutes} onChange={(e) => setMinutes(e.target.value)} /></Field>
        <Button type="submit">Add</Button>
      </form>
      {r.name.trim() && r.steps.length === 0 && <BreakdownButton task={r.name} onSteps={(steps) => setR({ ...r, steps })} />}
      <Field label="Gentle nudge at" help="Optional"><input type="time" value={r.reminder ?? ""} onChange={(e) => setR({ ...r, reminder: e.target.value || undefined })} /></Field>
    </Modal>
  );
}

function Cards({ readOnly }: { readOnly: boolean }) {
  const load = useLoad(() => api.cards().catch(() => DEFAULT_CARDS), []);
  const [showing, setShowing] = useState<CommunicationCard | null>(null);
  const [editing, setEditing] = useState(false);
  const cards = load.data ?? DEFAULT_CARDS;
  return (
    <div className="grid grid-2">
      <Card title="Cards to show someone" icon={<MessageSquareText size={18} />} className="span-2"
        action={!readOnly && <Button variant="ghost" onClick={() => setEditing(true)}><Pencil size={16} /> Change</Button>}>
        <p className="muted small">When talking is hard, tap a card and turn your screen to whoever needs to see it.</p>
        <div className="card-grid">
          {cards.map((c, i) => <button key={c.id ?? i} className="comm-card" onClick={() => setShowing(c)}><strong>{c.text}</strong>{c.detail && <span>{c.detail}</span>}</button>)}
        </div>
      </Card>
      {showing && <ShowCard card={showing} onClose={() => setShowing(null)} />}
      {editing && <CardsEditor cards={cards} onClose={() => setEditing(false)} onSaved={load.reload} />}
    </div>
  );
}

/** One card, full screen, big and high-contrast. */
export function ShowCard({ card, onClose }: { card: CommunicationCard; onClose: () => void }) {
  return (
    <div className="fullscreen show-card" role="dialog" aria-modal="true" aria-label={card.text} onClick={onClose}>
      <div className="show-card-text">
        <p>{card.text}</p>
        {card.detail && <p className="show-card-detail">{card.detail}</p>}
      </div>
      <p className="show-card-hint">Tap anywhere to close</p>
    </div>
  );
}

function CardsEditor({ cards, onClose, onSaved }: { cards: CommunicationCard[]; onClose: () => void; onSaved: () => void }) {
  const [list, setList] = useState(cards);
  const save = async () => {
    try { await api.saveCards(list.filter((c) => c.text.trim())); toast("Cards saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal wide title="Your cards" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
      {list.map((c, i) => (
        <div key={i} className="two-col card-edit">
          <Field label={`Card ${i + 1}`}><input value={c.text} onChange={(e) => setList(list.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)))} /></Field>
          <Field label="Smaller words"><input value={c.detail ?? ""} onChange={(e) => setList(list.map((x, j) => (j === i ? { ...x, detail: e.target.value || undefined } : x)))} /></Field>
          <Button variant="ghost" aria-label={`Remove card ${i + 1}`} onClick={() => setList(list.filter((_, j) => j !== i))}><Trash2 size={16} /></Button>
        </div>
      ))}
      <Button onClick={() => setList([...list, { text: "" }])}><Plus size={16} /> Another card</Button>
    </Modal>
  );
}

function Patterns() {
  const summary = useLoad(() => api.neuroSummary(30).catch(() => null), []);
  const s = summary.data;
  if (summary.loading && !s) return <Spinner />;
  if (!s) return <Card><Empty icon={<Brain size={32} />} title="Patterns come with time">Once there are a couple of weeks of entries, what goes with harder days shows here.</Empty></Card>;
  return (
    <div className="grid grid-2">
      {s.burnoutRisk && (
        <Card title="Something to notice" className="span-2">
          <p>{s.burnoutRisk}</p>
          <p className="muted small">Rest isn't a reward for getting through: it's how you keep going. Fewer demands, more of what restores you, and your people.</p>
        </Card>
      )}
      <Card title="The last 30 days">
        <div className="stats">
          <Stat label="Overwhelm" value={s.overloads} sub="times" />
          <Stat label="Masking" value={s.averageMasking != null ? num(s.averageMasking, 1) : "–"} sub="of 5, on average" />
          <Stat label="Focus" value={hm(s.focusMinutes)} />
          {s.estimateRatio != null && <Stat label="Time taken" value={`× ${num(s.estimateRatio, 1)}`} sub="against your guesses" />}
        </div>
        {s.triggers.length > 0 && <><h4 className="subsection-title">What set things off</h4><div className="tag-list">{s.triggers.map((t) => <span key={t.name} className="chip is-static">{t.name} <span className="muted">{t.count}</span></span>)}</div></>}
        {s.whatHelped.length > 0 && <><h4 className="subsection-title">What helped</h4><div className="tag-list">{s.whatHelped.map((t) => <span key={t.name} className="chip is-static">{t.name} <span className="muted">{t.count}</span></span>)}</div></>}
      </Card>
      {s.patterns.length > 0 && (
        <Card title="What goes together">
          <div className="insights" style={{ margin: 0 }}>
            {s.patterns.map((p, i) => {
              const Icon = INSIGHT_ICONS[p.icon] ?? Sparkles;
              return <article key={i} className={`insight tone-${p.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{p.title}</h4><p>{p.body}</p></div></article>;
            })}
          </div>
        </Card>
      )}
    </div>
  );
}

/**
 * Calm mode: a quiet full-screen page with muted colours and nothing that moves unless you ask,
 * your own strategies and cards, slow breathing, and a script for leaving.
 */
export function CalmMode() {
  const navigate = useNavigate();
  const profile = useLoad(() => api.neuroProfile().catch(() => EMPTY_PROFILE), []);
  const cards = useLoad(() => api.cards().catch(() => DEFAULT_CARDS), []);
  const [breathing, setBreathing] = useState(false);
  const [phase, setPhase] = useState<"in" | "out">("in");
  const [card, setCard] = useState<CommunicationCard | null>(null);
  const [leaving, setLeaving] = useState(false);
  useEffect(() => {
    if (!breathing) return;
    const t = window.setInterval(() => setPhase((p) => (p === "in" ? "out" : "in")), 5000);
    return () => window.clearInterval(t);
  }, [breathing]);
  const helps = useMemo(() => profile.data?.whatHelps ?? [], [profile.data]);
  return (
    <div className="calm" role="main" aria-label="Calm mode">
      <button className="calm-close" onClick={() => navigate(-1)} aria-label="Leave calm mode"><X size={22} /> <span>Leave</span></button>
      <div className="calm-body">
        <h1>You're safe. Take your time.</h1>
        <button className={`calm-breath ${breathing ? `is-${phase}` : ""}`} onClick={() => setBreathing(!breathing)} aria-pressed={breathing}>
          {breathing ? (phase === "in" ? "Breathe in…" : "…and out") : "Breathe with me"}
        </button>
        <button className="calm-leave" onClick={() => setLeaving(!leaving)} aria-expanded={leaving}>I need to leave</button>
        {leaving && (
          <div className="calm-script">
            <p>“I need to step out for a bit. I'm OK, I just need some quiet. I'll be back, or I'll message you.”</p>
            <p>Then: find the nearest quiet place. Headphones on if you have them. Water. Breathe out slowly.</p>
          </div>
        )}
        {helps.length > 0 && (
          <section>
            <h2>What helps you</h2>
            <ul className="calm-list">{helps.map((h) => <li key={h}>{h}</li>)}</ul>
          </section>
        )}
        <section>
          <h2>Show someone</h2>
          <div className="calm-cards">{(cards.data ?? DEFAULT_CARDS).map((c, i) => <button key={c.id ?? i} onClick={() => setCard(c)}>{c.text}</button>)}</div>
        </section>
      </div>
      {card && <ShowCard card={card} onClose={() => setCard(null)} />}
    </div>
  );
}

/** A small summary for Today. */
export function NeuroTodayCard({ data }: { data: { overloadsToday: number; energyLeft?: number; socialBattery?: number; routinesDue: string[]; focusMinutes: number } }) {
  return (
    <Card title="Autism & ADHD" icon={<Brain size={18} />} action={<Link className="card-action" to="/neuro">Open</Link>}>
      <div className="stats">
        {data.energyLeft != null && <Stat label="Energy left" value={data.energyLeft} sub="spoons" />}
        {data.socialBattery != null && <Stat label="Social battery" value={`${data.socialBattery * 10}%`} />}
        {data.focusMinutes > 0 && <Stat label="Focus" value={hm(data.focusMinutes)} />}
      </div>
      {data.routinesDue.length > 0 && <p className="muted small">Still to do: {data.routinesDue.join(", ")}</p>}
      {data.overloadsToday > 0 && <p className="muted small">A hard moment today. Go gently: <Link to="/calm">calm mode</Link> is here if it helps.</p>}
    </Card>
  );
}
