// How the app is laid out (src/lib/nav.ts): the tabs, which tab a page belongs to, where back goes,
// where the old addresses lead, Body's spans, the badges' count, and Settings' pages (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BODY_SPANS, MOVED, NAMES, SETTINGS_PAGES, TABS, backFor, badgeText, bodyPath, compareOffered, isTabRoot, movedTo, spanOf, tabOf, unseenCount,
} from "../src/lib/nav.ts";

test("four tabs, always the same, in order (the + sits between Body and Care)", () => {
  assert.deepEqual(TABS.map((t) => [t.to, t.label]), [["/", "Today"], ["/body", "Body"], ["/care", "Care"], ["/you", "You"]]);
  assert.equal(NAMES.recent, "Last 24 hours");
  assert.equal(NAMES.log, "Log");
});

test("each page lights up its tab: features under Care; what devices measured under Body; the rest under You", () => {
  assert.equal(tabOf("/"), "today");
  for (const p of ["/body", "/body/", "/body/weight", "/alerts", "/devices/compare"]) assert.equal(tabOf(p), "body", p);
  for (const p of ["/care", "/food", "/bladder", "/bladder/settings", "/cycle/settings", "/transition/voice", "/supplies", "/vitals/", "/meds", "/photos"]) assert.equal(tabOf(p), "care", p);
  for (const p of ["/you", "/settings", "/devices", "/digest", "/reports", "/conditions", "/assistant", "/assistant/sent", "/admin"]) assert.equal(tabOf(p), "you", p);
  // A path that only starts like a feature's isn't one.
  assert.equal(tabOf("/foodie"), "you");
  assert.equal(tabOf("/bodyguard"), "you");
});

test("tab roots have no back arrow; every other page goes back to its parent", () => {
  for (const p of ["/", "/body", "/care", "/you", "/you/"]) {
    assert.equal(isTabRoot(p), true, p);
    assert.equal(backFor(p), null, p);
  }
  for (const p of ["/food", "/bladder", "/meds", "/hormones", "/photos", "/neuro", "/supplies"]) assert.deepEqual(backFor(p), { to: "/care", label: "Care" }, p);
  assert.deepEqual(backFor("/bladder/settings"), { to: "/bladder", label: "Bladder & bowel" });
  assert.deepEqual(backFor("/transition/voice"), { to: "/transition", label: "Transition" });
  for (const p of ["/body/weight", "/alerts", "/devices/compare"]) assert.deepEqual(backFor(p), { to: "/body", label: "Body" }, p);
  assert.deepEqual(backFor("/assistant/sent"), { to: "/assistant", label: "Assistant" });
  for (const p of ["/digest", "/reports", "/devices", "/settings", "/conditions", "/assistant", "/admin"]) assert.deepEqual(backFor(p), { to: "/you", label: "You" }, p);
  assert.equal(backFor("/somewhere-else"), null);
});

test("the old addresses lead where they are now, keeping what they asked for", () => {
  assert.equal(movedTo("/recent"), "/body");
  assert.equal(movedTo("/recent", "?device=ring1"), "/body?device=ring1");
  assert.equal(movedTo("/trends"), "/body?span=month");
  assert.equal(movedTo("/sleep"), "/body?span=week#nights");
  assert.equal(movedTo("/activity/"), "/body?span=week#workouts");
  assert.equal(movedTo("/health"), "/care");
  assert.equal(movedTo("/more"), "/you");
  assert.equal(movedTo("/food"), null);
  assert.deepEqual([...MOVED].map((p) => movedTo(p) != null), MOVED.map(() => true));
});

test("Body's spans: 24 hours unless asked, and never 24 hours while viewing someone's share", () => {
  assert.deepEqual(BODY_SPANS.map((s) => [s.id, s.label, s.days]), [["day", "24 hours", 1], ["week", "Week", 7], ["month", "Month", 30], ["year", "Year", 365]]);
  assert.equal(spanOf(null), "day");
  assert.equal(spanOf("year"), "year");
  assert.equal(spanOf("fortnight"), "day");
  assert.equal(spanOf(null, true), "week");
  assert.equal(spanOf("month", true), "month");
  assert.equal(bodyPath(), "/body");
  assert.equal(bodyPath("day", "ring 1"), "/body?device=ring+1");
  // A device is only for 24 hours.
  assert.equal(bodyPath("week", "ring1", "nights"), "/body?span=week#nights");
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

test("Settings' pages: five plain groups and Where your data goes, keeping the ids links use", () => {
  assert.deepEqual(SETTINGS_PAGES.map((p) => [p.id, p.label]), [
    ["features", "What you track"], ["reminders", "Reminders, alerts and sharing"], ["you", "Profile and goals"],
    ["connections", "Connections"], ["appearance", "Look and account"], ["privacy", "Where your data goes"],
  ]);
});
