/** Settings: a read-only view of the server's policy, plus the administrator's own session controls. */

import { api, ApiError } from "./api.js";
import { logout } from "./auth.js";
import { setBusy, showAlert } from "./ui.js";
import { $, facts, formatDateTime, getToken, report } from "./admin-common.js";

let busy = false;

export function init() {
  $("settings-logout-all").addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    setBusy($("settings-logout-all"), true, "Signing out…");
    try {
      await api.logoutEverywhere(await getToken());
      await logout().catch(() => {});
      window.location.replace("login.html");
    } catch (error) {
      busy = false;
      setBusy($("settings-logout-all"), false);
      showAlert($("admin-alert"), "error", error instanceof ApiError ? error.message : "We couldn't sign you out everywhere.");
    }
  });
}

export async function load() {
  $("settings-loading").hidden = false;
  try {
    const token = await getToken();
    const [settings, me] = await Promise.all([api.admin.settings(token), api.me(token)]);
    facts("settings-account", [
      ["Signed in as", me.fullName || "—"],
      ["Phone number", me.phoneNumber],
      ["Role (decided by the server)", me.role],
      ["Account ID", me.uid],
      ["Last login", formatDateTime(me.lastLoginAt)],
    ]);
    facts("settings-policy", [
      ["Administrators configured", String(settings.administratorCount)],
      ["Alert records kept for", `${settings.alertRetentionDays} days`],
      ["Terms version users accept", settings.consentVersion],
      ["Token revocation check", settings.tokenRevocationCheck ? "on" : "off"],
      ["Request limits", settings.rateLimitEnabled
        ? `${settings.authRequestsPerMinute}/min for sign-in endpoints, ${settings.apiRequestsPerMinute}/min otherwise (per client)`
        : "off"],
      ["SOS alerts per user", `${settings.maxAlertsPerWindow} per ${settings.alertWindowMinutes} minutes`],
      ["Allowed browser origins", settings.allowedOrigins.join(", ") || "—"],
      ["Notification provider", settings.notificationProvider],
    ]);
    $("settings-notification").textContent = settings.notificationNote;
    $("settings-body").hidden = false;
  } catch (error) {
    report(error, "We couldn't load the settings.");
  } finally {
    $("settings-loading").hidden = true;
  }
}
