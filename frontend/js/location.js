/**
 * Browser geolocation. Used on demand only (after the user presses a button or ticks "Include my current location"),
 * never silently. The "Your location" card shows coordinates on the page and does not store or send them; the SOS dialog
 * attaches a position to an alert only when the user explicitly asks for it.
 */

import { hideAlert, setBusy, showAlert } from "./ui.js";

const PERMISSION_LABELS = {
  granted: "Allowed",
  prompt: "Will ask when you press the button",
  denied: "Blocked in browser settings",
  unknown: "Not checked",
  unsupported: "Not supported by this browser",
};

export function describeGeoError(error) {
  switch (error?.code) {
    case 1: // PERMISSION_DENIED
      return "Location access is blocked. Allow it for this site in your browser settings, then try again.";
    case 2: // POSITION_UNAVAILABLE
      return "Your device couldn't work out its location. Check that location services are on and try again.";
    case 3: // TIMEOUT
      return "Finding your location took too long. Try again, ideally with a clearer view of the sky or on Wi-Fi.";
    default:
      return "We couldn't get your location. Please try again.";
  }
}

export function geolocationSupported() {
  return "geolocation" in navigator;
}

function currentPosition(timeout = 15000) {
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: true,
      timeout,
      maximumAge: 0,
    });
  });
}

/** A timestamped position in the shape the API expects. Rejects with a GeolocationPositionError. */
export async function capturePosition({ timeout } = {}) {
  const { coords, timestamp } = await currentPosition(timeout);
  return {
    latitude: coords.latitude,
    longitude: coords.longitude,
    accuracyMeters: Math.round(coords.accuracy),
    capturedAt: new Date(timestamp).toISOString(),
  };
}

export function initLocation() {
  const $ = (id) => document.getElementById(id);
  const button = $("locate-btn");
  const alertBox = $("location-alert");
  const permission = $("loc-permission");
  const mapLink = $("map-link");
  let busy = false;

  const showPermission = (state) => {
    permission.textContent = PERMISSION_LABELS[state] ?? PERMISSION_LABELS.unknown;
  };

  if (!geolocationSupported()) {
    showPermission("unsupported");
    button.disabled = true;
    showAlert(alertBox, "info", "This browser can't provide your location.");
    return;
  }

  // The Permissions API is optional; without it we simply can't show the state in advance.
  showPermission("unknown");
  navigator.permissions
    ?.query({ name: "geolocation" })
    .then((status) => {
      showPermission(status.state);
      status.addEventListener("change", () => showPermission(status.state));
    })
    .catch(() => {});

  button.addEventListener("click", async () => {
    if (busy) return;
    busy = true;
    hideAlert(alertBox);
    setBusy(button, true, "Finding you…");
    try {
      const position = await capturePosition();
      $("loc-lat").textContent = position.latitude.toFixed(5);
      $("loc-lng").textContent = position.longitude.toFixed(5);
      $("loc-acc").textContent = `± ${position.accuracyMeters} m`;
      $("loc-time").textContent = new Date(position.capturedAt).toLocaleTimeString(undefined, { timeStyle: "medium" });
      mapLink.href = `https://www.openstreetmap.org/?mlat=${position.latitude}&mlon=${position.longitude}#map=17/${position.latitude}/${position.longitude}`;
      mapLink.hidden = false;
      showPermission("granted");
    } catch (error) {
      showAlert(alertBox, "error", describeGeoError(error));
      if (error?.code === 1) showPermission("denied");
    } finally {
      busy = false;
      setBusy(button, false);
    }
  });
}
