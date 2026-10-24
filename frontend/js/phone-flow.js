/**
 * The "enter your number → enter the code" interaction shared by login.html and register.html.
 *
 * It owns the two-step UI (phone form ↔ code form), the resend cooldown, loading states and duplicate-submit
 * protection. What happens after Firebase has verified the code is the page's business (`onVerified`).
 */

import { otpResendSeconds } from "./config.js";
import {
  cancelVerification,
  confirmVerificationCode,
  describeAuthError,
  sendVerificationCode,
} from "./auth.js";
import { maskE164 } from "./phone.js";
import { clearErrorOnInput, hideAlert, setBusy, setFieldError, showAlert, validateCode, validateFields } from "./ui.js";

/**
 * @param {object} els  the elements of the page (see login.html / register.html)
 * @param {(user: object) => Promise<void>} onVerified  called with the Firebase user once the code was accepted
 */
export function createPhoneFlow(els, onVerified) {
  const {
    phoneForm, codeForm, sendButton, codeInput, verifyButton, resendButton, changeButton,
    sentTo, alertBox, recaptchaId,
  } = els;

  let sending = false;
  let sendToken = 0; // each send gets a number so a superseded send cannot clobber a newer one
  let verifying = false;
  let pendingNumber = "";
  let cooldownTimer = null;

  function showStep(step) {
    phoneForm.hidden = step !== "phone";
    codeForm.hidden = step !== "code";
    if (step === "code") {
      codeInput.value = "";
      codeInput.focus();
    }
  }

  function stopCooldown() {
    window.clearInterval(cooldownTimer);
    cooldownTimer = null;
  }

  function startCooldown(seconds = otpResendSeconds) {
    stopCooldown();
    let remaining = seconds;
    const label = resendButton.querySelector(".btn__label");
    const render = () => {
      resendButton.disabled = remaining > 0;
      label.textContent = remaining > 0 ? `Resend code in ${remaining}s` : "Resend code";
    };
    render();
    cooldownTimer = window.setInterval(() => {
      remaining -= 1;
      render();
      if (remaining <= 0) stopCooldown();
    }, 1000);
  }

  /** Asks Firebase to send a code. Used for the first send and for "Resend". */
  async function sendCode(e164, { resend = false } = {}) {
    if (sending || verifying) return false;
    sending = true;
    const token = ++sendToken;
    hideAlert(alertBox);
    const button = resend ? resendButton : sendButton;
    setBusy(button, true, resend ? "Sending…" : "Sending code…");
    try {
      await sendVerificationCode(e164, recaptchaId);
      pendingNumber = e164;
      sentTo.textContent = maskE164(e164);
      showStep("code");
      startCooldown();
      showAlert(alertBox, "info", "Code requested. Enter the 6-digit code below.");
      return true;
    } catch (error) {
      if (error?.code !== "auth/cancelled") showAlert(alertBox, "error", describeAuthError(error));
      return false;
    } finally {
      if (token === sendToken) {
        sending = false;
        setBusy(button, false);
        if (resend && cooldownTimer) resendButton.disabled = true;
      }
    }
  }

  // Set when Firebase accepted the code but the SafeHer server step failed in a retryable way. The one-time code is
  // already used up, so the next press of the button repeats only the server step with the same Firebase user.
  let retryUser = null;

  codeForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (verifying || sending) return;
    if (!retryUser && !validateFields([[codeInput, validateCode]])) return;

    verifying = true;
    hideAlert(alertBox);
    setBusy(verifyButton, true, "Verifying…");
    resendButton.disabled = true;
    let verified = false;
    try {
      const user = retryUser ?? (await confirmVerificationCode(codeInput.value.replace(/\s+/g, "")));
      verified = true;
      retryUser = null;
      await onVerified(user);
    } catch (error) {
      if (!verified) {
        setFieldError(codeInput, describeAuthError(error));
        if (error?.code === "auth/code-expired" || error?.code === "auth/session-expired") {
          stopCooldown();
          resendButton.disabled = false;
          resendButton.querySelector(".btn__label").textContent = "Resend code";
        }
      } else {
        showAlert(alertBox, "error", describeAuthError(error));
      }
    } finally {
      verifying = false;
      setBusy(verifyButton, false);
      if (cooldownTimer === null && !codeForm.hidden) resendButton.disabled = false;
    }
  });

  resendButton.addEventListener("click", () => {
    if (pendingNumber) sendCode(pendingNumber, { resend: true });
  });

  changeButton.addEventListener("click", () => {
    if (verifying) return;
    sendToken += 1; // abandon any send still in flight
    sending = false;
    setBusy(sendButton, false);
    setBusy(resendButton, false);
    cancelVerification();
    stopCooldown();
    pendingNumber = "";
    hideAlert(alertBox);
    showStep("phone");
  });

  clearErrorOnInput(codeInput);
  codeInput.addEventListener("input", () => {
    codeInput.value = codeInput.value.replace(/[^\d ]/g, "");
  });

  return {
    sendCode,
    /** Lets the next press of the verify button repeat the server step for `user` (no new code needed). */
    allowRetry(user) {
      retryUser = user;
      codeInput.value = "";
      codeInput.disabled = true;
      stopCooldown();
      resendButton.disabled = true;
    },
    /** Returns to the phone step after a failed registration/login so the user can try another number. */
    reset() {
      retryUser = null;
      codeInput.disabled = false;
      cancelVerification();
      stopCooldown();
      pendingNumber = "";
      showStep("phone");
    },
    get busy() {
      return sending || verifying;
    },
  };
}
