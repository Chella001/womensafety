# SafeHer UI tests

`run.mjs` drives the **real frontend code** in headless Microsoft Edge (or Chrome) over the DevTools protocol, against
test doubles:

| Real | Replaced by |
|---|---|
| Firebase phone sign-in (loaded from www.gstatic.com) | `lib/fake-firebase.mjs`, injected in place of the CDN files. Code `123456` is accepted; numbers ending in `0000` are invalid. |
| Spring Boot API | `lib/mock-api.mjs`, an in-process mock with the same JSON shapes and error codes. |
| VS Code Live Server | `lib/static-server.mjs`, serving the project folder; `config.js` is served with `apiBaseUrl` pointing at the mock, and unknown paths get `frontend/404.html` with status 404. |

So the suite verifies browser behaviour only. It says nothing about the real Firebase project or the real backend —
run `.\mvnw.cmd verify` in `backend/` for the API, and test against the real services by hand.

## Run

Needs **Node 22 or newer** (for the built-in `fetch` and `WebSocket`; no npm install) and Microsoft Edge.

```powershell
node tests/ui/run.mjs
```

If your `node` is older, the Node bundled with VS Code works:

```powershell
$env:ELECTRON_RUN_AS_NODE = 1; & "$env:LOCALAPPDATA\Programs\Microsoft VS Code\Code.exe" tests/ui/run.mjs; Remove-Item Env:ELECTRON_RUN_AS_NODE
```

Options (environment variables): `EDGE_PATH` (browser executable), `UI_WEB_PORT` (5599), `UI_API_PORT` (8899),
`UI_CDP_PORT` (9339). The real backend on 8080 does not need to be stopped.

Results are printed and written to `tests/ui/output/results.txt`, with screenshots in `tests/ui/output/` (ignored by git).
The exit code is non-zero when any check fails.
