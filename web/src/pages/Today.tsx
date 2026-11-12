import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Activity as ActivityIcon, CalendarHeart, Check, Droplet, Dumbbell, GlassWater, Heart, HeartPulse, Moon, Pill, Plus, Salad, Smile, Sparkles, Watch, Weight } from "lucide-react";
import { useModules } from "../App";
import { FACES, MoodDialog } from "./Mind";
import { api } from "../lib/api";
import { clock, dayLabel, distance, hm, num, today, useUnits, weight, workoutLabel } from "../lib/format";
import { useSession } from "../lib/session";
import type { DaySummary, Score } from "../lib/types";
import { BarChart, Hypnogram, LineChart, Meter, ScoreRing, StageLegend } from "../components/charts";
import { Button, Card, DateNav, Empty, ErrorNotice, Modal, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { CheckInDialog, INSIGHT_ICONS, SleepDialog, WeightDialog, WorkoutDialog } from "../components/LogDialogs";

const RINGS: { key: "readiness" | "sleep" | "activity"; label: string; color: string }[] = [
  { key: "readiness", label: "Readiness", color: "var(--c2)" },
  { key: "sleep", label: "Sleep", color: "var(--c1)" },
  { key: "activity", label: "Activity", color: "var(--c3)" },
];

export function Today() {
  const [date, setDate] = useState(today());
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date]);
  const user = useSession((s) => s.user)!;
  const [open, setOpen] = useState<"weight" | "workout" | "sleep" | "checkin" | null>(null);
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [mood, setMood] = useState<number | null>(null);
  const on = useModules();
  const rings = RINGS.filter((r) => r.key === "readiness" ? on.includes("SLEEP") || on.includes("ACTIVITY") : r.key === "sleep" ? on.includes("SLEEP") : on.includes("ACTIVITY"));
  const m = data?.modules;
  const moduleData = !!(m && (m.mind || m.cycle?.cycleDay != null || m.continence || m.meds));
  const navigate = useNavigate();
  const hour = new Date().getHours();
  const greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  const addWater = async () => {
    try {
      await api.addWater(date, 250);
      toast("250 ml of water added");
      reload();
    } catch (e) { errorToast(e); }
  };

  return (
    <div className="page today">
      <header className="page-header">
        <div>
          <h1>{date === today() ? `${greeting}, ${user.displayName.split(" ")[0]}` : dayLabel(date)}</h1>
          <p className="page-subtitle">{new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}</p>
        </div>
        <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={today()} />
      </header>

      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div style={{ opacity: loading ? 0.6 : 1, transition: "opacity 0.2s" }}>
          {rings.length > 0 && <section className="hero" aria-label="Scores" style={{ gridTemplateColumns: `repeat(${rings.length}, minmax(0, 1fr))` }}>
            {rings.map((r) => {
              const s = data[r.key];
              return (
                <ScoreRing
                  key={r.key} label={r.label} color={r.color} score={s?.score} size={132} sub={s?.label ?? "Not enough data"}
                  onClick={s ? () => setScore({ title: r.label, score: s }) : undefined}
                />
              );
            })}
          </section>}

          <div className="quick">
            {on.includes("NUTRITION") && <button className="chip" onClick={() => navigate(`/food?date=${date}`)}><Salad size={18} /> Log food</button>}
            {on.includes("NUTRITION") && <button className="chip" onClick={addWater}><GlassWater size={18} /> +250 ml water</button>}
            {on.includes("MIND") && <button className="chip" onClick={() => setMood(3)}><Heart size={18} /> Mood</button>}
            {on.includes("CYCLE") && <button className="chip" onClick={() => navigate("/cycle")}><CalendarHeart size={18} /> Period</button>}
            {on.includes("CONTINENCE") && <button className="chip" onClick={() => navigate("/bladder")}><Droplet size={18} /> Bladder</button>}
            {on.includes("BODY") && <button className="chip" onClick={() => setOpen("weight")}><Weight size={18} /> Weight</button>}
            {on.includes("ACTIVITY") && <button className="chip" onClick={() => setOpen("workout")}><Dumbbell size={18} /> Workout</button>}
            {on.includes("SLEEP") && <button className="chip" onClick={() => setOpen("sleep")}><Moon size={18} /> Add a night</button>}
            <button className={`chip ${data.checkIn?.mood ? "is-on" : ""}`} onClick={() => setOpen("checkin")}><Smile size={18} /> Check in</button>
          </div>

          {data.insights.length > 0 && (
            <section className="insights" aria-label="Insights">
              {data.insights.map((i, n) => {
                const Icon = INSIGHT_ICONS[i.icon] ?? Sparkles;
                return (
                  <article key={n} className={`insight tone-${i.tone}`}>
                    <div className="insight-icon"><Icon size={20} /></div>
                    <div><h4>{i.title}</h4><p>{i.body}</p></div>
                  </article>
                );
              })}
            </section>
          )}

          <ModuleCards day={data} date={date} on={on} onChanged={reload} onMood={setMood} />
          {!data.hasData && !moduleData && date === today() ? <Welcome /> : <DayCards day={data} on={on} onOpenScore={(title, s) => setScore({ title, score: s })} />}
        </div>
      )}

      {score && <ScoreDialog title={score.title} score={score.score} onClose={() => setScore(null)} />}
      {open === "weight" && <WeightDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "workout" && <WorkoutDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "sleep" && <SleepDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "checkin" && <CheckInDialog date={date} initial={data?.checkIn} onClose={() => setOpen(null)} onSaved={reload} />}
      {mood != null && <MoodDialog initial={mood} onClose={() => setMood(null)} onSaved={reload} />}
    </div>
  );
}

function ModuleCards({ day, date, on, onChanged, onMood }: { day: DaySummary; date: string; on: string[]; onChanged: () => void; onMood: (n: number) => void }) {
  const navigate = useNavigate();
  const { mind, cycle, continence, meds } = day.modules;
  const showMind = on.includes("MIND");
  if (!showMind && !cycle && !continence && !meds) return null;
  const take = async (medicationId: string, scheduledFor: number, site?: string) => {
    try { await api.logDose({ medicationId, at: Date.now(), status: "TAKEN", scheduledFor, site }); toast("Taken"); onChanged(); } catch (e) { errorToast(e); }
  };
  return (
    <div className="grid grid-2" style={{ marginBottom: 16 }}>
      {meds && meds.due.length > 0 && (
        <Card title="Medications" icon={<Pill size={18} />} action={<Button variant="ghost" onClick={() => navigate("/meds")}>All</Button>}>
          <ul className="rows">
            {meds.due.map((d) => (
              <li key={`${d.medicationId}-${d.scheduledFor}`} className="row">
                <div className={`row-icon ${d.log?.status === "TAKEN" ? "is-done" : ""}`}>{d.log?.status === "TAKEN" ? <Check size={18} /> : <Pill size={18} />}</div>
                <div className="row-main">
                  <div className="row-title">{d.name}{d.dose ? ` · ${d.dose}` : ""}</div>
                  <div className="row-sub">{clock(d.scheduledFor)}{d.log ? ` · ${d.log.status === "TAKEN" ? "taken" : "skipped"}` : d.nextSite ? ` · ${d.nextSite} next` : ""}</div>
                </div>
                {!d.log && date === today() && <Button onClick={() => take(d.medicationId, d.scheduledFor, d.nextSite)}><Check size={16} /> Take</Button>}
              </li>
            ))}
          </ul>
          {meds.adherence7d != null && <p className="muted small">{meds.adherence7d}% of doses taken this week</p>}
        </Card>
      )}
      {showMind && (
        <Card title="Mood" icon={<Heart size={18} />} action={<Button variant="ghost" onClick={() => navigate("/mind")}>Mind</Button>}>
          {mind?.mood != null ? <div className="big-number">{FACES[Math.round(mind.mood) - 1]}<small>{mind.mood.toFixed(1)} of 5 · {mind.entries} {mind.entries === 1 ? "entry" : "entries"}</small></div> : <p className="muted">How are you feeling?</p>}
          <div className="scale" role="group" aria-label="Log your mood">{FACES.map((f, i) => <button key={i} type="button" aria-label={`Mood ${i + 1} of 5`} onClick={() => onMood(i + 1)}>{f}</button>)}</div>
        </Card>
      )}
      {cycle && (
        <Card title="Cycle" icon={<CalendarHeart size={18} />} onClick={() => navigate("/cycle")}>
          <div className="stats">
            <Stat label="Cycle day" value={cycle.cycleDay ?? "–"} sub={cycle.phase === "UNKNOWN" ? "Log a period to start" : cycle.phase === "FERTILE" ? "Fertile window (estimate)" : cycle.phase[0] + cycle.phase.slice(1).toLowerCase()} />
            <Stat label="Next period" value={cycle.daysUntilPeriod == null ? "–" : cycle.daysUntilPeriod === 0 ? "Today" : cycle.daysUntilPeriod > 0 ? `${cycle.daysUntilPeriod} days` : `${-cycle.daysUntilPeriod} days late`} />
          </div>
          {cycle.logged && <p className="muted small">Logged: {[cycle.logged.flow?.toLowerCase(), ...cycle.logged.symptoms].filter(Boolean).join(", ")}</p>}
        </Card>
      )}
      {continence && (
        <Card title="Bladder & bowel" icon={<Droplet size={18} />} onClick={() => navigate("/bladder")}>
          <div className="stats">
            <Stat label="Trips" value={continence.voids} sub={continence.nightVoids ? `${continence.nightVoids} at night` : undefined} />
            <Stat label="Leaks" value={continence.leaks} />
            <Stat label="Pelvic floor" value={continence.pelvicFloor} sub="sessions" />
          </div>
        </Card>
      )}
    </div>
  );
}

function DayCards({ day, on, onOpenScore }: { day: DaySummary; on: string[]; onOpenScore: (title: string, s: Score) => void }) {
  const units = useUnits();
  const navigate = useNavigate();
  const goalSteps = day.goals.steps;
  const night = day.sleepSummary;
  const target = day.nutritionTarget;
  const eaten = day.nutrition;
  const burned = (day.restingCalories ?? 0) + day.activeCalories;
  return (
    <div className="grid grid-2">
      {on.includes("ACTIVITY") && <Card title="Activity" icon={<ActivityIcon size={18} />} action={day.activity && <Button variant="ghost" onClick={() => onOpenScore("Activity", day.activity!)}>Details</Button>}>
        <div className="stats">
          <Stat label="Steps" value={num(day.steps)} sub={goalSteps ? `of ${num(goalSteps)}` : undefined} />
          <Stat label="Active" value={num(day.activeCalories)} unit="kcal" sub={`of ${num(day.goals.activeCalories)}`} />
          <Stat label="Distance" value={distance(day.distanceM, units)} />
          {day.restingCalories != null && <Stat label="Burned" value={num(burned)} unit="kcal" sub="resting + active" />}
        </div>
        <Meter value={day.steps} max={goalSteps} color="var(--c3)" label="Steps towards your goal" />
        {day.hourlySteps.some((s) => s > 0) && (
          <BarChart
            label="Steps each hour" color="var(--c3)" height={140} labelEvery={6} formatY={(v) => `${num(v)} steps`}
            bars={day.hourlySteps.map((v, h) => ({ key: String(h), label: h === 0 ? "12a" : h === 12 ? "12p" : h < 12 ? `${h}a` : `${h - 12}p`, value: v || null, detail: `${h}:00–${h + 1}:00` }))}
          />
        )}
      </Card>}

      <Card title="Heart" icon={<HeartPulse size={18} />}>
        <div className="stats">
          <Stat label="Resting" value={num(day.restingHeartRate)} unit="bpm" />
          <Stat label="HRV" value={num(day.hrv)} unit="ms" />
          {day.spo2 != null && <Stat label="Blood oxygen" value={num(day.spo2)} unit="%" />}
          {day.temperatureDeviation != null && <Stat label="Temperature" value={`${day.temperatureDeviation >= 0 ? "+" : "−"}${Math.abs(day.temperatureDeviation).toFixed(1)}`} unit="°C" sub="against your usual" />}
          {day.respiratoryRate != null && <Stat label="Breathing" value={num(day.respiratoryRate, 1)} unit="/min" />}
        </div>
        {day.heartRate.length > 1 ? (
          <LineChart
            label="Heart rate through the day" color="var(--c1)" height={160}
            points={day.heartRate.map((p) => ({ x: p.t, y: p.v }))}
            formatX={(x) => clock(x)} formatY={(y) => `${Math.round(y)} bpm`}
          />
        ) : <p className="muted small">No heart rate recorded this day.</p>}
      </Card>

      {on.includes("SLEEP") && <Card title="Sleep" icon={<Moon size={18} />} action={day.sleep && <Button variant="ghost" onClick={() => onOpenScore("Sleep", day.sleep!)}>Details</Button>}>
        {night ? (
          <>
            <div className="stats">
              <Stat label="Asleep" value={hm(night.asleepMinutes)} sub={`${clock(night.start)} – ${clock(night.end)}`} />
              <Stat label="Efficiency" value={night.efficiency} unit="%" />
              {night.lowestHeartRate != null && <Stat label="Lowest heart rate" value={num(night.lowestHeartRate)} unit="bpm" />}
            </div>
            {night.stages.length > 0 && <Hypnogram stages={night.stages} start={night.start} end={night.end} />}
            {night.staged && <StageLegend deep={night.deepMinutes} rem={night.remMinutes} light={night.lightMinutes} awake={night.awakeMinutes} />}
            {night.source && <p className="muted small">From {night.source}</p>}
          </>
        ) : <p className="muted">No night recorded. Wear your ring or watch to bed, or add the night by hand.</p>}
      </Card>}

      {on.includes("NUTRITION") && <Card title="Food" icon={<Salad size={18} />} onClick={() => navigate(`/food?date=${day.date}`)}>
        {target && (
          <>
            <div className="food-summary">
              <div>
                <div className="big-number">{num(eaten.calories)}<small>of {num(target.calories)} kcal</small></div>
                <p className="muted small">{eaten.calories <= target.calories ? `${num(target.calories - eaten.calories)} kcal left` : `${num(eaten.calories - target.calories)} kcal over`}{day.restingCalories != null ? ` · ${num(burned)} burned so far` : ""}</p>
              </div>
            </div>
            <Meter value={eaten.calories} max={target.calories} color="var(--c1)" label="Calories eaten against your target" over />
            <Macros eaten={eaten} target={target} />
            <div className="macro"><span className="macro-name"><GlassWater size={14} /> Water</span><Meter value={day.waterMl} max={target.waterMl} color="var(--c3)" label="Water" /><span className="macro-amount"><strong>{num(day.waterMl)}</strong> / {num(target.waterMl)} ml</span></div>
          </>
        )}
      </Card>}

      {(day.workouts.length > 0 || day.weightKg != null || day.checkIn) && (
        <Card title="Also today" icon={<Dumbbell size={18} />} className="span-2">
          <ul className="rows">
            {day.workouts.map((w) => (
              <li key={w.id} className="row">
                <div className="row-icon"><Dumbbell size={18} /></div>
                <div className="row-main">
                  <div className="row-title">{w.name ?? workoutLabel(w.type)}</div>
                  <div className="row-sub">{clock(w.start)} · {hm((w.end - w.start) / 60_000)}{w.distanceM ? ` · ${distance(w.distanceM, units)}` : ""}{w.source ? ` · ${w.source}` : ""}</div>
                </div>
                <div className="row-end">{w.activeCalories ? `${num(w.activeCalories)} kcal` : ""}<div className="muted">{w.avgHeartRate ? `${num(w.avgHeartRate)} bpm avg` : ""}</div></div>
              </li>
            ))}
            {day.weightKg != null && (
              <li className="row">
                <div className="row-icon"><Weight size={18} /></div>
                <div className="row-main"><div className="row-title">Weight</div><div className="row-sub">{day.bodyFat != null ? `Body fat ${day.bodyFat.toFixed(1)}%` : "Latest of the day"}</div></div>
                <div className="row-end">{weight(day.weightKg, units)}</div>
              </li>
            )}
            {day.checkIn && (day.checkIn.mood || day.checkIn.tags.length > 0 || day.checkIn.note) && (
              <li className="row">
                <div className="row-icon"><Smile size={18} /></div>
                <div className="row-main">
                  <div className="row-title">Check-in</div>
                  <div className="row-sub">{[
                    day.checkIn.mood && `mood ${day.checkIn.mood}/5`, day.checkIn.energy && `energy ${day.checkIn.energy}/5`,
                    day.checkIn.stress && `stress ${day.checkIn.stress}/5`, day.checkIn.tags.join(", "), day.checkIn.note,
                  ].filter(Boolean).join(" · ")}</div>
                </div>
              </li>
            )}
          </ul>
        </Card>
      )}
    </div>
  );
}

export function Macros({ eaten, target }: { eaten: { protein: number; carbs: number; fat: number }; target: { protein: number; carbs: number; fat: number } }) {
  const rows = [
    { name: "Protein", value: eaten.protein, max: target.protein, color: "var(--c1)" },
    { name: "Carbs", value: eaten.carbs, max: target.carbs, color: "var(--c2)" },
    { name: "Fat", value: eaten.fat, max: target.fat, color: "var(--c3)" },
  ];
  return (
    <div className="macros">
      {rows.map((r) => (
        <div key={r.name} className="macro">
          <span className="macro-name"><span className="legend-key" style={{ background: r.color }} />{r.name}</span>
          <Meter value={r.value} max={r.max} color={r.color} label={`${r.name} against target`} over />
          <span className="macro-amount"><strong>{num(r.value)}</strong> / {num(r.max)} g</span>
        </div>
      ))}
    </div>
  );
}

export function ScoreDialog({ title, score, onClose }: { title: string; score: Score; onClose: () => void }) {
  const color = title === "Sleep" ? "var(--c1)" : title === "Readiness" ? "var(--c2)" : "var(--c3)";
  return (
    <Modal title={`${title}: ${score.score}`} onClose={onClose}>
      <p className="muted">{score.label}. What it's made of, each scored out of 100:</p>
      <ul className="contributors">
        {score.contributors.map((c) => (
          <li key={c.key} className="contributor">
            <span className="contributor-label">{c.label}</span>
            <span className="contributor-value">{c.value} · <strong>{c.score}</strong></span>
            <Meter value={c.score} max={100} color={color} label={`${c.label}: ${c.score} of 100`} />
            {c.detail && <span className="contributor-detail">{c.detail}</span>}
          </li>
        ))}
      </ul>
    </Modal>
  );
}

function Welcome() {
  const navigate = useNavigate();
  return (
    <Card>
      <Empty icon={<Watch size={36} />} title="Let's bring in your data">
        <p>Sign in to the SuperAwesomeMagicalHealth app on your phone to carry data in from a Colmi ring, Health Connect (Garmin, Fitbit, Pebble through Gadgetbridge…) or Gadgetbridge. Or upload Garmin .FIT files here. Meanwhile, you can log food, weight and workouts by hand.</p>
        <div className="button-row" style={{ justifyContent: "center", marginTop: 12 }}>
          <Button variant="primary" onClick={() => navigate("/devices")}><Watch size={18} /> Devices</Button>
          <Button onClick={() => navigate("/settings")}><Plus size={18} /> Set up your profile</Button>
        </div>
      </Empty>
    </Card>
  );
}
