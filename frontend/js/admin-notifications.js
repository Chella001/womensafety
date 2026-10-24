/**
 * Notifications: how SOS messages are configured, this administrator's push devices, and recent delivery problems.
 * Nothing here claims a response service exists: SafeHer only attempts SMS to verified contacts and a push to registered
 * administrator devices, and shows the recorded outcome.
 */

import { api, ApiError } from "./api.js";
import { setBusy, showAlert } from "./ui.js";
import {
  $, actionButton, cell, clearReport, element, facts, fillTable, formatDateTime, getToken, notificationPill, pill, report, short,
} from "./admin-common.js";
import { pushSupport, registerThisBrowser } from "./admin-push.js";

let busy = false;

function deviceRow(device) {
  const tr = element("tr");
  tr.append(
    cell(device.label),
    cell(formatDateTime(device.createdAt)),
    cell(formatDateTime(device.lastSeenAt)),
    cell(actionButton("Remove", async (button) => {
      button.disabled = true;
      try {
        await api.admin.removeDevice(await getToken(), device.id);
        await load();
      } catch (error) {
        report(error, "We couldn't remove that device.");
        button.disabled = false;
      }
    }, { danger: true })),
  );
  return tr;
}

function problemRow(problem) {
  const tr = element("tr");
  tr.append(
    cell(formatDateTime(problem.createdAt)),
    cell(element("code", "", short(problem.alertId))),
    cell(notificationPill(problem.contactState)),
    cell(notificationPill(problem.adminState)),
    cell(actionButton("Open in SOS alerts", () => {
      $("alert-q").value = problem.alertId;
      window.location.hash = "alerts";
    })),
  );
  return tr;
}

export function init() {
  $("push-enable").addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    setBusy($("push-enable"), true, "Registering…");
    try {
      await registerThisBrowser(`${navigator.platform || "Browser"} (${new Date().toLocaleDateString()})`);
      await load();
      showAlert($("admin-alert"), "success", "This browser will receive a push when a new SOS alert is created. A push only means the push service accepted it; it does not mean anyone saw it.");
    } catch (error) {
      showAlert($("admin-alert"), "error", error instanceof ApiError ? error.message : error.message || "We couldn't register this browser.");
    } finally {
      busy = false;
      setBusy($("push-enable"), false);
    }
  });
}

export async function load() {
  clearReport();
  $("notifications-loading").hidden = false;
  try {
    const token = await getToken();
    const [overview, devices] = await Promise.all([api.admin.notificationOverview(token), api.admin.devices(token)]);
    facts("notifications-facts", [
      ["SMS mode", overview.mode === "TEST" ? element("span", "", "TEST — messages are simulated, nothing is sent") : "REAL"],
      ["SMS provider", overview.smsProvider],
      ["Push (FCM)", overview.fcmConfigured ? (overview.adminPushEnabled ? "Configured" : "Turned off by configuration") : "Not configured on the server"],
      ["Registered admin devices", `${overview.registeredDevices} (${overview.yourDevices} yours)`],
      ["Notification jobs open", String(overview.openJobs)],
    ]);
    $("notifications-note").textContent = overview.note;
    const support = pushSupport();
    $("push-enable").disabled = !support.ok;
    $("push-support-note").textContent = support.ok ? "" : support.reason;
    fillTable("devices-table", "devices-empty", devices.map(deviceRow));
    fillTable("problems-table", "problems-empty", overview.recentProblems.map(problemRow));
    if (overview.registeredDevices === 0) {
      showAlert($("admin-alert"), "info", "No administrator device is registered for push, so nobody is alerted outside this console. New alerts still appear here while it is open.");
    }
  } catch (error) {
    report(error, "We couldn't load the notification settings.");
  } finally {
    $("notifications-loading").hidden = true;
  }
}
