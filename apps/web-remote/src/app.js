import { API_BASE } from "../config.js";
import { createApi, REMOTE, ApiError } from "./api.js";

const api = createApi({ base: API_BASE });
const root = document.getElementById("app");
const TV_KEY = "cablegram.tv";
const TABS = { library: "Library", remote: "Remote", tvs: "TVs" };

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
  tvs: [], tv: remembered(), library: [], loaded: false, libraryError: "", link: null, pending: [],
  tab: TABS[location.hash.slice(1)] ? location.hash.slice(1) : "library", query: "", muted: false, volume: 50,
};
let pollTimer = null;

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
    case "unknown_target_device": return "That TV isn't paired any more.";
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

// ---- Sign in ----

function renderSignIn(error = "", registering = false) {
  stopPolling();
  closeSheet();
  const email = h("input", { type: "email", autocomplete: "email", placeholder: "Email", required: true });
  const password = h("input", { type: "password", autocomplete: registering ? "new-password" : "current-password", placeholder: "Password", required: true, minlength: registering ? 8 : null });
  const submit = h("button", { class: "primary wide", type: "submit" }, registering ? "Create account" : "Sign in");
  const form = h("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      submit.disabled = true;
      try {
        await (registering ? api.register : api.login)(email.value.trim(), password.value);
        await start();
      } catch (e) { renderSignIn(message(e), registering); }
    },
  },
    email, password,
    error && h("p", { class: "error", role: "alert" }, error),
    submit,
  );
  mount(h("div", { class: "signin" },
    h("img", { class: "logo", src: "icons/icon-192.png", alt: "" }),
    h("h1", {}, "Cablegram Remote"),
    h("p", {}, "Choose videos and control your TV from this phone."),
    form,
    h("p", { class: "center" }, registering ? "Have an account? " : "New here? ",
      h("a", { href: "#", onclick: (e) => { e.preventDefault(); renderSignIn("", !registering); } }, registering ? "Sign in" : "Create an account")),
    installHint(),
  ));
}

function installHint() {
  const iOS = /iphone|ipad/i.test(navigator.userAgent);
  const installed = navigator.standalone || matchMedia("(display-mode: standalone)").matches;
  if (!iOS || installed) return null;
  return h("p", { class: "hint" }, "Tip: tap the Share button in Safari, then “Add to Home Screen” to keep this remote on your home screen.");
}

// ---- Data ----

async function start() {
  try {
    state.tvs = await api.tvs();
  } catch (e) {
    if (signedOut(e)) return;
    toast(message(e));
  }
  if (!state.tvs.some((tv) => tv.id === state.tv)) state.tv = state.tvs[0]?.id ?? null;
  render();
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

function tvName() {
  return state.tvs.find((t) => t.id === state.tv)?.display_name || "your TV";
}

function selectTv(id) {
  state.tv = id;
  try { localStorage.setItem(TV_KEY, id); } catch { /* fine */ }
}

/** Sends to the chosen TV; `label` is the confirmation to show ("Paused"), or nothing for arrow keys. */
async function send(build, label) {
  if (!state.tv) { toast("Pair a TV first."); return false; }
  try {
    await api.command(build(state.tv));
    if (label) toast(label);
    return true;
  } catch (e) {
    if (!signedOut(e)) toast(message(e));
    return false;
  }
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
    h("div", { id: "banners" }, telegramBanners()),
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
  if (await send((id) => REMOTE.play(id, item.id), `Playing “${title}” on ${tvName()}`)) {
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
    onclick: async () => {
      if (!(await send((id) => REMOTE.mute(id, !state.muted), state.muted ? "Unmuted" : "Muted"))) return;
      state.muted = !state.muted;
      mute.replaceChildren(icon(state.muted ? "muted" : "volume"));
      mute.setAttribute("aria-label", state.muted ? "Unmute" : "Mute");
      mute.setAttribute("aria-pressed", String(state.muted));
    },
  }, icon(state.muted ? "muted" : "volume"));
  const volume = h("input", {
    type: "range", min: "0", max: "100", value: String(state.volume), "aria-label": "Volume",
    onchange: (e) => { state.volume = Number(e.target.value); send((id) => REMOTE.volume(id, state.volume), `Volume ${state.volume}`); },
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
    h("h2", {}, state.tvs.length ? "Add a TV" : "Pair your TV"),
    h("p", {}, "Open Cablegram on your TV. It shows a 6-digit code: enter it here."),
    pairForm(),
    h("div", { id: "telegram" }, telegramHelp()),
    h("h2", {}, "Account"),
    h("div", { class: "list" }, h("div", { class: "list-row" },
      h("span", { class: "grow" }, api.householdName || "Cablegram"),
      h("button", { class: "small", onclick: async () => { await api.logout(); renderSignIn(); } }, "Sign out"))),
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
        if (!signedOut(e)) toast(e instanceof ApiError && e.status === 404 ? "That code didn't match. Check the TV and try again." : message(e));
      }
    },
  }, pin, h("button", { class: "primary", type: "submit" }, "Pair"));
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
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeSheet(); });
document.addEventListener("visibilitychange", () => {
  if (document.hidden || !api.signedIn) return;
  refreshTelegram();
  refreshLibrary();
});
if (api.signedIn) start(); else renderSignIn();
