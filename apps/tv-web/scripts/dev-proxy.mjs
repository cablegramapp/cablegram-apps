// Dev only: forwards to the control plane and adds CORS headers so a TV app loaded from file://, localhost or a
// simulator can talk to it. The real control plane only answers origins listed in WEB_APP_ORIGINS.
// Usage: node scripts/dev-proxy.mjs [port]   then build with VITE_API_BASE=http://localhost:8787
import http from "node:http";
import https from "node:https";

const target = new URL(process.env.TARGET ?? "https://api.cablegram.app");
const port = Number(process.argv[2] ?? 8787);
const cors = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
  "access-control-allow-headers": "authorization, content-type",
};

http.createServer((req, res) => {
  if (req.method === "OPTIONS") { res.writeHead(204, cors).end(); return; }
  const headers = { ...req.headers, host: target.host };
  delete headers.origin;
  const up = https.request({ host: target.hostname, path: req.url, method: req.method, headers }, (r) => {
    res.writeHead(r.statusCode ?? 502, { ...r.headers, ...cors });
    r.pipe(res);
  });
  up.on("error", () => res.writeHead(502, cors).end());
  req.pipe(up);
}).listen(port, () => console.log(`dev proxy on http://localhost:${port} -> ${target.origin}`));
