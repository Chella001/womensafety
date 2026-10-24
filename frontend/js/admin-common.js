/** Helpers shared by the administrator console views. No framework; DOM is built with textContent only. */

import { ApiError } from "./api.js";
import { element, formatDateTime, hideAlert, showAlert } from "./ui.js";

export const $ = (id) => document.getElementById(id);
export { element, formatDateTime };

/** The signed-in Firebase user plus callbacks installed by admin.js (kept here to avoid circular imports). */
export const session = {
  user: null,
  onDenied: () => {},
  onExpired: () => {},
};

export const getToken = () => session.user.getIdToken();

/** Minutes east of UTC for this browser, e.g. 330 for India. Sent so the server cuts days the way the admin sees them. */
export const utcOffsetMinutes = () => -new Date().getTimezoneOffset();

export const short = (id) => (id ? `${String(id).slice(0, 8)}…` : "—");

/* ---------- status vocabulary ---------- */

const ALERT_LABELS = { ACTIVE: "new", ACKNOWLEDGED: "acknowledged", RESOLVED: "resolved", CANCELLED: "cancelled" };
const ALERT_KINDS = { ACTIVE: "warn", ACKNOWLEDGED: "info", RESOLVED: "ok", CANCELLED: "off" };

export const alertLabel = (status) => ALERT_LABELS[status] ?? String(status).toLowerCase();

export function pill(text, kind) {
  return element("span", `pill pill--${kind}`, text);
}

export const alertPill = (status) => pill(alertLabel(status), ALERT_KINDS[status] ?? "off");

export const accountPill = (status) =>
  status === "ACTIVE" ? pill("active", "ok") : pill("deactivated", "bad");

const NOTIFICATION_STATES = {
  QUEUED: ["processing", "warn"],
  PROCESSING: ["processing", "warn"],
  NO_RECIPIENTS: ["no recipients", "off"],
  SIMULATED: ["simulated (test)", "info"],
  SUBMITTED: ["accepted, receipt unconfirmed", "info"],
  DELIVERED: ["delivered", "ok"],
  PARTIAL: ["partial", "warn"],
  FAILED: ["failed", "bad"],
  UNAVAILABLE: ["unavailable", "off"],
};

/** Notification state as the server derived it from the delivery records (never more than the evidence shows). */
export const notificationPill = (state) => pill(...(NOTIFICATION_STATES[state] ?? NOTIFICATION_STATES.UNAVAILABLE));

const DELIVERY_KINDS = { QUEUED: "warn", SENDING: "warn", RETRY_SCHEDULED: "warn", SUBMITTED: "info", DELIVERED: "ok", SIMULATED: "info", FAILED: "bad", SKIPPED: "off", CANCELLED: "off" };
export const deliveryPill = (status) => pill(String(status).toLowerCase().replace("_", " "), DELIVERY_KINDS[status] ?? "off");

const FLAG_LABELS = {
  UNACKNOWLEDGED: "unacknowledged",
  CONTACT_NOTIFICATION_PROBLEM: "contact messages: problem",
  ADMIN_NOT_NOTIFIED: "admin push not delivered",
};
export const flagPill = (flag) => pill(FLAG_LABELS[flag] ?? flag.toLowerCase(), "bad");

export function outcomePill(outcome) {
  const kinds = { SUCCESS: "ok", FAILURE: "bad", DENIED: "bad" };
  return pill(String(outcome ?? "SUCCESS").toLowerCase(), kinds[outcome] ?? "off");
}

/* ---------- errors ---------- */

/**
 * Shows an API failure in the page alert. Returns true when the session itself is no longer usable
 * (and the shell has already reacted), so callers can stop.
 */
export function report(error, fallback, alertEl = $("admin-alert")) {
  if (error instanceof ApiError && error.status === 401) {
    session.onExpired();
    return true;
  }
  if (error instanceof ApiError && error.status === 403 && error.code !== "ACCOUNT_SUSPENDED") {
    session.onDenied();
    return true;
  }
  showAlert(alertEl, "error", error instanceof ApiError ? error.message : fallback);
  return false;
}

export const clearReport = (alertEl = $("admin-alert")) => hideAlert(alertEl);

/* ---------- tables, facts, paging ---------- */

export function cell(content, className) {
  const td = element("td", className);
  if (content instanceof Node) td.append(content);
  else td.textContent = content ?? "—";
  return td;
}

export function actionButton(label, onClick, { danger = false } = {}) {
  const button = element("button", `btn btn--outline btn--small${danger ? " btn--danger" : ""}`, label);
  button.type = "button";
  button.addEventListener("click", () => onClick(button));
  return button;
}

/** Replaces the rows of a table and toggles its empty-state message. */
export function fillTable(tableId, emptyId, rows) {
  $(tableId).querySelector("tbody").replaceChildren(...rows);
  $(tableId).closest(".table-wrap").hidden = rows.length === 0;
  $(emptyId).hidden = rows.length !== 0;
}

export function facts(listId, pairs) {
  $(listId).replaceChildren(
    ...pairs.map(([label, value]) => {
      const row = element("div");
      const dd = element("dd");
      if (value instanceof Node) dd.append(value);
      else dd.textContent = value ?? "—";
      row.append(element("dt", "", label), dd);
      return row;
    }),
  );
}

/**
 * Previous / Next controls for a server-paged list.
 * @param {string} id  id of an empty <nav> element
 * @param {{page:number,totalPages:number,total:number,size:number}} info  the page object from the API
 * @param {(page:number) => void} go
 */
export function renderPager(id, info, go) {
  const nav = $(id);
  nav.hidden = info.total === 0;
  if (info.total === 0) return;
  const from = info.page * info.size + 1;
  const to = Math.min((info.page + 1) * info.size, info.total);
  const prev = actionButton("Previous", () => go(info.page - 1));
  const next = actionButton("Next", () => go(info.page + 1));
  prev.disabled = info.page <= 0;
  next.disabled = info.page >= info.totalPages - 1;
  nav.replaceChildren(
    element("span", "pager__info", `${from}–${to} of ${info.total} · page ${info.page + 1} of ${info.totalPages}`),
    prev,
    next,
  );
}

/** Starts a browser download of text content (used for the CSV export). */
export function downloadText(filename, text, type = "text/csv;charset=utf-8") {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = element("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Opens a <dialog> and remembers which control to give focus back to when it closes. */
export function openDialog(dialog, trigger) {
  dialog.dataset.returnTo = trigger?.id ?? "";
  dialog.__trigger = trigger ?? null;
  dialog.showModal();
}

export function wireDialog(dialog, closeIds = []) {
  for (const id of closeIds) $(id).addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener("close", () => {
    const trigger = dialog.__trigger;
    if (trigger && trigger.isConnected && !trigger.disabled) trigger.focus();
  });
}
