// Plays the mock MKV (two audio tracks, embedded SRT) in the Samsung TV simulator (Chromium 55) through the tv-media
// engine, and probes the GramJS bundle. Expects the simulator already running with --remote-debugging-port=9333 and the
// app installed (see README "Simulators"), and the mock API on :8788 serving tv-media/fixtures/mkv-demo.mkv.
// Usage: node scripts/sim-tizen.mjs
import { chromium } from "playwright-core";

// THROTTLE=6 slows the simulator's CPU 6x (CDP Emulation.setCPUThrottlingRate), to stand in for a TV's weak processor.
const THROTTLE = Number(process.env.THROTTLE || 1);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, what) => { console.log(`${ok ? "ok  " : "FAIL"} ${what}`); if (!ok) failures.push(what); };

const browser = await chromium.connectOverCDP("http://localhost:9333");
const page = browser.contexts()[0].pages().find((p) => p.url().includes("ripple.html"));
const app = () => page.frames().find((f) => f.url().includes("CblGramTV1"));
const text = async () => (await app().evaluate(() => document.body.innerText)).replace(/\s+/g, " ");
async function until(fn, ms, what) {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { const v = await fn(); if (v) return v; } catch { /* reloading */ } await sleep(150); }
  throw new Error(`timed out waiting for ${what}`);
}
// A remote key press: the app reads keyCode from keydown/keyup on the document.
const press = (code) => app().evaluate((c) => {
  for (const type of ["keydown", "keyup"]) { const e = new Event(type, { bubbles: true, cancelable: true }); e.keyCode = c; document.dispatchEvent(e); }
}, code);
const KEY = { enter: 13, down: 40, right: 39 };

try {
  if (THROTTLE > 1) {
    const cdp = await page.context().newCDPSession(page);
    try { await cdp.send("Emulation.setCPUThrottlingRate", { rate: THROTTLE }); console.log(`CPU throttled ${THROTTLE}x`); }
    catch (e) { console.log(`this Chromium cannot throttle the CPU over CDP (${e.message.slice(0, 80)})`); }
  }
  let started = Date.now();
  console.log("user agent:", await page.evaluate(() => navigator.userAgent));
  await app().evaluate(() => { localStorage.clear(); location.reload(); });
  await sleep(3000); // let the reload happen before looking at the page
  await until(async () => /Who's watching/.test(await text()) && (await app().evaluate(() => /profile-tile/.test(document.activeElement.className))), 15000, "profile screen");
  await press(KEY.enter); // Mason has focus
  await until(() => app().evaluate(() => !!document.querySelector(".card")), 15000, "library");

  const env = await app().evaluate(() => ({
    subtle: !!(window.crypto && crypto.subtle), random: !!(window.crypto && crypto.getRandomValues), secure: window.isSecureContext,
    bigint: typeof BigInt, wasm: typeof WebAssembly, avc: MediaSource.isTypeSupported('video/mp4; codecs="avc1.64001F"'),
    aac: MediaSource.isTypeSupported('audio/mp4; codecs="mp4a.40.2"'), ac3: MediaSource.isTypeSupported('audio/mp4; codecs="ac-3"'),
    hevc: MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.B0"'), opus: MediaSource.isTypeSupported('audio/mp4; codecs="opus"'),
  }));
  console.log("environment:", JSON.stringify(env));

  started = Date.now();
  await app().evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Big Buck Bunny/.test(e.textContent)).onSelect());
  const video = () => app().evaluate(() => { const v = document.querySelector("video"); return v && { t: v.currentTime, src: v.currentSrc.slice(0, 5), error: v.error && v.error.message, dur: v.duration }; });
  await until(async () => (await video())?.t > 1, 20000, "playback to start");
  const v0 = await video();
  check(v0.src === "blob:", "engine playback (MediaSource) in the simulator");
  console.log(`     time to first picture ${((Date.now() - started) / 1000).toFixed(1)} s`);
  check(!v0.error, "no media element error");

  await app().evaluate(() => {
    const v = document.querySelector("video"), ctx = new AudioContext(), src = ctx.createMediaElementSource(v), an = ctx.createAnalyser();
    an.fftSize = 4096; src.connect(an); an.connect(ctx.destination); window.__an = an; ctx.resume();
  });
  const hz = () => app().evaluate(() => {
    const an = window.__an, bins = new Float32Array(an.frequencyBinCount); an.getFloatFrequencyData(bins);
    let best = 0; for (let i = 1; i < bins.length; i++) if (bins[i] > bins[best]) best = i;
    return bins[best] < -90 ? 0 : (best * an.context.sampleRate) / an.fftSize;
  });
  const near = (a, b) => Math.abs(a - b) < 40;
  await until(async () => near(await hz(), 440), 10000, "440 Hz");
  check(true, "first audio track audible (440 Hz)");

  for (let i = 0; i < 3; i++) { await press(KEY.down); await sleep(200); }
  await press(KEY.enter); await sleep(400);
  const menu = await app().evaluate(() => document.querySelector(".player-menu")?.innerText.replace(/\s+/g, " "));
  check(/English/.test(menu) && /German/.test(menu) && /English · in file/.test(menu), `audio and subtitle menu from the file's tracks (${menu})`);
  await press(KEY.down); await sleep(200); await press(KEY.enter);
  await until(async () => near(await hz(), 880), 12000, "880 Hz");
  check(true, "audio switch works (880 Hz)");
  await press(KEY.right); await sleep(200); await press(KEY.down); await sleep(200); await press(KEY.enter); await sleep(300);
  await app().evaluate(() => { document.querySelector("video").currentTime = 1.5; });
  await until(async () => /Hello world/.test(await app().evaluate(() => document.querySelector(".subtitle-line")?.innerText ?? "")), 10000, "subtitle");
  check(true, "embedded subtitle drawn");
  await app().evaluate(() => { document.querySelector("video").currentTime = 9; });
  await until(async () => (await video()).t > 10, 12000, "seek");
  check(true, "seek lands and plays on");

  // Telegram (VITE_TG_DIRECT build): the lazy GramJS chunk must load and fail politely without a session.
  await press(27); await sleep(500); // back to the library
  await until(() => app().evaluate(() => !!document.querySelector(".card")), 15000, "library again");
  await app().evaluate(() => [...document.querySelectorAll(".card")].find((e) => /Cosmos Laundromat/.test(e.textContent)).onSelect());
  await until(async () => /Telegram isn't set up on this TV yet/.test(await text()), 20000, "Telegram no-session message");
  check(true, "GramJS chunk loads in the simulator and reports a missing session");
} catch (e) {
  console.error(e.message);
  failures.push(e.message);
}
await browser.close();
process.exit(failures.length ? 1 : 0);
