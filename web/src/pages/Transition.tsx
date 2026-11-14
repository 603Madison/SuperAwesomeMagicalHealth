import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Camera, Check, Flag, Heart, Mic, Pencil, Plus, Scissors, Sparkles, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { isoDate, num, shortDate, today, useUnits, when } from "../lib/format";
import type {
  BodyChange, GenderMoment, HairRemovalSession, Milestone, MilestoneArea, MilestoneStatus, MilestoneTemplate, MomentKind, TransitionProfile, VoiceSession,
} from "../lib/types";
import { HAIR_METHODS, MILESTONE_AREAS, MILESTONE_STATUSES } from "../lib/types";
import { F, useFields } from "../lib/fields";
import { useReadOnly } from "../lib/viewing";
import { LineChart } from "../components/charts";
import { MeasurementsCard } from "../components/Measurements";
import { PitchMeter, type PitchResult } from "../components/PitchMeter";
import { Button, Card, CardAction, DateTimeInput, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { ChipPicker, OneOf, ScaleRow } from "../components/FieldKit";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { todaysLesson } from "../lib/voice/program";

/*
 * Transition, whatever it means to you and at your pace: milestones, the changes you notice, voice
 * practice, hair removal, and the moments that feel right (and the ones that don't).
 */

const CHANGE_AREAS = ["chest", "skin", "hair", "face", "voice", "fat distribution", "muscle", "emotions", "libido", "periods", "body hair", "smell", "energy"];
const MOMENT_CONTEXT = ["mirror", "voice", "being gendered right", "being misgendered", "clothes", "body", "name", "photos", "with friends", "at work"];
const VOICE_EXERCISES = ["resonance", "pitch slides", "reading aloud", "humming", "breath support", "intonation", "conversation"];
const HAIR_AREAS = ["face", "neck", "chest", "arms", "legs", "back", "underarms", "bikini line"];

export function Transition() {
  const { has } = useFields();
  const readOnly = useReadOnly();
  const units = useUnits();
  const overview = useLoad(() => api.transition(), []);
  const o = overview.data;
  const [profileOpen, setProfileOpen] = useState(false);
  const sections: Section[] = [
    { id: "where", label: "Where you are", node: o && (
      <Card title="Where you are" icon={<Sparkles size={18} />} className="span-2 transition-hero">
        <div className="stats">
          {o.sinceStart && <Stat label="Since you began" value={o.sinceStart} sub={o.profile.startedOn ? new Date(`${o.profile.startedOn}T12:00`).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : undefined} />}
          {o.onHormones && <Stat label="On hormones" value={o.onHormones} />}
          {has(F.TRANSITION_MOMENTS) && <Stat label="Euphoria" value={o.euphoria} sub="moments, last 90 days" />}
          {o.profile.pronouns && <Stat label="Pronouns" value={o.profile.pronouns} />}
        </div>
        {o.highlights.length > 0 && <ul className="plain-list highlights">{o.highlights.map((h, i) => <li key={i}>{h}</li>)}</ul>}
        {o.profile.goals.length > 0 && <div className="tag-list">{o.profile.goals.map((g) => <span key={g} className="chip is-static">{g}</span>)}</div>}
        {!o.sinceStart && !o.onHormones && <p className="muted">Transition looks different for everyone. Track only what matters to you: everything here is private and encrypted.</p>}
      </Card>
    ) },
    { id: "milestones", label: "Milestones", when: has(F.TRANSITION_MILESTONES), node: o && <Milestones readOnly={readOnly} /> },
    { id: "moments", label: "Moments", when: has(F.TRANSITION_MOMENTS), node: o && <Moments readOnly={readOnly} /> },
    { id: "changes", label: "Changes you notice", when: has(F.TRANSITION_CHANGES), node: o && <Changes readOnly={readOnly} /> },
    { id: "voice", label: "Voice training", when: has(F.TRANSITION_VOICE), node: o && <Voice readOnly={readOnly} target={o.profile.voiceTargetHz} /> },
    { id: "hair", label: "Hair removal", when: has(F.TRANSITION_HAIR), node: o && <HairRemoval readOnly={readOnly} /> },
    { id: "measurements", label: "Measurements", when: has(F.TRANSITION_MEASUREMENTS), node: o && (
      <MeasurementsCard imperial={units === "IMPERIAL"} readOnly={readOnly} metrics={["CHEST", "BUST", "UNDERBUST", "WAIST", "HIPS", "SHOULDERS"]} sets={o.measurements} onSaved={overview.reload} />
    ) },
    { id: "photos", label: "Photos", node: o && (
      <Card title="Photos" icon={<Camera size={18} />} link={{ to: "/photos", label: "Open Progress photos" }} className="span-2">
        <p className="muted small">Encrypted, never shown to AI, and shared only if you choose.</p>
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("transition"), "Transition", sections);
  return (
    <div className="page">
      <PageHeader title="Transition" subtitle="Your milestones, changes and moments, at your pace" actions={<>
        {!readOnly && <Button variant="ghost" onClick={() => setProfileOpen(true)}><Pencil size={16} /> About you</Button>}
        {arranged.button}
      </>} />
      {overview.error && <ErrorNotice message={overview.error} onRetry={overview.reload} />}
      {!o && overview.loading && <Spinner />}
      {arranged.render()}
      {profileOpen && o && <ProfileDialog profile={o.profile} onClose={() => setProfileOpen(false)} onSaved={overview.reload} />}
    </div>
  );
}

function ProfileDialog({ profile, onClose, onSaved }: { profile: TransitionProfile; onClose: () => void; onSaved: () => void }) {
  const [p, setP] = useState(profile);
  const save = async () => {
    try { await api.saveTransitionProfile(p); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title="About you" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
      <Field label="Pronouns" help="Shown with your name in the app, and to people you share with"><input value={p.pronouns ?? ""} placeholder="e.g. she/her, they/them" onChange={(e) => setP({ ...p, pronouns: e.target.value || undefined })} /></Field>
      <Field label="When you began" help="In whatever sense matters to you"><input type="date" value={p.startedOn ?? ""} onChange={(e) => setP({ ...p, startedOn: e.target.value || undefined })} /></Field>
      <ChipPicker label="Your goals, in your words" options={[]} value={p.goals} onChange={(goals) => setP({ ...p, goals })} />
      <Field label="Voice: a pitch to aim around (Hz)" help="Optional; a voice coach can help you choose"><input inputMode="numeric" value={p.voiceTargetHz ?? ""} onChange={(e) => setP({ ...p, voiceTargetHz: Number(e.target.value) || undefined })} /></Field>
    </Modal>
  );
}

function Milestones({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.milestones().catch(() => [] as Milestone[]), []);
  const templates = useLoad(() => api.milestoneTemplates().catch(() => [] as MilestoneTemplate[]), []);
  const [editing, setEditing] = useState<Milestone | null>(null);
  const [picking, setPicking] = useState(false);
  const items = list.data ?? [];
  return (
    <Card title="Milestones" icon={<Flag size={18} />} className="span-2"
      action={!readOnly && <CardAction label="Add a milestone" onClick={() => setEditing({ title: "", area: "SOCIAL", status: "PLANNED", checklist: [] })}><Plus size={18} /></CardAction>}>
      {items.length === 0 ? <p className="muted">Steps you've taken or want to: telling people, a new name, documents, hormones, a procedure, your voice. Each at your own pace.</p> : (
        <div className="milestone-board">
          {MILESTONE_AREAS.map((a) => {
            const inArea = items.filter((m) => m.area === a.id);
            if (inArea.length === 0) return null;
            return (
              <section key={a.id} className="milestone-column">
                <h4 className="subsection-title">{a.label}</h4>
                {inArea.map((m) => {
                  const done = m.checklist.filter((c) => c.done).length;
                  return (
                    <button key={m.id} className={`milestone status-${m.status.toLowerCase()}`} disabled={readOnly} onClick={() => setEditing(m)}>
                      <span className="milestone-title">{m.status === "DONE" && <Check size={14} />} {m.title}</span>
                      <span className="muted small">{MILESTONE_STATUSES.find((s) => s.id === m.status)?.label}{m.date ? ` · ${shortDate(m.date)}` : ""}{m.checklist.length ? ` · ${done} of ${m.checklist.length}` : ""}</span>
                    </button>
                  );
                })}
              </section>
            );
          })}
        </div>
      )}
      {!readOnly && <div><Button variant="ghost" className="btn-small" onClick={() => setPicking(true)}>Start from a template</Button></div>}
      {editing && <MilestoneDialog milestone={editing} onClose={() => setEditing(null)} onSaved={list.reload} />}
      {picking && (
        <Modal wide title="Start from a template" onClose={() => setPicking(false)}>
          {templates.loading ? <Spinner /> : (
            <ul className="rows">
              {(templates.data ?? []).map((t) => (
                <li key={t.title} className="row">
                  <div className="row-main"><div className="row-title">{t.title}</div><div className="row-sub">{MILESTONE_AREAS.find((a) => a.id === t.area)?.label}{t.about ? ` · ${t.about}` : ""}{t.checklist.length ? ` · ${t.checklist.length} steps` : ""}</div></div>
                  <div className="row-actions"><Button variant="tonal" onClick={() => { setPicking(false); setEditing({ title: t.title, area: t.area, status: "PLANNED", checklist: t.checklist.map((c) => ({ text: c, done: false })) }); }}>Use</Button></div>
                </li>
              ))}
            </ul>
          )}
        </Modal>
      )}
    </Card>
  );
}

function MilestoneDialog({ milestone, onClose, onSaved }: { milestone: Milestone; onClose: () => void; onSaved: () => void }) {
  const [m, setM] = useState(milestone);
  const [step, setStep] = useState("");
  const save = async () => {
    if (!m.title.trim()) return;
    try { await api.saveMilestone({ ...m, title: m.title.trim() }); toast(m.status === "DONE" && milestone.status !== "DONE" ? "Done. That's a big one." : "Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal wide title={milestone.id ? "Milestone" : "New milestone"} onClose={onClose} footer={<>
      {milestone.id && <Button variant="ghost" onClick={async () => { try { await api.deleteMilestone(milestone.id!); onSaved(); onClose(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!m.title.trim()} onClick={save}>Save</Button>
    </>}>
      <Field label="What"><input autoFocus value={m.title} onChange={(e) => setM({ ...m, title: e.target.value })} placeholder="e.g. Update my passport" /></Field>
      <OneOf<MilestoneArea> label="Area" options={MILESTONE_AREAS} value={m.area} onChange={(area) => setM({ ...m, area: area ?? "OTHER" })} />
      <OneOf<MilestoneStatus> label="Where it's at" options={MILESTONE_STATUSES} value={m.status} onChange={(status) => setM({ ...m, status: status ?? "PLANNED" })} />
      <Field label={m.status === "DONE" ? "When" : "When (planned)"}><input type="date" value={m.date ?? ""} onChange={(e) => setM({ ...m, date: e.target.value || undefined })} /></Field>
      <Field label="Steps">
        <ul className="checklist">
          {m.checklist.map((c, i) => (
            <li key={i}>
              <button type="button" className={`check-item ${c.done ? "is-done" : ""}`} aria-pressed={c.done} onClick={() => setM({ ...m, checklist: m.checklist.map((x, j) => (j === i ? { ...x, done: !x.done } : x)) })}>
                <span className="check-box">{c.done && <Check size={14} />}</span><span>{c.text}</span>
              </button>
            </li>
          ))}
        </ul>
        <div className="inline-form">
          <input value={step} onChange={(e) => setStep(e.target.value)} placeholder="Add a step" aria-label="Add a step" onKeyDown={(e) => { if (e.key === "Enter" && step.trim()) { e.preventDefault(); setM({ ...m, checklist: [...m.checklist, { text: step.trim(), done: false }] }); setStep(""); } }} />
          <Button onClick={() => { if (step.trim()) { setM({ ...m, checklist: [...m.checklist, { text: step.trim(), done: false }] }); setStep(""); } }}>Add</Button>
        </div>
      </Field>
      <Field label="Notes"><textarea rows={2} value={m.notes ?? ""} onChange={(e) => setM({ ...m, notes: e.target.value || undefined })} /></Field>
    </Modal>
  );
}

function Moments({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.moments(90).catch(() => [] as GenderMoment[]), []);
  const [adding, setAdding] = useState<MomentKind | null>(null);
  const items = [...(list.data ?? [])].sort((a, b) => b.at - a.at);
  return (
    <Card title="Moments" icon={<Heart size={18} />}
      action={!readOnly && <CardAction label="A moment that felt right" onClick={() => setAdding("EUPHORIA")}><Sparkles size={18} /></CardAction>}>
      {items.length === 0 ? <p className="muted">Keep the moments that feel right, to come back to. The hard ones are worth noting too: they show what to look after.</p> : (
        <ul className="rows">
          {items.slice(0, 8).map((m) => (
            <li key={m.id} className={`row moment-${m.kind.toLowerCase()}`}>
              <div className="row-icon">{m.kind === "EUPHORIA" ? <Sparkles size={18} /> : <Heart size={18} />}</div>
              <div className="row-main">
                <div className="row-title">{m.note ?? (m.kind === "EUPHORIA" ? "A good moment" : "A hard moment")}</div>
                <div className="row-sub">{when(m.at)}{m.context.length ? ` · ${m.context.join(", ")}` : ""}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
      {!readOnly && <div><Button variant="ghost" className="btn-small" onClick={() => setAdding("DYSPHORIA")}>Note a hard one</Button></div>}
      {adding && <MomentDialog kind={adding} onClose={() => setAdding(null)} onSaved={list.reload} />}
    </Card>
  );
}

export function MomentDialog({ kind, onClose, onSaved }: { kind: MomentKind; onClose: () => void; onSaved: () => void }) {
  const [m, setM] = useState<GenderMoment>({ at: Date.now(), kind, intensity: 3, context: [] });
  const save = async () => {
    try { await api.addMoment(m); toast(kind === "EUPHORIA" ? "Kept. Here's to more of those." : "Noted. Be kind to yourself today."); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title={kind === "EUPHORIA" ? "A moment that felt right" : "A hard moment"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
      {kind === "DYSPHORIA" && <p className="muted">That sounds hard. You don't have to write much: noting it is enough.</p>}
      <ScaleRow label="How strong" low="A little" high="A lot" value={m.intensity} onChange={(intensity) => setM({ ...m, intensity: intensity ?? 3 })} />
      <ChipPicker label="What it was about" options={MOMENT_CONTEXT} value={m.context} onChange={(context) => setM({ ...m, context })} />
      <Field label="In a few words"><textarea rows={2} value={m.note ?? ""} onChange={(e) => setM({ ...m, note: e.target.value || undefined })} /></Field>
    </Modal>
  );
}

function Changes({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.bodyChanges().catch(() => [] as BodyChange[]), []);
  const [editing, setEditing] = useState<BodyChange | null>(null);
  const items = [...(list.data ?? [])].sort((a, b) => b.date.localeCompare(a.date));
  return (
    <Card title="Changes you notice" icon={<Sparkles size={18} />} action={!readOnly && <CardAction label="Add a change you noticed" onClick={() => setEditing({ date: today(), area: "", note: "" })}><Plus size={18} /></CardAction>}>
      {items.length === 0 ? <p className="muted">The changes you notice, as you notice them: it's lovely to look back on.</p> : (
        <ol className="timeline">
          {items.slice(0, 10).map((c) => (
            <li key={c.id}>
              <span className="timeline-dot" aria-hidden="true" />
              <button className="link-row" disabled={readOnly} onClick={() => setEditing(c)}>
                <strong>{c.area}</strong> · {c.note}
                <span className="muted small"> {shortDate(c.date)}{c.feeling ? ` · ${["", "not good", "mixed", "fine", "good", "love it"][c.feeling]}` : ""}</span>
              </button>
            </li>
          ))}
        </ol>
      )}
      {editing && <ChangeDialog change={editing} onClose={() => setEditing(null)} onSaved={list.reload} />}
    </Card>
  );
}

function ChangeDialog({ change, onClose, onSaved }: { change: BodyChange; onClose: () => void; onSaved: () => void }) {
  const [c, setC] = useState(change);
  const save = async () => {
    if (!c.area.trim() || !c.note.trim()) return;
    try { await api.saveBodyChange(c); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title="A change" onClose={onClose} footer={<>
      {change.id && <Button variant="ghost" onClick={async () => { try { await api.deleteBodyChange(change.id!); onSaved(); onClose(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!c.area.trim() || !c.note.trim()} onClick={save}>Save</Button>
    </>}>
      <ChipPicker label="Where" multi={false} options={CHANGE_AREAS} value={c.area ? [c.area] : []} onChange={(v) => setC({ ...c, area: v[0] ?? "" })} />
      <Field label="What you noticed"><textarea rows={2} value={c.note} onChange={(e) => setC({ ...c, note: e.target.value })} /></Field>
      <Field label="When"><input type="date" value={c.date} onChange={(e) => setC({ ...c, date: e.target.value })} /></Field>
      <ScaleRow label="How you feel about it" low="Not good" high="Love it" value={c.feeling} onChange={(feeling) => setC({ ...c, feeling })} />
    </Modal>
  );
}

/** Voice training at a glance: today's lesson, the streak and the week, with the way in; and practice logged by hand. */
function Voice({ readOnly, target }: { readOnly: boolean; target?: number }) {
  const navigate = useNavigate();
  const list = useLoad(() => api.voiceSessions(180).catch(() => [] as VoiceSession[]), []);
  const progress = useLoad(() => api.voiceProgress().catch(() => null), []);
  const program = useLoad(() => api.voiceProgram().catch(() => null), []);
  const [adding, setAdding] = useState(false);
  const items = [...(list.data ?? [])].sort((a, b) => a.at - b.at);
  const withPitch = items.filter((v) => v.pitchAvgHz != null);
  const week = items.filter((v) => v.at > Date.now() - 7 * 86_400_000).reduce((t, v) => t + v.minutes, 0);
  const g = progress.data;
  const plan = g?.plan;
  const lesson = program.data && g ? todaysLesson(program.data, g) : undefined;
  const firstRun = !!plan && plan.baselineAt == null && plan.targetLowHz == null;
  const aim = plan?.targetLowHz && plan.targetHighHz ? `${Math.round(plan.targetLowHz)}–${Math.round(plan.targetHighHz)} Hz` : target ? `around ${target} Hz` : undefined;
  return (
    <Card title="Voice training" icon={<Mic size={18} />} link={{ to: "/transition/voice", label: "Open Voice training" }}
      action={!readOnly && <CardAction label="Log practice by hand" onClick={() => setAdding(true)}><Plus size={18} /></CardAction>}>
      <div className="stats">
        {g && <Stat label="Streak" value={g.streakDays} unit={g.streakDays === 1 ? "day" : "days"} />}
        <Stat label="This week" value={`${g?.minutesThisWeek ?? week} min`} />
        {withPitch.length > 0 && <Stat label="Latest average" value={`${Math.round(withPitch[withPitch.length - 1].pitchAvgHz!)} Hz`} sub={aim ? `aiming ${aim}` : undefined} />}
      </div>
      {g?.restNote && <p className="notice rest-note">{g.restNote}</p>}
      {!readOnly && (program.data || firstRun) && (
        <div className="voice-next">
          <div>
            <span className="muted small">{firstRun ? "A free, guided programme with live feedback" : "Today's lesson"}</span>
            <strong>{firstRun ? "Set up voice training" : lesson?.title ?? "Free practice"}</strong>
          </div>
          <Button variant="primary" className="btn-small" onClick={() => navigate("/transition/voice")}>{firstRun ? "Set up" : "Start"}</Button>
        </div>
      )}
      {withPitch.length > 1 && (
        <LineChart label="Average pitch" color="var(--c1)" dots height={140}
          points={withPitch.map((v) => ({ x: v.at, y: v.pitchAvgHz! }))} formatX={(x) => shortDate(isoDate(new Date(x)))} formatY={(y) => `${Math.round(y)} Hz`} />
      )}
      {items.length === 0 && !g && <p className="muted">Little and often works best: ten minutes a day goes a long way.</p>}
      {adding && <VoiceDialog target={target} onClose={() => setAdding(false)} onSaved={() => { list.reload(); progress.reload(); }} />}
    </Card>
  );
}

function VoiceDialog({ target, onClose, onSaved }: { target?: number; onClose: () => void; onSaved: () => void }) {
  const [v, setV] = useState<VoiceSession>({ at: Date.now(), minutes: 10, exercises: [], results: [], strain: false });
  const [pitch, setPitch] = useState<PitchResult | null>(null);
  useEffect(() => { if (pitch) setV((x) => ({ ...x, pitchAvgHz: Math.round(pitch.avg), pitchMinHz: Math.round(pitch.min), pitchMaxHz: Math.round(pitch.max) })); }, [pitch]);
  const save = async () => {
    try { await api.addVoiceSession(v); toast("Practice saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal wide title="Voice practice" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
      <PitchMeter target={target} onResult={setPitch} />
      {pitch && <p>Average <strong>{Math.round(pitch.avg)} Hz</strong>, from {Math.round(pitch.min)} to {Math.round(pitch.max)} Hz.</p>}
      <div className="three-col">
        <Field label="Average (Hz)"><input inputMode="numeric" value={v.pitchAvgHz ?? ""} onChange={(e) => setV({ ...v, pitchAvgHz: Number(e.target.value) || undefined })} /></Field>
        <Field label="Lowest"><input inputMode="numeric" value={v.pitchMinHz ?? ""} onChange={(e) => setV({ ...v, pitchMinHz: Number(e.target.value) || undefined })} /></Field>
        <Field label="Highest"><input inputMode="numeric" value={v.pitchMaxHz ?? ""} onChange={(e) => setV({ ...v, pitchMaxHz: Number(e.target.value) || undefined })} /></Field>
      </div>
      <div className="two-col">
        <Field label="Minutes"><input inputMode="numeric" value={v.minutes} onChange={(e) => setV({ ...v, minutes: Number(e.target.value) || 0 })} /></Field>
        <Field label="When"><DateTimeInput value={v.at} onChange={(at) => setV({ ...v, at })} /></Field>
      </div>
      <ChipPicker label="Exercises" options={VOICE_EXERCISES} value={v.exercises} onChange={(exercises) => setV({ ...v, exercises })} />
      <ScaleRow label="How it felt" low="Hard" high="Great" value={v.feeling} onChange={(feeling) => setV({ ...v, feeling })} />
    </Modal>
  );
}

function HairRemoval({ readOnly }: { readOnly: boolean }) {
  const list = useLoad(() => api.hairRemoval(365).catch(() => [] as HairRemovalSession[]), []);
  const [adding, setAdding] = useState(false);
  const items = list.data ?? [];
  const byArea = HAIR_AREAS.map((a) => ({ a, n: items.filter((h) => h.area === a).length })).filter((x) => x.n > 0);
  const others = [...new Set(items.map((h) => h.area))].filter((a) => !HAIR_AREAS.includes(a)).map((a) => ({ a, n: items.filter((h) => h.area === a).length }));
  return (
    <Card title="Hair removal" icon={<Scissors size={18} />} action={!readOnly && <CardAction label="Log a hair removal session" onClick={() => setAdding(true)}><Plus size={18} /></CardAction>}>
      {items.length === 0 ? <p className="muted">Laser, electrolysis and the rest: sessions by area, with how many hours or pulses, so you can see how far along you are.</p> : (
        <>
          <div className="tag-list">{[...byArea, ...others].map((x) => <span key={x.a} className="chip is-static">{x.a} <span className="muted">{x.n}</span></span>)}</div>
          <ul className="rows">
            {[...items].sort((a, b) => b.at - a.at).slice(0, 5).map((h) => (
              <li key={h.id} className="row">
                <div className="row-main"><div className="row-title">{h.area} · {HAIR_METHODS.find((m) => m.id === h.method)?.label}</div>
                  <div className="row-sub">{when(h.at)}{h.minutes ? ` · ${h.minutes} min` : ""}{h.pulses ? ` · ${num(h.pulses)} pulses` : ""}{h.pain != null ? ` · pain ${h.pain}/10` : ""}</div></div>
              </li>
            ))}
          </ul>
        </>
      )}
      {list.loading && !list.data && <Spinner />}
      {adding && <HairDialog onClose={() => setAdding(false)} onSaved={list.reload} />}
    </Card>
  );
}

function HairDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [h, setH] = useState<HairRemovalSession>({ at: Date.now(), method: "LASER", area: "face" });
  const save = async () => {
    try { await api.addHairRemoval(h); toast("Session saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal title="Hair removal session" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
      <OneOf label="How" options={HAIR_METHODS} value={h.method} onChange={(method) => setH({ ...h, method: method ?? "LASER" })} />
      <ChipPicker label="Area" multi={false} options={HAIR_AREAS} value={[h.area]} onChange={(v) => setH({ ...h, area: v[0] ?? "face" })} />
      <div className="three-col">
        <Field label="Minutes"><input inputMode="numeric" value={h.minutes ?? ""} onChange={(e) => setH({ ...h, minutes: Number(e.target.value) || undefined })} /></Field>
        <Field label="Pulses"><input inputMode="numeric" value={h.pulses ?? ""} onChange={(e) => setH({ ...h, pulses: Number(e.target.value) || undefined })} /></Field>
        <Field label="Cost"><input inputMode="decimal" value={h.cost ?? ""} onChange={(e) => setH({ ...h, cost: Number(e.target.value) || undefined })} /></Field>
      </div>
      <Field label="Where, or who"><input value={h.provider ?? ""} onChange={(e) => setH({ ...h, provider: e.target.value || undefined })} /></Field>
      <ScaleRow label="Pain" min={0} max={10} value={h.pain} onChange={(pain) => setH({ ...h, pain })} />
      <Field label="When"><DateTimeInput value={h.at} onChange={(at) => setH({ ...h, at })} /></Field>
    </Modal>
  );
}

export function EmptyTransition() {
  return <Empty icon={<Sparkles size={32} />} title="Transition">Turn on Transition in Settings › Features to keep milestones, changes and moments here.</Empty>;
}
