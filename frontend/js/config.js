/**
 * Runtime configuration for the SafeHer frontend.
 *
 * The values below are PUBLIC identifiers, not secrets: a Firebase web config only
 * tells the SDK which project to talk to. Copy yours from
 *   Firebase console → Project settings → General → Your apps → Web app → SDK setup.
 *
 * NEVER put a service-account key or any other secret in this file.
 */

export const firebaseConfig = {
  apiKey: "AIzaSyA8cB4hs66di-rXHtNfWgHcRHQhNDSbQJI",
  authDomain: "womensafety-f7fae.firebaseapp.com",
  projectId: "womensafety-f7fae",
  appId: "1:669553826908:web:eabedcb894613c692fda99",
  messagingSenderId: "669553826908",
};

/**
 * Web Push certificate key (VAPID, public) for administrator push notifications. Generate it in Firebase console →
 * Project settings → Cloud Messaging → Web Push certificates. Leave empty to keep push off; the console says so.
 */
export const vapidKey = "";

/**
 * Base URL of the Spring Boot API. Must be HTTPS everywhere except localhost —
 * api.js refuses to send an ID token over plain HTTP to any other host.
 */
export const apiBaseUrl = "http://localhost:8080/api";

/** The national emergency number shown by the SOS dialog (112 in India). It opens the phone's dialer. */
export const emergencyNumber = "112";

/** Country pre-selected in the phone-number fields (ISO 3166-1 alpha-2, must exist in js/phone.js). */
export const defaultCountry = "IN";

/** Seconds a user must wait before asking Firebase to resend the verification code. */
export const otpResendSeconds = 30;

/** Must match `app.policy.consent-version` on the backend (CONSENT_VERSION). */
export const consentVersion = "2026-10-v1";

/**
 * DEVELOPMENT ONLY — keep `false` in production.
 *
 * `true` switches on Firebase's OFFICIAL testing switch (`auth.settings.appVerificationDisabledForTesting`), which
 * skips the reCAPTCHA check. Firebase honours it ONLY for the fictional test phone numbers you list in
 * Firebase console → Authentication → Sign-in method → Phone → "Phone numbers for testing"; a real number is
 * still rejected by Firebase. It is not a SafeHer bypass: the verification code is still checked by Firebase and
 * the resulting ID token is still verified by the Java backend. Use it for automated browser tests.
 */
export const disableRecaptchaForTestNumbers = false;
