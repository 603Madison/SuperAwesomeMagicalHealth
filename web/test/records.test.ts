// Medical records (src/lib/records.ts): how long ago, what each connection says, the counts in a
// line, lab flags in words, the record by kind, You's line and the portal's way back (npm test).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  RECORD_SECTIONS, ago, countLine, dayText, draftMedication, flagWord, fromRecord, groupByKind, isDisconnected, itemLine, keepsText, labSource, reconnect,
  returnFrom, updatedText, youLine,
} from "../src/lib/records.ts";
import type { HealthRecords, RecordConnection, RecordItem } from "../src/lib/types.ts";

const now = Date.UTC(2026, 10, 18, 15, 0);
const hours = (h: number) => now - h * 3_600_000;

const mgb: RecordConnection = {
  id: "c1", organization: "Mass General Brigham", fhirBaseUrl: "https://fhir.mgb.org/R4", provider: "EPIC",
  connectedAt: hours(48), lastSyncAt: hours(2), keepsUpdating: true, counts: { LAB: 12, MEDICATION: 3, ALLERGY: 1 },
};

const item = (id: string, change: Partial<RecordItem> = {}): RecordItem =>
  ({ id, connectionId: "c1", kind: "LAB", title: id, organization: "Mass General Brigham", ...change });

test("how long ago, in plain words", () => {
  assert.equal(ago(now - 20_000, now), "just now");
  assert.equal(ago(now - 5 * 60_000, now), "5 min ago");
  assert.equal(ago(hours(2), now), "2 h ago");
  assert.equal(ago(hours(30), now), "yesterday");
  assert.equal(ago(hours(24 * 3), now), "3 days ago");
  assert.equal(ago(Date.UTC(2026, 10, 4, 12), now), "on 4 Nov");
  assert.equal(ago(Date.UTC(2025, 2, 4, 12), now), "on 4 Mar 2025");
  // A clock a little ahead is still "just now".
  assert.equal(ago(now + 5_000, now), "just now");
});

test("a connection's lines: updated, and whether it keeps itself up to date", () => {
  assert.equal(updatedText(mgb, now), "Updated 2 h ago");
  assert.equal(updatedText({ lastSyncAt: undefined }, now), "Not updated yet");
  assert.equal(keepsText(mgb), "Updates itself daily");
  assert.equal(keepsText({ keepsUpdating: false }), "Sign in again to update");
});

test("counts in a line, in the page's order, one or many", () => {
  assert.equal(countLine(mgb.counts), "12 lab results · 3 medications · 1 allergy");
  assert.equal(countLine({ PROCEDURE: 2, LAB: 1, VITAL: 0, IMMUNIZATION: 1 }), "1 lab result · 1 immunization · 2 procedures");
  assert.equal(countLine({}), null);
  assert.equal(countLine(undefined), null);
});

test("a lab flag is always words", () => {
  assert.equal(flagWord("HIGH"), "High");
  assert.equal(flagWord("LOW"), "Low");
  assert.equal(flagWord("ABNORMAL"), "Outside range");
  assert.equal(flagWord(undefined), null);
});

test("the record by kind, newest first, undated last", () => {
  const grouped = groupByKind([
    item("Old", { date: "2025-01-02" }),
    item("Undated"),
    item("New", { at: Date.UTC(2026, 10, 10) }),
    item("Middle", { date: "2026-03-01" }),
    item("Penicillin", { kind: "ALLERGY" }),
  ]);
  assert.deepEqual(grouped.LAB!.map((i) => i.title), ["New", "Middle", "Old", "Undated"]);
  assert.deepEqual(grouped.ALLERGY!.map((i) => i.title), ["Penicillin"]);
  assert.equal(grouped.VITAL, undefined);
});

test("the sections, with ids that are saved", () => {
  assert.deepEqual(RECORD_SECTIONS, ["connections", "labs", "vitals", "medications", "conditions", "allergies", "immunizations", "procedures"]);
});

test("an item's line: detail, day, and an unusual status", () => {
  assert.equal(dayText("2026-11-04"), "4 Nov 2026");
  assert.equal(itemLine({ detail: "142 pg/mL (usual 30–400)", date: "2026-11-04", status: "final" }), "142 pg/mL (usual 30–400) · 4 Nov 2026");
  assert.equal(itemLine({ detail: "Since 2019", status: "resolved" }), "Since 2019 · Resolved");
  assert.equal(itemLine({}), "");
});

test("You's line says where it stands now", () => {
  const records = (change: Partial<HealthRecords>): HealthRecords => ({ providers: ["EPIC"], paused: false, connections: [], ...change });
  assert.equal(youLine(null, now), "Your hospital or clinic's record");
  assert.equal(youLine(records({ providers: [] }), now), "Not set up on this server yet");
  assert.equal(youLine(records({}), now), "Connect your patient portal");
  assert.equal(youLine(records({ connections: [mgb] }), now), "Mass General Brigham · updated 2 h ago");
  assert.equal(youLine(records({ connections: [{ ...mgb, lastSyncAt: undefined }] }), now), "Mass General Brigham · not updated yet");
  assert.equal(youLine(records({ connections: [mgb, { ...mgb, id: "c2", organization: "Wentworth-Douglass", lastSyncAt: hours(1) }] }), now), "Mass General Brigham and 1 more · updated 1 h ago");
  assert.equal(youLine(records({ connections: [{ ...mgb, problem: "Sign in again to bring it up to date" }] }), now), "Mass General Brigham · needs you to sign in again");
  assert.equal(youLine(records({ connections: [mgb], paused: true }), now), "Mass General Brigham · paused");
  // One disconnected (kept) doesn't count while another is connected; on its own, it says so.
  const gone = { ...mgb, id: "c3", organization: "Wentworth-Douglass", problem: "Disconnected: what it brought in is kept", keepsUpdating: false };
  assert.equal(youLine(records({ connections: [gone, mgb] }), now), "Mass General Brigham · updated 2 h ago");
  assert.equal(youLine(records({ connections: [gone] }), now), "Wentworth-Douglass · disconnected");
});

test("back from the portal: what it said, and the address without it", () => {
  assert.deepEqual(returnFrom("?connected=c1"), { connected: "c1", error: null, confirm: null, rest: "" });
  assert.deepEqual(returnFrom("?error=The+portal+said+no&x=1"), { connected: null, error: "The portal said no", confirm: null, rest: "?x=1" });
  assert.deepEqual(returnFrom("?confirm=tok123"), { connected: null, error: null, confirm: "tok123", rest: "" });
  assert.deepEqual(returnFrom(""), { connected: null, error: null, confirm: null, rest: "" });
});

test("signing in again goes to the same portal", () => {
  assert.deepEqual(reconnect(mgb), { organization: "Mass General Brigham", fhirBaseUrl: "https://fhir.mgb.org/R4", provider: "EPIC" });
});

test("labs from a record say where they came from and can't be deleted", () => {
  assert.equal(fromRecord({ source: "Mass General Brigham", ref: "record:c1:obs-1" }), true);
  assert.equal(fromRecord({ ref: "record:c1:obs-1" }), true);
  assert.equal(fromRecord({}), false);
  assert.equal(labSource({ source: "Mass General Brigham" }), "From Mass General Brigham");
  assert.equal(labSource({}), null);
});

test("a record's medication becomes a new one to add, never an edit", () => {
  const draft = draftMedication({ name: "Levothyroxine", dose: "50 mcg", id: "server-id", schedule: { kind: "DAILY", times: [], everyDays: 1, weekdays: [] } });
  assert.equal(draft.id, undefined);
  assert.equal(draft.name, "Levothyroxine");
  assert.equal(draft.dose, "50 mcg");
  assert.equal(draft.form, "PILL");
  assert.deepEqual(draft.schedule.times, ["09:00"]);
  assert.equal(draft.active, true);
  assert.deepEqual(draftMedication({ name: "Ibuprofen", schedule: { kind: "AS_NEEDED", times: [], everyDays: 1, weekdays: [] } }).schedule.times, []);
});

test("disconnected but kept, as the server says it", () => {
  assert.equal(isDisconnected({ problem: "Disconnected: what it brought in is kept" }), true);
  assert.equal(isDisconnected({ problem: "Sign in again to bring it up to date" }), false);
  assert.equal(isDisconnected({}), false);
});
