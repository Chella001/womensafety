/** Small, framework-free helpers shared by every SafeHer page (login, register, dashboard, admin). */

/** Absolute URL of the icon sprite, so the same code works from /page.html and from /admin/index.html. */
const SPRITE_URL = new URL("../assets/icons/sprite.svg", import.meta.url).href;

export function spriteHref(name) {
  return `${SPRITE_URL}#${name}`;
}

/* ---------- DOM building (always textContent, never innerHTML) ---------- */

export function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", spriteHref(name));
  svg.append(use);
  return svg;
}

export function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/* ---------- Validation (the Java backend repeats and enforces all of these) ---------- */

const NAME_PATTERN = /^\p{L}[\p{L}\p{M} .'’-]*$/u;

export function validateFullName(value, { min = 2, max = 60 } = {}) {
  const name = value.trim().replace(/\s+/g, " ");
  if (!name) return "Please enter your name.";
  const length = Array.from(name).length;
  if (length < min) return `Your name must be at least ${min} characters.`;
  if (length > max) return `Your name can be at most ${max} characters.`;
  if (!NAME_PATTERN.test(name)) return "Use letters, spaces, . ' and - only.";
  return "";
}

export function validateCode(value) {
  const code = value.replace(/\s+/g, "");
  if (!code) return "Please enter the 6-digit code.";
  if (!/^\d{6}$/.test(code)) return "The code is 6 digits.";
  return "";
}

/* ---------- Field errors ---------- */

function errorBox(input) {
  return input.closest(".form-group")?.querySelector(".field-error") ?? null;
}

export function setFieldError(input, message) {
  const box = errorBox(input);
  if (!box) return;
  box.firstElementChild.textContent = message;
  box.classList.add("is-visible");
  input.setAttribute("aria-invalid", "true");
  const field = input.closest(".field");
  if (field) {
    field.classList.remove("is-shaking");
    void field.offsetWidth; // restart the shake animation
    field.classList.add("is-shaking");
  }
}

export function clearFieldError(input) {
  const box = errorBox(input);
  if (!box) return;
  box.classList.remove("is-visible");
  box.firstElementChild.textContent = "";
  input.removeAttribute("aria-invalid");
}

/**
 * Runs `validate(value)` for each [input, validator] pair, shows errors and focuses the
 * first invalid input. Returns true when every field is valid.
 */
export function validateFields(pairs) {
  let firstInvalid = null;
  for (const [input, validate] of pairs) {
    const message = validate(input.value);
    if (message) {
      setFieldError(input, message);
      firstInvalid ??= input;
    } else {
      clearFieldError(input);
    }
  }
  firstInvalid?.focus();
  return firstInvalid === null;
}

/** Clears a field's error as soon as the user edits it. */
export function clearErrorOnInput(...inputs) {
  for (const input of inputs) {
    input.addEventListener("input", () => clearFieldError(input));
  }
}

/** Shows server-side field errors (ApiError.details) next to their inputs. Returns true when any was shown. */
export function showServerFieldErrors(details, inputsByField) {
  let shown = false;
  for (const [field, input] of Object.entries(inputsByField)) {
    if (details?.[field]) {
      setFieldError(input, details[field]);
      shown = true;
    }
  }
  return shown;
}

/* ---------- Alerts ---------- */

const ALERT_ICONS = { error: "alert", success: "success", info: "info" };

export function showAlert(alertEl, type, message) {
  alertEl.dataset.type = type;
  alertEl.querySelector("use").setAttribute("href", spriteHref(ALERT_ICONS[type]));
  alertEl.querySelector(".form-alert__text").textContent = message;
  alertEl.classList.add("is-visible");
}

export function hideAlert(alertEl) {
  alertEl.classList.remove("is-visible");
}

/* ---------- Buttons ---------- */

export function setBusy(button, busy, busyLabel) {
  const label = button.querySelector(".btn__label");
  if (label) {
    if (busy) {
      button.dataset.label = label.textContent;
      if (busyLabel) label.textContent = busyLabel;
    } else if (button.dataset.label) {
      label.textContent = button.dataset.label;
    }
  }
  button.disabled = busy;
  button.classList.toggle("is-loading", busy);
  button.setAttribute("aria-busy", String(busy));
}

/** A small floating notice (kept outside the card so the card matches the design). */
export function showPageNotice(message) {
  const notice = element("div", "page-notice");
  notice.setAttribute("role", "status");
  const close = element("button", "page-notice__close", "×");
  close.type = "button";
  close.setAttribute("aria-label", "Dismiss notice");
  close.addEventListener("click", () => notice.remove());
  notice.append(element("span", "", message), close);
  document.body.append(notice);
}

/* ---------- Formatting ---------- */

/** Up to two initials for an avatar, e.g. "Asha Rao" -> "AR". */
export function initials(name) {
  const parts = String(name ?? "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = Array.from(parts[0])[0];
  const last = parts.length > 1 ? Array.from(parts[parts.length - 1])[0] : "";
  return (first + last).toUpperCase();
}

/** Formats an ISO timestamp for display in the user's locale. */
export function formatDateTime(iso) {
  if (!iso) return "—";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? "—"
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** A `tel:` URI containing only digits and an optional leading + (never raw user text). */
export function telHref(phone) {
  return `tel:${String(phone).replace(/[^\d+]/g, "")}`;
}
