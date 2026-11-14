// CSV files to download (src/lib/csv.ts): the bladder diary's cells, quoted and safe in a spreadsheet (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { csvCell, toCsv } from "../src/lib/csv.ts";

test("every cell is quoted, with its quotes doubled", () => {
  assert.equal(csvCell("Urination"), '"Urination"');
  assert.equal(csvCell('a "big" one'), '"a ""big"" one"');
  assert.equal(csvCell("cough; sneeze, laugh"), '"cough; sneeze, laugh"');
  assert.equal(csvCell(250), '"250"');
  assert.equal(csvCell(0), '"0"');
  assert.equal(csvCell(""), '""');
  assert.equal(csvCell(null), '""');
  assert.equal(csvCell(undefined), '""');
});

test("carriage returns are dropped; line breaks stay inside the quotes", () => {
  assert.equal(csvCell("first line\r\nsecond"), '"first line\nsecond"');
  assert.equal(csvCell("a\rb"), '"ab"');
});

test("nothing a spreadsheet would run as a formula", () => {
  assert.equal(csvCell("=HYPERLINK(\"http://x\")"), '"\'=HYPERLINK(""http://x"")"');
  assert.equal(csvCell("+1 cup"), '"\'+1 cup"');
  assert.equal(csvCell("-2"), '"\'-2"');
  assert.equal(csvCell("@SUM(A1)"), '"\'@SUM(A1)"');
  assert.equal(csvCell("\tlater"), '"\'\tlater"');
  // A carriage return in front doesn't hide one.
  assert.equal(csvCell("\r=1+1"), '"\'=1+1"');
  // Only at the start.
  assert.equal(csvCell("2-3 times"), '"2-3 times"');
  assert.equal(csvCell("a=b"), '"a=b"');
});

test("rows are lines ending in CRLF", () => {
  assert.equal(toCsv([["Date", "Note"], ["2026-11-13", "=cmd"], ["2026-11-14", undefined]]),
    '"Date","Note"\r\n"2026-11-13","\'=cmd"\r\n"2026-11-14",""\r\n');
});
