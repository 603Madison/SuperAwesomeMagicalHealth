import type { DeviceKind, HealthAlert, Module } from "./types";

/*
 * How the app is laid out (npm test covers it): the tabs a phone shows (as the Android app has
 * them), which tab each page belongs to, where a page's back arrow goes, each place's one name, the
 * old addresses and where they lead now, and the unseen health alerts' count for the badges. Type
 * imports only, so node can run it as it is.
 *
 *   Today · Body · [+] · Care · You
 *
 * Today is what's happening; Body what the devices measured (24 hours, week, month or year); + logs
 * anything; Care holds what the person looks after, one card per area, each opening its page; You
 * holds devices, records, where data goes and the settings. On a wide screen the sidebar has the
 * same four, with Log at the top.
 */

/** Each place's one name, wherever it's shown (titles, rows, links). */
export const NAMES = {
  today: "Today",
  body: "Body",
  care: "Care",
  you: "You",
  log: "Log",
  recent: "Last 24 hours",
  alerts: "Health alerts",
  digest: "Your week",
  digestSettings: "Weekly digest",
  reports: "A report for your doctor",
  tracking: "What you track",
  chooseTracking: "Choose what you track",
  devices: "Devices",
  compare: "Compare devices",
  assistant: "Assistant",
  settings: "Settings",
  privacy: "Where your data goes",
  records: "Medical records",
  weight: "Weight",
} as const;

export type Tab = "today" | "body" | "care" | "you";

/** The tabs, left to right, always these (the + sits in the middle, between Body and Care). */
export const TABS: { id: Tab; to: string; label: string }[] = [
  { id: "today", to: "/", label: NAMES.today },
  { id: "body", to: "/body", label: NAMES.body },
  { id: "care", to: "/care", label: NAMES.care },
  { id: "you", to: "/you", label: NAMES.you },
];

// ---- Body's spans ----

export type BodySpan = "day" | "week" | "month" | "year";

/** Body's switch: the last 24 hours as recorded, or the days of a week, a month or a year. */
export const BODY_SPANS: { id: BodySpan; label: string; days: number }[] = [
  { id: "day", label: "24 hours", days: 1 },
  { id: "week", label: "Week", days: 7 },
  { id: "month", label: "Month", days: 30 },
  { id: "year", label: "Year", days: 365 },
];

/**
 * The span an address asks for (?span=week); 24 hours without one. While viewing someone's share
 * ([shared]), never 24 hours: the last 24 hours as recorded is only ever the person's own.
 */
export function spanOf(value: string | null | undefined, shared = false): BodySpan {
  const span = BODY_SPANS.find((s) => s.id === value)?.id ?? "day";
  return shared && span === "day" ? "week" : span;
}

/** Body at [span] (and one device's readings, for 24 hours), with [hash] to land on a section. */
export function bodyPath(span: BodySpan = "day", device?: string | null, hash?: string): string {
  const params = new URLSearchParams();
  if (span !== "day") params.set("span", span);
  if (device && span === "day") params.set("device", device);
  const query = params.toString();
  return `/body${query ? `?${query}` : ""}${hash ? `#${hash}` : ""}`;
}

// ---- Where each page belongs ----

/** The areas' pages (Care's cards open them), and the feature each needs. */
export const CARE_PATHS: Record<string, Module> = {
  "/food": "NUTRITION", "/vitals": "VITALS", "/habits": "HABITS", "/mind": "MIND", "/cycle": "CYCLE", "/bladder": "CONTINENCE", "/meds": "MEDICATIONS",
  "/symptoms": "SYMPTOMS", "/hormones": "HORMONES", "/appointments": "APPOINTMENTS", "/neuro": "NEURO", "/transition": "TRANSITION", "/photos": "PHOTOS",
};

/** Pages under another page: their back arrow goes there. */
const PARENTS: Record<string, { to: string; label: string }> = {
  "/bladder/settings": { to: "/bladder", label: "Bladder & bowel" },
  "/cycle/settings": { to: "/cycle", label: "Cycle" },
  "/transition/voice": { to: "/transition", label: "Transition" },
  "/assistant/sent": { to: "/assistant", label: NAMES.assistant },
  "/supplies": { to: "/care", label: NAMES.care },
  "/body/weight": { to: "/body", label: NAMES.body },
  "/devices/compare": { to: "/body", label: NAMES.body },
  "/alerts": { to: "/body", label: NAMES.body },
};

/** Pages that belong to Body, past its own. */
const BODY_PAGES = ["/body", "/alerts", "/devices/compare"];

/** Pages that belong to You. */
const YOU_PAGES = ["/you", "/settings", "/devices", "/digest", "/reports", "/assistant", "/conditions", "/admin", "/records"];

const clean = (pathname: string) => (pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname) || "/";
const under = (path: string, root: string) => path === root || path.startsWith(`${root}/`);

/** The tab a page belongs to, for the tabs and the sidebar to light up. */
export function tabOf(pathname: string): Tab {
  const path = clean(pathname);
  if (path === "/") return "today";
  if (BODY_PAGES.some((p) => under(path, p))) return "body";
  if (under(path, "/care") || path === "/supplies" || Object.keys(CARE_PATHS).some((p) => under(path, p))) return "care";
  return "you";
}

/** The tab roots have no back arrow. */
export const isTabRoot = (pathname: string): boolean => TABS.some((t) => t.to === clean(pathname));

/** Where a page's back arrow goes when there's nothing to go back to: its parent page; null for a tab root or an unknown page. */
export function backFor(pathname: string): { to: string; label: string } | null {
  const path = clean(pathname);
  if (isTabRoot(path)) return null;
  if (PARENTS[path]) return PARENTS[path];
  if (CARE_PATHS[path]) return { to: "/care", label: NAMES.care };
  if (YOU_PAGES.some((p) => under(path, p))) return { to: "/you", label: NAMES.you };
  return null;
}

/**
 * Where an old address leads now, keeping what it asked for: Last 24 hours and Trends are Body at
 * 24 hours and at a month; Sleep and Activity are Body's week, at their nights and workouts; Health
 * is Care; More is You. Null for an address that's still a page.
 */
export function movedTo(pathname: string, search = ""): string | null {
  const path = clean(pathname);
  const params = new URLSearchParams(search);
  switch (path) {
    case "/recent": return bodyPath("day", params.get("device"));
    case "/trends": return bodyPath("month");
    case "/sleep": return bodyPath("week", null, "nights");
    case "/activity": return bodyPath("week", null, "workouts");
    case "/health": return "/care";
    case "/more": return "/you";
    default: return null;
  }
}

/** The old addresses [movedTo] knows. */
export const MOVED = ["/recent", "/trends", "/sleep", "/activity", "/health", "/more"] as const;

// ---- Counts ----

/** How many health alerts haven't been seen yet (and aren't put away): the badges' number. */
export function unseenCount(alerts: Pick<HealthAlert, "seenAt" | "dismissedAt">[]): number {
  return alerts.filter((a) => a.seenAt == null && a.dismissedAt == null).length;
}

/** A badge's number: "9+" past nine; nothing for none. */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count <= 0) return null;
  return count > 9 ? "9+" : String(count);
}

/** Devices whose readings Body's 24 hours and Compare devices don't use (blood pressure and glucose have pages of their own). */
export const NO_READINGS: DeviceKind[] = ["MANUAL", "BP_MONITOR", "GLUCOSE_METER"];

/** Whether Compare devices is offered: two or more devices with readings to set side by side. */
export function compareOffered(devices: { kind: DeviceKind }[] | null | undefined): boolean {
  return (devices ?? []).filter((d) => !NO_READINGS.includes(d.kind)).length >= 2;
}

// ---- Settings ----

/**
 * Settings' pages, each opened from You on its own, in You's order. Their ids are the address's #
 * (/settings#reminders) and are kept from before: "account" now lands on Look and account, where
 * its section is. Where your data goes is a page of its own, You's highlighted row.
 */
export const SETTINGS_PAGES = [
  { id: "features", label: NAMES.tracking, sub: "Your conditions, goals and features" },
  { id: "reminders", label: "Reminders, alerts and sharing", sub: "What tells you, and who can see" },
  { id: "you", label: "Profile and goals", sub: "Height, units, calorie formula, targets" },
  { id: "connections", label: "Connections", sub: "Mealie, the assistant, Home Assistant, the watch" },
  { id: "appearance", label: "Look and account", sub: "Theme, bold text, signing out" },
  { id: "privacy", label: NAMES.privacy, sub: "Every way something can leave your server" },
] as const;
export type SettingsPage = (typeof SETTINGS_PAGES)[number]["id"];
