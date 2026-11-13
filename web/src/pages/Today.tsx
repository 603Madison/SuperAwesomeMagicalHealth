import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Activity as ActivityIcon, CalendarClock, CalendarHeart, Coffee, Droplet, Droplets, Dumbbell, GlassWater, Heart, HeartPulse, Moon, Plus, Salad,
  Smile, Sparkles, Stethoscope, Watch, Weight, Waves, Camera, Bot,
} from "lucide-react";
import { FirstRunPrompt } from "./Conditions";
import { useAiAvailable } from "./Assistant";
import { useModules } from "../App";
import { MoodDialog } from "./Mind";
import { api } from "../lib/api";
import { clock, dayLabel, distance, hm, num, today, useUnits, weight, workoutLabel } from "../lib/format";
import { useSession } from "../lib/session";
import { useReadOnly, useViewing } from "../lib/viewing";
import type { DaySummary, Score } from "../lib/types";
import { BarChart, Hypnogram, LineChart, Meter, ScoreRing, StageLegend } from "../components/charts";
import { Button, Card, DateNav, Empty, ErrorNotice, Modal, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { CheckInDialog, INSIGHT_ICONS, SleepDialog, WeightDialog, WorkoutDialog } from "../components/LogDialogs";
import { BloodPressureDialog, GlucoseDialog } from "./Vitals";
import { SymptomDialog } from "./Symptoms";
import { DrinkDialog, quickDrink } from "./Drinks";
import { DigestCard, ModuleCards, StressCard } from "./TodayCards";

const RINGS: { key: "readiness" | "sleep" | "activity"; label: string; color: string }[] = [
  { key: "readiness", label: "Readiness", color: "var(--c2)" },
  { key: "sleep", label: "Sleep", color: "var(--c1)" },
  { key: "activity", label: "Activity", color: "var(--c3)" },
];

export function Today() {
  const [date, setDate] = useState(today());
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date]);
  const user = useSession((s) => s.user)!;
  const [open, setOpen] = useState<"weight" | "workout" | "sleep" | "checkin" | "bp" | "glucose" | "symptom" | "drink" | null>(null);
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [mood, setMood] = useState<number | null>(null);
  const on = useModules();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const glucoseUnit = useSession((s) => s.profile?.vitalsTargets?.glucoseUnit) ?? "MMOL_L";
  const rings = RINGS.filter((r) => r.key === "readiness" ? on.includes("SLEEP") || on.includes("ACTIVITY") : r.key === "sleep" ? on.includes("SLEEP") : on.includes("ACTIVITY"));
  const m = data?.modules;
  const moduleData = !!(m && (m.mind || m.cycle?.cycleDay != null || m.continence || m.meds || m.vitals?.bloodPressure || m.vitals?.glucose
    || m.habits?.length || m.symptoms?.entries.length || m.symptoms?.migraines.length || m.lifeStage || m.appointments?.length
    || m.lowSupplies?.length || m.neuro || m.transition));
  const ai = useAiAvailable();
  // Until something's picked in What you're tracking: a gentle prompt, dismissed per browser.
  const [skipFirstRun, setSkipFirstRun] = useState(() => { try { return localStorage.getItem("samh.firstRun.skip") === "1"; } catch { return false; } });
  const conditions = useLoad(() => (viewing || skipFirstRun ? Promise.resolve(null) : api.conditions().catch(() => null)), [viewing, skipFirstRun]);
  const showFirstRun = !viewing && !skipFirstRun && conditions.data != null && conditions.data.conditions.length === 0;
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
          <h1>{viewing ? `${viewing.ownerName}'s ${date === today() ? "day" : dayLabel(date).toLowerCase()}` : date === today() ? `${greeting}, ${user.displayName.split(" ")[0]}` : dayLabel(date)}</h1>
          <p className="page-subtitle">{new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}</p>
        </div>
        <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={today()} />
      </header>

      {showFirstRun && date === today() && <FirstRunPrompt onDismiss={() => { try { localStorage.setItem("samh.firstRun.skip", "1"); } catch { /* fine */ } setSkipFirstRun(true); }} />}
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

          {!readOnly && <div className="quick">
            {on.includes("NUTRITION") && <button className="chip" onClick={() => navigate(`/food?date=${date}`)}><Salad size={18} /> Log food</button>}
            {on.includes("NUTRITION") && <button className="chip" onClick={addWater}><GlassWater size={18} /> +250 ml water</button>}
            {on.includes("NUTRITION") && <button className="chip" onClick={async () => { try { await quickDrink("COFFEE", date); reload(); } catch (e) { errorToast(e); } }}><Coffee size={18} /> Coffee</button>}
            {on.includes("MIND") && <button className="chip" onClick={() => setMood(3)}><Heart size={18} /> Mood</button>}
            {on.includes("VITALS") && <button className="chip" onClick={() => setOpen("bp")}><HeartPulse size={18} /> Blood pressure</button>}
            {on.includes("VITALS") && <button className="chip" onClick={() => setOpen("glucose")}><Droplets size={18} /> Glucose</button>}
            {on.includes("SYMPTOMS") && <button className="chip" onClick={() => setOpen("symptom")}><Stethoscope size={18} /> Symptom</button>}
            {on.includes("CYCLE") && <button className="chip" onClick={() => navigate("/cycle")}><CalendarHeart size={18} /> {m?.lifeStage && m.lifeStage.stage !== "CYCLING" && m.lifeStage.stage !== "TRYING" ? "Cycle" : "Period"}</button>}
            {on.includes("CONTINENCE") && <button className="chip" onClick={() => navigate("/bladder")}><Droplet size={18} /> Bladder</button>}
            {on.includes("BODY") && <button className="chip" onClick={() => setOpen("weight")}><Weight size={18} /> Weight</button>}
            {on.includes("ACTIVITY") && <button className="chip" onClick={() => setOpen("workout")}><Dumbbell size={18} /> Workout</button>}
            {on.includes("SLEEP") && <button className="chip" onClick={() => setOpen("sleep")}><Moon size={18} /> Add a night</button>}
            {on.includes("APPOINTMENTS") && <button className="chip" onClick={() => navigate("/appointments")}><CalendarClock size={18} /> Appointment</button>}
            {on.includes("NEURO") && <button className="chip" onClick={() => navigate("/neuro")}><Sparkles size={18} /> Energy &amp; focus</button>}
            {on.includes("NEURO") && <button className="chip" onClick={() => navigate("/calm")}><Waves size={18} /> Calm mode</button>}
            {on.includes("TRANSITION") && <button className="chip" onClick={() => navigate("/transition")}><Sparkles size={18} /> Transition</button>}
            {on.includes("PHOTOS") && <button className="chip" onClick={() => navigate("/photos")}><Camera size={18} /> Photo</button>}
            {ai && <button className="chip" onClick={() => navigate("/assistant")}><Bot size={18} /> Ask the assistant</button>}
            <button className={`chip ${data.checkIn?.mood ? "is-on" : ""}`} onClick={() => setOpen("checkin")}><Smile size={18} /> Check in</button>
          </div>}

          {!readOnly && date === today() && <DigestCard />}

          {data.insights.some((i) => !i.module || on.includes(i.module)) && (
            <section className="insights" aria-label="Insights">
              {data.insights.filter((i) => !i.module || on.includes(i.module)).map((i, n) => {
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

          <ModuleCards day={data} date={date} on={on} readOnly={readOnly} onChanged={reload} onMood={setMood} onSymptom={() => setOpen("symptom")} />
          {!data.hasData && !moduleData && date === today() && !readOnly ? <Welcome /> : <DayCards day={data} on={on} onOpenScore={(title, s) => setScore({ title, score: s })} onDrink={readOnly ? undefined : () => setOpen("drink")} />}
        </div>
      )}

      {score && <ScoreDialog title={score.title} score={score.score} onClose={() => setScore(null)} />}
      {open === "bp" && <BloodPressureDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "glucose" && <GlucoseDialog unit={glucoseUnit} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "symptom" && <SymptomDialog initial={{ symptom: "" }} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "drink" && <DrinkDialog date={date} onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "weight" && <WeightDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "workout" && <WorkoutDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "sleep" && <SleepDialog onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "checkin" && <CheckInDialog date={date} initial={data?.checkIn} onClose={() => setOpen(null)} onSaved={reload} />}
      {mood != null && <MoodDialog initial={mood} onClose={() => setMood(null)} onSaved={reload} />}
    </div>
  );
}

function DayCards({ day, on, onOpenScore, onDrink }: { day: DaySummary; on: string[]; onOpenScore: (title: string, s: Score) => void; onDrink?: () => void }) {
  const units = useUnits();
  const navigate = useNavigate();
  const goalSteps = day.goals.steps;
  const night = day.sleepSummary;
  const target = day.nutritionTarget;
  const eaten = day.nutrition;
  const burned = (day.restingCalories ?? 0) + day.activeCalories;
  const drinks = day.drinks;
  return (
    <div className="grid grid-2">
      {on.includes("ACTIVITY") && (day.stressDay || day.resilience) && <StressCard stress={day.stressDay} resilience={day.resilience} className="span-2" />}
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
        {drinks && (drinks.caffeineMg > 0 || drinks.standardDrinks > 0) && (
          <p className="muted small drinks-line">
            <Coffee size={14} /> {num(drinks.caffeineMg)} mg caffeine{drinks.caffeineAtBedtimeMg != null && drinks.caffeineMg > 0 ? ` (~${num(drinks.caffeineAtBedtimeMg)} mg left at bedtime)` : ""}
            {drinks.standardDrinks > 0 ? ` · ${num(drinks.standardDrinks, 1)} standard ${drinks.standardDrinks === 1 ? "drink" : "drinks"}` : ""}
          </p>
        )}
        {onDrink && <div><Button variant="ghost" onClick={(e) => { e.stopPropagation(); onDrink(); }}><Coffee size={16} /> A drink</Button></div>}
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
