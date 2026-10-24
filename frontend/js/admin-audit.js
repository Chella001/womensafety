/** Audit log: read-only. There is no edit or delete control, and the API has no endpoint for either. */

import { api } from "./api.js";
import { $, cell, element, fillTable, formatDateTime, getToken, outcomePill, pill, renderPager, report, short } from "./admin-common.js";

const PAGE_SIZE = 15;

async function loadPage(requested = 0) {
  $("audit-loading").hidden = false;
  try {
    const result = await api.admin.auditLogs(await getToken(), { type: $("audit-type").value, page: requested, size: PAGE_SIZE });
    fillTable("audit-table", "audit-empty", result.items.map((entry) => {
      const tr = element("tr");
      const details = Object.entries(entry.details ?? {}).map(([k, v]) => `${k}: ${v}`).join(", ");
      const security = entry.type === "SECURITY_EVENT";
      tr.append(
        cell(formatDateTime(entry.createdAt)),
        cell(pill(security ? "security" : "admin", security ? "bad" : "off")),
        cell(entry.action),
        cell(outcomePill(entry.outcome)),
        cell(element("code", "", short(entry.actorUid))),
        cell(entry.targetUid ? element("code", "", short(entry.targetUid)) : "—"),
        cell(details || "—", "cell-wrap"),
      );
      return tr;
    }));
    renderPager("audit-pager", result, loadPage);
  } catch (error) {
    report(error, "We couldn't load the audit log.");
  } finally {
    $("audit-loading").hidden = true;
  }
}

export function init() {
  $("audit-filter").addEventListener("submit", (event) => {
    event.preventDefault();
    loadPage(0);
  });
  $("audit-type").addEventListener("change", () => loadPage(0));
}

export const load = () => loadPage(0);
