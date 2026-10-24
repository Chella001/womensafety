// Serves the project folder the way VS Code Live Server does (so /frontend/... and the root index.html behave the
// same), with two test-only changes:
//   - frontend/js/config.js is served with apiBaseUrl pointing at the mock API;
//   - unknown paths get frontend/404.html with status 404, as a static host such as Firebase Hosting would.
import { createServer } from "node:http";
import { existsSync, readFileSync, statSync } from "node:fs";
import { extname, join, normalize, resolve } from "node:path";

const TYPES = {
  ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".mjs": "text/javascript",
  ".svg": "image/svg+xml", ".png": "image/png", ".json": "application/json", ".ico": "image/x-icon",
};

export function startStaticServer({ root, port, apiBaseUrl }) {
  const base = resolve(root);
  const server = createServer((req, res) => {
    let path = decodeURIComponent(new URL(req.url, "http://x").pathname);
    let file = normalize(join(base, path));
    if (!file.startsWith(base)) {
      res.writeHead(403).end();
      return;
    }
    if (existsSync(file) && statSync(file).isDirectory()) file = join(file, "index.html");
    if (!existsSync(file)) {
      res.writeHead(404, { "Content-Type": TYPES[".html"] });
      res.end(readFileSync(join(base, "frontend", "404.html")));
      return;
    }
    let body = readFileSync(file);
    if (file.endsWith(join("frontend", "js", "config.js"))) {
      body = Buffer.from(body.toString("utf8").replace(/export const apiBaseUrl = "[^"]*";/, `export const apiBaseUrl = "${apiBaseUrl}";`));
    }
    res.writeHead(200, { "Content-Type": TYPES[extname(file)] ?? "application/octet-stream", "Cache-Control": "no-store" });
    res.end(body);
  });
  return new Promise((ready) => server.listen(port, () => ready(server)));
}
