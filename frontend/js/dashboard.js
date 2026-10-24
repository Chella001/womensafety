import { api, ApiError } from "./api.js";
import { isConfigured, logout, waitForUser } from "./auth.js";
import { initEmergencyContacts } from "./emergency-contacts.js";
import { renderHelplines } from "./helplines.js";
import { initLocation } from "./location.js";
import { initReports } from "./reports.js";
import { initSos } from "./sos.js";
import {
  clearErrorOnInput,
  formatDateTime,
  hideAlert,
  initials,
  setBusy,
  showAlert,
  validateFields,
  validateFullName,
} from "./ui.js";

const LOGIN_URL = "login.html";

const $ = (id) => document.getElementById(id);
const main = $("main");
const title = $("dash-title");
const lead = $("dash-lead");
const alertBox = $("form-alert");
const logoutButtons = [$("logout-btn"), $("logout-btn-2"), $("menu-logout")];

let currentUser = null;

/** A fresh ID token for every API call (the Firebase SDK refreshes it shortly before it expires). */
const getToken = () => currentUser.getIdToken();

function leave(reason) {
  const suffix = reason ? `?reason=${encodeURIComponent(reason)}` : "";
  window.location.replace(`${LOGIN_URL}${suffix}`);
}

/** The server said this session is no longer acceptable (expired, revoked, suspended, deleted). */
async function onUnauthorized(error) {
  await logout().catch(() => {});
  leave(error?.code === "ACCOUNT_SUSPENDED" ? "suspended" : "expired");
}

async function signOutAndLeave() {
  for (const button of logoutButtons) setBusy(button, true);
  try {
    await logout();
  } finally {
    leave();
  }
}
for (const button of logoutButtons) button.addEventListener("click", signOutAndLeave);

const setText = (id, value) => {
  $(id).textContent = value;
};

function renderProfile(profile) {
  const firstName = profile.fullName.split(" ")[0];
  title.textContent = "";
  title.append("Welcome back, ");
  const accent = document.createElement("span");
  accent.textContent = firstName;
  title.append(accent, ".");
  lead.textContent = "This is your safety hub. Keep your emergency contacts up to date and know your location.";
  document.title = `${firstName} · SafeHer`;

  $("chip-account").hidden = !profile.phoneVerified;
  const active = profile.accountStatus === "ACTIVE";
  $("chip-status").hidden = false;
  $("chip-status").classList.toggle("chip--ok", active);
  setText("chip-status-text", `Account: ${profile.accountStatus.toLowerCase()}`);
  setText("menu-name", profile.fullName);
  setText("menu-phone", profile.phoneNumber);
  $("menu-admin").hidden = profile.role !== "ADMIN";
  setText("chip-avatar", initials(profile.fullName));
  setText("chip-name", profile.fullName);
  setText("profile-avatar", initials(profile.fullName));
  setText("profile-name", profile.fullName);
  setText("profile-phone", profile.phoneNumber);
  setText("dash-created", formatDateTime(profile.createdAt));
  setText("dash-last-login", formatDateTime(profile.lastLoginAt));
  setText("profile-uid", profile.uid);
  $("profile-name-input").value = profile.fullName;
  // Convenience only: the server refuses admin APIs for everyone except the configured administrator.
  $("admin-link").hidden = profile.role !== "ADMIN";
}

/* ---------- profile menu (disclosure pattern) ---------- */

function initProfileMenu() {
  const button = $("menu-btn");
  const panel = $("menu-panel");

  const setOpen = (open, { restoreFocus = false } = {}) => {
    panel.hidden = !open;
    button.setAttribute("aria-expanded", String(open));
    if (!open && restoreFocus) button.focus();
  };

  button.addEventListener("click", () => setOpen(panel.hidden));
  $("menu-account").addEventListener("click", () => setOpen(false));
  document.addEventListener("click", (event) => {
    if (!panel.hidden && !$("profile-menu").contains(event.target)) setOpen(false);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !panel.hidden) setOpen(false, { restoreFocus: true });
  });
  panel.addEventListener("focusout", (event) => {
    if (!panel.hidden && event.relatedTarget && !$("profile-menu").contains(event.relatedTarget)) setOpen(false);
  });
}

/* ---------- account settings ---------- */

function initAccount() {
  const form = $("name-form");
  const input = $("profile-name-input");
  const save = $("name-save");
  const accountAlert = $("account-alert");
  let saving = false;
  clearErrorOnInput(input);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (saving) return;
    hideAlert(accountAlert);
    if (!validateFields([[input, validateFullName]])) return;
    saving = true;
    setBusy(save, true, "Saving…");
    try {
      renderProfile(await api.updateMe(await getToken(), input.value.trim()));
      showAlert(accountAlert, "success", "Your name was updated.");
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) return onUnauthorized(error);
      showAlert(accountAlert, "error", error instanceof ApiError ? error.message : "We couldn't save your name.");
    } finally {
      saving = false;
      setBusy(save, false);
    }
  });

  // Logout on this device is client-side. A Firebase ID token cannot be revoked individually, so this button revokes
  // every refresh token of the account instead: all other devices are signed out as soon as their token expires or refreshes.
  const everywhere = $("logout-all-btn");
  everywhere.addEventListener("click", async () => {
    setBusy(everywhere, true, "Signing out…");
    try {
      await api.logoutEverywhere(await getToken());
      await logout().catch(() => {});
      leave();
    } catch (error) {
      setBusy(everywhere, false);
      showAlert(accountAlert, "error", error instanceof ApiError ? error.message : "We couldn't sign you out everywhere. Please try again.");
    }
  });
}

async function init() {
  // Helplines never depend on the API or the sign-in: they are shown even if everything else fails.
  renderHelplines($("helplines-list"));

  if (!isConfigured()) {
    leave();
    return;
  }

  try {
    currentUser = await waitForUser();
    if (!currentUser) {
      leave();
      return;
    }
    // The page is only "protected" because the backend verifies the token before returning anything.
    renderProfile(await api.me(await getToken()));
  } catch (error) {
    if (error instanceof ApiError && [401, 403].includes(error.status)) {
      await onUnauthorized(error);
      return;
    }
    title.textContent = "We couldn't load your dashboard";
    lead.textContent = "";
    showAlert(alertBox, "error", error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    main.setAttribute("aria-busy", "false");
    return;
  }

  const contacts = initEmergencyContacts({ getToken, onUnauthorized });
  initSos({ getToken, getContacts: () => contacts.getContacts(), onUnauthorized });
  initReports({ getToken, onUnauthorized });
  initLocation();
  initAccount();
  initProfileMenu();
  main.setAttribute("aria-busy", "false");
}

init();
