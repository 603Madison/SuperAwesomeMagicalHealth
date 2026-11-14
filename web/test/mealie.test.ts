// Mealie in Food (src/lib/mealie.ts): how the connection, a recipe and a planned meal read (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { canAdd, connectedLine, planLine, recipeLine, yieldLine } from "../src/lib/mealie.ts";
import type { Food, MealiePlanEntry, MealieRecipe } from "../src/lib/types.ts";

const food: Food = { name: "Lentil soup", nutrients: { calories: 320.4, protein: 18, carbs: 40, fat: 6.5 }, serving: "1 serving", source: "MEALIE", uses: 0, ref: "mealie:1" };
const soup: MealieRecipe = { slug: "lentil-soup", name: "Lentil soup", food, servings: 4 };
const toast: MealieRecipe = { slug: "plain-toast", name: "Plain toast" };

test("the connection says where and who", () => {
  assert.equal(connectedLine({ connected: true, url: "http://mealie.home:9000", user: "Madison", version: "v3.1.0" }), "Connected to http://mealie.home:9000 as Madison · Mealie v3.1.0");
  assert.equal(connectedLine({ connected: true, url: "http://m" }), "Connected to http://m");
  assert.equal(connectedLine({ connected: false }), "Not connected");
});

test("a recipe says one serving, its calories when they show, and what it makes", () => {
  assert.equal(yieldLine(soup), "The recipe makes 4 servings");
  assert.equal(yieldLine({ ...soup, servings: 1 }), "The recipe makes 1 serving");
  assert.equal(yieldLine({ ...soup, servings: 2.5 }), "The recipe makes 2.5 servings");
  assert.equal(yieldLine(toast), null);
  assert.equal(recipeLine(soup, true), "1 serving · 320 kcal · makes 4 servings");
  // Eating disorder recovery: no numbers.
  assert.equal(recipeLine(soup, false), "1 serving · makes 4 servings");
  assert.equal(recipeLine(toast, true), "No nutrition in Mealie yet");
});

test("a planned meal says its meal and what it is, and only a recipe with nutrition can be added", () => {
  const dinner: MealiePlanEntry = { meal: "DINNER", title: "Lentil soup", recipe: soup };
  assert.equal(planLine(dinner, true), "Dinner · 320 kcal");
  assert.equal(planLine(dinner, false), "Dinner");
  assert.equal(planLine({ meal: "BREAKFAST", title: "Plain toast", recipe: toast }, true), "Breakfast · No nutrition in Mealie yet");
  assert.equal(planLine({ meal: "SNACK", title: "Fruit", note: "Whatever's in the bowl" }, true), "Snacks · Whatever's in the bowl");
  assert.ok(canAdd(dinner));
  assert.ok(!canAdd({ ...dinner, logged: true }));
  assert.ok(!canAdd({ meal: "BREAKFAST", title: "Plain toast", recipe: toast }));
  assert.ok(!canAdd({ meal: "SNACK", title: "Fruit" }));
});
