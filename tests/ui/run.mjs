// SafeHer UI suite: drives the REAL frontend code in headless Edge against test doubles of Firebase phone auth and of
// the API (tests/ui/lib). It checks browser behaviour; it does not prove anything about the real Firebase project or
// the real Java backend (those are covered by `mvnw verify` and by manual end-to-end runs).
//
// Usage (needs Node 22+ and Microsoft Edge; see tests/ui/README.md):
//   node tests/ui/run.mjs
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { launch } from "./lib/cdp.mjs";
import { firebaseApp, firebaseAuth } from "./lib/fake-firebase.mjs";
import * as mock from "./lib/mock-api.mjs";
import { startStaticServer } from "./lib/static-server.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..", "..");
const OUTPUT = join(HERE, "output");
const EDGE = process.env.EDGE_PATH ?? "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe";
const WEB_PORT = Number(process.env.UI_WEB_PORT ?? 5599);
const API_PORT = Number(process.env.UI_API_PORT ?? 8899);
const CDP_PORT = Number(process.env.UI_CDP_PORT ?? 9339);
const SITE = `http://localhost:${WEB_PORT}`;
const FRONT = `${SITE}/frontend`;
const LAT = 13.0827;
const LNG = 80.2707;

mkdirSync(OUTPUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok: Boolean(ok), detail: String(detail ?? "") });
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${!ok && detail ? `  — ${String(detail).slice(0, 300)}` : ""}`);
};

const webServer = await startStaticServer({ root: ROOT, port: WEB_PORT, apiBaseUrl: `http://localhost:${API_PORT}/api` });
const apiServer = await mock.startMockApi({ port: API_PORT, allowOrigin: SITE });
const b = await launch({
  edgePath: EDGE,
  port: CDP_PORT,
  outputDir: OUTPUT,
  interceptors: [
    { match: /^https:\/\/www\.gstatic\.com\/firebasejs\/[^/]+\/firebase-app\.js/, respond: () => ({ contentType: "text/javascript", body: firebaseApp }) },
    { match: /^https:\/\/www\.gstatic\.com\/firebasejs\/[^/]+\/firebase-auth\.js/, respond: () => ({ contentType: "text/javascript", body: firebaseAuth }) },
    { match: /^https:\/\/fonts\.(googleapis|gstatic)\.com\//, respond: () => ({ contentType: "text/css", body: "" }) },
  ],
});
const { ev, until, goto, click, fill, text, visible, sleep } = b;

// Counts every request for the browser location, so tests can prove it is only used after a button press.
await b.addInitScript(`(() => {
  window.__geoCalls = 0;
  const g = navigator.geolocation;
  if (!g) return;
  const get = g.getCurrentPosition.bind(g);
  const watch = g.watchPosition.bind(g);
  g.getCurrentPosition = (...a) => { window.__geoCalls++; return get(...a); };
  g.watchPosition = (...a) => { window.__geoCalls++; return watch(...a); };
})();`);
await b.grant(SITE, ["geolocation"]);
await b.geolocation(LAT, LNG, 25);
await b.viewport(1440, 900);

const config = await import(`file:///${join(ROOT, "frontend", "js", "config.js").replace(/\\/g, "/")}`);
const HELPLINES = config.helplines.map((h) => h.number);
const sosPosts = () => mock.state.requests.filter((r) => r.method === "POST" && r.path === "/api/sos");
const requestsMentioning = (needle) => mock.state.requests.filter((r) => r.path.includes(needle) || (r.raw ?? "").includes(needle));
const noHorizontalScroll = () => ev("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1");
const helplineHrefs = (selector) => ev(`[...document.querySelectorAll(${JSON.stringify(selector)} + " .helpline__call")].map(a => a.getAttribute("href"))`);

async function signInAs(uid, phone) {
  await goto(`${FRONT}/home.html`);
  await ev(`sessionStorage.setItem("fakeFirebaseUser", JSON.stringify({ uid: ${JSON.stringify(uid)}, phone: ${JSON.stringify(phone)} }))`);
  await goto(`${FRONT}/dashboard.html`);
  return until("document.getElementById('main').getAttribute('aria-busy') === 'false'", 10000);
}

try {
  /* ============================================================ 0. source rules for the new code */
  const NEW_FILES = ["helplines.js", "nearby.js", "onboarding.js", "sos-drill.js", "public.js"];
  const code = (file) => readFileSync(join(ROOT, "frontend", "js", file), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, ""); // comments may mention the forbidden names
  for (const file of NEW_FILES) {
    const source = code(file);
    check(`source: ${file} never uses innerHTML / insertAdjacentHTML`, !/innerHTML|outerHTML|insertAdjacentHTML/.test(source));
    check(`source: ${file} uses no localStorage / sessionStorage`, !/localStorage|sessionStorage/.test(source));
  }
  check("source: the drill module has no call to the alert API", !/createAlert|cancelAlert|["`/]sos["`/]/.test(code("sos-drill.js")));
  const htmlWithNumbers = ["home.html", "help.html", "404.html", "dashboard.html"].filter((f) =>
    HELPLINES.some((n) => new RegExp(`(tel:|>)\\s*${n}\\b`).test(readFileSync(join(ROOT, "frontend", f), "utf8"))));
  check("source: helpline numbers are not hardcoded in the pages (only in js/config.js)", htmlWithNumbers.length === 0, htmlWithNumbers.join(", "));

  /* ============================================================ 5. root + landing page */
  await goto(`http://127.0.0.1:${WEB_PORT}/`);
  check("root: 127.0.0.1 opens the landing page on localhost", await until(`location.href === "${FRONT}/home.html"`, 8000), await ev("location.href"));
  check("home: title and hero", /SafeHer/.test(await ev("document.title")) && /safer/i.test(await text("#hero-title")));
  const homeText = await ev("document.body.textContent"); // includes answers inside closed <details>
  check("home: says SafeHer is not an emergency service", /not an emergency service/i.test(homeText));
  check("home: says SafeHer does not contact the police", /does not contact the police/i.test(homeText) && /never contacts the police/i.test(homeText));
  check("home: explains test mode honestly", /test mode/i.test(homeText) && /simulated/i.test(homeText));
  check("home: 'is' and 'is not' lists present", (await ev("document.querySelectorAll('#is-list li').length")) >= 3 && (await ev("document.querySelectorAll('#isnt-list li').length")) >= 3);
  check("home: how it works in exactly 3 steps", (await ev("document.querySelectorAll('ol.steps > li').length")) === 3);
  check("home: helplines rendered from config as tel: links", JSON.stringify(await helplineHrefs("#helplines-list")) === JSON.stringify(HELPLINES.map((n) => `tel:${n}`)), JSON.stringify(await helplineHrefs("#helplines-list")));
  check("home: every helpline has a description", (await ev("[...document.querySelectorAll('#helplines-list .helpline__desc')].every(d => d.textContent.trim().length > 10)")));
  check("home: emergency number filled from config", (await ev("[...document.querySelectorAll('[data-emergency-number]')].every(e => e.textContent === " + JSON.stringify(config.emergencyNumber) + ")")) && (await ev("document.querySelector('.hero__urgent a').getAttribute('href')")) === `tel:${config.emergencyNumber}`);
  check("home: FAQ has at least 4 questions and they open", (await ev("document.querySelectorAll('#faq details').length")) >= 4 && (await ev("(() => { const d = document.querySelector('#faq details'); d.querySelector('summary').click(); return d.open; })()")));
  const links = await ev("[...document.querySelectorAll('a[href]')].map(a => a.getAttribute('href')).filter(h => /\\.html$/.test(h))");
  for (const page of ["login.html", "register.html", "terms.html", "privacy.html", "help.html"]) {
    check(`home: links to ${page}`, links.includes(page));
  }
  const statuses = await ev("Promise.all(['login.html','register.html','terms.html','privacy.html','help.html','dashboard.html'].map(p => fetch(p).then(r => r.status)))");
  check("home: every linked page exists (HTTP 200)", statuses.every((s) => s === 200), JSON.stringify(statuses));
  check("home: opens no API request and asks for no location", mock.state.requests.length === 0 && (await ev("window.__geoCalls")) === 0);
  await b.screenshot("home-1440");

  /* ============================================================ 6. help + 404 */
  await goto(`${FRONT}/help.html`);
  check("help: FAQ page with at least 15 questions", (await ev("document.querySelectorAll('details').length")) >= 15, await ev("document.querySelectorAll('details').length"));
  check("help: marks itself as the current page", (await ev("document.querySelector('[aria-current=page]').getAttribute('href')")) === "help.html");
  check("help: helplines from config", JSON.stringify(await helplineHrefs("#helplines-list")) === JSON.stringify(HELPLINES.map((n) => `tel:${n}`)));
  // textContent, not innerText: answers inside closed <details> are not rendered but are part of the page.
  const helpText = (await ev("document.querySelector('main').textContent")).toLowerCase();
  const topics = ["drill", "test mode", "verified", "track my location", "didn't get a code", "accepted by sms provider"];
  check("help: covers drill, test mode, verification, location, statuses and sign-in problems", topics.every((t) => helpText.includes(t)), topics.filter((t) => !helpText.includes(t)).join(", "));
  check("help: states account deletion is not available yet (no false promise)", /not available yet/.test(helpText));
  check("help: says SafeHer never contacts the police", /never contacts the police/.test(helpText));

  for (const path of ["/frontend/no-such-page.html", "/frontend/a/b/c/deep", "/nothing-here"]) {
    await goto(`${SITE}${path}`);
    const ok = /doesn't exist/.test(await ev("document.body.innerText"));
    const home = await ev("document.getElementById('nf-home').href");
    const styled = await ev("getComputedStyle(document.querySelector('.pub')).display === 'flex'");
    const logo = await ev("document.querySelector('.brand__logo').src");
    check(`404: ${path} shows the not-found page with working links and styles`, ok && home === `${FRONT}/home.html` && styled && logo === `${FRONT}/assets/icons/logo.svg`, `${home} styled=${styled} logo=${logo}`);
  }
  check("404: links to login and help resolve inside /frontend/", (await ev("document.getElementById('nf-login').href")) === `${FRONT}/login.html` && (await ev("document.getElementById('nf-help').href")) === `${FRONT}/help.html`);
  check("404: emergency number from config", (await text("[data-emergency-number]")) === config.emergencyNumber);

  for (const [page, url] of [["home", `${FRONT}/home.html`], ["help", `${FRONT}/help.html`], ["404", `${FRONT}/nope.html`]]) {
    for (const width of [1440, 768, 390]) {
      await b.viewport(width, 900);
      await goto(url);
      check(`responsive: ${page} has no horizontal scroll at ${width}px`, await noHorizontalScroll());
    }
    await b.screenshot(`${page}-390`);
    await b.viewport(1440, 900);
  }

  /* ============================================================ register a fresh user through the real UI */
  await goto(`${FRONT}/register.html`);
  await until("document.getElementById('country').options.length > 5", 8000);
  await fill("name", "Priya Sharma");
  await fill("phone", "98765 43210");
  await click("#consent");
  await ev("document.getElementById('phone-form').requestSubmit()");
  check("register: code step reached (fake Firebase)", await until("getComputedStyle(document.getElementById('code-form')).display !== 'none'", 8000), await text("#form-alert-text"));
  await fill("code", "123456");
  await ev("document.getElementById('code-form').requestSubmit()");
  check("register: lands on the dashboard", await until("location.pathname.endsWith('/dashboard.html')", 10000) && await until("document.getElementById('main').getAttribute('aria-busy') === 'false'", 10000));
  const UID = "u919876543210";
  check("register: profile created in the mock API", mock.state.users.has(UID));

  /* ============================================================ 1. helplines on the dashboard */
  check("dashboard helplines: 5 numbers from config as tel: links", JSON.stringify(await helplineHrefs("#helplines-list")) === JSON.stringify(HELPLINES.map((n) => `tel:${n}`)), JSON.stringify(await helplineHrefs("#helplines-list")));
  check("dashboard helplines: each has a name and description", await ev("[...document.querySelectorAll('#helplines-list .helpline')].every(li => li.querySelector('.helpline__name').textContent && li.querySelector('.helpline__desc').textContent.length > 10)"));
  check("dashboard helplines: accessible labels", await ev("[...document.querySelectorAll('#helplines-list .helpline__call')].every(a => /^Call \\d+, /.test(a.getAttribute('aria-label')))"));
  check("dashboard helplines: states SafeHer is not part of the call", /not part of the call/.test(await text("#helplines")));
  await click("#sos-btn");
  check("SOS dialog: the call button uses the config number", await until("document.getElementById('sos-dialog').open", 4000) && (await ev("document.getElementById('sos-call-emergency').getAttribute('href')")) === `tel:${config.emergencyNumber}` && (await text("#sos-number")) === config.emergencyNumber);
  await click("#sos-close");

  /* ============================================================ 2. nearby help */
  const nearPolice = await ev("document.getElementById('nearby-police').href");
  const nearHospital = await ev("document.getElementById('nearby-hospital').href");
  check("nearby: police link is a Google Maps 'near me' search", nearPolice === "https://www.google.com/maps/search/?api=1&query=police%20station%20near%20me", nearPolice);
  check("nearby: hospital link is a Google Maps 'near me' search", nearHospital === "https://www.google.com/maps/search/?api=1&query=hospital%20near%20me", nearHospital);
  check("nearby: links open in a new tab without referrer/opener", await ev("['nearby-police','nearby-hospital'].every(id => { const a = document.getElementById(id); return a.target === '_blank' && /noopener/.test(a.rel) && /noreferrer/.test(a.rel); })"));
  check("nearby: the location is NOT requested on page load", (await ev("window.__geoCalls")) === 0);
  await click("#nearby-locate");
  check("nearby: pressing 'Use my location' requests it once", await until("window.__geoCalls === 1", 6000), await ev("window.__geoCalls"));
  check("nearby: links now centre on the location", await until("document.getElementById('nearby-police').href === 'https://www.google.com/maps/search/police+station/@13.08270,80.27070,15z'", 6000) && (await ev("document.getElementById('nearby-hospital').href")) === "https://www.google.com/maps/search/hospital/@13.08270,80.27070,15z", await ev("document.getElementById('nearby-police').href"));
  check("nearby: tells the user the location is not sent or stored", /does not send or store/.test(await text("#nearby-status")));
  check("nearby: the location was not sent to the API", requestsMentioning("13.08").length === 0);
  check("nearby: nothing written to localStorage", (await ev("localStorage.length")) === 0);

  /* ============================================================ 3 + 4. onboarding checklist and drill */
  check("onboarding: shown for a new user", await until("!document.getElementById('onboarding').hidden", 6000) && (await ev("document.getElementById('main').classList.contains('dash-main--onboarding')")));
  check("onboarding: 0 of 3 done, three steps", (await text("#onboarding-count")) === "0 / 3" && (await ev("document.querySelectorAll('#onboarding-list li').length")) === 3);
  check("onboarding: steps are add contact, verify, drill", /Add an emergency contact/.test(await text("#onboarding-list")) && /Verify a contact/.test(await text("#onboarding-list")) && /SOS drill/.test(await text("#onboarding-list")));
  await b.screenshot("dashboard-onboarding-1440");

  await fill("contact-name", "Meera Nair");
  await fill("contact-phone", "98765 00002");
  await fill("contact-relationship", "Sister");
  await click("#contact-consent");
  await ev("document.getElementById('contact-form').requestSubmit()");
  check("onboarding: adding a contact ticks step 1 (live)", await until("document.getElementById('onboarding-count').textContent === '1 / 3'", 6000), await text("#onboarding-count"));
  check("onboarding: step 2 shows the real verified count", /0 of 1 verified/.test(await text("#onboarding-list")), await text("#onboarding-list"));

  await click("#contact-list button[aria-label^='Verify']");
  await until("document.getElementById('verify-dialog').open", 4000);
  await click("#verify-send");
  await until("!document.getElementById('verify-form').hidden", 6000);
  await fill("verify-code", await text("#verify-testcode"));
  await ev("document.getElementById('verify-form').requestSubmit()");
  check("onboarding: verifying the contact ticks step 2", await until("document.getElementById('onboarding-count').textContent === '2 / 3'", 6000), await text("#onboarding-count"));

  const sosBefore = sosPosts().length;
  const alertsBefore = mock.state.alerts.length;
  await click("#onboarding-list button");
  check("drill: 'Start drill' in the checklist opens the practice dialog", await until("document.getElementById('drill-dialog').open", 4000));
  check("drill: clearly labelled PRACTICE ONLY", /PRACTICE ONLY/.test(await text("#drill-banner")) && /no alert is created, nothing is sent/.test(await text("#drill-banner")));
  check("drill: contains no tel: link (practice cannot dial)", (await ev("document.querySelectorAll('#drill-dialog a[href^=\"tel:\"]').length")) === 0);
  check("drill: shows the emergency number as text from config", (await text("#drill-number")) === config.emergencyNumber);
  check("drill: names who a real SOS would message", await until("/Meera Nair/.test(document.getElementById('drill-recipients').textContent)", 4000), await text("#drill-recipients"));
  check("drill: repeats that SafeHer does not contact the police", /does not contact the police/i.test(await ev("document.getElementById('drill-step-practice').textContent")));
  await b.screenshot("drill-1440");
  await click("#drill-run");
  check("drill: reaches the practice result", await until("!document.getElementById('drill-step-done').hidden", 6000));
  check("drill: result says nothing was created or sent", /No alert was created and nothing was sent/.test(await text("#drill-step-done")));
  check("drill: NO alert request reached the API", sosPosts().length === sosBefore, `POST /api/sos count ${sosBefore} -> ${sosPosts().length}`);
  check("drill: NO alert exists afterwards", mock.state.alerts.length === alertsBefore);
  check("drill: records the drill on the profile", mock.state.requests.some((r) => r.method === "POST" && r.path === "/api/users/me/sos-drill") && Boolean(mock.state.users.get(UID).sosDrillCompletedAt));
  check("drill: checklist shows it saved", /marked as done/.test(await text("#drill-saved")));
  await click("#drill-done-close");
  check("onboarding: hidden once all three steps are done", await until("document.getElementById('onboarding').hidden", 4000) && !(await ev("document.getElementById('main').classList.contains('dash-main--onboarding')")));
  await goto(`${FRONT}/dashboard.html`);
  await until("document.getElementById('main').getAttribute('aria-busy') === 'false'", 10000);
  await sleep(1200);
  check("onboarding: stays hidden after reload (state comes from the API)", await ev("document.getElementById('onboarding').hidden"));

  // drill again from the SOS card, with the location check
  const geoBefore = await ev("window.__geoCalls");
  await click("#drill-btn");
  await until("document.getElementById('drill-dialog').open", 4000);
  await click("#drill-check-location");
  await click("#drill-run");
  check("drill (from SOS card): location check runs only because it was ticked", await until("!document.getElementById('drill-step-done').hidden", 8000) && (await ev("window.__geoCalls")) === geoBefore + 1);
  check("drill: location check result shown, marked as not sent", /Found \(±25 m\)/.test(await text("#drill-location-result")) && /not sent or saved/.test(await text("#drill-location-result")), await text("#drill-location-result"));
  check("drill: location never reached the API", requestsMentioning("13.08").length === 0);
  check("drill: still no alert", sosPosts().length === sosBefore && mock.state.alerts.length === alertsBefore);
  await click("#drill-done-close");

  // drill when saving the flag fails
  mock.failNext("POST", "/api/users/me/sos-drill", 503);
  await click("#drill-btn");
  await until("document.getElementById('drill-dialog').open", 4000);
  await click("#drill-run");
  check("drill: if saving the flag fails, it says so honestly", await until("/couldn't save/.test(document.getElementById('drill-saved').textContent)", 6000), await text("#drill-saved"));
  check("drill: a failed save still creates no alert", sosPosts().length === sosBefore);
  await click("#drill-done-close");

  /* ============================================================ regression: the real SOS still works */
  await click("#sos-btn");
  await until("document.getElementById('sos-dialog').open", 4000);
  await ev("document.getElementById('sos-include-location').checked = false");
  await click("#sos-create");
  check("regression: real SOS still creates exactly one alert", await until("!document.getElementById('sos-step-done').hidden", 8000) && sosPosts().length === sosBefore + 1 && mock.state.alerts.length === alertsBefore + 1);
  check("regression: real SOS sends an Idempotency-Key", /^[0-9a-f-]{16,}$/.test(sosPosts().at(-1).headers["idempotency-key"] ?? ""));
  check("regression: notification status settles (test mode)", await until("document.getElementById('sos-notif-pill').textContent === 'Test only — nothing sent'", 15000), await text("#sos-notif-pill"));
  await click("#sos-cancel-alert");
  await until("document.getElementById('sos-status').textContent === 'Cancelled by you'", 6000);
  await click("#sos-done-close");

  /* ============================================================ responsive dashboard */
  for (const width of [1440, 768, 390]) {
    await b.viewport(width, 900);
    await sleep(300);
    check(`responsive: dashboard has no horizontal scroll at ${width}px`, await noHorizontalScroll());
  }
  check("responsive: helplines and nearby visible on a phone", (await visible("#helplines-list")) && (await visible("#nearby-police")));
  await ev("document.getElementById('helplines').scrollIntoView()");
  await b.screenshot("dashboard-helplines-390");
  await b.viewport(1440, 900);

  /* ============================================================ checklist edge cases with seeded users */
  mock.seedUser("u919999911111", "+919999911111", "Asha Rao", { sosDrillCompletedAt: new Date().toISOString() });
  mock.seedContact("u919999911111", { name: "Ravi", phoneNumber: "+919876500077", verified: true, notifyConsent: true });
  await signInAs("u919999911111", "+919999911111");
  await sleep(1200);
  check("onboarding: a user with everything done never sees the checklist", await ev("document.getElementById('onboarding').hidden"));

  mock.seedUser("u919999922222", "+919999922222", "Divya Menon");
  mock.failNext("GET", "/api/emergency-contacts", 503);
  await signInAs("u919999922222", "+919999922222");
  await sleep(1500);
  check("onboarding: if the contact list fails to load, the checklist stays hidden (no guessing)", await ev("document.getElementById('onboarding').hidden"));

  mock.seedUser("u919999933333", "+919999933333", "Lata Iyer");
  mock.seedContact("u919999933333", { name: "Kiran", phoneNumber: "+919876500088", verified: true, notifyConsent: false });
  await signInAs("u919999933333", "+919999933333");
  check("onboarding: verified but no consent does NOT count as ready", await until("!document.getElementById('onboarding').hidden", 6000) && (await text("#onboarding-count")) === "1 / 3" && /1 of 1 verified · 0 will be messaged/.test(await text("#onboarding-list")), await text("#onboarding-list"));

  mock.failNext("GET", "/api/users/me", 503);
  await signInAs("u919999933333", "+919999933333");
  check("helplines: still shown when the API fails", (await ev("document.querySelectorAll('#helplines-list .helpline').length")) === HELPLINES.length && /couldn't load/i.test(await text("#dash-title")), await text("#dash-title"));

  /* ============================================================ console */
  const unexpected = b.problems.filter((p) => !/Failed to load resource/.test(p));
  check("no unexpected console errors or exceptions", unexpected.length === 0, unexpected.join(" | "));
} catch (error) {
  check("HARNESS", false, error.stack ?? error.message);
} finally {
  await b.close();
  webServer.close();
  apiServer.close();
}

const failed = results.filter((r) => !r.ok);
const summary = `${results.length - failed.length} passed, ${failed.length} failed, ${results.length} checks`;
console.log(`\n${summary}`);
writeFileSync(join(OUTPUT, "results.txt"), results.map((r) => `${r.ok ? "PASS" : "FAIL"} ${r.name}${r.ok ? "" : `  — ${r.detail}`}`).join("\n") + `\n\n${summary}\n`);
process.exit(failed.length ? 1 : 0);
