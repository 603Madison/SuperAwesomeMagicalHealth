// Health alerts (src/lib/alerts.ts): each level's words, when an alert happened, what's new, what
// putting one away says, what it's watching, and the settings within the server's limits (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ALERT_LEVEL_TEXT, ALERT_SECTIONS, DEFAULT_ALERT_SETTINGS, alertWhen, awayText, clampAlertNumber, clampAlertSettings, devicesText, emptyText, isAway,
  alertsLine, newestFirst, replaceAlert, settingsChanged, stepAlertNumber, unseenIds, watchState,
} from "../src/lib/alerts.ts";
import { clockTime, dateIn } from "../src/lib/today.ts";
import { ALERT_FEEDBACK_LABELS, type AlertWatch, type HealthAlert } from "../src/lib/types.ts";

const H = 3_600_000;
const ZONE = "America/New_York";
const utc = (d: number, h: number, mi = 0) => Date.UTC(2026, 10, d, h, mi);
const plain = (s: string) => s.replace(/\s/g, " ");
/** "Today" is 17 November where the person is. */
const clock = {
  time: (ms: number) => plain(clockTime(ms, ZONE, false, "en-US")),
  day: (ms: number) => ({ "2026-11-17": "Today", "2026-11-16": "Yesterday" })[dateIn(ms, ZONE)] ?? dateIn(ms, ZONE),
};

function alert(id: string, change: Partial<HealthAlert> = {}): HealthAlert {
  return { id, kind: "HIGH_HEART_RATE", level: "DOCTOR", title: "Your heart rate stayed high at rest", body: "", advice: "", start: utc(17, 7, 10), createdAt: utc(17, 8), ...change };
}

test("the page's sections keep their saved ids; each level in plain words", () => {
  assert.deepEqual([...ALERT_SECTIONS], ["alerts", "watching", "settings"]);
  assert.deepEqual(ALERT_LEVEL_TEXT, { URGENT: "Could need care now", DOCTOR: "Worth a doctor's look", WATCH: "Keep an eye on" });
});

test("when: a moment, a spell, across midnight, and over days", () => {
  // 2:10 AM in New York is 7:10 UTC.
  assert.equal(alertWhen({ start: utc(17, 7, 10) }, clock), "Today, 2:10 AM");
  assert.equal(alertWhen({ start: utc(17, 7, 10), end: utc(17, 7, 48) }, clock), "Today, 2:10 AM – 2:48 AM");
  assert.equal(alertWhen({ start: utc(17, 4, 40), end: utc(17, 5, 25) }, clock), "Yesterday 11:40 PM – Today 12:25 AM");
  assert.equal(alertWhen({ start: utc(14, 5), end: utc(17, 5) }, clock), "2026-11-14 – Today");
  assert.equal(alertWhen({ start: utc(16, 5), end: utc(16, 5) + 23 * H }, clock), "Yesterday");
  // An end before the start (it shouldn't happen): the start alone.
  assert.equal(alertWhen({ start: utc(17, 7, 10), end: utc(17, 7) }, clock), "Today, 2:10 AM");
});

test("newest first (urgent ones ahead); put away stays in the list; new means not seen and not put away", () => {
  const list = [alert("a", { createdAt: 1 }), alert("b", { createdAt: 3 }), alert("c", { createdAt: 2 })];
  assert.deepEqual(newestFirst(list).map((a) => a.id), ["b", "c", "a"]);
  assert.deepEqual(list.map((a) => a.id), ["a", "b", "c"]);
  // One that could need care now comes first, unless it's been put away.
  const mixed = [alert("new", { createdAt: 5, level: "WATCH" }), alert("urgent", { createdAt: 2, level: "URGENT" }), alert("away", { createdAt: 4, level: "URGENT", dismissedAt: 6 })];
  assert.deepEqual(newestFirst(mixed).map((a) => a.id), ["urgent", "new", "away"]);
  assert.deepEqual(unseenIds([alert("a"), alert("b", { seenAt: 5 }), alert("c", { dismissedAt: 6 })]), ["a"]);
  assert.equal(isAway(alert("a")), false);
  assert.equal(isAway(alert("a", { dismissedAt: 9 })), true);
  const away = alert("b", { dismissedAt: 9, feedback: "ACTIVE" });
  assert.deepEqual(replaceAlert(list, away).map((a) => a.dismissedAt ?? null), [null, 9, null]);
});

test("what a put-away alert says", () => {
  assert.equal(awayText({ feedback: "ACTIVE" }, ALERT_FEEDBACK_LABELS), "Put away: I was active");
  assert.equal(awayText({ feedback: "DEVICE" }, ALERT_FEEDBACK_LABELS), "Put away: The device slipped or misread");
  assert.equal(awayText({}, ALERT_FEEDBACK_LABELS), "Put away");
});

test("the devices it came from", () => {
  assert.equal(devicesText(["Polar H10"]), "From Polar H10");
  assert.equal(devicesText(["Polar H10", "Colmi R02"]), "From Polar H10 and Colmi R02");
  assert.equal(devicesText(["A", "B", "C"]), "From A, B and C");
  assert.equal(devicesText([]), null);
  assert.equal(devicesText(undefined), null);
});

test("what it's watching: on, off by choice, or not yet", () => {
  const w = (watching: boolean, how: string): AlertWatch => ({ what: "Heart rhythm", how, watching });
  assert.equal(watchState(w(true, "From Polar H10, while it records")), "on");
  assert.equal(watchState(w(false, "Turned off")), "off");
  assert.equal(watchState(w(false, "Needs a chest strap: a ring or watch can't tell")), "not-yet");
  assert.equal(watchState(w(false, "Learning your usual: 3 more nights")), "not-yet");
});

test("the empty list's words", () => {
  const on: AlertWatch[] = [{ what: "Blood oxygen", how: "From Colmi R02", watching: true }];
  assert.equal(emptyText({ enabled: true }, on), "No alerts in the last 30 days. Here's what it's watching:");
  assert.equal(emptyText({ enabled: true }, [{ ...on[0], watching: false }]), "There's nothing to watch yet: a device that measures heart rate or blood oxygen brings it in.");
  assert.match(emptyText({ enabled: false }, on), /^Health alerts are off/);
});

test("settings stay within the server's limits, and steps land on round numbers", () => {
  assert.equal(clampAlertNumber("highHeartRate", 200), 180);
  assert.equal(clampAlertNumber("highHeartRate", 99), 100);
  assert.equal(clampAlertNumber("lowHeartRate", 25), 30);
  assert.equal(clampAlertNumber("lowOxygen", 96), 94);
  assert.equal(clampAlertNumber("lowOxygen", 88.6), 89);
  assert.equal(clampAlertNumber("lowOxygen", Number.NaN), 90);
  assert.deepEqual(clampAlertSettings({ ...DEFAULT_ALERT_SETTINGS, highHeartRate: 300, lowHeartRate: 10, lowOxygen: 80 }),
    { ...DEFAULT_ALERT_SETTINGS, highHeartRate: 180, lowHeartRate: 30, lowOxygen: 85 });
  assert.equal(stepAlertNumber("highHeartRate", 120, 1), 125);
  assert.equal(stepAlertNumber("highHeartRate", 122, 1), 125);
  assert.equal(stepAlertNumber("highHeartRate", 122, -1), 120);
  assert.equal(stepAlertNumber("highHeartRate", 180, 1), 180);
  assert.equal(stepAlertNumber("lowHeartRate", 30, -1), 30);
  assert.equal(stepAlertNumber("lowOxygen", 90, -1), 89);
  assert.equal(settingsChanged(DEFAULT_ALERT_SETTINGS, { ...DEFAULT_ALERT_SETTINGS }), false);
  assert.equal(settingsChanged(DEFAULT_ALERT_SETTINGS, { ...DEFAULT_ALERT_SETTINGS, notify: false }), true);
});

test("Body's line for Health alerts: what's new, and how much it's watching", () => {
  const watching: AlertWatch[] = [{ what: "Heart rate", how: "", watching: true }, { what: "Blood oxygen", how: "", watching: true }, { what: "Getting ill", how: "Learning", watching: false }];
  assert.equal(alertsLine(1, { settings: DEFAULT_ALERT_SETTINGS, watching }), "1 new · watching 2 things");
  assert.equal(alertsLine(0, { settings: DEFAULT_ALERT_SETTINGS, watching: watching.slice(0, 1) }), "Nothing new · watching 1 thing");
  assert.equal(alertsLine(0, { settings: DEFAULT_ALERT_SETTINGS, watching: [] }), "Nothing new · nothing to watch yet");
  assert.equal(alertsLine(0, { settings: { ...DEFAULT_ALERT_SETTINGS, enabled: false }, watching }), "Turned off");
  // Before the list has come: just what's new.
  assert.equal(alertsLine(2, null), "2 new");
  assert.equal(alertsLine(0, null), "A heads-up when your readings look out of the ordinary");
});
