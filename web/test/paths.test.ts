// Where to go after signing in (src/lib/paths.ts): only pages on this server (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { safeReturn } from "../src/lib/paths.ts";

test("a page on this server is kept as it is", () => {
  assert.equal(safeReturn("/"), "/");
  assert.equal(safeReturn("/food?date=2026-11-13#water"), "/food?date=2026-11-13#water");
  assert.equal(safeReturn("/settings"), "/settings");
  assert.equal(safeReturn("/watch-setup?code=a%2Fb"), "/watch-setup?code=a%2Fb");
});

test("nothing, or anything that isn't a path here, goes home", () => {
  for (const path of [null, undefined, "", "food", " /food", "https://elsewhere.example/", "javascript:alert(1)", "data:text/html,hi"]) {
    assert.equal(safeReturn(path), "/", String(path));
  }
});

test("never another site, however it's written", () => {
  for (const path of ["//elsewhere.example", "/\\elsewhere.example", "/\\/elsewhere.example", "/\t/elsewhere.example", "/\n/elsewhere.example", "/\r\n/elsewhere.example", "/x\\..\\..\\/elsewhere"]) {
    assert.equal(safeReturn(path), "/", JSON.stringify(path));
  }
});
