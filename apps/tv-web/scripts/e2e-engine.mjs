// Headless check of the whole TV app against the mock control plane: pairs, opens a title whose relay copy is an MKV
// with two audio tracks and an embedded SRT, and checks that the tv-media engine plays it, switches audio and shows the
// embedded subtitle. Needs Chrome for Testing (CHROME_PATH or the Playwright cache) and the tv-media fixtures
// (npm run fixtures in libs/tv-media). Usage: node scripts/e2e-engine.mjs
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(here, "..");
const fixture = path.resolve(app, "../../libs/tv-media/fixtures/mkv-demo.mkv");
function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const cache = path.join(os.homedir(), "Library/Caches/ms-playwright");
  const dirs = fs.existsSync(cache) ? fs.readdirSync(cache).filter((d) => /^chromium-\d+$/.test(d)).sort((a, b) => Number(b.slice(9)) - Number(a.slice(9))) : [];
  for (const d of dirs) {
    const p = path.join(cache, d, "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
    if (fs.existsSync(p)) return p;
  }
  return "";
}
const chrome = findChrome();
if (!fs.existsSync(fixture)) { console.error(`missing ${fixture}; run npm run fixtures in libs/tv-media`); process.exit(2); }
if (!chrome) { console.error("Chrome for Testing not found; set CHROME_PATH"); process.exit(2); }

const MOCK_PORT = 8788;
const failures = [];
const check = (ok, what) => { console.log(`${ok ? "ok  " : "FAIL"} ${what}`); if (!ok) failures.push(what); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(fn, ms = 15000, what = "condition") {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { const v = await fn(); if (v) return v; } catch { /* page navigating */ } await sleep(100); }
  throw new Error(`timed out waiting for ${what}`);
}

const build = (extra = {}) => {
  const r = spawnSync("npx", ["vite", "build", "--outDir", "dist-mock"], { cwd: app, env: { ...process.env, VITE_API_BASE: `http://localhost:${MOCK_PORT}`, ...extra }, encoding: "utf8" });
  if (r.status !== 0) { console.error(r.stdout, r.stderr); process.exit(1); }
};
build();

let mock = null;
const startMock = async (file) => {
  mock?.kill();
  mock = spawn("node", ["scripts/mock-api.mjs", String(MOCK_PORT)], { cwd: app, env: { ...process.env, MOCK_VIDEO: file }, stdio: "ignore" });
  await until(() => fetch(`http://localhost:${MOCK_PORT}/api/me`).then((r) => r.ok), 5000, "mock API");
};
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
const browser = await chromium.launch({ executablePath: chrome, args: ["--autoplay-policy=no-user-gesture-required"] });
try {
  await startMock(fixture);
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  page.on("pageerror", (e) => console.log("[pageerror]", e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);

  await until(() => page.evaluate(() => /Who's watching/.test(document.body.innerText)), 10000, "profile screen");
  await page.keyboard.press("Enter"); // Mason
  await until(() => page.evaluate(() => !!document.querySelector(".card")), 10000, "library");
  // Same as OK on that card (a scripted focus() does not leave the library's key handling in a pressable state).
  await page.evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Big Buck Bunny/.test(e.textContent)).onSelect());

  const video = () => page.evaluate(() => { const v = document.querySelector("video"); return v && { t: v.currentTime, paused: v.paused, src: v.currentSrc.slice(0, 5), error: v.error && v.error.message, dur: v.duration }; });
  await until(async () => (await video())?.t > 1, 15000, "playback to start");
  const v0 = await video();
  check(v0.src === "blob:", "plays through the engine (MediaSource blob URL)");
  check(!v0.error, "no media element error");

  // route the element through an analyser so the playing audio can be told apart: 440 Hz vs 880 Hz
  await page.evaluate(() => {
    const v = document.querySelector("video"), ctx = new AudioContext(), src = ctx.createMediaElementSource(v), an = ctx.createAnalyser();
    an.fftSize = 4096; src.connect(an); an.connect(ctx.destination); window.__an = an; void ctx.resume();
  });
  const hz = () => page.evaluate(() => {
    const an = window.__an, bins = new Float32Array(an.frequencyBinCount); an.getFloatFrequencyData(bins);
    let best = 0; for (let i = 1; i < bins.length; i++) if (bins[i] > bins[best]) best = i;
    return bins[best] < -90 ? 0 : (best * an.context.sampleRate) / an.fftSize;
  });
  const near = (a, b) => Math.abs(a - b) < 40;
  await until(async () => near(await hz(), 440), 8000, "440 Hz first audio track");
  check(true, "first audio track plays (440 Hz)");

  // Audio / Subtitles menu: Down shows controls, Down timeline, Down the Audio/Subtitles button, OK opens it.
  for (let i = 0; i < 3; i++) { await page.keyboard.press("ArrowDown"); await sleep(150); }
  await page.keyboard.press("Enter");
  await sleep(300);
  const menuText = await page.evaluate(() => document.querySelector(".player-menu")?.innerText.replace(/\s+/g, " "));
  check(/English/.test(menuText ?? "") && /German/.test(menuText ?? ""), `audio menu lists both tracks by language (${menuText})`);
  check(/English · in file/.test(menuText ?? ""), "subtitle menu lists the embedded track");

  const before = (await video()).t;
  await page.keyboard.press("ArrowDown"); await sleep(150);
  await page.keyboard.press("Enter");
  await until(async () => near(await hz(), 880), 10000, "880 Hz after switching audio");
  const after = await video();
  check(true, "switching audio plays the second track (880 Hz)");
  check(after.t >= before && after.t < before + 8 && !after.paused, `playback continued at ${after.t.toFixed(1)} s, not from 0`);

  // embedded subtitle: Right = subtitles section, Down = first track after Off, OK = select
  await page.keyboard.press("ArrowRight"); await sleep(150);
  await page.keyboard.press("ArrowDown"); await sleep(150);
  await page.keyboard.press("Enter"); await sleep(200);
  await page.keyboard.press("Escape"); // back out of the menu
  await page.evaluate(() => { document.querySelector("video").currentTime = 1.5; });
  const line = () => page.evaluate(() => document.querySelector(".subtitle-line")?.innerText);
  await until(async () => /Hello world/.test((await line()) ?? ""), 8000, "embedded subtitle text");
  check(true, "embedded SRT subtitle is drawn at 1.5 s");
  await page.evaluate(() => { document.querySelector("video").currentTime = 5; });
  await until(async () => /Second line\s*with a break/.test(((await line()) ?? "").replace(/\n/g, " ")), 8000, "second cue");
  check(true, "seek to 5 s shows the next cue");

  // A format the engine cannot mux (VP9 here) falls back to the TV's own player instead of failing.
  const vp9 = path.resolve(app, "../../libs/tv-media/fixtures/webm-vp9-opus.webm");
  if (fs.existsSync(vp9)) {
    await startMock(vp9);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    // the paired profile opens by itself on a return visit
    await until(() => page.evaluate(() => !!document.querySelector(".card")), 10000, "library");
    await page.evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Big Buck Bunny/.test(e.textContent)).onSelect());
    await until(async () => (await video())?.t > 1, 15000, "fallback playback to start");
    const f = await video();
    check(f.src === "http:", `unsupported codec falls back to the plain player (src ${f.src})`);
    check(!f.error, "fallback plays without error");
  }

  // Direct Telegram playback (VITE_TG_DIRECT=1 build): without a session the TV says so instead of failing obscurely.
  build({ VITE_TG_DIRECT: "1" });
  await startMock(fixture);
  const openCosmos = async () => {
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await until(() => page.evaluate(() => !!document.querySelector(".card")), 10000, "library");
    await page.evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Cosmos Laundromat/.test(e.textContent)).onSelect());
  };
  const screenText = () => page.evaluate(() => document.body.innerText.replace(/\s+/g, " "));
  await openCosmos();
  await until(async () => /Telegram isn't set up on this TV yet/.test(await screenText()), 15000, "no-session message").catch(async (e) => { console.log("[screen]", (await screenText()).slice(0, 300)); throw e; });
  check(true, "Telegram title without a session explains that Telegram isn't set up");
  await page.evaluate(() => localStorage.setItem("cablegram.tv.tgSession", "not-a-session"));
  await openCosmos();
  await until(async () => /Telegram couldn't deliver this video/.test(await screenText()), 15000, "bad-session message");
  check(true, "Telegram title with an unusable session fails with a Telegram message, not a crash");

  // Diagnostics screen (also what a friend's TV runs): every check reports, the clip plays, a QR code appears.
  await page.goto(`http://127.0.0.1:${server.address().port}/#diag`);
  await page.reload();
  await until(() => page.evaluate(() => /Done\./.test(document.querySelector(".diag-sub")?.textContent ?? "")), 60000, "diagnostics to finish");
  const rows = await page.evaluate(() => [...document.querySelectorAll(".diag-row")].map((r) => ({ mark: r.querySelector(".diag-mark").textContent, label: r.querySelector(".diag-label").textContent, detail: r.querySelector(".diag-detail").textContent })));
  const bad = rows.filter((r) => r.mark === "✗");
  check(bad.length === 0, `diagnostics: no failed row (${bad.map((r) => `${r.label}: ${r.detail}`).join("; ") || "none"})`);
  for (const label of ["Time to first picture", "1080p decoding", "Subtitles from the file", "Switching audio track", "Seeking"]) {
    check(rows.some((r) => r.label === label && r.mark === "✓"), `diagnostics: ${label}`);
  }
  check(await page.evaluate(() => !!document.querySelector(".diag-qr canvas")), "diagnostics: results QR code shown");
  await page.evaluate(() => [...document.querySelectorAll(".focusable")].find((e) => /Measure buffer size/.test(e.textContent)).onSelect());
  await until(() => page.evaluate(() => [...document.querySelectorAll(".diag-row")].some((r) => /MSE buffer size/.test(r.textContent) && !/measuring/.test(r.textContent)) && /Done\./.test(document.querySelector(".diag-sub").textContent)), 150000, "buffer measurement");
  const buf = await page.evaluate(() => [...document.querySelectorAll(".diag-row")].find((r) => /MSE buffer size/.test(r.textContent)).textContent);
  check(/MB of 1080p video/.test(buf), `diagnostics: buffer probe reports a size (${buf.replace(/\s+/g, " ")})`);
} catch (e) {
  console.error(e.message);
  failures.push(e.message);
} finally {
  await browser.close();
  server.close();
  mock?.kill();
}
process.exit(failures.length ? 1 : 0);
