// Mock control plane that also serves the web remote from this repo, same origin, for test/e2e/command-outcomes.yaml.
// Run: node test/e2e/mock-control-plane.mjs   (then open http://localhost:8099)
// The command picks the scenario: pause completes after a delay, stop is rejected by the TV, mute completes quickly,
// anything else (play) is never confirmed and expires after 3 s.
import { createServer } from "node:http";
import { readFileSync, existsSync } from "node:fs";
import { extname, join } from "node:path";
const site = new URL("../../", import.meta.url).pathname;
const TV = "11111111-1111-4111-8111-111111111111";
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".webmanifest": "application/json" };
const cmds = new Map();
const json = (res, code, body) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(body)); };
createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  let raw = ""; req.on("data", (c) => raw += c); req.on("end", () => {
    const p = url.pathname;
    if (p === "/api/auth/login") return json(res, 200, { access_token: "a", refresh_token: "r", display_name: "Home" });
    if (p === "/api/me") return json(res, 200, { devices: [{ id: TV, kind: "tv", display_name: "Living room TV" }] });
    if (p === "/api/catalog/items") return json(res, 200, { items: [] });
    if (p === "/api/telegram/link") return json(res, 200, { linked: false });
    if (p === "/api/telegram/tv-logins/pending") return json(res, 200, { requests: [] });
    if (p === "/api/control/commands" && req.method === "POST") {
      const b = JSON.parse(raw); cmds.set(b.id, { ...b, polls: 0 }); console.log("POST", b.command, b.id); return json(res, 201, { id: b.id });
    }
    const m = /^\/api\/control\/commands\/([^/]+)$/.exec(p);
    if (m) {
      const c = cmds.get(m[1]); if (!c) return json(res, 404, { error: "not_found" });
      c.polls++; console.log("POLL", c.command, c.polls);
      let status = c.polls < 2 ? "pending" : "delivered", reason = null;
      if (c.command === "pause" && c.polls >= 4) status = "completed";   // delayed completion
      if (c.command === "stop" && c.polls >= 2) { status = "rejected"; reason = "no_profile_selected"; }
      if (c.command === "mute" && c.polls >= 2) status = "completed";
      return json(res, 200, { id: c.id, command: c.command, target_device_id: TV, status, reason, expires_in_ms: 3000 });  // play never completes
    }
    if (p === "/config.js") { res.writeHead(200, { "content-type": "text/javascript" }); return res.end('export const API_BASE = "";'); }
    const file = join(site, p === "/" ? "index.html" : p);
    if (existsSync(file)) { res.writeHead(200, { "content-type": types[extname(file)] || "text/plain" }); return res.end(readFileSync(file)); }
    res.writeHead(404); res.end();
  });
}).listen(8099, () => console.log("up"));
