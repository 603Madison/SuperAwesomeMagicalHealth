// "How much did I have?": what goes with a portion photo, and how its answer reads.
import type { Food, PortionEstimate, PortionFood } from "./types";

/** Only the words the assistant needs about the food: never its numbers. */
export function portionFood(food: Food): PortionFood {
  return {
    name: food.name,
    serving: food.serving,
    servingGrams: food.servingGrams,
    brand: food.brand,
    packageGrams: food.packageGrams,
    recipeServings: food.recipeServings,
  };
}

/** The servings field's text for an estimate: "1.5", "0.33"; nothing when it couldn't tell. */
export function portionServings(estimate: PortionEstimate): string | null {
  if (estimate.refused || !(estimate.servings > 0)) return null;
  return String(Math.round(estimate.servings * 100) / 100);
}

/** The line under the field: what it thought, and that it's hers to check. */
export function portionLine(estimate: PortionEstimate, serving: string): string {
  const servings = portionServings(estimate);
  if (!servings) return estimate.note || "Couldn't tell from that one; try another angle, or set the servings yourself.";
  const about = `About ${servings} × ${serving}`;
  return `${about}${estimate.note ? `: ${estimate.note.replace(/\.$/, "")}` : ""}. Check it before adding.`;
}
