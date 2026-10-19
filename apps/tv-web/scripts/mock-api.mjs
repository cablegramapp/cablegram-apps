// Dev only: a fake control plane for design work. Pairs instantly and serves a few sample titles, so the library
// can be seen without a phone. Usage: node scripts/mock-api.mjs [port]; build with VITE_API_BASE=http://localhost:<port>
import http from "node:http";

const port = Number(process.argv[2] ?? 8788);
const phone = (id) => [{ kind: "phone_local", origin_identity: id, serving_device_id: "ph1" }];
const items = [
  { id: "1", title: "Sintel", year: 2010, duration_seconds: 888, progress: { position_seconds: 420, state: "paused" }, sources: phone("s1") },
  { id: "2", title: "Elephants Dream", year: 2006, duration_seconds: 654, progress: { position_seconds: 90, state: "paused" }, sources: phone("s2") },
  { id: "3", title: "Big Buck Bunny", year: 2008, duration_seconds: 596, in_my_list: true, sources: phone("s3") },
  { id: "4", title: "Tears of Steel", year: 2012, duration_seconds: 734, sources: [{ kind: "own_cloud", origin_identity: "s4" }] },
  { id: "5", title: "Cosmos Laundromat", year: 2015, duration_seconds: 731, sources: [{ kind: "telegram", origin_identity: "tg:1:5" }] },
  { id: "6", title: "VID-20261018-WA0012", duration_seconds: 75, sources: phone("s6") },
  { id: "7", title: "Spring", year: 2019, duration_seconds: 464, sources: phone("s7") },
  { id: "8", title: "Agent 327 Operation Barbershop", year: 2017, duration_seconds: 231, sources: phone("s8") },
].map((i) => ({ media_type: "movie", ...i }));

const routes = {
  "POST /api/auth/device": { session_id: "m1", pin: "123456", pairing_token: "pt", expires_at: new Date(Date.now() + 6e5).toISOString() },
  "GET /api/auth/device/m1": { status: "paired", access_token: "mock-token" },
  "GET /api/profiles": { profiles: [{ id: "p1", name: "Mason", pin_set: false }] },
  "GET /api/me": { devices: [{ id: "ph1", kind: "phone" }] },
  "POST /api/relay/ticket": { ticket: "rt" },
};

http.createServer((req, res) => {
  const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type", "access-control-allow-methods": "*" };
  if (req.method === "OPTIONS") { res.writeHead(204, cors).end(); return; }
  const path = (req.url ?? "").split("?")[0];
  const body = path === "/api/catalog/items" ? { items } : routes[`${req.method} ${path}`] ?? (req.method === "PUT" ? {} : null);
  res.writeHead(body ? 200 : 404, { ...cors, "content-type": "application/json" });
  res.end(JSON.stringify(body ?? { error: "not_found" }));
}).listen(port, () => console.log(`mock control plane on http://localhost:${port}`));
