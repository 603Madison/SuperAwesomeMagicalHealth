import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { create } from "zustand";
import {
  ArrowUpDown, CalendarClock, CalendarHeart, Camera, Coffee, CupSoda, Droplet, Droplets, Dumbbell, GlassWater, Heart, HeartPulse, Layers, ListChecks,
  Moon, Pill, Ruler, Salad, Smile, Sparkles, Stethoscope, Waves, Weight, Wind, Zap, type LucideIcon,
} from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import { F, useFields, useWater } from "../lib/fields";
import { today, useUnits } from "../lib/format";
import { useScreenLayout } from "../lib/layout";
import { useSession } from "../lib/session";
import { actionIds, hourIn, knownAction, mealAt, type ActionIds } from "../lib/today";
import { BODY_MEASUREMENTS, LAYOUT_QUICK, QUICK_ACTIONS, arrange, type Meal, type NowItem, type QuickAction } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { useMealie } from "../pages/Mealie";
import { MoodDialog } from "../pages/Mind";
import { BloodPressureDialog, GlucoseDialog } from "../pages/Vitals";
import { SymptomDialog } from "../pages/Symptoms";
import { DrinkDialog, quickDrink } from "../pages/Drinks";
import { MomentDialog } from "../pages/Transition";
import { QuickCheckDialog } from "../pages/EnergyFocus";
import { QuickChange } from "../pages/Wearing";
import { CheckInDialog, SleepDialog, WeightDialog, WorkoutDialog } from "./LogDialogs";
import { MeasureDialog } from "./Measurements";
import { ArrangePanel } from "./Arrange";
import { Modal, errorToast, toast, useLoad } from "./ui";
import { markedToast } from "./doseUndo";

/*
 * The + : one way to log anything, from the tabs on a phone and the sidebar on a wide screen.
 * "Right now" first (what's on to change, a dose that's due, the meal it is), then everything the
 * person tracks as tiles, in their order (the shared quick actions' arrangement, "today.quick"),
 * each opening the log it always did. Never while viewing someone's share.
 *
 * Pages that show what was logged follow [useLogged], so they load again after each log.
 */

const useLogStore = create<{ open: boolean; changed: number }>(() => ({ open: false, changed: 0 }));

/** Opens the + sheet. */
export const openLog = () => useLogStore.setState({ open: true });

/** Something was logged: pages that follow [useLogged] load again. */
export const loggedSomething = () => useLogStore.setState((s) => ({ changed: s.changed + 1 }));

/** A number that moves on each time something's logged from the sheet: put it in a page's load. */
export const useLogged = (): number => useLogStore((s) => s.changed);

type Dialog = "weight" | "workout" | "sleep" | "checkin" | "bp" | "glucose" | "symptom" | "drink" | "moment" | "measure" | "mood" | "change" | "energy";

/** Something to log: its shared id (QuickAction), its tile's word, its full name, and what it does. */
interface LogAction {
  id: QuickAction;
  word: string;
  name: string;
  icon: LucideIcon;
  run: () => void;
  when?: boolean;
}

/** The sheet, and the logs it opens: once, around every page. */
export function LogHost() {
  const open = useLogStore((s) => s.open);
  const readOnly = useReadOnly();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [arranging, setArranging] = useState(false);
  const close = () => useLogStore.setState({ open: false });
  const done = () => loggedSomething();
  if (readOnly) return null;
  return (
    <>
      {open && <Sheet onClose={close} onDialog={(d) => { close(); setDialog(d); }} onArrange={() => { close(); setArranging(true); }} />}
      {arranging && <ArrangeLog onClose={() => setArranging(false)} />}
      {dialog && <LogDialog which={dialog} onClose={() => setDialog(null)} onSaved={done} />}
    </>
  );
}

/** Everything that can be logged for the features that are on, in the default order. */
function useActions(onDialog: (d: Dialog) => void): LogAction[] {
  const navigate = useNavigate();
  const on = useModules();
  const { has } = useFields();
  const water = useWater(on);
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const go = (to: string) => () => navigate(to);
  const meal = mealAt(hourIn(Date.now(), zone)).meal;
  const run = (work: () => Promise<unknown>) => async () => { try { await work(); loggedSomething(); } catch (e) { errorToast(e); } };
  const all: LogAction[] = [
    { id: "FOOD", word: "Food", name: "Log food", icon: Salad, run: go(`/food?add=${meal}`) },
    { id: "WATER", word: "Water", name: "Water: add 250 ml", icon: GlassWater, when: water, run: run(async () => { await api.addWater(today(), 250); toast("250 ml of water added"); }) },
    { id: "MOOD", word: "Mood", name: "Log mood", icon: Heart, run: () => onDialog("mood") },
    { id: "PERIOD", word: "Period", name: "Log your cycle", icon: CalendarHeart, run: go("/cycle") },
    { id: "BLADDER", word: "Bathroom", name: "Bladder & bowel: a trip, leak or bowel movement", icon: Droplet, run: go("/bladder") },
    { id: "DOSE", word: "Dose", name: "Log a dose", icon: Pill, run: go("/meds") },
    { id: "WEIGHT", word: "Weight", name: "Log weight", icon: Weight, run: () => onDialog("weight") },
    { id: "COFFEE", word: "Coffee", name: "Coffee: log a cup", icon: Coffee, run: run(() => quickDrink("COFFEE", today())) },
    { id: "DRINK", word: "Drink", name: "Log a drink", icon: CupSoda, run: () => onDialog("drink") },
    { id: "WORKOUT", word: "Workout", name: "Log a workout", icon: Dumbbell, run: () => onDialog("workout") },
    { id: "SLEEP", word: "Sleep", name: "Add a night's sleep", icon: Moon, run: () => onDialog("sleep") },
    { id: "CHECK_IN", word: "Check in", name: "Check in", icon: Smile, run: () => onDialog("checkin") },
    { id: "SYMPTOM", word: "Symptom", name: "Log a symptom", icon: Stethoscope, run: () => onDialog("symptom") },
    { id: "BLOOD_PRESSURE", word: "Blood pressure", name: "Log blood pressure", icon: HeartPulse, run: () => onDialog("bp") },
    { id: "GLUCOSE", word: "Glucose", name: "Log glucose", icon: Droplets, run: () => onDialog("glucose") },
    { id: "HABIT", word: "Habits", name: "Tick off a habit", icon: ListChecks, run: go("/habits") },
    // Autism & ADHD's energy and focus check-in, right here (Mind's own budget has no tile).
    { id: "ENERGY", word: "Energy & focus", name: "Check in: energy and focus", icon: Zap, when: has(F.NEURO_ENERGY), run: () => onDialog("energy") },
    { id: "OVERLOAD", word: "Too much", name: "Too much: calm mode", icon: Waves, when: has(F.NEURO_CALM), run: go("/calm") },
    { id: "CALM", word: "Calm", name: "Calm tools", icon: Wind, run: go("/mind#calm") },
    { id: "MOMENT", word: "Euphoria", name: "Log a moment of euphoria", icon: Sparkles, when: has(F.TRANSITION_MOMENTS), run: () => onDialog("moment") },
    { id: "PHOTO", word: "Photo", name: "Add a progress photo", icon: Camera, run: go("/photos") },
    { id: "MEASUREMENTS", word: "Measure", name: "Log body measurements", icon: Ruler, when: has(F.BODY_MEASUREMENTS), run: () => onDialog("measure") },
    { id: "APPOINTMENT", word: "Appointment", name: "Add an appointment", icon: CalendarClock, run: go("/appointments") },
  ];
  const moduleOf = (id: QuickAction) => QUICK_ACTIONS.find((q) => q.id === id)?.module ?? null;
  return all.filter((a) => a.when !== false && (moduleOf(a.id) == null || on.includes(moduleOf(a.id)!)));
}

/** Each meal's name, for one that isn't the one it is now. */
const MEAL_NAMES: Record<Meal, string> = { BREAKFAST: "Breakfast", LUNCH: "Lunch", DINNER: "Dinner", SNACK: "A snack" };

/** Something to do right now, as a big tinted button: its words and what it does. */
interface NowButton {
  key: string;
  icon: LucideIcon;
  title: string;
  sub: string;
  tone: "primary" | "accent";
  run: () => void;
}

function Sheet({ onClose, onDialog, onArrange }: { onClose: () => void; onDialog: (d: Dialog) => void; onArrange: () => void }) {
  const navigate = useNavigate();
  const on = useModules();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const actions = useActions(onDialog);
  const order = useScreenLayout(LAYOUT_QUICK);
  const tiles = arrange(actions, (a) => a.id, order);
  const mealie = useMealie();
  const date = today();
  // What's live now, from the server's day feed: what's on to change, the doses due, and the meal it is.
  const feed = useLoad(() => api.feed(date).catch(() => null), [date]);
  const planned = useLoad(() => (on.includes("NUTRITION") && mealie?.connected && !mealie.paused ? api.mealiePlan(date).catch(() => null) : Promise.resolve(null)), [date, !!mealie?.connected, !!mealie?.paused]);
  const [busy, setBusy] = useState<string | null>(null);
  const now = Date.now();

  const take = async (item: NowItem, ids: ActionIds) => {
    if (!ids.medicationId) return;
    setBusy(item.id);
    try {
      const saved = await api.logDose({ medicationId: ids.medicationId, at: Date.now(), status: "TAKEN", scheduledFor: ids.scheduledFor });
      markedToast(`${item.title} taken`, saved, loggedSomething);
      loggedSomething();
      onClose();
    } catch (e) { errorToast(e); }
    setBusy(null);
  };

  const right: NowButton[] = [];
  for (const item of feed.data?.now ?? []) {
    const action = knownAction(item);
    if (!action) continue;
    const ids = actionIds(action);
    if (action.action === "continence.change") {
      right.push({ key: item.id, icon: Layers, tone: "primary", title: action.label, sub: [item.title, item.detail].filter(Boolean).join(" · "), run: () => onDialog("change") });
    } else if (action.action === "dose.take" && right.filter((r) => r.icon === Pill).length < 2) {
      right.push({ key: item.id, icon: Pill, tone: "primary", title: `${action.label} ${item.title}`, sub: item.detail ?? "", run: () => void take(item, ids) });
    } else if (action.action === "food.add") {
      const meal = ids.meal ?? mealAt(hourIn(now, zone)).meal;
      const name = meal === mealAt(hourIn(now, zone)).meal ? mealAt(hourIn(now, zone)).name : MEAL_NAMES[meal];
      const plan = planned.data?.entries.find((e) => e.meal === "DINNER" && !e.logged) ?? planned.data?.entries.find((e) => e.meal === meal && !e.logged);
      right.push({
        key: item.id, icon: Salad, tone: "accent", title: name, sub: plan ? `Or tonight's plan: ${plan.title}, from Mealie` : item.detail ?? "Add what you're having",
        run: () => { onClose(); navigate(`/food?add=${meal}`); },
      });
    }
  }

  const time = new Date(now).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit", timeZone: zone });
  return (
    <Modal sheet className="log-modal" title="Log" subtitle={`now · ${time}`} onClose={onClose}>
      {right.length > 0 && (
        <section className="log-section" aria-labelledby="log-now">
          <h3 id="log-now" className="log-section-title">Right now</h3>
          <div className="log-now">
            {right.map((a) => (
              <button key={a.key} type="button" className={`log-now-btn tone-${a.tone}`} disabled={busy != null} onClick={a.run}>
                <a.icon size={22} aria-hidden="true" />
                <span className="log-now-text">
                  <span className="log-now-title">{a.title}</span>
                  <span className="log-now-sub">{a.sub}</span>
                </span>
              </button>
            ))}
          </div>
        </section>
      )}
      <section className="log-section" aria-labelledby="log-all">
        <h3 id="log-all" className="log-section-title">{right.length > 0 ? "Anything else" : "Everything you track"}</h3>
        <ul className="log-grid">
          {tiles.map((a) => (
            <li key={a.id}>
              <button type="button" className="log-tile" onClick={() => { onClose(); a.run(); }} aria-label={a.name} title={a.name}>
                <a.icon size={22} aria-hidden="true" />
                <span aria-hidden="true">{a.word}</span>
              </button>
            </li>
          ))}
        </ul>
        <p className="log-hint">
          Only what you track shows here, in your order.
          <button type="button" className="link-btn" onClick={onArrange}><ArrowUpDown size={14} aria-hidden="true" /> Arrange</button>
        </p>
      </section>
    </Modal>
  );
}

/** Putting the tiles in order: the same order the phone app's + uses. */
function ArrangeLog({ onClose }: { onClose: () => void }) {
  const actions = useActions(() => {});
  return (
    <ArrangePanel screen={LAYOUT_QUICK} title="what you log" items={actions.map((a) => ({ id: a.id, label: a.name }))} reorderOnly
      intro="Put what you log most at the top: the + shows it first, here and in the phone app." onClose={onClose} />
  );
}

/** The log a tile opens, right here. */
function LogDialog({ which, onClose, onSaved }: { which: Dialog; onClose: () => void; onSaved: () => void }) {
  const units = useUnits();
  const glucoseUnit = useSession((s) => s.profile?.vitalsTargets?.glucoseUnit) ?? "MMOL_L";
  const date = today();
  const checkIn = useLoad(() => (which === "checkin" ? api.checkIn(date).catch(() => undefined) : Promise.resolve(undefined)), [which, date]);
  switch (which) {
    case "mood": return <MoodDialog initial={3} onClose={onClose} onSaved={onSaved} />;
    case "change": return <QuickChange onClose={onClose} onSaved={onSaved} />;
    case "bp": return <BloodPressureDialog onClose={onClose} onSaved={onSaved} />;
    case "glucose": return <GlucoseDialog unit={glucoseUnit} onClose={onClose} onSaved={onSaved} />;
    case "symptom": return <SymptomDialog initial={{ symptom: "" }} onClose={onClose} onSaved={onSaved} />;
    case "drink": return <DrinkDialog date={date} onClose={onClose} onSaved={onSaved} />;
    case "weight": return <WeightDialog onClose={onClose} onSaved={onSaved} />;
    case "workout": return <WorkoutDialog onClose={onClose} onSaved={onSaved} />;
    case "sleep": return <SleepDialog onClose={onClose} onSaved={onSaved} />;
    case "checkin": return checkIn.loading ? null : <CheckInDialog date={date} initial={checkIn.data ?? undefined} onClose={onClose} onSaved={onSaved} />;
    case "moment": return <MomentDialog kind="EUPHORIA" onClose={onClose} onSaved={onSaved} />;
    case "energy": return <QuickCheckDialog onClose={onClose} onSaved={onSaved} />;
    case "measure": return <MeasureDialog imperial={units === "IMPERIAL"} metrics={BODY_MEASUREMENTS.map((x) => x.id)} onClose={onClose} onSaved={onSaved} />;
  }
}

