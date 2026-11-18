import type { HealthRecords, LabResult, MedSchedule, Medication, RecordConnect, RecordConnection, RecordFlag, RecordItem, RecordKind } from "./types";

/*
 * Medical records (pages/Records.tsx, You › Medical records, /api/records), the parts that don't
 * need React (npm test covers them): how long ago, what each connection says about itself, the
 * counts in a line, a lab flag in words, the record grouped by kind, You's line, and the address's
 * ?connected= / ?error= after the portal sends the browser back. Type imports only, so node can run
 * it as it is.
 */

/** The kinds in the order the page shows them (RecordKind's order). */
export const KIND_ORDER: RecordKind[] = ["LAB", "VITAL", "MEDICATION", "CONDITION", "ALLERGY", "IMMUNIZATION", "PROCEDURE"];

/** Each kind's section id on the page (web.records): saved with the person's arrangement, so never rename one. */
export const KIND_SECTION: Record<RecordKind, string> = {
  LAB: "labs", VITAL: "vitals", MEDICATION: "medications", CONDITION: "conditions",
  ALLERGY: "allergies", IMMUNIZATION: "immunizations", PROCEDURE: "procedures",
};

/** The connections' section id. */
export const CONNECTIONS_SECTION = "connections";

/** Every section id, in the default order. */
export const RECORD_SECTIONS: string[] = [CONNECTIONS_SECTION, ...KIND_ORDER.map((k) => KIND_SECTION[k])];

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago", or "on 4 Nov" (with the year when it isn't this one). */
export function ago(ms: number, now: number): string {
  const seconds = Math.max(0, (now - ms) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)} h ago`;
  if (seconds < 2 * 86_400) return "yesterday";
  if (seconds < 7 * 86_400) return `${Math.floor(seconds / 86_400)} days ago`;
  const d = new Date(ms);
  const sameYear = d.getFullYear() === new Date(now).getFullYear();
  return `on ${d.getDate()} ${MONTHS[d.getMonth()]}${sameYear ? "" : ` ${d.getFullYear()}`}`;
}

/** "4 Nov 2026" from "2026-11-04"; the text as it is when it isn't a date. */
export function dayText(date: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!m) return date;
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1] ?? m[2]} ${m[1]}`;
}

/** "Updated 2 h ago", or "Not updated yet". */
export function updatedText(c: Pick<RecordConnection, "lastSyncAt">, now: number): string {
  return c.lastSyncAt ? `Updated ${ago(c.lastSyncAt, now)}` : "Not updated yet";
}

/** Whether it keeps itself up to date: "Updates itself daily", or "Sign in again to update". */
export function keepsText(c: Pick<RecordConnection, "keepsUpdating">): string {
  return c.keepsUpdating ? "Updates itself daily" : "Sign in again to update";
}

const NOUNS: Record<RecordKind, [string, string]> = {
  LAB: ["lab result", "lab results"],
  VITAL: ["vital sign", "vital signs"],
  MEDICATION: ["medication", "medications"],
  CONDITION: ["condition", "conditions"],
  ALLERGY: ["allergy", "allergies"],
  IMMUNIZATION: ["immunization", "immunizations"],
  PROCEDURE: ["procedure", "procedures"],
};

/** "12 lab results · 3 medications · 1 allergy", in the page's order; null when nothing came in. */
export function countLine(counts: Partial<Record<RecordKind, number>> | undefined): string | null {
  const parts = KIND_ORDER.flatMap((k) => {
    const n = counts?.[k] ?? 0;
    return n > 0 ? [`${n} ${n === 1 ? NOUNS[k][0] : NOUNS[k][1]}`] : [];
  });
  return parts.length > 0 ? parts.join(" · ") : null;
}

/** A lab flag in words, never colour alone: "High", "Low", "Outside range"; null for none. */
export function flagWord(flag: RecordFlag | undefined | null): string | null {
  switch (flag) {
    case "HIGH": return "High";
    case "LOW": return "Low";
    case "ABNORMAL": return "Outside range";
    default: return null;
  }
}

/** When an item happened, to sort by: its time, else its day, else nothing (last). */
function whenOf(item: Pick<RecordItem, "at" | "date">): number {
  if (item.at != null) return item.at;
  if (item.date) {
    const t = Date.parse(`${item.date.slice(0, 10)}T12:00:00Z`);
    if (!Number.isNaN(t)) return t;
  }
  return Number.NEGATIVE_INFINITY;
}

/** The record, a kind at a time, each newest first (undated last, by title); kinds with nothing are left out. */
export function groupByKind(items: RecordItem[]): Partial<Record<RecordKind, RecordItem[]>> {
  const out: Partial<Record<RecordKind, RecordItem[]>> = {};
  for (const item of items) (out[item.kind] ??= []).push(item);
  for (const kind of Object.keys(out) as RecordKind[]) {
    out[kind]!.sort((a, b) => whenOf(b) - whenOf(a) || a.title.localeCompare(b.title));
  }
  return out;
}

/** The line under an item's title: its detail, its day, and its status when it isn't the usual one. */
export function itemLine(item: Pick<RecordItem, "detail" | "date" | "status">): string {
  const status = item.status && !["active", "final", "completed"].includes(item.status.toLowerCase())
    ? item.status.charAt(0).toUpperCase() + item.status.slice(1)
    : null;
  return [item.detail, item.date ? dayText(item.date) : null, status].filter(Boolean).join(" · ");
}

/** What to send to connect to the same portal again ("Sign in again"). */
export function reconnect(c: Pick<RecordConnection, "organization" | "fhirBaseUrl" | "provider">): RecordConnect {
  return { organization: c.organization, fhirBaseUrl: c.fhirBaseUrl, provider: c.provider };
}

/**
 * You's line for Medical records, true now: where it stands, or the connected portal and when it
 * was last brought up to date ("Mass General Brigham · updated 2 h ago").
 */
export function youLine(records: HealthRecords | null | undefined, now: number): string {
  if (!records) return "Your hospital or clinic's record";
  const all = records.connections;
  if (all.length === 0) return records.providers.length === 0 ? "Not set up on this server yet" : "Connect your patient portal";
  // Disconnected ones (kept, without access) only count when nothing else is connected.
  const live = all.filter((c) => !isDisconnected(c));
  const list = live.length > 0 ? live : all;
  const first = list[0].organization;
  const who = list.length === 1 ? first : `${first} and ${list.length - 1} more`;
  if (live.length === 0) return `${who} · disconnected`;
  if (records.paused) return `${who} · paused`;
  if (list.some((c) => c.problem)) return `${who} · needs you to sign in again`;
  const latest = Math.max(...list.map((c) => c.lastSyncAt ?? 0));
  return latest > 0 ? `${who} · updated ${ago(latest, now)}` : `${who} · not updated yet`;
}

/**
 * What the portal said on its way back (?connected=…, ?error=…, or ?confirm=… for the page to
 * confirm the sign-in with the server), and the address without it.
 */
export function returnFrom(search: string): { connected: string | null; error: string | null; confirm: string | null; rest: string } {
  const params = new URLSearchParams(search);
  const connected = params.get("connected");
  const error = params.get("error");
  const confirm = params.get("confirm");
  for (const key of ["connected", "error", "confirm"]) params.delete(key);
  const rest = params.toString();
  return { connected, error, confirm, rest: rest ? `?${rest}` : "" };
}

/**
 * Disconnected but kept: its access is gone and what it brought in stays (the server says so in
 * its problem, "Disconnected: …"). It can be connected again, or removed with all it brought.
 */
export function isDisconnected(c: Pick<RecordConnection, "problem">): boolean {
  return !!c.problem?.startsWith("Disconnected");
}

/** A lab result from a connected record: the server brings it back, so it can't be deleted here. */
export function fromRecord(lab: Pick<LabResult, "source" | "ref">): boolean {
  return !!lab.source || !!lab.ref?.startsWith("record:");
}

/** "From Mass General Brigham" for a lab from a record; null for one entered here. */
export function labSource(lab: Pick<LabResult, "source">): string | null {
  return lab.source ? `From ${lab.source}` : null;
}

/** A medication from a record as a new one to add here: the record's name and dose, everything else as a new one starts. */
export function draftMedication(m: Partial<Medication> & { name: string }): Medication {
  const schedule: MedSchedule = { kind: "DAILY", times: ["09:00"], everyDays: 1, weekdays: [], ...m.schedule };
  if (schedule.kind !== "AS_NEEDED" && schedule.times.length === 0) schedule.times = ["09:00"];
  const { id: _id, ...rest } = m;
  return { form: "PILL", sites: [], reminders: true, active: true, ...rest, schedule };
}
