import "./style.css";
import { ApiError, createApi } from "./api";
import { API_BASE, WEB_APP_BASE } from "./config";
import { focusFirst, keyFromEvent, moveFocus, registerPlatformKeys } from "./nav";
import { pairLink, qrCanvas } from "./pairlink";
import { loadSession, saveSession } from "./session";
import type { DeviceSession, Profile, Title } from "./types";

const root = document.getElementById("app")!;
const api = createApi({ token: () => loadSession()?.token });
let backHandler: (() => void) | null = null;
let activate: ((digit: string) => void) | null = null;

/** Builds an element; text goes through textContent so titles from the catalog can never inject markup. */
function h(tag: string, cls?: string, text?: string, children: Node[] = []): HTMLElement {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (text !== undefined) el.textContent = text;
  children.forEach((c) => el.appendChild(c));
  return el;
}

function focusable(tag: string, cls: string, text: string, onSelect: () => void): HTMLElement {
  const el = h(tag, `focusable ${cls}`, text);
  el.tabIndex = 0;
  (el as HTMLElement & { onSelect?: () => void }).onSelect = onSelect;
  return el;
}

function show(nodes: Node[], back: (() => void) | null = null, digit: ((d: string) => void) | null = null) {
  root.textContent = "";
  nodes.forEach((n) => root.appendChild(n));
  backHandler = back;
  activate = digit;
  focusFirst(root);
}

function deviceName(): string {
  const ua = navigator.userAgent;
  if (/Tizen/i.test(ua)) return "Samsung TV";
  if (/Web0S|webOS/i.test(ua)) return "LG TV";
  return "TV";
}

// ---- Pairing ----------------------------------------------------------------------------------------------------

async function pair() {
  show([h("h1", undefined, "Cablegram"), h("p", undefined, "Getting a pairing code…")]);
  let session: DeviceSession;
  try {
    session = await api.createDeviceSession(deviceName());
  } catch {
    show([h("h1", undefined, "Can't reach Cablegram"), h("p", "error", `Check the TV's internet connection (${API_BASE}).`),
      focusable("div", "btn", "Try again", pair)]);
    return;
  }
  const link = pairLink(WEB_APP_BASE, session.pin, deviceName());
  const qr = qrCanvas(link);
  qr.style.cssText = "position:absolute;right:96px;top:96px;border-radius:12px";
  show([
    h("h1", undefined, "Pair this TV"),
    h("p", undefined, "On your phone, open Cablegram → Add a TV and enter this code, or scan the QR code."),
    h("div", "pin", session.pin),
    h("p", undefined, link),
    qr,
  ]);
  const deadline = Date.parse(session.expiresAt) || Date.now() + 10 * 60_000;
  const timer = window.setInterval(async () => {
    if (Date.now() > deadline) { clearInterval(timer); pair(); return; }
    try {
      const status = await api.pairingStatus(session);
      if (status.status === "paired" && status.accessToken) {
        clearInterval(timer);
        saveSession({ token: status.accessToken, lanCapability: status.lanCapability });
        profiles();
      }
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 410)) { clearInterval(timer); pair(); }
    }
  }, 2000);
  backHandler = () => clearInterval(timer);
}

// ---- Profiles ---------------------------------------------------------------------------------------------------

async function profiles() {
  let list: Profile[];
  try {
    list = await api.profiles();
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) { saveSession(null); pair(); return; }
    show([h("h1", undefined, "Can't load profiles"), focusable("div", "btn", "Try again", profiles)]);
    return;
  }
  if (list.length === 1 && !list[0].pinSet) { openProfile(list[0]); return; }
  show([
    h("h1", undefined, "Who's watching?"),
    h("div", "row", undefined, list.map((p) => focusable("div", "profile", p.name, () => selectProfile(p)))),
    focusable("div", "btn", "Unpair this TV", () => { saveSession(null); pair(); }),
  ]);
}

function selectProfile(p: Profile) {
  if (!p.pinSet) { openProfile(p); return; }
  let entered = "";
  const dots = h("div", "pin", "");
  const message = h("p", "error", "");
  const submit = async () => {
    const status = await api.unlockProfile(p.id, entered);
    if (status < 300) { openProfile(p); return; }
    entered = "";
    dots.textContent = "";
    message.textContent = status === 429 ? "Too many tries. Wait a few minutes." : "Wrong PIN.";
  };
  const pad = h("div", "keypad", undefined, "1234567890".split("").map((d) =>
    focusable("div", "key", d, () => { entered += d; dots.textContent = "•".repeat(entered.length); })));
  pad.appendChild(focusable("div", "key", "OK", () => { if (entered.length >= 4) void submit(); }));
  show([h("h1", undefined, `PIN for ${p.name}`), dots, message, pad], profiles,
    (d) => { entered += d; dots.textContent = "•".repeat(entered.length); });
}

function openProfile(p: Profile) {
  const s = loadSession();
  if (s) saveSession({ ...s, profileId: p.id });
  library(p);
}

// ---- Library ----------------------------------------------------------------------------------------------------

async function library(p: Profile) {
  show([h("h1", undefined, "Loading your library…")]);
  let items: Title[];
  try {
    items = await api.catalog(p.id);
  } catch {
    show([h("h1", undefined, "Can't load your library"), focusable("div", "btn", "Try again", () => library(p))], profiles);
    return;
  }
  const continueRow = items.filter((t) => t.resumeSeconds);
  const myList = items.filter((t) => t.inMyList);
  const rows: Node[] = [h("h1", undefined, `Hi, ${p.name}`)];
  const addRow = (title: string, list: Title[]) => {
    if (!list.length) return;
    rows.push(h("h2", undefined, title), h("div", "row", undefined, list.map((t) => card(t, p))));
  };
  addRow("Continue watching", continueRow);
  addRow("My list", myList);
  addRow("All titles", items);
  if (!items.length) rows.push(h("p", undefined, "Your library is empty. Add videos from the Cablegram phone app."));
  show(rows, profiles);
}

function card(t: Title, p: Profile): HTMLElement {
  const art = h("div", "art");
  if (t.posterUrl) art.style.backgroundImage = `url("${t.posterUrl.replace(/"/g, "%22")}")`;
  const el = focusable("div", "card", "", () => play(t, p, () => library(p)));
  el.appendChild(art);
  el.appendChild(h("div", "name", t.title));
  return el;
}

// ---- Player -----------------------------------------------------------------------------------------------------

async function play(t: Title, p: Profile, back: () => void) {
  show([h("h1", undefined, t.title), h("p", undefined, "Starting playback…")], back);
  let playback;
  try {
    playback = await api.resolvePlayback(t, loadSession()?.lanCapability);
  } catch (e) {
    const offline = e instanceof ApiError && e.status === 503;
    show([h("h1", undefined, t.title), h("p", "error", offline ? "Your phone isn't online." : "Couldn't start this video."),
      focusable("div", "btn", "Try again", () => play(t, p, back)), focusable("div", "btn", "Back", back)], back);
    return;
  }
  if (playback.status !== "ready" || !playback.url) {
    show([h("h1", undefined, t.title), h("p", undefined, playback.message ?? "This video can't play right now."),
      focusable("div", "btn", "Back", back)], back);
    return;
  }
  startPlayer(t, p, playback.url, back);
}

function startPlayer(t: Title, p: Profile, url: string, back: () => void) {
  const video = document.createElement("video");
  video.src = url;
  video.autoplay = true;
  const bar = h("i");
  const clock = h("span", undefined, "");
  const hud = h("div", undefined, undefined, [h("span", undefined, `${t.title}  `), clock, h("div", undefined, undefined)]);
  hud.id = "hud";
  hud.lastChild!.appendChild(bar);
  (hud.lastChild as HTMLElement).id = "bar";
  const wrap = h("div", undefined, undefined, [video, hud]);
  wrap.id = "player";
  show([wrap]);

  const fmt = (s: number) => `${Math.floor(s / 60)}:${("0" + Math.floor(s % 60)).slice(-2)}`;
  let lastSave = 0;
  const save = (state: "playing" | "paused" | "stopped" | "completed") =>
    api.saveProgress(p.id, t.id, video.currentTime, isFinite(video.duration) ? video.duration : t.durationSeconds, state).catch(() => {});
  video.addEventListener("loadedmetadata", () => { if (t.resumeSeconds && t.resumeSeconds < video.duration - 10) video.currentTime = t.resumeSeconds; });
  video.addEventListener("timeupdate", () => {
    clock.textContent = `${fmt(video.currentTime)} / ${isFinite(video.duration) ? fmt(video.duration) : "--:--"}`;
    bar.style.width = `${isFinite(video.duration) && video.duration ? (video.currentTime / video.duration) * 100 : 0}%`;
    if (Date.now() - lastSave > 15_000) { lastSave = Date.now(); void save(video.paused ? "paused" : "playing"); }
  });
  video.addEventListener("pause", () => void save("paused"));
  video.addEventListener("ended", () => { void save("completed"); stop(); });
  video.addEventListener("error", () => {
    stop();
    show([h("h1", undefined, t.title), h("p", "error", "This TV couldn't play the video (format or connection)."),
      focusable("div", "btn", "Back", back)], back);
  });

  const stop = () => { void save("stopped"); video.removeAttribute("src"); video.load(); back(); };
  const toggle = () => (video.paused ? video.play() : video.pause());
  playerKeys = (k) => {
    if (k === "back" || k === "stop") stop();
    else if (k === "enter" || k === "playpause") toggle();
    else if (k === "play") video.play();
    else if (k === "pause") video.pause();
    else if (k === "right" || k === "ff") video.currentTime += 30;
    else if (k === "left" || k === "rw") video.currentTime -= 10;
  };
  backHandler = stop;
}
let playerKeys: ((k: ReturnType<typeof keyFromEvent>) => void) | null = null;

// ---- Keys -------------------------------------------------------------------------------------------------------

document.addEventListener("keydown", (e) => {
  const key = keyFromEvent(e);
  if (!key) return;
  if (document.getElementById("player")) { e.preventDefault(); playerKeys?.(key); return; }
  playerKeys = null;
  e.preventDefault();
  if (key === "back") { backHandler?.(); return; }
  if (key === "digit") { activate?.(String.fromCharCode(e.keyCode)); return; }
  if (key === "enter") {
    const el = document.activeElement as (HTMLElement & { onSelect?: () => void }) | null;
    el?.onSelect?.();
    return;
  }
  if (key === "up" || key === "down" || key === "left" || key === "right") moveFocus(key, root);
});

registerPlatformKeys();
if (loadSession()?.token) void profiles(); else void pair();
