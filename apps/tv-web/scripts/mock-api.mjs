// Dev only: a fake control plane for design work. Pairs instantly and serves a few sample titles, so the library
// can be seen without a phone. Usage: node scripts/mock-api.mjs [port]; build with VITE_API_BASE=http://localhost:<port>
import { createReadStream, existsSync, statSync } from "node:fs";
import http from "node:http";

const port = Number(process.argv[2] ?? 8788);
const phone = (id) => [{ kind: "phone_local", origin_identity: id, serving_device_id: "ph1" }];
const day = (n) => new Date(Date.UTC(2026, 9, n)).toISOString();
const items = [
  { id: "1", title: "Sintel", year: 2010, duration_seconds: 888, genres: ["Animation", "Fantasy"], created_at: day(18),
    overview: "A lonely young woman, Sintel, helps and befriends a dragon, whom she calls Scales.",
    progress: { position_seconds: 420, state: "paused" }, sources: phone("s1") },
  { id: "2", title: "Elephants Dream", year: 2006, duration_seconds: 654, genres: ["Animation", "Science Fiction"], created_at: day(17),
    progress: { position_seconds: 90, state: "paused" }, sources: phone("s2") },
  { id: "3", title: "Big Buck Bunny", year: 2008, duration_seconds: 596, genres: ["Animation", "Comedy"], created_at: day(16), in_my_list: true, sources: phone("s3") },
  { id: "4", title: "Tears of Steel", year: 2012, duration_seconds: 734, genres: ["Science Fiction"], created_at: day(15), sources: [{ kind: "own_cloud", origin_identity: "s4" }] },
  { id: "5", title: "Cosmos Laundromat", year: 2015, duration_seconds: 731, genres: ["Animation"], created_at: day(14), sources: [{ kind: "telegram", origin_identity: "tg:1:5" }] },
  { id: "6", title: "VID-20261018-WA0012", duration_seconds: 75, created_at: day(19), sources: [{ kind: "phone_local", origin_identity: "s6", serving_device_id: "ph1", private: true }] },
  { id: "7", title: "Spring", year: 2019, duration_seconds: 464, genres: ["Animation", "Fantasy"], created_at: day(12), sources: phone("s7") },
  { id: "8", title: "Agent 327 Operation Barbershop", year: 2017, duration_seconds: 231, genres: ["Comedy"], created_at: day(11), sources: phone("s8") },
  ...[1, 2, 3, 4].map((e) => ({ id: `s1e${e}`, title: "Charge", media_type: "tv", tmdb_id: 9001, season_number: 1, episode_number: e,
    episode_title: ["Pilot", "Second wind", "The long night", "Home"][e - 1], duration_seconds: 2700, genres: ["Drama"], created_at: day(10 + e),
    progress: e === 2 ? { position_seconds: 1200, state: "paused" } : undefined, sources: phone(`c${e}`) })),
].map((i) => ({ media_type: "movie", ...i }));

const routes = {
  "POST /api/auth/device": { session_id: "m1", pin: "123456", pairing_token: "pt", expires_at: new Date(Date.now() + 6e5).toISOString() },
  "GET /api/auth/device/m1": { status: "paired", access_token: "mock-token" },
  "GET /api/profiles": { profiles: [{ id: "p1", name: "Mason", pin_set: false }, { id: "p2", name: "Robin", pin_set: true }] },
  "POST /api/profiles/p2/unlock": {},
  "POST /api/profiles/switch-requests": { requestId: "r1", status: "approved" },
  "GET /api/me": { devices: [{ id: "ph1", kind: "phone" }] },
  "POST /api/relay/ticket": { ticket: "rt" },
  // Dummy application identity (not a real api_id/api_hash): enough for the TV app to build a Telegram client.
  "GET /api/telegram/client-config": { api_id: 1, api_hash: "0".repeat(32) },
  // Sintel has a Cablegram subtitle, so the player's subtitle drawing can be seen.
  "GET /api/subtitles/playback/item/1": { subtitles: [{ trackId: "sub1", language: "en", label: "English", isDefault: true,
    url: "/api/subtitles/sub1/content",
    content: "WEBVTT\n\n00:00:00.500 --> 00:00:30.000\nThis is a Cablegram subtitle\nsynced on the phone\n" }] },
};

http.createServer((req, res) => {
  const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type", "access-control-allow-methods": "*",
    "access-control-expose-headers": "content-range, accept-ranges, content-length" };
  if (req.method === "OPTIONS") { res.writeHead(204, cors).end(); return; }
  const path = (req.url ?? "").split("?")[0];
  // MOCK_VIDEO=<file> serves that file for every relay media request (with Range support, as the relay does), so the
  // player can be seen working. An MKV with two audio tracks and an embedded SRT shows the tv-media engine.
  if (path.startsWith("/relay/") && process.env.MOCK_VIDEO && existsSync(process.env.MOCK_VIDEO)) {
    const file = process.env.MOCK_VIDEO;
    const size = statSync(file).size;
    const type = file.endsWith(".mkv") ? "video/x-matroska" : file.endsWith(".mp4") ? "video/mp4" : "video/webm";
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? "");
    const start = m ? Number(m[1]) : 0;
    const end = m && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
    if (start >= size) { res.writeHead(416, cors).end(); return; }
    res.writeHead(m ? 206 : 200, { ...cors, "content-type": type, "accept-ranges": "bytes", "content-length": end - start + 1,
      ...(m ? { "content-range": `bytes ${start}-${end}/${size}` } : {}) });
    createReadStream(file, { start, end }).pipe(res);
    return;
  }
  const body = path === "/api/catalog/items" ? { items } : routes[`${req.method} ${path}`]
    ?? (path.startsWith("/api/subtitles/playback/item/") ? { subtitles: [] } : null)
    ?? (req.method === "PUT" || req.method === "DELETE" ? {} : null);
  res.writeHead(body ? 200 : 404, { ...cors, "content-type": "application/json" });
  res.end(JSON.stringify(body ?? { error: "not_found" }));
}).listen(port, () => console.log(`mock control plane on http://localhost:${port}`));
