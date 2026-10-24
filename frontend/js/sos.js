/**
 * SOS and alert history.
 *
 * HONESTY RULES (the UI never says more than the server reported):
 *  - "Alert created" (the record exists) and "Notifications" (what happened to the messages) are shown separately.
 *  - SUBMITTED means a provider accepted a message, not that anyone received it; only the server's DELIVERED, which comes
 *    from provider evidence, is shown as delivered. TEST mode shows "test only" and is labelled everywhere.
 *  - Cancelling records a request; it does not recall messages that were already sent.
 */

import { api, ApiError } from "./api.js";
import { emergencyNumber } from "./config.js";
import { contactRow } from "./emergency-contacts.js";
import { capturePosition, describeGeoError } from "./location.js";
import { loadSosInfo } from "./sos-info.js";
import { element, formatDateTime, hideAlert, setBusy, showAlert, telHref } from "./ui.js";

const POLL_MS = 3000;
const MAX_POLLS = 40; // about two minutes
const MAX_POLL_FAILURES = 3;
const SOS_GEO_TIMEOUT_MS = 8000;

const STATUS_LABELS = { ACTIVE: "New", ACKNOWLEDGED: "Seen by the safety team", RESOLVED: "Resolved", CANCELLED: "Cancelled by you" };
const STATUS_KINDS = { ACTIVE: "warn", ACKNOWLEDGED: "info", RESOLVED: "ok", CANCELLED: "off" };

export const NOTIFICATION_LABELS = {
  QUEUED: ["Processing", "warn"],
  PROCESSING: ["Processing", "warn"],
  NO_RECIPIENTS: ["Nobody was messaged", "off"],
  SIMULATED: ["Test only — nothing sent", "info"],
  SUBMITTED: ["Accepted by SMS provider", "info"],
  DELIVERED: ["Delivered", "ok"],
  PARTIAL: ["Partially delivered", "warn"],
  FAILED: ["Delivery failed", "bad"],
  UNAVAILABLE: ["Delivery status unavailable", "off"],
};

const LOCATION_TEXT = {
  NOT_REQUESTED: "Not shared",
  PERMISSION_DENIED: "Not available — location permission was denied",
  UNAVAILABLE: "Not available — the device couldn't find its position",
  TIMEOUT: "Not available — finding the position took too long",
};

const isProcessing = (alert) => ["QUEUED", "PROCESSING"].includes(alert?.notification?.state);

/** The Geolocation error code tells us why there is no location; the server records it as the alert's locationStatus. */
function locationStatusFor(error) {
  return { 1: "PERMISSION_DENIED", 2: "UNAVAILABLE", 3: "TIMEOUT" }[error?.code] ?? "UNAVAILABLE";
}

/** Who an SOS would message, in words. Shared by the real SOS dialog and the practice drill. */
export function recipientSummary(contacts, testMode) {
  const eligible = contacts.filter((c) => c.eligible);
  const simulated = testMode ? " (simulated in test mode)" : "";
  if (eligible.length > 0) {
    return `${eligible.length} verified contact(s) would be messaged${simulated}: ${eligible.map((c) => c.name).join(", ")}. The alert also appears in the SafeHer admin console, which may not be watched at all times.`;
  }
  if (contacts.length > 0) {
    return `None of your ${contacts.length} contact(s) is verified and has agreed to messages yet, so nobody would be messaged. Verify a contact under Emergency contacts, or call someone yourself now.`;
  }
  return "You have no emergency contacts, so nobody would be messaged. Add and verify a contact, or call someone yourself now.";
}

const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`);

/**
 * @param {{ getToken: () => Promise<string>, getContacts: () => object[], onUnauthorized: (e: Error) => void }} options
 */
export function initSos({ getToken, getContacts, onUnauthorized }) {
  const $ = (id) => document.getElementById(id);
  const dialog = $("sos-dialog");
  const trigger = $("sos-btn");
  const alertBox = $("sos-alert");
  const confirmStep = $("sos-step-confirm");
  const doneStep = $("sos-step-done");
  const createButton = $("sos-create");
  const cancelButton = $("sos-cancel-alert");
  const includeLocation = $("sos-include-location");

  let creating = false;
  let cancelling = false;
  let current = null; // the alert shown in the "done" step
  let alerts = [];
  let idempotencyKey = newKey(); // one per intended alert: a retry reuses it, so it can never create a second alert
  let info = { mode: "UNKNOWN", testMode: false, note: "" };
  const polling = new Map(); // alertId -> { polls, failures, timer }

  $("sos-number").textContent = emergencyNumber;
  $("sos-call-emergency").href = telHref(emergencyNumber);

  loadSosInfo(getToken).then((loaded) => {
    info = loaded;
    $("sos-testmode-card").hidden = !info.testMode;
    $("sos-testmode").hidden = !info.testMode;
  });

  /* ---------- history list ---------- */

  const historyAlert = $("alerts-alert");
  const list = $("alert-list");

  function notificationPill(alert) {
    const [label, kind] = NOTIFICATION_LABELS[alert.notification?.state] ?? NOTIFICATION_LABELS.UNAVAILABLE;
    return element("span", `pill pill--${kind}`, label);
  }

  function renderHistory() {
    $("alerts-loading").hidden = true;
    list.replaceChildren(...alerts.map(historyRow));
    list.hidden = alerts.length === 0;
    $("alerts-empty").hidden = alerts.length !== 0;
  }

  function historyRow(alert) {
    const item = element("li", "record");
    const head = element("div", "record__head");
    head.append(element("span", `pill pill--${STATUS_KINDS[alert.status] ?? "off"}`, STATUS_LABELS[alert.status] ?? alert.status.toLowerCase()));
    head.append(element("time", "record__time", formatDateTime(alert.createdAt)));
    item.append(head);
    const meta = element("p", "record__meta");
    meta.append(`${alert.location ? "Location shared" : "No location"} · Messages: `, notificationPill(alert));
    if (alert.testMode) meta.append(" ", element("span", "pill pill--test", "TEST"));
    item.append(meta);
    if (alert.status === "ACTIVE") {
      const cancel = element("button", "btn btn--outline btn--small");
      cancel.type = "button";
      cancel.textContent = "Cancel alert";
      cancel.addEventListener("click", () => cancelAlert(alert, cancel));
      item.append(cancel);
    }
    return item;
  }

  function upsert(alert) {
    alerts = [alert, ...alerts.filter((a) => a.id !== alert.id)].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    renderHistory();
  }

  async function loadHistory() {
    try {
      alerts = await api.listAlerts(await getToken());
      renderHistory();
      for (const alert of alerts.filter(isProcessing)) startPolling(alert.id);
    } catch (error) {
      $("alerts-loading").hidden = true;
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) return onUnauthorized(error);
      showAlert(historyAlert, "error", error instanceof ApiError ? error.message : "We couldn't load your alerts.");
    }
  }

  async function cancelAlert(alert, button) {
    if (cancelling) return;
    cancelling = true;
    if (button) button.disabled = true;
    try {
      const updated = await api.cancelAlert(await getToken(), alert.id);
      upsert(updated);
      hideAlert(historyAlert);
      if (current?.id === alert.id) showDone(updated);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return onUnauthorized(error);
      const message = error instanceof ApiError ? error.message : "We couldn't cancel that alert. Please try again.";
      showAlert(dialog.open ? alertBox : historyAlert, "error", message);
      if (button) button.disabled = false;
    } finally {
      cancelling = false;
    }
  }

  /* ---------- live notification status ---------- */

  function startPolling(alertId) {
    if (polling.has(alertId)) return;
    polling.set(alertId, { polls: 0, failures: 0, timer: null });
    schedule(alertId);
  }

  function schedule(alertId) {
    const state = polling.get(alertId);
    if (!state) return;
    state.timer = setTimeout(() => poll(alertId), POLL_MS);
  }

  async function poll(alertId) {
    const state = polling.get(alertId);
    if (!state) return;
    try {
      const fresh = await api.getAlert(await getToken(), alertId);
      state.failures = 0;
      upsert(fresh);
      if (current?.id === alertId) showDone(fresh);
      if (isProcessing(fresh) && ++state.polls < MAX_POLLS) return schedule(alertId);
      polling.delete(alertId);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        polling.delete(alertId);
        return onUnauthorized(error);
      }
      if (++state.failures < MAX_POLL_FAILURES) return schedule(alertId);
      polling.delete(alertId);
      // The alert exists; we simply can't read its notification progress right now. Say exactly that.
      if (current?.id === alertId) {
        $("sos-notif-refresh").hidden = false;
        $("sos-notif-refresh").textContent = "We couldn't refresh the notification status. Your alert is saved. Delivery status unavailable.";
      }
    }
  }

  /* ---------- dialog ---------- */

  function describeRecipients() {
    $("sos-recipients-text").textContent = recipientSummary(getContacts(), info.testMode);
  }

  async function showConfirm() {
    current = null;
    confirmStep.hidden = false;
    doneStep.hidden = true;
    hideAlert(alertBox);
    describeRecipients();
    // Only pre-tick location sharing when the browser has ALREADY been granted it: no surprise permission prompt.
    includeLocation.checked = false;
    try {
      const status = await navigator.permissions?.query({ name: "geolocation" });
      if (status?.state === "granted") includeLocation.checked = true;
    } catch {
      // permissions API unavailable: leave it unticked
    }
  }

  function showDone(alert, locationNote) {
    current = alert;
    confirmStep.hidden = true;
    doneStep.hidden = false;
    const cancelled = alert.status === "CANCELLED";
    $("sos-headline").replaceChildren(element("strong", null, cancelled ? "Cancellation recorded." : "Alert created."));
    $("sos-status").textContent = STATUS_LABELS[alert.status] ?? alert.status.toLowerCase();
    $("sos-time").textContent = formatDateTime(alert.createdAt);
    $("sos-location").textContent = alert.location
      ? `Shared (${alert.location.latitude.toFixed(4)}, ${alert.location.longitude.toFixed(4)}, ±${alert.location.accuracyMeters ?? "?"} m)`
      : (LOCATION_TEXT[alert.locationStatus] ?? "Not shared");
    const [label, kind] = NOTIFICATION_LABELS[alert.notification?.state] ?? NOTIFICATION_LABELS.UNAVAILABLE;
    const pill = $("sos-notif-pill");
    pill.className = `pill pill--${kind}`;
    pill.textContent = label;
    $("sos-notif-text").textContent = alert.notification?.message ?? "";
    $("sos-testmode").hidden = !alert.testMode;
    $("sos-notif-refresh").hidden = true;
    if (locationNote) {
      $("sos-notif-refresh").hidden = false;
      $("sos-notif-refresh").textContent = locationNote;
    }
    $("sos-cancel-note").hidden = !alert.cancellationNote;
    $("sos-cancel-note").textContent = alert.cancellationNote ?? "";
    cancelButton.hidden = alert.status !== "ACTIVE";

    const contacts = getContacts();
    $("sos-contacts").replaceChildren(...contacts.map((contact) => contactRow(contact)));
    $("sos-no-contacts").hidden = contacts.length > 0;
  }

  trigger.addEventListener("click", () => {
    showConfirm();
    dialog.showModal();
  });

  $("sos-close").addEventListener("click", () => dialog.close());
  $("sos-done-close").addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener("close", () => {
    current = null;
    trigger.focus();
  });
  cancelButton.addEventListener("click", () => current && cancelAlert(current, cancelButton));

  createButton.addEventListener("click", async () => {
    if (creating) return; // double click / Enter key repeat
    creating = true;
    hideAlert(alertBox);
    setBusy(createButton, true, includeLocation.checked ? "Finding location…" : "Creating…");
    try {
      let location = null;
      let locationStatus = "NOT_REQUESTED";
      let locationNote = null;
      if (includeLocation.checked) {
        try {
          location = await capturePosition({ timeout: SOS_GEO_TIMEOUT_MS });
          locationStatus = "AVAILABLE";
        } catch (geoError) {
          // In an emergency the alert matters more than the location: create it anyway and say why there is none.
          locationStatus = locationStatusFor(geoError);
          locationNote = `${describeGeoError(geoError)} The alert was created without a location.`;
        }
        setBusy(createButton, true, "Creating…");
      }
      const alert = await api.createAlert(await getToken(), { location, locationStatus, idempotencyKey });
      idempotencyKey = newKey(); // this alert exists now: the next press must be a NEW alert
      upsert(alert);
      showDone(alert, locationNote);
      if (isProcessing(alert)) startPolling(alert.id);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) return onUnauthorized(error);
      const message = error instanceof ApiError ? error.message : "We couldn't create the alert. Please try again.";
      showAlert(alertBox, "error", error instanceof ApiError && error.retryable
        ? `${message} The alert may not have been saved. Pressing the button again is safe: it will not create a duplicate. If you are in danger, call ${emergencyNumber} now.`
        : message);
    } finally {
      creating = false;
      setBusy(createButton, false);
    }
  });

  loadHistory();
}
