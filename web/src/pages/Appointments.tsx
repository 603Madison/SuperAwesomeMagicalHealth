import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { CalendarClock, CalendarPlus, Check, FileText, MapPin, MessageCircleQuestion, Plus, Sparkles, Trash2, UserRound, X } from "lucide-react";
import { api } from "../lib/api";
import { clock, isoDate, localInput, shiftDate, today } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import type { Appointment, AppointmentQuestion, SuggestedQuestion } from "../lib/types";
import { Button, Card, Empty, ErrorNotice, Field, Modal, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";

const REMINDERS: { minutes: number; label: string }[] = [
  { minutes: 2880, label: "2 days before" }, { minutes: 1440, label: "A day before" }, { minutes: 180, label: "3 hours before" },
  { minutes: 60, label: "An hour before" }, { minutes: 30, label: "30 minutes before" },
];

/** "Today, 2:30 PM", "Tomorrow, 9:00 AM", "Thu 14 Nov, 10:15 AM". */
export function appointmentWhen(at: number): string {
  const date = isoDate(new Date(at));
  const day = date === today() ? "Today" : date === shiftDate(today(), 1) ? "Tomorrow"
    : new Date(at).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: new Date(at).getFullYear() === new Date().getFullYear() ? undefined : "numeric" });
  return `${day}, ${clock(at)}`;
}

function inDays(at: number): string {
  const days = Math.round((new Date(isoDate(new Date(at))).getTime() - new Date(today()).getTime()) / 86_400_000);
  return days <= 0 ? "Today" : days === 1 ? "Tomorrow" : days < 14 ? `In ${days} days` : `In ${Math.round(days / 7)} weeks`;
}

export function Appointments() {
  const readOnly = useReadOnly();
  const { data, error, loading, reload } = useLoad(() => api.appointments(730), []);
  const [editing, setEditing] = useState<Appointment | "new" | null>(null);
  const now = Date.now();
  const upcoming = (data ?? []).filter((a) => a.at + (a.durationMinutes ?? 60) * 60_000 >= now).sort((a, b) => a.at - b.at);
  const past = (data ?? []).filter((a) => a.at + (a.durationMinutes ?? 60) * 60_000 < now).sort((a, b) => b.at - a.at);

  return (
    <div className="page">
      <PageHeader title="Appointments" subtitle="Questions ready before, notes after" actions={!readOnly && <Button variant="primary" onClick={() => setEditing("new")}><CalendarPlus size={18} /> Add an appointment</Button>} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && upcoming.length === 0 && (
        <Card>
          <Empty icon={<CalendarClock size={36} />} title="Nothing coming up">
            Add an appointment to get a reminder, and a list of questions to take with you, with suggestions from what you've logged.
          </Empty>
        </Card>
      )}
      {upcoming.length > 0 && (
        <div className="grid grid-2">
          {upcoming.map((a) => {
            const done = a.questions.filter((q) => q.done).length;
            return (
              <Card key={a.id} className="appointment-card" onClick={() => setEditing(a)}>
                <div className="appointment-when">
                  <span className="tone-chip tone-info">{inDays(a.at)}</span>
                  <span className="muted small">{appointmentWhen(a.at)}{a.durationMinutes ? ` · ${a.durationMinutes} min` : ""}</span>
                </div>
                <h3>{a.title}</h3>
                <div className="muted small appointment-meta">
                  {a.with && <span><UserRound size={14} /> {a.with}</span>}
                  {a.place && <span><MapPin size={14} /> {a.place}</span>}
                </div>
                <p className="small"><MessageCircleQuestion size={14} style={{ verticalAlign: -2 }} /> {a.questions.length === 0 ? "No questions yet" : `${a.questions.length} ${a.questions.length === 1 ? "question" : "questions"}${done ? `, ${done} asked` : ""}`}</p>
              </Card>
            );
          })}
        </div>
      )}
      {past.length > 0 && (
        <>
          <h2 className="section-title">Before</h2>
          <Card>
            <ul className="rows">
              {past.map((a) => (
                <li key={a.id} className="row">
                  <div className="row-icon"><CalendarClock size={18} /></div>
                  <button type="button" className="row-main link-row" onClick={() => setEditing(a)}>
                    <div className="row-title">{a.title}{a.with ? ` · ${a.with}` : ""}</div>
                    <div className="row-sub">{appointmentWhen(a.at)}{a.notes ? ` · ${a.notes.slice(0, 90)}${a.notes.length > 90 ? "…" : ""}` : readOnly ? "" : " · add notes"}</div>
                  </button>
                </li>
              ))}
            </ul>
          </Card>
        </>
      )}
      {editing && <AppointmentDialog appointment={editing === "new" ? undefined : editing} past={past} readOnly={readOnly} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

function AppointmentDialog({ appointment, past, readOnly, onClose, onSaved }: { appointment?: Appointment; past: Appointment[]; readOnly: boolean; onClose: () => void; onSaved: () => void }) {
  const [a, setA] = useState<Appointment>(appointment ?? { at: nextHour(), title: "", questions: [], remindBefore: [1440, 60] });
  const [at, setAt] = useState(localInput(a.at));
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const suggestions = useLoad<SuggestedQuestion[]>(() => (appointment?.id && !readOnly ? api.appointmentSuggestions(appointment.id) : Promise.resolve([])), [appointment?.id]);
  const isPast = new Date(at).getTime() < Date.now();
  const offered = (suggestions.data ?? []).filter((s) => !a.questions.some((q) => q.text === s.text));
  const setQ = (questions: AppointmentQuestion[]) => setA({ ...a, questions });
  const addQuestion = () => { const t = question.trim(); if (t) { setQ([...a.questions, { text: t, done: false, suggested: false }]); setQuestion(""); } };
  const previous = past.find((p) => p.at < new Date(at).getTime() && p.id !== appointment?.id);
  const save = async () => {
    if (!a.title.trim()) return;
    setBusy(true);
    try {
      await api.saveAppointment({ ...a, title: a.title.trim(), at: new Date(at).getTime() });
      toast("Saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  const remove = async () => {
    try { await api.deleteAppointment(appointment!.id!); toast("Appointment deleted"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  if (readOnly) {
    return (
      <Modal title={a.title} onClose={onClose}>
        <p className="muted">{appointmentWhen(a.at)}{a.with ? ` · ${a.with}` : ""}{a.place ? ` · ${a.place}` : ""}</p>
        {a.questions.length > 0 && <ul className="question-list">{a.questions.map((q, i) => <li key={i} className={q.done ? "is-done" : ""}><Check size={16} /> {q.text}</li>)}</ul>}
        {a.notes && <p>{a.notes}</p>}
      </Modal>
    );
  }
  return (
    <Modal wide title={appointment ? a.title || "Appointment" : "Add an appointment"} onClose={onClose} footer={<>
      {appointment && <Button variant="ghost" onClick={remove}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!a.title.trim()} onClick={save}>Save</Button>
    </>}>
      <div className="two-col">
        <Field label="What"><input autoFocus={!appointment} value={a.title} onChange={(e) => setA({ ...a, title: e.target.value })} placeholder="e.g. Check-up" /></Field>
        <Field label="With" help="Optional"><input value={a.with ?? ""} onChange={(e) => setA({ ...a, with: e.target.value || undefined })} placeholder="e.g. Dr Patel" /></Field>
      </div>
      <div className="two-col">
        <Field label="When"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
        <Field label="Where" help="Optional"><input value={a.place ?? ""} onChange={(e) => setA({ ...a, place: e.target.value || undefined })} placeholder="e.g. Riverside clinic, or video call" /></Field>
      </div>
      <Field label="Remind me">
        <div className="tag-list">
          {REMINDERS.map((r) => {
            const on = a.remindBefore.includes(r.minutes);
            return <button key={r.minutes} type="button" className={`chip ${on ? "is-on" : ""}`} aria-pressed={on} onClick={() => setA({ ...a, remindBefore: on ? a.remindBefore.filter((m) => m !== r.minutes) : [...a.remindBefore, r.minutes].sort((x, y) => y - x) })}>{r.label}</button>;
          })}
        </div>
      </Field>

      <h3>Questions to ask</h3>
      {a.questions.length > 0 && (
        <ul className="question-list">
          {a.questions.map((q, i) => (
            <li key={i} className={q.done ? "is-done" : ""}>
              <button type="button" className={`question-check ${q.done ? "is-on" : ""}`} aria-pressed={q.done} aria-label={q.done ? `Asked: ${q.text}` : `Mark as asked: ${q.text}`} onClick={() => setQ(a.questions.map((x, k) => (k === i ? { ...x, done: !x.done } : x)))}><Check size={14} /></button>
              <span>{q.text}{q.suggested && <Sparkles size={13} className="suggested-mark" aria-label="Suggested from your logs" />}</span>
              <button type="button" className="icon-btn" aria-label={`Remove: ${q.text}`} onClick={() => setQ(a.questions.filter((_, k) => k !== i))}><X size={16} /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); addQuestion(); }}>
        <Field label="A question"><input value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="e.g. Could my new tablets be why I'm tired?" /></Field>
        <Button type="submit" disabled={!question.trim()}><Plus size={16} /> Add</Button>
      </form>

      <div className="suggestions">
        <h4><Sparkles size={16} /> Suggested from your logs</h4>
        {!appointment?.id ? <p className="muted small">Save the appointment, and questions worth asking show here, from what you've logged since your last one.</p>
          : suggestions.loading ? <Spinner /> : offered.length === 0 ? <p className="muted small">Nothing stands out since your last appointment.</p> : (
            <ul className="rows">
              {offered.map((s) => (
                <li key={s.text} className="row">
                  <div className="row-main"><div className="row-title">{s.text}</div><div className="row-sub">{s.why}</div></div>
                  <Button onClick={() => setQ([...a.questions, { text: s.text, done: false, suggested: true }])}><Plus size={16} /> Add</Button>
                </li>
              ))}
            </ul>
          )}
      </div>

      <div className="button-row">
        <Button variant="ghost" onClick={() => navigate(`/reports?from=${previous ? isoDate(new Date(previous.at)) : shiftDate(today(), -90)}&to=${today()}`)}><FileText size={16} /> A report to bring</Button>
      </div>

      <Field label={isPast ? "Notes" : "Notes (afterwards)"} help="What was said, what was decided, what's next.">
        <textarea rows={isPast ? 5 : 3} value={a.notes ?? ""} onChange={(e) => setA({ ...a, notes: e.target.value || undefined })} />
      </Field>
    </Modal>
  );
}

function nextHour(): number {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(10, 0, 0, 0);
  return d.getTime();
}
