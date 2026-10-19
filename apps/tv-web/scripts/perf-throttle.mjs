// How the engine and GramJS's pure-JavaScript decryption cope when the CPU is slowed down, to stand in for a TV's weak
// processor. Chrome's CPU throttling (CDP Emulation.setCPUThrottlingRate) multiplies every task's duration by the rate;
// typical TV chips are 4 to 10 times slower than a laptop core. Needs the fixtures (npm run fixtures in libs/tv-media).
// Usage: node scripts/perf-throttle.mjs [rate ...]      default rates: 1 4 6 10
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { chromium } from "playwright-core";
import { build } from "vite";

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = path.resolve(app, "../../libs/tv-media/fixtures");
const rates = process.argv.slice(2).map(Number).filter(Boolean);
if (!rates.length) rates.push(1, 4, 6, 10);
for (const f of ["mp4-hi.mp4", "mkv-hi.mkv"]) if (!fs.existsSync(path.join(fixtures, f))) { console.error(`missing ${f}; run npm run fixtures in libs/tv-media`); process.exit(2); }

function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const cache = path.join(os.homedir(), "Library/Caches/ms-playwright");
  const dirs = fs.existsSync(cache) ? fs.readdirSync(cache).filter((d) => /^chromium-\d+$/.test(d)).sort((a, b) => Number(b.slice(9)) - Number(a.slice(9))) : [];
  for (const d of dirs) { const p = path.join(cache, d, "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"); if (fs.existsSync(p)) return p; }
  return "";
}
const chrome = findChrome();
if (!chrome) { console.error("Chrome for Testing not found; set CHROME_PATH"); process.exit(2); }

// Same module aliases and defines as the app, without the legacy plugin.
const baseExport = (await import(pathToFileURL(path.join(app, "vite.config.ts")).href)).default;
const base = typeof baseExport === "function" ? baseExport({ mode: "production", command: "build" }) : baseExport;
const out = fs.mkdtempSync(path.join(os.tmpdir(), "perf-"));
await build({
  ...base, configFile: false, root: app, logLevel: "error", plugins: [],
  build: { outDir: out, emptyOutDir: true, minify: "esbuild", target: "es2015", lib: { entry: path.join(app, "scripts/perf-entry.ts"), formats: ["iife"], name: "perf", fileName: () => "perf.js" } },
});
const js = fs.readFileSync(path.join(out, "perf.js"), "utf8");

const server = http.createServer((req, res) => {
  const url = (req.url ?? "/").split("?")[0];
  res.setHeader("access-control-allow-origin", "*");
  res.setHeader("access-control-expose-headers", "content-range, accept-ranges, content-length");
  if (url === "/") { res.writeHead(200, { "content-type": "text/html" }).end('<!doctype html><script src="/perf.js"></script>'); return; }
  if (url === "/perf.js") { res.writeHead(200, { "content-type": "text/javascript" }).end(js); return; }
  const file = path.join(fixtures, path.basename(url));
  if (!fs.existsSync(file)) { res.writeHead(404).end(); return; }
  const size = fs.statSync(file).size;
  const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? "");
  const start = m ? Number(m[1]) : 0, end = m && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
  if (start >= size) { res.writeHead(416).end(); return; }
  res.writeHead(m ? 206 : 200, { "content-type": "video/mp4", "accept-ranges": "bytes", "content-length": end - start + 1, ...(m ? { "content-range": `bytes ${start}-${end}/${size}` } : {}) });
  fs.createReadStream(file, { start, end }).pipe(res);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base_url = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({ executablePath: chrome, headless: true });
const rows = [];
try {
  for (const rate of rates) {
    const page = await browser.newPage();
    const cdp = await page.context().newCDPSession(page);
    await page.goto(base_url + "/");
    await cdp.send("Emulation.setCPUThrottlingRate", { rate });
    const ige = await page.evaluate(() => window.perf.ige(8));
    const mp4 = await page.evaluate((u) => window.perf.pump(u, 25), `${base_url}/mp4-hi.mp4`);
    const mkv = await page.evaluate((u) => window.perf.pump(u, 25), `${base_url}/mkv-hi.mkv`);
    rows.push({ rate, ige, mp4, mkv });
    console.log(`CPU ${rate}x slower: AES-IGE ${ige.toFixed(1)} MB/s | MP4 20 Mbit/s: ${(mp4.mediaSec / mp4.wallSec).toFixed(1)}x real time | MKV: ${(mkv.mediaSec / mkv.wallSec).toFixed(1)}x real time`);
    await page.close();
  }
} finally {
  await browser.close();
  server.close();
}
console.log("\nNeeded: demux/mux well above 1x real time at the stream's bitrate; Telegram download also needs AES-IGE above the stream's MB/s");
console.log("(a 20 Mbit/s stream is 2.5 MB/s, a 4 Mbit/s stream 0.5 MB/s).");
