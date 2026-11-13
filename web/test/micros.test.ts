// Vitamins and minerals (src/lib/micros.ts): amounts against targets in words, "No data" for
// what nothing carried, how complete the day is, and what typed values become (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { MAIN_FEW, NUTRIENTS, amountOf, carriedText, coverageText, referenceText, row, rows, scaled, typedNutrients } from "../src/lib/micros.ts";
import type { FoodEntry, Nutrients, NutritionTarget } from "../src/lib/types.ts";

const target: NutritionTarget = {
  calories: 2000, protein: 100, carbs: 250, fat: 67, waterMl: 2000, basis: "",
  micros: [
    { micro: "IRON", amount: 18, upper: 45, custom: false },
    { micro: "CALCIUM", amount: 1000, upper: 2500, custom: false },
    { micro: "VITAMIN_D", amount: 15, upper: 100, custom: true },
  ],
  fiberG: 25, sodiumMg: 2300, saturatedFatG: 22.2, sugarG: 90,
};

const totals: Nutrients = { calories: 1500, protein: 80, carbs: 200, fat: 50, fiber: 12, sodiumMg: 1450, micros: { IRON: 9.5, VITAMIN_D: 120 } };

test("every Food.kt nutrient is here once, and the main few are among them", () => {
  assert.equal(NUTRIENTS.length, 28);
  assert.equal(new Set(NUTRIENTS.map((n) => n.key)).size, 28);
  for (const k of MAIN_FEW) assert.ok(NUTRIENTS.some((n) => n.key === k), k);
});

test("a nutrient no food carried says No data, never 0", () => {
  const calcium = row("CALCIUM", totals, target);
  assert.equal(calcium.state, "none");
  assert.equal(calcium.text, "No data");
  assert.equal(calcium.amount, undefined);
  assert.equal(amountOf({ calories: 0, protein: 0, carbs: 0, fat: 0 }, "FIBER"), undefined);
});

test("amounts read against their target, and limits read as limits", () => {
  const iron = row("IRON", totals, target);
  assert.equal(iron.state, "under-target");
  assert.equal(iron.text, "9.5 of 18 mg");
  assert.ok(Math.abs(iron.fraction! - 9.5 / 18) < 1e-9);
  const sodium = row("SODIUM", totals, target);
  assert.equal(sodium.state, "under-limit");
  assert.equal(sodium.text, "1,450 mg, under your limit");
  const over = row("SODIUM", { ...totals, sodiumMg: 2600 }, target);
  assert.equal(over.state, "over-limit");
  assert.equal(over.note, "A little over your limit today");
  // Over the upper limit: a gentle note, and the bar stays full rather than shouting.
  const d = row("VITAMIN_D", totals, target);
  assert.equal(d.state, "met");
  assert.equal(d.fraction, 1);
  assert.match(d.note!, /upper limit/);
  // No target (monounsaturated fat): just the amount.
  const mono = row("MONO_FAT", { ...totals, micros: { MONO_FAT: 14.25 } }, target);
  assert.equal(mono.state, "plain");
  assert.equal(mono.text, "14.3 g");
  assert.equal(mono.fraction, undefined);
});

test("collapsed shows the main few in order; all shows every one", () => {
  assert.deepEqual(rows(totals, target, false).map((r) => r.info.key), ["FIBER", "SODIUM", "IRON", "CALCIUM", "VITAMIN_D", "POTASSIUM"]);
  assert.equal(rows(totals, target, true).length, 28);
});

const entry = (name: string, n: Partial<Nutrients> = {}): FoodEntry => ({
  date: "2026-11-13", meal: "LUNCH", name, servings: 1, serving: "1 serving", nutrients: { calories: 100, protein: 1, carbs: 1, fat: 1, ...n },
});

test("how complete the day's vitamins are, in words", () => {
  const list = [entry("Cereal", { micros: { IRON: 4 } }), entry("Toast"), entry("Soup", { micros: { POTASSIUM: 300 } }), entry("Tea", { fiber: 0 })];
  assert.equal(coverageText(list, true), "Vitamins and minerals came with 2 of 4 foods today. Only those count.");
  assert.equal(coverageText([entry("Toast")], false), "None of the foods that day came with vitamins or minerals. Ones found by search or barcode often do.");
  assert.equal(coverageText([entry("Cereal", { micros: { IRON: 4 } })], true), "Vitamins and minerals came with the one food today.");
  assert.equal(coverageText([], true), null);
});

test("what a food carries, in a line", () => {
  assert.equal(carriedText({ calories: 120, protein: 3, carbs: 24, fat: 1, fiber: 2.7, micros: { IRON: 4.2, CALCIUM: 135, VITAMIN_B12: 0.63 } }), "Fibre 2.7 g · Iron 4.2 mg · Calcium 135 mg · Vitamin B12 0.63 µg");
  assert.equal(carriedText({ calories: 120, protein: 3, carbs: 24, fat: 1, fiber: 2.7, micros: { IRON: 4.2, CALCIUM: 135, VITAMIN_B12: 0.63 } }, 2), "Fibre 2.7 g · Iron 4.2 mg · and 2 more");
  assert.equal(carriedText({ calories: 120, protein: 3, carbs: 24, fat: 1 }), null);
});

test("scaling keeps micros and what's unknown stays unknown", () => {
  const two = scaled({ calories: 100, protein: 2, carbs: 10, fat: 1, sodiumMg: 200, micros: { IRON: 1.5 } }, 2);
  assert.equal(two.micros!.IRON, 3);
  assert.equal(two.sodiumMg, 400);
  assert.equal(two.fiber, undefined);
});

test("typed nutrients: blanks stay unknown, and a negative one is refused", () => {
  const typed = typedNutrients({ FIBER: "3,5", IRON: "2", CALCIUM: " ", SODIUM: "0" });
  assert.equal(typed.error, undefined);
  assert.deepEqual(typed.extras, { fiber: 3.5, sodiumMg: 0 });
  assert.deepEqual(typed.micros, { IRON: 2 });
  assert.equal(typedNutrients({}).micros, undefined);
  assert.match(typedNutrients({ ZINC: "-1" }).error!, /Zinc/);
});

test("the targets settings' line", () => {
  assert.equal(referenceText({ key: "IRON", reference: 18, upper: 45 }), "Reference 18 mg · upper limit 45 mg");
  assert.equal(referenceText({ key: "SODIUM", reference: 2300 }), "Limit 2,300 mg");
  assert.equal(referenceText({ key: "MONO_FAT" }), "No reference intake");
});
