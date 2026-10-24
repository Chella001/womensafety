/**
 * "Get ready" checklist on the dashboard, driven only by real API data:
 *   1. at least one emergency contact      (GET /api/emergency-contacts)
 *   2. at least one contact that is verified AND agreed to messages (same list: `eligible`)
 *   3. an SOS practice drill finished       (GET /api/users/me → sosDrillCompletedAt)
 * The card stays hidden until BOTH the profile and the contact list have loaded (no guessing from missing data) and
 * disappears once every step is done.
 */

import { element, formatDateTime } from "./ui.js";

/**
 * @param {{ onStartDrill?: (trigger: HTMLElement) => void }} options
 * @returns {{ setProfile: (profile: object) => void, setContacts: (contacts: object[]) => void }}
 */
export function initOnboarding({ onStartDrill } = {}) {
  const $ = (id) => document.getElementById(id);
  const card = $("onboarding");
  const list = $("onboarding-list");
  const main = $("main");
  let profile = null;
  let contacts = null;

  function steps() {
    const added = contacts.length;
    const verified = contacts.filter((c) => c.verified).length;
    const ready = contacts.filter((c) => c.eligible).length;
    return [
      {
        done: added > 0,
        title: "Add an emergency contact",
        detail: added > 0 ? `${added} contact(s) added.` : "Someone you trust, who has agreed to be your contact.",
        action: { label: "Add contact", href: "#contacts" },
      },
      {
        done: ready > 0,
        title: "Verify a contact who agreed to messages",
        detail: added === 0
          ? "Only verified contacts who agreed can receive an SOS message."
          : `${verified} of ${added} verified · ${ready} will be messaged in an SOS.`,
        action: { label: "Verify", href: "#contacts" },
      },
      {
        done: Boolean(profile.sosDrillCompletedAt),
        title: "Practise with an SOS drill",
        detail: profile.sosDrillCompletedAt
          ? `Done on ${formatDateTime(profile.sosDrillCompletedAt)}.`
          : "A practice run of the SOS screens. No alert is created and nothing is sent.",
        action: { label: "Start drill", drill: true },
      },
    ];
  }

  function render() {
    if (!profile || !contacts) return;
    const all = steps();
    const done = all.filter((s) => s.done).length;
    const complete = done === all.length;
    card.hidden = complete;
    main.classList.toggle("dash-main--onboarding", !complete);
    if (complete) return;

    $("onboarding-count").textContent = `${done} / ${all.length}`;
    $("onboarding-bar").style.width = `${Math.round((done / all.length) * 100)}%`;
    list.replaceChildren(
      ...all.map((step) => {
        const item = element("li", `checklist__item${step.done ? " is-done" : ""}`);
        const mark = element("span", "checklist__mark", "✓");
        mark.setAttribute("aria-hidden", "true");
        const label = element("div", "checklist__label");
        label.append(element("strong", "", step.title), element("span", "", step.detail));
        const status = element("span", "sr-only", step.done ? "Done: " : "To do: ");
        label.prepend(status);
        item.append(mark, label);
        if (!step.done) {
          let action;
          if (step.action.drill && onStartDrill) {
            action = element("button", "btn btn--outline btn--small", step.action.label);
            action.type = "button";
            action.addEventListener("click", () => onStartDrill(action));
          } else {
            action = element("a", "btn btn--outline btn--small", step.action.label);
            action.href = step.action.href ?? "#sos";
          }
          item.append(action);
        }
        return item;
      }),
    );
  }

  return {
    setProfile(next) {
      profile = next;
      render();
    },
    setContacts(next) {
      contacts = next;
      render();
    },
  };
}
