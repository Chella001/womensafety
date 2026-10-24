/**
 * Administrator console shell: guards the page, routes between the seven views and owns the responsive sidebar.
 *
 * SECURITY NOTE: everything here is convenience. The Java backend decides who is an administrator (server-side uid
 * allow-list) and refuses every admin API call from anyone else, so hiding or showing parts of this page grants nothing.
 */

import { api, ApiError } from "./api.js";
import { isConfigured, logout, waitForUser } from "./auth.js";
import { setBusy } from "./ui.js";
import { $, session } from "./admin-common.js";
import * as dashboard from "./admin-dashboard.js";
import * as users from "./admin-users.js";
import * as alerts from "./admin-alerts.js";
import * as notifications from "./admin-notifications.js";
import { createLive } from "./admin-live.js";
import * as reports from "./admin-reports.js";
import * as audit from "./admin-audit.js";
import * as settings from "./admin-settings.js";

const LOGIN_URL = "login.html";

const VIEWS = {
  dashboard: { title: "Dashboard", module: dashboard },
  users: { title: "Users", module: users },
  alerts: { title: "SOS alerts", module: alerts },
  notifications: { title: "Notifications", module: notifications },
  reports: { title: "Reports", module: reports },
  audit: { title: "Audit logs", module: audit },
  settings: { title: "Settings", module: settings },
};

const initialised = new Set();

/* ---------- live updates ---------- */

const LIVE_TEXT = {
  live: "Live",
  reconnecting: "Reconnecting…",
  polling: "Live updates unavailable — refreshing every minute",
  stopped: "Updates stopped",
};
const STALE_AFTER_MS = 90_000;
let liveState = "reconnecting";
let lastRefreshed = Date.now();
let newAlerts = 0;
let live = null;
let refreshTimer = null;

function renderLiveStatus() {
  const stale = liveState !== "live" && Date.now() - lastRefreshed > STALE_AFTER_MS;
  const status = $("live-status");
  status.textContent = LIVE_TEXT[liveState];
  status.dataset.state = liveState;
  $("last-refreshed").textContent = `Last refreshed ${new Date(lastRefreshed).toLocaleTimeString()}`;
  $("stale-note").hidden = !stale;
}

function touchRefreshed() {
  lastRefreshed = Date.now();
  renderLiveStatus();
}

function renderNewAlerts() {
  const badge = $("alerts-badge");
  badge.hidden = newAlerts === 0;
  badge.textContent = newAlerts ? String(newAlerts) : "";
  $("new-alert-indicator").hidden = newAlerts === 0;
  $("new-alert-text").textContent = newAlerts === 1 ? "1 new SOS alert" : `${newAlerts} new SOS alerts`;
  document.title = (newAlerts ? `(${newAlerts}) ` : "") + document.title.replace(/^\(\d+\)\s/, "");
}

function handleSignal(signal) {
  touchRefreshed();
  // An alert that arrives while the administrator is looking at the alert list is visible right away: no badge needed.
  if (signal.type === "created" && !(currentView === "alerts" && !document.hidden)) {
    newAlerts += 1;
    renderNewAlerts();
  }
  // Reload what is on screen. Quiet marker types ("connected", "poll") also refresh: data may have changed meanwhile.
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => {
    if (currentView === "alerts") alerts.onSignal(signal);
    else if (currentView === "dashboard" || currentView === "notifications") VIEWS[currentView].module.load();
  }, 300);
}
let currentView = null;

function showDenied(message) {
  $("admin-loading").hidden = true;
  $("admin-content").hidden = true;
  $("sidebar-wrap").hidden = true;
  $("denied-text").textContent = message;
  $("admin-denied").hidden = false;
  $("admin-main").setAttribute("aria-busy", "false");
}

function toViewName() {
  const name = window.location.hash.replace("#", "");
  return VIEWS[name] ? name : "dashboard";
}

function closeSidebar() {
  $("sidebar").classList.remove("is-open");
  $("sidebar-toggle").setAttribute("aria-expanded", "false");
}

function show(name, { reload = false } = {}) {
  const view = VIEWS[name];
  for (const key of Object.keys(VIEWS)) {
    $(`view-${key}`).hidden = key !== name;
    const link = document.querySelector(`.sidebar__link[data-view="${key}"]`);
    if (key === name) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  $("view-title").textContent = view.title;
  if (name === "alerts" && newAlerts > 0) {
    newAlerts = 0;
    renderNewAlerts();
  }
  document.title = `${view.title} · SafeHer admin`;
  $("admin-alert").classList.remove("is-visible");
  closeSidebar();

  const first = !initialised.has(name);
  if (first) {
    initialised.add(name);
    view.module.init?.();
  }
  if (first || reload || currentView !== name) {
    view.module.load();
  }
  currentView = name;
  touchRefreshed();
}

async function leave(reason) {
  await logout().catch(() => {});
  window.location.replace(reason ? `${LOGIN_URL}?reason=${reason}` : LOGIN_URL);
}

async function init() {
  session.onExpired = () => leave("expired");
  session.onDenied = () => showDenied("This area is only for the SafeHer administrator.");

  if (!isConfigured()) {
    window.location.replace(LOGIN_URL);
    return;
  }

  try {
    session.user = await waitForUser();
    if (!session.user) {
      window.location.replace(LOGIN_URL);
      return;
    }
    const me = await api.me(await session.user.getIdToken());
    if (me.role !== "ADMIN") {
      showDenied("Your account does not have administrator access.");
      return;
    }
    $("sidebar-name").textContent = me.fullName || "Administrator";
    $("sidebar-avatar").textContent = (me.fullName || "A").trim().charAt(0).toUpperCase();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      await leave("expired");
      return;
    }
    showDenied(error instanceof ApiError && ![401, 403].includes(error.status)
      ? error.message
      : "This area is only for the SafeHer administrator.");
    return;
  }

  $("admin-loading").hidden = true;
  $("admin-content").hidden = false;
  $("admin-main").setAttribute("aria-busy", "false");

  for (const link of document.querySelectorAll(".sidebar__link")) {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      const name = link.dataset.view;
      if (window.location.hash === `#${name}`) show(name, { reload: true });
      else window.location.hash = name; // hashchange renders it
    });
  }
  window.addEventListener("hashchange", () => show(toViewName()));
  $("refresh-btn").addEventListener("click", () => show(currentView ?? "dashboard", { reload: true }));

  $("sidebar-toggle").addEventListener("click", () => {
    const open = !$("sidebar").classList.contains("is-open");
    $("sidebar").classList.toggle("is-open", open);
    $("sidebar-toggle").setAttribute("aria-expanded", String(open));
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && $("sidebar").classList.contains("is-open")) {
      closeSidebar();
      $("sidebar-toggle").focus();
    }
  });

  for (const id of ["logout-btn", "logout-btn-top"]) {
    $(id).addEventListener("click", async () => {
      setBusy($(id), true);
      await leave();
    });
  }

  $("new-alert-indicator").addEventListener("click", () => {
    if (window.location.hash === "#alerts") show("alerts", { reload: true });
    else window.location.hash = "alerts";
  });
  setInterval(renderLiveStatus, 15_000);

  live = createLive({
    getToken: () => session.user.getIdToken(),
    onSignal: handleSignal,
    onState: (state) => {
      liveState = state;
      renderLiveStatus();
    },
    onAuthError: (error) => (error.status === 401 ? leave("expired") : showDenied("This area is only for the SafeHer administrator.")),
  });
  live.start();
  window.addEventListener("pagehide", () => live.stop());

  show(toViewName());
}

init();
