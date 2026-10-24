/**
 * Administrator login. It reuses the normal phone + OTP flow, then asks the Java backend who this is. The page only
 * moves on when the BACKEND says the role is ADMIN (decided by its server-side allow-list); everyone else is signed out
 * again and told they have no admin access. Nothing here can grant or fake admin rights.
 */

import { api, ApiError } from "./api.js";
import { defaultCountry } from "./config.js";
import { isConfigured, logout } from "./auth.js";
import { fillCountrySelect, toE164, validateNationalNumber } from "./phone.js";
import { createPhoneFlow } from "./phone-flow.js";
import { clearErrorOnInput, hideAlert, showAlert, showPageNotice, validateFields } from "./ui.js";

const CONSOLE_URL = "index.html";

const $ = (id) => document.getElementById(id);
const country = $("country");
const phoneInput = $("phone");
const alertBox = $("form-alert");

fillCountrySelect(country, defaultCountry);
clearErrorOnInput(phoneInput);

const stayBusy = () => new Promise(() => {});
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function onVerified(user) {
  let profile;
  try {
    profile = await api.verify(await user.getIdToken());
  } catch (error) {
    if (error instanceof ApiError && error.retryable) {
      flow.allowRetry(user);
      showAlert(alertBox, "error", `${error.message} Press the button to try again.`);
      return;
    }
    await logout().catch(() => {});
    flow.reset();
    showAlert(alertBox, "error", error instanceof ApiError && error.code !== "NOT_REGISTERED"
      ? error.message
      : "This phone number has no SafeHer account. The administrator must first register on the normal site.");
    return;
  }

  if (profile.role !== "ADMIN") {
    await logout().catch(() => {});
    flow.reset();
    showAlert(alertBox, "error", "This account does not have administrator access.");
    return;
  }

  showAlert(alertBox, "success", "Welcome. Opening the admin console…");
  await sleep(500);
  window.location.assign(CONSOLE_URL);
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
  expired: ["info", "Your session has ended. Please log in again."],
  denied: ["error", "That account does not have administrator access."],
};
if (REASONS[reason]) showAlert(alertBox, ...REASONS[reason]);

if (!isConfigured()) {
  showPageNotice("Setup needed: add your Firebase web app settings to frontend/js/config.js.");
}
