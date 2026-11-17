import type { DataDestination, DataMap, DataPath } from "./types";

/*
 * Where your data goes (Settings, GET /api/privacy), the parts that don't need React (npm test
 * covers them): which ways out can be paused here, what each says about itself, where it's set up,
 * and the change to show at once while a pause is saved. Type imports only, so node can run it as it is.
 */

/** Whether the switch shows: one the person can pause, set up and not turned off on the server. */
export function switchable(d: Pick<DataDestination, "pausable" | "state">): boolean {
  return d.pausable !== false && (d.state === "ON" || d.state === "PAUSED");
}

/** Settings' section (an id on the page) where a way out is set up, by the server's setUp key; null for none. */
export function setUpSection(d: Pick<DataDestination, "path" | "setUp">): string | null {
  switch (d.setUp) {
    case "settings.connections": return d.path === "MEALIE" ? "mealie" : "connections";
    case "settings.ai": return "assistant";
    case "settings.connector": return "connector";
    case "settings.integrations": return "integrations";
    case "settings.notifications": return "reminders";
    case "settings.digest": return "digest";
    case "settings.sharing": return "sharing";
    case "settings.profile": return "profile";
    default: return null;
  }
}

const NOUNS: Partial<Record<DataPath, [string, string]>> = {
  AI_CONNECTORS: ["link", "links"],
  WEBHOOKS: ["webhook", "webhooks"],
  BROWSER_PUSH: ["browser", "browsers"],
  HOME_ASSISTANT: ["key", "keys"],
};

/** How many are set up, in words: "2 links", "1 browser", "Shared with 3 people"; null with none or no count. */
export function countText(d: Pick<DataDestination, "path" | "count">): string | null {
  const n = d.count;
  if (n == null || n <= 0) return null;
  if (d.path === "SHARES") return `Shared with ${n} ${n === 1 ? "person" : "people"}`;
  const noun = NOUNS[d.path];
  return noun ? `${n} ${n === 1 ? noun[0] : noun[1]}` : String(n);
}

/** The word for where it stands, beside the switch or on its own. */
export function stateText(d: Pick<DataDestination, "state" | "pausable">): string {
  if (d.pausable === false && d.state === "ON") return "Always on";
  switch (d.state) {
    case "ON": return "On";
    case "PAUSED": return "Paused";
    case "NOT_SET_UP": return "Not set up";
    case "OFF_ON_SERVER": return "Turned off on this server";
  }
}

/** The map as it will be once [path] is paused (or resumed): shown at once, while the server saves it. */
export function withPaused(map: DataMap, path: DataPath, paused: boolean): DataMap {
  return {
    ...map,
    destinations: map.destinations.map((d) => (d.path === path && switchable(d) ? { ...d, state: paused ? "PAUSED" : "ON" } : d)),
  };
}

/** What the toast says once it's saved. */
export function pausedToast(d: Pick<DataDestination, "name">, paused: boolean): string {
  return paused ? `${d.name}: paused. Nothing goes until you turn it back on.` : `${d.name}: on again`;
}
