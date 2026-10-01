import { API_BASE } from "../config.js";
import { createApi, REMOTE, ApiError } from "./api.js";

const api = createApi({ base: API_BASE });
const root = document.getElementById("app");
const TV_KEY = "cablegram.tv";

/** Tiny DOM helper. Text always goes in as text, never as HTML: the server supplies titles and TV names. */
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === false || value == null) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) if (child != null) node.append(child);
  return node;
}

/** Replaces the page; a null child (an optional piece) is skipped, where replaceChildren would print "null". */
function mount(...nodes) {
  root.replaceChildren(...nodes.flat().filter(Boolean));
}

const state = { tvs: [], tv: remembered(), library: [], link: null, pending: [], status: "" };
let pollTimer = null;
const posterCache = new Map();

function remembered() {
  try { return localStorage.getItem(TV_KEY); } catch { return null; }
}

function message(error) {
  if (!(error instanceof ApiError)) return "Can't reach Cablegram. Check your connection.";
  switch (error.code) {
    case "invalid_credentials": return "Wrong email or password.";
    case "too_many_login_attempts": return "Too many tries. Wait a few minutes.";
    case "disposable_email": return "Use a permanent email address.";
    case "forbidden_for_web_client": return "This can't be done from the web app.";
    default: return error.status === 401 ? "Please sign in again." : `Something went wrong (${error.code || error.status}).`;
  }
}

// ---- Sign in ----

function renderSignIn(error = "", registering = false) {
  stopPolling();
  const email = h("input", { type: "email", autocomplete: "email", placeholder: "Email", required: true });
  const password = h("input", { type: "password", autocomplete: registering ? "new-password" : "current-password", placeholder: "Password", required: true, minlength: registering ? 8 : null });
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      try {
        await (registering ? api.register : api.login)(email.value.trim(), password.value);
        await start();
      } catch (e) { renderSignIn(message(e), registering); }
    },
  },
    email, password,
    error && h("p", { class: "error", role: "alert" }, error),
    h("button", { class: "primary", type: "submit", style: "width:100%" }, registering ? "Create account" : "Sign in"),
  );
  mount(
    h("h1", {}, "Cablegram Remote"),
    h("p", {}, "Choose videos and control your TV from this phone."),
    form,
    h("p", {}, registering ? "Have an account? " : "New here? ",
      h("a", { href: "#", style: "color:var(--accent)", onclick: (e) => { e.preventDefault(); renderSignIn("", !registering); } }, registering ? "Sign in" : "Create an account")),
    installHint(),
  );
}

function installHint() {
  const iOS = /iphone|ipad/i.test(navigator.userAgent);
  const installed = navigator.standalone || matchMedia("(display-mode: standalone)").matches;
  if (!iOS || installed) return null;
  return h("p", { class: "hint" }, "Tip: tap the Share button in Safari, then “Add to Home Screen” to keep this remote on your home screen.");
}

// ---- Home ----

async function start() {
  try {
    state.tvs = await api.tvs();
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return renderSignIn();
    state.status = message(e);
  }
  if (!state.tvs.some((tv) => tv.id === state.tv)) state.tv = state.tvs[0]?.id ?? null;
  renderHome();
  refreshLibrary();
  refreshTelegram();
  startPolling();
}

async function refreshLibrary() {
  try { state.library = await api.library(); } catch (e) { state.status = message(e); }
  renderHome();
}

/** Updates only the Telegram parts of the page, and only when they changed: a full redraw would wipe a code being typed. */
async function refreshTelegram() {
  const before = JSON.stringify([state.link, state.pending]);
  try { state.link = await api.telegramLink(); } catch { /* the banners stay as they were */ }
  try { state.pending = await api.pendingTvLogins(); } catch { /* same */ }
  if (JSON.stringify([state.link, state.pending]) === before) return;
  document.getElementById("banners")?.replaceChildren(...telegramBanners());
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

async function send(body, label) {
  try {
    await api.command(body);
    setStatus(label || "Sent");
  } catch (e) { setStatus(message(e)); }
}

function setStatus(text) {
  state.status = text;
  const node = document.getElementById("status");
  if (node) node.textContent = text;
}

function renderHome() {
  if (!api.signedIn) return;
  const tv = state.tv;
  const target = (fn) => () => { if (tv) fn(tv); else setStatus("Pair a TV first."); };
  const cmd = (build, label) => target((id) => send(build(id), label));

  mount(
    h("div", { class: "bar" },
      h("h1", {}, api.householdName || "Cablegram"),
      h("button", { onclick: async () => { await api.logout(); renderSignIn(); } }, "Sign out"),
    ),
    h("div", { id: "banners" }, telegramBanners()),
    h("h2", {}, "TV"),
    state.tvs.length
      ? h("div", { class: "tvs" }, state.tvs.map((t) => h("button", {
          class: t.id === tv ? "on" : "",
          onclick: () => { state.tv = t.id; try { localStorage.setItem(TV_KEY, t.id); } catch { /* fine */ } renderHome(); },
        }, t.display_name || "TV")))
      : h("p", {}, "No TV yet. Open Cablegram on your TV, then enter the 6-digit code it shows."),
    pairForm(),
    h("h2", {}, "Remote"),
    remotePad(cmd),
    h("p", { id: "status", class: "status", role: "status" }, state.status),
    h("h2", {}, "Library"),
    libraryGrid(target),
    h("div", { id: "telegram" }, telegramHelp()),
  );
  lazyPosters();
}

function pairForm() {
  const pin = h("input", { inputmode: "numeric", pattern: "[0-9]{6}", maxlength: "6", placeholder: "6-digit code on your TV", autocomplete: "one-time-code" });
  return h("form", {
    class: "row",
    onsubmit: async (event) => {
      event.preventDefault();
      try {
        await api.claimTv(pin.value.trim());
        pin.value = "";
        state.status = "TV paired.";
        await start();
      } catch (e) { setStatus(e instanceof ApiError && e.status === 404 ? "That code didn't match. Check the TV and try again." : message(e)); }
    },
  }, pin, h("button", { type: "submit", style: "flex:0 0 auto" }, "Pair TV"));
}

function remotePad(cmd) {
  const key = (label, build, status) => h("button", { onclick: cmd(build, status), "aria-label": status || label }, label);
  const blank = () => h("button", { class: "blank", tabindex: "-1", "aria-hidden": "true" }, "·");
  const volume = h("input", {
    type: "range", min: "0", max: "100", value: "50", "aria-label": "Volume",
    onchange: (e) => cmd((id) => REMOTE.volume(id, Number(e.target.value)), "Volume set")(),
  });
  return h("div", {},
    h("div", { class: "row" },
      key("⏮", (id) => REMOTE.previous(id), "Previous"),
      key("▶︎", (id) => REMOTE.play(id), "Play"),
      key("⏸", (id) => REMOTE.pause(id), "Paused"),
      key("⏹", (id) => REMOTE.stop(id), "Stopped"),
      key("⏭", (id) => REMOTE.next(id), "Next"),
    ),
    h("div", { class: "row", style: "margin-top:8px" },
      key("−30s", (id) => REMOTE.seek(id, -30)), key("−10s", (id) => REMOTE.seek(id, -10)),
      key("+10s", (id) => REMOTE.seek(id, 10)), key("+30s", (id) => REMOTE.seek(id, 30)),
    ),
    h("div", { class: "pad" },
      blank(), key("▲", (id) => REMOTE.move(id, "up")), blank(),
      key("◀︎", (id) => REMOTE.move(id, "left")), key("OK", (id) => REMOTE.select(id)), key("▶︎", (id) => REMOTE.move(id, "right")),
      blank(), key("▼", (id) => REMOTE.move(id, "down")), blank(),
    ),
    h("div", { class: "row" }, volume, key("Mute", (id) => REMOTE.mute(id, true), "Muted"), key("Unmute", (id) => REMOTE.mute(id, false), "Unmuted")),
  );
}

function libraryGrid(target) {
  if (!state.library.length) return h("p", {}, "Your library is empty. Videos you add with Cablegram show up here.");
  return h("div", { class: "grid" }, state.library.map((item) => h("button", {
    class: "tile",
    onclick: target((id) => send(REMOTE.play(id, item.id), `Playing “${item.title || "video"}” on your TV`)),
  },
    item.poster_url ? h("img", { alt: "", "data-poster": item.poster_url }) : h("div", { class: "noposter" }, item.title || "Video"),
    h("span", {}, item.title || "Untitled"),
  )));
}

/** Posters need the bearer token, so each is fetched as bytes the first time it scrolls into view. */
function lazyPosters() {
  const images = [...root.querySelectorAll("img[data-poster]")];
  const load = async (img) => {
    const url = img.dataset.poster;
    if (!posterCache.has(url)) posterCache.set(url, api.poster(url).then((blob) => (blob ? URL.createObjectURL(blob) : null)).catch(() => null));
    const objectUrl = await posterCache.get(url);
    if (objectUrl) img.src = objectUrl;
  };
  if (!("IntersectionObserver" in window)) return images.forEach(load);
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) if (entry.isIntersecting) { observer.unobserve(entry.target); load(entry.target); }
  }, { rootMargin: "200px" });
  images.forEach((img) => observer.observe(img));
}

// ---- Telegram ----

/** "Let <TV> use your Telegram?": nothing is approved until the user taps Allow (as on the Android phone). */
function telegramBanners() {
  return state.pending.map((request) => {
    const answered = () => {
      state.pending = state.pending.filter((r) => r.request_id !== request.request_id);
      document.getElementById("banners")?.replaceChildren(...telegramBanners());
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
document.addEventListener("visibilitychange", () => { if (!document.hidden && api.signedIn) refreshTelegram(); });
if (api.signedIn) start(); else renderSignIn();
