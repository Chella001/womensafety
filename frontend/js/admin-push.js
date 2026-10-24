/**
 * Push notifications for administrators (Firebase Cloud Messaging, web push).
 *
 * Registering a browser only tells the backend where to send a short "new SOS alert" signal. The push carries no location,
 * phone number or name, and "the push service accepted it" is NOT "a person saw it": acknowledging an alert stays a
 * separate, audited action in this console.
 *
 * Needs a Web Push certificate key (VAPID) from Firebase console → Project settings → Cloud Messaging, set as
 * `vapidKey` in js/config.js. Without it this module explains what is missing instead of pretending to work.
 */

import { api } from "./api.js";
import { firebaseConfig, vapidKey } from "./config.js";
import { getToken } from "./admin-common.js";

const SDK_BASE = "https://www.gstatic.com/firebasejs/10.14.1";

export function pushSupport() {
  if (!vapidKey) return { ok: false, reason: "Push isn't set up yet: add your Web Push key (vapidKey) to js/config.js." };
  if (!("serviceWorker" in navigator) || !("Notification" in window) || !("PushManager" in window)) {
    return { ok: false, reason: "This browser does not support web push notifications." };
  }
  if (!window.isSecureContext) return { ok: false, reason: "Push needs HTTPS (or localhost)." };
  return { ok: true, reason: "" };
}

/** Asks for permission, registers the service worker and the FCM token, and tells the backend. Throws Errors with safe messages. */
export async function registerThisBrowser(label) {
  const support = pushSupport();
  if (!support.ok) throw new Error(support.reason);
  const permission = await Notification.requestPermission();
  if (permission !== "granted") throw new Error("Notifications are blocked for this site. Allow them in your browser settings and try again.");

  const registration = await navigator.serviceWorker.register(new URL("../firebase-messaging-sw.js", import.meta.url), { scope: new URL("../", import.meta.url).pathname });
  let token;
  try {
    const [{ initializeApp, getApps }, { getMessaging, getToken: getFcmToken }] = await Promise.all([
      import(`${SDK_BASE}/firebase-app.js`),
      import(`${SDK_BASE}/firebase-messaging.js`),
    ]);
    const app = getApps()[0] ?? initializeApp(firebaseConfig);
    token = await getFcmToken(getMessaging(app), { vapidKey, serviceWorkerRegistration: registration });
  } catch {
    throw new Error("We couldn't get a push token from Firebase. Check the Web Push key and that Cloud Messaging is enabled.");
  }
  if (!token) throw new Error("Firebase did not return a push token.");
  return api.admin.registerDevice(await getToken(), token, label);
}
