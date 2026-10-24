/**
 * SOS alerts. "Acknowledged" and "Resolved" only record what an administrator did in this console; nothing here
 * notifies anyone, and the delivery information shown is exactly what the server recorded (see the pills' wording).
 */

import { api, ApiError } from "./api.js";
import { hideAlert, setBusy, showAlert } from "./ui.js";
import {
  $, actionButton, alertLabel, alertPill, cell, clearReport, deliveryPill, element, facts, fillTable, flagPill,
  formatDateTime, getToken, notificationPill, openDialog, pill, renderPager, report, short, utcOffsetMinutes, wireDialog,
} from "./admin-common.js";

const PAGE_SIZE = 10;
let page = 0;
let current = null; // the alert shown in the detail dialog
let busy = false;
let reloadTimer = null;
let latest = 0; // only the newest request may draw (live reloads and filter changes can overlap)

const LOCATION_LABELS = {
  AVAILABLE: "Shared",
  NOT_REQUESTED: "Not shared",
  PERMISSION_DENIED: "Unavailable (permission denied)",
  UNAVAILABLE: "Unavailable (device)",
  TIMEOUT: "Unavailable (timed out)",
};

function filters() {
  return {
    status: $("alert-status").value,
    q: $("alert-q").value.trim(),
    from: $("alert-from").value,
    to: $("alert-to").value,
    utcOffsetMinutes: utcOffsetMinutes(),
  };
}

async function loadPage(requested = 0, { quiet = false } = {}) {
  const f = filters();
  if (f.from && f.to && f.from > f.to) {
    showAlert($("admin-alert"), "error", "The start date must not be after the end date.");
    return false;
  }
  if (!quiet) {
    clearReport();
    $("alerts-loading").hidden = false;
  }
  const mine = ++latest;
  try {
    const result = await api.admin.alerts(await getToken(), { ...f, page: requested, size: PAGE_SIZE });
    if (mine !== latest) return true;
    page = result.page;
    fillTable("alerts-table", "alerts-empty-admin", result.items.map(row));
    renderPager("alerts-pager", result, loadPage);
    return true;
  } catch (error) {
    report(error, "We couldn't load the SOS alerts.");
    return false;
  } finally {
    $("alerts-loading").hidden = true;
  }
}

function row(alert) {
  const tr = element("tr");
  const notifications = element("div", "cell-stack");
  notifications.append(notificationPill(alert.notification.state));
  if (alert.testMode) notifications.append(pill("TEST", "test"));
  const flags = element("div", "cell-stack");
  for (const flag of alert.flags) flags.append(flagPill(flag));
  tr.append(
    cell(formatDateTime(alert.createdAt)),
    cell(element("code", "", short(alert.id))),
    cell(element("code", "", alert.userRef)),
    cell(alertPill(alert.status)),
    cell(alert.hasLocation ? "Shared (hidden)" : (LOCATION_LABELS[alert.locationStatus] ?? "Not shared")),
    cell(notifications),
    cell(flags.childElementCount ? flags : "—"),
    cell(actionButton("Open", (button) => openAlert(alert.id, button))),
  );
  tr.dataset.alertId = alert.id;
  return tr;
}

async function openAlert(id, trigger) {
  if (trigger) trigger.disabled = true;
  try {
    const alert = await api.admin.alert(await getToken(), id);
    showDetail(alert, trigger);
    loadDeliveries(id);
  } catch (error) {
    report(error, "We couldn't load that alert.");
  } finally {
    if (trigger) trigger.disabled = false;
  }
}

function notificationSection(label, view, test) {
  const wrap = element("div", "cell-stack");
  wrap.append(notificationPill(view.state));
  if (test) wrap.append(pill("TEST", "test"));
  wrap.append(element("span", "", view.message));
  return wrap;
}

function showDetail(alert, trigger) {
  current = alert;
  facts("alert-facts", [
    ["Alert ID", alert.id],
    ["User", alert.uid],
    ["Status", alertLabel(alert.status)],
    ["Created at", formatDateTime(alert.createdAt)],
    ["Cancellation requested", alert.cancellationRequestedAt ? `${formatDateTime(alert.cancellationRequestedAt)} (messages already sent are not recalled)` : "—"],
    ["Acknowledged", alert.acknowledgedAt ? `${formatDateTime(alert.acknowledgedAt)} by ${short(alert.acknowledgedBy)}` : "—"],
    ["Resolved", alert.resolvedAt ? `${formatDateTime(alert.resolvedAt)} by ${short(alert.resolvedBy)}` : "—"],
    ["Resolution note", alert.resolutionNote || "—"],
    ["Location", alert.location
      ? `${alert.location.latitude.toFixed(5)}, ${alert.location.longitude.toFixed(5)} (±${alert.location.accuracyMeters ?? "?"} m, captured ${formatDateTime(alert.location.capturedAt)})`
      : (LOCATION_LABELS[alert.locationStatus] ?? "Not shared")],
    ["Contact messages", notificationSection("contacts", alert.notification, alert.testMode)],
    ["Admin push", notificationSection("admins", alert.adminNotification, false)],
  ]);
  const needs = element("div", "cell-stack");
  for (const flag of alert.flags) needs.append(flagPill(flag));
  $("alert-flags").replaceChildren(...(alert.flags.length ? [element("strong", "", "Needs attention: "), needs] : []));
  $("alert-flags").hidden = alert.flags.length === 0;

  $("alert-events").replaceChildren(
    ...alert.events.map((event) => {
      const item = element("li", "record");
      item.append(element("strong", "", event.type.replaceAll("_", " ").toLowerCase()));
      item.append(element("span", "record__time", ` ${formatDateTime(event.at)} · ${event.actor === "user" || event.actor === "system" || event.actor === "provider" ? event.actor : short(event.actor)}`));
      const detail = Object.entries(event.detail ?? {}).map(([k, v]) => `${k}: ${v}`).join(", ");
      if (detail) item.append(element("span", "record__meta", detail));
      return item;
    }),
  );
  $("alert-events-empty").hidden = alert.events.length !== 0;

  // The map is only loaded on request: it sends the location to openstreetmap.org, so that is the administrator's choice.
  $("alert-map").replaceChildren();
  $("alert-map-wrap").hidden = !alert.location;
  $("alert-map-toggle").textContent = "Show map";
  $("alert-map-toggle").setAttribute("aria-expanded", "false");
  $("alert-changed").hidden = true;

  $("alert-acknowledge").hidden = alert.status !== "ACTIVE";
  $("alert-resolve").hidden = alert.status !== "ACTIVE" && alert.status !== "ACKNOWLEDGED";
  hideAlert($("alert-dialog-alert"));
  if (!$("alert-dialog").open) openDialog($("alert-dialog"), trigger);
}

async function loadDeliveries(id) {
  $("alert-deliveries-loading").hidden = false;
  $("alert-deliveries-empty").hidden = true;
  $("alert-deliveries").closest(".table-wrap").hidden = true;
  try {
    const deliveries = await api.admin.alertDeliveries(await getToken(), id);
    if (current?.id !== id) return;
    $("alert-deliveries").querySelector("tbody").replaceChildren(...deliveries.map(deliveryRow));
    $("alert-deliveries").closest(".table-wrap").hidden = deliveries.length === 0;
    $("alert-deliveries-empty").hidden = deliveries.length !== 0;
  } catch (error) {
    if (current?.id === id) showAlert($("alert-dialog-alert"), "error", error instanceof ApiError ? error.message : "We couldn't load the delivery records.");
  } finally {
    $("alert-deliveries-loading").hidden = true;
  }
}

function deliveryRow(d) {
  const tr = element("tr");
  const status = element("div", "cell-stack");
  status.append(deliveryPill(d.status), element("span", "hint", d.statusLabel));
  const attempts = d.attempts.map((a) => `${formatDateTime(a.at)} ${a.outcome.toLowerCase()}${a.code ? ` (${a.code})` : ""}`).join("; ");
  tr.append(
    cell(d.recipientType === "CONTACT" ? `Contact ${d.recipient}` : `Device: ${d.recipient}`),
    cell(d.channel === "SMS" ? "SMS" : "Push"),
    cell(status),
    cell(String(d.attemptCount)),
    cell(d.failureCode ?? "—"),
    cell(attempts || "—"),
  );
  return tr;
}

function toggleMap() {
  const alert = current;
  if (!alert?.location) return;
  const open = $("alert-map-toggle").getAttribute("aria-expanded") === "true";
  if (open) {
    $("alert-map").replaceChildren();
    $("alert-map-toggle").textContent = "Show map";
    $("alert-map-toggle").setAttribute("aria-expanded", "false");
    return;
  }
  const { latitude: lat, longitude: lon } = alert.location;
  const d = 0.01;
  const frame = element("iframe", "map-frame");
  frame.title = "Map of the location shared with this alert";
  frame.loading = "lazy";
  frame.referrerPolicy = "no-referrer";
  frame.setAttribute("sandbox", "allow-scripts allow-same-origin");
  frame.src = `https://www.openstreetmap.org/export/embed.html?bbox=${lon - d},${lat - d},${lon + d},${lat + d}&layer=mapnik&marker=${lat},${lon}`;
  const link = element("a", "link-btn", "Open in OpenStreetMap");
  link.href = `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=16/${lat}/${lon}`;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  $("alert-map").replaceChildren(frame, link);
  $("alert-map-toggle").textContent = "Hide map";
  $("alert-map-toggle").setAttribute("aria-expanded", "true");
}

async function afterAction(message) {
  $("ack-dialog").close();
  $("resolve-alert-dialog").close();
  $("alert-dialog").close();
  await loadPage(page);
  showAlert($("admin-alert"), "success", message);
}

function actionError(error, dialogAlert, fallback) {
  if (error instanceof ApiError && ![401, 403].includes(error.status)) {
    showAlert($(dialogAlert), "error", error.message);
    return;
  }
  report(error, fallback);
}

/** Called by the shell for every live signal while the alerts view is on screen. */
export function onSignal(signal) {
  if (signal.alertId && current?.id === signal.alertId && $("alert-dialog").open && signal.type !== "created") {
    $("alert-changed").hidden = false; // do not silently re-open (and re-audit) the record; let the admin choose
  }
  clearTimeout(reloadTimer);
  reloadTimer = setTimeout(() => loadPage(page, { quiet: true }), 400);
}

export function init() {
  wireDialog($("alert-dialog"), ["alert-close"]);
  wireDialog($("ack-dialog"), ["ack-cancel"]);
  wireDialog($("resolve-alert-dialog"), ["resolve-alert-cancel"]);
  $("alert-map-toggle").addEventListener("click", toggleMap);
  $("alert-reload").addEventListener("click", () => current && openAlert(current.id, null));

  $("alert-filter").addEventListener("submit", (event) => {
    event.preventDefault();
    loadPage(0);
  });
  $("alert-clear").addEventListener("click", () => {
    $("alert-status").value = "";
    $("alert-q").value = "";
    $("alert-from").value = "";
    $("alert-to").value = "";
    loadPage(0);
  });

  $("alert-acknowledge").addEventListener("click", (event) => {
    hideAlert($("ack-alert"));
    openDialog($("ack-dialog"), event.currentTarget);
  });
  $("ack-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !current) return;
    busy = true;
    setBusy($("ack-confirm"), true, "Saving…");
    try {
      await api.admin.setAlertStatus(await getToken(), current.id, "ACKNOWLEDGED");
      await afterAction("Alert acknowledged. This only records that you have seen it; nobody was notified.");
    } catch (error) {
      actionError(error, "ack-alert", "We couldn't acknowledge that alert.");
    } finally {
      busy = false;
      setBusy($("ack-confirm"), false);
    }
  });

  $("alert-resolve").addEventListener("click", (event) => {
    $("resolve-alert-note").value = "";
    hideAlert($("resolve-alert-alert"));
    openDialog($("resolve-alert-dialog"), event.currentTarget);
    $("resolve-alert-note").focus();
  });
  $("resolve-alert-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !current) return;
    const note = $("resolve-alert-note").value.trim();
    if (note.length < 3) {
      showAlert($("resolve-alert-alert"), "error", "Please write a short resolution note (at least 3 characters).");
      $("resolve-alert-note").focus();
      return;
    }
    busy = true;
    setBusy($("resolve-alert-confirm"), true, "Saving…");
    try {
      await api.admin.setAlertStatus(await getToken(), current.id, "RESOLVED", note);
      await afterAction("Alert resolved and recorded in the audit log.");
    } catch (error) {
      actionError(error, "resolve-alert-alert", "We couldn't resolve that alert.");
    } finally {
      busy = false;
      setBusy($("resolve-alert-confirm"), false);
    }
  });
}

export const load = () => loadPage(0);
