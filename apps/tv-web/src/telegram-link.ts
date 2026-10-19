// The TV's half of Cablegram's Telegram sign-in (contracts/telegram-link.md): the login link the phone approves, the
// Telegram two-step password typed on the phone and sealed to a one-time TV key, and where the TV keeps its own Telegram
// session. No GramJS here, so it loads everywhere and is unit-tested. Same sealing as apps/android-tv/.../PasswordSeal.kt.

const SPKI_LENGTH = 91; // DER SubjectPublicKeyInfo of a P-256 key
const IV_LENGTH = 12;
const TAG_LENGTH = 16;
const enc = (s: string) => new TextEncoder().encode(s);

export const LOGIN_LINK = /^tg:\/\/login\?token=[A-Za-z0-9_=-]{16,512}$/;

export function toBase64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s);
}

export function fromBase64(text: string): Uint8Array {
  const s = atob(text);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

/** `tg://login?token=…` for the token Telegram's auth.exportLoginToken returned (base64url, no padding). */
export function loginLink(token: Uint8Array): string {
  return `tg://login?token=${toBase64(token).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;
}

// ---- the two-step password, sealed to this TV ----------------------------------------------------------------------

export interface TvKeys { publicKey: string; privateKey: CryptoKey }

/** A one-time P-256 key pair for one password request; [publicKey] (base64 SPKI) is what the control plane gets. */
export async function newTvKeys(): Promise<TvKeys> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"])) as CryptoKeyPair;
  const spki = new Uint8Array((await crypto.subtle.exportKey("spki", pair.publicKey)) as ArrayBuffer);
  return { publicKey: toBase64(spki), privateKey: pair.privateKey };
}

/** The password the phone sealed for [requestId], or null when it is not for this request and key, or was altered. */
export async function openSealed(privateKey: CryptoKey, requestId: string, sealed: string): Promise<string | null> {
  try {
    const raw = fromBase64(sealed);
    if (raw.length <= SPKI_LENGTH + IV_LENGTH + TAG_LENGTH) return null;
    const phone = await crypto.subtle.importKey("spki", raw.slice(0, SPKI_LENGTH) as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, []);
    const secret = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: phone } as EcdhKeyDeriveParams, privateKey, 256));
    const label = enc(`cablegram-tg-password-v1:${requestId}`);
    const material = new Uint8Array(secret.length + label.length);
    material.set(secret, 0);
    material.set(label, secret.length);
    const key = await crypto.subtle.importKey("raw", await crypto.subtle.digest("SHA-256", material), { name: "AES-GCM" }, false, ["decrypt"]);
    const iv = raw.slice(SPKI_LENGTH, SPKI_LENGTH + IV_LENGTH) as BufferSource;
    const body = raw.slice(SPKI_LENGTH + IV_LENGTH) as BufferSource;
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv, additionalData: enc(requestId), tagLength: TAG_LENGTH * 8 }, key, body);
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}

/**
 * "1234 5678": the code the phone shows for this TV key, so the owner can see the phone seals to the key the TV made and
 * not one a compromised server put in its place. PBKDF2-HMAC-SHA256, 100,000 rounds, 8 bytes mod 10^8.
 */
export async function checkCode(publicKey: string): Promise<string> {
  const base = await crypto.subtle.importKey("raw", enc(publicKey), { name: "PBKDF2" }, false, ["deriveBits"]);
  const bits = new Uint8Array(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: enc("cablegram-tg-key-check-v1"), iterations: 100_000 }, base, 64));
  let n = 0;
  for (let i = 0; i < bits.length; i++) n = (n * 256 + bits[i]) % 100_000_000; // stays far below 2^53
  const digits = ("00000000" + n).slice(-8);
  return `${digits.slice(0, 4)} ${digits.slice(4)}`;
}

// ---- this TV's Telegram session -----------------------------------------------------------------------------------

/** Kept per paired account (a TV can hold several). The plain key is the developer override from scripts/tg-session.mjs. */
const SESSION_PREFIX = "cablegram.tv.tgSession";

export interface KeyValueStore { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void }

function defaultStore(): KeyValueStore | null {
  try { return localStorage; } catch { return null; }
}

export function savedSession(accountId: string, store: KeyValueStore | null = defaultStore()): string | null {
  if (!store) return null;
  try { return store.getItem(`${SESSION_PREFIX}.${accountId}`) || store.getItem(SESSION_PREFIX) || null; } catch { return null; }
}

export function saveSession(accountId: string, session: string, store: KeyValueStore | null = defaultStore()): void {
  try { store?.setItem(`${SESSION_PREFIX}.${accountId}`, session); } catch { /* storage blocked */ }
}

export function clearSession(accountId: string, store: KeyValueStore | null = defaultStore()): void {
  try { store?.removeItem(`${SESSION_PREFIX}.${accountId}`); store?.removeItem(SESSION_PREFIX); } catch { /* storage blocked */ }
}
