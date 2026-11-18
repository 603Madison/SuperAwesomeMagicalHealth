// Where your data goes (src/lib/privacy.ts): which ways out can be paused here, what each says about
// itself, where it's set up, and the change shown at once while a pause is saved (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import { countText, pausedToast, privacyLine, setUpPage, setUpSection, stateText, switchable, withPaused } from "../src/lib/privacy.ts";
import type { DataDestination, DataMap, DataPath, DataPathState } from "../src/lib/types.ts";

function way(path: DataPath, state: DataPathState, change: Partial<DataDestination> = {}): DataDestination {
  return { path, name: path, who: "Someone", hosts: [], what: "Something", whenSent: "Sometimes", state, pausable: true, ...change };
}

const map: DataMap = {
  destinations: [
    way("FOOD_DATABASE", "ON"),
    way("WEATHER", "OFF_ON_SERVER", { setUp: "settings.profile" }),
    way("MEALIE", "PAUSED", { setUp: "settings.connections" }),
    way("SHARES", "NOT_SET_UP", { count: 0, setUp: "settings.sharing" }),
    way("SIGN_IN", "ON", { pausable: false }),
  ],
  note: "Everything else stays on this server.",
};

test("a switch only for what can be paused, set up and not turned off on the server", () => {
  assert.deepEqual(map.destinations.map(switchable), [true, false, true, false, false]);
  // Pausable unless the server says otherwise.
  assert.equal(switchable({ state: "ON" }), true);
});

test("each one's state in a word", () => {
  assert.deepEqual(map.destinations.map(stateText), ["On", "Turned off on this server", "Paused", "Not set up", "Always on"]);
});

test("where each is set up: the section on the Settings page", () => {
  assert.equal(setUpSection({ path: "MEALIE", setUp: "settings.connections" }), "mealie");
  assert.equal(setUpSection({ path: "WEBHOOKS", setUp: "settings.connections" }), "connections");
  assert.equal(setUpSection({ path: "AI_ASSISTANT", setUp: "settings.ai" }), "assistant");
  assert.equal(setUpSection({ path: "AI_CONNECTORS", setUp: "settings.connector" }), "connector");
  assert.equal(setUpSection({ path: "WEBHOOKS", setUp: "settings.integrations" }), "integrations");
  assert.equal(setUpSection({ path: "BROWSER_PUSH", setUp: "settings.notifications" }), "reminders");
  assert.equal(setUpSection({ path: "EMAIL", setUp: "settings.digest" }), "digest");
  assert.equal(setUpSection({ path: "SHARES", setUp: "settings.sharing" }), "sharing");
  assert.equal(setUpSection({ path: "WEATHER", setUp: "settings.profile" }), "profile");
  assert.equal(setUpSection({ path: "FOOD_DATABASE" }), null);
  assert.equal(setUpSection({ path: "FOOD_DATABASE", setUp: "settings.somewhere-new" }), null);
});

test("how many are set up, in words", () => {
  assert.equal(countText({ path: "AI_CONNECTORS", count: 1 }), "1 link");
  assert.equal(countText({ path: "AI_CONNECTORS", count: 2 }), "2 links");
  assert.equal(countText({ path: "WEBHOOKS", count: 3 }), "3 webhooks");
  assert.equal(countText({ path: "BROWSER_PUSH", count: 1 }), "1 browser");
  assert.equal(countText({ path: "HOME_ASSISTANT", count: 2 }), "2 keys");
  assert.equal(countText({ path: "SHARES", count: 1 }), "Shared with 1 person");
  assert.equal(countText({ path: "SHARES", count: 3 }), "Shared with 3 people");
  assert.equal(countText({ path: "SHARES", count: 0 }), null);
  assert.equal(countText({ path: "MEALIE" }), null);
  assert.equal(countText({ path: "HEALTH_RECORDS", count: 1 }), "1 portal");
  assert.equal(countText({ path: "HEALTH_RECORDS", count: 2 }), "2 portals");
});

test("a pause shows at once: only that one, and only where it can be paused", () => {
  const paused = withPaused(map, "FOOD_DATABASE", true);
  assert.equal(paused.destinations[0].state, "PAUSED");
  assert.deepEqual(paused.destinations.slice(1), map.destinations.slice(1));
  assert.equal(paused.note, map.note);
  assert.equal(withPaused(map, "MEALIE", false).destinations[2].state, "ON");
  // Nothing set up, turned off on the server, or signing in: as they were.
  assert.deepEqual(withPaused(map, "SHARES", true), map);
  assert.deepEqual(withPaused(map, "WEATHER", true), map);
  assert.deepEqual(withPaused(map, "SIGN_IN", true), map);
  // The map itself is left alone.
  assert.equal(map.destinations[0].state, "ON");
});

test("You's line: how many ways out are on and paused; signing in isn't counted", () => {
  assert.equal(privacyLine(map), "1 way out on, 1 paused · everything else stays on your server");
  assert.equal(privacyLine({ destinations: [way("SIGN_IN", "ON", { pausable: false }), way("SHARES", "NOT_SET_UP")] }), "Everything stays on your server");
  assert.equal(privacyLine({ destinations: [way("MEALIE", "ON"), way("WEBHOOKS", "ON")] }), "2 ways out on · everything else stays on your server");
});

test("what the toast says", () => {
  assert.equal(pausedToast({ name: "Mealie" }, true), "Mealie: paused. Nothing goes until you turn it back on.");
  assert.equal(pausedToast({ name: "Mealie" }, false), "Mealie: on again");
});

test("medical records are set up on a page of their own", () => {
  assert.deepEqual(setUpPage({ setUp: "records" }), { to: "/records", label: "Medical records" });
  assert.equal(setUpSection({ path: "HEALTH_RECORDS", setUp: "records" }), null);
  assert.equal(setUpPage({ setUp: "settings.sharing" }), null);
  assert.equal(setUpPage({}), null);
});
