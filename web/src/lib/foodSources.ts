import type { Food } from "./types";

/*
 * Where a food came from, in words, and a search's foods from the food databases grouped under
 * where they came from (USDA first, as the server sends them). Type imports only, so node can run
 * it as it is (npm test).
 */

/** "USDA", "Open Food Facts", "Mealie"; null for the person's own. */
export function sourceName(source: Food["source"]): string | null {
  switch (source) {
    case "USDA": return "USDA";
    case "OPEN_FOOD_FACTS": return "Open Food Facts";
    case "MEALIE": return "Mealie";
    default: return null;
  }
}

/** A heading over a group of search results: USDA's full name, Open Food Facts as it is. */
function groupTitle(source: Food["source"]): string {
  return source === "USDA" ? "USDA FoodData Central" : sourceName(source) ?? "Food database";
}

/** [online] in runs of the same source, each with its heading, in the order they came. */
export function sourceGroups<T extends Pick<Food, "source">>(online: T[]): { title: string; foods: T[] }[] {
  const groups: { title: string; foods: T[] }[] = [];
  for (const food of online) {
    const title = groupTitle(food.source);
    const last = groups[groups.length - 1];
    if (last && last.title === title) last.foods.push(food);
    else groups.push({ title, foods: [food] });
  }
  return groups;
}
