// Where a food came from (src/lib/foodSources.ts): its name, and search results under headings (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { sourceGroups, sourceName } from "../src/lib/foodSources.ts";

test("each source has a plain name, and the person's own foods none", () => {
  assert.equal(sourceName("USDA"), "USDA");
  assert.equal(sourceName("OPEN_FOOD_FACTS"), "Open Food Facts");
  assert.equal(sourceName("MEALIE"), "Mealie");
  assert.equal(sourceName("CUSTOM"), null);
});

test("results are grouped under where they came from, in the order they came", () => {
  const groups = sourceGroups([
    { source: "USDA" as const, name: "Banana, raw" },
    { source: "USDA" as const, name: "Greek Yogurt" },
    { source: "OPEN_FOOD_FACTS" as const, name: "Greek yoghurt" },
  ]);
  assert.deepEqual(groups.map((g) => [g.title, g.foods.map((f) => f.name)]), [
    ["USDA FoodData Central", ["Banana, raw", "Greek Yogurt"]],
    ["Open Food Facts", ["Greek yoghurt"]],
  ]);
  assert.deepEqual(sourceGroups([]), []);
});
