// TEST DOUBLE of the SafeHer Spring Boot API: the user-facing endpoints the dashboard and auth pages call, with the
// same JSON shapes and error codes as the Java DTOs. Runs in the test process so tests can inspect `state` directly.
// Token: "Bearer fake-<uid>" (issued by the fake Firebase SDK).
import { createServer } from "node:http";

const now = () => new Date().toISOString();
let seq = 0;
const nextId = (prefix) => `${prefix}${(++seq).toString(16).padStart(6, "0")}`;
const mask = (phone) => phone.slice(0, 3) + "•".repeat(Math.max(0, phone.length - 6)) + phone.slice(-3);

export const state = {
  users: new Map(), // uid -> profile
  contacts: new Map(), // uid -> [contact]
  alerts: [], // { ...alert, uid, polls }
  requests: [], // { method, path, body, headers }
  failures: [], // { method, path, status } one-shot injected failures
};

export function reset() {
  state.users.clear();
  state.contacts.clear();
  state.alerts.length = 0;
  state.requests.length = 0;
  state.failures.length = 0;
}

/** Makes the next matching request fail with `status` (503 by default). */
export function failNext(method, pathPrefix, status = 503) {
  state.failures.push({ method, pathPrefix, status });
}

export function seedUser(uid, phone, fullName, extra = {}) {
  state.users.set(uid, {
    uid, fullName, phoneNumber: phone, phoneVerified: true, role: "USER", accountStatus: "ACTIVE",
    createdAt: now(), lastLoginAt: now(), consentVersion: "2026-10-v1", sosDrillCompletedAt: null, ...extra,
  });
  if (!state.contacts.has(uid)) state.contacts.set(uid, []);
  return state.users.get(uid);
}

export function seedContact(uid, { name, phoneNumber, verified = false, notifyConsent = false }) {
  const contact = {
    id: nextId("c"), name, phoneNumber, relationship: "", createdAt: now(), updatedAt: now(),
    verified, verifiedAt: verified ? now() : null, notifyConsent, eligible: verified && notifyConsent, verificationPending: false,
  };
  state.contacts.get(uid).push(contact);
  return contact;
}

const json = (status, body, headers = {}) => ({ status, body, headers });
const error = (status, code, message, details) => json(status, { timestamp: now(), status, code, message, ...(details ? { details } : {}) });
const invalid = (field, message) => error(400, "VALIDATION_FAILED", "The request contains invalid data.", { [field]: message });

function contactView(c) {
  return { ...c, eligible: c.verified && c.notifyConsent, code: undefined };
}

const MESSAGES = {
  QUEUED: "Notifications are being processed. This can take a moment.",
  PROCESSING: "Notifications are being processed. This can take a moment.",
  NO_RECIPIENTS: "No verified emergency contact was available, so nobody was messaged.",
  SIMULATED: "TEST MODE: 1 message(s) were simulated. Nothing was actually sent.",
};

function alertView(a) {
  return {
    id: a.id, uid: a.uid, status: a.status, createdAt: a.createdAt, cancellationRequestedAt: a.cancellationRequestedAt,
    location: a.location, locationStatus: a.locationStatus, testMode: true,
    notification: { state: a.state, total: 0, queued: 0, submitted: 0, delivered: 0, simulated: a.state === "SIMULATED" ? 1 : 0, failed: 0, skipped: 0, updatedAt: now(), message: MESSAGES[a.state] },
    cancellationNote: a.status === "CANCELLED" ? "Cancellation recorded. Messages that were already sent cannot be recalled; messages not yet sent were stopped." : null,
  };
}

function route(method, path, body, uid, headers) {
  const p = path.replace(/^\/api/, "");
  if (!uid) return error(401, "UNAUTHENTICATED", "Authentication is required. Sign in again and retry.");
  const user = state.users.get(uid);

  if (method === "POST" && p === "/auth/register") {
    if (user) return error(409, "ACCOUNT_EXISTS", "An account already exists for this phone number. Please log in instead.");
    const name = String(body?.fullName ?? "").trim().replace(/\s+/g, " ");
    if (name.length < 2) return invalid("fullName", "Name must be 2 to 60 characters");
    if (body?.acceptedTerms !== true) return invalid("acceptedTerms", "You must accept the Terms and Privacy Policy");
    return json(201, seedUser(uid, `+${uid.slice(1)}`, name));
  }
  if (method === "POST" && p === "/auth/verify") {
    return user ? json(200, user) : error(404, "NOT_REGISTERED", "No SafeHer account exists for this phone number yet. Please register first.");
  }
  if (!user) return error(403, "REGISTRATION_REQUIRED", "Please complete registration before using SafeHer.");
  if (method === "POST" && p === "/auth/logout-all") return json(204, null);

  if (p === "/users/me" && method === "GET") return json(200, user);
  if (p === "/users/me/sos-drill" && method === "POST") {
    user.sosDrillCompletedAt = now();
    return json(200, user);
  }

  const contacts = state.contacts.get(uid);
  if (p === "/emergency-contacts" && method === "GET") return json(200, contacts.map(contactView));
  if (p === "/emergency-contacts" && method === "POST") {
    const digits = String(body?.phoneNumber ?? "").replace(/\D/g, "");
    if (digits.length < 7) return invalid("phoneNumber", "Enter a valid phone number including the country code.");
    const phone = String(body.phoneNumber).trim().startsWith("+") ? `+${digits}` : `+91${digits.replace(/^0+/, "")}`;
    if (contacts.some((c) => c.phoneNumber === phone)) return error(409, "DUPLICATE_CONTACT", "You already have an emergency contact with that number.");
    const contact = seedContact(uid, { name: String(body.name).trim(), phoneNumber: phone, notifyConsent: body.notifyConsent === true });
    contact.relationship = String(body.relationship ?? "").trim();
    return json(201, contactView(contact));
  }
  let m = p.match(/^\/emergency-contacts\/([\w-]+)(\/verification(?:\/confirm)?)?$/);
  if (m) {
    const contact = contacts.find((c) => c.id === m[1]);
    if (!contact) return error(404, "NOT_FOUND", "That emergency contact does not exist.");
    if (!m[2] && method === "DELETE") {
      contacts.splice(contacts.indexOf(contact), 1);
      return json(204, null);
    }
    if (!m[2] && method === "PATCH") {
      if (typeof body.name === "string") contact.name = body.name.trim();
      if (typeof body.relationship === "string") contact.relationship = body.relationship.trim();
      if (typeof body.notifyConsent === "boolean") contact.notifyConsent = body.notifyConsent;
      return json(200, contactView(contact));
    }
    if (m[2] === "/verification" && method === "POST") {
      if (!contact.notifyConsent) return invalid("notifyConsent", "Confirm that this person agreed to receive SafeHer emergency messages before sending a code.");
      contact.code = "246810";
      contact.verificationPending = true;
      return json(200, { expiresAt: new Date(Date.now() + 600000).toISOString(), testMode: true, testModeCode: "246810", message: "TEST MODE: no SMS was sent. Use the code shown here." });
    }
    if (m[2] === "/verification/confirm" && method === "POST") {
      if (body?.code !== contact.code) return invalid("code", "That code isn't correct.");
      Object.assign(contact, { verified: true, verifiedAt: now(), verificationPending: false, code: undefined });
      return json(200, contactView(contact));
    }
  }

  if (p === "/sos/info" && method === "GET") {
    return json(200, { mode: "TEST", testMode: true, maxContacts: 5, note: "TEST MODE: SOS messages are only simulated, nothing is sent to anyone. SafeHer does not contact the police or any emergency service." });
  }
  if (p === "/sos" && method === "POST") {
    const key = headers["idempotency-key"];
    const existing = state.alerts.find((a) => a.uid === uid && a.key && a.key === key);
    if (existing) return json(200, alertView(existing), { "Idempotent-Replayed": "true" });
    const alert = {
      id: nextId("a"), uid, key, status: "ACTIVE", createdAt: now(), cancellationRequestedAt: null,
      location: body?.location ?? null, locationStatus: body?.location ? "AVAILABLE" : (body?.locationStatus ?? "NOT_REQUESTED"),
      state: "QUEUED", polls: 0,
    };
    state.alerts.unshift(alert);
    return json(201, alertView(alert));
  }
  if (p === "/sos/me" && method === "GET") return json(200, state.alerts.filter((a) => a.uid === uid).map(alertView));
  m = p.match(/^\/sos\/([\w-]+)\/(me|cancel)$/);
  if (m) {
    const alert = state.alerts.find((a) => a.id === m[1] && a.uid === uid);
    if (!alert) return error(404, "NOT_FOUND", "That alert does not exist.");
    if (m[2] === "me" && method === "GET") {
      alert.polls += 1;
      if (alert.polls >= 2 && ["QUEUED", "PROCESSING"].includes(alert.state)) {
        alert.state = contacts.some((c) => c.verified && c.notifyConsent) ? "SIMULATED" : "NO_RECIPIENTS";
      } else if (alert.state === "QUEUED") {
        alert.state = "PROCESSING";
      }
      return json(200, alertView(alert));
    }
    if (m[2] === "cancel" && method === "POST") {
      if (alert.status !== "ACTIVE") return error(409, "ALERT_NOT_ACTIVE", `This alert is already ${alert.status.toLowerCase()}.`);
      alert.status = "CANCELLED";
      alert.cancellationRequestedAt = now();
      return json(200, alertView(alert));
    }
  }

  if (p === "/incident-reports/me" && method === "GET") return json(200, []);
  if (p.startsWith("/admin/")) return error(403, "FORBIDDEN", "You are not allowed to perform this action.");
  return error(404, "NOT_FOUND", "Not found");
}

export function startMockApi({ port, allowOrigin }) {
  const server = createServer((req, res) => {
    const cors = {
      "Access-Control-Allow-Origin": allowOrigin,
      "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, Idempotency-Key",
      "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
      "Access-Control-Expose-Headers": "Retry-After, Idempotent-Replayed",
    };
    if (req.method === "OPTIONS") {
      res.writeHead(204, cors).end();
      return;
    }
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      const path = new URL(req.url, "http://x").pathname;
      let body = null;
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        body = null;
      }
      state.requests.push({ method: req.method, path, raw, headers: req.headers });
      const send = (r) => {
        res.writeHead(r.status, { "Content-Type": "application/json", ...cors, ...r.headers });
        res.end(r.status === 204 || r.body === null ? "" : JSON.stringify(r.body));
      };
      const failure = state.failures.findIndex((f) => f.method === req.method && path.startsWith(f.pathPrefix));
      if (failure >= 0) {
        const [f] = state.failures.splice(failure, 1);
        send(error(f.status, "DATA_STORE_UNAVAILABLE", "SafeHer is temporarily unavailable. Please try again shortly."));
        return;
      }
      const token = /^Bearer fake-(.+)$/.exec(req.headers.authorization ?? "");
      send(route(req.method, path, body, token ? token[1] : null, req.headers));
    });
  });
  return new Promise((ready) => server.listen(port, () => ready(server)));
}

export { mask };
