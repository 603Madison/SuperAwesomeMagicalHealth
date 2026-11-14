import { api } from "./api";

/*
 * Reminders in this browser (Web Push): they arrive even when the tab is closed. The browser has
 * one subscription, for one account at most: its id with the server is kept under that account
 * (samh.push.id.<user id>) and checked against the server's list. Signing out stops it, and
 * someone else signing in here stops one that was left on, so nobody gets another's reminders.
 */

const LEGACY = "samh.push.id";
const PREFIX = `${LEGACY}.`;
const keyFor = (userId: string) => `${PREFIX}${userId}`;

function read(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}

function write(key: string, value: string | null) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch { /* still works; this browser just won't remember it */ }
}

/** Every stored subscription id's key, whoever's (and the one from before they were per account). */
function storedKeys(): string[] {
  const keys: string[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k === LEGACY || k?.startsWith(PREFIX)) keys.push(k);
    }
  } catch { /* nothing kept */ }
  return keys;
}

export function pushSupported(): boolean {
  return typeof window !== "undefined" && "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
}

export function pushPermission(): NotificationPermission | "unsupported" {
  return pushSupported() ? Notification.permission : "unsupported";
}

function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = (value + "=".repeat((4 - (value.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function browserName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const system = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "";
  return system ? `${browser} on ${system}` : browser;
}

async function registration(): Promise<ServiceWorkerRegistration> {
  return navigator.serviceWorker.register("/sw.js", { scope: "/" });
}

async function browserSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null;
  try {
    const reg = await navigator.serviceWorker.getRegistration("/");
    return (await reg?.pushManager.getSubscription()) ?? null;
  } catch {
    return null;
  }
}

/** Lets go of this browser's subscription with the push service, and closes reminders still showing. */
async function dropBrowserSubscription(): Promise<void> {
  if (!pushSupported()) return;
  try {
    const reg = await navigator.serviceWorker.getRegistration("/");
    await (await reg?.pushManager.getSubscription())?.unsubscribe();
    (await reg?.getNotifications())?.forEach((n) => n.close());
  } catch {
    // The push service lets it lapse once the server's copy is gone.
  }
}

/**
 * This browser's subscription id for [userId], checked with the server: null when the server
 * doesn't have it for them (removed from another device, or someone else's). Throws when the
 * server can't say.
 */
async function confirmedId(userId: string): Promise<string | null> {
  const id = read(keyFor(userId)) ?? read(LEGACY);
  if (!id) return null;
  const theirs = (await api.pushSubscriptions()).some((s) => s.id === id);
  write(LEGACY, null);
  write(keyFor(userId), theirs ? id : null);
  return theirs ? id : null;
}

/** Whether this browser is signed up for [userId]'s reminders, as the server sees it. */
export async function pushEnabledHere(userId: string): Promise<boolean> {
  if (!pushSupported() || Notification.permission !== "granted") return false;
  if (!(await browserSubscription())) return false;
  try {
    return (await confirmedId(userId)) != null;
  } catch {
    return read(keyFor(userId)) != null;
  }
}

/** Asks for permission, signs this browser up with the push service and tells the server. */
export async function enablePush(userId: string): Promise<void> {
  if (!pushSupported()) throw new Error("This browser can't show reminders when the page is closed.");
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error("Notifications are blocked for this site. Allow them in the browser's site settings, then try again.");
  const { publicKey } = await api.pushKey();
  const reg = await registration();
  await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  // One that isn't this person's (someone else's, or from before) starts afresh, so only their reminders come here.
  if (sub && !read(keyFor(userId))) {
    await sub.unsubscribe().catch(() => false);
    sub = null;
  }
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) });
  const json = sub.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) throw new Error("The browser's push service didn't answer properly.");
  const info = await api.addPushSubscription(json.endpoint, json.keys.p256dh, json.keys.auth, browserName());
  storedKeys().forEach((k) => write(k, null));
  write(keyFor(userId), info.id);
}

/** Stops reminders in this browser: from Settings, and before signing out (while the server still knows who's asking). */
export async function disablePush(userId: string): Promise<void> {
  const id = read(keyFor(userId)) ?? read(LEGACY);
  write(keyFor(userId), null);
  write(LEGACY, null);
  await dropBrowserSubscription();
  if (id) await api.deletePushSubscription(id).catch(() => undefined);
}

/**
 * On signing in: a subscription this browser still has for someone else (they didn't sign out
 * here) stops, so their reminders don't come to this person.
 */
export async function claimPush(userId: string): Promise<void> {
  const others = storedKeys().filter((k) => k !== keyFor(userId) && k !== LEGACY);
  const unknown = read(LEGACY) != null;
  if (others.length === 0 && !unknown) return;
  others.forEach((k) => write(k, null));
  if (!read(keyFor(userId)) && !unknown) {
    await dropBrowserSubscription();
    return;
  }
  try {
    if ((await confirmedId(userId)) == null) await dropBrowserSubscription();
  } catch {
    // The server can't say just now; the next sign-in looks again.
  }
}

export function thisBrowserPushId(userId: string): string | null {
  return read(keyFor(userId));
}
