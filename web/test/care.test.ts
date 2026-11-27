// Care (src/lib/care.ts): which areas show, with related features together, and their order (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { CARE_AREAS, CARE_SOON_MS, careAreas, careOrder } from "../src/lib/care.ts";

const ids = (list: { id: string }[]) => list.map((a) => a.id);

test("an area for each feature that's on; hormone therapy in Medications, photos in Transition; Meditation for everyone", () => {
  assert.deepEqual(ids(careAreas([])), ["MEDITATION"]);
  // What the devices measure is Body's, not Care's.
  assert.deepEqual(ids(careAreas(["ACTIVITY", "SLEEP", "BODY"])), ["MEDITATION"]);
  assert.deepEqual(ids(careAreas(["MEDICATIONS", "HORMONES", "TRANSITION", "PHOTOS", "NUTRITION"])), ["MEDICATIONS", "FOOD", "TRANSITION", "MEDITATION"]);
  // On their own, they have cards of their own.
  assert.deepEqual(ids(careAreas(["HORMONES", "PHOTOS"])), ["MEDITATION", "HORMONES", "PHOTOS"]);
  // Mind's next to Mind.
  assert.deepEqual(ids(careAreas(["MIND", "CYCLE"])), ["MIND", "MEDITATION", "CYCLE"]);
});

test("viewing someone's share, Meditation comes with Mind, as the server shares it", () => {
  assert.deepEqual(ids(careAreas(["NUTRITION"], true)), ["FOOD"]);
  assert.deepEqual(ids(careAreas(["MIND"], true)), ["MIND", "MEDITATION"]);
  assert.deepEqual(ids(careAreas([], true)), []);
});

test("the areas' ids are the shared arrangement's (Layouts.NAV): Module names, FOOD for Food", () => {
  assert.deepEqual(ids(CARE_AREAS), [
    "MEDICATIONS", "CONTINENCE", "APPOINTMENTS", "FOOD", "TRANSITION", "NEURO", "MIND", "MEDITATION", "CYCLE", "VITALS", "SYMPTOMS", "HABITS", "HORMONES", "PHOTOS",
  ]);
  // Meditation's is the NavPage's (it has no feature to turn on).
  for (const a of CARE_AREAS) assert.equal(a.id, a.module === "NUTRITION" ? "FOOD" : a.module ?? "MEDITATION");
});

test("what's due within a few hours comes first, soonest first; then the person's order", () => {
  const now = 1_000_000_000;
  const arranged = [{ id: "MIND" }, { id: "FOOD" }, { id: "CONTINENCE" }, { id: "MEDICATIONS" }, { id: "APPOINTMENTS" }];
  const due = { MEDICATIONS: now + 60 * 60_000, CONTINENCE: now - 10 * 60_000, APPOINTMENTS: now + CARE_SOON_MS + 60_000, FOOD: null };
  assert.deepEqual(ids(careOrder(arranged, due, now)), ["CONTINENCE", "MEDICATIONS", "MIND", "FOOD", "APPOINTMENTS"]);
  assert.deepEqual(ids(careOrder(arranged, {}, now)), ids(arranged));
});
