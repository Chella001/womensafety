import { api, ApiError } from "./api.js";
import { defaultCountry } from "./config.js";
import { describeAuthError, isConfigured, logout } from "./auth.js";
import { fillCountrySelect, toE164, validateNationalNumber } from "./phone.js";
import { createPhoneFlow } from "./phone-flow.js";
import { clearErrorOnInput, hideAlert, showAlert, showPageNotice, validateFields } from "./ui.js";

const DASHBOARD_URL = "dashboard.html";

const $ = (id) => document.getElementById(id);
const country = $("country");
const phoneInput = $("phone");
const alertBox = $("form-alert");

fillCountrySelect(country, defaultCountry);
clearErrorOnInput(phoneInput);

/** Never resolves: the page is navigating away, so the flow must stay "busy" (no duplicate submits). */
const stayBusy = () => new Promise(() => {});
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function describeVerifyFailure(error) {
  if (error instanceof ApiError) {
    if (error.code === "NOT_REGISTERED") return "No SafeHer account exists for this phone number. Please create an account first.";
    return error.message;
  }
  // Not a SafeHer server answer (for example Firebase could not issue an ID token): show what it was.
  return describeAuthError(error);
}

/**
 * Firebase has verified the phone number. The login only counts once the Java backend has verified the ID token and
 * confirmed that this phone number has an active SafeHer account.
 */
async function onVerified(user) {
  try {
    await api.verify(await user.getIdToken());
  } catch (error) {
    if (error instanceof ApiError && error.retryable) {
      flow.allowRetry(user); // keep the Firebase session; the code is spent, so only the server step is retried
      showAlert(alertBox, "error", `${error.message} Press the button to try again.`);
      return;
    }
    await logout().catch(() => {}); // never leave a half-authenticated browser session behind
    flow.reset();
    showAlert(alertBox, "error", describeVerifyFailure(error));
    return;
  }
  showAlert(alertBox, "success", "Welcome back! Taking you to your dashboard…");
  await sleep(600);
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
  if (!validateFields([[phoneInput, (value) => validateNationalNumber(country.value, value)]])) return;
  flow.sendCode(toE164(country.value, phoneInput.value));
});

const reason = new URLSearchParams(window.location.search).get("reason");
const REASONS = {
  suspended: ["error", "This account has been suspended. Please contact SafeHer support."],
  expired: ["info", "Your session has ended. Please log in again."],
};
if (REASONS[reason]) showAlert(alertBox, ...REASONS[reason]);

if (!isConfigured()) {
  showPageNotice("Setup needed: add your Firebase web app settings to frontend/js/config.js to enable login.");
}
