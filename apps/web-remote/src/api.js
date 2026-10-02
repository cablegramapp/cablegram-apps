// Cablegram web remote: the control-plane client. It signs in as `client: "web"`, so the server treats
// this app as a remote and never as a Telegram client (contracts/telegram-link.md, "Web (iPhone PWA) households").

export class ApiError extends Error {
  constructor(status, code) {
    super(code || `http_${status}`);
    this.status = status;
    this.code = code || null;
  }
}

/** The command body the control plane takes (same shape as the Android phone's `remoteCommandBody`). */
export function commandBody(command, targetDeviceId, payload = {}) {
  if (!targetDeviceId) throw new Error("A remote command must target a paired TV");
  return { command, target_device_id: targetDeviceId, payload };
}

/** Only the TV commands the TV app accepts (apps/android-tv CommandValidation.kt). */
export const REMOTE = {
  play: (tv, videoId) => commandBody("play", tv, videoId ? { videoId } : {}),
  pause: (tv) => commandBody("pause", tv),
  stop: (tv) => commandBody("stop", tv),
  next: (tv) => commandBody("next", tv),
  previous: (tv) => commandBody("previous", tv),
  select: (tv) => commandBody("select", tv),
  move: (tv, direction) => {
    if (!["up", "down", "left", "right"].includes(direction)) throw new Error("bad direction");
    return commandBody("move", tv, { direction });
  },
  seek: (tv, seconds) => commandBody("seek", tv, { seconds: Math.max(-86400, Math.min(86400, Math.round(seconds))) }),
  volume: (tv, level) => commandBody("volume", tv, { level: Math.max(0, Math.min(100, Math.round(level))) }),
  mute: (tv, muted) => commandBody("mute", tv, { muted: !!muted }),
};

const SESSION_KEY = "cablegram.session";

/**
 * @param {{ base: string, storage?: Storage, fetch?: typeof fetch }} options
 * Tokens live in the browser's own storage on this device. The access token lasts 2 hours and the
 * refresh token rotates on every use, so a refresh is retried at most once per request.
 */
export function createApi({ base, storage = globalThis.localStorage, fetch: fetchImpl = globalThis.fetch.bind(globalThis) }) {
  const root = base.replace(/\/+$/, "");
  let refreshing = null;

  const read = () => {
    try { return JSON.parse(storage.getItem(SESSION_KEY) || "null"); } catch { return null; }
  };
  const write = (session) => {
    try {
      if (session) storage.setItem(SESSION_KEY, JSON.stringify(session));
      else storage.removeItem(SESSION_KEY);
    } catch { /* private mode: the session lasts until the page closes */ }
    memory = session;
  };
  let memory = read();

  async function parse(response) {
    if (response.status === 204) return null;
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    if (!response.ok) throw new ApiError(response.status, body?.error || body?.message);
    return body;
  }

  async function send(path, { method = "GET", body, auth = true } = {}, retried = false) {
    const headers = {};
    if (body !== undefined) headers["content-type"] = "application/json";
    if (auth && memory?.access_token) headers.authorization = `Bearer ${memory.access_token}`;
    const response = await fetchImpl(`${root}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    if (response.status === 401 && auth && !retried && memory?.refresh_token) {
      if (await refresh()) return send(path, { method, body, auth }, true);
    }
    return parse(response);
  }

  /** One refresh at a time: the server rotates the token, so two in flight would burn it. */
  function refresh() {
    refreshing ??= (async () => {
      try {
        const response = await fetchImpl(`${root}/api/auth/refresh`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ refresh_token: memory.refresh_token }),
        });
        if (!response.ok) { write(null); return false; }
        const tokens = await response.json();
        write({ ...memory, access_token: tokens.access_token, refresh_token: tokens.refresh_token });
        return true;
      } catch {
        return false; // offline: keep the session and let the caller report the network error
      } finally { refreshing = null; }
    })();
    return refreshing;
  }

  async function authenticate(path, credentials) {
    const result = await send(path, { method: "POST", auth: false, body: { ...credentials, client: "web" } });
    write({ access_token: result.access_token, refresh_token: result.refresh_token, household_name: result.display_name || "" });
    return result;
  }

  return {
    get signedIn() { return !!memory?.refresh_token; },
    get householdName() { return memory?.household_name || ""; },
    login: (email, password) => authenticate("/api/auth/login", { email, password }),
    register: (email, password) => authenticate("/api/auth/register", { email, password }),
    async logout() {
      const token = memory?.refresh_token;
      write(null);
      if (token) await send("/api/auth/logout", { method: "POST", auth: false, body: { refresh_token: token } }).catch(() => {});
    },
    /** TVs of the household that haven't been revoked. */
    async tvs() {
      const me = await send("/api/me");
      return (me.devices || []).filter((d) => d.kind === "tv" && !d.revoked_at);
    },
    claimTv: (pin, name) => send("/api/auth/device/claim", { method: "POST", body: { pin, ...(name ? { tv_display_name: name } : {}) } }),
    library: async () => (await send("/api/catalog/items")).items || [],
    command: (body) => send("/api/control/commands", { method: "POST", body }),
    telegramLink: () => send("/api/telegram/link"),
    pendingTvLogins: async () => (await send("/api/telegram/tv-logins/pending")).requests || [],
    /** The web client may only answer "approved" or "denied"; the server refuses "failed". */
    answerTvLogin: (id, allow) => send(`/api/telegram/tv-logins/${id}/result`, { method: "POST", body: { outcome: allow ? "approved" : "denied" } }),
  };
}
