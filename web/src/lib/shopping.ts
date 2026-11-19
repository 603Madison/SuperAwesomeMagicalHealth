import type { Food, ShoppingItem } from "./types";

/*
 * Shopping and grades, worked out without React: a product's key nutrients per serving, two
 * products side by side, and the list in the order it shows. Type imports only, so node can run it
 * as it is (npm test).
 */

/** A grade's letter as a class for its badge ("grade-a" … "grade-f"); nothing for an unknown one. */
export function gradeClass(letter: string | undefined): string {
  const l = (letter ?? "").toLowerCase();
  return ["a", "b", "c", "d", "f"].includes(l) ? `grade-${l}` : "";
}

const round = (v: number, places = 0) => {
  const f = 10 ** places;
  return String(Math.round(v * f) / f);
};

/** One line of nutrients: its name, and the amount in a serving ("—" where it isn't known). */
export interface NutrientLine { key: string; label: string; value: string }

/** What's in one serving, for deciding in the shop: calories and the macros, then what grades weigh most. */
export function keyNutrients(food: Food): NutrientLine[] {
  const n = food.nutrients;
  const g = (v: number | undefined, places = 1) => (v == null ? "—" : `${round(v, places)} g`);
  return [
    { key: "calories", label: "Calories", value: `${round(n.calories)} kcal` },
    { key: "protein", label: "Protein", value: g(n.protein) },
    { key: "carbs", label: "Carbs", value: g(n.carbs) },
    { key: "sugar", label: "Sugars", value: g(n.sugar) },
    { key: "fat", label: "Fat", value: g(n.fat) },
    { key: "saturatedFat", label: "Saturated fat", value: g(n.saturatedFat) },
    { key: "fiber", label: "Fibre", value: g(n.fiber) },
    { key: "sodium", label: "Sodium", value: n.sodiumMg == null ? "—" : `${round(n.sodiumMg)} mg` },
  ];
}

/** Two products side by side: each line with both amounts. Without [numbers], only their grades and servings. */
export function compareLines(a: Food, b: Food, numbers = true): { label: string; a: string; b: string }[] {
  const lines = [
    { label: "Grade", a: a.grade?.letter ?? "—", b: b.grade?.letter ?? "—" },
    { label: "Serving", a: a.serving, b: b.serving },
  ];
  if (!numbers) return lines;
  const left = keyNutrients(a);
  const right = keyNutrients(b);
  return [...lines, ...left.map((l, i) => ({ label: l.label, a: l.value, b: right[i].value }))];
}

/** The list as it shows: still to buy first (in the order added), then what's bought. */
export function shoppingOrder(items: ShoppingItem[]): ShoppingItem[] {
  return [...items.filter((i) => !i.checked), ...items.filter((i) => i.checked)];
}

/** "2 bought" for the clear button; null when nothing is. */
export function boughtLabel(items: ShoppingItem[]): string | null {
  const n = items.filter((i) => i.checked).length;
  return n ? `Clear ${n} bought` : null;
}
