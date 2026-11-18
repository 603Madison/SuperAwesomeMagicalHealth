// The app sign-in's pairing code (src/lib/pairing.ts), the same as the Android app's (npm test).
import { test } from "node:test";
import assert from "node:assert/strict";
import { pairingCode } from "../src/lib/pairing.ts";

test("six digits from the challenge, the same as the app's", () => {
  assert.equal(pairingCode("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"), "249 228");
  assert.match(pairingCode("abc"), /^\d{3} \d{3}$/);
});
