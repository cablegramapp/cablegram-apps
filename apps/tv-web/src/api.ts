import { API_BASE, TG_DIRECT } from "./config";
import { parseTelegramIdentity } from "./telegram-identity";
import type { Device, DeviceSession, PairingStatus, Playback, Profile, Source, SwitchRequest, Title } from "./types";

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = Record<string, any>;

const str = (v: unknown): string | undefined => (typeof v === "string" && v ? v : undefined);
const num = (v: unknown): number | undefined => (typeof v === "number" ? v : undefined);

/** Phone-served sources (LAN/relay) carry an origin identity and are neither Telegram nor the household cloud. */
export const isPhoneSource = (s: Source) =>
  s.kind !== "telegram" && s.kind !== "own_cloud" && !!s.originIdentity;
const usable = (s: Source) => s.archiveState !== "archived" && s.availability !== "unavailable";

export function parseTitle(raw: Json): Title {
  const progress = raw.progress as Json | undefined;
  const resume = progress && progress.state !== "completed" ? num(progress.position_seconds) : undefined;
  const sources: Source[] = ((raw.sources as Json[] | undefined) ?? []).map((s) => ({
    kind: str(s.kind), originIdentity: str(s.origin_identity), isPrivate: s.private === true,
    servingDeviceId: str(s.serving_device_id), availability: str(s.availability), archiveState: str(s.archive_state),
  }));
  const isPrivate = sources.some((s) => s.isPrivate);
  const kinds: string[] = [];
  sources.filter(usable).forEach((s) => { if (s.kind && kinds.indexOf(s.kind) < 0) kinds.push(s.kind); });
  return {
    id: String(raw.id),
    title: str(raw.title),
    // A private title's artwork is never shown on the TV; it gets a branded stand-in instead.
    posterUrl: isPrivate ? undefined : str(raw.poster_url),
    backdropUrl: isPrivate ? undefined : str(raw.backdrop_url),
    overview: str(raw.overview),
    year: num(raw.year) ?? num(raw.release_year),
    durationSeconds: num(raw.duration_seconds) ?? num(progress?.duration_seconds),
    resumeSeconds: resume,
    mediaType: str(raw.media_type) ?? "movie",
    season: num(raw.season_number), episode: num(raw.episode_number), episodeTitle: str(raw.episode_title),
    inMyList: raw.in_my_list === true,
    genres: ((raw.genres as unknown[] | undefined) ?? []).filter((g): g is string => typeof g === "string"),
    addedAt: str(raw.created_at) ?? "",
    tmdbId: num(raw.tmdb_id),
    isPrivate,
    sourceKinds: kinds,
    sources,
  };
}

/** The relay URL for a phone path; `rt` authorizes this TV at the relay because `<video>` cannot send headers. */
export function relayUrl(base: string, phoneId: string, path: string, capability: string | undefined, ticket: string) {
  const url = new URL(`${base.replace(/\/+$/, "")}/relay/v1/p/${encodeURIComponent(phoneId)}/${path}`);
  if (capability) url.searchParams.set("token", capability);
  url.searchParams.set("rt", ticket);
  return url.toString();
}

/** Why a playback URL failed, in the Android TV app's words (PlayerScreen.kt relayMessage). */
export function relayMessage(status: number | null, error: string | undefined): string {
  if (status == null) return "Can't reach Cablegram from this TV. Check the TV's internet connection.";
  if (!error && status >= 500) return "Cablegram's relay isn't reachable right now. Try again in a few minutes.";
  switch (error) {
    case "phone_offline": return "Your phone isn't reachable. Open Cablegram on the phone and check that it has internet.";
    case "mobile_data_not_allowed": return "Your phone is on mobile data, and streaming over mobile data is turned off in Cablegram's settings on the phone.";
    case "relay_email_unverified": return "Free relay needs a confirmed email. Open Cablegram on your phone and confirm your email in Settings.";
    case "relay_free_device_limit": return "Free relay isn't available: your phone already gives free relay to other Cablegram accounts. Upgrade your plan to use it here.";
    case "relay_quota_exceeded": return "This month's relay traffic is used up. Upgrade your plan to keep watching here.";
    case "relay_streams_exceeded": return "Too many relay streams are running in your household. Stop one and try again.";
    case "approval_required": return "Private titles need approval on your phone. Not supported on this TV yet.";
  }
  if (error === "phone_timeout" || error === "phone_disconnected" || status === 502 || status === 504) {
    return "Your phone didn't respond. Check that Cablegram is open on the phone.";
  }
  if (status === 401 || status === 403) return "This TV isn't allowed to play this video any more. Pair it again from your phone.";
  if (status === 404) return "This video isn't on your phone any more.";
  return "This video can't play right now.";
}

/** Asks for the first byte of [url]: null when it answers with video, else the HTTP status and the server's error code. */
export async function preflight(url: string, doFetch: Fetch = (i, init) => fetch(i, init)): Promise<{ status: number | null; error?: string } | null> {
  try {
    const res = await doFetch(url, { headers: { range: "bytes=0-0" } });
    if (res.ok) { await res.text().catch(() => ""); return null; }
    let error: string | undefined;
    try { error = str(JSON.parse(await res.text()).error); } catch { /* not JSON */ }
    return { status: res.status, error };
  } catch {
    return { status: null };
  }
}

export function servingPhone(devices: Device[], servingDeviceId?: string): Device | undefined {
  const phones = devices.filter((d) => d.kind === "phone" && !d.revokedAt);
  return phones.find((d) => d.id === servingDeviceId) ?? phones[0];
}

export function createApi(opts: { base?: string; fetch?: Fetch; token?: () => string | undefined } = {}) {
  const base = (opts.base ?? API_BASE).replace(/\/+$/, "");
  const doFetch: Fetch = opts.fetch ?? ((i, init) => fetch(i, init));

  async function call(path: string, init: RequestInit = {}, auth?: string): Promise<Json | null> {
    const headers: Record<string, string> = { "content-type": "application/json", ...(init.headers as Json | undefined) };
    const token = opts.token?.();
    const bearer = auth ?? (token ? `Bearer ${token}` : undefined);
    if (bearer) headers.authorization = bearer;
    const res = await doFetch(`${base}/${path}`, { ...init, headers });
    if (res.status === 204) return null;
    const text = await res.text();
    let body: Json = {};
    try { body = text ? JSON.parse(text) : {}; } catch { /* non-JSON error page */ }
    if (!res.ok) throw new ApiError(res.status, str(body.error) ?? `http_${res.status}`);
    return body;
  }

  const bearer = (token?: string) => (token ? `Bearer ${token}` : undefined);
  const avatarUrl = (url?: string) => (!url ? undefined : /^(https?:|data:)/.test(url) ? url : `${base}/${url.replace(/^\/+/, "")}`);

  const api = {
    async createDeviceSession(deviceName: string, hardwareId?: string): Promise<DeviceSession> {
      const b = (await call("api/auth/device", { method: "POST", body: JSON.stringify({ deviceName, hardwareId }) }))!;
      return { sessionId: b.session_id, pin: String(b.pin), pairingToken: b.pairing_token, expiresAt: b.expires_at };
    },
    async pairingStatus(session: DeviceSession): Promise<PairingStatus> {
      const b = (await call(`api/auth/device/${session.sessionId}`, {}, `Pairing ${session.pairingToken}`))!;
      return { status: b.status, accessToken: str(b.access_token), lanCapability: str(b.lan_capability) };
    },
    /** [token] picks the account; a TV can hold several. */
    async profiles(token?: string): Promise<Profile[]> {
      const b = (await call("api/profiles", {}, bearer(token)))!;
      return ((b.profiles as Json[]) ?? []).map((p) => ({
        id: p.id, name: p.name, pinSet: p.pin_set === true, avatarUrl: avatarUrl(str(p.avatarUrl) ?? str(p.avatar_url)),
      }));
    },
    /** Returns the HTTP status: 2xx unlocked, 403 wrong PIN, 429 locked out, 0 no answer. */
    async unlockProfile(profileId: string, pin: string, token?: string): Promise<number> {
      try { await call(`api/profiles/${profileId}/unlock`, { method: "POST", body: JSON.stringify({ pin }) }, bearer(token)); return 200; }
      catch (e) { return e instanceof ApiError ? e.status : 0; }
    },
    /** Profile lock: "approved" opens at once, otherwise a phone in the household has to allow it. */
    async requestProfileSwitch(profileId: string, deviceName: string, token?: string): Promise<SwitchRequest> {
      const b = (await call("api/profiles/switch-requests", { method: "POST", body: JSON.stringify({ profileId, deviceName }) }, bearer(token)))!;
      return { requestId: b.requestId, status: b.status };
    },
    async profileSwitch(requestId: string, token?: string): Promise<SwitchRequest> {
      const b = (await call(`api/profiles/switch-requests/${requestId}`, {}, bearer(token)))!;
      return { requestId: b.requestId ?? requestId, status: b.status };
    },
    async cancelProfileSwitch(requestId: string, token?: string) {
      await call(`api/profiles/switch-requests/${requestId}/cancel`, { method: "POST", body: "{}" }, bearer(token));
    },
    /** The Cablegram subtitle saved for this title, already synchronized; empty when there is none. */
    async subtitles(itemId: string): Promise<{ id: string; label: string; content: string }[]> {
      const b = await call(`api/subtitles/playback/item/${encodeURIComponent(itemId)}`);
      return ((b?.subtitles as Json[] | undefined) ?? []).filter((t) => str(t.content)).map((t) => ({
        id: String(t.trackId), content: t.content as string,
        label: `${str(t.label) ?? str(t.language) ?? "Subtitles"} · Cablegram`,
      }));
    },
    async setMyList(profileId: string, itemIds: string[], present: boolean) {
      await call(`api/profiles/${profileId}/my-list`, { method: "PUT", body: JSON.stringify({ item_ids: itemIds, present }) });
    },
    /** Unpairing revokes this TV on the server too; clearing only the local token left the device active. */
    async revokeSelf(token: string) {
      await call("api/devices/self", { method: "DELETE" }, bearer(token));
    },
    async catalog(profileId: string): Promise<Title[]> {
      const b = (await call(`api/catalog/items?profile_id=${encodeURIComponent(profileId)}`))!;
      return ((b.items as Json[]) ?? []).map(parseTitle);
    },
    async devices(): Promise<Device[]> {
      const b = (await call("api/me"))!;
      return ((b.devices as Json[]) ?? []).map((d) => ({ id: d.id, kind: d.kind, revokedAt: str(d.revoked_at) }));
    },
    /** The application's Telegram identity for this TV (allowed per TV by the control plane). */
    async telegramClientConfig(): Promise<{ apiId: number; apiHash: string }> {
      const b = (await call("api/telegram/client-config"))!;
      return { apiId: Number(b.api_id), apiHash: String(b.api_hash) };
    },
    /** Whether the household's phone has linked Telegram, and whether this TV must sign out (the phone ended its session). */
    async telegramLink(): Promise<{ linked: boolean; direct: boolean; logoutRequired: boolean }> {
      const b = (await call("api/telegram/link"))!;
      return { linked: b.linked === true, direct: b.telegram_direct !== false, logoutRequired: b.telegram_logout_required === true };
    },
    /** Hands the phone the link it can approve (Telegram "login by QR"). A new link replaces the TV's earlier one. */
    async postTelegramLogin(loginLink: string): Promise<string> {
      const b = (await call("api/telegram/tv-logins", { method: "POST", body: JSON.stringify({ login_link: loginLink }) }))!;
      return String(b.request_id);
    },
    async telegramLoginState(requestId: string): Promise<{ state: string; error?: string }> {
      const b = (await call(`api/telegram/tv-logins/${encodeURIComponent(requestId)}`))!;
      return { state: String(b.state), error: str(b.error) };
    },
    /** Asks the phone to type the Telegram two-step password; [publicKey] is this request's one-time TV key. */
    async postTelegramPasswordRequest(publicKey: string, hint?: string): Promise<string> {
      const body: Json = { public_key: publicKey };
      if (hint) body.hint = hint.slice(0, 200);
      const b = (await call("api/telegram/tv-password-requests", { method: "POST", body: JSON.stringify(body) }))!;
      return String(b.request_id);
    },
    /** The sealed password arrives exactly once, as state "delivered". */
    async telegramPasswordState(requestId: string): Promise<{ state: string; sealed?: string }> {
      const b = (await call(`api/telegram/tv-password-requests/${encodeURIComponent(requestId)}`))!;
      return { state: String(b.state), sealed: str(b.sealed) };
    },
    async cancelTelegramPasswordRequest(requestId: string): Promise<void> {
      await call(`api/telegram/tv-password-requests/${encodeURIComponent(requestId)}`, { method: "DELETE" }).catch(() => undefined);
    },
    async telegramLogoutAck(): Promise<void> {
      await call("api/telegram/logout-ack", { method: "POST", body: "{}" });
    },
    async relayTicket(phoneId: string): Promise<string> {
      const b = (await call("api/relay/ticket", { method: "POST", body: JSON.stringify({ phone_device_id: phoneId }) }))!;
      return b.ticket;
    },
    async resolveCloud(itemId: string): Promise<Json> {
      return (await call("api/playback/resolve", { method: "POST", body: JSON.stringify({ media_item_id: itemId }) }))!;
    },
    async saveProgress(profileId: string, itemId: string, positionSeconds: number, durationSeconds: number | undefined,
      state: "playing" | "paused" | "stopped" | "completed") {
      await call(`api/profiles/${profileId}/progress/${itemId}`, {
        method: "PUT",
        body: JSON.stringify({ position_seconds: Math.floor(positionSeconds), duration_seconds: durationSeconds, state,
          client_updated_at: new Date().toISOString() }),
      });
    },

    /**
     * Where a title plays from. A browser cannot pin the phone's self-signed LAN certificate, so the TV web app uses the
     * relay for phone copies and the signed URL for cloud copies. Telegram needs an MTProto client (not built yet).
     */
    async resolvePlayback(title: Title, capability: string | undefined): Promise<Playback> {
      const none = { headers: {} as Record<string, string> };
      if (title.sources.some((s) => s.isPrivate)) {
        return { ...none, status: "unsupported", message: "Private titles need approval on your phone. Not supported on this TV yet." };
      }
      const live = title.sources.filter(usable);
      const cloud = live.some((s) => s.kind === "own_cloud" || s.kind === "web");
      const phone = live.find(isPhoneSource);
      if (phone) {
        const phoneDevice = servingPhone(await api.devices(), phone.servingDeviceId);
        if (phoneDevice) {
          try {
            const ticket = await api.relayTicket(phoneDevice.id);
            return { ...none, status: "ready", engine: true, url: relayUrl(base, phoneDevice.id, `media/${phone.originIdentity}`, capability, ticket) };
          } catch (e) { if (!cloud) throw e; }
        }
      }
      if (cloud) {
        const r = await api.resolveCloud(title.id);
        if (r.status === "ready" && str(r.url)) return { status: "ready", url: r.url, headers: (r.headers as Record<string, string>) ?? {} };
      }
      const tg = live.find((s) => s.kind === "telegram");
      const ref = TG_DIRECT ? parseTelegramIdentity(tg?.originIdentity) : null;
      if (ref) return { ...none, status: "ready", engine: true, telegram: ref };
      if (tg) {
        return { ...none, status: "unsupported", message: "This video is stored in Telegram. Telegram playback isn't available on this TV yet." };
      }
      return { ...none, status: "preparing", message: "Waiting for your phone to come online." };
    },
  };
  return api;
}
export type Api = ReturnType<typeof createApi>;
