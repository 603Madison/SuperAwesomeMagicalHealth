import type { DeviceKind, HealthAlert, Module } from "./types";

/*
 * How the app is laid out (npm test covers it): the five tabs a phone shows (as the Android app has
 * them), which tab each page belongs to, where a page's back arrow goes, each place's one name, and
 * the unseen health alerts' count for the badges. Type imports only, so node can run it as it is.
 *
 *   Today · 24 hours · Health · Trends · More
 *
 * Health holds every feature that's on; More holds your records (Health alerts, Your week, Reports &
 * export), Devices, the assistant and the Settings pages. On a wide screen the sidebar has the same
 * groups.
 */

/** Each place's one name, wherever it's shown (titles, rows, links). */
export const NAMES = {
  today: "Today",
  recent: "Last 24 hours",
  /** The tab's own label for Last 24 hours. */
  recentTab: "24 hours",
  health: "Health",
  trends: "Trends",
  more: "More",
  alerts: "Health alerts",
  digest: "Your week",
  digestSettings: "Weekly digest",
  reports: "Reports & export",
  tracking: "What you're tracking",
  chooseTracking: "Choose what you track",
  devices: "Devices",
  compare: "Compare devices",
  assistant: "Assistant",
  settings: "Settings",
  privacy: "Where your data goes",
} as const;

export type Tab = "today" | "recent" | "health" | "trends" | "more";

/** The phone's tabs, left to right: always these five, whatever's turned on. */
export const TABS: { id: Tab; to: string; label: string }[] = [
  { id: "today", to: "/", label: NAMES.today },
  { id: "recent", to: "/recent", label: NAMES.recentTab },
  { id: "health", to: "/health", label: NAMES.health },
  { id: "trends", to: "/trends", label: NAMES.trends },
  { id: "more", to: "/more", label: NAMES.more },
];

/** Each feature's page (FEATURES' routes), and the pages that belong to them. */
export const FEATURE_PATHS: Record<string, Module> = {
  "/activity": "ACTIVITY", "/sleep": "SLEEP", "/food": "NUTRITION", "/body": "BODY", "/vitals": "VITALS", "/habits": "HABITS", "/mind": "MIND",
  "/cycle": "CYCLE", "/bladder": "CONTINENCE", "/meds": "MEDICATIONS", "/symptoms": "SYMPTOMS", "/hormones": "HORMONES",
  "/appointments": "APPOINTMENTS", "/neuro": "NEURO", "/transition": "TRANSITION", "/photos": "PHOTOS",
};

/** Pages under another page: their back arrow goes there. */
const PARENTS: Record<string, { to: string; label: string }> = {
  "/bladder/settings": { to: "/bladder", label: "Bladder & bowel" },
  "/cycle/settings": { to: "/cycle", label: "Cycle" },
  "/transition/voice": { to: "/transition", label: "Transition" },
  "/devices/compare": { to: "/devices", label: NAMES.devices },
  "/assistant/sent": { to: "/assistant", label: NAMES.assistant },
  "/supplies": { to: "/health", label: NAMES.health },
};

/** Pages that belong to More. */
const MORE_PATHS = ["/more", "/alerts", "/digest", "/reports", "/devices", "/settings", "/conditions", "/assistant", "/admin"];

const clean = (pathname: string) => (pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname) || "/";
const under = (path: string, root: string) => path === root || path.startsWith(`${root}/`);

/** The tab a page belongs to, for the tab bar to light up: features under Health, records, devices and settings under More. */
export function tabOf(pathname: string): Tab {
  const path = clean(pathname);
  if (path === "/") return "today";
  if (under(path, "/recent")) return "recent";
  if (under(path, "/trends")) return "trends";
  if (under(path, "/health") || path === "/supplies" || Object.keys(FEATURE_PATHS).some((p) => under(path, p))) return "health";
  return "more";
}

/** The five tab roots have no back arrow. */
export const isTabRoot = (pathname: string): boolean => TABS.some((t) => t.to === clean(pathname));

/** Where a page's back arrow goes when there's nothing to go back to: its parent page; null for a tab root or an unknown page. */
export function backFor(pathname: string): { to: string; label: string } | null {
  const path = clean(pathname);
  if (isTabRoot(path)) return null;
  if (PARENTS[path]) return PARENTS[path];
  if (FEATURE_PATHS[path]) return { to: "/health", label: NAMES.health };
  if (MORE_PATHS.some((p) => under(path, p))) return { to: "/more", label: NAMES.more };
  return null;
}

/** How many health alerts haven't been seen yet (and aren't put away): the badges' number. */
export function unseenCount(alerts: Pick<HealthAlert, "seenAt" | "dismissedAt">[]): number {
  return alerts.filter((a) => a.seenAt == null && a.dismissedAt == null).length;
}

/** A badge's number: "9+" past nine; nothing for none. */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count <= 0) return null;
  return count > 9 ? "9+" : String(count);
}

/** Devices whose readings Last 24 hours and Compare devices don't use (blood pressure and glucose have pages of their own). */
export const NO_READINGS: DeviceKind[] = ["MANUAL", "BP_MONITOR", "GLUCOSE_METER"];

/** Whether Compare devices is offered: two or more devices with readings to set side by side. */
export function compareOffered(devices: { kind: DeviceKind }[] | null | undefined): boolean {
  return (devices ?? []).filter((d) => !NO_READINGS.includes(d.kind)).length >= 2;
}

/** Settings' pages (its tabs on a wide screen), in order: their ids are the address's #, kept from before. */
export const SETTINGS_PAGES = [
  { id: "you", label: "You & goals", sub: "Your profile, goals and nutrient targets" },
  { id: "features", label: NAMES.tracking, sub: "Conditions, features and what each form asks" },
  { id: "reminders", label: "Reminders & sharing", sub: "Reminders, the weekly digest and who sees what" },
  { id: "connections", label: "Connections & privacy", sub: "Where your data goes, Mealie, the assistant, links and webhooks" },
  { id: "appearance", label: "Appearance", sub: "Theme, bold text and arranged screens" },
  { id: "account", label: "Account", sub: "Your name, password and where you're signed in" },
] as const;
export type SettingsPage = (typeof SETTINGS_PAGES)[number]["id"];
