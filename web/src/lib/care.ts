import type { Module } from "./types";

/*
 * Care (pages/Care.tsx), the parts that don't need React (npm test covers them): which areas the
 * person looks after, with related features together, and their order. Type imports only, so node
 * can run it as it is.
 *
 * Each area's id is its Layouts.NAV id (a Module name, FOOD for Food), so arranging Care is the same
 * arrangement the sidebar and the phone's features list used: saved ids keep their meaning.
 */

/** One of Care's cards. */
export type CareArea =
  | "MEDICATIONS" | "CONTINENCE" | "APPOINTMENTS" | "FOOD" | "TRANSITION" | "NEURO" | "MIND" | "CYCLE" | "VITALS" | "SYMPTOMS" | "HABITS"
  | "HORMONES" | "PHOTOS" | "MEDITATION";

export interface CareAreaInfo {
  id: CareArea;
  name: string;
  /** The area's own page, which its card's header opens. */
  to: string;
  /** The feature that brings the card; none for one that's everyone's (Meditation). */
  module?: Module;
}

/** Every area, in the default order (before the person arranges them). */
export const CARE_AREAS: CareAreaInfo[] = [
  { id: "MEDICATIONS", name: "Medications", to: "/meds", module: "MEDICATIONS" },
  { id: "CONTINENCE", name: "Bladder & bowel", to: "/bladder", module: "CONTINENCE" },
  { id: "APPOINTMENTS", name: "Appointments", to: "/appointments", module: "APPOINTMENTS" },
  { id: "FOOD", name: "Food", to: "/food", module: "NUTRITION" },
  { id: "TRANSITION", name: "Transition", to: "/transition", module: "TRANSITION" },
  { id: "NEURO", name: "Autism & ADHD", to: "/neuro", module: "NEURO" },
  { id: "MIND", name: "Mind", to: "/mind", module: "MIND" },
  // Guided meditation: everyone's, whatever's on (its id is NavPage.MEDITATION).
  { id: "MEDITATION", name: "Meditation", to: "/meditation" },
  { id: "CYCLE", name: "Cycle", to: "/cycle", module: "CYCLE" },
  { id: "VITALS", name: "Blood pressure & glucose", to: "/vitals", module: "VITALS" },
  { id: "SYMPTOMS", name: "Symptoms & pain", to: "/symptoms", module: "SYMPTOMS" },
  { id: "HABITS", name: "Habits", to: "/habits", module: "HABITS" },
  { id: "HORMONES", name: "Hormone therapy", to: "/hormones", module: "HORMONES" },
  { id: "PHOTOS", name: "Progress photos", to: "/photos", module: "PHOTOS" },
];

/**
 * The areas for the features that are on ([on]), with related ones together: hormone therapy is in
 * Medications, and progress photos in Transition, while those are on (each has a card of its own
 * otherwise). Supplies, body measurements and voice practice are always inside their areas.
 * Meditation is everyone's; while [viewing] someone's share ([on] is what they shared), it comes
 * with Mind, as the server shares it.
 */
export function careAreas(on: Module[], viewing = false): CareAreaInfo[] {
  const is = (m: Module) => on.includes(m);
  return CARE_AREAS.filter((a) => {
    if (a.id === "MEDITATION") return !viewing || is("MIND");
    if (!a.module || !is(a.module)) return false;
    if (a.id === "HORMONES") return !is("MEDICATIONS");
    if (a.id === "PHOTOS") return !is("TRANSITION");
    return true;
  });
}

/** How soon counts as soon: something due within this goes to the top. */
export const CARE_SOON_MS = 3 * 60 * 60_000;

/**
 * Care's order: whatever's due within the next few hours (or overdue), soonest first; then the rest
 * as the person arranged them ([arranged], already in their order, less the hidden ones). [due]
 * says when each area next needs something, where it knows.
 */
export function careOrder<T extends { id: string }>(arranged: T[], due: Partial<Record<string, number | null>>, now: number, soon = CARE_SOON_MS): T[] {
  const at = (a: T) => due[a.id];
  const isSoon = (a: T) => at(a) != null && at(a)! <= now + soon;
  const first = arranged.filter(isSoon).sort((x, y) => at(x)! - at(y)!);
  return [...first, ...arranged.filter((a) => !isSoon(a))];
}
