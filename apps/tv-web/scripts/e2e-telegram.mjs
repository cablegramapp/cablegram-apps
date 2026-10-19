// Live check of direct Telegram playback: the real app (VITE_TG_DIRECT=1 build) in headless Chrome, a mock control plane
// whose catalog points one title at your Telegram message, your real api_id/api_hash and account session. Prints only
// pass/fail lines and playback numbers, never the credentials or the session.
//
//   TG_API_ID=… TG_API_HASH=… TG_SESSION=… TG_IDENTITY=tg:<chat_id>:<message_id> node scripts/e2e-telegram.mjs
//
// <chat_id> is the TDLib chat id (a channel is -100<channel id>); the message must hold a video (MP4 or MKV).
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

// The control plane's own names work too, so `set -a; . …/control-plane/.env; set +a` is enough.
process.env.TG_API_ID ||= process.env.TELEGRAM_API_ID;
process.env.TG_API_HASH ||= process.env.TELEGRAM_API_HASH;
const need = ["TG_API_ID", "TG_API_HASH", "TG_SESSION", "TG_IDENTITY"].filter((k) => !process.env[k]);
if (need.length) { console.error(`missing environment: ${need.join(", ")}`); process.exit(2); }
if (!/^tg:-?\d+:\d+$/.test(process.env.TG_IDENTITY)) { console.error("TG_IDENTITY must look like tg:-1001234567890:42"); process.exit(2); }

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const MOCK_PORT = 8788;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const cache = path.join(os.homedir(), "Library/Caches/ms-playwright");
  const dirs = fs.existsSync(cache) ? fs.readdirSync(cache).filter((d) => /^chromium-\d+$/.test(d)).sort((a, b) => Number(b.slice(9)) - Number(a.slice(9))) : [];
  for (const d of dirs) { const p = path.join(cache, d, "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"); if (fs.existsSync(p)) return p; }
  return "";
}
const chrome = findChrome();
if (!chrome) { console.error("Chrome for Testing not found; set CHROME_PATH"); process.exit(2); }

console.log("building the app (about 10 s)…");
const built = spawnSync("npx", ["vite", "build", "--outDir", "dist-mock"], { cwd: app, env: { ...process.env, VITE_API_BASE: `http://localhost:${MOCK_PORT}`, VITE_TG_DIRECT: "1" }, encoding: "utf8" });
if (built.status !== 0) { console.error("build failed"); process.exit(1); }
const mock = spawn("node", ["scripts/mock-api.mjs", String(MOCK_PORT)], { cwd: app, env: { ...process.env, MOCK_TG_IDENTITY: process.env.TG_IDENTITY }, stdio: "ignore" });
const root = path.join(app, "dist-mock");
const types = { ".js": "text/javascript", ".css": "text/css", ".html": "text/html", ".png": "image/png", ".svg": "image/svg+xml" };
const server = http.createServer((req, res) => {
  let p = path.join(root, (req.url ?? "/").split("?")[0]);
  if (p.endsWith("/")) p += "index.html";
  if (!fs.existsSync(p)) { res.writeHead(404).end(); return; }
  res.writeHead(200, { "content-type": types[path.extname(p)] ?? "application/octet-stream" });
  fs.createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
console.log("starting Chrome and the mock control plane…");
const browser = await chromium.launch({ executablePath: chrome, args: ["--autoplay-policy=no-user-gesture-required"] });
let code = 1;
try {
  for (let i = 0; i < 50; i++) { try { if ((await fetch(`http://localhost:${MOCK_PORT}/api/me`)).ok) break; } catch { /* starting */ } await sleep(100); }
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await page.addInitScript((s) => localStorage.setItem("cablegram.tv.tgSession", s), process.env.TG_SESSION);
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => /Who's watching/.test(document.body.innerText), null, { timeout: 15000 });
  await page.keyboard.press("Enter");
  await page.waitForSelector(".card", { timeout: 15000 });
  await page.evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Cosmos Laundromat/.test(e.textContent)).onSelect());

  const state = () => page.evaluate(() => {
    const v = document.querySelector("video");
    return { text: document.body.innerText.replace(/\s+/g, " ").slice(0, 200), t: v ? v.currentTime : null, dur: v ? v.duration : null, src: v ? v.currentSrc.slice(0, 5) : null, err: v && v.error ? v.error.message : null };
  });
  console.log("opened the title; connecting to Telegram and waiting for playback (up to 90 s)…");
  let s;
  let lastNote = 0;
  const end = Date.now() + 90000;
  while (Date.now() < end) {
    s = await state();
    if (Date.now() - lastNote > 10000) { lastNote = Date.now(); console.log(`  … ${Math.round((Date.now() - (end - 90000)) / 1000)} s: ${s.t == null ? "no video yet" : `video at ${s.t.toFixed(1)} s`}`); }
    if (s.t > 3 || /Couldn't play this video|can't play/.test(s.text)) break;
    await sleep(500);
  }
  if (!(s.t > 3)) { console.log(`FAIL did not start: "${s.text}"`); }
  else {
    console.log(`ok   playing over MTProto: ${s.t.toFixed(1)} s of ${s.dur.toFixed(0)} s (${s.src})`);
    const target = Math.max(5, Math.floor(s.dur * 0.6));
    await page.evaluate((t) => { document.querySelector("video").currentTime = t; }, target);
    const end2 = Date.now() + 60000;
    while (Date.now() < end2) { s = await state(); if (s.t > target + 2) break; await sleep(500); }
    if (s.t > target + 2) { console.log(`ok   seek to ${target} s and playing on (${s.t.toFixed(1)} s)`); code = 0; }
    else console.log(`FAIL seek to ${target} s: at ${s.t?.toFixed(1)} s, "${s.text}"`);
  }
} catch (e) {
  console.log(`FAIL ${e.message}`);
} finally {
  await browser.close();
  server.close();
  mock.kill();
}
process.exit(code);
