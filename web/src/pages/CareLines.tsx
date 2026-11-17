import type { ReactNode } from "react";
import { Inbox } from "lucide-react";
import { FACE_LABELS, FACES } from "./Mind";
import { api, ApiError } from "../lib/api";
import { clock, hm, num, parseDate, today } from "../lib/format";
import { useSession } from "../lib/session";
import { mealsLogged } from "../lib/today";
import { BP_TEXT, glucoseText } from "../lib/vitals";
import type {
  CycleToday, DaySummary, Digest, FoodEntry, GlucoseUnit, HabitStatus, HormonesToday, LifeStageToday, MindDay, NeuroToday, SymptomsDay, TransitionToday,
  VitalsToday,
} from "../lib/types";
import { useLoad } from "../components/ui";
import { severityWord } from "./Symptoms";
import { weekText } from "./Digest";
import { InsightRow } from "../components/Insights";

/*
 * What each area says about today, for Care's cards (pages/Care.tsx): a headline (a number with its
 * unit, or a short line) and at most one supporting line, or null when there's nothing today. And
 * the weekly digest's row, for Today's For you.
 */

/** An area's one headline and its supporting line. */
export interface Headline {
  /** The number, or a short line ([text]). */
  value: ReactNode;
  unit?: string;
  /** A line of words rather than a number. */
  text?: boolean;
  support?: string;
}

/** A headline as one line of text: "6 spoons left · Focus 1h 20m". */
export function lineText(h: Headline | null | undefined): string | null {
  if (!h) return null;
  return [[h.value, h.unit].filter((x) => x != null && x !== "").join(" "), h.support].filter(Boolean).join(" · ");
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
  return {
    value: num(eaten.calories), unit: `of ${num(t.calories)} kcal`,
    support: `${num(eaten.protein)} of ${num(t.protein)} g protein`,
  };
}

/** The weekly digest, on its day and the next, while it's new: a row in Today's For you ([enabled]: the person's own Today). */
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
