/** Reports: daily / weekly / monthly SOS summaries with CSV export, and incident reports. */

import { api } from "./api.js";
import { hideAlert, setBusy, showAlert } from "./ui.js";
import {
  $, actionButton, cell, clearReport, downloadText, element, fillTable, formatDateTime, getToken, openDialog, pill,
  report, short, utcOffsetMinutes, wireDialog,
} from "./admin-common.js";

let resolving = null;
let busy = false;
let exporting = false;

function query() {
  return {
    period: $("report-period").value,
    from: $("report-from").value,
    to: $("report-to").value,
    utcOffsetMinutes: utcOffsetMinutes(),
  };
}

async function loadSummary() {
  const q = query();
  if (q.from && q.to && q.from > q.to) {
    showAlert($("admin-alert"), "error", "The start date must not be after the end date.");
    return;
  }
  clearReport();
  $("summary-loading").hidden = false;
  try {
    const result = await api.admin.alertReport(await getToken(), q);
    fillTable("summary-table", "summary-empty", result.rows.map((r) => {
      const tr = element("tr");
      tr.append(cell(r.label), cell(String(r.total)), cell(String(r.newAlerts)), cell(String(r.acknowledged)),
        cell(String(r.resolved)), cell(String(r.cancelled)), cell(String(r.withLocation)));
      return tr;
    }));
    $("summary-range").textContent = `${result.from} to ${result.to} (${result.period}, times in UTC${formatOffset(q.utcOffsetMinutes)})`;
    $("summary-truncated").hidden = !result.truncated;
    const anyAlerts = result.rows.some((r) => r.total > 0);
    $("summary-empty").textContent = "No SOS records in this period.";
    $("summary-empty").hidden = anyAlerts;
    $("summary-table").closest(".table-wrap").hidden = !anyAlerts;
  } catch (error) {
    report(error, "We couldn't build the summary.");
  } finally {
    $("summary-loading").hidden = true;
  }
}

function formatOffset(minutes) {
  const sign = minutes >= 0 ? "+" : "-";
  const abs = Math.abs(minutes);
  return `${sign}${String(Math.floor(abs / 60)).padStart(2, "0")}:${String(abs % 60).padStart(2, "0")}`;
}

async function exportCsv() {
  if (exporting) return;
  const q = query();
  if (q.from && q.to && q.from > q.to) {
    showAlert($("admin-alert"), "error", "The start date must not be after the end date.");
    return;
  }
  exporting = true;
  setBusy($("report-export"), true, "Preparing…");
  try {
    const csv = await api.admin.alertReportCsv(await getToken(), q);
    downloadText(`safeher-alert-summary-${q.period}.csv`, csv);
    showAlert($("admin-alert"), "success", "CSV downloaded. It contains dates and counts only, and the export was recorded in the audit log.");
  } catch (error) {
    report(error, "We couldn't export the CSV.");
  } finally {
    exporting = false;
    setBusy($("report-export"), false);
  }
}

async function loadIncidents() {
  $("incidents-loading").hidden = false;
  try {
    const reports = await api.admin.incidentReports(await getToken());
    fillTable("incidents-table", "incidents-empty", reports.map((r) => {
      const tr = element("tr");
      const action = r.status === "OPEN"
        ? actionButton("Resolve", (button) => {
          resolving = r;
          $("resolve-text").textContent = `Mark this ${r.category.toLowerCase().replace("_", " ")} report as resolved?`;
          $("resolve-note").value = "";
          hideAlert($("resolve-alert"));
          openDialog($("resolve-dialog"), button);
        })
        : element("span", "hint", "Done");
      tr.append(
        cell(formatDateTime(r.createdAt)),
        cell(element("code", "", short(r.uid))),
        cell(r.category.toLowerCase().replace("_", " ")),
        cell(r.description, "cell-wrap"),
        cell(pill(r.status.toLowerCase(), r.status === "OPEN" ? "warn" : "ok")),
        cell(action),
      );
      return tr;
    }));
  } catch (error) {
    report(error, "We couldn't load the incident reports.");
  } finally {
    $("incidents-loading").hidden = true;
  }
}

export function init() {
  wireDialog($("resolve-dialog"), ["resolve-cancel"]);
  $("report-form").addEventListener("submit", (event) => {
    event.preventDefault();
    loadSummary();
  });
  $("report-export").addEventListener("click", exportCsv);

  $("resolve-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (busy || !resolving) return;
    busy = true;
    setBusy($("resolve-confirm"), true, "Saving…");
    try {
      await api.admin.resolveIncidentReport(await getToken(), resolving.id, $("resolve-note").value.trim() || undefined);
      $("resolve-dialog").close();
      await loadIncidents();
    } catch (error) {
      $("resolve-dialog").close();
      report(error, "We couldn't resolve that report.");
    } finally {
      busy = false;
      setBusy($("resolve-confirm"), false);
    }
  });
}

export async function load() {
  await Promise.all([loadSummary(), loadIncidents()]);
}
