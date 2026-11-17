// How the app is laid out (src/lib/nav.ts): the five tabs, which tab a page belongs to, where back
// goes, the badges' count, and Settings' pages (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { NAMES, SETTINGS_PAGES, TABS, backFor, badgeText, compareOffered, isTabRoot, tabOf, unseenCount } from "../src/lib/nav.ts";

test("five tabs, always the same, in order", () => {
  assert.deepEqual(TABS.map((t) => [t.to, t.label]), [["/", "Today"], ["/recent", "24 hours"], ["/health", "Health"], ["/trends", "Trends"], ["/more", "More"]]);
  assert.equal(NAMES.recent, "Last 24 hours");
});

test("each page lights up its tab: features under Health; records, devices and settings under More", () => {
  assert.equal(tabOf("/"), "today");
  assert.equal(tabOf("/recent"), "recent");
  assert.equal(tabOf("/trends"), "trends");
  for (const p of ["/health", "/food", "/sleep", "/bladder", "/bladder/settings", "/cycle/settings", "/transition/voice", "/supplies", "/vitals/"]) assert.equal(tabOf(p), "health", p);
  for (const p of ["/more", "/alerts", "/digest", "/reports", "/devices", "/devices/compare", "/settings", "/conditions", "/assistant", "/assistant/sent", "/admin"]) assert.equal(tabOf(p), "more", p);
  // A path that only starts like a feature's isn't one.
  assert.equal(tabOf("/foodie"), "more");
});

test("tab roots have no back arrow; every other page goes back to its parent", () => {
  for (const p of ["/", "/recent", "/health", "/trends", "/more", "/more/"]) {
    assert.equal(isTabRoot(p), true, p);
    assert.equal(backFor(p), null, p);
  }
  assert.deepEqual(backFor("/sleep"), { to: "/health", label: "Health" });
  assert.deepEqual(backFor("/bladder/settings"), { to: "/bladder", label: "Bladder & bowel" });
  assert.deepEqual(backFor("/transition/voice"), { to: "/transition", label: "Transition" });
  assert.deepEqual(backFor("/supplies"), { to: "/health", label: "Health" });
  assert.deepEqual(backFor("/devices/compare"), { to: "/devices", label: "Devices" });
  assert.deepEqual(backFor("/assistant/sent"), { to: "/assistant", label: "Assistant" });
  for (const p of ["/alerts", "/digest", "/reports", "/devices", "/settings", "/conditions", "/assistant", "/admin"]) assert.deepEqual(backFor(p), { to: "/more", label: "More" }, p);
  assert.equal(backFor("/somewhere-else"), null);
});

test("the badges count what's unseen and not put away, and stop at 9+", () => {
  assert.equal(unseenCount([{}, { seenAt: 1 }, { dismissedAt: 2 }, {}]), 2);
  assert.equal(unseenCount([]), 0);
  assert.equal(badgeText(0), null);
  assert.equal(badgeText(null), null);
  assert.equal(badgeText(3), "3");
  assert.equal(badgeText(12), "9+");
});

test("Compare devices only with two devices that record readings", () => {
  assert.equal(compareOffered([{ kind: "COLMI_RING" }, { kind: "HEART_RATE_STRAP" }]), true);
  assert.equal(compareOffered([{ kind: "COLMI_RING" }, { kind: "MANUAL" }, { kind: "BP_MONITOR" }]), false);
  assert.equal(compareOffered(null), false);
});

test("Settings' pages keep the ids links use, with their new names", () => {
  assert.deepEqual(SETTINGS_PAGES.map((p) => [p.id, p.label]), [
    ["you", "You & goals"], ["features", "What you're tracking"], ["reminders", "Reminders & sharing"],
    ["connections", "Connections & privacy"], ["appearance", "Appearance"], ["account", "Account"],
  ]);
});
