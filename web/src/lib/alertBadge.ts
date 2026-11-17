import { useEffect } from "react";
import { create } from "zustand";
import { api } from "./api";
import { unseenCount } from "./nav";
import { useSession } from "./session";
import { useViewing } from "./viewing";

/*
 * How many health alerts the person hasn't seen yet, for the badges (Today's bell, the phone's
 * More tab, Health alerts in the sidebar). Asked when the app opens, when the tab comes back (at
 * most once a minute) and every few minutes; Health alerts sets it to nothing once it marks them
 * seen. Only ever the person's own: never while viewing someone's share.
 */

const useBadge = create<{ count: number; user: string | null }>(() => ({ count: 0, user: null }));

const AGAIN_MS = 60_000;
const EVERY_MS = 5 * 60_000;
let last = 0;
let asking = 0;

async function ask(userId: string) {
  const mine = ++asking;
  last = Date.now();
  try {
    const alerts = await api.alerts(30);
    if (mine === asking) useBadge.setState({ count: unseenCount(alerts.alerts), user: userId });
  } catch {
    // An older server without alerts, or offline: no badge.
  }
}

/** Asks again now (after the person put one away, say). */
export function refreshAlertBadge() {
  const user = useSession.getState().user;
  if (user && !useViewing.getState().share) void ask(user.id);
}

/** Every alert is seen now (Health alerts has marked them). */
export function clearAlertBadge() {
  asking++;
  useBadge.setState((s) => ({ ...s, count: 0 }));
}

/** Keeps the count up to date: called once, by the app's frame. */
export function useAlertBadgeUpdates() {
  const user = useSession((s) => s.user?.id ?? null);
  const viewing = useViewing((s) => !!s.share);
  useEffect(() => {
    if (!user || viewing) return;
    if (useBadge.getState().user !== user || Date.now() - last > AGAIN_MS) void ask(user);
    const again = () => { if (document.visibilityState === "visible" && Date.now() - last > AGAIN_MS) void ask(user); };
    const timer = window.setInterval(() => void ask(user), EVERY_MS);
    document.addEventListener("visibilitychange", again);
    window.addEventListener("focus", again);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", again);
      window.removeEventListener("focus", again);
    };
  }, [user, viewing]);
}

/** The unseen count for the signed-in person: 0 while viewing a share, or before it's known. */
export function useAlertBadge(): number {
  const user = useSession((s) => s.user?.id ?? null);
  const viewing = useViewing((s) => !!s.share);
  const { count, user: whose } = useBadge();
  return !user || viewing || whose !== user ? 0 : count;
}
