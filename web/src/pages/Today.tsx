import { Fragment, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Activity as ActivityIcon, CalendarClock, CalendarHeart, Camera, ChevronRight, Coffee, CupSoda, Droplet, Droplets, Dumbbell, GlassWater, Heart, HeartPulse,
  ListChecks, Moon, MoreHorizontal, Pill, Plus, Ruler, Salad, Smile, Sparkles, Stethoscope, Watch, Waves, Weight, Wind, ArrowUpDown,
} from "lucide-react";
import { FirstRunPrompt } from "./Conditions";
import { useModules } from "../App";
import { MoodDialog } from "./Mind";
import { api } from "../lib/api";
import { F, useFields, useWater } from "../lib/fields";
import { clock, dayLabel, distance, hm, num, today, useUnits, weight, workoutLabel } from "../lib/format";
import { useScreenLayout } from "../lib/layout";
import { useSession } from "../lib/session";
import { useReadOnly, useViewing } from "../lib/viewing";
import { DEFAULT_TARGETS } from "../lib/vitals";
import { BODY_MEASUREMENTS, LAYOUT_QUICK, LAYOUT_TODAY, QUICK_ACTIONS, QUICK_SHOWN, arrange, type DaySummary, type Module, type QuickAction, type Score, type TodaySection } from "../lib/types";
import { BarChart, Hypnogram, LineChart, Meter, ScoreRing, StageLegend } from "../components/charts";
import { Button, Card, CardAction, DateNav, ErrorNotice, Menu, Modal, Segmented, Spinner, Stat, errorToast, toast, useLoad } from "../components/ui";
import { ArrangeButton, ArrangePanel, useArrange, type Section } from "../components/Arrange";
import { CheckInDialog, INSIGHT_ICONS, SleepDialog, WeightDialog, WorkoutDialog } from "../components/LogDialogs";
import { MeasureDialog } from "../components/Measurements";
import { BloodPressureDialog, GlucoseDialog } from "./Vitals";
import { SymptomDialog } from "./Symptoms";
import { DrinkDialog, quickDrink } from "./Drinks";
import { MomentDialog } from "./Transition";
import { LowSuppliesCard } from "./Supplies";
import { NeuroTodayCard } from "./Neuro";
import { TransitionTodayCard } from "./Transition";
import {
  AppointmentsCard, ContinenceCard, CycleCard, DigestCard, HabitsCard, HormonesCard, LifeStageCard, MedsCard, MoodCard, StressCard, SymptomsCard, VitalsCard,
} from "./TodayCards";

const RINGS: { key: "readiness" | "sleep" | "activity"; label: string; color: string }[] = [
  { key: "readiness", label: "Readiness", color: "var(--c2)" },
  { key: "sleep", label: "Sleep", color: "var(--c1)" },
  { key: "activity", label: "Activity", color: "var(--c3)" },
];

type Dialog = "weight" | "workout" | "sleep" | "checkin" | "bp" | "glucose" | "symptom" | "drink" | "moment" | "measure";

/** A quick action as Today shows it: the shared id, the chip's words and icon, and what it does. */
interface Quick { id: QuickAction; label: string; chip?: string; icon: ReactNode; run: () => void; when?: boolean; on?: boolean }

export function Today() {
  const [date, setDate] = useState(today());
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date]);
  const user = useSession((s) => s.user)!;
  const [open, setOpen] = useState<Dialog | null>(null);
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [mood, setMood] = useState<number | null>(null);
  const [arranging, setArranging] = useState<"cards" | "quick" | null>(null);
  const on = useModules();
  const { has } = useFields();
  const water = useWater(on);
  const units = useUnits();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const navigate = useNavigate();
  const glucoseUnit = useSession((s) => s.profile?.vitalsTargets?.glucoseUnit) ?? "MMOL_L";
  const rings = RINGS.filter((r) => r.key === "readiness" ? on.includes("SLEEP") || on.includes("ACTIVITY") : r.key === "sleep" ? on.includes("SLEEP") : on.includes("ACTIVITY"));
  // Until something's picked in What you're tracking: a gentle prompt, dismissed per browser.
  const [skipFirstRun, setSkipFirstRun] = useState(() => { try { return localStorage.getItem("samh.firstRun.skip") === "1"; } catch { return false; } });
  const conditions = useLoad(() => (viewing || skipFirstRun ? Promise.resolve(null) : api.conditions().catch(() => null)), [viewing, skipFirstRun]);
  const showFirstRun = !viewing && !skipFirstRun && conditions.data != null && conditions.data.conditions.length === 0;
  const hour = new Date().getHours();
  const greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  const m = data?.modules;
  const is = (id: Module) => on.includes(id);
  const lifeStage = is("CYCLE") ? m?.lifeStage : undefined;
  const lifeStageShown = !!lifeStage && lifeStage.stage !== "CYCLING" && lifeStage.stage !== "TRYING";
  const lowSupplies = is("CONTINENCE") || is("CYCLE") ? m?.lowSupplies ?? [] : [];

  const addWater = async () => {
    try { await api.addWater(date, 250); toast("250 ml of water added"); reload(); } catch (e) { errorToast(e); }
  };

  // ---- Today's sections, in the default order (shared with the phone app) ----
  const sections: (Section & { id: TodaySection })[] = [
    { id: "SCORES", label: "Readiness, sleep and activity scores", when: rings.length > 0,
      node: data && <Scores day={data} rings={rings} readOnly={readOnly} onOpen={(title, s) => setScore({ title, score: s })} /> },
    { id: "INSIGHTS", label: "Insights", node: data && <Insights day={data} on={on} lowSupplies={lowSupplies.map((s) => s.supply.name)} readOnly={readOnly} date={date} /> },
    { id: "QUICK", label: "Quick actions", when: !readOnly, node: null /* filled in below, once the cards are known */ },
    { id: "MEDS", label: "Medications", when: is("MEDICATIONS"), node: m?.meds && m.meds.due.length > 0 && <MedsCard meds={m.meds} date={date} readOnly={readOnly} onChanged={reload} /> },
    { id: "MIND", label: "Mood", when: is("MIND"), node: data && <MoodCard mind={m?.mind} readOnly={readOnly} onMood={setMood} /> },
    { id: "CYCLE", label: "Cycle", when: is("CYCLE"), node: m?.cycle && !lifeStageShown && <CycleCard cycle={m.cycle} /> },
    { id: "CONTINENCE", label: "Bladder & bowel", when: is("CONTINENCE"), node: m?.continence && <ContinenceCard continence={m.continence} date={date} /> },
    { id: "SUPPLIES", label: "Supplies running low", when: is("CONTINENCE") || is("CYCLE"), node: lowSupplies.length > 0 && <LowSuppliesCard items={lowSupplies} /> },
    { id: "NEURO", label: "Autism & ADHD", when: is("NEURO"), node: m?.neuro && <NeuroTodayCard data={m.neuro} /> },
    { id: "TRANSITION", label: "Transition", when: is("TRANSITION"), node: m?.transition && <TransitionTodayCard {...m.transition} /> },
    { id: "VITALS", label: "Blood pressure & glucose", when: is("VITALS"), node: data && <VitalsCard vitals={m?.vitals ?? { targets: DEFAULT_TARGETS }} readOnly={readOnly} onLog={() => setOpen("bp")} /> },
    { id: "DRINKS", label: water ? "Drinks and water" : "Drinks", when: is("NUTRITION"),
      node: data && <DrinksCard day={data} date={date} water={water} readOnly={readOnly} onDrink={() => setOpen("drink")} onWater={addWater} /> },
    { id: "SYMPTOMS", label: "Symptoms", when: is("SYMPTOMS"), node: data && <SymptomsCard symptoms={m?.symptoms} readOnly={readOnly} onLog={() => setOpen("symptom")} /> },
    { id: "HABITS", label: "Habits", when: is("HABITS"), node: data && <HabitsCard habits={m?.habits ?? []} date={date} readOnly={readOnly} onChanged={reload} /> },
    { id: "HORMONES", label: "Hormone therapy", when: is("HORMONES"),
      node: m?.hormones && (m.hormones.medications > 0 || m.hormones.nextLabsDue || m.hormones.outOfRange.length > 0) && <HormonesCard hormones={m.hormones} /> },
    { id: "LIFE_STAGE", label: "Life stage", when: is("CYCLE"), node: lifeStage && lifeStageShown && <LifeStageCard stage={lifeStage} /> },
    { id: "APPOINTMENTS", label: "Appointments coming up", when: is("APPOINTMENTS"), node: m?.appointments && m.appointments.length > 0 && <AppointmentsCard appointments={m.appointments} /> },
    { id: "ACTIVITY", label: "Activity", when: is("ACTIVITY"), node: data && <ActivityCard day={data} readOnly={readOnly} onWorkout={() => setOpen("workout")} /> },
    { id: "SLEEP", label: "Sleep", when: is("SLEEP"), node: data && <SleepCard day={data} readOnly={readOnly} onAdd={() => setOpen("sleep")} /> },
    { id: "HEART", label: "Heart", node: data && <HeartCard day={data} /> },
    { id: "STRESS", label: "Stress and recovery", when: is("ACTIVITY"), node: data && (data.stressDay || data.resilience) && <StressCard stress={data.stressDay} resilience={data.resilience} /> },
    { id: "FOOD", label: "Food", when: is("NUTRITION"), node: data?.nutritionTarget && <FoodCard day={data} /> },
    { id: "WORKOUTS", label: "Workouts and check-in", node: data && <AlsoToday day={data} /> },
    { id: "BODY", label: "Weight", when: is("BODY"), node: data?.weightKg != null && (
      <Card title="Weight" icon={<Weight size={18} />} link={{ to: "/body", label: "Open Body" }}>
        <div className="stats"><Stat label="Today" value={weight(data.weightKg, units)} sub={data.bodyFat != null ? `Body fat ${data.bodyFat.toFixed(1)}%` : undefined} /></div>
      </Card>
    ) },
  ];

  const cards = useArrange(LAYOUT_TODAY, "Today", sections);
  // Water's quick-add lives in the Drinks card while that shows; otherwise it's a quick action.
  const drinksShown = cards.shown.some((s) => s.id === "DRINKS");

  // ---- The quick actions (shared ids with the phone app); the first few show, the rest are under More ----
  const coffee = async () => { try { await quickDrink("COFFEE", date); reload(); } catch (e) { errorToast(e); } };
  const otherStage = lifeStageShown;
  const actions: Quick[] = [
    { id: "FOOD", label: "Log food", icon: <Salad size={18} />, run: () => navigate(`/food?date=${date}`) },
    { id: "WATER", label: "Water", chip: "+250 ml water", icon: <GlassWater size={18} />, run: addWater, when: water && !drinksShown },
    { id: "MOOD", label: "Mood", icon: <Heart size={18} />, run: () => setMood(3) },
    { id: "PERIOD", label: "Period", chip: otherStage ? "Cycle" : "Period", icon: <CalendarHeart size={18} />, run: () => navigate("/cycle") },
    { id: "BLADDER", label: "Bladder & bowel", icon: <Droplet size={18} />, run: () => navigate("/bladder") },
    { id: "DOSE", label: "Medications", icon: <Pill size={18} />, run: () => navigate("/meds") },
    { id: "WEIGHT", label: "Weight", icon: <Weight size={18} />, run: () => setOpen("weight") },
    { id: "COFFEE", label: "Coffee", icon: <Coffee size={18} />, run: coffee },
    { id: "DRINK", label: "A drink", icon: <CupSoda size={18} />, run: () => setOpen("drink") },
    { id: "WORKOUT", label: "Workout", icon: <Dumbbell size={18} />, run: () => setOpen("workout") },
    { id: "SLEEP", label: "Add a night", icon: <Moon size={18} />, run: () => setOpen("sleep") },
    { id: "CHECK_IN", label: "Check in", icon: <Smile size={18} />, run: () => setOpen("checkin"), on: !!data?.checkIn?.mood },
    { id: "SYMPTOM", label: "Symptom", icon: <Stethoscope size={18} />, run: () => setOpen("symptom") },
    { id: "BLOOD_PRESSURE", label: "Blood pressure", icon: <HeartPulse size={18} />, run: () => setOpen("bp") },
    { id: "GLUCOSE", label: "Glucose", icon: <Droplets size={18} />, run: () => setOpen("glucose") },
    { id: "HABIT", label: "Habits", icon: <ListChecks size={18} />, run: () => navigate("/habits") },
    { id: "ENERGY", label: "Energy & focus", icon: <Sparkles size={18} />, run: () => navigate("/neuro") },
    { id: "OVERLOAD", label: "Too much", chip: "Too much: calm mode", icon: <Waves size={18} />, run: () => navigate("/calm"), when: has(F.NEURO_CALM) },
    { id: "CALM", label: "Calm", chip: "Calm tools", icon: <Wind size={18} />, run: () => navigate("/mind#calm") },
    { id: "MOMENT", label: "Euphoria", icon: <Sparkles size={18} />, run: () => setOpen("moment"), when: has(F.TRANSITION_MOMENTS) },
    { id: "PHOTO", label: "Photo", icon: <Camera size={18} />, run: () => navigate("/photos") },
    { id: "MEASUREMENTS", label: "Measurements", icon: <Ruler size={18} />, run: () => setOpen("measure"), when: has(F.BODY_MEASUREMENTS) },
    { id: "APPOINTMENT", label: "Appointment", icon: <CalendarClock size={18} />, run: () => navigate("/appointments") },
  ];
  const module = (id: QuickAction) => QUICK_ACTIONS.find((q) => q.id === id)?.module ?? null;
  const offeredQuick = actions.filter((a) => a.when !== false && (module(a.id) == null || is(module(a.id)!)));
  const quickLayout = useScreenLayout(LAYOUT_QUICK);
  const quickOrdered = arrange(offeredQuick, (a) => a.id, quickLayout);
  // Until the person arranges them: the first few, the rest under More. After: the ones they hid are under More.
  const defaultMore = quickOrdered.slice(QUICK_SHOWN).map((a) => a.id as string);
  const more = new Set(quickLayout ? quickLayout.hidden : defaultMore);
  const quickShown = quickOrdered.filter((a) => !more.has(a.id));
  const quickMore = quickOrdered.filter((a) => more.has(a.id));
  const quickNode = data && offeredQuick.length > 0 && (
    <div className="quick span-2" role="group" aria-label="Quick actions">
      {quickShown.map((a) => <button key={a.id} type="button" className={`chip ${a.on ? "is-on" : ""}`} onClick={a.run}>{a.icon} {a.chip ?? a.label}</button>)}
      <Menu label="More quick actions" buttonClass="chip chip-more" trigger={<><MoreHorizontal size={18} /> More</>} items={[
        ...quickMore.map((a) => ({ label: a.chip ?? a.label, icon: a.icon, onSelect: a.run })),
        { label: "Arrange quick actions", icon: <ArrowUpDown size={18} />, onSelect: () => setArranging("quick") },
      ]} />
    </div>
  );
  const nodes = cards.shown.map((s) => (s.id === "QUICK" ? { ...s, node: quickNode } : s)).filter((s) => s.node).map((s) => <Fragment key={s.id}>{s.node}</Fragment>);

  return (
    <div className="page today">
      <header className="page-header">
        <div>
          <h1>{viewing ? `${viewing.ownerName}'s ${date === today() ? "day" : dayLabel(date).toLowerCase()}` : date === today() ? `${greeting}, ${user.displayName.split(" ")[0]}` : dayLabel(date)}</h1>
          <p className="page-subtitle">{new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}</p>
        </div>
        <div className="page-actions">
          <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={today()} />
          <ArrangeButton title="Today" onClick={() => setArranging("cards")} />
        </div>
      </header>

      {showFirstRun && date === today() && <FirstRunPrompt onDismiss={() => { try { localStorage.setItem("samh.firstRun.skip", "1"); } catch { /* fine */ } setSkipFirstRun(true); }} />}
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div className="grid grid-2 arranged" style={{ opacity: loading ? 0.6 : 1, transition: "opacity 0.2s" }}>
          {nodes}
          {cards.shown.length === 0 && !readOnly && <p className="muted arranged-empty span-2">Everything on Today is hidden. <button type="button" className="link-btn" onClick={() => setArranging("cards")}>Arrange</button> to show it again.</p>}
        </div>
      )}

      {arranging && (
        <ArrangePanel
          key={arranging}
          screen={arranging === "quick" ? LAYOUT_QUICK : LAYOUT_TODAY}
          title={arranging === "quick" ? "quick actions" : "Today"}
          items={arranging === "quick" ? offeredQuick : sections.filter((s) => s.when !== false)}
          defaultHidden={arranging === "quick" ? defaultMore : []}
          hiddenText={arranging === "quick" ? "Under More" : "Hidden"}
          showLabel={arranging === "quick" ? (l) => `Show ${l} in the row` : undefined}
          intro={arranging === "quick" ? `The ones switched on show on Today; the rest wait under More.${drinksShown && water ? " Water's quick button is in the Drinks card." : ""}` : undefined}
          top={!readOnly && (
            <Segmented label="What to arrange" value={arranging} onChange={setArranging} options={[{ value: "cards", label: "Cards" }, { value: "quick", label: "Quick actions" }]} />
          )}
          onClose={() => setArranging(null)}
        />
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
      {open === "moment" && <MomentDialog kind="EUPHORIA" onClose={() => setOpen(null)} onSaved={reload} />}
      {open === "measure" && <MeasureDialog imperial={units === "IMPERIAL"} metrics={BODY_MEASUREMENTS.map((x) => x.id)} onClose={() => setOpen(null)} onSaved={reload} />}
      {mood != null && <MoodDialog initial={mood} onClose={() => setMood(null)} onSaved={reload} />}
    </div>
  );
}

/** The three scores; with no scores yet, one line about where they come from. */
function Scores({ day, rings, readOnly, onOpen }: { day: DaySummary; rings: typeof RINGS; readOnly: boolean; onOpen: (title: string, s: Score) => void }) {
  if (!rings.some((r) => day[r.key] != null)) {
    return (
      <div className="score-line span-2">
        <Watch size={18} aria-hidden="true" />
        <span>Scores appear after a night with your ring or watch</span>
        {!readOnly && <Link to="/devices" className="score-line-link">Devices <ChevronRight size={16} aria-hidden="true" /></Link>}
      </div>
    );
  }
  return (
    <section className="hero span-2" aria-label="Scores" style={{ gridTemplateColumns: `repeat(${rings.length}, minmax(0, 1fr))` }}>
      {rings.map((r) => {
        const s = day[r.key];
        return <ScoreRing key={r.key} label={r.label} color={r.color} score={s?.score} size={120} sub={s?.label ?? "Not enough data"} onClick={s ? () => onOpen(r.label, s) : undefined} />;
      })}
    </section>
  );
}

/** The day's insights, and the weekly digest on its day. Supplies running low show once: in their card. */
function Insights({ day, on, lowSupplies, readOnly, date }: { day: DaySummary; on: Module[]; lowSupplies: string[]; readOnly: boolean; date: string }) {
  const aboutSupplies = (title: string) => lowSupplies.some((n) => title === `Running low on ${n}` || title === `Out of ${n}`);
  const list = day.insights.filter((i) => (!i.module || on.includes(i.module)) && !((i.module === "CONTINENCE" || i.module === "CYCLE") && aboutSupplies(i.title)));
  return (
    <section className="insights span-2" aria-label="Insights">
      {!readOnly && date === today() && <DigestCard />}
      {list.map((i, n) => {
        const Icon = INSIGHT_ICONS[i.icon] ?? Sparkles;
        return (
          <article key={n} className={`insight tone-${i.tone}`}>
            <div className="insight-icon"><Icon size={20} /></div>
            <div><h4>{i.title}</h4><p>{i.body}</p></div>
          </article>
        );
      })}
    </section>
  );
}

function ActivityCard({ day, readOnly, onWorkout }: { day: DaySummary; readOnly: boolean; onWorkout: () => void }) {
  const units = useUnits();
  const goalSteps = day.goals.steps;
  const burned = (day.restingCalories ?? 0) + day.activeCalories;
  const moved = (day.steps ?? 0) > 0 || day.activeCalories > 0 || (day.distanceM ?? 0) > 0;
  return (
    <Card title="Activity" icon={<ActivityIcon size={18} />} link={{ to: "/activity", label: "Open Activity" }}
      action={!moved && !readOnly && <CardAction label="Log a workout" onClick={onWorkout}><Plus size={18} /></CardAction>}>
      {moved && (
        <>
          <div className="stats">
            <Stat label="Steps" value={num(day.steps)} sub={goalSteps ? `of ${num(goalSteps)}` : undefined} />
            <Stat label="Active" value={num(day.activeCalories)} unit="kcal" sub={`of ${num(day.goals.activeCalories)}`} />
            <Stat label="Distance" value={distance(day.distanceM, units)} />
            {day.restingCalories != null && <Stat label="Burned" value={num(burned)} unit="kcal" sub="resting + active" />}
          </div>
          <Meter value={day.steps} max={goalSteps} color="var(--c3)" label="Steps towards your goal" />
          {day.hourlySteps.some((s) => s > 0) && (
            <BarChart
              label="Steps each hour" color="var(--c3)" height={120} labelEvery={6} formatY={(v) => `${num(v)} steps`}
              bars={day.hourlySteps.map((v, h) => ({ key: String(h), label: h === 0 ? "12a" : h === 12 ? "12p" : h < 12 ? `${h}a` : `${h - 12}p`, value: v || null, detail: `${h}:00–${h + 1}:00` }))}
            />
          )}
        </>
      )}
    </Card>
  );
}

/** Resting heart rate, HRV and the day's heart rate; nothing at all on a day without them. */
function HeartCard({ day }: { day: DaySummary }) {
  if (day.restingHeartRate == null && day.hrv == null && day.heartRate.length < 2 && day.spo2 == null) return null;
  return (
    <Card title="Heart" icon={<HeartPulse size={18} />} link={{ to: "/trends", label: "Open Trends" }}>
      <div className="stats">
        <Stat label="Resting" value={num(day.restingHeartRate)} unit="bpm" />
        <Stat label="HRV" value={num(day.hrv)} unit="ms" />
        {day.spo2 != null && <Stat label="Blood oxygen" value={num(day.spo2)} unit="%" />}
        {day.temperatureDeviation != null && <Stat label="Temperature" value={`${day.temperatureDeviation >= 0 ? "+" : "−"}${Math.abs(day.temperatureDeviation).toFixed(1)}`} unit="°C" sub="against your usual" />}
        {day.respiratoryRate != null && <Stat label="Breathing" value={num(day.respiratoryRate, 1)} unit="/min" />}
      </div>
      {day.heartRate.length > 1 && (
        <LineChart
          label="Heart rate through the day" color="var(--c1)" height={150}
          points={day.heartRate.map((p) => ({ x: p.t, y: p.v }))}
          formatX={(x) => clock(x)} formatY={(y) => `${Math.round(y)} bpm`}
        />
      )}
    </Card>
  );
}

function SleepCard({ day, readOnly, onAdd }: { day: DaySummary; readOnly: boolean; onAdd: () => void }) {
  const night = day.sleepSummary;
  return (
    <Card title="Sleep" icon={<Moon size={18} />} link={{ to: "/sleep", label: "Open Sleep" }}
      action={!night && !readOnly && <CardAction label="Add a night" onClick={onAdd}><Plus size={18} /></CardAction>}>
      {night && (
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
      )}
    </Card>
  );
}

function FoodCard({ day }: { day: DaySummary }) {
  const target = day.nutritionTarget!;
  const eaten = day.nutrition;
  const burned = (day.restingCalories ?? 0) + day.activeCalories;
  return (
    <Card title="Food" icon={<Salad size={18} />} link={{ to: `/food?date=${day.date}`, label: "Open Food" }}>
      <div>
        <div className="big-number">{num(eaten.calories)}<small>of {num(target.calories)} kcal</small></div>
        <p className="muted small">{eaten.calories <= target.calories ? `${num(target.calories - eaten.calories)} kcal left` : `${num(eaten.calories - target.calories)} kcal over`}{day.restingCalories != null ? ` · ${num(burned)} burned so far` : ""}</p>
      </div>
      <Meter value={eaten.calories} max={target.calories} color="var(--c1)" label="Calories eaten against your target" over />
      <Macros eaten={eaten} target={target} />
    </Card>
  );
}

/** Drinks today: water against its goal with a quick +250 ml (while water shows), caffeine and alcohol. */
function DrinksCard({ day, date, water, readOnly, onDrink, onWater }: { day: DaySummary; date: string; water: boolean; readOnly: boolean; onDrink: () => void; onWater: () => void }) {
  const drinks = day.drinks;
  const goal = day.nutritionTarget?.waterMl ?? day.goals.waterMl;
  const caffeine = drinks && (drinks.caffeineMg > 0 || drinks.standardDrinks > 0);
  return (
    <Card title="Drinks" icon={<Coffee size={18} />} link={{ to: `/food?date=${date}#drinks`, label: "Open Food" }}
      action={!readOnly && <CardAction label="Log a drink" onClick={onDrink}><Plus size={18} /></CardAction>}>
      {water || caffeine ? (
        <>
          {water && (
            <div className="macro water-row">
              <span className="macro-name"><GlassWater size={14} /> Water</span>
              <Meter value={day.waterMl} max={goal} color="var(--c3)" label="Water against your goal" />
              <span className="macro-amount"><strong>{num(day.waterMl)}</strong> / {num(goal)} ml</span>
              {!readOnly && <Button variant="tonal" className="btn-small" onClick={onWater} aria-label="Add 250 ml of water">+250 ml</Button>}
            </div>
          )}
          {caffeine && (
            <p className="muted small drinks-line">
              <Coffee size={14} /> {num(drinks!.caffeineMg)} mg caffeine{drinks!.caffeineAtBedtimeMg != null && drinks!.caffeineMg > 0 ? ` (~${num(drinks!.caffeineAtBedtimeMg)} mg left at bedtime)` : ""}
              {drinks!.standardDrinks > 0 ? ` · ${num(drinks!.standardDrinks, 1)} standard ${drinks!.standardDrinks === 1 ? "drink" : "drinks"}` : ""}
            </p>
          )}
        </>
      ) : null}
    </Card>
  );
}

/** Workouts and the check-in, when there are any. */
function AlsoToday({ day }: { day: DaySummary }) {
  const units = useUnits();
  const c = day.checkIn;
  const checkIn = c && (c.mood || c.tags.length > 0 || c.note);
  if (day.workouts.length === 0 && !checkIn) return null;
  return (
    <Card title="Also today" icon={<Dumbbell size={18} />} link={{ to: "/activity", label: "Open Activity" }}>
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
        {checkIn && (
          <li className="row">
            <div className="row-icon"><Smile size={18} /></div>
            <div className="row-main">
              <div className="row-title">Check-in</div>
              <div className="row-sub">{[
                c!.mood && `mood ${c!.mood}/5`, c!.energy && `energy ${c!.energy}/5`, c!.stress && `stress ${c!.stress}/5`, c!.tags.join(", "), c!.note,
              ].filter(Boolean).join(" · ")}</div>
            </div>
          </li>
        )}
      </ul>
    </Card>
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
