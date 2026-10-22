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
const SERVER_KEY = "cablegram.server";

function isPrivateOrLocalHost(hostname) {
  const host = hostname.toLowerCase();
  if (host === "localhost" || host === "127.0.0.1" || host === "[::1]" || host.endsWith(".local")) return true;
  const ipv4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!ipv4) return false;
  const [a, b] = [Number(ipv4[1]), Number(ipv4[2])];
  return a === 10 || a === 127 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 169 && b === 254);
}

/** Validates a custom server URL (HTTPS or private LAN HTTP). */
export function validateServerUrl(raw) {
  let parsed;
  try {
    parsed = new URL(String(raw || "").trim());
  } catch {
    return { ok: false, error: "Enter a complete server URL (for example https://tv.example.com)." };
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    return { ok: false, error: "Server URL must start with https:// or http://." };
  }
  const insecureLan = parsed.protocol === "http:";
  if (insecureLan && !isPrivateOrLocalHost(parsed.hostname)) {
    return { ok: false, error: "HTTP is only allowed for local network (LAN) addresses. Use HTTPS for remote servers." };
  }
  const path = parsed.pathname === "/" ? "" : parsed.pathname.replace(/\/+$/, "");
  return { ok: true, url: `${parsed.origin}${path}`, insecureLan };
}

/**
 * @param {{ base: string, storage?: Storage, fetch?: typeof fetch }} options
 * Tokens live in the browser's own storage on this device. The access token lasts 2 hours and the
 * refresh token rotates on every use, so a refresh is retried at most once per request.
 */
export function createApi({ base, storage = globalThis.localStorage, fetch: fetchImpl = globalThis.fetch.bind(globalThis) }) {
  const defaultRoot = base.replace(/\/+$/, "");
  const readServer = () => {
    try {
      const saved = storage.getItem(SERVER_KEY);
      if (!saved) return defaultRoot;
      const check = validateServerUrl(saved);
      return check.ok ? check.url : defaultRoot;
    } catch { return defaultRoot; }
  };
  let root = readServer();
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

  /** Keeps the tokens when a sign-in step returned them; a new person gets `{ status: "signup_required", … }` instead. */
  function keep(result) {
    if (result?.access_token) {
      write({ access_token: result.access_token, refresh_token: result.refresh_token, household_name: result.display_name || "" });
    }
    return result;
  }

  const post = (path, body) => send(path, { method: "POST", auth: false, body: { ...body, client: "web" } });

  return {
    get signedIn() { return !!memory?.refresh_token; },
    get householdName() { return memory?.household_name || ""; },
    get baseUrl() { return root; },
    get defaultBaseUrl() { return defaultRoot; },
    setBaseUrl(nextBase) {
      const next = nextBase ? validateServerUrl(nextBase) : { ok: true, url: defaultRoot };
      if (!next.ok) throw new Error(next.error);
      const changed = next.url !== root;
      root = next.url;
      try {
        if (root === defaultRoot) storage.removeItem(SERVER_KEY);
        else storage.setItem(SERVER_KEY, root);
      } catch { /* private mode */ }
      if (changed) write(null);
      return root;
    },
    /** Server capabilities (`GET /api/server`); falls back for servers older than `api_version: 1`. */
    async server() {
      try {
        return await send("/api/server", { auth: false });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          return {
            api_version: 1,
            edition: "hosted",
            auth: { mode: "account", email_code: true },
            features: { telegram: true, lan_playback: true, profile_lock: true },
            legal: null,
          };
        }
        throw e;
      }
    },
    /** `{ google: { client_id } | null, email_code: true }`: the server offers Google only when it has a client. */
    methods: () => send("/api/auth/methods", { auth: false }),
    /** The current Terms and privacy policy: `{ terms: { version, url }, privacy: { version, url } }`. */
    legal: () => send("/api/legal", { auth: false }),
    /** Spec 007: the server answers the same whether or not the email has an account. */
    requestCode: (email) => send("/api/auth/email-code/request", { method: "POST", auth: false, body: { email } }),
    verifyCode: async (email, code) => keep(await post("/api/auth/email-code/verify", { email, code })),
    google: async (idToken, nonce) => keep(await post("/api/auth/google", { id_token: idToken, nonce })),
    /** Device sign-in (`auth.mode === "device"`): claim the server's one-time setup code or request approval from an existing phone. */
    claimSetup: async (code, deviceName = "Web Remote") => keep(await post("/api/auth/setup/claim", { code, device_name: deviceName })),
    createJoinRequest: (deviceName = "Web Remote") => post("/api/auth/join-requests", { device_name: deviceName }),
    pollJoinRequest: async (id, secret) =>
      keep(await send(`/api/auth/join-requests/${encodeURIComponent(id)}?secret=${encodeURIComponent(secret)}`, { auth: false })),
    pendingJoinRequests: async () => (await send("/api/auth/join-requests/pending")).requests || [],
    answerJoinRequest: (id, allow) =>
      send(`/api/auth/join-requests/${encodeURIComponent(id)}/${allow ? "approve" : "deny"}`, { method: "POST", body: {} }),
    /** Creates the account after the person accepted the Terms and confirmed they are 18 or older. */
    signUp: async (ticket, name, legal) => keep(await post("/api/auth/signup", {
      signup_ticket: ticket,
      name,
      accepted: { terms_version: legal.terms.version, privacy_version: legal.privacy.version },
      age_confirmed: true,
    })),
    acceptTerms: (legal) => send("/api/auth/legal/accept", {
      method: "POST", body: { terms_version: legal.terms.version, privacy_version: legal.privacy.version },
    }),
    async logout() {
      const token = memory?.refresh_token;
      write(null);
      if (token) await send("/api/auth/logout", { method: "POST", auth: false, body: { refresh_token: token } }).catch(() => {});
    },
    /** The account: its TVs that haven't been revoked, and whether new Terms must be accepted first. */
    async account() {
      const me = await send("/api/me");
      return {
        tvs: (me.devices || []).filter((d) => d.kind === "tv" && !d.revoked_at),
        termsRequired: me.legal?.acceptance_required === true,
        profileLock: me.household?.profile_lock === true,
        /** CAB-67: minutes an allowed profile stays open without use on a TV shared with other accounts. */
        sharedTvIdleMinutes: me.household?.shared_tv_idle_minutes ?? 240,
      };
    },
    /** Renames one of the account's TVs (the owner's own client only; a TV cannot rename itself). */
    renameTv: (id, name) => send(`/api/devices/${encodeURIComponent(id)}`, { method: "PATCH", body: { display_name: String(name).trim().slice(0, 80) } }),
    claimTv: (pin, name) => send("/api/auth/device/claim", { method: "POST", body: { pin, ...(name ? { tv_display_name: name } : {}) } }),
    library: async () => (await send("/api/catalog/items")).items || [],
    command: (body) => send("/api/control/commands", { method: "POST", body }),
    /** `{ status: pending | delivered | completed | rejected, reason, expires_in_ms, target_device_id }`. */
    commandStatus: (id) => send(`/api/control/commands/${encodeURIComponent(id)}`),
    telegramLink: () => send("/api/telegram/link"),
    pendingTvLogins: async () => (await send("/api/telegram/tv-logins/pending")).requests || [],
    /** The web client may only answer "approved" or "denied"; the server refuses "failed". */
    answerTvLogin: (id, allow) => send(`/api/telegram/tv-logins/${id}/result`, { method: "POST", body: { outcome: allow ? "approved" : "denied" } }),
    /** Profile lock: TVs waiting for a phone to allow a profile. */
    pendingProfileRequests: async () => (await send("/api/profiles/switch-requests/pending")).items || [],
    answerProfileRequest: (id, allow) => send(`/api/profiles/switch-requests/${encodeURIComponent(id)}/${allow ? "approve" : "deny"}`, { method: "POST", body: {} }),
    /** Whether TVs must ask before opening a profile. */
    profileLock: async () => (await send("/api/me")).household?.profile_lock === true,
    setProfileLock: async (on) => (await send("/api/household", { method: "PATCH", body: { profile_lock: on } })).household?.profile_lock === true,
    setSharedTvIdleMinutes: async (minutes) =>
      (await send("/api/household", { method: "PATCH", body: { shared_tv_idle_minutes: minutes } })).household?.shared_tv_idle_minutes,
  };
}
