import { api } from "./api";

/** Reminders in this browser (Web Push): they arrive even when the tab is closed. */

const KEY = "samh.push.id";

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

/** Whether this browser is signed up for reminders (as far as this browser knows). */
export async function pushEnabledHere(): Promise<boolean> {
  if (!pushSupported() || Notification.permission !== "granted") return false;
  try {
    const reg = await navigator.serviceWorker.getRegistration("/");
    const sub = await reg?.pushManager.getSubscription();
    return !!sub && !!localStorage.getItem(KEY);
  } catch {
    return false;
  }
}

/** Asks for permission, signs this browser up with the push service and tells the server. */
export async function enablePush(): Promise<void> {
  if (!pushSupported()) throw new Error("This browser can't show reminders when the page is closed.");
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error("Notifications are blocked for this site. Allow them in the browser's site settings, then try again.");
  const { publicKey } = await api.pushKey();
  const reg = await registration();
  await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) });
  const json = sub.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) throw new Error("The browser's push service didn't answer properly.");
  const info = await api.addPushSubscription(json.endpoint, json.keys.p256dh, json.keys.auth, browserName());
  try { localStorage.setItem(KEY, info.id); } catch { /* still works; this browser just won't remember it */ }
}

/** Stops reminders in this browser. */
export async function disablePush(): Promise<void> {
  let id: string | null = null;
  try { id = localStorage.getItem(KEY); localStorage.removeItem(KEY); } catch { /* nothing kept */ }
  try {
    const reg = await navigator.serviceWorker.getRegistration("/");
    await (await reg?.pushManager.getSubscription())?.unsubscribe();
  } catch {
    // The server's copy goes below either way.
  }
  if (id) await api.deletePushSubscription(id).catch(() => undefined);
}

export function thisBrowserPushId(): string | null {
  try { return localStorage.getItem(KEY); } catch { return null; }
}
