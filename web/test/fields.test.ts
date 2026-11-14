// The field layout's rules (src/lib/fieldRules.ts): what a failed load leaves, whether amounts of
// food show (never by mistake for someone in eating disorder recovery), and the latest answer (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { latestOnly, layoutAfterError, showsFoodNumbers } from "../src/lib/fieldRules.ts";
import type { FieldLayout } from "../src/lib/types.ts";

const DEFAULTS = ["nutrition.water", "nutrition.micros"];
const layout = (foodNumbers?: boolean): FieldLayout => ({ effective: DEFAULTS, show: [], hide: [], because: {}, ...(foodNumbers === undefined ? {} : { foodNumbers }) });

test("only a 404 (an older server without /api/fields) falls back to the defaults", () => {
  assert.deepEqual(layoutAfterError(404, DEFAULTS), { effective: DEFAULTS, show: [], hide: [], because: {} });
  for (const status of [0, 401, 403, 500, 502, 503, undefined]) assert.equal(layoutAfterError(status, DEFAULTS), null, String(status));
});

test("amounts of food show only when the server says so", () => {
  assert.equal(showsFoodNumbers(layout(true), layout(true)), true);
  assert.equal(showsFoodNumbers(layout(false), layout(false)), false);
  // Still loading, or it couldn't be loaded: none.
  assert.equal(showsFoodNumbers(null, null), false);
  // An answer that leaves it out: none.
  assert.equal(showsFoodNumbers(layout(), layout()), false);
  // The older server's defaults don't say either.
  const fallback = layoutAfterError(404, DEFAULTS);
  assert.equal(showsFoodNumbers(fallback, fallback), false);
});

test("viewing a share: both the owner's layout and the viewer's own have to allow them", () => {
  assert.equal(showsFoodNumbers(layout(true), layout(true)), true);
  assert.equal(showsFoodNumbers(layout(true), layout(false)), false);
  assert.equal(showsFoodNumbers(layout(false), layout(true)), false);
  assert.equal(showsFoodNumbers(layout(true), null), false);
  assert.equal(showsFoodNumbers(null, layout(true)), false);
});

test("only the latest request's answer counts", () => {
  const requests = latestOnly();
  const first = requests.next();
  const second = requests.next();
  assert.equal(requests.isLatest(first), false);
  assert.equal(requests.isLatest(second), true);
  // Someone else signed in: nothing asked before counts.
  requests.forget();
  assert.equal(requests.isLatest(second), false);
  assert.equal(requests.isLatest(requests.next()), true);
});
