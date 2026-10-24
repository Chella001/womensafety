/*
 * Service worker for administrator push notifications.
 *
 * It shows what the backend sent (a generic title and body plus the alert id) and opens the console on click. It does
 * not load the Firebase SDK and holds no secrets. The payload never contains a location, name or phone number.
 */

self.addEventListener("push", (event) => {
  let title = "SafeHer: new SOS alert";
  let body = "Open the admin console to review it.";
  let alertId = "";
  try {
    const payload = event.data ? event.data.json() : {};
    title = payload.notification?.title ?? title;
    body = payload.notification?.body ?? body;
    alertId = payload.data?.alertId ?? "";
  } catch {
    // keep the generic text
  }
  event.waitUntil(self.registration.showNotification(title, { body, tag: alertId || "sos", data: { alertId }, requireInteraction: true }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL("admin/index.html#alerts", self.registration.scope).href;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      for (const client of windows) {
        if (client.url.startsWith(new URL("admin/", self.registration.scope).href) && "focus" in client) {
          client.navigate(target);
          return client.focus();
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
