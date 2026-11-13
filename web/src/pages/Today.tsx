import { Fragment, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Activity as ActivityIcon, Baby, Brain, CalendarClock, CalendarHeart, Camera, ChevronRight, CircleDashed, Coffee, CupSoda, Droplet, Droplets, Dumbbell, Gauge, GlassWater,
  Heart, HeartPulse, Infinity as InfinityIcon, ListChecks, Moon, Pill, Plus, Ruler, Salad, Smile, Sparkles, Stethoscope, Syringe, ThermometerSun, Watch, Waves,
  Weight, Wind, type LucideIcon,
} from "lucide-react";
import { FirstRunPrompt } from "./Conditions";
import { useModules } from "../App";
import { MoodDialog } from "./Mind";
import { api } from "../lib/api";
import { F, useFields, useFoodNumbers, useWater } from "../lib/fields";
import { clock, dayLabel, today, useUnits } from "../lib/format";
import { useScreenLayout } from "../lib/layout";
import { useSession } from "../lib/session";
import { QUICK_ROW, dosesLater, quickRow } from "../lib/today";
import { useReadOnly, useViewing } from "../lib/viewing";
import {
  BODY_MEASUREMENTS, LAYOUT_QUICK, LAYOUT_TODAY, QUICK_ACTIONS, TODAY_SECTION_LABELS, arrange, type DaySummary, type Module, type QuickAction, type Score,
  type TodaySection,
} from "../lib/types";
import { Meter } from "../components/charts";
import { Button, CardAction, Card, DateNav, ErrorNotice, Modal, Segmented, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { ArrangeButton, ArrangePanel, useArrange, type Section } from "../components/Arrange";
import { ACTIVITY_METRICS, HEART_METRICS, SourcesLink, combinedFor } from "../components/Sources";
import { CheckInDialog, SleepDialog, WeightDialog, WorkoutDialog } from "../components/LogDialogs";
import { ForYouCard, InsightRow } from "../components/Insights";
import { MeasureDialog } from "../components/Measurements";
import { FEATURES } from "../components/features";
import { BloodPressureDialog, GlucoseDialog } from "./Vitals";
import { SymptomDialog } from "./Symptoms";
import { DrinkDialog, quickDrink } from "./Drinks";
import { MomentDialog } from "./Transition";
import {
  DayCard, activityLine, bodyLine, continenceLine, cycleLine, drinksLine, foodLine, habitsLine, heartLine, hormonesLine, lifeStageLine, lifeStageTitle,
  mindLine, neuroLine, sleepLine, stressLine, symptomsLine, transitionLine, useDigestRow, vitalsLine, waterLine, workoutsLine, type Headline,
} from "./TodayCards";
import { UpNextCard, useUpNext } from "./UpNext";
import { LogSheet, QuickRow, type LogAction, type LogGroup } from "./LogSheet";

/*
 * Today, top to bottom: the scores; Up next (what needs doing now); For you (a few things worth
 * knowing); the quick row; "Your day", a card for each feature with something today; and "Also
 * today", one row for each feature that's on with nothing yet. Everything to log is under + Log.
 * The sections can be moved and hidden (Arrange); their ids are shared with the phone app.
 */

const RINGS: { key: "readiness" | "sleep" | "activity"; label: string; color: string }[] = [
  { key: "readiness", label: "Readiness", color: "var(--c2)" },
  { key: "sleep", label: "Sleep", color: "var(--c1)" },
  { key: "activity", label: "Activity", color: "var(--c3)" },
];

type Dialog = "weight" | "workout" | "sleep" | "checkin" | "bp" | "glucose" | "symptom" | "drink" | "moment" | "measure";

/** A row in "Also today": a feature that's on with nothing yet, and a + that logs. */
interface AlsoRow {
  icon: LucideIcon;
  name: string;
  /** "Nothing yet", unless there's something better to say ("None set up", "Next at 22:00"). */
  state?: string;
  log?: { label: string; run: () => void };
}

/** One of Today's sections: full width, or a feature card in "Your day" ([card]) with its Also today row for days with nothing. */
interface Part extends Section {
  id: TodaySection;
  card?: boolean;
  also?: AlsoRow | null;
}

export function Today() {
  const [date, setDate] = useState(today());
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date]);
  const user = useSession((s) => s.user)!;
  const [open, setOpen] = useState<Dialog | null>(null);
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [mood, setMood] = useState<number | null>(null);
  const [logging, setLogging] = useState(false);
  const [arranging, setArranging] = useState<"cards" | "quick" | null>(null);
  const on = useModules();
  const { has } = useFields();
  const water = useWater(on);
  const foodNumbers = useFoodNumbers();
  const units = useUnits();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const navigate = useNavigate();
  const glucoseUnit = useSession((s) => s.profile?.vitalsTargets?.glucoseUnit) ?? "MMOL_L";
  const is = (id: Module) => on.includes(id);
  const isToday = date === today();
  const rings = RINGS.filter((r) => r.key === "readiness" ? is("SLEEP") || is("ACTIVITY") : r.key === "sleep" ? is("SLEEP") : is("ACTIVITY"));
  // Until something's picked in What you're tracking: a gentle prompt, dismissed per browser.
  const [skipFirstRun, setSkipFirstRun] = useState(() => { try { return localStorage.getItem("samh.firstRun.skip") === "1"; } catch { return false; } });
  const conditions = useLoad(() => (viewing || skipFirstRun ? Promise.resolve(null) : api.conditions().catch(() => null)), [viewing, skipFirstRun]);
  const showFirstRun = !viewing && !skipFirstRun && conditions.data != null && conditions.data.conditions.length === 0;
  // In eating disorder recovery the Food card says which meals were logged: the day's summary only has amounts.
  const food = useLoad(() => (is("NUTRITION") && !foodNumbers ? api.foodDay(date).catch(() => null) : Promise.resolve(null)), [date, foodNumbers, is("NUTRITION")]);
  const hour = new Date().getHours();
  const greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  const m = data?.modules;
  const lifeStage = is("CYCLE") ? m?.lifeStage : undefined;
  const lifeStageShown = !!lifeStage && lifeStage.stage !== "CYCLING" && lifeStage.stage !== "TRYING";
  const layout = useScreenLayout(LAYOUT_TODAY);
  const hidden = new Set(layout?.hidden ?? []);
  const upNext = useUpNext(data, date, on, hidden, readOnly);
  const changed = () => { reload(); food.reload(); upNext.reload(); };

  const addWater = async () => {
    try { await api.addWater(date, 250); toast("250 ml of water added"); changed(); } catch (e) { errorToast(e); }
  };
  const coffee = async () => { try { await quickDrink("COFFEE", date); changed(); } catch (e) { errorToast(e); } };

  // ---- Everything to log, with the shared quick-action ids: the quick row's first few, and all of it under + Log ----
  const actions: (Omit<LogAction, "module"> & { when?: boolean })[] = [
    { id: "FOOD", label: "Food", short: "Food", name: "Log food", icon: Salad, run: () => navigate(`/food?date=${date}`) },
    { id: "WATER", label: "Water, 250 ml", short: "Water", name: "Water: add 250 ml", icon: GlassWater, run: addWater, when: water },
    { id: "MOOD", label: "Mood", short: "Mood", name: "Log mood", icon: Heart, run: () => setMood(3) },
    { id: "PERIOD", label: lifeStageShown ? "Cycle" : "Period", short: lifeStageShown ? "Cycle" : "Period", icon: CalendarHeart, run: () => navigate("/cycle") },
    { id: "BLADDER", label: "Trip, leak or bowel", short: "Bladder", name: "Bladder: trip, leak or bowel", icon: Droplet, run: () => navigate("/bladder") },
    { id: "DOSE", label: "A dose", short: "Dose", name: "Log a dose", icon: Pill, run: () => navigate("/meds") },
    { id: "WEIGHT", label: "Weight", short: "Weight", name: "Log weight", icon: Weight, run: () => setOpen("weight") },
    { id: "COFFEE", label: "Coffee", short: "Coffee", name: "Coffee: log a cup", icon: Coffee, run: coffee },
    { id: "DRINK", label: "A drink", short: "Drink", name: "Log a drink", icon: CupSoda, run: () => setOpen("drink") },
    { id: "WORKOUT", label: "Workout", short: "Workout", name: "Log a workout", icon: Dumbbell, run: () => setOpen("workout") },
    { id: "SLEEP", label: "A night's sleep", short: "Sleep", name: "Add a night's sleep", icon: Moon, run: () => setOpen("sleep") },
    { id: "CHECK_IN", label: "Check in", short: "Check in", icon: Smile, run: () => setOpen("checkin"), on: !!data?.checkIn?.mood },
    { id: "SYMPTOM", label: "Symptom", short: "Symptom", name: "Log a symptom", icon: Stethoscope, run: () => setOpen("symptom") },
    { id: "BLOOD_PRESSURE", label: "Blood pressure", short: "BP", name: "BP: log blood pressure", icon: HeartPulse, run: () => setOpen("bp") },
    { id: "GLUCOSE", label: "Glucose", short: "Glucose", name: "Log glucose", icon: Droplets, run: () => setOpen("glucose") },
    { id: "HABIT", label: "Habits", short: "Habits", icon: ListChecks, run: () => navigate("/habits") },
    { id: "ENERGY", label: "Energy & focus", short: "Energy", icon: Sparkles, run: () => navigate("/neuro") },
    { id: "OVERLOAD", label: "Too much: calm mode", short: "Too much", icon: Waves, run: () => navigate("/calm"), when: has(F.NEURO_CALM) },
    { id: "CALM", label: "Calm tools", short: "Calm", icon: Wind, run: () => navigate("/mind#calm") },
    { id: "MOMENT", label: "Euphoria", short: "Euphoria", name: "Log a moment of euphoria", icon: Sparkles, run: () => setOpen("moment"), when: has(F.TRANSITION_MOMENTS) },
    { id: "PHOTO", label: "Progress photo", short: "Photo", icon: Camera, run: () => navigate("/photos") },
    { id: "MEASUREMENTS", label: "Measurements", short: "Measure", icon: Ruler, run: () => setOpen("measure"), when: has(F.BODY_MEASUREMENTS) },
    { id: "APPOINTMENT", label: "Appointment", short: "Visit", name: "Visit: add an appointment", icon: CalendarClock, run: () => navigate("/appointments") },
  ];
  const moduleOf = (id: QuickAction) => QUICK_ACTIONS.find((q) => q.id === id)?.module ?? null;
  const offered: LogAction[] = actions.filter((a) => a.when !== false && (moduleOf(a.id) == null || is(moduleOf(a.id)!))).map((a) => ({ ...a, module: moduleOf(a.id) }));
  const quickLayout = useScreenLayout(LAYOUT_QUICK);
  const quickOrdered = arrange(offered, (a) => a.id, quickLayout);
  const quick = quickRow(quickOrdered, (a) => a.id, quickLayout);
  // + Log's groups: each feature once, in the order its first action comes in the person's quick order.
  const groups: LogGroup[] = [];
  for (const a of quickOrdered) {
    const feature = a.module ? FEATURES.find((f) => f.id === a.module) : undefined;
    const name = feature?.name ?? "Check-in";
    const group = groups.find((g) => g.name === name);
    if (group) group.actions.push(a);
    else groups.push({ name, actions: [a] });
  }

  // ---- Today's sections, in the default order (TodaySection, shared with the phone app) ----
  const nothing = isToday ? "Nothing yet" : "Nothing logged";
  /** A feature's card while it has something today ([line]); otherwise its row in Also today, unless [also] is null. */
  const feature = (id: TodaySection, when: boolean, card: {
    title: string; icon: LucideIcon; to: string; open: string; line: Headline | null; action?: ReactNode; extra?: ReactNode;
  }, also?: Omit<AlsoRow, "icon" | "name"> | null): Part => ({
    id, label: TODAY_SECTION_LABELS[id], when, card: true,
    node: card.line && (
      <DayCard title={card.title} icon={<card.icon size={18} />} link={{ to: card.to, label: `Open ${card.open}` }}
        action={!readOnly && card.action} headline={card.line} extra={card.extra} />
    ),
    also: card.line || also === null || also === undefined ? null : { icon: card.icon, name: card.title, ...also },
  });
  const log = (label: string, run: () => void) => (readOnly ? undefined : { label, run });
  const plus = (label: string, run: () => void) => <CardAction label={label} onClick={run}><Plus size={18} /></CardAction>;

  const meds = m?.meds;
  const dosesShown = upNext.items.some((i) => i.kind === "dose");
  const takenToday = (meds?.due ?? []).some((d) => d.log) || (meds?.extra ?? []).length > 0;
  const nextDose = isToday ? dosesLater(meds?.due ?? [], Date.now())[0] : undefined;
  const workouts = data ? workoutsLine(data, units, foodNumbers) : null;
  const neuro = m?.neuro;
  const habits = m?.habits ?? [];

  const sections: Part[] = [
    { id: "SCORES", label: TODAY_SECTION_LABELS.SCORES, when: rings.length > 0,
      node: data && <Scores day={data} rings={rings} readOnly={readOnly} onOpen={(title, s) => setScore({ title, score: s })} /> },
    { id: "UP_NEXT", label: TODAY_SECTION_LABELS.UP_NEXT, node: <UpNextCard items={upNext.items} readOnly={readOnly} onChanged={changed} /> },
    { id: "INSIGHTS", label: TODAY_SECTION_LABELS.INSIGHTS, node: null /* filled in below, once what's showing is known */ },
    { id: "QUICK", label: TODAY_SECTION_LABELS.QUICK, when: !readOnly, node: offered.length > 0 && <QuickRow actions={quick.shown} onMore={() => setLogging(true)} /> },
    // The day's doses are in Up next; with none due and none taken yet, Medications is a row in Also today.
    { id: "MEDS", label: TODAY_SECTION_LABELS.MEDS, when: is("MEDICATIONS"), card: true, node: null,
      also: dosesShown || takenToday ? null : { icon: Pill, name: "Medications", state: nextDose ? `Next at ${clock(nextDose.scheduledFor)}` : undefined, log: log("Log a dose", () => navigate("/meds")) } },
    feature("MIND", is("MIND"), { title: "Mind", icon: Brain, to: "/mind", open: "Mind", line: mindLine(m?.mind), action: plus("Log mood", () => setMood(3)) },
      { log: log("Log mood", () => setMood(3)) }),
    feature("CYCLE", is("CYCLE"), { title: "Cycle", icon: CalendarHeart, to: "/cycle", open: "Cycle", line: lifeStageShown ? null : cycleLine(m?.cycle) },
      lifeStageShown ? null : { log: log("Log your cycle", () => navigate("/cycle")) }),
    feature("CONTINENCE", is("CONTINENCE"), { title: "Bladder & bowel", icon: Droplet, to: "/bladder", open: "Bladder & bowel", line: continenceLine(m?.continence) },
      { log: log("Log a trip or leak", () => navigate("/bladder")) }),
    // Supplies to restock are in Up next.
    { id: "SUPPLIES", label: TODAY_SECTION_LABELS.SUPPLIES, when: is("CONTINENCE") || is("CYCLE"), card: true, node: null },
    feature("NEURO", is("NEURO"), {
      title: "Autism & ADHD", icon: InfinityIcon, to: "/neuro", open: "Autism & ADHD", line: neuroLine(neuro),
      action: (neuro?.overloadsToday ?? 0) > 0 && has(F.NEURO_CALM) && <CardAction label="Calm mode" onClick={() => navigate("/calm")}><Waves size={18} /></CardAction>,
    }, { log: log("Log energy and focus", () => navigate("/neuro")) }),
    feature("TRANSITION", is("TRANSITION"), { title: "Transition", icon: Sparkles, to: "/transition", open: "Transition", line: transitionLine(m?.transition) },
      { log: log("Log a moment", () => (has(F.TRANSITION_MOMENTS) ? setOpen("moment") : navigate("/transition"))) }),
    feature("VITALS", is("VITALS"), {
      title: "Blood pressure & glucose", icon: HeartPulse, to: "/vitals", open: "Blood pressure & glucose", line: vitalsLine(m?.vitals, glucoseUnit),
      action: plus("Log blood pressure", () => setOpen("bp")),
    }, { log: log("Log blood pressure", () => setOpen("bp")) }),
    feature("DRINKS", is("NUTRITION"), { title: "Drinks", icon: Coffee, to: `/food?date=${date}#drinks`, open: "Food", line: data ? drinksLine(data) : null, action: plus("Log a drink", () => setOpen("drink")) },
      { log: log("Log a drink", () => setOpen("drink")) }),
    feature("SYMPTOMS", is("SYMPTOMS"), { title: "Symptoms", icon: Stethoscope, to: "/symptoms", open: "Symptoms", line: symptomsLine(m?.symptoms), action: plus("Log a symptom", () => setOpen("symptom")) },
      { log: log("Log a symptom", () => setOpen("symptom")) }),
    feature("HABITS", is("HABITS"), { title: "Habits", icon: ListChecks, to: "/habits", open: "Habits", line: habitsLine(habits) },
      { state: "None set up", log: log("Add a habit", () => navigate("/habits?new=1")) }),
    feature("HORMONES", is("HORMONES"), { title: "Hormone therapy", icon: Syringe, to: "/hormones", open: "Hormone therapy", line: hormonesLine(m?.hormones) }, null),
    feature("LIFE_STAGE", is("CYCLE"), {
      title: lifeStage && lifeStageShown ? lifeStageTitle(lifeStage) : "Life stage", icon: lifeStage?.stage === "PREGNANT" || lifeStage?.stage === "POSTPARTUM" ? Baby : ThermometerSun,
      to: "/cycle", open: "Cycle", line: lifeStage && lifeStageShown ? lifeStageLine(lifeStage) : null,
    }, null),
    // Today's appointment is in Up next.
    { id: "APPOINTMENTS", label: TODAY_SECTION_LABELS.APPOINTMENTS, when: is("APPOINTMENTS"), card: true, node: null },
    feature("ACTIVITY", is("ACTIVITY"), {
      title: "Activity", icon: ActivityIcon, to: "/activity", open: "Activity", line: data ? activityLine(data, units, foodNumbers) : null,
      action: plus("Log a workout", () => setOpen("workout")),
      extra: data && <SourcesLink values={combinedFor(data.combined, ACTIVITY_METRICS)} onChanged={changed} />,
    }, { log: log("Log a workout", () => setOpen("workout")) }),
    feature("SLEEP", is("SLEEP"), {
      title: "Sleep", icon: Moon, to: "/sleep", open: "Sleep", line: data ? sleepLine(data) : null,
      extra: data?.sleepSummary?.combined && data.sleepSummary.combined.sources.length > 1 && <SourcesLink sleep={data.sleepSummary.combined} onChanged={changed} />,
    }, { log: log("Add a night", () => setOpen("sleep")) }),
    feature("HEART", true, {
      title: "Heart", icon: HeartPulse, to: "/trends", open: "Trends", line: data ? heartLine(data) : null,
      extra: data && <SourcesLink values={combinedFor(data.combined, HEART_METRICS)} onChanged={changed} />,
    }, null),
    feature("STRESS", is("ACTIVITY"), {
      title: "Stress", icon: Gauge, to: "/activity", open: "Activity", line: stressLine(data?.stressDay, data?.resilience),
      extra: data && <SourcesLink values={combinedFor(data.combined, ["STRESS"])} onChanged={changed} />,
    }, null),
    feature("FOOD", is("NUTRITION"), {
      title: "Food", icon: Salad, to: `/food?date=${date}`, open: "Food", line: data ? foodLine(data, foodNumbers, food.data?.entries) : null,
      action: plus("Log food", () => navigate(`/food?date=${date}`)),
    }, { log: log("Log food", () => navigate(`/food?date=${date}`)) }),
    feature("WATER", water, { title: "Water", icon: GlassWater, to: `/food?date=${date}#water`, open: "Food", line: data ? waterLine(data) : null, action: plus("Add 250 ml of water", addWater) },
      { log: log("Add 250 ml of water", addWater) }),
    feature("WORKOUTS", true, { title: workouts?.title ?? "Workouts", icon: workouts?.title === "Check-in" ? Smile : Dumbbell, to: "/activity", open: "Activity", line: workouts?.line ?? null }, null),
    feature("BODY", is("BODY"), { title: "Body", icon: Weight, to: "/body", open: "Body", line: data ? bodyLine(data, units) : null, action: plus("Log weight", () => setOpen("weight")) },
      { log: log("Log weight", () => setOpen("weight")) }),
    { id: "ALSO", label: TODAY_SECTION_LABELS.ALSO, node: null /* filled in below, from the cards with nothing today */ },
  ];

  const cards = useArrange(LAYOUT_TODAY, "Today", sections);
  const shown = cards.shown as Part[];
  // What's on the screen, so an insight that repeats it is left out: the day's doses while Up next has them.
  const showing = new Set<string>(shown.filter((s) => s.node).map((s) => s.id));
  if (dosesShown && showing.has("UP_NEXT")) showing.add("MEDS");
  const openScores = () => {
    if (data?.readiness) setScore({ title: "Readiness", score: data.readiness });
    else document.querySelector(".content")?.scrollTo({ top: 0, behavior: "smooth" });
  };
  const also = shown.filter((s) => s.also && !s.node).map((s) => s.also!);
  const nodeOf = (s: Part): ReactNode => {
    if (s.id === "INSIGHTS") return data && <Insights day={data} on={on} readOnly={readOnly} date={date} showing={showing} onChanged={changed} onScores={openScores} />;
    if (s.id === "ALSO") return data && also.length > 0 && <AlsoToday rows={also} nothing={nothing} />;
    return s.node;
  };

  // Full-width sections one under another; the feature cards between them in a grid.
  const blocks: ReactNode[] = [];
  let grid: ReactNode[] = [];
  const flush = () => {
    if (grid.length > 0) blocks.push(<div key={`grid-${blocks.length}`} className="day-grid">{grid}</div>);
    grid = [];
  };
  for (const s of shown) {
    const node = nodeOf(s);
    if (!node) continue;
    if (s.card) grid.push(<Fragment key={s.id}>{node}</Fragment>);
    else { flush(); blocks.push(<Fragment key={s.id}>{node}</Fragment>); }
  }
  flush();

  return (
    <div className="page today">
      <header className="page-header today-header">
        <div className="today-title">
          <h1>{viewing ? `${viewing.ownerName}'s ${isToday ? "day" : dayLabel(date).toLowerCase()}` : isToday ? `${greeting}, ${user.displayName.split(" ")[0]}` : dayLabel(date)}</h1>
          <p className="page-subtitle">{new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}</p>
        </div>
        <div className="page-actions">
          <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={today()} />
          <ArrangeButton title="Today" onClick={() => setArranging("cards")} />
          {!readOnly && <Button variant="primary" className="log-btn" onClick={() => setLogging(true)} aria-haspopup="dialog"><Plus size={18} aria-hidden="true" /> Log</Button>}
        </div>
      </header>

      {showFirstRun && isToday && <FirstRunPrompt onDismiss={() => { try { localStorage.setItem("samh.firstRun.skip", "1"); } catch { /* fine */ } setSkipFirstRun(true); }} />}
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div className="today-stack" style={{ opacity: loading ? 0.6 : 1, transition: "opacity 0.2s" }}>
          {blocks}
          {shown.length === 0 && !readOnly && <p className="muted arranged-empty">Everything on Today is hidden. <button type="button" className="link-btn" onClick={() => setArranging("cards")}>Arrange</button> to show it again.</p>}
        </div>
      )}

      {logging && <LogSheet groups={groups} onClose={() => setLogging(false)} onArrange={() => setArranging("quick")} />}
      {arranging && (
        <ArrangePanel
          key={arranging}
          screen={arranging === "quick" ? LAYOUT_QUICK : LAYOUT_TODAY}
          title={arranging === "quick" ? "the quick row" : "Today"}
          items={arranging === "quick" ? offered : sections.filter((s) => s.when !== false)}
          defaultHidden={arranging === "quick" ? quickOrdered.slice(QUICK_ROW).map((a) => a.id) : []}
          hiddenText={arranging === "quick" ? "Under More" : "Hidden"}
          showLabel={arranging === "quick" ? (l) => `Show ${l} in the row` : undefined}
          intro={arranging === "quick" ? `The first ${QUICK_ROW} switched on show in Today's row; the rest wait under More. Everything is under + Log too.` : undefined}
          top={!readOnly && (
            <Segmented label="What to arrange" value={arranging} onChange={setArranging} options={[{ value: "cards", label: "Sections" }, { value: "quick", label: "Quick row" }]} />
          )}
          onClose={() => setArranging(null)}
        />
      )}
      {score && <ScoreDialog title={score.title} score={score.score} onClose={() => setScore(null)} />}
      {open === "bp" && <BloodPressureDialog onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "glucose" && <GlucoseDialog unit={glucoseUnit} onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "symptom" && <SymptomDialog initial={{ symptom: "" }} onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "drink" && <DrinkDialog date={date} onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "weight" && <WeightDialog onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "workout" && <WorkoutDialog onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "sleep" && <SleepDialog onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "checkin" && <CheckInDialog date={date} initial={data?.checkIn} onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "moment" && <MomentDialog kind="EUPHORIA" onClose={() => setOpen(null)} onSaved={changed} />}
      {open === "measure" && <MeasureDialog imperial={units === "IMPERIAL"} metrics={BODY_MEASUREMENTS.map((x) => x.id)} onClose={() => setOpen(null)} onSaved={changed} />}
      {mood != null && <MoodDialog initial={mood} onClose={() => setMood(null)} onSaved={changed} />}
    </div>
  );
}

/** A score's ring, small: the arc and the number. */
function MiniRing({ score, color }: { score?: number; color: string }) {
  const size = 48;
  const stroke = 5;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  return (
    <svg className="mini-ring" width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--ring-track)" strokeWidth={stroke} />
      {score != null && (
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
          strokeDasharray={`${(c * score) / 100} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} className="ring-arc" />
      )}
      <text x="50%" y="50%" textAnchor="middle" dominantBaseline="central" className="ring-number" style={{ fontSize: 15 }}>{score ?? "–"}</text>
    </svg>
  );
}

/** The three scores as a slim strip; with no scores yet, one quiet line about where they come from. */
function Scores({ day, rings, readOnly, onOpen }: { day: DaySummary; rings: typeof RINGS; readOnly: boolean; onOpen: (title: string, s: Score) => void }) {
  if (!rings.some((r) => day[r.key] != null)) {
    return (
      <p className="score-line">
        <Watch size={16} aria-hidden="true" />
        <span>{readOnly ? "No scores for this day" : "Scores appear after a night with your ring or watch"}</span>
        {!readOnly && <Link to="/devices" className="score-line-link">Devices <ChevronRight size={16} aria-hidden="true" /></Link>}
      </p>
    );
  }
  return (
    <section className="score-strip" aria-label="Scores" style={{ gridTemplateColumns: `repeat(${rings.length}, minmax(0, 1fr))` }}>
      {rings.map((r) => {
        const s = day[r.key];
        const body = (
          <>
            <MiniRing score={s?.score} color={r.color} />
            <span className="score-text">
              <span className="score-name">{r.label}</span>
              <span className="score-word">{s?.label ?? "Not enough data"}</span>
            </span>
          </>
        );
        return s
          ? <button key={r.key} type="button" className="score-item" onClick={() => onOpen(r.label, s)} aria-label={`${r.label}: ${s.score}, ${s.label}. What it's made of`}>{body}</button>
          : <div key={r.key} className="score-item" role="img" aria-label={`${r.label}: not enough data yet`}>{body}</div>;
      })}
    </section>
  );
}

/**
 * "For you": the weekly digest on its day, then the day's insights, each a row that opens its page.
 * One that repeats what's showing is left out (the day's doses while Up next has them).
 */
function Insights({ day, on, readOnly, date, showing, onChanged, onScores }: {
  day: DaySummary; on: Module[]; readOnly: boolean; date: string; showing: Set<string>; onChanged: () => void; onScores: () => void;
}) {
  const digest = useDigestRow(!readOnly && date === today());
  const list = day.insights.filter((i) => (!i.module || on.includes(i.module)) && !(i.shownBy && showing.has(i.shownBy)));
  return (
    <ForYouCard>
      {[
        digest,
        ...list.map((i) => (
          <InsightRow
            key={`${i.icon}-${i.title}`} insight={i} date={date} readOnly={readOnly} onChanged={onChanged}
            onPage={(page) => { if (page !== "TODAY") return false; onScores(); return true; }}
          />
        )),
      ]}
    </ForYouCard>
  );
}

/** The features that are on with nothing today, one short row each, with a + that logs (not while viewing a share). */
function AlsoToday({ rows, nothing }: { rows: AlsoRow[]; nothing: string }) {
  return (
    <Card title="Also today" icon={<CircleDashed size={18} />} heading="h2" className="also-today">
      <ul className="also-rows">
        {rows.map((r) => (
          <li key={r.name} className="also-row">
            <span className="also-icon" aria-hidden="true"><r.icon size={18} /></span>
            <span className="also-name">{r.name}</span>
            <span className="also-state">{r.state ?? nothing}</span>
            {r.log && <button type="button" className="icon-btn also-add" aria-label={r.log.label} title={r.log.label} onClick={r.log.run}><Plus size={18} /></button>}
          </li>
        ))}
      </ul>
    </Card>
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
