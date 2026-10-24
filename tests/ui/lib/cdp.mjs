// Minimal Chrome DevTools Protocol driver for headless Microsoft Edge (or Chrome). No dependencies: uses the
// WebSocket and fetch built into Node 22+.
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function connect(url) {
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  let id = 0;
  const pending = new Map();
  const listeners = new Map();
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(`${message.error.message} (${message.error.code})`));
      else resolve(message.result ?? {});
      return;
    }
    for (const fn of listeners.get(message.method) ?? []) fn(message.params);
  });
  return {
    send(method, params = {}) {
      const callId = ++id;
      ws.send(JSON.stringify({ id: callId, method, params }));
      return new Promise((resolve, reject) => pending.set(callId, { resolve, reject }));
    },
    on(method, fn) {
      if (!listeners.has(method)) listeners.set(method, []);
      listeners.get(method).push(fn);
    },
    close() {
      ws.close();
    },
  };
}

/**
 * @param {{ edgePath: string, port: number, outputDir: string, interceptors?: Array<{ match: RegExp, respond: (url: string) => { status?: number, contentType: string, body: string } }> }} options
 */
export async function launch({ edgePath, port, outputDir, interceptors = [] }) {
  mkdirSync(outputDir, { recursive: true });
  const profile = join(outputDir, `profile-${Date.now()}`);
  const proc = spawn(edgePath, [
    "--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run",
    "--no-default-browser-check", "--disable-extensions", "--disable-sync", "--window-size=1440,900", "about:blank",
  ], { stdio: "ignore" });

  let version;
  for (let i = 0; i < 60 && !version; i++) {
    await sleep(250);
    try {
      version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    } catch {
      /* not up yet */
    }
  }
  if (!version) throw new Error(`The browser did not start (${edgePath}).`);
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const pageTarget = targets.find((t) => t.type === "page");
  const browser = await connect(version.webSocketDebuggerUrl);
  const page = await connect(pageTarget.webSocketDebuggerUrl);

  const problems = [];
  page.on("Runtime.exceptionThrown", (p) => problems.push(`exception: ${p.exceptionDetails.exception?.description ?? p.exceptionDetails.text}`));
  page.on("Runtime.consoleAPICalled", (p) => {
    if (p.type === "error") problems.push(`console.error: ${p.args.map((a) => a.value ?? a.description).join(" ")}`);
  });
  page.on("Log.entryAdded", (p) => {
    if (p.entry.level === "error") problems.push(`log: ${p.entry.text} ${p.entry.url ?? ""}`);
  });

  // Requests to external hosts are answered locally (fake Firebase SDK, empty fonts) so a run is hermetic.
  page.on("Fetch.requestPaused", async (p) => {
    const hit = interceptors.find((i) => i.match.test(p.request.url));
    if (!hit) {
      await page.send("Fetch.continueRequest", { requestId: p.requestId }).catch(() => {});
      return;
    }
    const answer = hit.respond(p.request.url);
    await page.send("Fetch.fulfillRequest", {
      requestId: p.requestId,
      responseCode: answer.status ?? 200,
      responseHeaders: [
        { name: "Content-Type", value: answer.contentType },
        { name: "Access-Control-Allow-Origin", value: "*" },
        { name: "Cache-Control", value: "no-store" },
      ],
      body: Buffer.from(answer.body).toString("base64"),
    }).catch(() => {});
  });

  await page.send("Page.enable");
  await page.send("Runtime.enable");
  await page.send("Log.enable");
  if (interceptors.length) {
    await page.send("Fetch.enable", { patterns: [{ urlPattern: "https://*", requestStage: "Request" }] });
  }

  async function ev(expression) {
    const result = await page.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) {
      throw new Error(`eval failed: ${result.exceptionDetails.exception?.description ?? result.exceptionDetails.text}`);
    }
    return result.result.value;
  }

  async function until(expression, ms = 8000) {
    const start = Date.now();
    while (Date.now() - start < ms) {
      try {
        if (await ev(expression)) return true;
      } catch {
        /* page navigating */
      }
      await sleep(150);
    }
    return false;
  }

  async function goto(url) {
    const loaded = new Promise((resolve) => {
      const done = () => resolve();
      page.on("Page.loadEventFired", done);
      setTimeout(done, 15000);
    });
    await page.send("Page.navigate", { url });
    await loaded;
    await until("document.readyState === 'complete'", 10000);
  }

  return {
    page,
    browser,
    problems,
    ev,
    until,
    goto,
    sleep,
    click: (selector) => ev(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) throw new Error("no element ${selector.replace(/"/g, "'")}"); e.click(); return true; })()`),
    fill: (id, value) => ev(`(() => { const e = document.getElementById(${JSON.stringify(id)}); e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event("input", { bubbles: true })); e.dispatchEvent(new Event("change", { bubbles: true })); return true; })()`),
    text: (selector) => ev(`(document.querySelector(${JSON.stringify(selector)}) || {}).textContent || ""`),
    visible: (selector) => ev(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) return false; const s = getComputedStyle(e); return s.display !== "none" && s.visibility !== "hidden" && e.getClientRects().length > 0; })()`),
    async viewport(width, height) {
      await page.send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 });
    },
    async geolocation(latitude, longitude, accuracy = 25) {
      await page.send("Emulation.setGeolocationOverride", { latitude, longitude, accuracy });
    },
    async grant(origin, permissions) {
      await browser.send("Browser.grantPermissions", { origin, permissions });
    },
    async screenshot(name) {
      const { data } = await page.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
      writeFileSync(join(outputDir, `${name}.png`), Buffer.from(data, "base64"));
    },
    async addInitScript(source) {
      await page.send("Page.addScriptToEvaluateOnNewDocument", { source });
    },
    async close() {
      try {
        await browser.send("Browser.close");
      } catch {
        proc.kill();
      }
      page.close();
      browser.close();
    },
  };
}
