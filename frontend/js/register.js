import { api, ApiError } from "./api.js";
import { consentVersion, defaultCountry } from "./config.js";
import { isConfigured, logout } from "./auth.js";
import { fillCountrySelect, toE164, validateNationalNumber } from "./phone.js";
import { createPhoneFlow } from "./phone-flow.js";
import {
  clearErrorOnInput,
  hideAlert,
  showAlert,
  showPageNotice,
  showServerFieldErrors,
  validateFields,
  validateFullName,
} from "./ui.js";

const DASHBOARD_URL = "dashboard.html";

const $ = (id) => document.getElementById(id);
const nameInput = $("name");
const country = $("country");
const phoneInput = $("phone");
const consent = $("consent");
const alertBox = $("form-alert");

fillCountrySelect(country, defaultCountry);
clearErrorOnInput(nameInput, phoneInput);
consent.addEventListener("change", () => {
  if (consent.checked) {
    $("consent-error").classList.remove("is-visible");
    consent.removeAttribute("aria-invalid");
  }
});

const stayBusy = () => new Promise(() => {});
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Firebase has verified the phone number. The profile is created by the Java backend from the verified token; the
 * browser sends only the name and the consent it collected, never a role, uid or phone number.
 */
async function onVerified(user) {
  try {
    await api.register(await user.getIdToken(), {
      fullName: nameInput.value.trim(),
      acceptedTerms: consent.checked,
      consentVersion,
    });
  } catch (error) {
    if (error instanceof ApiError && error.retryable) {
      flow.allowRetry(user);
      showAlert(alertBox, "error", `${error.message} Press the button to try again.`);
      return;
    }
    await logout().catch(() => {});
    flow.reset();
    if (error instanceof ApiError && error.code === "ACCOUNT_EXISTS") {
      showAlert(alertBox, "error", "An account already exists for this phone number. Please log in instead.");
    } else if (error instanceof ApiError && showServerFieldErrors(error.details, { fullName: nameInput })) {
      nameInput.focus();
    } else {
      showAlert(alertBox, "error", error instanceof ApiError ? error.message : "We couldn't create your account. Please try again.");
    }
    return;
  }
  showAlert(alertBox, "success", "Account created! Taking you to your dashboard…");
  await sleep(700);
  window.location.assign(DASHBOARD_URL);
  await stayBusy();
}

const flow = createPhoneFlow(
  {
    phoneForm: $("phone-form"),
    codeForm: $("code-form"),
    sendButton: $("send-btn"),
    codeInput: $("code"),
    verifyButton: $("verify-btn"),
    resendButton: $("resend-btn"),
    changeButton: $("change-btn"),
    sentTo: $("sent-to"),
    alertBox,
    recaptchaId: "recaptcha-container",
  },
  onVerified,
);

$("phone-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (flow.busy) return;
  hideAlert(alertBox);

  const valid = validateFields([
    [nameInput, (value) => validateFullName(value)],
    [phoneInput, (value) => validateNationalNumber(country.value, value)],
  ]);
  if (!consent.checked) {
    const box = $("consent-error");
    box.firstElementChild.textContent = "Please accept the Terms and Privacy Policy to continue.";
    box.classList.add("is-visible");
    consent.setAttribute("aria-invalid", "true");
    if (valid) consent.focus();
  }
  if (!valid || !consent.checked) return;

  flow.sendCode(toE164(country.value, phoneInput.value));
});

if (!isConfigured()) {
  showPageNotice("Setup needed: add your Firebase web app settings to frontend/js/config.js to enable registration.");
}
