import type { ReactNode } from "react";
import { Inbox } from "lucide-react";
import { FACE_LABELS, FACES } from "./Mind";
import { api, ApiError } from "../lib/api";
import { clock, distance, hm, num, parseDate, today, weightValue, workoutLabel } from "../lib/format";
import { useSession } from "../lib/session";
import { mealsLogged } from "../lib/today";
import { BP_TEXT, glucoseText } from "../lib/vitals";
import type {
  CheckIn, ContinenceDay, CycleToday, DaySummary, Digest, FoodEntry, GlucoseUnit, HabitStatus, HormonesToday, LifeStageToday, MindDay, NeuroToday,
  Resilience, StressDay, SymptomsDay, TransitionToday, Units, VitalsToday, Workout,
} from "../lib/types";
import { Card, useLoad } from "../components/ui";
import { severityWord } from "./Symptoms";
import { weekText } from "./Digest";
import { InsightRow } from "../components/Insights";

/*
 * Today's feature cards ("Your day"). Every one is the same: the heading row (icon, title, at most
 * one action, the chevron to the feature's page), then one headline (a number with its unit, or a
 * short line) and at most one supporting line. Charts and details live on the feature's page.
 *
 * Each feature's headline comes from a function here, which gives null when there's nothing today:
 * then Today puts the feature in "Also today" instead of showing a card.
 */

/** A card's one headline and its supporting line. */
export interface Headline {
  /** The number, or a short line ([text]). */
  value: ReactNode;
  unit?: string;
  /** A line of words rather than a number: a little smaller, so it fits. */
  text?: boolean;
  support?: string;
}

export function DayCard({ title, icon, link, action, headline, extra }: {
  title: string; icon: ReactNode; link: { to: string; label: string }; action?: ReactNode; headline: Headline;
  /** After the supporting line: "From 2 devices". */
  extra?: ReactNode;
}) {
  return (
    <Card title={title} icon={icon} link={link} action={action || undefined} heading="h2" className="day-card">
      <div className="day-body">
        <p className={`day-value ${headline.text ? "is-text" : ""}`}>
          <span>{headline.value}</span>
          {headline.unit && <span className="day-unit">{headline.unit}</span>}
        </p>
        {(headline.support || extra) && (
          <div className="day-support">
            {headline.support && <span>{headline.support}</span>}
            {extra}
          </div>
        )}
      </div>
    </Card>
  );
}

const join = (parts: (string | false | null | undefined)[]) => parts.filter(Boolean).join(" · ");
const count = (n: number, one: string, many = `${one}s`) => `${num(n)} ${n === 1 ? one : many}`;
const firstFew = (names: string[], n = 3) => {
  const unique = [...new Set(names)];
  return unique.length > n ? `${unique.slice(0, n).join(", ")} and ${unique.length - n} more` : unique.join(", ");
};

export function mindLine(mind?: MindDay): Headline | null {
  if (!mind) return null;
  if (mind.mood != null) {
    const i = Math.min(4, Math.max(0, Math.round(mind.mood) - 1));
    return { value: `${FACES[i]} ${FACE_LABELS[i]}`, support: join([`Mood ${mind.mood.toFixed(1)} of 5`, count(mind.entries, "entry", "entries"), mind.mindfulMinutes > 0 && `${hm(mind.mindfulMinutes)} mindful`]) };
  }
  if (mind.mindfulMinutes > 0) return { value: hm(mind.mindfulMinutes), unit: "mindful", support: mind.journal > 0 ? count(mind.journal, "journal entry", "journal entries") : undefined };
  if (mind.journal > 0) return { value: num(mind.journal), unit: mind.journal === 1 ? "journal entry" : "journal entries" };
  return null;
}

const PHASE: Record<string, string | null> = { PERIOD: "Period", FOLLICULAR: "Follicular phase", FERTILE: "Fertile window (estimate)", LUTEAL: "Luteal phase", UNKNOWN: null };

export function cycleLine(cycle?: CycleToday): Headline | null {
  if (!cycle || (cycle.cycleDay == null && !cycle.logged)) return null;
  const d = cycle.daysUntilPeriod;
  const next = d == null ? null : d === 0 ? "period due today" : d > 0 ? `period in about ${count(d, "day")}` : `${count(-d, "day")} late`;
  const logged = cycle.logged ? [cycle.logged.flow && `${cycle.logged.flow.toLowerCase()} flow`, ...cycle.logged.symptoms].filter(Boolean) as string[] : [];
  if (cycle.cycleDay == null) return { value: "Logged today", text: true, support: logged.length ? firstFew(logged) : undefined };
  return { value: `Day ${cycle.cycleDay}`, support: join([PHASE[cycle.phase], next]) };
}

export function continenceLine(c?: ContinenceDay): Headline | null {
  if (!c) return null;
  const any = c.voids + c.leaks + c.bowel + c.pads + c.pelvicFloor + (c.products ?? 0) + (c.catheters ?? 0) + (c.bowelLeaks ?? 0) + (c.wetNights ?? 0);
  if (any === 0) return null;
  const parts = [
    c.nightVoids > 0 && `${num(c.nightVoids)} at night`,
    c.leaks > 0 && count(c.leaks, "leak"),
    c.bowel > 0 && count(c.bowel, "bowel movement"),
    (c.bowelLeaks ?? 0) > 0 && count(c.bowelLeaks, "bowel accident"),
    c.pelvicFloor > 0 && count(c.pelvicFloor, "pelvic floor session"),
    (c.catheters ?? 0) > 0 && count(c.catheters, "catheter"),
  ].filter(Boolean) as string[];
  return { value: num(c.voids), unit: c.voids === 1 ? "trip" : "trips", support: parts.slice(0, 3).join(" · ") || undefined };
}

export function neuroLine(n?: NeuroToday): Headline | null {
  if (!n) return null;
  const all: Headline[] = [];
  if (n.energyLeft != null) all.push({ value: num(n.energyLeft), unit: n.energyLeft === 1 ? "spoon left" : "spoons left" });
  if (n.socialBattery != null) all.push({ value: `${n.socialBattery * 10}%`, unit: "social battery" });
  if (n.focusMinutes > 0) all.push({ value: hm(n.focusMinutes), unit: "focus" });
  const hard = n.overloadsToday > 0 ? "A hard moment today. Go gently" : null;
  if (all.length === 0) return hard ? { value: "A hard moment today", text: true, support: "Go gently. Calm mode is here if it helps" } : null;
  const [first, ...rest] = all;
  return { ...first, support: join([hard, ...rest.map((r) => `${r.unit} ${r.value}`.replace(/^./, (c) => c.toUpperCase()))]) || undefined };
}

export function transitionLine(t?: TransitionToday): Headline | null {
  if (!t || (!t.headline && t.milestonesDue.length === 0 && t.euphoriaThisWeek === 0)) return null;
  const euphoria = t.euphoriaThisWeek > 0 ? `${count(t.euphoriaThisWeek, "moment")} of euphoria this week` : null;
  const coming = t.milestonesDue.length > 0 ? `Coming up: ${firstFew(t.milestonesDue, 2)}` : null;
  if (t.headline) return { value: t.headline, text: true, support: join([euphoria, coming]) || undefined };
  if (euphoria) return { value: num(t.euphoriaThisWeek), unit: t.euphoriaThisWeek === 1 ? "moment of euphoria this week" : "moments of euphoria this week", support: coming ?? undefined };
  return { value: coming!, text: true };
}

export function vitalsLine(v: VitalsToday | undefined, unit: GlucoseUnit): Headline | null {
  if (!v || (!v.bloodPressure && !v.glucose)) return null;
  const glucose = v.glucose ? glucoseText(v.glucose.mmolL, unit) : null;
  if (v.bloodPressure) {
    const bp = v.bloodPressure;
    return { value: `${bp.systolic}/${bp.diastolic}`, unit: "mmHg", support: join([v.category && BP_TEXT[v.category], `at ${clock(bp.at)}`, glucose && `glucose ${glucose}`]) };
  }
  const [value, u] = glucose!.split(" ");
  return { value, unit: u, support: join([`at ${clock(v.glucose!.at)}`, v.timeInRange != null && `${v.timeInRange}% in range today`]) };
}

export function drinksLine(day: DaySummary): Headline | null {
  const d = day.drinks;
  if (!d || d.drinks.length === 0) return null;
  const alcohol = d.standardDrinks > 0 ? count(Math.round(d.standardDrinks * 10) / 10, "standard drink") : null;
  if (d.caffeineMg > 0) {
    return { value: num(d.caffeineMg), unit: "mg caffeine", support: join([d.caffeineAtBedtimeMg != null && `about ${num(d.caffeineAtBedtimeMg)} mg left at bedtime`, alcohol]) || undefined };
  }
  if (alcohol) return { value: num(d.standardDrinks, 1), unit: d.standardDrinks === 1 ? "standard drink" : "standard drinks", support: count(d.drinks.length, "drink") };
  return { value: num(d.drinks.length), unit: d.drinks.length === 1 ? "drink" : "drinks" };
}

export function waterLine(day: DaySummary): Headline | null {
  if (day.waterMl <= 0) return null;
  const goal = day.nutritionTarget?.waterMl ?? day.goals.waterMl;
  return { value: num(day.waterMl), unit: `of ${num(goal)} ml`, support: day.waterMl >= goal ? "Goal reached" : `${num(goal - day.waterMl)} ml to go` };
}

export function symptomsLine(s?: SymptomsDay): Headline | null {
  const entries = s?.entries ?? [];
  const migraines = s?.migraines ?? [];
  const total = entries.length + migraines.length;
  if (total === 0) return null;
  if (total === 1 && migraines.length === 1) {
    const m = migraines[0];
    return { value: "Migraine", text: true, support: join([`since ${clock(m.start)}`, m.end ? hm((m.end - m.start) / 60_000) : "still going"]) };
  }
  if (total === 1) {
    const e = entries[0];
    return { value: e.symptom, text: true, support: join([severityWord(e.severity), `at ${clock(e.at)}`]) };
  }
  return { value: num(total), unit: "symptoms", support: firstFew([...migraines.map(() => "Migraine"), ...entries.map((e) => e.symptom)]) };
}

export function habitsLine(habits: HabitStatus[]): Headline | null {
  if (habits.length === 0) return null;
  const left = habits.filter((h) => !h.done);
  return { value: num(habits.length - left.length), unit: `of ${num(habits.length)} done`, support: left.length === 0 ? "All done today" : `Still to do: ${firstFew(left.map((h) => h.habit.name))}` };
}

function labsDueText(date: string): string {
  const days = Math.round((parseDate(date).getTime() - parseDate(today()).getTime()) / 86_400_000);
  return days <= 0 ? "Labs due now" : days < 14 ? `Labs due in ${count(days, "day")}` : `Labs due in ${Math.round(days / 7)} weeks`;
}

export function hormonesLine(h?: HormonesToday): Headline | null {
  if (!h || (h.medications === 0 && !h.nextLabsDue && h.outOfRange.length === 0)) return null;
  const outside = h.outOfRange.length > 0 ? `Outside your target: ${firstFew(h.outOfRange, 2)}` : null;
  if (h.nextLabsDue) return { value: labsDueText(h.nextLabsDue), text: true, support: join([outside, h.medications > 0 && count(h.medications, "medication")]) || undefined };
  if (h.medications > 0) return { value: num(h.medications), unit: h.medications === 1 ? "medication" : "medications", support: outside ?? undefined };
  return { value: outside!, text: true };
}

export const lifeStageTitle = (stage: LifeStageToday) =>
  stage.stage === "PREGNANT" ? "Pregnancy" : stage.stage === "POSTPARTUM" ? "After the birth" : stage.stage === "PERIMENOPAUSE" ? "Perimenopause" : "After menopause";

export function lifeStageLine(stage: LifeStageToday): Headline {
  const p = stage.pregnancy;
  if (p) return { value: `Week ${p.week}`, support: join([`trimester ${p.trimester}`, p.daysToGo > 0 ? `due in ${count(p.daysToGo, "day")}` : "due any day"]) };
  if (stage.stage === "POSTPARTUM") return { value: num(stage.feedsToday), unit: stage.feedsToday === 1 ? "feed today" : "feeds today", support: stage.lastFeed ? `Last at ${clock(stage.lastFeed.start)}` : stage.headline };
  return { value: num(stage.hotFlashesToday), unit: stage.hotFlashesToday === 1 ? "hot flash today" : "hot flashes today", support: stage.headline };
}

export function activityLine(day: DaySummary, units: Units, foodNumbers: boolean): Headline | null {
  if ((day.steps ?? 0) <= 0 && day.activeCalories <= 0 && (day.distanceM ?? 0) <= 0) return null;
  const goal = day.goals.steps;
  return {
    value: num(day.steps), unit: goal ? `of ${num(goal)} steps` : "steps",
    // Calories burned are amounts too: not for people in eating disorder recovery.
    support: join([(day.distanceM ?? 0) > 0 && distance(day.distanceM, units), foodNumbers && day.activeCalories > 0 && `${num(day.activeCalories)} kcal active`]) || undefined,
  };
}

export function sleepLine(day: DaySummary): Headline | null {
  const night = day.sleepSummary;
  if (!night) return null;
  return { value: hm(night.asleepMinutes), unit: "asleep", support: join([`${clock(night.start)}–${clock(night.end)}`, `${night.efficiency}% efficient`]) };
}

export function heartLine(day: DaySummary): Headline | null {
  const others = join([day.hrv != null && day.restingHeartRate != null && `HRV ${num(day.hrv)} ms`, day.spo2 != null && `blood oxygen ${num(day.spo2)}%`]);
  if (day.restingHeartRate != null) return { value: num(day.restingHeartRate), unit: "bpm resting", support: others ? others.replace(/^./, (c) => c.toUpperCase()) : undefined };
  if (day.hrv != null) return { value: num(day.hrv), unit: "ms HRV", support: day.spo2 != null ? `Blood oxygen ${num(day.spo2)}%` : undefined };
  if (day.heartRate.length > 1) {
    const avg = day.heartRate.reduce((s, p) => s + p.v, 0) / day.heartRate.length;
    return { value: num(avg), unit: "bpm on average", support: day.spo2 != null ? `Blood oxygen ${num(day.spo2)}%` : undefined };
  }
  if (day.spo2 != null) return { value: `${num(day.spo2)}%`, unit: "blood oxygen" };
  return null;
}

export function stressLine(stress?: StressDay, resilience?: Resilience): Headline | null {
  const level = resilience ? resilience.level.charAt(0) + resilience.level.slice(1).toLowerCase() : null;
  if (stress) {
    return { value: stress.summary, text: true, support: join([`${hm(stress.stressedMinutes)} stressed`, `${hm(stress.restoredMinutes)} restored`, level && `resilience ${level.toLowerCase()}`]) };
  }
  if (resilience) return { value: level!, text: true, support: `Resilience over two weeks, ${resilience.trend === "up" ? "rising" : resilience.trend === "down" ? "lower than before" : "steady"}` };
  return null;
}

/**
 * Food: calories against the target and protein; in eating disorder recovery ([foodNumbers]
 * false), what was logged instead ("Breakfast, lunch logged"), from [entries].
 */
export function foodLine(day: DaySummary, foodNumbers: boolean, entries: FoodEntry[] | undefined): Headline | null {
  if (!foodNumbers) {
    const meals = mealsLogged((entries ?? []).map((e) => e.meal));
    if (meals) return { value: meals, text: true, support: firstFew(entries!.map((e) => e.name)) };
    return entries == null && day.nutrition.calories > 0 ? { value: "Food logged today", text: true } : null;
  }
  const eaten = day.nutrition;
  if (eaten.calories <= 0 && eaten.protein <= 0) return null;
  const t = day.nutritionTarget;
  if (!t) return { value: num(eaten.calories), unit: "kcal", support: `Protein ${num(eaten.protein)} g` };
  const left = t.calories - eaten.calories;
  return {
    value: num(eaten.calories), unit: `of ${num(t.calories)} kcal`,
    support: join([left >= 0 ? `${num(left)} kcal left` : `${num(-left)} kcal over`, `protein ${num(eaten.protein)} of ${num(t.protein)} g`]),
  };
}

const checkInText = (c: CheckIn) =>
  join([c.mood != null && `mood ${c.mood}/5`, c.energy != null && `energy ${c.energy}/5`, c.stress != null && `stress ${c.stress}/5`, c.tags.slice(0, 2).join(", ")]);

/** Workouts, and the check-in: the card's title and headline, or null with neither. */
export function workoutsLine(day: DaySummary, units: Units, foodNumbers: boolean): { title: string; line: Headline } | null {
  const c = day.checkIn;
  const checkIn = c && (c.mood || c.tags.length > 0 || c.note) ? c : null;
  const w: Workout[] = day.workouts;
  if (w.length === 0) return checkIn ? { title: "Check-in", line: { value: "Checked in", text: true, support: checkInText(checkIn) || undefined } } : null;
  const minutes = (x: Workout) => (x.end - x.start) / 60_000;
  const checked = checkIn ? "checked in" : null;
  if (w.length === 1) {
    const one = w[0];
    return {
      title: "Workouts",
      line: {
        value: one.name ?? workoutLabel(one.type), text: true,
        support: join([`at ${clock(one.start)}`, hm(minutes(one)), one.distanceM ? distance(one.distanceM, units) : null, foodNumbers && one.activeCalories ? `${num(one.activeCalories)} kcal` : null, checked]),
      },
    };
  }
  return { title: "Workouts", line: { value: num(w.length), unit: "workouts", support: join([firstFew(w.map((x) => x.name ?? workoutLabel(x.type)), 2), hm(w.reduce((s, x) => s + minutes(x), 0)), checked]) } };
}

export function bodyLine(day: DaySummary, units: Units): Headline | null {
  if (day.weightKg == null) return null;
  return { value: weightValue(day.weightKg, units).toFixed(1), unit: units === "IMPERIAL" ? "lb" : "kg", support: day.bodyFat != null ? `Body fat ${day.bodyFat.toFixed(1)}%` : undefined };
}

/** The weekly digest, on its day and the next, while it's new: a row in Today's "For you" ([enabled]: the person's own Today). */
export function useDigestRow(enabled: boolean): ReactNode {
  const prefs = useSession((s) => s.profile?.digest);
  const now = new Date();
  const iso = ((now.getDay() + 6) % 7) + 1; // 1 Monday … 7 Sunday
  const day = prefs?.day ?? 7;
  const show = enabled && !!prefs?.enabled && (iso === day || iso === (day % 7) + 1);
  const { data } = useLoad<Digest | null>(() => (show ? api.latestDigest().catch((e) => (e instanceof ApiError ? null : Promise.reject(e))) : Promise.resolve(null)), [show]);
  if (!show || !data) return null;
  // Only a digest made in the last two days counts as new.
  if (Date.now() - data.generatedAt > 2 * 86_400_000) return null;
  return (
    <InsightRow key="digest" insight={{ tone: "info", title: `Your week, ${weekText(data)}`, body: data.headline, icon: "inbox" }} icon={Inbox} to="/digest" openName="your week" />
  );
}
