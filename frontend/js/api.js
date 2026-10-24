/**
 * Client for the SafeHer Spring Boot API.
 * Every call sends the Firebase ID token as a Bearer token; the backend verifies it with the Firebase Admin SDK
 * before doing anything else, so nothing here is trusted by the server.
 */

import { apiBaseUrl } from "./config.js";

const REQUEST_TIMEOUT_MS = 15000;
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export class ApiError extends Error {
  constructor(status, code, message, details = null, retryAfter = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryAfter = retryAfter;
  }

  /** True for failures where trying again makes sense (network, timeout, server unavailable). */
  get retryable() {
    return this.status === 0 || this.status === 503 || this.status === 429;
  }
}

/** ID tokens must never travel over plain HTTP, except to a server on this machine. */
function assertSecureTransport(url) {
  const { protocol, hostname } = new URL(url);
  if (protocol !== "https:" && !LOCAL_HOSTS.has(hostname)) {
    throw new ApiError(0, "INSECURE_TRANSPORT", "The API must be served over HTTPS. Update apiBaseUrl in js/config.js.");
  }
}

async function request(path, { method = "GET", token, body, text = false, headers: extraHeaders = {} } = {}) {
  const url = `${apiBaseUrl.replace(/\/+$/, "")}${path}`;
  assertSecureTransport(url);

  const headers = { Accept: text ? "text/csv" : "application/json", ...extraHeaders };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  let response;
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      cache: "no-store",
    });
  } catch (error) {
    const timedOut = error?.name === "AbortError";
    throw new ApiError(
      0,
      timedOut ? "TIMEOUT" : "NETWORK",
      timedOut
        ? "The SafeHer server took too long to respond. Please try again."
        : "We couldn't reach the SafeHer server. Check your connection and try again.",
    );
  } finally {
    clearTimeout(timer);
  }

  if (text && response.ok) {
    return response.text();
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload?.code ?? "HTTP_ERROR",
      payload?.message ?? `The server responded with an error (${response.status}).`,
      payload?.details ?? null,
      Number(response.headers.get("Retry-After")) || null,
    );
  }
  return payload;
}

const get = (path) => (token) => request(path, { token });

/**
 * Opens the administrator live stream (Server-Sent Events) with a bearer token. Returns the raw Response so the caller
 * can read the body incrementally; it rejects with an ApiError for 401/403/429 and network failures.
 */
export async function openAdminStream(token, signal) {
  const url = `${apiBaseUrl.replace(/\/+$/, "")}/admin/sos/stream`;
  assertSecureTransport(url);
  let response;
  try {
    response = await fetch(url, { headers: { Accept: "text/event-stream", Authorization: `Bearer ${token}` }, signal, cache: "no-store" });
  } catch (error) {
    if (error?.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK", "We couldn't reach the SafeHer server.");
  }
  if (!response.ok || !response.body) {
    const payload = await response.json().catch(() => null);
    throw new ApiError(response.status, payload?.code ?? "HTTP_ERROR", payload?.message ?? `The live stream is not available (${response.status}).`,
      null, Number(response.headers.get("Retry-After")) || null);
  }
  return response;
}

export const api = {
  /* ----- session ----- */
  /** Confirms the phone sign-in with the backend and returns the profile (404 NOT_REGISTERED when there is none). */
  verify: (token) => request("/auth/verify", { method: "POST", token }),
  register: (token, body) => request("/auth/register", { method: "POST", token, body }),
  /** Revokes every Firebase session of the user (all devices). */
  logoutEverywhere: (token) => request("/auth/logout-all", { method: "POST", token }),

  /* ----- profile ----- */
  me: get("/users/me"),
  updateMe: (token, fullName) => request("/users/me", { method: "PATCH", token, body: { fullName } }),
  /** Records that the user finished an SOS practice drill (a profile flag only: no alert, nothing sent). */
  markSosDrill: (token) => request("/users/me/sos-drill", { method: "POST", token }),

  /* ----- emergency contacts ----- */
  listContacts: get("/emergency-contacts"),
  addContact: (token, contact) => request("/emergency-contacts", { method: "POST", token, body: contact }),
  updateContact: (token, id, changes) =>
    request(`/emergency-contacts/${encodeURIComponent(id)}`, { method: "PATCH", token, body: changes }),
  deleteContact: (token, id) => request(`/emergency-contacts/${encodeURIComponent(id)}`, { method: "DELETE", token }),
  /** Texts a 6-digit code to the contact (TEST mode: the response carries the code because nothing is sent). */
  startContactVerification: (token, id) =>
    request(`/emergency-contacts/${encodeURIComponent(id)}/verification`, { method: "POST", token }),
  confirmContactVerification: (token, id, code) =>
    request(`/emergency-contacts/${encodeURIComponent(id)}/verification/confirm`, { method: "POST", token, body: { code } }),

  /* ----- SOS alerts ----- */
  /** TEST or REAL mode, the contact limit and a plain-language note about what SafeHer does. */
  sosInfo: get("/sos/info"),
  /**
   * @param {{ location?: object|null, locationStatus?: string, idempotencyKey: string }} options
   * The same idempotencyKey makes a retry or double click return the SAME alert instead of creating a second one.
   */
  createAlert: (token, { location = null, locationStatus, idempotencyKey }) => {
    const body = {};
    if (location) body.location = location;
    else if (locationStatus) body.locationStatus = locationStatus;
    return request("/sos", { method: "POST", token, body, headers: { "Idempotency-Key": idempotencyKey } });
  },
  listAlerts: get("/sos/me"),
  getAlert: (token, id) => request(`/sos/${encodeURIComponent(id)}/me`, { token }),
  /** Records the cancellation request. It does not recall messages that were already sent. */
  cancelAlert: (token, id) => request(`/sos/${encodeURIComponent(id)}/cancel`, { method: "POST", token }),

  /* ----- incident reports ----- */
  createReport: (token, report) => request("/incident-reports", { method: "POST", token, body: report }),
  listReports: get("/incident-reports/me"),

  /* ----- administrator (the server enforces access; these calls simply fail with 403 for everyone else) ----- */
  admin: {
    dashboard: (token, utcOffsetMinutes = 0) => request(`/admin/dashboard?utcOffsetMinutes=${utcOffsetMinutes}`, { token }),
    users: (token, query = {}) => request(`/admin/users${qs(query)}`, { token }),
    user: (token, uid) => request(`/admin/users/${encodeURIComponent(uid)}`, { token }),
    setUserStatus: (token, uid, status, reason) =>
      request(`/admin/users/${encodeURIComponent(uid)}/status`, { method: "PATCH", token, body: { status, reason } }),
    alerts: (token, query = {}) => request(`/admin/sos${qs(query)}`, { token }),
    alert: (token, id) => request(`/admin/sos/${encodeURIComponent(id)}`, { token }),
    alertDeliveries: (token, id) => request(`/admin/sos/${encodeURIComponent(id)}/deliveries`, { token }),
    /** status: ACKNOWLEDGED, or RESOLVED with a note. Nothing is sent to anyone. */
    setAlertStatus: (token, id, status, note) =>
      request(`/admin/sos/${encodeURIComponent(id)}/status`, { method: "PATCH", token, body: note ? { status, note } : { status } }),
    notificationOverview: (token) => request("/admin/notifications/overview", { token }),
    devices: (token) => request("/admin/devices", { token }),
    registerDevice: (token, deviceToken, label) =>
      request("/admin/devices", { method: "POST", token, body: { token: deviceToken, label } }),
    removeDevice: (token, id) => request(`/admin/devices/${encodeURIComponent(id)}`, { method: "DELETE", token }),
    alertReport: (token, query = {}) => request(`/admin/reports/alerts${qs(query)}`, { token }),
    /** The CSV text of the same summary (the server records the export in the audit log). */
    alertReportCsv: (token, query = {}) => request(`/admin/reports/alerts.csv${qs(query)}`, { token, text: true }),
    incidentReports: (token) => request("/admin/incident-reports", { token }),
    resolveIncidentReport: (token, id, note) =>
      request(`/admin/incident-reports/${encodeURIComponent(id)}/resolve`, { method: "PATCH", token, body: { note } }),
    auditLogs: (token, query = {}) => request(`/admin/audit-logs${qs(query)}`, { token }),
    settings: (token) => request("/admin/settings", { token }),
  },
};

/** Builds "?a=1&b=2" from an object, skipping empty values. */
function qs(query) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}
