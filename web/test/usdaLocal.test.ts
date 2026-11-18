// Admin › Food search's words for USDA's food list kept on this server (src/lib/usdaLocal.ts, npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { choiceText, copyLine, datesLine, sizeText, workingLine } from "../src/lib/usdaLocal.ts";

const sizes = { everydayDownload: 18_000_000, everydayDisk: 12_000_000, allDownload: 214_000_000, allDisk: 330_000_000 };

test("sizes read plainly", () => {
  assert.equal(sizeText(18_000_000), "18 MB");
  assert.equal(sizeText(1_200_000_000), "1.2 GB");
  assert.equal(sizeText(200), "1 MB");
});

test("each choice says what it takes before downloading", () => {
  assert.equal(choiceText(sizes, false), "About 18 MB to download and 12 MB on disk. No barcodes.");
  assert.equal(choiceText(sizes, true), "About 214 MB to download and 330 MB on disk. Barcodes need these.");
});

test("the copy, what's under way, and the dates", () => {
  assert.equal(copyLine({ ready: false }), "Not downloaded yet");
  assert.equal(copyLine({ ready: true, release: "2026-10-29", foods: 412345, bytes: 330_000_000, packaged: true }), "Release 2026-10-29 · 412,345 everyday and packaged foods · 330 MB on disk");
  assert.equal(workingLine({ working: false }), null);
  assert.equal(workingLine({ working: true, phase: "Downloading packaged foods", progress: 0.42 }), "Downloading packaged foods · 42%");
  assert.equal(workingLine({ working: true, phase: "Finishing up", progress: null }), "Finishing up");
  assert.equal(datesLine({ lastUpdated: 1, nextCheck: 2 }, (ms) => `day ${ms}`), "Last updated day 1 · Next check day 2");
  assert.equal(datesLine({}, String), null);
});
