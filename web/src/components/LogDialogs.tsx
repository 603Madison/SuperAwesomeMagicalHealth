import { useEffect, useState } from "react";
import { Activity, BatteryLow, Droplet, Dumbbell, Egg, Flame, Footprints, Heart, Moon, Scale, Sparkles, Thermometer, Zap, type LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { localInput, toKg, useUnits, weightValue, WORKOUT_TYPES } from "../lib/format";
import type { CheckIn, Workout, WorkoutType } from "../lib/types";
import { Button, errorToast, Field, Modal, toast } from "./ui";

/** The insights' icons, by the names the server gives them. */
export const INSIGHT_ICONS: Record<string, LucideIcon> = {
  zap: Zap, "battery-low": BatteryLow, heart: Heart, activity: Activity, thermometer: Thermometer, moon: Moon,
  footprints: Footprints, dumbbell: Dumbbell, egg: Egg, flame: Flame, droplet: Droplet, scale: Scale, sparkles: Sparkles,
};

export function WeightDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const units = useUnits();
  const [value, setValue] = useState("");
  const [fat, setFat] = useState("");
  const [at, setAt] = useState(localInput(Date.now()));
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    // Starts from the last weight, which is usually close.
    api.measurements("WEIGHT", 365).then((list) => list[0] && setValue(weightValue(list[0].value, units).toFixed(1)), () => undefined);
  }, [units]);
  const save = async () => {
    const n = Number(value.replace(",", "."));
    if (!n) return;
    setBusy(true);
    try {
      const when = new Date(at).getTime();
      await api.addMeasurement("WEIGHT", Math.round(toKg(n, units) * 100) / 100, when);
      if (fat) await api.addMeasurement("BODY_FAT", Number(fat.replace(",", ".")), when);
      toast("Weight saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Log weight" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="two-col">
          <Field label={units === "IMPERIAL" ? "Weight (lb)" : "Weight (kg)"}><input autoFocus inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)} required /></Field>
          <Field label="Body fat (%)" help="If your scale tells you"><input inputMode="decimal" value={fat} onChange={(e) => setFat(e.target.value)} /></Field>
        </div>
        <Field label="When"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

export function WorkoutDialog({ workout, onClose, onSaved }: { workout?: Workout; onClose: () => void; onSaved: () => void }) {
  const units = useUnits();
  const [type, setType] = useState<WorkoutType>(workout?.type ?? "WALK");
  const [name, setName] = useState(workout?.name ?? "");
  const [start, setStart] = useState(localInput(workout?.start ?? Date.now() - 45 * 60_000));
  const [minutes, setMinutes] = useState(String(workout ? Math.round((workout.end - workout.start) / 60_000) : 45));
  const [dist, setDist] = useState(workout?.distanceM ? String(units === "IMPERIAL" ? (workout.distanceM / 1609.344).toFixed(2) : (workout.distanceM / 1000).toFixed(2)) : "");
  const [kcal, setKcal] = useState(workout?.activeCalories ? String(Math.round(workout.activeCalories)) : "");
  const [effort, setEffort] = useState(workout?.effort ?? 0);
  const [note, setNote] = useState(workout?.note ?? "");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    const begin = new Date(start).getTime();
    const length = Number(minutes);
    if (!begin || !length) return;
    setBusy(true);
    try {
      const d = Number(dist.replace(",", "."));
      await api.saveWorkout({
        id: workout?.id, type, start: begin, end: begin + length * 60_000, name: name.trim() || undefined,
        distanceM: d ? (units === "IMPERIAL" ? d * 1609.344 : d * 1000) : undefined,
        activeCalories: Number(kcal) || undefined, effort: effort || undefined, note: note.trim() || undefined,
      });
      toast(workout ? "Workout saved" : "Workout logged");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={workout ? "Edit workout" : "Log a workout"} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="two-col">
          <Field label="What">
            <select value={type} onChange={(e) => setType(e.target.value as WorkoutType)}>
              {WORKOUT_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
            </select>
          </Field>
          <Field label="Name" help="Optional, e.g. Leg day"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        </div>
        <div className="two-col">
          <Field label="Started"><input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} required /></Field>
          <Field label="Minutes"><input inputMode="numeric" value={minutes} onChange={(e) => setMinutes(e.target.value)} required /></Field>
        </div>
        <div className="two-col">
          <Field label={units === "IMPERIAL" ? "Distance (mi)" : "Distance (km)"}><input inputMode="decimal" value={dist} onChange={(e) => setDist(e.target.value)} /></Field>
          <Field label="Active calories"><input inputMode="numeric" value={kcal} onChange={(e) => setKcal(e.target.value)} /></Field>
        </div>
        <Field label={`How hard it felt${effort ? `: ${effort} of 10` : ""}`}>
          <input type="range" min={0} max={10} value={effort} onChange={(e) => setEffort(Number(e.target.value))} style={{ ["--fill" as string]: `${effort * 10}%` }} aria-label="Effort, 1 to 10" />
        </Field>
        <Field label="Note"><textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

export function SleepDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const lastNight = new Date();
  lastNight.setHours(23, 0, 0, 0);
  lastNight.setDate(lastNight.getDate() - 1);
  const [bed, setBed] = useState(localInput(lastNight.getTime()));
  const [wake, setWake] = useState(localInput(lastNight.getTime() + 8 * 3_600_000));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.addSleep(new Date(bed).getTime(), new Date(wake).getTime(), note.trim() || undefined);
      toast("Night saved");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Add a night" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <p className="muted">For nights no device recorded. Without stages, the sleep score counts your time asleep and when you slept.</p>
      <div className="two-col">
        <Field label="Went to bed"><input type="datetime-local" value={bed} onChange={(e) => setBed(e.target.value)} /></Field>
        <Field label="Woke up"><input type="datetime-local" value={wake} onChange={(e) => setWake(e.target.value)} /></Field>
      </div>
      <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
    </Modal>
  );
}

const FACES = ["😣", "🙁", "😐", "🙂", "😄"];
const SUGGESTED_TAGS = ["alcohol", "caffeine late", "late meal", "stress", "travel", "sick", "period", "meditation", "sauna", "nap", "screen late", "great workout"];

export function CheckInDialog({ date, initial, onClose, onSaved }: { date: string; initial?: CheckIn; onClose: () => void; onSaved: (c: CheckIn) => void }) {
  const [check, setCheck] = useState<CheckIn>(initial ?? { date, tags: [] });
  const [custom, setCustom] = useState("");
  const [busy, setBusy] = useState(false);
  const scale = (key: "mood" | "energy" | "stress" | "soreness", label: string, faces: string[]) => (
    <Field label={label}>
      <div className="scale" role="radiogroup" aria-label={label}>
        {faces.map((f, i) => (
          <button key={i} type="button" role="radio" aria-checked={check[key] === i + 1} aria-label={`${i + 1} of 5`} className={check[key] === i + 1 ? "is-on" : ""}
            onClick={() => setCheck({ ...check, [key]: check[key] === i + 1 ? undefined : i + 1 })}>{f}</button>
        ))}
      </div>
    </Field>
  );
  const toggleTag = (tag: string) => setCheck({ ...check, tags: check.tags.includes(tag) ? check.tags.filter((t) => t !== tag) : [...check.tags, tag] });
  const save = async () => {
    setBusy(true);
    try {
      const saved = await api.saveCheckIn({ ...check, date });
      toast("Checked in");
      onSaved(saved);
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="How are you feeling?" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      {scale("mood", "Mood", FACES)}
      {scale("energy", "Energy", ["🪫", "😴", "🙂", "💪", "⚡"])}
      {scale("stress", "Stress", ["😌", "🙂", "😐", "😬", "🤯"])}
      {scale("soreness", "Soreness", ["✨", "🙂", "😐", "😖", "🥵"])}
      <Field label="Tags" help="Tags help spot what affects your sleep and readiness">
        <div className="tag-list">
          {[...new Set([...SUGGESTED_TAGS, ...check.tags])].map((t) => (
            <button key={t} type="button" className={`chip ${check.tags.includes(t) ? "is-on" : ""}`} onClick={() => toggleTag(t)} aria-pressed={check.tags.includes(t)}>{t}</button>
          ))}
        </div>
      </Field>
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); if (custom.trim()) { toggleTag(custom.trim().toLowerCase()); setCustom(""); } }}>
        <Field label="Your own tag"><input value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="e.g. hiking" /></Field>
        <Button type="submit">Add</Button>
      </form>
      <Field label="Note"><textarea rows={2} value={check.note ?? ""} onChange={(e) => setCheck({ ...check, note: e.target.value })} /></Field>
    </Modal>
  );
}
