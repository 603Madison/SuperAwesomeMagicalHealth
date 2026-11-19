// "How much did I have?" (src/lib/portion.ts): what goes with the photo, and how the answer reads (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { portionFood, portionLine, portionServings } from "../src/lib/portion.ts";
import type { Food } from "../src/lib/types.ts";

const crisps: Food = {
  name: "Sea salt crisps", nutrients: { calories: 130, protein: 2, carbs: 13, fat: 8 }, serving: "1 bag (25 g)", servingGrams: 25,
  brand: "Crunchy Co", barcode: "5000000000001", source: "OPEN_FOOD_FACTS", uses: 0, packageGrams: 150,
};

test("only the food's words go, never its numbers", () => {
  const sent = portionFood(crisps);
  assert.deepEqual(sent, { name: "Sea salt crisps", serving: "1 bag (25 g)", servingGrams: 25, brand: "Crunchy Co", packageGrams: 150, recipeServings: undefined });
  assert.ok(!JSON.stringify(sent).includes("calories"));
  assert.ok(!JSON.stringify(sent).includes("5000000000001"));
  assert.equal(portionFood({ ...crisps, source: "MEALIE", recipeServings: 6 }).recipeServings, 6);
});

test("an estimate prefills the field, and says it's hers to check", () => {
  const e = { servings: 1.5, note: "Looks like a bag and a half.", refused: false, usedToday: 1 };
  assert.equal(portionServings(e), "1.5");
  assert.equal(portionServings({ ...e, servings: 0.3333 }), "0.33");
  assert.equal(portionLine(e, "1 bag (25 g)"), "About 1.5 × 1 bag (25 g): Looks like a bag and a half. Check it before adding.");
  assert.equal(portionLine({ ...e, note: "" }, "1 bag"), "About 1.5 × 1 bag. Check it before adding.");
});

test("when it couldn't tell, nothing is prefilled and it says so", () => {
  const none = { servings: 0, note: "No food in view", refused: true, usedToday: 2 };
  assert.equal(portionServings(none), null);
  assert.equal(portionLine(none, "1 bag"), "No food in view");
  assert.match(portionLine({ ...none, note: "" }, "1 bag"), /set the servings yourself/);
});
