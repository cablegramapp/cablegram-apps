const KEY = "cablegram.tv.accounts";
const LEGACY_KEY = "cablegram.tv.session";

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
