/** Users: search, filter, pagination, detail (audited) and deactivate / reactivate with confirmation. */

import { api, ApiError } from "./api.js";
import { setBusy, showAlert, hideAlert } from "./ui.js";
import {
  $, accountPill, actionButton, cell, element, facts, fillTable, formatDateTime, getToken, openDialog, renderPager,
  report, wireDialog,
} from "./admin-common.js";

const PAGE_SIZE = 10;
let page = 0;
let target = null;
let busy = false;

let latest = 0; // only the newest request may draw: a slow, older response must not overwrite a newer one

async function loadPage(requested = 0) {
  const mine = ++latest;
  $("users-loading").hidden = false;
  try {
    const result = await api.admin.users(await getToken(), {
      search: $("user-search").value.trim(),
      status: $("user-status").value,
      page: requested,
      size: PAGE_SIZE,
    });
    if (mine !== latest) return;
    page = result.page;
    fillTable("users-table", "users-empty", result.items.map(row));
    renderPager("users-pager", result, loadPage);
  } catch (error) {
    report(error, "We couldn't load the users.");
  } finally {
    $("users-loading").hidden = true;
  }
}

function row(user) {
  const tr = element("tr");
  const actions = element("div", "cell-actions");
  actions.append(actionButton("View", (button) => viewUser(user.uid, button)));
  if (user.accountStatus === "ACTIVE") {
    actions.append(actionButton("Deactivate", (button) => openStatus(user, "SUSPENDED", button), { danger: true }));
  } else {
    actions.append(actionButton("Reactivate", (button) => openStatus(user, "ACTIVE", button)));
  }
  tr.append(
    cell(user.fullName || "—"),
    cell(user.phoneMasked),
    cell(accountPill(user.accountStatus)),
    cell(formatDateTime(user.createdAt)),
    cell(formatDateTime(user.lastLoginAt)),
    cell(actions),
  );
  return tr;
}

async function viewUser(uid, button) {
  button.disabled = true;
  try {
    const user = await api.admin.user(await getToken(), uid);
    $("user-dialog-title").textContent = user.fullName || "User";
    facts("user-facts", [
      ["Account ID", user.uid],
      ["Phone number", user.phoneNumber || "—"],
      ["Phone verified", user.phoneVerified ? "Yes" : "No"],
      ["Role", user.role],
      ["Status", user.accountStatus === "ACTIVE" ? "active" : "deactivated"],
      ["Registered", formatDateTime(user.createdAt)],
      ["Last login", formatDateTime(user.lastLoginAt)],
      ["Accepted terms", user.consentVersion ? `${user.consentVersion} (${formatDateTime(user.consentTimestamp)})` : "—"],
    ]);
    openDialog($("user-dialog"), button);
  } catch (error) {
    report(error, "We couldn't load that user.");
  } finally {
    button.disabled = false;
  }
}

function openStatus(user, status, trigger) {
  target = { user, status };
  const deactivate = status === "SUSPENDED";
  $("status-title").textContent = deactivate ? "Deactivate account?" : "Reactivate account?";
  $("status-text").textContent = deactivate
    ? `${user.fullName || "This user"} will be signed out on every device and will not be able to log in or use SafeHer until you reactivate the account.`
    : `${user.fullName || "This user"} will be able to log in and use SafeHer again.`;
  $("status-confirm-label").textContent = deactivate ? "Deactivate" : "Reactivate";
  $("status-reason").value = "";
  hideAlert($("status-alert"));
  openDialog($("status-dialog"), trigger);
}

export function init() {
  wireDialog($("user-dialog"), ["user-close"]);
  wireDialog($("status-dialog"), ["status-cancel"]);

  $("user-filter").addEventListener("submit", (event) => {
    event.preventDefault();
    loadPage(0);
  });
  $("user-status").addEventListener("change", () => loadPage(0));

  $("status-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !target) return;
    busy = true;
    setBusy($("status-confirm"), true, "Working…");
    try {
      await api.admin.setUserStatus(await getToken(), target.user.uid, target.status, $("status-reason").value.trim() || undefined);
      $("status-dialog").close();
      await loadPage(page);
    } catch (error) {
      if (error instanceof ApiError && ![401, 403].includes(error.status)) {
        showAlert($("status-alert"), "error", error.message);
      } else {
        $("status-dialog").close();
        report(error, "We couldn't change the account status.");
      }
    } finally {
      busy = false;
      setBusy($("status-confirm"), false);
    }
  });
}

export const load = () => loadPage(0);
