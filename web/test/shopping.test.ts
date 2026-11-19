// Shopping and grades (src/lib/shopping.ts): key nutrients, a side-by-side compare, and the list's order (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { boughtLabel, compareLines, gradeClass, keyNutrients, shoppingOrder } from "../src/lib/shopping.ts";
import type { Food, ShoppingItem } from "../src/lib/types.ts";

const oats: Food = {
  name: "Oats", serving: "40 g", servingGrams: 40, source: "USDA", uses: 0,
  nutrients: { calories: 152, protein: 5.3, carbs: 27, fat: 2.6, fiber: 4, sugar: 0.4, saturatedFat: 0.5, sodiumMg: 2, micros: {} },
  grade: { letter: "A", reasons: ["Good fibre"] },
};
const cola: Food = { name: "Cola", serving: "1 can", source: "USDA", uses: 0, nutrients: { calories: 139, protein: 0, carbs: 35, fat: 0, sugar: 35, micros: {} } };

test("a grade's badge class, and none for a letter it doesn't know", () => {
  assert.equal(gradeClass("A"), "grade-a");
  assert.equal(gradeClass("f"), "grade-f");
  assert.equal(gradeClass("E"), "");
  assert.equal(gradeClass(undefined), "");
});

test("key nutrients per serving, with what isn't known as a dash", () => {
  const lines = keyNutrients(cola);
  assert.equal(lines.find((l) => l.key === "calories")!.value, "139 kcal");
  assert.equal(lines.find((l) => l.key === "sugar")!.value, "35 g");
  assert.equal(lines.find((l) => l.key === "fiber")!.value, "—");
  assert.equal(lines.find((l) => l.key === "sodium")!.value, "—");
});

test("two products side by side; without numbers, only grades and servings", () => {
  const lines = compareLines(oats, cola);
  assert.deepEqual(lines[0], { label: "Grade", a: "A", b: "—" });
  assert.deepEqual(lines.find((l) => l.label === "Sugars"), { label: "Sugars", a: "0.4 g", b: "35 g" });
  assert.equal(compareLines(oats, cola, false).length, 2);
});

test("the list: still to buy first, and a clear button only when something's bought", () => {
  const item = (id: string, checked: boolean): ShoppingItem => ({ id, name: id, checked, addedAt: 0 });
  const items = [item("a", true), item("b", false), item("c", false)];
  assert.deepEqual(shoppingOrder(items).map((i) => i.id), ["b", "c", "a"]);
  assert.equal(boughtLabel(items), "Clear 1 bought");
  assert.equal(boughtLabel([item("b", false)]), null);
});
