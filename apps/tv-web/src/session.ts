const KEY = "cablegram.tv.accounts";
const LEGACY_KEY = "cablegram.tv.session";
const SERVER_KEY = "cablegram.tv.server";

function isPrivateLanHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  if (host === "localhost" || host === "127.0.0.1" || host === "[::1]" || host.endsWith(".local")) return true;
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 10 || a === 127 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 169 && b === 254);
}

/** Validates a server URL: HTTPS or private LAN HTTP. Returns normalized URL without trailing slash or undefined. */
export function normalizeServerUrl(raw: string | null | undefined): string | undefined {
  if (!raw) return undefined;
  try {
    const u = new URL(raw.trim());
    if (u.protocol !== "https:" && !(u.protocol === "http:" && isPrivateLanHost(u.hostname))) return undefined;
    const path = u.pathname === "/" ? "" : u.pathname.replace(/\/+$/, "");
    return `${u.origin}${path}`;
  } catch {
    return undefined;
  }
}

let customServerMemory: string | undefined;

export function serverUrl(): string | undefined {
  if (customServerMemory !== undefined) return customServerMemory || undefined;
  try {
    const saved = normalizeServerUrl(localStorage.getItem(SERVER_KEY));
    customServerMemory = saved ?? "";
    return saved;
  } catch {
    return undefined;
  }
}

export function setServerUrl(next: string | null | undefined): string | undefined {
  const normalized = normalizeServerUrl(next);
  const prev = serverUrl();
  customServerMemory = normalized ?? "";
  try {
    if (normalized) localStorage.setItem(SERVER_KEY, normalized);
    else localStorage.removeItem(SERVER_KEY);
  } catch { /* storage blocked */ }
  if (normalized !== prev) clearAccounts();
  return normalized;
}

/** One paired account. A TV can hold several (one per person), like the Android TV app. */
export interface Account { sessionId: string; token: string; lanCapability?: string }

interface Stored { accounts: Account[]; activeSessionId?: string; profileId?: string }

let memory: Stored | null = null;

function load(): Stored {
  if (memory) return memory;
  try {
    memory = JSON.parse(localStorage.getItem(KEY) || "null");
    if (!memory) {
      // The first web build kept one account under another key.
      const legacy = JSON.parse(localStorage.getItem(LEGACY_KEY) || "null");
      memory = { accounts: legacy?.token ? [{ sessionId: "legacy", token: legacy.token, lanCapability: legacy.lanCapability }] : [] };
    }
  } catch { memory = { accounts: [] }; }
  return memory!;
}

function save(next: Stored) {
  memory = next;
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
    localStorage.removeItem(LEGACY_KEY);
  } catch { /* storage blocked */ }
}

export const accounts = (): Account[] => load().accounts;

export function addAccount(account: Account) {
  const s = load();
  save({ ...s, accounts: s.accounts.filter((a) => a.sessionId !== account.sessionId).concat(account), activeSessionId: account.sessionId });
}

export function removeAccount(sessionId: string) {
  const s = load();
  save({ accounts: s.accounts.filter((a) => a.sessionId !== sessionId),
    activeSessionId: s.activeSessionId === sessionId ? undefined : s.activeSessionId,
    profileId: s.activeSessionId === sessionId ? undefined : s.profileId });
}

export function clearAccounts() { save({ accounts: [] }); }

export function activeAccount(): Account | undefined {
  const s = load();
  return s.accounts.find((a) => a.sessionId === s.activeSessionId) ?? s.accounts[0];
}

export function setActive(sessionId: string, profileId?: string) {
  save({ ...load(), activeSessionId: sessionId, profileId });
}

export const activeProfileId = (): string | undefined => load().profileId;
