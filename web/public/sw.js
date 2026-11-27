/*
 * SuperAwesomeMagicalHealth's service worker: shows reminders the server pushes (a PushMessage),
 * even when no tab is open, and answers their buttons. Taken and Skip mark the dose straight from
 * the notification; Snooze brings it back in 10 minutes (and the server reminds again too, as
 * set in Settings, in case the browser stops this worker before then).
 */

const SNOOZE_MS = 10 * 60 * 1000;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

/*
 * The guided meditations (GET /api/meditations: the same for everyone, nothing of anyone's), kept
 * for offline: from the server when it answers, else the copy from last time, so one can be begun
 * without a connection. Nothing else is cached or touched here.
 */
const MEDITATIONS = "samh-meditations-v1";

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname !== "/api/meditations") return;
  event.respondWith((async () => {
    const cache = await caches.open(MEDITATIONS);
    try {
      const response = await fetch(request);
      if (response.ok) await cache.put("/api/meditations", response.clone());
      return response;
    } catch (e) {
      const kept = await cache.match("/api/meditations");
      if (kept) return kept;
      throw e;
    }
  })());
});

/** A health alert that could need care now (its tag is "alert:<id>:URGENT"): it stays until it's seen to. */
function urgentAlert(message) {
  return typeof message.tag === "string" && message.tag.startsWith("alert:") && message.tag.endsWith(":URGENT");
}

function show(message) {
  const actions = (message.actions || [])
    .filter((a) => a.action !== "open")
    .slice(0, 2)
    .map((a) => ({ action: a.action, title: a.title }));
  // A health alert opens /alerts when tapped, like any other notification its url.
  return self.registration.showNotification(message.title || "SuperAwesomeMagicalHealth", {
    body: message.body || "",
    tag: message.tag || undefined,
    renotify: !!message.tag,
    requireInteraction: urgentAlert(message),
    icon: "/icon.svg",
    badge: "/icon.svg",
    data: message,
    actions,
  });
}

self.addEventListener("push", (event) => {
  let message;
  try {
    message = event.data ? event.data.json() : {};
  } catch {
    message = { title: "SuperAwesomeMagicalHealth", body: event.data ? event.data.text() : "" };
  }
  event.waitUntil(show(message));
});

/**
 * Whether the account signed in here is the one a reminder is for ([userId], from servers that
 * say). Someone else may have signed in since it came: then it's marked in the app instead.
 */
async function signedInAs(userId) {
  if (!userId) return true;
  try {
    const response = await fetch("/api/me", { credentials: "same-origin" });
    if (!response.ok) return false;
    const me = await response.json();
    return !!me && !!me.user && me.user.id === userId;
  } catch {
    return false;
  }
}

async function markDose(message, status) {
  if (!(await signedInAs(message.userId))) throw new Error("signed in as someone else");
  const response = await fetch("/api/meds/doses", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-SAMH": "1" },
    body: JSON.stringify({ medicationId: message.medicationId, at: Date.now(), status, scheduledFor: message.scheduledFor }),
  });
  if (!response.ok) throw new Error(String(response.status));
}

async function openPage(url) {
  const target = new URL(url || "/", self.location.origin).href;
  const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const client of windows) {
    if (new URL(client.url).origin === self.location.origin) {
      await client.focus();
      if ("navigate" in client) return client.navigate(target);
      return undefined;
    }
  }
  return self.clients.openWindow(target);
}

async function snooze(message) {
  // A notification trigger where the browser has one; otherwise wait here (the browser may stop
  // a waiting worker, in which case the server's "remind again" still comes).
  if (typeof TimestampTrigger !== "undefined") {
    return self.registration.showNotification(message.title, {
      body: message.body, tag: message.tag, icon: "/icon.svg", data: message,
      // eslint-disable-next-line no-undef
      showTrigger: new TimestampTrigger(Date.now() + SNOOZE_MS),
    });
  }
  await new Promise((resolve) => setTimeout(resolve, SNOOZE_MS));
  return show(message);
}

self.addEventListener("notificationclick", (event) => {
  const message = event.notification.data || {};
  event.notification.close();
  if ((event.action === "taken" || event.action === "skip") && message.medicationId) {
    event.waitUntil(
      markDose(message, event.action === "taken" ? "TAKEN" : "SKIPPED").catch(() =>
        self.registration.showNotification("Couldn't mark that dose", {
          body: "Open the app to mark it.", icon: "/icon.svg", tag: message.tag, data: { url: message.url || "/meds" },
        }),
      ),
    );
    return;
  }
  if (event.action === "snooze") {
    event.waitUntil(snooze(message));
    return;
  }
  event.waitUntil(openPage(message.url));
});
