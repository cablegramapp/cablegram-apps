import { API_BASE } from "../config.js";
import { createApi, REMOTE, ApiError, validateServerUrl } from "./api.js";
import { trackCommand, OUTCOME } from "./outcome.js";
import { capturePairLink, clearPendingPair, pendingPair } from "./pairlink.js";

const api = createApi({ base: API_BASE });
const root = document.getElementById("app");
const TV_KEY = "cablegram.tv";
const TABS = { library: "Library", remote: "Remote", tvs: "TVs" };
/** This tab's storage for a TV scanned before signing in (CAB-62); a stand-in when the browser refuses storage. */
const tabStore = (() => {
  try { return window.sessionStorage; } catch { return { getItem: () => null, setItem() {}, removeItem() {} }; }
})();

/** Tiny DOM helper. Text always goes in as text, never as HTML: the server supplies titles and TV names. */
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === false || value == null) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) if (child != null && child !== false) node.append(child);
  return node;
}

/** Replaces the page; a null child (an optional piece) is skipped, where replaceChildren would print "null". */
function mount(...nodes) {
  root.replaceChildren(...nodes.flat().filter(Boolean));
}

/** Icons are fixed markup from this file, never server text, so building them from a string is safe. */
const ICONS = {
  library: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  remote: '<rect x="7" y="2" width="10" height="20" rx="3"/><circle cx="12" cy="8" r="2"/><path d="M10 14h4M10 17.5h4"/>',
  tv: '<rect x="2.5" y="4.5" width="19" height="13" rx="2"/><path d="M8 21h8"/>',
  play: '<path d="M7.5 5v14l11.5-7z" fill="currentColor"/>',
  pause: '<path d="M8 5v14M16 5v14" stroke-width="3.5"/>',
  stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="1.5" fill="currentColor"/>',
  prev: '<path d="M6 5v14"/><path d="M19 5.5v13L9 12z" fill="currentColor"/>',
  next: '<path d="M18 5v14"/><path d="M5 5.5v13L15 12z" fill="currentColor"/>',
  up: '<path d="M6 15l6-6 6 6"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  left: '<path d="M15 6l-6 6 6 6"/>',
  right: '<path d="M9 6l6 6-6 6"/>',
  volume: '<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z" fill="currentColor"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
  muted: '<path d="M4 9.5v5h3.5L12 18V6L7.5 9.5z" fill="currentColor"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  chevron: '<path d="M7 10l5 5 5-5"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
};

function icon(name) {
  const t = document.createElement("template");
  t.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
  return t.content.firstChild;
}

const state = {
  tvs: [], tv: remembered(), library: [], loaded: false, libraryError: "", link: null, pending: [], profileRequests: [], joinRequests: [], lock: null, idle: null,
  tab: TABS[location.hash.slice(1)] ? location.hash.slice(1) : "library", query: "", muted: false, volume: 50,
  serverCaps: null,
};
let pollTimer = null;
let joinPollTimer = null;
const serverCapsCache = new Map();

async function getServerCaps(force = false) {
  const url = api.baseUrl;
  if (!force && serverCapsCache.has(url)) {
    state.serverCaps = serverCapsCache.get(url);
    return state.serverCaps;
  }
  try {
    const caps = await api.server();
    serverCapsCache.set(url, caps);
    state.serverCaps = caps;
    return caps;
  } catch {
    const fallback = {
      api_version: 1,
      edition: "hosted",
      auth: { mode: "account", email_code: true },
      features: { telegram: true, lan_playback: true, profile_lock: true },
      legal: null,
    };
    state.serverCaps = fallback;
    return fallback;
  }
}

function isDeviceAuth() {
  return state.serverCaps?.auth?.mode === "device";
}

function remembered() {
  try { return localStorage.getItem(TV_KEY); } catch { return null; }
}

function message(error) {
  if (!(error instanceof ApiError)) return "Can't reach Cablegram. Check your connection.";
  switch (error.code) {
    case "invalid_code": return "That code isn't right. Check the email and try again.";
    case "code_expired": return "That code expired. Ask for a new one.";
    case "code_rate_limited": return "A code was sent a moment ago. Wait a minute before asking again.";
    case "rate_limited": return "Too many tries. Wait a while and try again.";
    case "mail_unavailable": return "We couldn't send the email. Try again in a few minutes.";
    case "invalid_google_token": return "Google sign-in didn't work. Try again.";
    case "google_email_unverified": return "Your Google account's email isn't verified. Sign in with an email code instead.";
    case "google_account_mismatch": return "This email is already linked to another Google account.";
    case "google_not_configured": return "Google sign-in isn't available on this server.";
    case "disposable_email": return "Use a permanent email address.";
    case "forbidden_for_web_client": return "This can't be done from the web app.";
    case "unknown_target_device": return "That TV isn't paired any more.";
    case "pin_expired": return "That code expired. Scan the new code on the TV, or type the new PIN.";
    case "invalid_pin": return "That code didn't match. Check the code on the TV and try again.";
    case "invalid_setup_code": return "That setup code isn't valid or has already been used.";
    case "setup_code_expired": return "That setup code expired. Run cablegram-server setup-code on your server for a new one.";
    case "household_not_initialized": return "This server isn't set up yet. Enter the setup code from the server log first.";
    default: return error.status === 401 ? "Please sign in again." : `Something went wrong (${error.code || error.status}).`;
  }
}

/** A 401 that the refresh token couldn't fix has already cleared the session: go back to sign-in. */
function signedOut(error) {
  if (!(error instanceof ApiError) || error.status !== 401 || api.signedIn) return false;
  renderSignIn("Please sign in again.");
  return true;
}

let toastTimer = null;
/** Short confirmations ("Paused") and errors, dropping in under the header so they show on every tab. */
function toast(text) {
  let node = document.getElementById("toast");
  if (!node) document.body.append(node = h("div", { id: "toast", class: "toast", role: "status" }));
  node.textContent = text;
  node.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove("show"), 2600);
}

// ---- Sign in (spec 007: Google or an email code, no passwords) ----

const FALLBACK_LEGAL = {
  terms: { version: "", url: "https://cablegram.app/terms.html" },
  privacy: { version: "", url: "https://cablegram.app/privacy.html" },
};
let legalDocs = null;

/** The current Terms and privacy policy; the links work even when the server can't be reached. */
async function legal() {
  if (!legalDocs) {
    try { legalDocs = await api.legal(); } catch { return FALLBACK_LEGAL; }
  }
  return legalDocs;
}

function link(href, text) {
  return h("a", { href, target: "_blank", rel: "noopener" }, text);
}

function stopJoinPolling() {
  clearInterval(joinPollTimer);
  joinPollTimer = null;
}

function signinPage(...children) {
  stopPolling();
  stopJoinPolling();
  stopTracking();
  closeSheet();
  const scanned = pendingPair(tabStore);
  mount(h("div", { class: "signin" },
    h("img", { class: "logo", src: "icons/icon-192.png", alt: "" }),
    // CAB-62: the person scanned a TV's code; it waits here until they have an account.
    scanned && h("div", { class: "banner", role: "status" }, h("p", {}, `Sign in or create an account to connect ${scanned.tv}.`)),
    ...children));
}

async function selectServer(url) {
  await api.logout().catch(() => {});
  api.setBaseUrl(url);
  legalDocs = null;
  state.tvs = [];
  state.tv = null;
  state.library = [];
  state.loaded = false;
  state.libraryError = "";
  state.link = null;
  state.pending = [];
  state.profileRequests = [];
  state.joinRequests = [];
  try { localStorage.removeItem(TV_KEY); } catch { /* fine */ }
  await getServerCaps(true);
  renderSignIn();
}

function serverFooter() {
  const custom = api.baseUrl !== api.defaultBaseUrl;
  return h("p", { class: "center hint" },
    custom ? `Server: ${api.baseUrl} · ` : null,
    h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderServerPicker(); } }, custom ? "Change server" : "Join a server"),
    custom ? [" · ", h("a", { href: "#", onclick: (e) => { e.preventDefault(); selectServer(null); } }, "Use default server")] : null,
  );
}

function renderServerPicker(error = "") {
  const input = h("input", {
    type: "url", placeholder: "https://cablegram.example.com", required: true,
    "aria-label": "Server URL", value: api.baseUrl === api.defaultBaseUrl ? "" : api.baseUrl,
  });
  const warnBox = h("label", { class: "agree", style: "display:none" });
  const warnCheck = h("input", { type: "checkbox" });
  warnBox.append(warnCheck, h("span", {}, "Allow unencrypted HTTP on my local network (LAN)."));
  const submit = h("button", { class: "primary wide", type: "submit" }, "Connect to server");
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  input.addEventListener("input", () => {
    const check = validateServerUrl(input.value);
    warnBox.style.display = check.ok && check.insecureLan ? "" : "none";
  });
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      const check = validateServerUrl(input.value);
      if (!check.ok) { errorLine.textContent = check.error; return; }
      if (check.insecureLan && !warnCheck.checked) {
        warnBox.style.display = "";
        errorLine.textContent = "Confirm that you trust this local network HTTP address.";
        return;
      }
      submit.disabled = true;
      await selectServer(check.url);
    },
  }, input, warnBox, submit);
  signinPage(
    h("h1", {}, "Join a server"),
    h("p", {}, "Enter the address of your Cablegram server."),
    form,
    errorLine,
    h("p", { class: "center" },
      api.baseUrl !== api.defaultBaseUrl && [h("a", { href: "#", onclick: (e) => { e.preventDefault(); selectServer(null); } }, "Return to Cablegram"), " · "],
      h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderSignIn(); } }, "Back")),
  );
}

function renderDeviceSignIn(error = "") {
  const code = h("input", {
    autocapitalize: "characters", autocomplete: "one-time-code", maxlength: "12", required: true,
    placeholder: "Setup code (e.g. ABCD-EFGH)", "aria-label": "Setup code from server log",
  });
  const name = h("input", {
    autocomplete: "nickname", maxlength: "60", placeholder: "Device name (optional)", "aria-label": "Device name", value: "Web Remote",
  });
  const submit = h("button", { class: "primary wide", type: "submit" }, "Claim with setup code");
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      submit.disabled = true;
      try {
        await api.claimSetup(code.value.trim(), name.value.trim() || "Web Remote");
        start();
      } catch (e) {
        errorLine.textContent = message(e);
        submit.disabled = false;
      }
    },
  }, code, name, submit);
  const joinBtn = h("button", {
    class: "wide", type: "button",
    onclick: () => renderJoinWait(name.value.trim() || "Web Remote"),
  }, "Ask an existing phone to approve this device");
  signinPage(
    h("h1", {}, "Connect to server"),
    h("p", {}, `Sign in to ${api.baseUrl} with the setup code from the server log, or approve from an existing phone.`),
    form,
    h("p", { class: "or" }, "or"),
    joinBtn,
    errorLine,
    serverFooter(),
    installHint(),
  );
}

async function renderJoinWait(deviceName) {
  signinPage(h("h1", {}, "Requesting approval…"), h("p", {}, "Contacting server…"));
  let req;
  try {
    req = await api.createJoinRequest(deviceName);
  } catch (e) {
    renderDeviceSignIn(message(e));
    return;
  }
  const statusLine = h("p", { class: "hint" }, "Open Cablegram on an already signed-in phone or remote and tap Allow.");
  signinPage(
    h("h1", {}, "Approve on your phone"),
    h("p", {}, `Check that the code matches on your other phone:`),
    h("p", { class: "pin", style: "font-size:1.75rem;font-weight:700;letter-spacing:0.12em;text-align:center" }, req.short_code),
    statusLine,
    h("p", { class: "center" },
      h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderDeviceSignIn(); } }, "Cancel")),
  );
  joinPollTimer = setInterval(async () => {
    try {
      const res = await api.pollJoinRequest(req.id, req.poll_secret);
      if (res?.status === "approved" && api.signedIn) {
        stopJoinPolling();
        start();
      } else if (res?.status === "denied") {
        stopJoinPolling();
        renderDeviceSignIn("The join request was denied.");
      } else if (res?.status === "expired") {
        stopJoinPolling();
        renderDeviceSignIn("The join request expired. Try again.");
      }
    } catch (e) {
      stopJoinPolling();
      renderDeviceSignIn(message(e));
    }
  }, 2_000);
}

/** First screen: Google (when the server offers it) or an email code, or device sign-in on Community servers. */
async function renderSignIn(error = "", email = "") {
  const caps = await getServerCaps();
  if (caps.auth?.mode === "device") {
    renderDeviceSignIn(error);
    return;
  }
  const googleSlot = h("div", { class: "google" });
  const input = h("input", { type: "email", autocomplete: "email", placeholder: "Email", required: true, "aria-label": "Email", value: email });
  const submit = h("button", { class: "primary wide", type: "submit" }, "Email me a code");
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      submit.disabled = true;
      const address = input.value.trim();
      try {
        await api.requestCode(address);
        renderCode(address);
      } catch (e) {
        errorLine.textContent = message(e);
        submit.disabled = false;
      }
    },
  }, input, submit);
  const docs = await legal();
  signinPage(
    h("h1", {}, "Cablegram Remote"),
    h("p", {}, "Choose videos and control your TV from this phone."),
    googleSlot,
    form,
    errorLine,
    h("p", { class: "hint" }, "How Cablegram uses your data: ", link(docs.privacy.url, "Privacy Policy"), "."),
    serverFooter(),
    installHint(),
  );
  showGoogleButton(googleSlot, (text) => { errorLine.textContent = text; });
}

/** The code from the email signs a known person in, or leads a new person to Create your account. */
function renderCode(email, error = "") {
  const code = h("input", {
    inputmode: "numeric", pattern: "[0-9]{6}", maxlength: "6", autocomplete: "one-time-code", required: true,
    placeholder: "6-digit code", "aria-label": "6-digit code from the email",
  });
  const submit = h("button", { class: "primary wide", type: "submit" }, "Continue");
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      submit.disabled = true;
      try {
        signedIn(await api.verifyCode(email, code.value.trim()));
      } catch (e) {
        errorLine.textContent = message(e);
        submit.disabled = false;
      }
    },
  }, code, submit);
  const resend = async (event) => {
    event.preventDefault();
    try {
      await api.requestCode(email);
      errorLine.textContent = "";
      toast("A new code is on its way.");
    } catch (e) { errorLine.textContent = message(e); }
  };
  signinPage(
    h("h1", {}, "Check your email"),
    h("p", {}, `Enter the 6-digit code we sent to ${email}. It expires in 15 minutes. Didn't get it? Check your spam or junk folder.`),
    form,
    errorLine,
    h("p", { class: "center" },
      h("a", { href: "#", onclick: resend }, "Send a new code"), " · ",
      h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderSignIn("", email); } }, "Use a different email")),
  );
  code.focus();
}

/** After Google or a code: tokens for a known person, or Create your account for a new one. */
function signedIn(result) {
  if (result?.status === "signup_required") renderCreateAccount(result);
  else start();
}

/** Create your account: one unticked box for age and Terms; nothing is stored until it is ticked and sent. */
async function renderCreateAccount(ticket, error = "") {
  const docs = await legal();
  const name = h("input", { autocomplete: "name", placeholder: "Your name", required: true, maxlength: "80", "aria-label": "Your name", value: ticket.name || "" });
  const agree = h("input", { type: "checkbox", id: "agree" });
  const submit = h("button", { class: "primary wide", type: "submit", disabled: true }, "Create account");
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  const update = () => { submit.disabled = !(agree.checked && name.value.trim()); };
  agree.addEventListener("change", update);
  name.addEventListener("input", update);
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      if (submit.disabled) return;
      submit.disabled = true;
      try {
        await api.signUp(ticket.signup_ticket, name.value.trim(), docs);
        start();
      } catch (e) {
        if (e instanceof ApiError && e.code === "terms_outdated") {
          legalDocs = null;
          renderCreateAccount(ticket, "The Terms or Privacy Policy just changed. Please read them again.");
        } else if (e instanceof ApiError && e.code === "invalid_signup_ticket") {
          renderSignIn("That took too long. Start again.", ticket.email);
        } else {
          errorLine.textContent = message(e);
          update();
        }
      }
    },
  },
    name,
    h("label", { class: "agree", for: "agree" }, agree,
      h("span", {}, "I'm 18 or older and I accept the ", link(docs.terms.url, "Terms of Service"), ".")),
    submit,
  );
  signinPage(
    h("h1", {}, "Create your account"),
    h("p", {}, `For ${ticket.email}.`),
    form,
    errorLine,
    h("p", { class: "hint" }, "How Cablegram uses your data: ", link(docs.privacy.url, "Privacy Policy"), "."),
  );
  update();
}

/** New Terms: nothing else until they are accepted, except deleting the account or signing out (spec 007 US4). */
async function renderTerms(error = "") {
  legalDocs = null;
  const docs = await legal();
  const agree = h("input", { type: "checkbox", id: "agree" });
  const submit = h("button", { class: "primary wide", type: "submit", disabled: true }, "Accept");
  agree.addEventListener("change", () => { submit.disabled = !agree.checked; });
  const errorLine = h("p", { class: "error", role: "alert" }, error);
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      submit.disabled = true;
      try {
        await api.acceptTerms(docs);
        start();
      } catch (e) {
        if (signedOut(e)) return;
        if (e instanceof ApiError && e.code === "terms_outdated") renderTerms("The Terms just changed again. Please read them.");
        else { errorLine.textContent = message(e); submit.disabled = false; }
      }
    },
  }, h("label", { class: "agree", for: "agree" }, agree, h("span", {}, "I accept the ", link(docs.terms.url, "Terms of Service"), ".")), submit);
  signinPage(
    h("h1", {}, "Our Terms changed"),
    h("p", {}, "Please read the new Terms of Service. You can keep using Cablegram once you accept them."),
    form,
    errorLine,
    h("p", { class: "hint" }, "How Cablegram uses your data: ", link(docs.privacy.url, "Privacy Policy"), "."),
    h("p", { class: "center" },
      !isDeviceAuth() && [link(`${api.baseUrl}/delete-account`, "Delete my account"), " · "],
      h("a", { href: "#", onclick: async (e) => { e.preventDefault(); await api.logout(); renderSignIn(); } }, "Sign out")),
  );
}

// ---- Google (Google Identity Services, popup) ----

let googleScript = null;

function loadGoogle() {
  googleScript ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.onload = () => resolve(window.google);
    script.onerror = () => { googleScript = null; reject(new Error("google_script")); };
    document.head.append(script);
  });
  return googleScript;
}

/** A fresh random nonce per sign-in: Google puts it in the ID token and the server checks it. */
function nonce() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Shows Google's own button when the server offers Google; otherwise only the email code is shown. */
async function showGoogleButton(slot, onError) {
  let clientId;
  try { clientId = (await api.methods()).google?.client_id; } catch { return; }
  if (!clientId) return;
  let google;
  try { google = await loadGoogle(); } catch { return; }
  if (!slot.isConnected) return;
  const value = nonce();
  google.accounts.id.initialize({
    client_id: clientId,
    nonce: value,
    ux_mode: "popup",
    auto_select: false,
    callback: async ({ credential }) => {
      try {
        signedIn(await api.google(credential, value));
      } catch (e) { onError(message(e)); }
    },
  });
  google.accounts.id.renderButton(slot, { type: "standard", theme: "filled_black", size: "large", text: "continue_with", shape: "pill", width: 300 });
  slot.after(h("p", { class: "or" }, "or"));
}

function installHint() {
  const iOS = /iphone|ipad/i.test(navigator.userAgent);
  const installed = navigator.standalone || matchMedia("(display-mode: standalone)").matches;
  if (!iOS || installed) return null;
  return h("p", { class: "hint" }, "Tip: tap the Share button in Safari, then “Add to Home Screen” to keep this remote on your home screen.");
}

// ---- Data ----

async function start() {
  await getServerCaps();
  try {
    const account = await api.account();
    if (account.termsRequired) { renderTerms(); return; }
    state.tvs = account.tvs;
    state.lock = account.profileLock;
    state.idle = account.sharedTvIdleMinutes;
  } catch (e) {
    if (signedOut(e)) return;
    toast(message(e));
  }
  if (!state.tvs.some((tv) => tv.id === state.tv)) state.tv = state.tvs[0]?.id ?? null;
  render();
  offerPendingPair();
  refreshLibrary();
  refreshTelegram();
  startPolling();
}

/** Redraws only when the library changed, and never under an open sheet or a search being typed. */
async function refreshLibrary() {
  const before = JSON.stringify([state.library, state.libraryError]);
  try {
    state.library = await api.library();
    state.libraryError = "";
  } catch (e) {
    if (signedOut(e)) return;
    state.libraryError = message(e);
  }
  const first = !state.loaded;
  state.loaded = true;
  if (state.tab !== "library" || document.getElementById("sheet")) return;
  if (first || JSON.stringify([state.library, state.libraryError]) !== before) render();
}

/** Updates only the TV requests and Telegram parts of the page, and only when they changed: a full redraw would wipe a code being typed. */
async function refreshTelegram() {
  const before = JSON.stringify([state.link, state.pending, state.profileRequests, state.joinRequests]);
  try { state.link = await api.telegramLink(); } catch { /* the banners stay as they were */ }
  try { state.pending = await api.pendingTvLogins(); } catch { /* same */ }
  try { state.profileRequests = await api.pendingProfileRequests(); } catch { /* same */ }
  if (isDeviceAuth()) {
    try { state.joinRequests = await api.pendingJoinRequests(); } catch { /* same */ }
  }
  if (JSON.stringify([state.link, state.pending, state.profileRequests, state.joinRequests]) === before) return;
  document.getElementById("banners")?.replaceChildren(...banners());
  document.getElementById("telegram")?.replaceChildren(...telegramHelp());
}

function startPolling() {
  stopPolling();
  // A TV's login link is valid for about a minute, so ask often, and only while the page is open.
  pollTimer = setInterval(() => { if (!document.hidden) refreshTelegram(); }, 5_000);
}

function stopPolling() {
  clearInterval(pollTimer);
  pollTimer = null;
}

function tvName() {
  return state.tvs.find((t) => t.id === state.tv)?.display_name || "your TV";
}

function selectTv(id) {
  // What was sent to the old TV says nothing about the new one.
  if (id !== state.tv) stopTracking();
  state.tv = id;
  try { localStorage.setItem(TV_KEY, id); } catch { /* fine */ }
}

// ---- Command outcomes ----
// The server accepting a command is not the TV doing it: only the TV's own receipt counts as done.

let tracking = null;
let outcomeTimer = null;

function stopTracking() {
  tracking?.tracker.cancel();
  tracking = null;
  clearTimeout(outcomeTimer);
  document.getElementById("outcome")?.remove();
}

/** The one line above the tabs saying what the TV did with the last command, naming the TV it went to. */
function showOutcome(attempt, update) {
  let node = document.getElementById("outcome");
  if (!node) document.body.append(node = h("div", { id: "outcome", class: "outcome", role: "status", "aria-live": "polite" }));
  clearTimeout(outcomeTimer);
  const tv = attempt.tvLabel;
  const command = attempt.command;
  node.dataset.outcome = update.outcome;
  const dismiss = () => { if (tracking?.attempt === attempt) tracking = null; node.remove(); };
  if (update.outcome === OUTCOME.sent) {
    node.replaceChildren(h("span", { class: "grow" }, `Sent to ${tv}, waiting for it to confirm…`));
    return;
  }
  if (update.outcome === OUTCOME.confirmed) {
    node.replaceChildren(h("span", { class: "grow" }, `${tv}: ${attempt.label || "Done"}`));
    outcomeTimer = setTimeout(dismiss, 2600);
    attempt.onConfirmed?.();
    return;
  }
  if (update.error && signedOut(update.error)) { node.remove(); return; }
  const text = update.outcome === OUTCOME.rejected
    ? (update.reason === "unknown_target_device" ? `${tv} isn't paired any more.` : `${tv} rejected the ${command} command.`)
    : `Couldn't confirm that ${tv} did it. It may be off or offline.`;
  node.replaceChildren(
    h("span", { class: "grow" }, text),
    h("button", {
      class: "small", "aria-label": `Retry ${command} on ${tv}`,
      // Retrying is always the person's choice. After a lost response the same id keeps the TV from running it twice.
      onclick: () => send(attempt.build, attempt.label, attempt.onConfirmed, { tvId: attempt.tvId, tvLabel: tv, id: update.reason === "network" ? tracking?.tracker.id : undefined }),
    }, "Retry"),
    h("button", { class: "small", "aria-label": "Dismiss", onclick: dismiss }, "Dismiss"),
  );
}

/**
 * Sends to the chosen TV and follows the command until the TV answers. `label` is what to say once the TV confirms
 * ("Paused"); `onConfirmed` runs only then. Resolves true once the server has accepted the command.
 */
async function send(build, label, onConfirmed, { tvId = state.tv, tvLabel = tvName(), id } = {}) {
  if (!tvId) { toast("Pair a TV first."); return false; }
  stopTracking();
  const body = build(tvId);
  const attempt = { build, label, onConfirmed, tvId, tvLabel, command: body.command };
  const tracker = trackCommand(api, body, (update) => showOutcome(attempt, update), { id });
  tracking = { tracker, attempt };
  return tracker.accepted;
}

// ---- Shell ----

function go(tab) {
  state.tab = tab;
  history.replaceState(null, "", `#${tab}`);
  render();
  window.scrollTo(0, 0);
}

function render() {
  if (!api.signedIn) return;
  const views = { library: libraryView, remote: remoteView, tvs: tvsView };
  mount(
    h("header", { class: "top" }, h("h1", {}, TABS[state.tab]), tvPill()),
    h("div", { id: "banners" }, banners()),
    h("section", { class: `view view-${state.tab}` }, views[state.tab]()),
    h("nav", { class: "tabs", "aria-label": "Sections" },
      Object.entries(TABS).map(([tab, label]) => h("button", {
        class: tab === state.tab ? "on" : "", "aria-current": tab === state.tab ? "page" : null, onclick: () => go(tab),
      }, icon(tab === "tvs" ? "tv" : tab), h("span", {}, label))),
    ),
  );
}

/** Which TV the buttons control, always in view. More than one TV: tap to switch. */
function tvPill() {
  if (!state.tvs.length) return h("button", { class: "pill warn", onclick: () => go("tvs") }, "Pair a TV");
  return h("button", {
    class: "pill", "aria-label": `Controlling ${tvName()}. Change TV`,
    onclick: () => (state.tvs.length > 1 ? openTvPicker() : go("tvs")),
  }, icon("tv"), h("span", {}, tvName()), state.tvs.length > 1 && icon("chevron"));
}

function openTvPicker() {
  openSheet(
    h("h3", { class: "sheet-title" }, "Control which TV?"),
    tvList(() => { closeSheet(); render(); }),
    h("button", { class: "wide", onclick: () => { closeSheet(); go("tvs"); } }, "Add or manage TVs"),
  );
}

// ---- Sheet ----

function openSheet(...children) {
  closeSheet();
  const sheet = h("div", { class: "sheet", role: "dialog", "aria-modal": "true" },
    h("button", { class: "close", "aria-label": "Close", onclick: closeSheet }, icon("close")),
    ...children,
  );
  const scrim = h("div", { id: "sheet", class: "scrim", onclick: (e) => { if (e.target === scrim) closeSheet(); } }, sheet);
  document.body.append(scrim);
  document.body.classList.add("locked");
}

function closeSheet() {
  document.getElementById("sheet")?.remove();
  document.body.classList.remove("locked");
}

// ---- Library ----

/** Season and episode from the catalog, or from the file name ("Show.S01E02.1080p") when it has none yet. */
function episodeOf(item) {
  if (item.season_number != null || item.episode_number != null) return { season: item.season_number, episode: item.episode_number };
  const m = /S(\d{1,2})[ ._-]?E(\d{1,3})/i.exec(item.original_display_hint || "");
  return m ? { season: Number(m[1]), episode: Number(m[2]) } : null;
}

function episodeLabel(item) {
  const e = episodeOf(item);
  if (!e) return null;
  return [e.season != null && `S${e.season}`, e.episode != null && `E${e.episode}`].filter(Boolean).join(" · ");
}

/** Movies stay single; episodes of a series (same series_identity, or a TV title) collapse into one entry. */
function shelf() {
  const series = new Map();
  const entries = [];
  for (const item of state.library) {
    const key = item.series_identity || (item.media_type === "tv" || episodeOf(item) ? `title:${(item.title || "").toLowerCase()}` : null);
    if (!key) { entries.push({ item, title: item.title || "Untitled", poster: item.poster_url }); continue; }
    let group = series.get(key);
    if (!group) {
      group = { title: item.title || "Untitled", poster: item.poster_url, episodes: [] };
      series.set(key, group);
      entries.push(group);
    }
    group.episodes.push(item);
    group.poster ||= item.poster_url;
  }
  const order = (item) => { const e = episodeOf(item); return e ? (e.season ?? 0) * 10000 + (e.episode ?? 0) : Infinity; };
  for (const group of series.values()) group.episodes.sort((a, b) => order(a) - order(b));
  return entries;
}

function subtitle(entry) {
  if (entry.item) return entry.item.year ? String(entry.item.year) : null;
  return entry.episodes.length > 1 ? `${entry.episodes.length} episodes` : episodeLabel(entry.episodes[0]);
}

/** Posters are public (TMDB, or the API's /poster route), so a plain lazy <img> loads them. */
function art(url, title, cls = "art") {
  const blank = () => h("div", { class: `${cls} noposter`, "aria-hidden": "true" }, title.trim().charAt(0).toUpperCase() || "▶︎");
  return url
    ? h("img", { class: cls, src: url, alt: "", loading: "lazy", decoding: "async", onerror: (e) => e.target.replaceWith(blank()) })
    : blank();
}

function libraryView() {
  if (!state.loaded) return h("p", { class: "empty" }, "Loading your library…");
  if (state.libraryError && !state.library.length) {
    return h("div", { class: "empty" }, h("p", {}, state.libraryError), h("button", { onclick: () => { state.loaded = false; render(); refreshLibrary(); } }, "Try again"));
  }
  if (!state.library.length) return h("div", { class: "empty" }, h("p", {}, "Your library is empty. Videos you add with Cablegram show up here."));
  const grid = h("div", { class: "grid" });
  const fill = () => {
    const q = state.query.trim().toLowerCase();
    const entries = shelf().filter((e) => !q || e.title.toLowerCase().includes(q));
    grid.replaceChildren(...(entries.length ? entries.map(tile) : [h("p", { class: "empty wide-cell" }, "Nothing matches.")]));
  };
  fill();
  return [
    h("label", { class: "search" }, icon("search"),
      h("input", { type: "search", placeholder: "Search your library", value: state.query, "aria-label": "Search your library", oninput: (e) => { state.query = e.target.value; fill(); } })),
    grid,
  ];
}

function tile(entry) {
  const sub = subtitle(entry);
  return h("button", { class: "tile", onclick: () => openDetails(entry) },
    art(entry.poster, entry.title),
    h("span", { class: "title" }, entry.title),
    sub && h("span", { class: "sub" }, sub),
  );
}

function openDetails(entry) {
  const first = entry.item || entry.episodes[0];
  const meta = [
    first.year,
    entry.item && first.duration_seconds && `${Math.max(1, Math.round(first.duration_seconds / 60))} min`,
    entry.episodes && `${entry.episodes.length} episode${entry.episodes.length === 1 ? "" : "s"}`,
  ].filter(Boolean).join(" · ");
  openSheet(
    h("div", { class: "detail" },
      art(entry.poster, entry.title, "thumb"),
      h("div", {}, h("h3", { class: "sheet-title" }, entry.title), meta && h("p", { class: "meta" }, meta)),
    ),
    first.overview && h("p", { class: "overview" }, first.overview),
    entry.item
      ? h("button", { class: "primary wide", onclick: () => playItem(entry.item, entry.title) }, icon("play"), h("span", {}, `Play on ${tvName()}`))
      : h("ul", { class: "episodes" }, entry.episodes.map((ep) => {
          const label = episodeLabel(ep);
          return h("li", {}, h("button", { class: "episode", onclick: () => playItem(ep, [entry.title, label].filter(Boolean).join(" ")) },
            h("span", { class: "ep-num" }, label || "—"),
            h("span", { class: "ep-title" }, ep.episode_title || (label ? `Episode ${episodeOf(ep).episode ?? ""}`.trim() : ep.original_display_hint || entry.title)),
            icon("play"),
          ));
        })),
    !state.tv && h("p", { class: "hint center" }, "Pair a TV to play this."),
  );
}

async function playItem(item, title) {
  if (!state.tv) { closeSheet(); toast("Pair a TV first."); return go("tvs"); }
  if (await send((id) => REMOTE.play(id, item.id), `Playing “${title}”`)) {
    closeSheet();
    go("remote");
  }
}

// ---- Remote ----

function remoteView() {
  if (!state.tv) {
    return h("div", { class: "empty" }, icon("tv"),
      h("p", {}, "No TV paired yet. Pair one to use the remote."),
      h("button", { class: "primary", onclick: () => go("tvs") }, "Pair a TV"));
  }
  const key = (content, build, label, cls = "", status) =>
    h("button", { class: `key ${cls}`, "aria-label": label, onclick: () => send(build, status) }, content);
  const mute = h("button", {
    class: "key mute", "aria-label": state.muted ? "Unmute" : "Mute", "aria-pressed": String(state.muted),
    onclick: () => {
      const next = !state.muted;
      // The button follows the TV, not the tap: it changes only when the TV confirms.
      send((id) => REMOTE.mute(id, next), next ? "Muted" : "Unmuted", () => {
        state.muted = next;
        mute.replaceChildren(icon(state.muted ? "muted" : "volume"));
        mute.setAttribute("aria-label", state.muted ? "Unmute" : "Mute");
        mute.setAttribute("aria-pressed", String(state.muted));
      });
    },
  }, icon(state.muted ? "muted" : "volume"));
  const volume = h("input", {
    type: "range", min: "0", max: "100", value: String(state.volume), "aria-label": "Volume",
    onchange: (e) => { const level = Number(e.target.value); send((id) => REMOTE.volume(id, level), `Volume ${level}`, () => { state.volume = level; }); },
  });
  return h("div", { class: "remote" },
    h("div", { class: "dpad" },
      key(icon("up"), (id) => REMOTE.move(id, "up"), "Up", "up"),
      key(icon("left"), (id) => REMOTE.move(id, "left"), "Left", "left"),
      key("OK", (id) => REMOTE.select(id), "OK", "ok"),
      key(icon("right"), (id) => REMOTE.move(id, "right"), "Right", "right"),
      key(icon("down"), (id) => REMOTE.move(id, "down"), "Down", "down"),
    ),
    h("div", { class: "keys transport" },
      key(icon("prev"), (id) => REMOTE.previous(id), "Previous", "", "Previous"),
      key(icon("play"), (id) => REMOTE.play(id), "Play", "primary", "Playing"),
      key(icon("pause"), (id) => REMOTE.pause(id), "Pause", "", "Paused"),
      key(icon("stop"), (id) => REMOTE.stop(id), "Stop", "", "Stopped"),
      key(icon("next"), (id) => REMOTE.next(id), "Next", "", "Next"),
    ),
    h("div", { class: "keys seek" },
      [-30, -10, 10, 30].map((s) => key(`${s < 0 ? "−" : "+"}${Math.abs(s)}s`, (id) => REMOTE.seek(id, s), `${s < 0 ? "Back" : "Forward"} ${Math.abs(s)} seconds`)),
    ),
    h("div", { class: "volume" }, mute, volume),
  );
}

// ---- TVs ----

function tvList(after) {
  return h("div", { class: "list" }, state.tvs.map((t) => h("button", {
    class: "list-row", "aria-pressed": String(t.id === state.tv),
    onclick: () => { selectTv(t.id); after(); },
  }, icon("tv"), h("span", { class: "grow" }, t.display_name || "TV"), t.id === state.tv && h("span", { class: "check" }, icon("check")))));
}

function tvsView() {
  return [
    state.tvs.length ? h("h2", {}, "Your TVs") : null,
    state.tvs.length ? tvList(() => { render(); toast(`Controlling ${tvName()}`); }) : null,
    state.tvs.length ? h("div", { class: "list" }, state.tvs.map((t) => h("div", { class: "list-row" },
      icon("tv"), h("span", { class: "grow" }, t.display_name || "TV"),
      h("button", { class: "small", "aria-label": `Rename ${t.display_name || "TV"}`, onclick: () => renameTv(t) }, "Rename")))) : null,
    h("h2", {}, state.tvs.length ? "Add a TV" : "Pair your TV"),
    h("p", {}, "Open Cablegram on your TV. It shows a 6-digit code: enter it here."),
    pairForm(),
    h("div", { id: "telegram" }, telegramHelp()),
    h("h2", {}, "Account"),
    h("div", { class: "list" },
      h("div", { class: "list-row" },
        h("span", { class: "grow" }, api.householdName || "Cablegram"),
        h("button", { class: "small", onclick: async () => { await api.logout(); renderSignIn(); } }, "Sign out")),
      api.baseUrl !== api.defaultBaseUrl && h("div", { class: "list-row" },
        h("span", { class: "grow" }, `Server: ${api.baseUrl}`),
        h("button", { class: "small", onclick: () => selectServer(null) }, "Return to Cablegram")),
      state.lock != null && h("label", { class: "list-row" },
        h("span", { class: "grow" }, "Lock account on TVs"),
        h("input", { type: "checkbox", checked: state.lock, onchange: (e) => setLock(e.target.checked) }))),
    state.lock != null && h("p", { class: "hint" }, state.lock
      ? "A TV asks you before it opens any profile."
      : "A TV opens profiles without asking, unless other accounts use it too."),
    // CAB-67: a TV shared with other accounts always asks; this is how long an allowed profile stays open unused.
    state.idle != null && h("label", { class: "idle" },
      h("span", {}, "On a TV shared with other accounts, ask again after"),
      h("select", { "aria-label": "Ask again after", onchange: (e) => setIdle(Number(e.target.value)) },
        [[30, "30 minutes"], [60, "1 hour"], [240, "4 hours"], [720, "12 hours"]].map(([value, label]) =>
          h("option", { value, selected: state.idle === value }, label)))),
    state.idle != null && h("p", { class: "hint" }, "without use. The TV then goes back to Who's watching, and you have to allow it again."),
    installHint(),
  ];
}

function pairForm() {
  const pin = h("input", { inputmode: "numeric", pattern: "[0-9]{6}", maxlength: "6", placeholder: "6-digit code", autocomplete: "one-time-code", "aria-label": "6-digit code on your TV" });
  return h("form", {
    class: "pair",
    onsubmit: async (event) => {
      event.preventDefault();
      try {
        await api.claimTv(pin.value.trim());
        pin.value = "";
        toast("TV paired.");
        await start();
      } catch (e) {
        if (!signedOut(e)) toast(message(e));
      }
    },
  }, pin, h("button", { class: "primary", type: "submit" }, "Pair"));
}

/** CAB-62: a TV scanned before signing in. Nothing is paired until the person taps Connect. */
function offerPendingPair() {
  const link = pendingPair(tabStore);
  if (!link) return;
  const errorLine = h("p", { class: "error", role: "alert" });
  const connect = h("button", {
    class: "primary wide",
    onclick: async () => {
      connect.disabled = true;
      try {
        await api.claimTv(link.pin, link.tv === "your TV" ? undefined : link.tv);
        clearPendingPair(tabStore);
        closeSheet();
        toast(`${link.tv} is connected.`);
        await start();
      } catch (e) {
        if (signedOut(e)) return;
        if (e instanceof ApiError && (e.code === "pin_expired" || e.code === "invalid_pin")) clearPendingPair(tabStore);
        errorLine.textContent = message(e);
        connect.disabled = e instanceof ApiError && (e.code === "pin_expired" || e.code === "invalid_pin");
      }
    },
  }, "Connect");
  openSheet(
    h("h3", { class: "sheet-title" }, `Connect ${link.tv}?`),
    h("p", {}, `${link.tv} will show your library, and this phone can control it. Only connect a TV you can see in front of you.`),
    connect,
    errorLine,
    h("button", { class: "wide", onclick: () => { clearPendingPair(tabStore); closeSheet(); } }, "Not now"),
  );
}

// ---- TV requests ----

function banners() {
  return [...joinRequestBanners(), ...profileBanners(), ...telegramBanners()];
}

function joinRequestBanners() {
  return state.joinRequests.map((request) => {
    const answer = async (allow) => {
      state.joinRequests = state.joinRequests.filter((r) => r.id !== request.id);
      document.getElementById("banners")?.replaceChildren(...banners());
      try {
        await api.answerJoinRequest(request.id, allow);
      } catch (e) {
        if (!signedOut(e)) toast(e instanceof ApiError && e.status === 409 ? "That request already ended." : message(e));
      }
    };
    return h("div", { class: "banner", role: "alert" },
      h("p", {}, `${request.device_name || "A new device"} wants to join this server`),
      h("p", { class: "hint" }, `Confirm that the code on the other device is ${request.short_code}.`),
      h("div", { class: "row" },
        h("button", { class: "primary", onclick: () => answer(true) }, "Allow"),
        h("button", { onclick: () => answer(false) }, "Don't allow"),
      ),
    );
  });
}

async function setIdle(minutes) {
  try {
    state.idle = (await api.setSharedTvIdleMinutes(minutes)) ?? state.idle;
    toast("Saved.");
  } catch (e) {
    if (signedOut(e)) return;
    toast(message(e));
  }
  render();
}

async function renameTv(tv) {
  const name = (globalThis.prompt("TV name", tv.display_name || "") || "").trim();
  if (!name || name === tv.display_name) return;
  try {
    await api.renameTv(tv.id, name);
    state.tvs = state.tvs.map((t) => (t.id === tv.id ? { ...t, display_name: name.slice(0, 80) } : t));
    toast("Renamed.");
  } catch (e) {
    if (signedOut(e)) return;
    toast(message(e));
  }
  render();
}

async function setLock(on) {
  try {
    state.lock = await api.setProfileLock(on);
  } catch (e) {
    if (signedOut(e)) return;
    toast(message(e));
  }
  render();
}

/** Profile lock: "<TV> wants to open <profile>". The TV waits until someone answers here or on a phone. */
function profileBanners() {
  return state.profileRequests.map((request) => {
    const answer = async (allow) => {
      state.profileRequests = state.profileRequests.filter((r) => r.request_id !== request.request_id);
      document.getElementById("banners")?.replaceChildren(...banners());
      try {
        await api.answerProfileRequest(request.request_id, allow);
      } catch (e) {
        if (!signedOut(e)) toast(e instanceof ApiError && e.status === 409 ? "That request already ended." : message(e));
      }
    };
    return h("div", { class: "banner", role: "alert" },
      h("p", {}, `${request.tv_name || "A TV"} wants to open ${request.profile_name || "a profile"}`),
      h("p", { class: "hint" }, request.shared_tv
        ? "Other accounts use this TV too. Only allow this if you are at that TV."
        : "Only allow this if you are at that TV."),
      h("div", { class: "row" },
        h("button", { class: "primary", onclick: () => answer(true) }, "Allow"),
        h("button", { onclick: () => answer(false) }, "Don't allow"),
      ),
    );
  });
}

// ---- Telegram ----

/** "Let <TV> use your Telegram?": nothing is approved until the user taps Allow (as on the Android phone). */
function telegramBanners() {
  return state.pending.map((request) => {
    const answered = () => {
      state.pending = state.pending.filter((r) => r.request_id !== request.request_id);
      document.getElementById("banners")?.replaceChildren(...banners());
    };
    return h("div", { class: "banner", role: "alert" },
      h("p", {}, `Let ${request.tv_name || "this TV"} use your Telegram?`),
      h("p", { class: "hint" }, "Telegram will ask you to confirm. Only allow this if you started it on your TV."),
      h("div", { class: "row" },
        // A real link, so iOS opens Telegram from the tap itself. The answer is posted alongside.
        h("a", {
          class: "button primary", href: request.login_link,
          onclick: () => { api.answerTvLogin(request.request_id, true).catch(() => {}); setTimeout(answered, 300); },
        }, "Allow"),
        h("button", { onclick: async () => { await api.answerTvLogin(request.request_id, false).catch(() => {}); answered(); } }, "Don't allow"),
      ),
    );
  });
}

function telegramHelp() {
  const link = state.link;
  if (!link) return [];
  return [
    h("h2", {}, "Telegram"),
    link.linked
      ? h("p", {}, `Connected as ${link.display_name}. Your TV plays videos from your Telegram library.`)
      : h("p", {}, "Telegram isn't connected yet. On your TV, open Settings and choose “Connect Telegram on this TV”, then scan the code with the Telegram app on this phone."),
  ];
}

// ---- Boot ----

if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeSheet(); });
addEventListener("pagehide", stopTracking);
document.addEventListener("visibilitychange", () => {
  if (document.hidden || !api.signedIn) return;
  refreshTelegram();
  refreshLibrary();
});
// CAB-62: a TV's pairing link (/?pair=…&tv=…) is kept for after sign-in, and leaves the address bar at once.
const withoutPairLink = capturePairLink(location, tabStore);
if (withoutPairLink) history.replaceState(null, "", withoutPairLink);
if (api.signedIn) start(); else renderSignIn();
