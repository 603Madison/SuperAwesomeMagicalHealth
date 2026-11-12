/*
 * SuperAwesomeMagicalHealth's service worker: shows reminders the server pushes (a PushMessage),
 * even when no tab is open, and answers their buttons. Taken and Skip mark the dose straight from
 * the notification; Snooze brings it back in 10 minutes (and the server reminds again too, as
 * set in Settings, in case the browser stops this worker before then).
 */

const SNOOZE_MS = 10 * 60 * 1000;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

function show(message) {
  const actions = (message.actions || [])
    .filter((a) => a.action !== "open")
    .slice(0, 2)
    .map((a) => ({ action: a.action, title: a.title }));
  return self.registration.showNotification(message.title || "SuperAwesomeMagicalHealth", {
    body: message.body || "",
    tag: message.tag || undefined,
    renotify: !!message.tag,
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

async function markDose(message, status) {
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
