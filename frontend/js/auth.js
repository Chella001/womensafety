/**
 * Firebase Authentication (phone / SMS-OTP) for SafeHer. This is the ONLY file that initialises Firebase.
 *
 * The SDK is loaded lazily from Google's CDN so the pages still render (and can explain the problem) when the network
 * or the Firebase config is unavailable. The one-time code is checked by Firebase itself (`confirm(code)`); SafeHer
 * never sees, stores or compares it. ID tokens are handed straight to the API client and are never written to
 * localStorage/sessionStorage by this code (Firebase keeps its own session in IndexedDB).
 */

import { disableRecaptchaForTestNumbers, firebaseConfig } from "./config.js";

const SDK_VERSION = "10.14.1";
const CAPTCHA_TIMEOUT_MS = 120000;
const SDK_BASE = `https://www.gstatic.com/firebasejs/${SDK_VERSION}`;

export class AuthError extends Error {
  constructor(code, message) {
    super(message ?? code);
    this.name = "AuthError";
    this.code = code;
  }
}

export function isConfigured() {
  const { apiKey, projectId, appId } = firebaseConfig;
  return [apiKey, projectId, appId].every((value) => value && !value.startsWith("YOUR_"));
}

let sdkPromise = null;

function loadSdk() {
  if (!sdkPromise) {
    sdkPromise = (async () => {
      if (!isConfigured()) throw new AuthError("auth/not-configured");
      let appModule;
      let authModule;
      try {
        [appModule, authModule] = await Promise.all([
          import(`${SDK_BASE}/firebase-app.js`),
          import(`${SDK_BASE}/firebase-auth.js`),
        ]);
      } catch {
        throw new AuthError("auth/sdk-load-failed");
      }
      const app = appModule.initializeApp(firebaseConfig);
      const auth = authModule.getAuth(app);
      if (disableRecaptchaForTestNumbers) {
        auth.settings.appVerificationDisabledForTesting = true; // honoured by Firebase for test numbers only
      }
      return { auth, sdk: authModule };
    })();
    sdkPromise.catch(() => {
      sdkPromise = null; // allow a retry after a transient failure
    });
  }
  return sdkPromise;
}

/* ---------- phone verification ---------- */

let confirmation = null;
let recaptcha = null;
let generation = 0; // bumped whenever a pending verification is cancelled, so late answers can be ignored

function discardRecaptcha() {
  try {
    recaptcha?.clear();
  } catch {
    /* already cleared */
  }
  recaptcha = null;
}

/**
 * Asks Firebase to text a verification code to `e164`. For a fictional test number configured in the Firebase console
 * no SMS is sent and the fixed code from the console is the right answer.
 * @param {string} e164 the phone number in E.164 form, e.g. "+919876543210"
 * @param {string} containerId id of an empty element used for the (invisible) reCAPTCHA check
 */
export async function sendVerificationCode(e164, containerId) {
  const { auth, sdk } = await loadSdk();
  discardRecaptcha();
  const mine = ++generation;
  const container = document.getElementById(containerId);
  container.replaceChildren();
  recaptcha = new sdk.RecaptchaVerifier(auth, container, { size: "invisible" });
  let result;
  try {
    // reCAPTCHA may show an image challenge; if the user abandons it Firebase never answers, so give up after a while.
    result = await Promise.race([
      sdk.signInWithPhoneNumber(auth, e164, recaptcha),
      new Promise((_, reject) => {
        window.setTimeout(() => reject(new AuthError("auth/captcha-timeout")), CAPTCHA_TIMEOUT_MS);
      }),
    ]);
  } catch (error) {
    if (mine === generation) {
      confirmation = null;
      discardRecaptcha();
    }
    throw error;
  }
  if (mine !== generation) {
    throw new AuthError("auth/cancelled"); // the user pressed "Change number" while this was in flight
  }
  confirmation = result;
}

/** Completes sign-in with the code the user typed. Resolves with the Firebase user. */
export async function confirmVerificationCode(code) {
  if (!confirmation) throw new AuthError("auth/session-expired");
  const credential = await confirmation.confirm(code);
  confirmation = null;
  discardRecaptcha();
  return credential.user;
}

/** Forgets a pending verification (used by "Change number"). */
export function cancelVerification() {
  generation += 1;
  confirmation = null;
  discardRecaptcha();
}

export function hasPendingVerification() {
  return confirmation !== null;
}

/* ---------- session ---------- */

export async function logout() {
  if (!isConfigured()) return;
  const { auth, sdk } = await loadSdk();
  await sdk.signOut(auth);
}

/** Resolves with the restored Firebase user (or null) once the SDK has loaded its session. */
export async function waitForUser() {
  const { auth } = await loadSdk();
  await auth.authStateReady();
  return auth.currentUser;
}

const MESSAGES = {
  "auth/not-configured": "Firebase isn't configured yet. Add your web app settings to frontend/js/config.js (see the README).",
  "auth/sdk-load-failed": "We couldn't load the sign-in service. Check your connection and try again.",
  "auth/invalid-phone-number": "That phone number isn't valid. Check the country and the number.",
  "auth/missing-phone-number": "Please enter your phone number.",
  "auth/invalid-verification-code": "That code isn't correct. Check it and try again.",
  "auth/missing-verification-code": "Please enter the 6-digit code.",
  "auth/code-expired": "That code has expired. Request a new one.",
  "auth/session-expired": "This verification has expired. Request a new code.",
  "auth/invalid-verification-id": "This verification has expired. Request a new code.",
  "auth/too-many-requests": "Too many attempts. Please wait a while before trying again.",
  "auth/quota-exceeded": "The daily SMS limit of this Firebase project has been reached. Try again tomorrow or use a configured test number.",
  "auth/captcha-timeout": "The security check wasn't completed. Please try again.",
  "auth/captcha-check-failed": "The security check failed. Reload the page and try again.",
  "auth/invalid-app-credential": "The security check failed. Reload the page and try again.",
  "auth/user-disabled": "This account has been disabled. Please contact SafeHer support.",
  "auth/network-request-failed": "Network error. Check your connection and try again.",
  "auth/unauthorized-domain":
    "This web address isn't authorised in Firebase. Open the site as http://localhost:5500 (not 127.0.0.1).",
  "auth/operation-not-allowed":
    "Phone sign-in isn't available yet. In the Firebase console enable Authentication → Sign-in method → Phone, and allow your country under SMS region policy.",
  "auth/billing-not-enabled":
    "Firebase needs a billing plan to send real SMS. During development use a fictional test number from the Firebase console.",
  "auth/operation-not-supported-in-this-environment": "Open the site over http://localhost, not by double-clicking the file.",
  "auth/internal-error":
    "Firebase could not complete the sign-in (internal error). Reload the page (Ctrl+F5) and try again; if it repeats, check that the API key in js/config.js has no HTTP-referrer restriction that excludes localhost.",
  "auth/timeout": "The security check took too long to load. Check your connection and try again.",
  "auth/argument-error": "The security check could not start. Reload the page (Ctrl+F5) and try again.",
  "auth/missing-app-credential": "The security check was not completed. Reload the page and try again.",
  "auth/app-not-authorized": "This website is not allowed to use this Firebase project. Check the settings in js/config.js.",
  "auth/invalid-api-key": "The Firebase API key in js/config.js is not valid.",
  "auth/web-storage-unsupported": "This browser blocks the storage sign-in needs. Allow site data / cookies for localhost, or leave private mode.",
  "auth/admin-restricted-operation": "Sign-in is restricted for this Firebase project.",
  "auth/error-code:-39": "Firebase blocked this request as suspicious (SMS fraud protection). Wait a while and try again, or use a configured test number.",
};

/** Maps an error to user-friendly text. Unknown errors keep their code visible so the real cause can be found. */
export function describeAuthError(error) {
  if (MESSAGES[error?.code]) return MESSAGES[error.code];
  console.error("SafeHer sign-in error:", error?.code, error?.message, error); // eslint-disable-line no-console
  const code = error?.code ?? error?.name ?? "unknown";
  return `Something went wrong while signing you in (${code}). Please try again; if it keeps happening, note this code.`;
}
