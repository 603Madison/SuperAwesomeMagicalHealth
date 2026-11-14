import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import {
  Apple, BedDouble, BookOpen, Brain, Check, Coffee, Droplet, Dumbbell, Flame, Footprints, Heart, Leaf, ListChecks, Minus, Moon, MoreHorizontal,
  Music, Pause, Pencil, PenLine, Pill, Play, Plus, Smile, Sparkles, Sun, Trash2, Wind, WineOff,
} from "lucide-react";
import { api } from "../lib/api";
import { dayLabel, useDayPicker } from "../lib/format";
import { useReadOnly } from "../lib/viewing";
import type { Habit, HabitSource, HabitStatus } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, Card, DateNav, Empty, ErrorNotice, Field, Menu, Modal, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { useWater } from "../lib/fields";
import { useModules } from "../App";

/** The icons a habit can have, by the names the server keeps. */
export const HABIT_ICONS: Record<string, LucideIcon> = {
  sparkles: Sparkles, dumbbell: Dumbbell, footprints: Footprints, droplet: Droplet, moon: Moon, "bed-double": BedDouble, "book-open": BookOpen,
  brain: Brain, heart: Heart, leaf: Leaf, sun: Sun, coffee: Coffee, pill: Pill, wind: Wind, music: Music, "pen-line": PenLine, apple: Apple,
  smile: Smile, "wine-off": WineOff, "list-checks": ListChecks,
};

export const HABIT_SOURCES: { id: HabitSource; label: string; about: string; icon: string }[] = [
  { id: "MANUAL", label: "I'll tick it", about: "You tick it off yourself.", icon: "sparkles" },
  { id: "STEPS_GOAL", label: "Reach my step goal", about: "Ticks itself when your steps reach your goal.", icon: "footprints" },
  { id: "SLEEP_GOAL", label: "Sleep my sleep goal", about: "Ticks itself after a night as long as your goal.", icon: "bed-double" },
  { id: "WATER_GOAL", label: "Drink my water goal", about: "Ticks itself when your water reaches your goal.", icon: "droplet" },
  { id: "WORKOUT", label: "Work out", about: "Ticks itself when a workout is recorded or logged.", icon: "dumbbell" },
  { id: "MOOD_LOGGED", label: "Log my mood", about: "Ticks itself when you log a mood in Mind.", icon: "smile" },
  { id: "JOURNAL", label: "Write in my journal", about: "Ticks itself when you write in your journal.", icon: "pen-line" },
  { id: "MINDFUL", label: "A calm practice", about: "Ticks itself after breathing, grounding or meditation.", icon: "wind" },
  { id: "MEDS_TAKEN", label: "Take all my medications", about: "Ticks itself when every dose of the day is marked taken.", icon: "pill" },
  { id: "PELVIC_FLOOR", label: "Pelvic floor exercises", about: "Ticks itself when you log a pelvic floor session.", icon: "heart" },
  { id: "NO_ALCOHOL", label: "No alcohol", about: "Done on days with no alcohol logged.", icon: "wine-off" },
  { id: "CAFFEINE_CUTOFF", label: "No caffeine after 2 pm", about: "Done on days without caffeine logged after 2 pm.", icon: "coffee" },
];

export function habitIcon(name: string): LucideIcon {
  return HABIT_ICONS[name] ?? Sparkles;
}

export function Habits() {
  // Today until another day is picked; left open overnight, it moves on.
  const [date, setDate, now] = useDayPicker();
  const readOnly = useReadOnly();
  const status = useLoad(() => api.habitsToday(date), [date]);
  const all = useLoad(() => api.habits(true), []);
  const [params, setParams] = useSearchParams();
  // Today's "Add a habit" comes here with ?new=1.
  const [editing, setEditing] = useState<Habit | "new" | null>(() => (params.get("new") && !readOnly ? "new" : null));
  useEffect(() => { if (params.get("new")) setParams({}, { replace: true }); }, [params, setParams]);
  const reload = () => { status.reload(); all.reload(); };
  const paused = (all.data ?? []).filter((h) => !h.active);
  const list = status.data ?? [];

  const sections: Section[] = [
    { id: "habits", label: "Your habits", node: status.data && (list.length === 0 ? (
      <Card className="span-2">
        <Empty icon={<ListChecks size={28} />} title="Start with one small habit">
          A ten-minute walk, two minutes of breathing, a page of a book. Choose how many days a week to aim for: streaks count weeks that reach it.
        </Empty>
      </Card>
    ) : (
      <>{list.map((s) => <HabitCard key={s.habit.id} status={s} date={date} readOnly={readOnly} onChanged={status.reload} onEdit={() => setEditing(s.habit)} />)}</>
    )) },
    { id: "paused", label: "Paused", node: paused.length > 0 && (
      <Card title="Paused" icon={<Pause size={18} />} className="span-2">
        <ul className="rows">
          {paused.map((h) => {
            const Icon = habitIcon(h.icon);
            return (
              <li key={h.id} className="row is-off">
                <div className="row-icon"><Icon size={18} /></div>
                <div className="row-main"><div className="row-title">{h.name}</div><div className="row-sub">{HABIT_SOURCES.find((x) => x.id === h.source)?.label} · {h.timesPerWeek} days a week</div></div>
                {!readOnly && <Button variant="ghost" onClick={async () => { try { await api.saveHabit({ ...h, active: true }); reload(); } catch (e) { errorToast(e); } }}><Play size={16} /> Start again</Button>}
              </li>
            );
          })}
        </ul>
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("habits"), "Habits", sections);

  return (
    <div className="page">
      <PageHeader title="Habits" subtitle="Small things, most days. A rest day never breaks a streak." actions={<>
        <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={now} />
        {!readOnly && <Button variant="primary" onClick={() => setEditing("new")}><Plus size={18} /> New habit</Button>}
        {arranged.button}
      </>} />
      {status.error && <ErrorNotice message={status.error} onRetry={status.reload} />}
      {!status.data && status.loading && <div className="center-pad"><Spinner size={28} /></div>}
      <div style={{ opacity: status.loading ? 0.6 : 1 }}>{arranged.render()}</div>
      {editing && <HabitDialog habit={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

/** One habit: today's tick, the week, the streak and the last two weeks. */
export function HabitCard({ status, date, readOnly, onChanged, onEdit, compact }: {
  status: HabitStatus; date: string; readOnly: boolean; onChanged: () => void; onEdit?: () => void; compact?: boolean;
}) {
  const h = status.habit;
  const Icon = habitIcon(h.icon);
  const manual = h.source === "MANUAL";
  const [busy, setBusy] = useState(false);
  const tick = async (count: number) => {
    setBusy(true);
    try {
      await api.tickHabit(h.id!, { date, count: Math.max(0, count) });
      if (count >= h.perDay && status.count < h.perDay) toast(status.streakDays > 0 ? `${h.name}: done. ${status.streakDays + 1} days and counting.` : `${h.name}: done`);
      onChanged();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const tickButton = manual && !readOnly && (
    h.perDay > 1 ? (
      <div className="habit-counter" role="group" aria-label={`${h.name}: ${status.count} of ${h.perDay}`}>
        <button type="button" className="icon-btn" aria-label="One less" disabled={busy || status.count === 0} onClick={() => tick(status.count - 1)}><Minus size={18} /></button>
        <span>{status.count}/{h.perDay}</span>
        <button type="button" className="icon-btn" aria-label="One more" disabled={busy} onClick={() => tick(status.count + 1)}><Plus size={18} /></button>
      </div>
    ) : (
      <button type="button" className={`habit-tick ${status.done ? "is-done" : ""}`} aria-pressed={status.done} aria-label={status.done ? `${h.name}: done. Untick it` : `${h.name}: tick it off`} disabled={busy} onClick={() => tick(status.done ? 0 : 1)}>
        <Check size={22} />
      </button>
    )
  );
  if (compact) {
    return (
      <li className="row">
        <div className={`row-icon ${status.done ? "is-done" : ""}`}>{status.done ? <Check size={18} /> : <Icon size={18} />}</div>
        <div className="row-main">
          <div className="row-title">{h.name}</div>
          <div className="row-sub">{status.weekDone} of {h.timesPerWeek} this week{status.streakWeeks > 1 ? ` · ${status.streakWeeks}-week streak` : ""}{!manual && !status.done ? " · ticks itself" : ""}</div>
        </div>
        {tickButton}
      </li>
    );
  }
  return (
    <Card className="habit-card">
      <div className="habit-top">
        <div className={`habit-icon ${status.done ? "is-done" : ""}`}><Icon size={22} /></div>
        <div className="habit-name">
          <strong>{h.name}</strong>
          <span className="muted small">{manual ? `${h.timesPerWeek === 7 ? "Every day" : `${h.timesPerWeek} days a week`}${h.reminderTime ? ` · nudge at ${h.reminderTime}` : ""}` : HABIT_SOURCES.find((x) => x.id === h.source)?.about}</span>
        </div>
        {tickButton}
        {!manual && <span className={`tone-chip ${status.done ? "tone-good" : "tone-info"}`}>{status.done ? "Done" : "Not yet"}</span>}
        {onEdit && !readOnly && <Menu label={`More for ${h.name}`} trigger={<MoreHorizontal size={18} />} items={[
          { label: "Edit", icon: <Pencil size={18} />, onSelect: onEdit },
          { label: "Pause", icon: <Pause size={18} />, onSelect: async () => { try { await api.saveHabit({ ...h, active: false }); onChanged(); } catch (e) { errorToast(e); } } },
        ]} />}
      </div>
      <div className="habit-week">
        <span className="small"><strong>{status.weekDone}</strong> of {h.timesPerWeek} this week</span>
        <Meter value={status.weekDone} max={h.timesPerWeek} color="var(--c2)" label={`${status.weekDone} of ${h.timesPerWeek} days this week`} />
      </div>
      <div className="habit-foot">
        <span className={`streak ${status.streakWeeks > 0 ? "is-on" : ""}`} title="Weeks in a row that reached your aim">
          <Flame size={16} /> {status.streakWeeks > 0 ? `${status.streakWeeks} ${status.streakWeeks === 1 ? "week" : "weeks"}` : "No streak yet"}
          {status.streakDays > 1 && <span className="muted"> · {status.streakDays} days</span>}
        </span>
        <span className="habit-strip" role="img" aria-label={`Last 14 days: ${status.recent.filter((d) => d === true).length} done`}>
          {status.recent.map((d, i) => <i key={i} className={d == null ? "is-none" : d ? "is-done" : "is-missed"} />)}
        </span>
      </div>
    </Card>
  );
}

function HabitDialog({ habit, onClose, onSaved }: { habit?: Habit; onClose: () => void; onSaved: () => void }) {
  const [h, setH] = useState<Habit>(habit ?? { name: "", icon: "sparkles", source: "MANUAL", timesPerWeek: 7, perDay: 1, active: true });
  const [busy, setBusy] = useState(false);
  const manual = h.source === "MANUAL";
  const water = useWater(useModules());
  const sources = HABIT_SOURCES.filter((x) => x.id !== "WATER_GOAL" || water || habit?.source === "WATER_GOAL");
  const save = async () => {
    if (!h.name.trim()) return;
    setBusy(true);
    try { await api.saveHabit({ ...h, name: h.name.trim() }); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  const remove = async () => {
    if (!habit?.id) return;
    try { await api.deleteHabit(habit.id); toast("Habit deleted"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal wide title={habit ? `Edit ${habit.name}` : "New habit"} onClose={onClose} footer={<>
      {habit && <Button variant="ghost" onClick={remove}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!h.name.trim()} onClick={save}>Save</Button>
    </>}>
      <Field label="How it's ticked">
        <select value={h.source} onChange={(e) => {
          const source = e.target.value as HabitSource;
          const s = HABIT_SOURCES.find((x) => x.id === source)!;
          setH({ ...h, source, icon: s.icon, name: !h.name || HABIT_SOURCES.some((x) => x.label === h.name) ? (source === "MANUAL" ? "" : s.label) : h.name });
        }}>
          {sources.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
        </select>
      </Field>
      <Field label="Name"><input autoFocus value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} placeholder="e.g. A ten-minute walk" maxLength={60} /></Field>
      <Field label="Icon">
        <div className="icon-picker" role="radiogroup" aria-label="Icon">
          {Object.entries(HABIT_ICONS).map(([name, Icon]) => (
            <button key={name} type="button" role="radio" aria-checked={h.icon === name} aria-label={name.replace("-", " ")} className={h.icon === name ? "is-on" : ""} onClick={() => setH({ ...h, icon: name })}><Icon size={18} /></button>
          ))}
        </div>
      </Field>
      <Field label={`Days a week to aim for: ${h.timesPerWeek}`} help="Streaks count weeks that reach this, so a rest day never breaks one.">
        <input type="range" min={1} max={7} value={h.timesPerWeek} onChange={(e) => setH({ ...h, timesPerWeek: Number(e.target.value) })} style={{ ["--fill" as string]: `${((h.timesPerWeek - 1) / 6) * 100}%` }} aria-label="Days a week" />
      </Field>
      <div className="two-col">
        {manual && <Field label="Times a day" help="Ticks that count as done, e.g. 3 short walks"><input inputMode="numeric" value={h.perDay} onChange={(e) => setH({ ...h, perDay: Math.max(1, Math.min(20, Number(e.target.value) || 1)) })} /></Field>}
        <Field label="A nudge at" help="If it isn't done yet; optional">
          <input type="time" value={h.reminderTime ?? ""} onChange={(e) => setH({ ...h, reminderTime: e.target.value || undefined })} />
        </Field>
      </div>
      {habit && <Toggle label="Active" help="Paused habits keep their history." checked={h.active} onChange={(active) => setH({ ...h, active })} />}
    </Modal>
  );
}
