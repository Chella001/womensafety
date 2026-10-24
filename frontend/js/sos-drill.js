/**
 * SOS practice drill.
 *
 * Walks through the same steps as a real SOS so the user knows what to expect, but it is a separate dialog with no
 * path to the alert API: it never calls api.createAlert, creates no alert, sends nothing and writes nothing to the
 * alert collections. The emergency number is shown as text, not as a tel: link, so a practice run cannot dial it.
 * An optional location check runs the browser's location lookup to confirm it works; the position is shown and then
 * discarded. At the end ONE harmless request records "drill done" on the user's own profile (for the checklist).
 */

import { api, ApiError } from "./api.js";
import { emergencyNumber } from "./config.js";
import { capturePosition, describeGeoError } from "./location.js";
import { recipientSummary } from "./sos.js";
import { loadSosInfo } from "./sos-info.js";
import { hideAlert, setBusy, showAlert } from "./ui.js";

/**
 * @param {{ getToken: () => Promise<string>, getContacts: () => object[], onUnauthorized: (e: Error) => void,
 *           onCompleted: (profile: object) => void }} options
 * @returns {{ open: (trigger?: HTMLElement) => void }}
 */
export function initSosDrill({ getToken, getContacts, onUnauthorized, onCompleted }) {
  const $ = (id) => document.getElementById(id);
  const dialog = $("drill-dialog");
  const alertBox = $("drill-alert");
  const practiceStep = $("drill-step-practice");
  const doneStep = $("drill-step-done");
  const runButton = $("drill-run");
  const checkLocation = $("drill-check-location");
  let running = false;
  let trigger = null;

  $("drill-number").textContent = emergencyNumber;
  $("drill-number-2").textContent = emergencyNumber;

  function open(from) {
    trigger = from ?? null;
    hideAlert(alertBox);
    practiceStep.hidden = false;
    doneStep.hidden = true;
    checkLocation.checked = false;
    loadSosInfo(getToken).then((info) => {
      $("drill-recipients").textContent = recipientSummary(getContacts(), info.testMode);
    });
    $("drill-recipients").textContent = recipientSummary(getContacts(), false);
    dialog.showModal();
    runButton.focus();
  }

  runButton.addEventListener("click", async () => {
    if (running) return;
    running = true;
    hideAlert(alertBox);
    setBusy(runButton, true, checkLocation.checked ? "Checking location…" : "Practising…");
    let locationText = "Not checked in this practice run.";
    if (checkLocation.checked) {
      try {
        const position = await capturePosition({ timeout: 8000 });
        locationText = `Found (±${position.accuracyMeters} m). In a real alert it would be shared with your verified contacts and the safety team. In this practice it was not sent or saved.`;
      } catch (error) {
        locationText = `${describeGeoError(error)} In a real alert SafeHer would still create the alert, without a location.`;
      }
    }
    $("drill-location-result").textContent = locationText;
    try {
      onCompleted(await api.markSosDrill(await getToken()));
      $("drill-saved").textContent = "Your drill is marked as done.";
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.code === "ACCOUNT_SUSPENDED")) {
        dialog.close();
        onUnauthorized(error);
        return;
      }
      // The practice itself still happened; only the checklist tick could not be saved.
      $("drill-saved").textContent = "We couldn't save that you finished the drill, so the checklist may still show it as not done.";
    } finally {
      running = false;
      setBusy(runButton, false);
    }
    practiceStep.hidden = true;
    doneStep.hidden = false;
    $("drill-done-close").focus();
  });

  $("drill-close").addEventListener("click", () => dialog.close());
  $("drill-done-close").addEventListener("click", () => dialog.close());
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener("close", () => {
    const back = trigger?.isConnected ? trigger : $("drill-btn");
    back.focus();
  });
  $("drill-btn").addEventListener("click", (event) => open(event.currentTarget));

  return { open };
}
