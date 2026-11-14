import type { Meal, MealiePlanEntry, MealieRecipe, MealieStatus } from "./types";

/*
 * Mealie in Food (npm test covers these): how the connection, a recipe and a planned meal read.
 * Type imports only, so node can run it as it is.
 */

const MEAL_LABEL: Record<Meal, string> = { BREAKFAST: "Breakfast", LUNCH: "Lunch", DINNER: "Dinner", SNACK: "Snacks" };

const amount = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(1).replace(/\.0$/, ""));

/** "Connected to http://mealie.home:9000 as Madison · Mealie v3.1.0". */
export function connectedLine(s: MealieStatus): string {
  if (!s.connected) return "Not connected";
  return [`Connected to ${s.url}${s.user ? ` as ${s.user}` : ""}`, s.version ? `Mealie ${s.version}` : ""].filter(Boolean).join(" · ");
}

/** "The recipe makes 4 servings", when Mealie says. */
export function yieldLine(r?: MealieRecipe): string | null {
  const n = r?.servings;
  if (!n || n <= 0) return null;
  return `The recipe makes ${amount(n)} ${n === 1 ? "serving" : "servings"}`;
}

/** What a recipe row says under its name: one serving's calories (with numbers shown), or that Mealie has none. */
export function recipeLine(r: MealieRecipe, numbers: boolean): string {
  if (!r.food) return "No nutrition in Mealie yet";
  const parts = ["1 serving", numbers ? `${Math.round(r.food.nutrients.calories)} kcal` : "", yieldLine(r)?.replace("The recipe makes", "makes") ?? ""];
  return parts.filter(Boolean).join(" · ");
}

/** A planned meal's line: "Dinner · 320 kcal", "Breakfast · No nutrition in Mealie yet", "Snacks · Whatever's in the bowl". */
export function planLine(e: MealiePlanEntry, numbers: boolean): string {
  const what = e.recipe
    ? (e.recipe.food ? (numbers ? `${Math.round(e.recipe.food.nutrients.calories)} kcal` : "") : "No nutrition in Mealie yet")
    : e.note ?? "";
  return [MEAL_LABEL[e.meal], what].filter(Boolean).join(" · ");
}

/** Whether a planned meal can be added with one tap: a recipe with nutrition, not logged yet. */
export const canAdd = (e: MealiePlanEntry) => !!e.recipe?.food && !e.logged;
