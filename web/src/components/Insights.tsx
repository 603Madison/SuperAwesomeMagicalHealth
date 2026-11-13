import { useId, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronRight, Plus, Sparkles, type LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { today } from "../lib/format";
import { useViewing } from "../lib/viewing";
import { entryInput, insightPage, type AppPage, type FoodEntry, type FoodSuggestion, type Insight, type Meal, type Module } from "../lib/types";
import { INSIGHT_ICONS } from "./LogDialogs";
import { Card, errorToast, toast } from "./ui";

/** Where each page is, and its name for "Open …". */
const PAGES: Record<AppPage, { path: string; name: string }> = {
  TODAY: { path: "/", name: "your scores" },
  FOOD: { path: "/food", name: "Food" },
  MEDS: { path: "/meds", name: "Medications" },
  MIND: { path: "/mind", name: "Mind" },
  CYCLE: { path: "/cycle", name: "Cycle" },
  CONTINENCE: { path: "/bladder", name: "Bladder & bowel" },
  SUPPLIES: { path: "/supplies", name: "Supplies" },
  VITALS: { path: "/vitals", name: "Blood pressure & glucose" },
  HABITS: { path: "/habits", name: "Habits" },
  SYMPTOMS: { path: "/symptoms", name: "Symptoms" },
  HORMONES: { path: "/hormones", name: "Hormone therapy" },
  APPOINTMENTS: { path: "/appointments", name: "Appointments" },
  NEURO: { path: "/neuro", name: "Autism & ADHD" },
  TRANSITION: { path: "/transition", name: "Transition" },
  PHOTOS: { path: "/photos", name: "Progress photos" },
  BODY: { path: "/body", name: "Body" },
  SLEEP: { path: "/sleep", name: "Sleep" },
  ACTIVITY: { path: "/activity", name: "Activity" },
  TRENDS: { path: "/trends", name: "Trends" },
  DEVICES: { path: "/devices", name: "Devices" },
};

/** The feature each page needs shared, for someone viewing a share (Summary.kt's AppPage.module). */
const PAGE_MODULES: Partial<Record<AppPage, Module[]>> = {
  FOOD: ["NUTRITION"], MEDS: ["MEDICATIONS"], MIND: ["MIND"], CYCLE: ["CYCLE"], CONTINENCE: ["CONTINENCE"], SUPPLIES: ["CONTINENCE", "CYCLE"],
  VITALS: ["VITALS"], HABITS: ["HABITS"], SYMPTOMS: ["SYMPTOMS"], HORMONES: ["HORMONES"], APPOINTMENTS: ["APPOINTMENTS"], NEURO: ["NEURO"],
  TRANSITION: ["TRANSITION"], PHOTOS: ["PHOTOS"], BODY: ["BODY"], SLEEP: ["SLEEP"], ACTIVITY: ["ACTIVITY"],
};

/** A page's name, for "Open …". */
export const pageName = (page: AppPage) => PAGES[page].name;

/** A page's address (Food on another day keeps that day). */
export function pagePath(page: AppPage, date?: string): string {
  if (page === "FOOD" && date && date !== today()) return `/food?date=${date}`;
  return PAGES[page].path;
}

/** Whether [page] can be opened here: everything is one's own; viewing a share, only what's shared (and never Devices). */
export function usePageOpens(): (page: AppPage) => boolean {
  const share = useViewing((s) => s.share);
  return (page) => {
    if (!share) return true;
    if (page === "DEVICES") return false;
    const needs = PAGE_MODULES[page];
    return !needs || needs.some((m) => share.modules.includes(m));
  };
}

const MEAL_WORDS: Record<Meal, string> = { BREAKFAST: "breakfast", LUNCH: "lunch", DINNER: "dinner", SNACK: "snacks" };

/** "38 g protein" (and the calories, with [kcal]). */
export function amountText(s: FoodSuggestion, kcal = false): string {
  const protein = `${Math.round(s.nutrients.protein)} g protein`;
  return kcal ? `${protein} · ${Math.round(s.nutrients.calories)} kcal` : protein;
}

/**
 * Logs a suggestion's items on [date] at its meal (one entry each, as the Food page logs a past
 * entry again), says so with an Undo, and calls [onChanged] after either. Half-logged, it takes
 * back what it added.
 */
export async function logSuggestion(s: FoodSuggestion, date: string, onChanged: () => void, meal: Meal = s.meal) {
  const added: FoodEntry[] = [];
  try {
    for (const item of s.items) added.push(await api.logFood(entryInput(item, date, meal)));
  } catch (e) {
    await Promise.all(added.map((a) => api.deleteEntry(a.id!).catch(() => undefined)));
    errorToast(e);
    return;
  }
  onChanged();
  toast(`Added to ${MEAL_WORDS[meal]}: ${s.name}`, "info", {
    label: "Undo",
    run: async () => {
      try {
        await Promise.all(added.map((a) => api.deleteEntry(a.id!)));
        toast("Taken back off");
      } catch (e) { errorToast(e); }
      onChanged();
    },
  });
}

/** A food idea as a chip: its name and protein, and Add (not while viewing a share). */
export function SuggestionChip({ suggestion: s, date, readOnly, showNumbers = true, onChanged }: {
  suggestion: FoodSuggestion; date: string; readOnly: boolean; showNumbers?: boolean; onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <li className="idea-chip" title={s.why}>
      <span className="idea-chip-name">{s.name}</span>
      {showNumbers && <span className="idea-chip-amount">{Math.round(s.nutrients.protein)} g</span>}
      {!readOnly && (
        <button
          type="button" className="idea-chip-add" disabled={busy} aria-label={`Add ${s.name} to ${MEAL_WORDS[s.meal]}`}
          onClick={async () => { setBusy(true); await logSuggestion(s, date, onChanged); setBusy(false); }}
        >
          <Plus size={14} aria-hidden="true" /> Add
        </button>
      )}
    </li>
  );
}

/**
 * One insight as a compact row: its icon, title and a short line, and a chevron when it opens a
 * page (its link, else its feature's page). The accessible name is the title and "Open <page>".
 * The protein insight's food ideas sit beneath it as chips. [onPage] may handle a page itself
 * (Today opens the readiness details for TODAY) by returning true; [to] and [openName] point the
 * row somewhere else entirely (the weekly digest).
 */
export function InsightRow({ insight, date = today(), readOnly = false, onChanged = () => {}, onPage, to, openName, icon }: {
  insight: Insight; date?: string; readOnly?: boolean; onChanged?: () => void; onPage?: (page: AppPage) => boolean;
  to?: string; openName?: string; icon?: LucideIcon;
}) {
  const navigate = useNavigate();
  const opens = usePageOpens();
  const bodyId = useId();
  const Icon = icon ?? INSIGHT_ICONS[insight.icon] ?? Sparkles;
  const page = insightPage(insight);
  const target = to ? { name: openName ?? "it", go: () => navigate(to) } : page && opens(page) ? {
    name: pageName(page),
    go: () => { if (!onPage?.(page)) navigate(pagePath(page, date)); },
  } : null;
  const content: ReactNode = (
    <>
      <span className="insight-icon" aria-hidden="true"><Icon size={18} /></span>
      <span className="insight-text">
        <span className="insight-title">{insight.title}</span>
        {insight.body && <span className="insight-body" id={bodyId}>{insight.body}</span>}
      </span>
      {target && <ChevronRight size={18} className="insight-chevron" aria-hidden="true" />}
    </>
  );
  const ideas = insight.suggestions ?? [];
  return (
    <li className={`insight-row tone-${insight.tone}`}>
      {target ? (
        <button type="button" className="insight-main" onClick={target.go} aria-label={`${insight.title}. Open ${target.name}`} aria-describedby={insight.body ? bodyId : undefined}>
          {content}
        </button>
      ) : <div className="insight-main">{content}</div>}
      {ideas.length > 0 && (
        <ul className="idea-chips" aria-label="Ideas">
          {ideas.slice(0, 3).map((s) => <SuggestionChip key={s.name} suggestion={s} date={date} readOnly={readOnly} onChanged={onChanged} />)}
        </ul>
      )}
    </li>
  );
}

/** Today's insights as one card of [InsightRow]s: the first three, and "Show N more" for the rest. */
export function ForYouCard({ children, count }: { children: ReactNode[]; count?: number }) {
  const [all, setAll] = useState(false);
  const rows = children.filter(Boolean);
  if (rows.length === 0) return null;
  const limit = count ?? 3;
  return (
    <Card title="For you" icon={<Sparkles size={18} />} className="span-2 for-you">
      <ul className="insight-rows">{all ? rows : rows.slice(0, limit)}</ul>
      {rows.length > limit && (
        <button type="button" className="link-btn for-you-more" aria-expanded={all} onClick={() => setAll(!all)}>
          {all ? "Show fewer" : `Show ${rows.length - limit} more`}
        </button>
      )}
    </Card>
  );
}
