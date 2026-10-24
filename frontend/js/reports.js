/** Incident reports: file a report and list your own. Reports are read by the administrator. */

import { api, ApiError } from "./api.js";
import { element, formatDateTime, hideAlert, setBusy, setFieldError, showAlert, showServerFieldErrors } from "./ui.js";

const CATEGORY_LABELS = {
  HARASSMENT: "Harassment",
  STALKING: "Stalking",
  ASSAULT: "Assault",
  UNSAFE_LOCATION: "Unsafe location",
  OTHER: "Other",
};

export function initReports({ getToken, onUnauthorized }) {
  const $ = (id) => document.getElementById(id);
  const dialog = $("report-dialog");
  const form = $("report-form");
  const category = $("report-category");
  const description = $("report-description");
  const alertBox = $("report-alert");
  const submit = $("report-submit");
  const list = $("report-list");
  let reports = [];
  let sending = false;

  function render() {
    $("reports-loading").hidden = true;
    list.replaceChildren(...reports.slice(0, 5).map(row));
    list.hidden = reports.length === 0;
    $("reports-empty").hidden = reports.length !== 0;
  }

  function row(report) {
    const item = element("li", "record");
    const head = element("div", "record__head");
    head.append(element("strong", "", CATEGORY_LABELS[report.category] ?? report.category));
    head.append(element("span", `pill pill--${report.status === "OPEN" ? "warn" : "ok"}`, report.status.toLowerCase()));
    item.append(head, element("p", "record__meta", `Filed ${formatDateTime(report.createdAt)}`));
    if (report.resolutionNote) item.append(element("p", "record__meta", `Response: ${report.resolutionNote}`));
    return item;
  }

  async function load() {
    try {
      reports = (await api.listReports(await getToken())).slice();
      render();
    } catch (error) {
      $("reports-loading").hidden = true;
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) onUnauthorized(error);
    }
  }

  $("report-open").addEventListener("click", () => {
    hideAlert(alertBox);
    form.reset();
    dialog.showModal();
    description.focus();
  });
  $("report-cancel").addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
  description.addEventListener("input", () => {
    $("report-description-error").classList.remove("is-visible");
    description.removeAttribute("aria-invalid");
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (sending) return;
    hideAlert(alertBox);
    const text = description.value.trim();
    if (text.length < 10) {
      setFieldError(description, "Please describe what happened (at least 10 characters).");
      description.focus();
      return;
    }

    sending = true;
    setBusy(submit, true, "Sending…");
    try {
      const created = await api.createReport(await getToken(), { category: category.value, description: text });
      reports = [created, ...reports];
      render();
      dialog.close();
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) return onUnauthorized(error);
      const shown = error instanceof ApiError && showServerFieldErrors(error.details, { description });
      if (!shown) showAlert(alertBox, "error", error instanceof ApiError ? error.message : "We couldn't send your report. Please try again.");
    } finally {
      sending = false;
      setBusy(submit, false);
    }
  });

  load();
}
