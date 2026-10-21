import "./style.css";
import { ApiError, createApi, preflight, relayMessage } from "./api";
import { API_BASE, WEB_APP_BASE } from "./config";
import {
  ASPECTS, AUTO_HIDE_MS, formatSpeed, formatTime, hideControls, initialHud, menuOpen, menuOptions, reduceHud, SCRUB_COMMIT_MS,
  SEEK_FLASH_MS, SEEK_STEP_MS, SPEEDS, timelineActive,
} from "./hud";
import type { Aspect, DpadKey, HudContext, HudEffect, HudState, HudTrack, SettingsRow } from "./hud";
import {
  allGenres, buildShelves, displayTitle, entryBackdrop, entryHint, entryIds, entryInMyList, entryMetadata, entryOverview,
  entryProgress, entryRemaining, entrySourceBadge, episodeBadge, episodeCaption, metadataParts, monogram, nextEpisode,
  placeholderColors, privateThumbnail, progressFraction, remainingLabel, seasonSummary, showTitle, sourceBadge, toEntries,
} from "./library";
import type { Entry, LibraryType, Show } from "./library";
import { focusEl, focusFirst, keyFromEvent, moveFocus, registerPlatformKeys } from "./nav";
import type { Key } from "./nav";
import { pairLink, qrCanvas } from "./pairlink";
import * as diag from "./diagnostics";
import { engineFailureMessage, EMBEDDED, lazySource, openEngine, silentVideoNotice } from "./engine-adapter";
import { SourceError } from "tv-media";
import type { ByteSource } from "tv-media";
import type { TelegramRef } from "./telegram-identity";
import { checkCode, clearSession, newTvKeys, openSealed, saveSession, savedSession } from "./telegram-link";
import type { EngineHandle } from "./engine-adapter";
import { cueText, parseVtt, saveChoice, savedChoice, startWithSmart } from "./subtitles";
import type { Cue, SmartSubtitle } from "./subtitles";
import * as store from "./session";
import type { Account } from "./session";
import type { DeviceSession, Playback, Profile, SwitchRequest, Title } from "./types";

// The screens follow the Android TV app (apps/android-tv, ui/CablegramApp.kt and ui/LibraryScreen.kt) one to one:
// same layout, copy, colours and remote behaviour. Sizes are the Android dp values doubled (a 1080p TV is 960 dp wide).

const root = document.getElementById("app")!;
interface ProfileEntry { profile: Profile; account: Account }
let current: ProfileEntry | null = null;
const api = createApi({ token: () => current?.account.token ?? store.activeAccount()?.token });

type Focusable = HTMLElement & { onSelect?: () => void; onLong?: () => void };
let backHandler: (() => void) | null = null;
/** Screen-level keys (PIN digits, the player); true when handled. */
let screenKeys: ((key: Key, e: KeyboardEvent, repeat: number) => boolean) | null = null;
/** Timers and polls that belong to the screen on show; cleared when another screen replaces it. */
let screenCleanup: Array<() => void> = [];

const EXIT_CONFIRM_MS = 2_500;
const LONG_PRESS_MS = 650;

// ---- DOM helpers -------------------------------------------------------------------------------------------------

/** Builds an element; text goes through textContent so titles from the catalog can never inject markup. */
function h(tag: string, cls?: string, text?: string, children: Node[] = []): HTMLElement {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (text !== undefined) el.textContent = text;
  children.forEach((c) => el.appendChild(c));
  return el;
}

let lastKeySelectAt = 0;

function focusable(tag: string, cls: string, onSelect: () => void, children: Node[] = [], text?: string): Focusable {
  const el = h(tag, `focusable ${cls}`, text, children) as Focusable;
  el.tabIndex = -1;
  el.onSelect = onSelect;
  el.addEventListener("mouseenter", () => {
    if (modal && !modal.el.contains(el)) return;
    // Poster cards inside .shelves translate the shelf container vertically on focus, so only focus non-shelf controls on hover.
    if (!el.closest(".shelves")) focusEl(el);
  });
  el.addEventListener("click", (e) => {
    e.stopPropagation();
    if (modal && !modal.el.contains(el)) return;
    if (Date.now() - lastKeySelectAt < 250) return;
    focusEl(el);
    el.onSelect?.();
  });
  return el;
}

type ButtonStyle = "primary" | "raised" | "panel" | "ghost" | "coral";
/** tv-material's Button: a pill that turns white and grows a little when focused. */
function button(label: string, onSelect: () => void, style: ButtonStyle = "primary", autofocus = false): Focusable {
  const el = focusable("div", `btn btn-${style}`, onSelect, [], label);
  if (autofocus) el.setAttribute("data-autofocus", "");
  return el;
}

function brandMark(): HTMLElement {
  const img = document.createElement("img");
  img.src = "img/cablegram_brand_mark.png";
  img.alt = "Cablegram";
  return h("div", "brand", undefined, [img, h("span", undefined, "Cablegram")]);
}

function every(ms: number, fn: () => void) {
  const id = window.setInterval(fn, ms);
  screenCleanup.push(() => clearInterval(id));
}

function after(ms: number, fn: () => void): () => void {
  const id = window.setTimeout(fn, ms);
  const cancel = () => clearTimeout(id);
  screenCleanup.push(cancel);
  return cancel;
}

function show(nodes: Node[], back: (() => void) | null = null) {
  screenCleanup.forEach((f) => f());
  screenCleanup = [];
  closeModal();
  root.textContent = "";
  nodes.forEach((n) => root.appendChild(n));
  backHandler = back;
  screenKeys = null;
  focusFirst(root);
}

let modal: { el: HTMLElement; back: () => void; restore: Element | null } | null = null;

/** A dialog over the screen; focus stays inside it until it closes. */
function openModal(content: HTMLElement, onBack: () => void) {
  closeModal();
  const el = h("div", "modal", undefined, [content]);
  el.addEventListener("click", (e) => { if (e.target === el) modal?.back(); });
  modal = { el, back: onBack, restore: document.activeElement };
  root.appendChild(el);
  focusFirst(el);
}

function closeModal() {
  if (!modal) return;
  const { el, restore } = modal;
  modal = null;
  if (el.parentNode) el.parentNode.removeChild(el);
  if (restore && document.body.contains(restore)) focusEl(restore as HTMLElement);
}

function notice(text: string, ms = 1_600) {
  const old = root.querySelector(".notice");
  if (old) old.parentNode!.removeChild(old);
  const el = h("div", "notice", text);
  root.appendChild(el);
  window.setTimeout(() => { if (el.parentNode) el.parentNode.removeChild(el); }, ms);
}

function deviceName(): string {
  const ua = navigator.userAgent;
  if (/Tizen/i.test(ua)) return "Samsung TV";
  if (/Web0S|webOS/i.test(ua)) return "LG TV";
  return "TV";
}

function exitApp() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const w = window as any;
  try { w.tizen.application.getCurrentApplication().exit(); return; } catch { /* not Tizen */ }
  try { w.webOS?.platformBack?.(); } catch { /* not webOS */ }
  window.close();
}

let exitArmedAt = 0;
/** "Press Back again to exit"; true when this Back should close the app. */
function backExits(): boolean {
  if (Date.now() - exitArmedAt < EXIT_CONFIRM_MS) return true;
  exitArmedAt = Date.now();
  notice("Press Back again to exit", EXIT_CONFIRM_MS);
  return false;
}

function avatar(name: string, url: string | undefined, cls: string, letterOnly = true): HTMLElement {
  const disc = h("div", cls);
  disc.style.backgroundColor = placeholderColors(name)[0];
  const letter = () => { disc.textContent = (letterOnly ? name.trim().charAt(0) : name.charAt(0)).toUpperCase() || "C"; };
  if (url) {
    const img = document.createElement("img");
    img.src = url;
    img.onerror = () => { disc.removeChild(img); letter(); };
    disc.appendChild(img);
  } else letter();
  return disc;
}

// ---- Loading, errors ---------------------------------------------------------------------------------------------

function loading(label = "Opening your library…") {
  show([h("div", "center-screen", undefined, [h("div", "center-col", undefined, [brandMark(), h("p", "muted loading-label", label)])])]);
}

const ERROR_ANIMATIONS = ["img/error_frame.gif", "img/error_rain.gif", "img/error_signal.gif"];

/** The Android TV error screen: an animation on the left, what happened and what to do on the right. */
function errorScreen(title: string, message: string, actions: Focusable[], back: (() => void) | null) {
  const art = document.createElement("img");
  art.src = ERROR_ANIMATIONS[Math.floor(Math.random() * ERROR_ANIMATIONS.length)];
  if (actions[0]) actions[0].setAttribute("data-autofocus", "");
  show([h("div", "unhappy", undefined, [
    h("div", "unhappy-art", undefined, [art]),
    h("div", "unhappy-text", undefined, [
      h("div", "eyebrow coral", "CABLEGRAM"),
      h("div", "unhappy-title", title),
      h("div", "unhappy-message", message),
      h("div", "stack", undefined, actions),
    ]),
  ])], back);
}

// ---- Pairing -----------------------------------------------------------------------------------------------------

let pairingRun = 0;

/** "Pair this TV": the PIN in boxes, the QR code in a card. [canCancel] returns to the profiles of the accounts here. */
async function pair(canCancel = profileEntries.length > 0) {
  const run = ++pairingRun;
  if (!root.querySelector(".pairing")) loading("Getting a pairing code…");
  let session: DeviceSession;
  try {
    session = await api.createDeviceSession(deviceName());
  } catch {
    if (run !== pairingRun) return;
    errorScreen("Can't reach Cablegram", `Check the TV's internet connection (${API_BASE}).`, [button("Try again", () => pair(canCancel))],
      canCancel ? () => picker() : null);
    return;
  }
  if (run !== pairingRun) return;
  const link = pairLink(WEB_APP_BASE, session.pin, deviceName());
  const qr = qrCanvas(link, 544);
  const buttons = [button("New PIN", () => pair(canCancel), "raised", true)];
  if (canCancel) buttons.push(button("Cancel", () => { pairingRun++; picker(); }, "raised"));
  buttons.push(button("Check this TV", () => { pairingRun++; diagnosticsScreen(() => void pair(canCancel)); }, "raised"));
  show([h("div", "pairing", undefined, [
    h("div", "pairing-text", undefined, [
      brandMark(),
      h("div", "screen-title", "Pair this TV"),
      h("div", "pairing-sub", "Scan the code with your phone’s camera, or type this PIN in the Cablegram app or at app.cablegram.app."),
      h("div", "pin-boxes ltr", undefined, session.pin.split("").map((d) => h("div", "pin-box", d))),
      h("div", "row-buttons", undefined, buttons),
      h("div", "pairing-note", "Your videos stay on your phone."),
    ]),
    h("div", "qr-card", undefined, [
      h("div", "qr-white", undefined, [qr]),
      h("div", "qr-caption", "Scan with your phone’s camera"),
      h("div", "qr-caption-small", "Or open app.cablegram.app and enter the PIN"),
    ]),
  ])], canCancel ? () => { pairingRun++; picker(); } : () => { if (backExits()) exitApp(); });

  const deadline = Date.parse(session.expiresAt) || Date.now() + 10 * 60_000;
  every(2_000, async () => {
    if (run !== pairingRun) return;
    if (Date.now() > deadline) { pair(canCancel); return; }
    try {
      const status = await api.pairingStatus(session);
      if (run !== pairingRun) return;
      if (status.status === "paired" && status.accessToken) {
        pairingRun++;
        store.addAccount({ sessionId: session.sessionId, token: status.accessToken, lanCapability: status.lanCapability });
        connectedWelcome = true;
        void loadProfiles();
      } else if (status.status === "expired") pair(canCancel);
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 410)) pair(canCancel);
    }
  });
}

// ---- Profiles ----------------------------------------------------------------------------------------------------

let profileEntries: ProfileEntry[] = [];
const unlocked: Record<string, boolean> = {};
let connectedWelcome = false;
const sharedTv = () => store.accounts().length > 1;

/** Profiles of every account on this TV; an account the server no longer knows is dropped. */
async function loadProfiles() {
  const list = store.accounts();
  if (!list.length) { profileEntries = []; current = null; void pair(false); return; }
  if (!root.querySelector(".picker")) loading();
  const entries: ProfileEntry[] = [];
  let failed = false;
  for (const account of list) {
    try {
      (await api.profiles(account.token)).forEach((profile) => entries.push({ profile, account }));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) store.removeAccount(account.sessionId);
      else failed = true;
    }
  }
  profileEntries = entries;
  if (!store.accounts().length) { void pair(false); return; }
  if (!entries.length && failed) {
    errorScreen("Can't load profiles", "Check the TV's internet connection and try again.", [button("Try again", () => loadProfiles())], null);
    return;
  }
  picker();
}

/** "Who's watching?" */
function picker(message?: string, pending = false) {
  const tiles = profileEntries.map((entry, i) => {
    const p = entry.profile;
    const art = avatar(p.name, p.avatarUrl, "tile-disc");
    if (p.pinSet) art.appendChild(h("div", "pin-badge", "PIN"));
    const tile = focusable("div", `profile-tile${pending ? " disabled" : ""}`, () => { if (!pending) chooseProfile(entry); },
      [art, h("div", "tile-label", p.name)]);
    if (i === 0) tile.setAttribute("data-autofocus", "");
    return tile;
  });
  const add = focusable("div", `profile-tile add-tile${pending ? " disabled" : ""}`, () => { if (!pending) void pair(true); },
    [h("div", "tile-disc dashed", "+"), h("div", "tile-label", "Add profile")]);
  if (!tiles.length) add.setAttribute("data-autofocus", "");
  const footer = sharedTv()
    ? h("div", "picker-footnote", "Several accounts use this TV. To remove one, open one of its profiles, then choose Remove this account from this TV.")
    : button("Unpair this TV", () => confirmUnpair(unpairEverything), "ghost");
  const canReturn = !!current && !!videos.length;
  show([h("div", "picker", undefined, [
    h("div", "picker-brand", undefined, [brandMark()]),
    h("div", "picker-center", undefined, [
      h("div", "picker-title", "Who's watching?"),
      h("div", `picker-message${message && !pending ? " warning" : ""}`, message ?? "Choose your profile"),
      h("div", "profile-row", undefined, tiles.concat(add)),
      footer,
    ]),
  ])], () => {
    // Back returns to the library after "switch profile"; at app start it asks before exiting.
    if (canReturn) { libraryScreen(); return; }
    if (backExits()) exitApp();
  });
}

function chooseProfile(entry: ProfileEntry) {
  if (entry.profile.pinSet && !unlocked[entry.profile.id]) { pinPrompt(entry); return; }
  void proceedWithProfile(entry);
}

/** The PIN overlay: two columns so the keypad (with 0 and OK) fits; remote number keys type too. */
function pinPrompt(entry: ProfileEntry, error?: string) {
  let digits = "";
  let pending = false;
  const dots = h("div", "pin-dots ltr");
  const status = h("div", `pin-status${error ? " warning" : ""}`, error ?? "Choose the digits, then OK.");
  const draw = () => {
    dots.textContent = "";
    for (let i = 0; i < Math.max(4, digits.length); i++) dots.appendChild(h("i", i < digits.length ? "on" : undefined));
  };
  const add = (d: string) => { if (!pending && digits.length < 8) { digits += d; draw(); } };
  const del = () => { digits = digits.slice(0, -1); draw(); };
  const submit = async () => {
    if (pending || !digits) return;
    pending = true;
    status.className = "pin-status";
    status.textContent = "Checking…";
    const code = await api.unlockProfile(entry.profile.id, digits, entry.account.token);
    if (code >= 200 && code < 300) { unlocked[entry.profile.id] = true; void proceedWithProfile(entry); return; }
    // A lockout or network failure used to read "Wrong PIN", so people kept retrying the right PIN.
    pinPrompt(entry, code === 403 ? "Wrong PIN. Try again." : code === 429 ? "Too many tries. This profile is locked for 15 minutes."
      : "Couldn't check the PIN. Check the connection and try again.");
  };
  draw();
  const key = (label: string, onSelect: () => void, first = false) => {
    const k = focusable("div", "pin-key", onSelect, [], label);
    if (first) k.setAttribute("data-autofocus", "");
    return k;
  };
  const rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]].map((r) =>
    h("div", "pin-key-row", undefined, r.map((d) => key(d, () => add(d), d === "1"))));
  rows.push(h("div", "pin-key-row", undefined, [key("⌫", del), key("0", () => add("0")), key("OK", () => void submit())]));
  show([h("div", "center-screen pad", undefined, [h("div", "pin-panel", undefined, [
    h("div", "pin-info", undefined, [
      brandMark(),
      h("div", "pin-eyebrow", "Enter PIN"),
      h("div", "pin-name", entry.profile.name),
      dots, status,
      button("Cancel", () => picker(), "raised"),
    ]),
    h("div", "keypad ltr", undefined, rows),
  ])])], () => picker());
  screenKeys = (k, e) => {
    if (k === "digit") { add(String.fromCharCode(e.keyCode)); return true; }
    if (k === "delete" || e.keyCode === 8) { del(); return true; }
    return false;
  };
}

/** Profile lock: a phone in the household has to allow the profile before the TV opens it. */
async function proceedWithProfile(entry: ProfileEntry) {
  picker("Opening profile…", true);
  const token = entry.account.token;
  let request: SwitchRequest;
  try {
    request = await api.requestProfileSwitch(entry.profile.id, deviceName(), token);
  } catch (e) {
    // A control plane without profile lock answers 404: nothing to wait for.
    if (e instanceof ApiError && e.status === 404) { void openProfile(entry); return; }
    picker(e instanceof ApiError ? "Couldn't open the profile. Try again." : "Couldn't reach Cablegram. Check the connection.");
    return;
  }
  if (request.status === "approved") { void openProfile(entry); return; }
  const requestId = request.requestId;
  let waiting = true;
  const cancel = () => {
    waiting = false;
    void api.cancelProfileSwitch(requestId, token).catch(() => {});
    picker();
  };
  show([h("div", "center-screen pad", undefined, [h("div", "approval-panel", undefined, [
    brandMark(),
    h("div", "pin-eyebrow", "Allow on your phone"),
    h("div", "pin-name", entry.profile.name),
    h("div", "approval-text", sharedTv()
      ? "Other accounts use this TV too, so this account's phone has to allow it. Open Cablegram on that phone and tap Allow."
      : "This account is locked. Open Cablegram on a phone signed in to it and tap Allow."),
    button("Cancel", cancel, "raised", true),
  ])])], cancel);
  every(2_000, async () => {
    if (!waiting) return;
    let status: string | undefined;
    // A missed poll (Wi-Fi blip) keeps waiting; the server expires the request.
    try { status = (await api.profileSwitch(requestId, token)).status; } catch { return; }
    if (!waiting) return;
    if (status === "approved") { waiting = false; void openProfile(entry); }
    else if (status === "denied") { waiting = false; picker(`Your phone didn't allow ${entry.profile.name}.`); }
    else if (status === "expired") { waiting = false; picker(`Nobody allowed ${entry.profile.name} on a phone in time. Try again.`); }
  });
}

async function openProfile(entry: ProfileEntry) {
  current = entry;
  store.setActive(entry.account.sessionId, entry.profile.id);
  libraryState = { rail: "home", type: "all", genre: null, show: null, focusId: null };
  await loadLibrary();
}

// ---- Session actions, unpairing ----------------------------------------------------------------------------------

/** The avatar at the top of the menu: switch profile, sign out, or unpair. */
function sessionActions() {
  const shared = sharedTv();
  show([h("div", "center-screen pad", undefined, [h("div", "session-panel", undefined, [
    brandMark(),
    h("div", "session-title", "TV session"),
    h("div", "muted session-sub", "Choose an action"),
    button("Switch profile", () => picker(), "primary", true),
    button("Sign out", () => { current = null; videos = []; void loadProfiles(); }),
    // On a shared TV this removes only the open account; with one account it unpairs the TV.
    button(shared ? "Remove this account from this TV" : "Unpair this TV",
      () => (shared ? confirmUnpair(removeCurrentAccount, "Remove this account from this TV?",
        "This account and its profiles leave this TV. The other accounts stay.", "Remove") : confirmUnpair(unpairEverything)), "panel"),
    button("Back", () => libraryScreen(), "panel"),
  ])])], () => libraryScreen());
}

/** Unpairing revokes the TV on the server at once, so it is asked about first. Cancel has focus; Back cancels. */
function confirmUnpair(onUnpair: () => void, title = "Unpair this TV?",
  text = "This TV will be signed out of Cablegram. To use it again you will need to pair it with your phone again.", confirm = "Unpair") {
  openModal(h("div", "dialog", undefined, [
    h("div", "dialog-title", title),
    h("div", "dialog-text", text),
    h("div", "row-buttons center", undefined, [button("Cancel", closeModal, "primary", true), button(confirm, () => { closeModal(); onUnpair(); }, "coral")]),
  ]), closeModal);
}

async function unpairEverything() {
  loading("Unpairing…");
  await Promise.all(store.accounts().map((a) => api.revokeSelf(a.token).catch(() => {})));
  store.clearAccounts();
  current = null; videos = []; profileEntries = [];
  void pair(false);
}

async function removeCurrentAccount() {
  const account = current?.account;
  if (!account) return;
  loading("Removing this account…");
  await api.revokeSelf(account.token).catch(() => {});
  store.removeAccount(account.sessionId);
  current = null; videos = [];
  void loadProfiles();
}

/** Shown once over the library right after pairing. "Start watching" has focus, so OK or Back closes it. */
function showConnectedWelcome() {
  const points = [
    "Browse your library with the remote and press OK to play.",
    "Hold OK on a title to add it to My List.",
  ];
  openModal(h("div", "dialog welcome", undefined, [
    brandMark(),
    h("div", "welcome-title", "This TV is connected"),
    h("div", "welcome-text", "Your phone is the remote and the library. Add videos in the phone app's Import tab and they appear here within a few seconds."),
    h("div", "welcome-points", undefined, points.map((p) => h("div", "welcome-point", undefined, [h("i"), h("span", undefined, p)]))),
    button("Start watching", closeModal, "primary", true),
  ]), closeModal);
}

// ---- Library -----------------------------------------------------------------------------------------------------

type Rail = "home" | "player" | "library" | "settings";
interface LibraryState { rail: Rail; type: LibraryType; genre: string | null; show: Show | null; focusId: string | null }
let libraryState: LibraryState = { rail: "home", type: "all", genre: null, show: null, focusId: null };
let videos: Title[] = [];

async function loadLibrary() {
  const entry = current;
  if (!entry) { picker(); return; }
  if (!videos.length) loading();
  try {
    videos = await api.catalog(entry.profile.id);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) { store.removeAccount(entry.account.sessionId); current = null; void loadProfiles(); return; }
    errorScreen("Can't load your library", "Check the TV's internet connection and try again.",
      [button("Try again", () => loadLibrary())], () => picker());
    return;
  }
  void telegramHousekeeping();
  libraryScreen();
  if (connectedWelcome) { connectedWelcome = false; showConnectedWelcome(); }
}

function libraryScreen() {
  const entry = current;
  if (!entry) { picker(); return; }
  const st = libraryState;
  const onMainView = () => st.rail === "home" && st.type === "all" && !st.genre;
  const genres = allGenres(videos);
  const shelves = buildShelves(videos, st.type, st.genre);
  const catalogEntries: Entry[] = [];
  shelves.forEach((s) => s.entries.forEach((e) => { if (!catalogEntries.some((c) => c.id === e.id)) catalogEntries.push(e); }));
  // Shelves hold filtered episode subsets; opening a show always lists every episode.
  const fullShows: Record<string, Show> = {};
  toEntries(videos).forEach((e) => { if (e.kind === "show") fullShows[e.id] = e.show; });
  if (st.show) st.show = fullShows[st.show.id] ?? st.show;

  const backdrop = h("div", "ambient-image");
  const tint = h("div", "ambient-tint");
  const ambient = h("div", "ambient", undefined, [tint, backdrop, h("div", "ambient-fade-x"), h("div", "ambient-fade-y")]);
  let backdropTimer = 0;
  // Debounced so scrolling along a row does not decode a full-screen image for every poster the focus passes over.
  const setBackdrop = (url: string | undefined, seed: string | undefined) => {
    clearTimeout(backdropTimer);
    backdropTimer = window.setTimeout(() => {
      backdrop.style.backgroundImage = url ? `url("${url.replace(/"/g, "%22")}")` : "";
      backdrop.style.opacity = url ? "0.62" : "0";
      tint.style.backgroundImage = !url && seed
        ? `linear-gradient(to bottom left, ${hexAlpha(placeholderColors(seed)[0], 0.55)}, rgba(0,0,0,0))` : "";
    }, 220);
  };
  screenCleanup.push(() => clearTimeout(backdropTimer));

  // ---- Rail
  const railItem = (id: string, icon: string, label: string, rail: Rail, onSelect: () => void) => {
    const el = focusable("div", `rail-item${st.rail === rail ? " selected" : ""}`, onSelect,
      [h("div", "rail-icon", undefined, [lineIcon(icon)]), h("div", "rail-label", label)]);
    el.id = id;
    return el;
  };
  const go = (rail: Rail, type: LibraryType) => {
    libraryState = { rail, type: rail === "settings" ? st.type : type, genre: rail === "home" ? st.genre : null, show: null, focusId: null };
    libraryScreen();
    const target = document.getElementById(`rail-${rail}`);
    if (target) focusEl(target);
  };
  const avatarLabel = h("div", "avatar-label", entry.profile.name);
  const avatarBtn = focusable("div", "rail-avatar", () => sessionActions(), [avatar(entry.profile.name, entry.profile.avatarUrl, "avatar-disc"), avatarLabel]);
  avatarBtn.id = "rail-avatar";
  avatarBtn.addEventListener("focus", () => { avatarLabel.textContent = "Switch"; });
  avatarBtn.addEventListener("blur", () => { avatarLabel.textContent = entry.profile.name; });
  const rail = h("div", "rail", undefined, [
    avatarBtn,
    h("div", "rail-gap"),
    railItem("rail-home", "home", "Home", "home", () => go("home", "all")),
    railItem("rail-player", "player", "Continue", "player", () => go("player", "watchlist")),
    railItem("rail-library", "library", "My List", "library", () => go("library", "mylist")),
    h("div", "rail-spacer"),
    railItem("rail-settings", "settings", "Settings", "settings", () => go("settings", st.type)),
  ]);
  rail.setAttribute("data-nav-group", "");

  // ---- Top bar
  const top = h("div", "topbar", undefined, [brandMark()]);
  if (st.rail === "home" && !st.show) {
    const pill = (label: string, selected: boolean, onSelect: () => void, outlined = false) => {
      const el = focusable("div", `pill${selected ? " selected" : ""}${outlined ? " outlined" : ""}`, onSelect, [], label);
      el.setAttribute("data-left", "rail-home");
      return el;
    };
    const setType = (type: LibraryType) => () => { libraryState = { ...st, type, show: null, focusId: null }; libraryScreen(); focusPill(type); };
    top.appendChild(h("div", "pills", undefined, [
      pill("All", st.type === "all", setType("all")),
      pill("Movies", st.type === "movie", setType("movie")),
      pill("TV Shows", st.type === "tv", setType("tv")),
      pill(`Genre: ${st.genre ?? "All"}`, !!st.genre, () => {
        if (!genres.length) return;
        const i = st.genre ? genres.indexOf(st.genre) : -1;
        libraryState = { ...st, genre: i === genres.length - 1 ? null : genres[(i + 1) % genres.length], focusId: null };
        libraryScreen();
        focusPill("genre");
      }, true),
    ]));
  } else {
    top.appendChild(h("div", "topbar-title", ({ home: "Home", player: "Continue watching", library: "My List", settings: "Settings" } as const)[st.rail]));
  }
  top.appendChild(h("div", "flex"));
  top.appendChild(clock());

  const main = h("div", "lib-main", undefined, [top]);
  let lastContent: HTMLElement | null = null;

  // ---- Hero
  const heroTitle = h("div", "hero-title");
  const heroChips = h("div", "hero-chips");
  const heroOverview = h("div", "hero-overview");
  const heroBarFill = h("i");
  const heroBar = h("div", "hero-bar", undefined, [heroBarFill]);
  const heroRemaining = h("div", "hero-remaining");
  const heroHint = h("div", "hero-hint");
  const hero = h("div", "hero", undefined, [heroTitle, heroChips, heroOverview, h("div", "hero-progress", undefined, [heroBar, heroRemaining, heroHint])]);
  const describe = (title: string, meta: string[], overview?: string, hint?: string, progress = 0, remaining?: string) => {
    heroTitle.textContent = title;
    heroChips.textContent = "";
    meta.forEach((m, i) => heroChips.appendChild(h("span", i === 0 ? "chip accent" : "chip", m)));
    heroChips.style.display = meta.length ? "" : "none";
    heroOverview.textContent = overview ?? "";
    heroOverview.style.display = overview && overview.trim() ? "" : "none";
    heroBar.style.display = heroRemaining.style.display = remaining ? "" : "none";
    heroBarFill.style.width = `${Math.max(4, Math.min(100, progress * 100))}%`;
    heroRemaining.textContent = remaining ?? "";
    heroHint.textContent = hint ?? "";
    heroHint.style.display = hint ? "" : "none";
  };
  const describeEntry = (e: Entry) => {
    describe(e.title, entryMetadata(e), entryOverview(e), entryHint(e), entryProgress(e), entryRemaining(e));
    setBackdrop(entryBackdrop(e), e.title);
  };

  const toggleMyList = (e: Entry) => {
    const next = !entryInMyList(e);
    const ids = entryIds(e);
    const apply = (value: boolean) => { videos = videos.map((v) => (ids.indexOf(v.id) >= 0 ? { ...v, inMyList: value } : v)); };
    apply(next);
    notice(next ? "Added to My List" : "Removed from My List");
    api.setMyList(entry.profile.id, ids, next).catch(() => { apply(!next); });
    libraryState = { ...st, focusId: e.id };
    const kept = document.activeElement as HTMLElement | null;
    const shelfId = kept?.getAttribute("data-shelf");
    libraryScreen();
    const again = root.querySelector(`.card[data-shelf="${shelfId}"][data-id="${cssEscape(e.id)}"]`) as HTMLElement | null;
    if (again) focusCard(again);
  };

  const play = (v: Title) => {
    libraryState = { ...st, focusId: v.id };
    void startPlayback(v);
  };

  if (st.rail === "settings") {
    main.appendChild(settingsPanel());
    show([h("div", "library", undefined, [ambient, h("div", "lib-row", undefined, [rail, main])])], libraryBack);
    focusEl(document.getElementById("rail-settings")!);
    return;
  }

  if (st.show) {
    // ---- An open show: its episodes in one landscape row.
    const showEntry = st.show;
    const target = (st.focusId && showEntry.episodes.some((x) => x.id === st.focusId) ? st.focusId : nextEpisode(showEntry).id);
    const describeEpisode = (ep?: Title) => {
      const sEntry: Entry = { kind: "show", id: showEntry.id, title: showEntry.title, show: showEntry };
      describe(showEntry.title, ep ? metadataParts(ep) : entryMetadata(sEntry),
        ep ? [ep.episodeTitle, ep.overview].filter(Boolean).join(" — ") || undefined : entryOverview(sEntry),
        "OK to play · Back for all titles", ep ? progressFraction(ep) : entryProgress(sEntry), ep ? remainingLabel(ep) : entryRemaining(sEntry));
      setBackdrop(ep?.backdropUrl ?? entryBackdrop(sEntry), showEntry.title);
    };
    const all = h("div", "pill outlined focusable", "‹  All titles") as Focusable;
    all.tabIndex = -1;
    all.setAttribute("data-left", "rail-home");
    const closeShow = () => { libraryState = { ...st, show: null, focusId: showEntry.id }; libraryScreen(); };
    all.onSelect = closeShow;
    const strip = h("div", "strip episodes");
    showEntry.episodes.forEach((ep, i) => {
      const c = posterCard({
        id: ep.id, shelf: "episodes", title: showTitle(ep), caption: episodeCaption(ep),
        poster: ep.isPrivate ? privateThumbnail(ep.id) : ep.backdropUrl ?? ep.posterUrl ?? showEntry.poster,
        progress: progressFraction(ep), landscape: true, badge: episodeBadge(ep), sourceBadge: sourceBadge(ep.sourceKinds),
        first: i === 0, onFocus: () => { describeEpisode(ep); libraryState = { ...st, focusId: ep.id }; }, onSelect: () => play(ep),
      });
      strip.appendChild(c);
    });
    main.appendChild(hero);
    main.appendChild(h("div", "episode-bar", undefined, [all, h("div", "season-summary", seasonSummary(showEntry))]));
    main.appendChild(h("div", "strip-wrap", undefined, [strip]));
    describeEpisode(showEntry.episodes.filter((x) => x.id === target)[0]);
    show([h("div", "library", undefined, [ambient, h("div", "lib-row", undefined, [rail, main])])], closeShow);
    const focusTarget = strip.querySelector(`[data-id="${cssEscape(target)}"]`) as HTMLElement | null;
    if (focusTarget) focusCard(focusTarget);
    installLibraryKeys(rail, () => lastContent, (el) => { lastContent = el; });
    return;
  }

  main.appendChild(hero);
  const hero0 = (st.focusId ? catalogEntries.filter((e) => e.id === st.focusId)[0] : undefined) ?? catalogEntries[0];
  if (hero0) describeEntry(hero0);
  else {
    describe(st.rail === "player" ? "Nothing in progress" : st.rail === "library" ? "Your list is empty" : "Your library",
      [], st.rail === "library" ? "Hold OK on any title to save it here." : "Movies and shows from your phone appear here.");
    setBackdrop(undefined, undefined);
  }

  if (!videos.length) {
    main.appendChild(h("div", "empty-wrap", undefined, [h("div", "empty-panel", undefined, [
      h("div", "empty-title", "Nothing here yet"),
      h("div", "empty-text", "On your phone, open Cablegram and tap Import to add videos. They appear on this TV within a few seconds."),
      h("div", "empty-tip", "Tip: connect Telegram in the phone app (Import › Connected storage) and this TV can play even when your phone is off."),
      button("Check again", () => loadLibrary(), "primary"),
    ])]));
  } else if (!shelves.length) {
    main.appendChild(h("div", "empty-wrap", undefined, [h("div", "empty-search",
      st.rail === "player" ? "Titles you start watching show up here."
        : st.rail === "library" ? "Nothing saved yet. Hold OK on a title to add it." : "No titles match these filters.")]));
  } else {
    const list = h("div", "shelves");
    shelves.forEach((shelf) => {
      const strip = h("div", "strip");
      shelf.entries.forEach((e, i) => {
        const isShow = e.kind === "show";
        const c = posterCard({
          id: e.id, shelf: shelf.id, title: e.title, caption: e.title,
          poster: e.kind === "movie" ? (e.video.isPrivate ? privateThumbnail(e.video.id) : e.video.posterUrl) : e.show.poster,
          progress: entryProgress(e), inMyList: entryInMyList(e),
          badge: isShow ? (e.show.episodes.length === 1 ? episodeBadge(e.show.episodes[0]) : undefined) ?? `${e.show.episodes.length} EP` : undefined,
          sourceBadge: entrySourceBadge(e), first: i === 0,
          onFocus: () => { describeEntry(e); libraryState = { ...st, focusId: e.id }; },
          onSelect: () => {
            if (e.kind === "movie") play(e.video);
            else { libraryState = { ...st, show: fullShows[e.id] ?? e.show, focusId: null }; libraryScreen(); }
          },
          onLong: () => toggleMyList(e),
        });
        strip.appendChild(c);
      });
      list.appendChild(h("div", "shelf", undefined, [
        h("div", "shelf-head", undefined, [h("span", "shelf-title", shelf.title), h("span", "shelf-count", String(shelf.entries.length))]),
        h("div", "strip-wrap", undefined, [strip]),
      ]));
    });
    main.appendChild(list);
  }

  show([h("div", "library", undefined, [ambient, h("div", "lib-row", undefined, [rail, main])])], libraryBack);
  const restore = st.focusId ? root.querySelector(`.card[data-id="${cssEscape(st.focusId)}"]`) as HTMLElement | null : null;
  if (restore) focusCard(restore);
  else focusEl(document.getElementById(`rail-${st.rail}`)!);
  installLibraryKeys(rail, () => lastContent, (el) => { lastContent = el; });

  /*
   * Back never leaves the app by surprise:
   *  1. from any section, filter or genre → the main library (Home);
   *  2. on Home with focus in the rows or menu → the profile avatar at the top of the menu;
   *  3. on the avatar → "Press Back again to exit"; a second Back within 2.5 s closes the app.
   */
  function libraryBack() {
    if (!onMainView()) {
      libraryState = { rail: "home", type: "all", genre: null, show: null, focusId: null };
      libraryScreen();
      focusEl(document.getElementById("rail-home")!);
      return;
    }
    if (document.activeElement !== document.getElementById("rail-avatar")) { focusEl(document.getElementById("rail-avatar")!); return; }
    if (backExits()) exitApp();
  }
}

function focusPill(which: LibraryType | "genre") {
  const pills = root.querySelectorAll(".pill");
  const index = { all: 0, movie: 1, tv: 2, genre: 3 } as Record<string, number>;
  const el = pills[index[which] ?? 0] as HTMLElement | undefined;
  if (el) focusEl(el);
}

/** Right from the menu returns to where the viewer was in the rows (Compose's focusRestorer). */
function installLibraryKeys(rail: HTMLElement, last: () => HTMLElement | null, remember: (el: HTMLElement) => void) {
  const main = rail.nextSibling as HTMLElement;
  main.addEventListener("focusin", (e) => remember(e.target as HTMLElement));
  screenKeys = (key) => {
    const active = document.activeElement;
    if (key === "right" && active && rail.contains(active)) {
      const target = last();
      if (target && document.body.contains(target)) { focusEl(target); return true; }
    }
    return false;
  };
}

interface CardSpec {
  id: string; shelf: string; title: string; caption?: string; poster?: string; progress: number; landscape?: boolean;
  badge?: string; sourceBadge?: string; inMyList?: boolean; first: boolean;
  onFocus: () => void; onSelect: () => void; onLong?: () => void;
}

function posterCard(spec: CardSpec): HTMLElement {
  const art = h("div", "card-art");
  const placeholder = () => {
    const [a, b] = placeholderColors(spec.title);
    art.style.backgroundImage = `linear-gradient(to bottom right, ${a}, ${b})`;
    art.insertBefore(h("div", "monogram", monogram(spec.title)), art.firstChild);
  };
  if (spec.poster) {
    const img = document.createElement("img");
    img.src = spec.poster;
    img.alt = "";
    img.onerror = () => { art.removeChild(img); placeholder(); };
    art.appendChild(img);
  } else placeholder();
  // Top left, where poster art rarely puts its title: the episode badge, then where the title plays from.
  if (spec.badge || spec.sourceBadge) {
    const col = h("div", "badges");
    if (spec.badge) col.appendChild(h("div", "card-badge", spec.badge));
    if (spec.sourceBadge) col.appendChild(h("div", `card-badge${spec.badge ? " tucked" : ""}`, spec.sourceBadge));
    art.appendChild(col);
  }
  if (spec.inMyList) art.appendChild(h("div", "card-badge check", "✓"));
  if (spec.progress > 0) {
    const fill = h("i");
    fill.style.width = `${Math.max(4, Math.min(100, spec.progress * 100))}%`;
    art.appendChild(h("div", "card-progress", undefined, [fill]));
  }
  const el = focusable("div", `card${spec.landscape ? " landscape" : ""}`, spec.onSelect, [h("div", "card-frame", undefined, [art])]);
  if (spec.caption) el.appendChild(h("div", "caption", spec.caption));
  if (spec.onLong) el.onLong = spec.onLong;
  el.setAttribute("data-id", spec.id);
  el.setAttribute("data-shelf", spec.shelf);
  if (spec.first) el.setAttribute("data-left", "rail-home");
  el.addEventListener("focus", () => { spec.onFocus(); bringIntoView(el); });
  return el;
}

function focusCard(el: HTMLElement) {
  focusEl(el);
  bringIntoView(el);
}

/** Keeps a focused card (and its shelf) on screen, the way a LazyRow inside a LazyColumn scrolls. */
function bringIntoView(card: HTMLElement) {
  const strip = card.parentElement as HTMLElement;
  const wrap = strip.parentElement as HTMLElement;
  const pad = 8;
  const left = card.offsetLeft - pad;
  const right = card.offsetLeft + card.offsetWidth + 80;
  let x = -(parseFloat(strip.getAttribute("data-x") || "0"));
  if (left < x) x = left;
  else if (right > x + wrap.clientWidth) x = right - wrap.clientWidth;
  x = Math.max(0, x);
  strip.setAttribute("data-x", String(-x));
  strip.style.transform = `translateX(${-x}px)`;
  const shelf = wrap.parentElement as HTMLElement;
  const list = shelf.parentElement as HTMLElement;
  if (!list || list.className !== "shelves") return;
  // The focused shelf sits at the top of the list, so no half-scrolled row peeks out under the hero.
  const y = Math.max(0, shelf.offsetTop);
  list.setAttribute("data-y", String(-y));
  for (let i = 0; i < list.children.length; i++) (list.children[i] as HTMLElement).style.transform = `translateY(${-y}px)`;
}

/** A glanceable wall clock, the way every living-room screen has one. */
function clock(): HTMLElement {
  const el = h("div", "clock");
  const tick = () => { const d = new Date(); el.textContent = `${("0" + d.getHours()).slice(-2)}:${("0" + d.getMinutes()).slice(-2)}`; };
  tick();
  every(10_000, tick);
  return el;
}

const ICONS: Record<string, string> = {
  home: '<path d="M2.4 9.6 10 2.8l7.6 6.8M4.4 8.4v8.8h11.2V8.4"/>',
  player: '<circle cx="10" cy="10" r="8.5"/><path d="M8.4 6.4 14.4 10l-6 3.6z"/>',
  library: '<path d="M5.2 2h9.6v16L10 13.6 5.2 18z"/>',
  settings: '<circle cx="10" cy="10" r="3.2"/><circle cx="10" cy="10" r="8.4"/>',
};

/** The menu's line icons, drawn like LibraryScreen.kt's LineIcon. */
function lineIcon(name: string): HTMLElement {
  const span = h("span", "line-icon");
  span.innerHTML = `<svg viewBox="0 0 20 20" width="40" height="40" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`;
  return span;
}

function settingsPanel(): HTMLElement {
  const value = (label: string, v: string) => h("div", "setting-value", undefined, [h("div", "setting-label", label), h("div", "setting-v", v)]);
  return h("div", "settings", undefined, [
    h("div", "settings-col wide", undefined, [
      h("div", "settings-title", "About Cablegram"),
      h("div", "settings-tagline", "Your own videos, streamed from your phone to your TV"),
      h("div", "settings-text", "Cablegram plays videos you own or are allowed to watch. It provides no media of its own. Videos from your phone or your Telegram channel are never stored on its servers; only a web video you choose to save to Cablegram storage is, until you delete your account."),
      h("div", "settings-text small", "Privacy policy: https://cablegram.app/privacy.html"),
    ]),
    h("div", "settings-col", undefined, [
      h("div", "settings-heading", "Connection"),
      value("This TV", deviceName()),
      value("Media source", "Your paired phone"),
      value("Playback path", "Relay through Cablegram"),
      h("div", "settings-text small", "This TV reaches your phone through the Cablegram relay, so it plays whether or not both are on the same Wi‑Fi."),
      h("div", "settings-heading", "Telegram"),
      h("div", "settings-text small", import.meta.env.VITE_TG_DIRECT === "1"
        ? "Telegram playback on this TV is in testing."
        : "Telegram playback isn't available on this TV yet. Titles stored in Telegram play on the Android TV app."),
      h("div", "settings-heading", "Help"),
      button("Diagnostics", () => diagnosticsScreen(), "panel"),
    ]),
  ]);
}

// ---- Diagnostics -------------------------------------------------------------------------------------------------

/** What this TV can do and how fast it is; also reachable at #diag. Results can be photographed or scanned as a QR code. */
function diagnosticsScreen(back: () => void = () => libraryScreen()) {
  const rowsEl = h("div", "diag-rows");
  const status = h("div", "diag-sub", "Running checks…");
  const preview = h("div", "diag-preview");
  const qrBox = h("div", "diag-qr");
  let run = 0;
  let clip: Uint8Array | null = null;
  let report = new diag.Report();
  const mark: Record<diag.Status, string> = { ok: "✓", fail: "✗", warn: "!", info: "·" };

  const draw = (rows: diag.Row[]) => {
    rowsEl.textContent = "";
    rows.forEach((r) => rowsEl.appendChild(h("div", "diag-row", undefined, [
      h("div", `diag-mark ${r.status}`, mark[r.status]), h("div", "diag-label", r.label), h("div", "diag-detail", r.detail),
    ])));
  };
  const finish = () => {
    qrBox.textContent = "";
    try { qrBox.appendChild(qrCanvas(diag.compactSummary(report.facts, report.rows), 300)); } catch { /* too long for a QR: the list on screen still says it all */ }
  };
  const getClip = async () => (clip ??= await diag.loadBytes("diag/clip.mkv"));

  const runAll = async () => {
    const id = ++run;
    const live = () => id === run;
    report = new diag.Report(draw);
    qrBox.textContent = "";
    status.textContent = "Running checks…";
    try {
      diag.checkEnvironment(report);
      diag.checkDecoders(report);
      await diag.checkSpeed(report);
      if (!live()) return;
      await diag.checkNetwork(report, API_BASE);
      if (!live()) return;
      const bytes = await getClip().catch((e: Error) => { report.add("clip", "Test clip", "fail", `could not load it (${e.message})`); return null; });
      if (bytes && live()) await diag.checkPlayback(report, bytes, preview);
    } catch (e) {
      report.add("error", "Diagnostics", "fail", (e as Error).message);
    }
    if (!live()) return;
    status.textContent = "Done. Photograph this screen or scan the code to send the results.";
    finish();
  };
  const measure = async () => {
    const id = ++run;
    status.textContent = "Measuring the video buffer (about a minute; the TV may flicker)…";
    try {
      await diag.measureBuffer(report, await getClip(), (mb) => { if (id === run) status.textContent = `Measuring the video buffer… ${Math.round(mb)} MB accepted so far`; });
    } catch (e) {
      report.add("buffer", "MSE buffer size", "fail", (e as Error).message);
    }
    if (id !== run) return;
    status.textContent = "Done. Photograph this screen or scan the code to send the results.";
    finish();
  };

  const again = button("Run again", () => void runAll(), "panel");
  const buffer = button("Measure buffer size", () => void measure(), "panel");
  const done = button("Back", back, "primary", true);
  show([h("div", "diag", undefined, [
    h("div", "diag-main", undefined, [h("div", "diag-title", "Diagnostics"), status, rowsEl]),
    h("div", "diag-side", undefined, [preview, qrBox, h("div", "stack", undefined, [again, buffer, done])]),
  ])], back);
  screenCleanup.push(() => { run = -1; });
  void runAll();
}

// ---- Playback ----------------------------------------------------------------------------------------------------

async function startPlayback(v: Title) {
  const entry = current;
  if (!entry) return;
  const back = () => libraryScreen();
  let cancelled = false;
  show([h("div", "player-start", undefined, [h("div", "start-card", undefined, [
    h("div", "start-title", "Starting playback…"),
    h("div", "start-sub", displayTitle(v)),
  ])])], () => { cancelled = true; back(); });
  let playback: Playback;
  try {
    playback = await api.resolvePlayback(v, entry.account.lanCapability);
  } catch (e) {
    if (cancelled) return;
    const offline = e instanceof ApiError && e.status === 503;
    errorScreen(offline ? "Your phone isn't online" : "Couldn't start this video",
      offline ? "Open Cablegram on your phone, or check that it has a connection, then try again." : "Check the TV's connection and try again.",
      [button("Back to library", back), button("Try again", () => void startPlayback(v), "panel")], back);
    return;
  }
  if (cancelled) return;
  if (playback.status !== "ready" || (!playback.url && !playback.telegram)) {
    errorScreen("This video can't play here", playback.message ?? "This video can't play right now.",
      [button("Back to library", back), button("Try again", () => void startPlayback(v), "panel")], back);
    return;
  }
  if (playback.telegram && !savedSession(entry.account.sessionId)) { askToConnectTelegram(v); return; }
  // A missing subtitle never stops playback.
  const smart = await api.subtitles(v.id).catch(() => [] as SmartSubtitle[]);
  if (cancelled) return;
  playerScreen(v, entry.profile, playback.url ?? "", undefined, 0, smart, playback.engine ? "engine" : "plain", playback.telegram);
}

/** Telegram bytes over MTProto (VITE_TG_DIRECT=1 builds only); failures become typed source errors the player explains. */
async function telegramSource(ref: TelegramRef): Promise<ByteSource> {
  try {
    // Written out here (not via TG_DIRECT) so the bundler drops the whole Telegram chunk from builds without the flag.
    if (import.meta.env.VITE_TG_DIRECT === "1") {
      const [mod, config] = await Promise.all([import("./telegram"), api.telegramClientConfig()]);
      return await mod.openTelegramSource(ref, config, current ? savedSession(current.account.sessionId) : null);
    }
    throw new SourceError("telegram_error", "direct Telegram playback is not built in", undefined, "not_built");
  } catch (e) {
    if (e instanceof SourceError) throw e;
    if (e instanceof ApiError) throw new SourceError(e.status === 403 ? "telegram_auth" : "telegram_error", e.code, e.status, e.code);
    const reason = (e as { reason?: string }).reason;
    if (reason === "no_session") throw new SourceError("telegram_auth", reason, undefined, reason);
    if (reason === "no_video" || reason === "not_found") throw new SourceError("telegram_file", reason, undefined, reason);
    throw new SourceError("telegram_error", e instanceof Error ? e.message : "telegram failed", undefined, "connect_failed");
  }
}

// ---- Telegram sign-in on the TV ------------------------------------------------------------------------------------

/** The GramJS side, only in builds with VITE_TG_DIRECT=1 (written out so other builds drop the whole chunk). */
async function telegramModule() {
  if (import.meta.env.VITE_TG_DIRECT === "1") return import("./telegram");
  throw new Error("direct Telegram playback is not built in");
}

/** A card in the middle of the screen: what is happening, what to do, and a way out. */
function telegramCard(title: string, lines: string[], actions: Focusable[], big?: string, back: (() => void) | null = null) {
  if (actions[0]) actions[0].setAttribute("data-autofocus", "");
  show([h("div", "player-start", undefined, [h("div", "start-card tg-card", undefined, [
    h("div", "start-title", title),
    ...lines.map((l) => h("div", "start-sub", l)),
    ...(big ? [h("div", "tg-code ltr", big)] : []),
    h("div", "stack tg-actions", undefined, actions),
  ])])], back);
}

/** The first time a Telegram title is played on this TV: explain, and ask before signing in to anyone's Telegram. */
async function askToConnectTelegram(v: Title) {
  const back = () => libraryScreen();
  telegramCard("Connect Telegram to this TV?", ["Checking your account…"], [button("Back to library", back, "raised")], undefined, back);
  try {
    const link = await api.telegramLink();
    if (!link.linked) {
      errorScreen("Telegram isn't connected yet", "Open Cablegram on your phone, go to Import, connect your Telegram account, then try this video again.",
        [button("Back to library", back), button("Try again", () => void askToConnectTelegram(v), "panel")], back);
      return;
    }
    if (!link.direct) {
      errorScreen("Telegram isn't allowed on this TV", "This TV is a temporary TV, so Telegram videos play through your phone instead. The owner can allow Telegram on this TV in the phone app.",
        [button("Back to library", back)], back);
      return;
    }
  } catch {
    errorScreen("Can't reach Cablegram", "Check the TV's internet connection and try again.", [button("Back to library", back), button("Try again", () => void askToConnectTelegram(v), "panel")], back);
    return;
  }
  telegramCard("Connect Telegram to this TV?", [
    "This video is stored in your Telegram. To play it, this TV signs in to your Telegram account, and your phone approves it.",
    "Your Telegram password never goes through Cablegram. You can end the TV's access any time from Telegram's Devices list or the Cablegram app.",
  ], [button("Connect with my phone", () => void connectTelegram(v)), button("Not now", back, "raised")], undefined, back);
}

/** Posts the login link for the phone, waits for it to approve, and asks for the two-step password on the phone if needed. */
async function connectTelegram(v: Title) {
  const entry = current;
  if (!entry) return;
  const accountId = entry.account.sessionId;
  let cancelled = false;
  const back = () => { cancelled = true; libraryScreen(); };
  const cancel = () => button("Cancel", back, "raised");
  telegramCard("Connecting Telegram…", ["Getting ready"], [cancel()], undefined, back);
  const retry = [button("Back to library", () => libraryScreen()), button("Try again", () => void askToConnectTelegram(v), "panel")];
  try {
    const [mod, config] = await Promise.all([telegramModule(), api.telegramClientConfig()]);
    let requestId = "";
    let waitingShown = false;
    const session = await mod.loginWithPhone(config, {
      cancelled: () => cancelled,
      postLink: async (link) => {
        requestId = await api.postTelegramLogin(link);
        if (!waitingShown && !cancelled) {
          waitingShown = true;
          telegramCard("Approve on your phone", [`Open Cablegram on your phone and tap Allow to let ${deviceName()} use your Telegram.`, "This takes a few seconds."], [cancel()], undefined, back);
        }
      },
      phoneState: async () => (requestId ? api.telegramLoginState(requestId) : { state: "pending" }),
      password: (hint, attempt) => passwordFromPhone(hint, attempt, () => cancelled, back),
    });
    if (cancelled) return;
    saveSession(accountId, session);
    void startPlayback(v);
  } catch (e) {
    if (cancelled) return;
    const code = e instanceof ApiError ? e.code : (e as { errorMessage?: string; code?: string }).errorMessage ?? (e as { code?: string }).code;
    const reason = (e as { reason?: string }).reason;
    if (e instanceof ApiError && e.code === "telegram_not_linked") {
      errorScreen("Telegram isn't connected yet", "Open Cablegram on your phone, go to Import, connect your Telegram account, then try again.", retry, () => libraryScreen());
    } else if (e instanceof ApiError && e.code === "telegram_not_allowed_on_temporary_tv") {
      errorScreen("Telegram isn't allowed on this TV", "The owner can allow it for this temporary TV in the phone app.", [retry[0]], () => libraryScreen());
    } else if (reason === "denied") {
      errorScreen("Your phone didn't allow it", "Nothing was connected. You can try again whenever you like.", retry, () => libraryScreen());
    } else if (reason === "password") {
      errorScreen("The password wasn't entered", "Telegram needs your two-step password to finish. Try again and type it on your phone when it asks.", retry, () => libraryScreen());
    } else {
      errorScreen("Couldn't connect Telegram", code ? `Telegram answered: ${code}.` : "Check the TV's internet connection and your phone, then try again.", retry, () => libraryScreen());
    }
  }
}

/** The Telegram two-step password, typed on the phone and sealed to a key made here; the control plane cannot read it. */
async function passwordFromPhone(hint: string | undefined, attempt: number, cancelled: () => boolean, back: () => void): Promise<string> {
  const keys = await newTvKeys();
  const id = await api.postTelegramPasswordRequest(keys.publicKey, hint);
  const code = await checkCode(keys.publicKey);
  telegramCard("Type your Telegram password on your phone", [
    attempt > 1 ? "That password wasn't right. Open Cablegram on your phone and try again." : "Your Telegram account has a two-step password. Open Cablegram on your phone and type it there.",
    ...(hint ? [`Hint: ${hint}`] : []),
    "Check that your phone shows this code:",
  ], [button("Cancel", back, "raised")], code, back);
  const end = Date.now() + 5 * 60_000;
  try {
    while (Date.now() < end && !cancelled()) {
      await new Promise<void>((r) => setTimeout(r, 2_000));
      const st = await api.telegramPasswordState(id);
      if (st.state === "delivered" && st.sealed) {
        const password = await openSealed(keys.privateKey, id, st.sealed);
        if (password) return password;
        throw new Error("the sealed password did not open");
      }
      if (st.state === "cancelled" || st.state === "expired") throw new Error(st.state);
    }
    throw new Error(cancelled() ? "cancelled" : "timeout");
  } catch (e) {
    await api.cancelTelegramPasswordRequest(id);
    throw e;
  }
}

/** The phone can end this TV's Telegram session; when it has, sign out here too and say so (contracts: logout-ack). */
async function telegramHousekeeping() {
  if (import.meta.env.VITE_TG_DIRECT !== "1" || !current) return;
  const accountId = current.account.sessionId;
  const session = savedSession(accountId);
  if (!session) return;
  try {
    const link = await api.telegramLink();
    if (!link.logoutRequired) return;
    const [mod, config] = await Promise.all([telegramModule(), api.telegramClientConfig()]);
    await mod.signOut(config, session);
    clearSession(accountId);
    await api.telegramLogoutAck();
  } catch { /* try again the next time the library opens */ }
}

/** How often a refused relay slot is retried before the player gives up (the browser's own extra range request often holds it briefly). */
const SLOT_RETRIES = 4;

function playerScreen(t: Title, p: Profile, url: string, startAt?: number, attempt = 0, smart: SmartSubtitle[] = [], mode: "engine" | "plain" = "plain", telegram?: TelegramRef) {
  const video = document.createElement("video");
  video.autoplay = true;
  let engine: EngineHandle | null = null;
  let embeddedOn: string | null = null;
  const resumeAt = startAt ?? t.resumeSeconds;
  // The engine starts at the resume point itself; plain <video> seeks once its metadata is in.
  const engineStart = resumeAt && (!t.durationSeconds || resumeAt < t.durationSeconds - 10) ? resumeAt : 0;
  const back = () => { void loadLibrary(); };

  let hud: HudState = { ...initialHud };
  let started = false;
  let speed = 1;
  let aspect: Aspect = "Fit";
  let interaction = 0;

  // Top left while starting or interacting: the brand.
  const brandImg = document.createElement("img");
  brandImg.src = "img/cablegram_brand_mark.png";
  const topLeft = h("div", "player-brand", undefined, [brandImg]);
  const flash = h("div", "seek-flash");
  const starting = h("div", "start-card centered", undefined, [h("div", "start-title", "Starting playback…"), h("div", "start-sub", "Opening the video file")]);

  const tTitle = h("div", "hud-title", displayTitle(t));
  const tPaused = h("div", "hud-paused", "Paused");
  const btnRew = h("div", "tbtn ltr", "◀ 10s");
  const btnPlay = h("div", "tbtn");
  const btnFwd = h("div", "tbtn ltr", "10s ▶");
  const previewTime = h("div", "preview-time");
  const previewSpeed = h("div", "preview-speed");
  const preview = h("div", "preview", undefined, [h("div", "preview-label", "Preview"), previewTime, previewSpeed]);
  const barFill = h("i");
  const barKnob = h("b");
  const bar = h("div", "timeline", undefined, [h("div", "timeline-track", undefined, [barFill]), barKnob]);
  const time = h("div", "hud-time ltr");
  const btnTracks = h("div", "tbtn", "Audio / Subtitles");
  const btnSettings = h("div", "tbtn", "Player Settings");
  const controls = h("div", "hud", undefined, [
    h("div", "hud-head", undefined, [tTitle, tPaused]),
    h("div", "hud-transport ltr", undefined, [btnRew, btnPlay, btnFwd]),
    h("div", "hud-timeline ltr", undefined, [preview, bar, time]),
    h("div", "hud-transport", undefined, [btnTracks, btnSettings]),
  ]);
  const menu = h("div", "player-menu");
  // Cablegram subtitles are drawn here; ids "smart:<id>" in the menu, native text tracks keep their index.
  const subLine = h("div", "subtitle-line");
  const subtitleBox = h("div", "subtitles", undefined, [subLine]);
  const parsed: Record<string, Cue[]> = {};
  smart.forEach((s) => { parsed[s.id] = parseVtt(s.content); });
  let smartOn: string | null = startWithSmart(smart[0], savedChoice(t.id)) ? smart[0].id : null;
  let shownText = "";
  const drawSubtitle = () => {
    const text = smartOn && parsed[smartOn] ? cueText(parsed[smartOn], video.currentTime)
      : embeddedOn && engine ? cueText(engine.cues(embeddedOn), video.currentTime) : "";
    if (text === shownText) return;
    shownText = text;
    subLine.textContent = "";
    text.split("\n").forEach((line, i) => { if (i) subLine.appendChild(document.createElement("br")); subLine.appendChild(document.createTextNode(line)); });
    subtitleBox.style.display = text ? "" : "none";
  };
  subtitleBox.style.display = "none";
  const wrap = h("div", "player", undefined, [video, subtitleBox, topLeft, flash, controls, menu, starting]);
  show([wrap], null);
  // After show(): it clears the timers of the screen it replaces.
  every(200, drawSubtitle);

  const positionMs = () => (isFinite(video.currentTime) ? video.currentTime * 1000 : 0);
  const durationMs = () => (isFinite(video.duration) ? video.duration * 1000 : (t.durationSeconds ?? 0) * 1000);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const v = video as any;
  const audioTracks = (): HudTrack[] => {
    if (engine) return engine.audioTracks();
    const list = v.audioTracks;
    if (!list || !list.length) return [];
    const out: HudTrack[] = [];
    for (let i = 0; i < list.length; i++) out.push({ id: String(i), label: list[i].label || list[i].language || `Track ${i + 1}` });
    return out;
  };
  const selectedAudio = (): string | null => {
    if (engine) return engine.selectedAudio();
    const list = v.audioTracks;
    if (!list) return null;
    for (let i = 0; i < list.length; i++) if (list[i].enabled) return String(i);
    return null;
  };
  const subtitleTracks = (): HudTrack[] => {
    const out: HudTrack[] = smart.map((s) => ({ id: `smart:${s.id}`, label: s.label }));
    if (engine) out.push(...engine.subtitleTracks());
    for (let i = 0; i < video.textTracks.length; i++) {
      const tr = video.textTracks[i];
      if (tr.kind === "subtitles" || tr.kind === "captions") out.push({ id: String(i), label: tr.label || tr.language || `Subtitle ${i + 1}` });
    }
    return out;
  };
  const selectedSubtitle = (): string | null => {
    if (smartOn) return `smart:${smartOn}`;
    if (embeddedOn) return embeddedOn;
    for (let i = 0; i < video.textTracks.length; i++) if (video.textTracks[i].mode === "showing") return String(i);
    return null;
  };
  const ctx = (): HudContext => ({
    isPlaying: !video.paused, positionMs: positionMs(), durationMs: durationMs(),
    audioTracks: audioTracks(), subtitleTracks: subtitleTracks(), selectedAudioId: selectedAudio(), selectedSubtitleId: selectedSubtitle(),
    speed, aspect,
  });

  const applyAspect = () => {
    const fit: Record<Aspect, string> = { Fit: "contain", Fill: "fill", Crop: "cover", "16:9": "fill", "4:3": "contain", Zoom: "cover" };
    video.style.objectFit = fit[aspect];
    video.style.transform = aspect === "Zoom" ? "scale(1.33)" : aspect === "4:3" ? "scaleX(0.75)" : "";
  };

  const render = () => {
    const c = ctx();
    const interacting = !started || hud.controlsVisible || hud.seekFlashDeltaMs != null;
    topLeft.style.opacity = interacting ? "1" : "0";
    flash.style.opacity = hud.seekFlashDeltaMs != null && !hud.controlsVisible ? "1" : "0";
    if (hud.seekFlashDeltaMs != null) {
      const s = Math.round(Math.abs(hud.seekFlashDeltaMs) / 1000);
      flash.textContent = hud.seekFlashDeltaMs < 0 ? `↶  ${s} sec` : `${s} sec  ↷`;
    }
    controls.style.opacity = hud.controlsVisible ? "1" : "0";
    controls.style.pointerEvents = hud.controlsVisible ? "" : "none";
    subtitleBox.className = hud.controlsVisible ? "subtitles above-hud" : "subtitles";
    tPaused.style.display = c.isPlaying ? "none" : "";
    btnPlay.textContent = c.isPlaying ? "❚❚" : "▶";
    const focus = (el: HTMLElement, on: boolean) => { el.className = el.className.replace(/ focused/g, "") + (on ? " focused" : ""); };
    const open = menuOpen(hud);
    focus(btnRew, hud.focus === "rewind" && !open);
    focus(btnPlay, hud.focus === "playPause" && !open);
    focus(btnFwd, hud.focus === "fastForward" && !open);
    focus(btnTracks, hud.focus === "audioSubtitles" && !open);
    focus(btnSettings, hud.focus === "playerSettings" && !open);
    focus(bar, hud.focus === "timeline" && !open);
    const shown = hud.previewPositionMs ?? c.positionMs;
    const frac = c.durationMs > 0 ? Math.max(0, Math.min(1, shown / c.durationMs)) : 0;
    barFill.style.width = `${frac * 100}%`;
    barKnob.style.left = `${frac * 100}%`;
    time.textContent = `${formatTime(shown)} / ${c.durationMs > 0 ? formatTime(c.durationMs) : "--:--"}`;
    preview.style.display = timelineActive(hud) ? "" : "none";
    previewTime.textContent = formatTime(shown);
    previewSpeed.textContent = hud.holdMultiplier > 1 ? `${shown < c.positionMs ? "◀◀" : "▶▶"}  ${hud.holdMultiplier}x` : "";
    renderMenu(c);
  };

  let pointerHoveringTimeline = false;

  const renderMenu = (c: HudContext) => {
    menu.textContent = "";
    menu.style.display = menuOpen(hud) ? "" : "none";
    if (hud.overlay === "audioSubtitles") {
      const block = (title: string, section: "audio" | "subtitles", selected: string | null) => {
        const items = menuOptions(section, c).map((o, i) => {
          const focused = hud.menuSection === section && hud.menuIndex === i;
          const opt = h("div", `menu-option${focused ? " focused" : ""}${o.id === selected ? " selected" : ""}`, `${o.id === selected ? "●  " : "    "}${o.id == null ? "Off" : o.label}`);
          opt.addEventListener("mouseenter", () => {
            if (hud.menuSection !== section || hud.menuIndex !== i) {
              hud = { ...hud, menuSection: section, menuIndex: i };
              render();
            }
          });
          opt.addEventListener("click", (e) => {
            e.stopPropagation();
            apply(section === "audio" ? (o.id != null ? [{ type: "audio", id: o.id }] : []) : [{ type: "subtitle", id: o.id }]);
            hud = { ...hud, overlay: "closed", focus: "audioSubtitles" };
            render(); arm();
          });
          return opt;
        });
        return h("div", "menu-block", undefined, [h("div", "menu-heading", title)].concat(items));
      };
      const panel = h("div", "menu-panel", undefined, [
        h("div", "menu-title", "Audio & Subtitles"),
        block("Audio", "audio", c.selectedAudioId),
        block("Subtitles", "subtitles", c.selectedSubtitleId),
      ]);
      panel.addEventListener("click", (e) => e.stopPropagation());
      menu.appendChild(panel);
    } else if (hud.overlay === "playerSettings") {
      const row = (rowKey: SettingsRow, title: string, value: string, focused: boolean, onCycle: () => void) => {
        const el = h("div", `setting-row${focused ? " focused" : ""}`, undefined, [h("span", undefined, title), h("b", undefined, focused ? `◀  ${value}  ▶` : value)]);
        el.addEventListener("mouseenter", () => {
          if (hud.settingsRow !== rowKey) {
            hud = { ...hud, settingsRow: rowKey };
            render();
          }
        });
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          hud = { ...hud, settingsRow: rowKey };
          onCycle();
          render(); arm();
        });
        return el;
      };
      const panel = h("div", "menu-panel", undefined, [
        h("div", "menu-title", "Player Settings"),
        h("div", "menu-sub", "Left / Right or Click to adjust"),
        row("speed", "Playback Speed", formatSpeed(speed), hud.settingsRow === "speed", () => {
          const idx = SPEEDS.indexOf(speed);
          apply([{ type: "speed", rate: SPEEDS[(idx + 1) % SPEEDS.length] }]);
        }),
        row("aspect", "Aspect Ratio", aspect, hud.settingsRow === "aspect", () => {
          const idx = ASPECTS.indexOf(aspect);
          apply([{ type: "aspect", mode: ASPECTS[(idx + 1) % ASPECTS.length] }]);
        }),
      ]);
      panel.addEventListener("click", (e) => e.stopPropagation());
      menu.appendChild(panel);
    }
  };

  // Auto-hide, the seek flash, and the timeline letting go: re-armed on every interaction like Android's effects.
  let hideTimer = 0, flashTimer = 0, scrubTimer = 0;
  const arm = () => {
    clearTimeout(hideTimer); clearTimeout(flashTimer); clearTimeout(scrubTimer);
    if (hud.seekFlashDeltaMs != null && !hud.controlsVisible) {
      flashTimer = window.setTimeout(() => { hud = { ...hud, seekFlashDeltaMs: null }; render(); }, SEEK_FLASH_MS);
    }
    if (hud.controlsVisible && !video.paused && !menuOpen(hud) && hud.focus !== "timeline") {
      hideTimer = window.setTimeout(() => { hud = { ...hud, controlsVisible: false, previewPositionMs: null, holdMultiplier: 1 }; render(); }, AUTO_HIDE_MS);
    }
    if (timelineActive(hud) && !pointerHoveringTimeline) {
      const preview0 = hud.previewPositionMs;
      const skimming = preview0 != null && Math.abs(preview0 - positionMs()) > 500;
      scrubTimer = window.setTimeout(() => {
        if (preview0 != null && skimming) video.currentTime = preview0 / 1000;
        hud = { ...hud, focus: "playPause", previewPositionMs: null, holdMultiplier: 1 };
        render(); arm();
      }, skimming ? SCRUB_COMMIT_MS : AUTO_HIDE_MS);
    }
  };
  screenCleanup.push(() => { clearTimeout(hideTimer); clearTimeout(flashTimer); clearTimeout(scrubTimer); });

  let lastSave = 0;
  const save = (state: "playing" | "paused" | "stopped" | "completed") =>
    api.saveProgress(p.id, t.id, video.currentTime, isFinite(video.duration) ? video.duration : t.durationSeconds, state).catch(() => {});
  let exited = false;
  const stop = (then: () => void = back) => {
    if (exited) return;
    exited = true;
    void save("stopped");
    if (engine) engine.destroy();
    else {
      video.removeAttribute("src");
      video.load();
    }
    then();
  };

  const apply = (effects: HudEffect[]) => {
    effects.forEach((e) => {
      switch (e.type) {
        case "exit": stop(); break;
        case "play": void video.play(); break;
        case "pause": video.pause(); break;
        case "seekBy": video.currentTime = Math.max(0, video.currentTime + e.deltaMs / 1000); break;
        case "seekTo": video.currentTime = e.positionMs / 1000; break;
        case "speed": speed = e.rate; video.playbackRate = e.rate; break;
        case "aspect": aspect = e.mode; applyAspect(); break;
        case "audio": {
          if (engine) { if (e.id) engine.selectAudio(e.id); break; }
          const list = v.audioTracks;
          if (list) for (let i = 0; i < list.length; i++) list[i].enabled = String(i) === e.id;
          break;
        }
        case "subtitle": {
          const id = e.id;
          smartOn = id && id.indexOf("smart:") === 0 ? id.slice(6) : null;
          embeddedOn = id && id.indexOf(EMBEDDED) === 0 ? id : null;
          for (let i = 0; i < video.textTracks.length; i++) video.textTracks[i].mode = String(i) === id ? "showing" : "disabled";
          // Remembered per video, and for which Cablegram subtitle, so a newly added one still shows next time.
          if (smart[0]) saveChoice(t.id, { off: !smartOn, smartId: smart[0].id });
          drawSubtitle();
          break;
        }
      }
    });
  };

  video.addEventListener("loadedmetadata", () => {
    if (engine) return;
    const resume = startAt ?? t.resumeSeconds;
    if (resume && resume < video.duration - 10) video.currentTime = resume;
  });
  video.addEventListener("playing", () => {
    if (!started) { started = true; starting.style.display = "none"; }
    render(); arm();
  });
  video.addEventListener("pause", () => { void save("paused"); render(); arm(); });
  video.addEventListener("timeupdate", () => {
    if (hud.controlsVisible) render();
    if (Date.now() - lastSave > 15_000) { lastSave = Date.now(); void save(video.paused ? "paused" : "playing"); }
  });
  video.addEventListener("ended", () => { void save("completed"); stop(); });
  video.addEventListener("error", () => {
    if (exited || engine) return; // the engine reports media errors itself
    const at = started ? video.currentTime : startAt;
    // Release our own relay stream before asking why: the relay counts open requests.
    exited = true;
    video.removeAttribute("src");
    video.load();
    starting.style.display = "";
    screenKeys = (k) => { if (k === "back" || k === "stop") libraryScreen(); return true; };
    void (async () => {
      const failure = await preflight(url);
      if (!document.body.contains(wrap)) return;
      if (failure && failure.error === "relay_streams_exceeded" && attempt < SLOT_RETRIES) {
        window.setTimeout(() => { if (document.body.contains(wrap)) playerScreen(t, p, url, at, attempt + 1, smart); }, 2_000 * (attempt + 1));
        return;
      }
      if (started) void save("stopped");
      const message = failure ? relayMessage(failure.status, failure.error)
        : "This TV can't play this video's format. It plays on the Android TV app.";
      errorScreen("Couldn't play this video", message,
        [button("Back to library", () => libraryScreen()), button("Try again", () => void startPlayback(t), "panel")], () => libraryScreen());
    })();
  });

  if (mode === "engine") {
    engine = openEngine(video, telegram ? lazySource(() => telegramSource(telegram)) : url, engineStart, {
      onError: (e) => {
        if (exited) return;
        exited = true;
        const at = started ? video.currentTime : startAt;
        engine?.destroy();
        engine = null;
        const message = engineFailureMessage(e);
        if (message === null && url) {
          // A format the engine can't handle: the TV's own player may still manage it.
          playerScreen(t, p, url, at, 0, smart, "plain");
          return;
        }
        if (started) void save("stopped");
        // Telegram ended this TV's session (or it never worked): signing in again fixes it, trying again does not.
        const reconnect = telegram && e.kind === "telegram_auth" && e.code !== "telegram_direct_not_allowed";
        errorScreen("Couldn't play this video", message ?? "This TV can't play this video's format. It plays on the Android TV app.",
          [button("Back to library", () => libraryScreen()),
            reconnect ? button("Connect Telegram again", () => { clearSession(current?.account.sessionId ?? ""); askToConnectTelegram(t); }, "panel")
              : button("Try again", () => void startPlayback(t), "panel")], () => libraryScreen());
      },
    });
    // For inspection from a debugger on the TV (tracks, codecs); not used by the app.
    (window as unknown as { __player?: unknown }).__player = engine.player;
    engine.player.ready.then(() => {
      const say = silentVideoNotice(engine?.audioTracks().length ?? 0, engine?.player.unplayableAudio.map((t) => t.codec) ?? []);
      if (say) notice(say, 9_000);
    }).catch(() => undefined);
  } else video.src = url;

  // LG Magic Remote / mouse pointer handlers for HUD, transport buttons, timeline, and overlay menus.
  const pointerTimeMs = (e: MouseEvent): number => {
    const rect = bar.getBoundingClientRect();
    const dur = durationMs();
    if (rect.width <= 0 || dur <= 0) return positionMs();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    return Math.round(ratio * dur);
  };
  let lastMoveWakeAt = 0;
  wrap.addEventListener("mousemove", () => {
    if (exited) return;
    const now = Date.now();
    if (!hud.controlsVisible) {
      lastMoveWakeAt = now;
      hud = { ...hud, controlsVisible: true, seekFlashDeltaMs: null };
      render(); arm();
    } else if (now - lastMoveWakeAt > 350 && !pointerHoveringTimeline) {
      lastMoveWakeAt = now;
      arm();
    }
  });
  wrap.addEventListener("click", () => {
    if (exited || Date.now() - lastKeySelectAt < 250) return;
    if (menuOpen(hud)) {
      hud = { ...hud, overlay: "closed" };
      render(); arm();
      return;
    }
    if (!hud.controlsVisible) {
      hud = { ...hud, controlsVisible: true, focus: "playPause", seekFlashDeltaMs: null };
    } else {
      apply([{ type: ctx().isPlaying ? "pause" : "play" }]);
    }
    render(); arm();
  });
  controls.addEventListener("click", (e) => e.stopPropagation());
  menu.addEventListener("click", (e) => {
    e.stopPropagation();
    if (menuOpen(hud)) {
      hud = { ...hud, overlay: "closed" };
      render(); arm();
    }
  });
  btnRew.addEventListener("mouseenter", () => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "rewind", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  btnRew.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "rewind", previewPositionMs: null, seekFlashDeltaMs: null };
    apply([{ type: "seekBy", deltaMs: -SEEK_STEP_MS }]);
    render(); arm();
  });
  btnPlay.addEventListener("mouseenter", () => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "playPause", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  btnPlay.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "playPause", previewPositionMs: null, seekFlashDeltaMs: null };
    apply([{ type: ctx().isPlaying ? "pause" : "play" }]);
    render(); arm();
  });
  btnFwd.addEventListener("mouseenter", () => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "fastForward", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  btnFwd.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "fastForward", previewPositionMs: null, seekFlashDeltaMs: null };
    apply([{ type: "seekBy", deltaMs: SEEK_STEP_MS }]);
    render(); arm();
  });
  btnTracks.addEventListener("mouseenter", () => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "audioSubtitles", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  btnTracks.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    const [next, effects] = reduceHud({ ...hud, controlsVisible: true, overlay: "closed", focus: "audioSubtitles" }, "ok", 0, ctx());
    hud = next;
    apply(effects);
    render(); arm();
  });
  btnSettings.addEventListener("mouseenter", () => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, controlsVisible: true, focus: "playerSettings", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  btnSettings.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    const [next, effects] = reduceHud({ ...hud, controlsVisible: true, overlay: "closed", focus: "playerSettings" }, "ok", 0, ctx());
    hud = next;
    apply(effects);
    render(); arm();
  });
  bar.addEventListener("mousemove", (e) => {
    if (menuOpen(hud)) return;
    pointerHoveringTimeline = true;
    hud = { ...hud, controlsVisible: true, focus: "timeline", previewPositionMs: pointerTimeMs(e), holdMultiplier: 1, seekFlashDeltaMs: null };
    render(); arm();
  });
  bar.addEventListener("mouseleave", () => {
    if (!pointerHoveringTimeline) return;
    pointerHoveringTimeline = false;
    hud = { ...hud, focus: "playPause", previewPositionMs: null, holdMultiplier: 1 };
    render(); arm();
  });
  bar.addEventListener("click", (e) => {
    e.stopPropagation();
    if (Date.now() - lastKeySelectAt < 250) return;
    pointerHoveringTimeline = false;
    const targetMs = pointerTimeMs(e);
    hud = { ...hud, controlsVisible: true, focus: "timeline", previewPositionMs: null, holdMultiplier: 1, seekFlashDeltaMs: null };
    apply([{ type: "seekTo", positionMs: targetMs }, { type: "play" }]);
    render(); arm();
  });

  const dpad: Partial<Record<Key, DpadKey>> = { up: "up", down: "down", left: "left", right: "right", enter: "ok", back: "back" };
  screenKeys = (key, _e, repeat) => {
    interaction++;
    pointerHoveringTimeline = false;
    const d = dpad[key];
    if (d) {
      const [next, effects] = reduceHud(hud, d, repeat, ctx());
      hud = next;
      apply(effects);
    } else if (key === "playpause") { if (video.paused) void video.play(); else video.pause(); hud = { ...hud, controlsVisible: true }; }
    else if (key === "play") { void video.play(); hud = { ...hud, controlsVisible: true, overlay: "closed" }; }
    else if (key === "pause") { video.pause(); hud = { ...hud, controlsVisible: true, overlay: "closed" }; }
    else if (key === "ff" || key === "rw") {
      const delta = key === "ff" ? SEEK_STEP_MS : -SEEK_STEP_MS;
      apply([{ type: "seekBy", deltaMs: delta }]);
      hud = { ...hideControls(hud), seekFlashDeltaMs: delta };
    } else if (key === "stop") stop();
    else return false;
    if (!exited) { render(); arm(); }
    return true;
  };
  backHandler = null;
  applyAspect();
  render();
}

// ---- Keys --------------------------------------------------------------------------------------------------------

function hexAlpha(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

function cssEscape(s: string): string { return s.replace(/["\\]/g, "\\$&"); }

let held: { code: number; count: number } | null = null;
let enterDown: { at: number; timer: number; long: boolean } | null = null;

document.addEventListener("keydown", (e) => {
  const key = keyFromEvent(e);
  if (!key) return;
  e.preventDefault();
  const repeat = held && held.code === e.keyCode ? ++held.count : 0;
  if (!held || held.code !== e.keyCode) held = { code: e.keyCode, count: 0 };
  if (screenKeys && screenKeys(key, e, repeat)) return;
  if (key === "back") {
    if (repeat) return;
    if (modal) { modal.back(); return; }
    backHandler?.();
    return;
  }
  if (key === "enter") {
    // OK acts on release so holding it can mean something else (Hold OK adds to My List).
    if (enterDown) return;
    const el = document.activeElement as Focusable | null;
    enterDown = { at: Date.now(), long: false, timer: window.setTimeout(() => {
      if (enterDown && el && el === document.activeElement && el.onLong) { enterDown.long = true; el.onLong(); }
    }, LONG_PRESS_MS) };
    return;
  }
  if (key === "up" || key === "down" || key === "left" || key === "right") moveFocus(key, modal ? modal.el : root);
});

document.addEventListener("keyup", (e) => {
  if (held && held.code === e.keyCode) held = null;
  if (keyFromEvent(e) !== "enter" || !enterDown) return;
  const press = enterDown;
  enterDown = null;
  clearTimeout(press.timer);
  if (press.long) return;
  lastKeySelectAt = Date.now();
  const el = document.activeElement as Focusable | null;
  if (el && el.onSelect && (!modal || modal.el.contains(el))) el.onSelect();
});

registerPlatformKeys();

async function start() {
  if (location.hash === "#diag") { diagnosticsScreen(); return; }
  if (!store.accounts().length) { void pair(false); return; }
  await loadProfiles();
  // Reopen the profile this TV was on, unless it needs a PIN or a phone's approval.
  const last = store.activeProfileId();
  const entry = profileEntries.filter((x) => x.profile.id === last)[0];
  if (entry && !entry.profile.pinSet && root.querySelector(".picker")) void proceedWithProfile(entry);
}
void start();
