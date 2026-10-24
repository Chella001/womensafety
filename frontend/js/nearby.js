/**
 * "Nearby help": links that open a Google Maps search for police stations and hospitals.
 *
 * Without any action the links ask Google for "... near me" (Google decides the area itself). The browser location is
 * requested ONLY when the user presses "Use my location"; it is then written into the two links on this page and
 * nowhere else — it is not sent to SafeHer, not stored, and gone when the page is closed or reloaded.
 */

import { capturePosition, describeGeoError, geolocationSupported } from "./location.js";
import { hideAlert, setBusy, showAlert } from "./ui.js";

export const NEARBY_PLACES = Object.freeze({ police: "police station", hospital: "hospital" });

/** Google Maps search URL; with a position the map is centred there, otherwise Google resolves "near me" itself. */
export function mapsSearchUrl(place, position) {
  if (position) {
    const lat = Number(position.latitude).toFixed(5);
    const lng = Number(position.longitude).toFixed(5);
    return `https://www.google.com/maps/search/${encodeURIComponent(place).replace(/%20/g, "+")}/@${lat},${lng},15z`;
  }
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${place} near me`)}`;
}

export function initNearby() {
  const $ = (id) => document.getElementById(id);
  const links = { police: $("nearby-police"), hospital: $("nearby-hospital") };
  const button = $("nearby-locate");
  const status = $("nearby-status");
  const alertBox = $("nearby-alert");
  let busy = false;

  const setLinks = (position) => {
    for (const [key, link] of Object.entries(links)) link.href = mapsSearchUrl(NEARBY_PLACES[key], position);
  };
  setLinks(null);

  if (!geolocationSupported()) {
    button.hidden = true;
    return;
  }

  button.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    hideAlert(alertBox);
    setBusy(button, true, "Finding your location…");
    try {
      const position = await capturePosition({ timeout: 10000 });
      setLinks(position);
      status.textContent = `The links now search around your current location (±${position.accuracyMeters} m). It is kept only in these links on this page — SafeHer does not send or store it.`;
    } catch (error) {
      setLinks(null);
      status.textContent = "";
      showAlert(alertBox, "error", `${describeGeoError(error)} The links still work: Google will search "near me" itself.`);
    } finally {
      busy = false;
      setBusy(button, false);
    }
  });
}
