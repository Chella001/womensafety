/**
 * Shared script for the public pages (home, help, 404). No sign-in, no API calls, nothing stored.
 *   - renders the helplines from js/config.js into #helplines-list (if the page has one)
 *   - fills every [data-emergency-number] with the emergency number and turns [data-emergency-call] into a tel: link
 *   - resolves [data-href] / [data-src] against the frontend folder, so 404.html works at any URL depth
 */

import { emergencyNumber } from "./config.js";
import { renderHelplines, telLink } from "./helplines.js";

const frontendRoot = new URL("../", import.meta.url);

for (const element of document.querySelectorAll("[data-href]")) {
  element.href = new URL(element.dataset.href, frontendRoot).href;
}
for (const element of document.querySelectorAll("[data-src]")) {
  element.src = new URL(element.dataset.src, frontendRoot).href;
}
for (const element of document.querySelectorAll("[data-emergency-number]")) {
  element.textContent = emergencyNumber;
}
for (const element of document.querySelectorAll("a[data-emergency-call]")) {
  element.href = telLink(emergencyNumber);
}

const list = document.getElementById("helplines-list");
if (list) {
  renderHelplines(list, { iconHref: new URL("assets/icons/sprite.svg", frontendRoot).href });
}

const year = document.getElementById("year");
if (year) year.textContent = String(new Date().getFullYear());
