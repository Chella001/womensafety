/**
 * Renders the public helplines from js/config.js as tap-to-call links. Used by the dashboard and the public pages, so the
 * numbers exist in exactly one place. DOM is built with textContent only.
 */

import { helplines } from "./config.js";

/** `tel:` URL for a short national number (digits only). */
export const telLink = (number) => `tel:${String(number).replace(/[^0-9+]/g, "")}`;

/**
 * @param {HTMLElement} list an empty <ul> to fill
 * @param {{ iconHref?: string }} options path to sprite.svg relative to the page (dashboard: "assets/icons/sprite.svg")
 */
export function renderHelplines(list, { iconHref = "assets/icons/sprite.svg" } = {}) {
  list.replaceChildren(
    ...helplines.map((line) => {
      const item = document.createElement("li");
      item.className = "helpline";

      const call = document.createElement("a");
      call.className = "helpline__call";
      call.href = telLink(line.number);
      call.setAttribute("aria-label", `Call ${line.number}, ${line.name}`);
      const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.setAttribute("class", "icon");
      svg.setAttribute("aria-hidden", "true");
      const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
      use.setAttribute("href", `${iconHref}#phone`);
      svg.append(use);
      const number = document.createElement("span");
      number.className = "helpline__number";
      number.textContent = line.number;
      call.append(svg, number);

      const text = document.createElement("div");
      text.className = "helpline__text";
      const name = document.createElement("strong");
      name.className = "helpline__name";
      name.textContent = line.name;
      const description = document.createElement("span");
      description.className = "helpline__desc";
      description.textContent = line.description;
      text.append(name, description);

      item.append(call, text);
      return item;
    }),
  );
}
