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

/**
 * Public helplines (India), shown on the dashboard and on the public pages. This is the ONLY place the numbers are
 * written: every page renders them from this list. They are answered by public services, not by SafeHer — tapping one
 * opens the phone's dialer and SafeHer is not part of the call. Availability and routing can differ between states
 * (in several states 100 and 1091 are now answered through 112).
 */
export const helplines = Object.freeze([
  { number: "112", name: "Emergency response", description: "Police, fire and ambulance. The single emergency number across India — call this first if you are in danger." },
  { number: "1091", name: "Women helpline (police)", description: "Police helpline for women facing harassment, stalking or violence." },
  { number: "181", name: "Women helpline", description: "24×7 support for women affected by violence: counselling, legal aid, shelter and referral to One Stop Centres." },
  { number: "100", name: "Police", description: "Police control room." },
  { number: "1930", name: "Cyber crime helpline", description: "Report online fraud, harassment or blackmail. For money lost to fraud, call as soon as possible." },
]);

/** The number the SOS dialog offers to call: the first helpline (112 in India). */
export const emergencyNumber = helplines[0].number;

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
