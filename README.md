# SafeHer — women-safety web application

Phone-number sign-in (Firebase SMS OTP), a Java Spring Boot API, Cloud Firestore storage, a user dashboard and an
administrator console. Development-stage project: **it is not an emergency dispatch service** (see
[What is real and what is not](#what-is-real-and-what-is-not)).

```
index.html         forwards Live Server's root to frontend/home.html (on localhost)
frontend/          HTML + CSS + vanilla JS (static files, no build step)
  home.html help.html 404.html          public pages (no sign-in)
  login.html register.html dashboard.html terms.html privacy.html
  admin/login.html admin/index.html     administrator login and console
  css/                       styles.css animations.css dashboard.css admin.css helplines.css public.css
  js/                        config.js (incl. the helpline numbers) auth.js api.js ui.js phone.js phone-flow.js
                             login.js register.js dashboard.js emergency-contacts.js sos.js sos-drill.js
                             onboarding.js helplines.js nearby.js public.js reports.js location.js admin*.js
backend/           Spring Boot 3.5 · Java 17+ · Maven (wrapper included)
  src/main/java/com/womensafety/{config,controller,service,notification,repository,model,dto,security,validation,exception}
tests/ui/          browser UI suite (headless Edge, fake Firebase, mock API) — see tests/ui/README.md
firestore.rules    deny-all rules (only the backend touches Firestore)
```

## How it works

1. The browser signs in with **Firebase Phone Authentication** (`signInWithPhoneNumber` → code → `confirm(code)`).
   Firebase itself checks the one-time code; SafeHer never sees, stores or compares it.
2. The browser sends the resulting **ID token** to the API as `Authorization: Bearer <token>`.
3. The backend verifies it with the **Firebase Admin SDK** (including revocation), requires that it came from **phone**
   sign-in, and takes the uid and verified phone number **from the token**, never from the request body.
4. `POST /api/auth/register` creates `users/{uid}` (role `USER`, status `ACTIVE`, consent version + time) — once
   (atomic create; a second call is `409 ACCOUNT_EXISTS`). `POST /api/auth/verify` is the login check.
5. Every other endpoint also requires an existing, **non-suspended** profile.

Logout: signing out clears the browser's Firebase session. A Firebase ID token cannot be revoked individually and
stays valid until it expires (about an hour), so the account page also has **Sign out of all devices**
(`POST /api/auth/logout-all` → `revokeRefreshTokens`; the backend verifies tokens with the revocation check, so revoked
tokens are refused immediately). Suspending a user does the same and disables the Firebase account.

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 or newer (21 is fine) |
| Maven | not needed — `backend/mvnw.cmd` downloads it |
| Browser | any current Chrome / Edge / Firefox, opened on **`http://localhost:5500`** |

## Firebase setup (one time)

Project: **womensafety-f7fae** (already registered as a web app; its public config is in `frontend/js/config.js`).

### 1. Enable Phone sign-in
Firebase console → **Build → Authentication → Sign-in method → Phone → Enable → Save**.

Then check **Authentication → Settings → SMS region policy**. If it is "Allow" with an empty list (or blocks your
country), allow your country (India) — otherwise Firebase answers `operation-not-allowed` and the page tells you so.

### 2. Add fictional test phone numbers (no real SMS, no cost)
Same screen: **Sign-in method → Phone → "Phone numbers for testing" → Add phone number**.

* Enter a fictional number in E.164 form, e.g. `+91 99999 00001`, and a fixed 6-digit code of your choice.
* Firebase then **sends no SMS** for that number; the fixed code is the correct answer, and a genuine Firebase user
  and ID token are created, which the Java backend verifies like any other.
* Use only numbers **you** configure. Codes are fixed, so test numbers are for development only; Firebase allows a
  limited number of them (currently 10).
* Real numbers still send real SMS (subject to your plan/quota). **Real SMS delivery has not been tested in this project.**

### 3. reCAPTCHA
Phone sign-in on the web runs an invisible reCAPTCHA. Google may show an image challenge; a real user just solves it
(the page gives up after 2 minutes if it is abandoned). For **automated browser tests only** you may set
`disableRecaptchaForTestNumbers = true` in `frontend/js/config.js`. That is Firebase's official testing switch
(`auth.settings.appVerificationDisabledForTesting`); Firebase honours it **only for your fictional test numbers**,
the code is still checked by Firebase and the token by the backend. **Keep it `false` in production.**

### 4. Authorised domains
Open the site as **`http://localhost:5500`** (`localhost` is authorised by default; `127.0.0.1` is not). Add your real
domain under **Authentication → Settings → Authorized domains** when you deploy.

### 5. Firestore
* Database id: this project's database was created with the id **`default`** (not `(default)`), which is why
  `run-local.ps1` sets `FIREBASE_FIRESTORE_DATABASE_ID=default`.
* Rules: paste `firestore.rules` into **Firestore → Rules → Publish**. They deny every browser request; the Admin SDK
  bypasses rules, so **authorisation is enforced in Java** (see `security/` and `service/`).
* Data model:

| Path | Contents |
|---|---|
| `users/{uid}` | `fullName`, `phoneNumber` (E.164, from the token), `phoneVerified`, `role` (always `USER`), `accountStatus`, `createdAt`, `updatedAt`, `lastLoginAt`, `consentVersion`, `consentTimestamp`, `sosDrillCompletedAt` (set when the user finishes an SOS practice drill) |
| `users/{uid}/emergencyContacts/{id}` | `name`, `phoneNumber` (E.164), `relationship`, `createdAt`, `updatedAt` |
| `emergencyAlerts/{id}` | `uid`, `status` (ACTIVE/CANCELLED), timestamps, optional `location` (only if the user ticked "include"), `deliveryStatus`, `deliveryNote`, `contactsNotified`, `retainUntil` |
| `incidentReports/{id}` | `uid`, `category`, `description`, `status`, timestamps, resolution fields |
| `adminAuditLogs/{id}` | `type` (ADMIN_ACTION / SECURITY_EVENT), `action`, `actorUid`, `targetUid`, `details`, `createdAt` (**Firestore server time**) |

No passwords, OTPs or tokens are stored anywhere. **Retention:** alerts get `retainUntil` (`ALERT_RETENTION_DAYS`, 90 by
default). An hourly backend job deletes **resolved or cancelled** alerts once that date has passed, together with their
location, delivery records, history and notification job, and writes one `RETENTION_PURGE` audit entry per run (ids and
counts only). Alerts that are still **new or acknowledged are never purged** until an administrator closes them.
Turn it off with `RETENTION_PURGE_ENABLED=false`. Do **not** also create a Firestore TTL policy on `emergencyAlerts`:
TTL would delete open alerts too and leaves the subcollections behind.

### 6. Service-account key (backend)
Project settings → **Service accounts → Generate new private key**. Keep the JSON **outside the project** (this machine:
`C:\secrets\safeher-firebase.json`). It is git-ignored; never paste or commit it.

## Provision your administrator account (only you)

The administrator is **not stored in the database and cannot be requested by any client**. It is a server-side
allow-list of Firebase UIDs: environment variable **`ADMIN_UIDS`**. Empty (the default) means *nobody* is an admin.
Registration always stores role `USER`; `role` in a request body is ignored; the role string in a Firestore document is
ignored too.

1. Register in the app with your own phone number (a test number or your real one) and log in.
2. Copy your **Account ID**: dashboard → **Account** card (or Firebase console → Authentication → Users → *User UID*).
3. Set it on the machine that runs the backend, then restart the backend:
   ```powershell
   $env:ADMIN_UIDS = "paste-your-uid-here"      # exactly one uid = exactly one administrator
   cd backend ; .\run-local.ps1
   ```
4. Open the administrator login `frontend/admin/login.html` (or reload the dashboard: an **Admin** button appears —
   convenience only) and sign in with the same phone number. The console is `frontend/admin/index.html`; see
   *The admin panel* below.
5. To remove admin access, unset `ADMIN_UIDS` and restart.

Admin APIs additionally require a **phone** token and an **active** profile. The administrator cannot be suspended.
Every suspend/reactivate, acknowledged/resolved alert, resolved report, opened user detail (full phone number), opened
alert (coordinates) and CSV export is written to `adminAuditLogs`, and **denied attempts on `/api/admin/**` are logged
as security events**.

## Run it

Backend (needs the key and your project settings; it refuses to start without credentials):
```powershell
cd backend
.\run-local.ps1          # sets project id, key path and database id, then runs on http://localhost:8080
```
Environment variables (all optional except credentials):

| Variable | Default | Meaning |
|---|---|---|
| `FIREBASE_PROJECT_ID` | – | Firebase project id |
| `FIREBASE_CREDENTIALS_PATH` | – | path to the service-account JSON (or use `GOOGLE_APPLICATION_CREDENTIALS`) |
| `FIREBASE_FIRESTORE_DATABASE_ID` | `(default)` | `default` for this project |
| `ADMIN_UIDS` | empty | comma-separated administrator uid(s) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5500,http://127.0.0.1:5500` | allowed browser origins |
| `CONSENT_VERSION` | `2026-10-v1` | must equal `consentVersion` in `frontend/js/config.js` |
| `ALERT_RETENTION_DAYS` | `90` | value written to `retainUntil` |
| `RATE_LIMIT_ENABLED` | `true` | per-IP limits: 20/min on `/api/auth/**`, 240/min elsewhere (in memory) |
| `FIREBASE_CHECK_REVOKED` | `true` | reject revoked/disabled users' tokens |
| `PORT` | `8080` | |

SOS, SMS, push and limits have their own variables: see *SOS emergency alert system* below.

Frontend: open the project folder in VS Code and press **Go Live** (port 5500). The root `index.html` forwards to the
public landing page <http://localhost:5500/frontend/home.html> — and switches `127.0.0.1` to `localhost`, because
Firebase phone sign-in only works on authorised domains and the API's CORS list expects `localhost`. Log in at
`frontend/login.html`; the admin login is <http://localhost:5500/frontend/admin/login.html>. (`file://` does not work —
ES modules.)

## Tests

```powershell
cd backend
.\mvnw.cmd verify        # 196 tests, no network and no Firebase project needed
```
They run the real security filter chain and controllers with **Firebase Auth and the Firestore repositories mocked**:
token handling (missing / forged / non-phone), registration validation, role self-assignment attempts, ownership of
profiles / contacts / alerts, suspended accounts, admin-only access (and an empty allow-list), audit logging, rate limits,
CORS and security headers, the admin panel (alert acknowledge/resolve transitions, filters, pagination, reports and CSV,
audit outcomes, legacy profiles), plus unit tests for phone normalisation and name validation.

**Browser UI suite** (`tests/ui`, 105 checks): the real frontend in headless Edge against a fake Firebase SDK and a mock
API — public pages, helplines, nearby help, the onboarding checklist, the SOS drill, a regression of the real SOS flow and
responsive layout at 1440/768/390 px. Run `node tests/ui/run.mjs` (Node 22+; see `tests/ui/README.md`).

**Not covered by automated tests (needs your Firebase set-up):** a real phone sign-in with a test number, and the
Firestore repository classes against a real database. See the report for what was exercised by hand.

## The dashboard (`frontend/dashboard.html`)

Shown only after Firebase sign-in **and** a successful `GET /api/users/me` (the backend verifies the token, finds the
profile and checks the account is not suspended). No Firebase session → redirect to `login.html`; a rejected, expired or
revoked token, or a suspended account → sign out and back to login with a message.

| Section | Backed by |
|---|---|
| Header: logo, section navigation, **profile menu** (name, phone, Account settings, Admin console for the admin only, Log out) and a Log out button | `GET /api/users/me` |
| Welcome: "Welcome back, <name>", account status, phone-verified status | `GET /api/users/me` |
| Emergency SOS: two-step dialog → alert created, **notification status shown separately** (live), cancel request; opt-in location; real `tel:` links; TEST MODE banner | `GET /api/sos/info`, `POST /api/sos`, `GET /api/sos/{id}/me`, `POST /api/sos/{id}/cancel` |
| **Get ready** checklist: add a contact, verify a consenting contact, do an SOS drill. Built only from real data (hidden until both the profile and the contact list loaded); disappears when all three are done | `GET /api/users/me` (`sosDrillCompletedAt`), `GET /api/emergency-contacts` |
| **SOS drill**: a separate PRACTICE ONLY dialog. No alert is created, nothing is sent, the emergency number is shown as text (not a `tel:` link) so practice cannot dial; optional location check that is not sent or saved. Finishing sets a profile flag | `POST /api/users/me/sos-drill` only |
| **Helplines**: 112, 1091, 181, 100, 1930 as tap-to-call links with a short description. The numbers exist only in `js/config.js` (`helplines`; `emergencyNumber` is derived from it) and are shown even if the API fails | none (static) |
| **Nearby help**: Google Maps search links for police stations and hospitals. The browser location is requested only after "Use my location" is pressed, is written into the two links and is neither sent to SafeHer nor stored | none (Google Maps in a new tab) |
| Emergency contacts: add / edit / delete (with confirmation), **consent + verification code**, duplicate and own-number checks, configurable limit | `/api/emergency-contacts` (GET, POST, PATCH, DELETE, `…/verification`) |
| Location status: permission state, button to request it, explanation of why | browser Geolocation API (nothing stored) |
| Recent activity: your alerts with creation and notification status | `GET /api/sos/me` |
| Incident reports | `POST /api/incident-reports`, `GET /api/incident-reports/me` |
| Account: edit name, account id, sign out of all devices | `PATCH /api/users/me`, `POST /api/auth/logout-all` |

Every section shows loading, empty and error states from the real response; there is no sample data.

## Public pages (no sign-in)

| Page | Content |
|---|---|
| `frontend/home.html` | What SafeHer is and is not (not an emergency service, never contacts the police, TEST mode simulates messages), how it works in 3 steps, helplines, short FAQ, links to login / register / help / terms / privacy. The project root opens this page. |
| `frontend/help.html` | Full FAQ: SOS and message statuses, cancelling, the drill, test mode, contact verification, location and privacy (what is stored, for how long, who can see it; account deletion is not available in the app yet), sign-in problems, helplines. |
| `frontend/404.html` | Not-found page that works at any URL depth. Static hosts such as Firebase Hosting serve it automatically for unknown paths; VS Code Live Server does not use it (open it directly to see it). |

They use `js/public.js` (helplines and the emergency number from `config.js`, no API calls, nothing stored).

## The admin panel (`frontend/admin/`)

Separate sign-in at `admin/login.html` (Firebase phone OTP, same as users). After sign-in the page calls the backend; only a
profile whose role **the server** reports as `ADMIN` proceeds — anyone else is signed out again with a message. The console
(`admin/index.html`) re-checks on load and shows "Access denied" otherwise. This is convenience: every `/api/admin/**`
request is authorised again in Spring Security (`ROLE_ADMIN` from `ADMIN_UIDS` only), so calling the API directly gets
`403` for a normal user and `401` without a token or with a bad one.

| Sidebar item | What it does | API |
|---|---|---|
| Dashboard | Real totals: users (active / deactivated / phone-verified), SOS records by state, last 24 h, open incident reports, 14-day registration and SOS bars, recent SOS and admin actions. Empty states, no sample data | `GET /api/admin/dashboard` |
| Users | Search (name, phone, id), status filter, pages of 10, masked numbers; detail shows the full number (audited); **Deactivate / Reactivate** with confirmation and optional reason — also disables the Firebase account and revokes its sessions | `GET /api/admin/users`, `/users/{uid}`, `PATCH /users/{uid}/status` |
| SOS alerts | Search by id, status/date filters, pages of 10, notification state and attention flags, delivery records, history, opt-in map, live updates. **Acknowledge** and **Resolve** (note) change the record and write an audit entry. See *SOS emergency alert system* | `GET /api/admin/sos`, `/sos/{id}`, `/sos/{id}/deliveries`, `PATCH /sos/{id}/status`, `/sos/stream` |
| Notifications | SMS mode and provider, push configuration, your push devices, recent delivery problems | `GET /api/admin/notifications/overview`, `/devices` |
| Reports | Daily / weekly (Monday start) / monthly counts for a date range, **CSV export** (dates and counts only), incident-report queue with resolve | `GET /api/admin/reports/alerts`, `/reports/alerts.csv`, `/incident-reports` |
| Audit logs | Admin actions and security events with timestamp, actor, target and outcome (success / failure / denied). Written by the server; **there is no API or UI to edit or delete entries**, and Firestore rules deny all client access | `GET /api/admin/audit-logs` |
| Settings | Read-only server policy (retention, rate limits, origins, notification provider) and sign-out everywhere | `GET /api/admin/settings` |

Alert states: **New** → **Acknowledged** → **Resolved**; the user may **Cancel** a new alert. *Acknowledged* and *Resolved*
only record what an administrator did. **SafeHer does not contact the police or any emergency service**, and nothing in the
console claims it does. The panel never shows data that was not stored.

Removing alerts or audit entries is intentionally not provided.

## SOS emergency alert system

**What SafeHer does when SOS is pressed:** it stores the alert, then tries to text the user's **verified, consenting**
emergency contacts and to send a push notice to registered **administrator devices**, and records exactly what happened to
every message. **What it does not do:** contact the police or any emergency service, run a staffed response service, or
guarantee that anyone reads a message in time. The UI says so wherever it matters (SOS card and dialog, admin console).

### Flow

1. The user opens **SOS** (two steps, so it is not triggered by accident) → *Create SOS alert*. The browser generates an
   `Idempotency-Key`; location is attached only if the user ticked it (pre-ticked only when the browser already granted
   location; 8-second limit; if it fails the alert is **still created** and the reason is stored: permission denied /
   unavailable / timeout).
2. `POST /api/sos` — the owner is taken **only** from the verified Firebase token. The alert id is derived from the user id and
   the key and Firestore `create()` is atomic, so a double click, a retry or 8 concurrent submissions give **one** alert
   (`201` the first time, `200` + `Idempotent-Replayed: true` afterwards). The alert and its **outbox job** are written in
   one batch **before** anything is sent.
3. A background worker (leases jobs, so it is safe with several instances and recovers after a restart) plans the recipients
   as deterministic delivery records, sends with bounded retries and backoff (3 attempts: 20 s, 60 s), and recomputes the
   summaries from the delivery records. A notification failure can never remove an alert.
4. The user's screen shows **"Alert created"** and the **notification status separately**, refreshed every 3 s while it is
   processing; if the status cannot be refreshed it says *Delivery status unavailable* and that the alert is saved.
5. Administrators see it in **Admin → SOS alerts** (live over SSE), acknowledge and resolve it; each step is audited.

### Notification states (always derived from delivery records)

| State | Meaning |
|---|---|
| Processing | at least one recipient is still queued / being sent / waiting for a retry |
| Nobody was messaged | no contact is verified **and** consenting (skipped, with the reason) |
| Test only — nothing sent | TEST MODE: simulated. **Never** shown as delivered |
| Accepted by SMS provider | the provider took the message. **Not** proof of receipt |
| Delivered | the provider's own delivery receipt (signed webhook) says so |
| Partially delivered | some recipients succeeded and some failed |
| Delivery failed | every attempted recipient failed |
| Delivery status unavailable | no information (old alert, or the status could not be read) |

Cancelling is a *request*: unsent SMS are stopped, messages already sent **cannot be recalled**, and the UI says so.
Resolved or cancelled alerts can never be re-opened (no endpoint exists for it).

### Emergency contacts: consent and verification

Max **5** (`MAX_EMERGENCY_CONTACTS`), E.164 validation, duplicate and own-number checks. A contact receives SOS messages only if
it is **verified** (it entered a 6-digit code texted to *its* number — your own login never verifies someone else's number)
**and** you confirmed it agreed to receive them. Codes: 10 minutes, 5 wrong tries, 3 sends per contact per hour, stored only
as a hash; changing the number removes verification and consent. In TEST MODE nothing is texted, so the API returns the code
(`testModeCode`) so the flow can be tried; in REAL mode the response **never** contains it. Admins only ever see masked
numbers (`+91•••••••002`).

### TEST MODE and REAL MODE

| | TEST (default) | REAL |
|---|---|---|
| SMS | mock provider, nothing leaves the server, deliveries are `SIMULATED`, UI shows *TEST MODE* | Twilio adapter |
| Needed | nothing | `NOTIFICATIONS_MODE=REAL`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` **or** `TWILIO_MESSAGING_SERVICE_SID`; the app **refuses to start** without them |
| Receipts | none | signed Twilio status callbacks at `POST /api/webhooks/sms/twilio` (needs `PUBLIC_BASE_URL=https://…`) |

**SMS provider setup (Twilio).** Create a Twilio account, buy/verify a sender that may message your recipients' country, set the
four variables above, expose the API over HTTPS and set `PUBLIC_BASE_URL`. India needs sender and template registration under
TRAI DLT and consent from recipients; check your provider's policies before enabling REAL. Secrets come from environment
variables only. **The Twilio adapter is covered by tests against a local stand-in server, but has not been run against Twilio.**
Callbacks are authenticated with `X-Twilio-Signature` (HMAC-SHA1 over the URL *we* configured and the posted fields); anything
unsigned or altered gets `403` before any data is read, and the endpoint does not exist in TEST mode.

**Admin push (FCM).** Backend: nothing to set (the Admin SDK is already initialised; `ADMIN_PUSH_ENABLED`,
`ADMIN_PUSH_RECIPIENTS` limit it). Browser: Firebase console → Project settings → **Cloud Messaging → Web Push certificates →
Generate key pair**, put the public key in `frontend/js/config.js` as `vapidKey`, then *Admin → Notifications → Enable push on
this browser* (allows notifications, registers `firebase-messaging-sw.js`, stores the FCM token against your admin account).
The payload is a generic title plus the alert id: **no location, name or phone number**. "Accepted by FCM" is not "seen by a
person" — acknowledging stays a separate audited action. Invalid tokens are removed automatically. If no device is registered
the console says so and the alert is flagged *admin push not delivered*. **A real push to a real browser has not been exercised
in this project** (it needs your VAPID key). The invalid-token handling is covered by automated tests with a scripted push
sender; the live check against real FCM did not complete and still needs to be re-run.

### Delivery guarantee and limitations

* **At-least-once with de-duplication.** A delivery that is already submitted/delivered/simulated/failed is never sent again and
  planning twice cannot create a second delivery. If the process dies *between the provider accepting a message and us
  recording it*, that message is retried and a recipient can receive it **twice**. Exactly-once delivery of an external SMS cannot
  be guaranteed by any design that calls a third party, and SafeHer does not claim it.
* Retries are bounded (`NOTIFICATIONS_MAX_ATTEMPTS`); a stuck worker is recovered when its lease (60 s) expires.
* SSE subscribers and rate limits live in one instance's memory; with several instances the console also polls (every 60 s) when
  the stream is unavailable.
* Retention: closed alerts are purged hourly after `retainUntil` (see *Firestore data* above); open alerts are kept until
  closed. Each run reads at most 500 expired alerts, so a backlog of more than 500 still-open expired alerts would hold up
  the purge of closed ones behind them. The audit log is not purged.
* No STOP/opt-out handling of inbound SMS replies, and no standalone cross-user "contacts directory" for admins (deliberately:
  data minimisation — recipients are shown masked inside an alert).

### Admin SOS module

*Admin → SOS alerts*: search by alert id, status and date filters, pages of 10, newest first; columns for the masked user
reference, location availability, notification state (with a TEST tag) and grounded **attention flags** (new alert still
unacknowledged after `ALERT_ATTENTION_MINUTES`, contact messages failed/partial, admin push not delivered — nothing else; no
invented severity). The detail shows the alert, the per-recipient delivery records (recipient masked, failure code, attempts,
a plain-language label such as *accepted — receipt not confirmed*), the event history and — only if the user shared one — the
location, with an opt-in map (loads openstreetmap.org only when you press *Show map*). Opening a record, its deliveries, or
changing its status is audited; the resolution note is never copied into logs. *Admin → Notifications*: mode, provider, push
configuration, your registered devices and recent delivery problems. Live updates use SSE with reconnect/backoff, a stale-data
warning, a new-alert indicator and a manual Refresh.

### Firestore data model, rules and indexes

| Path | Content | Written by |
|---|---|---|
| `emergencyAlerts/{alertId}` | `uid`, `status`, `createdAt`, `locationStatus`, `location`, ack/resolve fields, `idempotencyKeyHash`, `notificationMode`, `notificationSummary`, `adminNotificationSummary`, `cancellationRequestedAt`, `retainUntil`, `schemaVersion` | backend |
| `emergencyAlerts/{id}/deliveries/{d}` | one per recipient+channel: type, masked label, status, attempts, provider reference, failure code, `deliveredAt` (provider evidence only) | worker / webhook |
| `emergencyAlerts/{id}/events/{e}` | append-only history (no phone numbers or coordinates) | backend |
| `notificationJobs/{alertId}` | outbox with lease; deleted when finished | backend |
| `adminDevices/{id}` | FCM token of an admin device (id = hash of the token) | backend |
| `users/{uid}/emergencyContacts/{id}` | + `verified`, `notifyConsent`, hashed pending code | backend |
| `adminAuditLogs/{id}` | + `SYSTEM_EVENT` entries for notification processing | backend |

(The existing `emergencyAlerts` collection and `ACTIVE` status — shown as "new" — were kept instead of introducing a duplicate
`sos_alerts`; `/api/sos` is the primary path and `/api/emergency-alerts` remains as an alias.)

`firestore.rules` **denies every client read and write** — all access goes through the backend, which also enforces ownership
(other users' alerts are `404`) and the administrator allow-list. That is why a user cannot read others' alerts, forge status
changes or delivery results, or touch admin collections. A rule such as "owners may read their own alert" was rejected because
it would also expose delivery results and internal fields. The rules were checked **against the live project** (a signed-in
user's own ID token gets `403` reading `emergencyAlerts` and `users` and creating an alert directly). Deploy them with
`firebase deploy --only firestore:rules` if you have not. **No composite indexes are needed** (single-field queries only;
`firestore.indexes.json` is empty on purpose).

### APIs

| User | |
|---|---|
| `GET /api/sos/info` | TEST/REAL mode, contact limit, plain-language note |
| `POST /api/sos` | create (header `Idempotency-Key`; body `location?`, `locationStatus?`) |
| `GET /api/sos/me`, `GET /api/sos/{id}/me` | own alerts / one own alert with notification status |
| `POST /api/sos/{id}/cancel` | cancellation request |
| `POST /api/emergency-contacts/{id}/verification`, `…/verification/confirm` | send / confirm a code |
| `POST /api/users/me/sos-drill` | marks a practice drill as finished (own profile flag only; creates no alert, sends nothing) |

| Admin (`ROLE_ADMIN` only) | |
|---|---|
| `GET /api/admin/sos` (`status,q,from,to,page,size`), `GET /api/admin/sos/{id}`, `GET …/{id}/deliveries` | list / detail (audited) / delivery records (audited) |
| `PATCH /api/admin/sos/{id}/status` | `ACKNOWLEDGED` or `RESOLVED` + note; invalid transitions `409`/`400` |
| `GET /api/admin/sos/stream` | Server-Sent Events: *"something changed"* markers only |
| `GET /api/admin/notifications/overview`, `GET/POST /api/admin/devices`, `DELETE /api/admin/devices/{id}` | notification health, push devices |
| `POST /api/webhooks/sms/twilio` | provider receipts (signature-authenticated, REAL mode only) |

State-changing admin calls are rate limited (30/min per client) and contact verification (10/min).

### Environment variables added

| Variable | Default | Meaning |
|---|---|---|
| `NOTIFICATIONS_MODE` | `TEST` | `TEST` (simulate) or `REAL` (Twilio) |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`, `TWILIO_MESSAGING_SERVICE_SID` | – | REAL mode credentials |
| `PUBLIC_BASE_URL` | – | https base URL of this API, for provider receipts |
| `NOTIFICATIONS_MAX_ATTEMPTS` | `3` | attempts per recipient |
| `NOTIFICATIONS_WORKER_ENABLED` | `true` | run the background worker |
| `ADMIN_PUSH_ENABLED` / `ADMIN_PUSH_RECIPIENTS` | `true` / empty | push on/off; limit to listed admin uids |
| `ALERT_ATTENTION_MINUTES` | `5` | flag unacknowledged alerts after this long |
| `MAX_EMERGENCY_CONTACTS`, `MAX_ALERTS_PER_WINDOW` | `5`, `5` (per 10 min) | limits |
| `RETENTION_PURGE_ENABLED` | `true` | hourly deletion of closed alerts past `retainUntil` |

### Manual console steps

1. Firestore rules: deploy `firestore.rules` (deny-all). 2. For push: generate the Web Push key pair and set `vapidKey`.
3. For REAL SMS: provider account, sender registration and the variables above. 4. Phone sign-in test numbers as before.

## What is real and what is not

* **SOS** stores the alert, and in **TEST MODE (default)** *simulates* the messages — nothing is sent. In REAL mode it submits SMS
  through Twilio to verified, consenting contacts and shows "delivered" only on the provider's receipt. **It never contacts the
  police or emergency services**; the dialog offers a real `tel:112` link and `tel:` links for your contacts.
* **Location** is read from the browser only on request and is stored only if the user ticks *Share my current location* when
  creating an alert (then it is included in messages to verified contacts and visible to administrators). No live tracking.
* Adding an emergency contact notifies nobody; verifying one sends a single code to that number (simulated in TEST mode).
* `terms.html` / `privacy.html` are development drafts that need legal review before a public launch.

## Security notes

* All state-changing and private endpoints require a verified **phone** ID token; `/api/admin/**` also requires
  `ROLE_ADMIN` (allow-list only). Other users' records are answered as `404`, so existence is not revealed.
* Input is validated on both sides (names 2–60 chars; E.164 numbers via libphonenumber; coordinates; lengths).
* The API sends `Content-Security-Policy: default-src 'none'`, `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Cache-Control: no-store`. CORS allows only the configured origins.
* The frontend builds DOM with `textContent` (no `innerHTML` with user data).
* **When you host the static frontend**, set these response headers (Firebase Hosting `headers`, nginx, …):
  `Content-Security-Policy: default-src 'self'; script-src 'self' https://www.gstatic.com https://www.google.com https://www.recaptcha.net; frame-src https://www.google.com https://www.recaptcha.net https://*.firebaseapp.com; connect-src 'self' <your API origin> https://*.googleapis.com https://www.gstatic.com; img-src 'self' data: https://*.gstatic.com https://www.google.com; style-src 'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; object-src 'none'; base-uri 'self'`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: geolocation=(self)`.
  Test it with your real pages before enforcing (reCAPTCHA needs the Google origins above).
* Use HTTPS everywhere outside localhost; `api.js` refuses to send tokens over plain HTTP to non-local hosts.
* Rate limits are per application instance and use the socket address; behind a proxy configure forwarded headers.
* Admin lists search in memory over the newest records (users 500, alerts 1000, audit entries 500, report range 5000).
  Fine for development; production scale needs indexed queries. Rate limits are in memory, per instance.
* **Least privilege:** `firestore.rules` denies every client read/write — only the backend (Admin SDK) touches Firestore.
  For production run the backend with a dedicated service account holding only the *Cloud Datastore User* and
  *Firebase Authentication Admin* roles (not Owner/Editor), and rotate the key you used for development.

## Common problems

| Message / symptom | Cause and fix |
|---|---|
| "Phone sign-in isn't available yet…" (`auth/operation-not-allowed`) | Phone provider not enabled, or your country is blocked by the **SMS region policy** (steps 1) |
| "…billing…" (`auth/billing-not-enabled`) | Real SMS needs a billing plan; use a fictional test number during development |
| "This web address isn't authorised" | Open `http://localhost:5500`, not `127.0.0.1`; add your domain to Authorized domains |
| Spinner stays on "Sending code…" | reCAPTCHA is showing a challenge — solve it (or use a test number with the testing switch in automated tests) |
| "That code isn't correct" with a test number | The code must be the fixed code you entered in the console for that exact number |
| "The security check failed" | Reload; make sure the page is on an authorised domain and not blocked by an extension |
| `403 PHONE_AUTH_REQUIRED` | The token came from another sign-in method (e.g. e-mail). Only phone sign-in is accepted |
| `403 REGISTRATION_REQUIRED` / `404 NOT_REGISTERED` | The number signed in but has no SafeHer profile — register first |
| `403 ACCOUNT_SUSPENDED` | The administrator suspended the account |
| `503 DATA_STORE_UNAVAILABLE` | Firestore unreachable or wrong database id (`FIREBASE_FIRESTORE_DATABASE_ID`) |
| Backend won't start: "Firebase credentials not found" | Set `FIREBASE_CREDENTIALS_PATH` / `FIREBASE_PROJECT_ID` |
| Browser CORS error | Frontend not on an allowed origin; set `CORS_ALLOWED_ORIGINS` |

Nothing in this project sends real SMS during development as long as you only use the fictional numbers you configured.
