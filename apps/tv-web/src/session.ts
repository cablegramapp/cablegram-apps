const KEY = "cablegram.tv.session";

export interface Session { token: string; lanCapability?: string; profileId?: string }

let memory: Session | null = null;

export function loadSession(): Session | null {
  if (memory) return memory;
  try { memory = JSON.parse(localStorage.getItem(KEY) || "null"); } catch { memory = null; }
  return memory;
}

export function saveSession(session: Session | null) {
  memory = session;
  try { session ? localStorage.setItem(KEY, JSON.stringify(session)) : localStorage.removeItem(KEY); } catch { /* storage blocked */ }
}
