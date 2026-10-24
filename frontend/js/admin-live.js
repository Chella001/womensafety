/**
 * Live updates for the administrator console.
 *
 * Preferred: a Server-Sent Events stream from the backend, read with fetch() because EventSource cannot send the
 * Authorization header. The stream carries only "something changed" markers; the console then reloads the data through
 * the normal authorised API. Everything degrades safely:
 *   - the stream is closed by the server every few minutes, and is reopened with a FRESH ID token;
 *   - a silent connection (no heartbeat for STALL_MS) is dropped and reopened;
 *   - reconnects back off (1 s, 2 s, 4 s … 30 s);
 *   - after FALLBACK_AFTER failures the console polls slowly (POLL_MS) until the stream is back;
 *   - 401/403 stop everything and are reported to the shell, which signs the admin out.
 * The console shows when it last refreshed and marks the data as stale when nothing has arrived for STALE_MS.
 */

import { ApiError, openAdminStream } from "./api.js";

const STALL_MS = 70_000;
const FALLBACK_AFTER = 3;
const POLL_MS = 60_000;
const MAX_BACKOFF_MS = 30_000;

/**
 * @param {{ getToken: () => Promise<string>,
 *           onSignal: (signal: object) => void,
 *           onState: (state: "live"|"reconnecting"|"polling"|"stopped") => void,
 *           onAuthError: (error: ApiError) => void }} handlers
 */
export function createLive({ getToken, onSignal, onState, onAuthError }) {
  let running = false;
  let controller = null;
  let pollTimer = null;
  let failures = 0;

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  function startPolling() {
    if (pollTimer) return;
    onState("polling");
    pollTimer = setInterval(() => onSignal({ type: "poll" }), POLL_MS);
  }

  function stopPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
  }

  async function readStream(response) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let watchdog = setTimeout(() => controller.abort(), STALL_MS);
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) return;
        clearTimeout(watchdog);
        watchdog = setTimeout(() => controller.abort(), STALL_MS);
        buffer += decoder.decode(value, { stream: true });
        let boundary;
        while ((boundary = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          handleBlock(block);
        }
      }
    } finally {
      clearTimeout(watchdog);
    }
  }

  function handleBlock(block) {
    let name = "message";
    let data = "";
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) name = line.slice(6).trim();
      else if (line.startsWith("data:")) data += line.slice(5).trim();
    }
    if (name === "hello") {
      failures = 0;
      stopPolling();
      onState("live");
      onSignal({ type: "connected" }); // data may have changed while we were away
    } else if (name === "sos" && data) {
      try {
        onSignal(JSON.parse(data));
      } catch {
        onSignal({ type: "unknown" });
      }
    }
  }

  async function loop() {
    while (running) {
      controller = new AbortController();
      try {
        const response = await openAdminStream(await getToken(), controller.signal);
        await readStream(response); // returns when the server ends the stream (its normal timeout)
        failures = 0;
        if (running) onState("reconnecting");
        await sleep(500);
      } catch (error) {
        if (!running) return;
        if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
          running = false;
          stopPolling();
          onState("stopped");
          onAuthError(error);
          return;
        }
        failures += 1;
        onState(failures >= FALLBACK_AFTER ? "polling" : "reconnecting");
        if (failures >= FALLBACK_AFTER) startPolling();
        const retryAfter = error instanceof ApiError && error.retryAfter ? error.retryAfter * 1000 : 0;
        await sleep(Math.max(retryAfter, Math.min(1000 * 2 ** (failures - 1), MAX_BACKOFF_MS)));
      }
    }
  }

  return {
    start() {
      if (running) return;
      running = true;
      onState("reconnecting");
      loop();
    },
    stop() {
      running = false;
      controller?.abort();
      stopPolling();
      onState("stopped");
    },
  };
}
