import { create } from "zustand";
import { api } from "./api";
import { useSession } from "./session";
import type { LayoutPrefs, ScreenLayout } from "./types";

/*
 * How the person has arranged each screen (docs/API.md, "Arranging screens"): loaded once after
 * sign-in and kept for every page. Changes show at once and are saved in the order they're made;
 * if the server says no, the screen goes back to what it last saved.
 *
 * Always the person's own, also while they look at someone's share: /api/layout never carries
 * X-SAMH-View (it isn't one of the API's shared paths).
 */

interface LayoutStore {
  /** Every arranged screen, by key; empty until loaded. */
  screens: Record<string, ScreenLayout>;
  loaded: boolean;
}

export const useLayoutStore = create<LayoutStore>(() => ({ screens: {}, loaded: false }));

/** What the server last confirmed, to go back to when a save fails. */
let confirmed: Record<string, ScreenLayout> = {};
let saving: Promise<void> = Promise.resolve();
let inFlight = 0;
let loading: Promise<void> | null = null;

const isEmpty = (l?: ScreenLayout) => !l || (l.order.length === 0 && l.hidden.length === 0);

function withScreen(screens: Record<string, ScreenLayout>, key: string, layout?: ScreenLayout): Record<string, ScreenLayout> {
  const next = { ...screens };
  if (isEmpty(layout)) delete next[key];
  else next[key] = layout!;
  return next;
}

/** Loads the person's layout (again, with [force]). An older server without it: every screen as it comes. */
export function loadLayout(force = false): Promise<void> {
  if (loading && !force) return loading;
  loading = api.layout()
    .then((prefs: LayoutPrefs) => {
      confirmed = prefs.screens ?? {};
      // Saves made meanwhile win: they're already on their way.
      if (inFlight === 0) useLayoutStore.setState({ screens: confirmed, loaded: true });
      else useLayoutStore.setState({ loaded: true });
    })
    .catch(() => useLayoutStore.setState({ loaded: true }));
  return loading;
}

/** Saves one screen's arrangement; an empty one puts the screen back as it was. */
export function saveScreen(key: string, layout: ScreenLayout): Promise<void> {
  useLayoutStore.setState((s) => ({ screens: withScreen(s.screens, key, layout) }));
  inFlight++;
  saving = saving.then(async () => {
    try {
      const prefs = isEmpty(layout) ? await api.resetLayout(key) : await api.saveLayout(key, layout);
      confirmed = prefs.screens ?? {};
      inFlight--;
      if (inFlight === 0) useLayoutStore.setState({ screens: confirmed });
    } catch (e) {
      inFlight--;
      useLayoutStore.setState((s) => ({ screens: withScreen(s.screens, key, confirmed[key]) }));
      throw e;
    }
  });
  const mine = saving;
  // The next save waits for this one either way.
  saving = saving.catch(() => undefined);
  return mine;
}

/** Puts one screen back as it was. */
export const resetScreen = (key: string) => saveScreen(key, { order: [], hidden: [] });

/** Puts every screen back as it was. */
export async function resetAllScreens(): Promise<void> {
  const before = useLayoutStore.getState().screens;
  useLayoutStore.setState({ screens: {} });
  try {
    confirmed = (await api.saveAllLayouts({ screens: {} })).screens ?? {};
    useLayoutStore.setState({ screens: confirmed });
  } catch (e) {
    useLayoutStore.setState({ screens: before });
    throw e;
  }
}

/** One screen's saved arrangement, or undefined for the app's default. */
export function useScreenLayout(key: string): ScreenLayout | undefined {
  return useLayoutStore((s) => s.screens[key]);
}

/**
 * The full order to save when a screen's offered sections are now [next]: ids the screen doesn't
 * offer right now (a feature that's off, or one only the other app has) keep their places.
 */
export function mergeOrder(saved: string[], next: string[]): string[] {
  const offered = new Set(next);
  const queue = [...next];
  let used = 0;
  const out = saved.map((id) => (offered.has(id) ? queue[used++] : id)).filter((id): id is string => id !== undefined);
  // Ones the saved order didn't have yet go after the one before them.
  for (; used < queue.length; used++) {
    const before = queue[used - 1];
    const at = before === undefined ? 0 : out.indexOf(before) + 1;
    out.splice(at, 0, queue[used]);
  }
  return [...new Set(out)];
}

// Loaded once after sign-in; signing out forgets it, and signing in as someone else loads theirs.
let lastUser: string | null = null;
function follow(id: string | null) {
  if (id === lastUser) return;
  lastUser = id;
  confirmed = {};
  useLayoutStore.setState({ screens: {}, loaded: false });
  if (id) void loadLayout(true);
}
useSession.subscribe((s) => follow(s.user?.id ?? null));
follow(useSession.getState().user?.id ?? null);
