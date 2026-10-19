import { pbkdf2Sync } from "node:crypto";
import { describe, expect, it } from "vitest";
import { checkCode, clearSession, fromBase64, loginLink, LOGIN_LINK, newTvKeys, openSealed, saveSession, savedSession, toBase64 } from "../src/telegram-link";

// Made by the Android implementation's test (PasswordSealTest.kt "it opens a password sealed by the other implementation").
const PKCS8 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgd6hz1VCMmSJHHZWjlI2PhgxKnPtmy7mkEWmn/jvuOd6hRANCAAS4ZZFtaUkrEOQIcHXzjWiBlfLi6F0yLnoVDFZ6+bK4YiLdRxS5lW3AyTMjSYGSvPu0OdblfqypzKmshPisH+S/";
const SEALED = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERfgTGx8Usy9c4fdK0Ok377/Tt6pjRYn81rDsMTvML/X/4qwBM10a/xa6nevKrn6p9VViDyZ+5/Oj3LWdRXy+ZwECAwQFBgcICQoLDKb79ll2gKLoeV98C9NAM73VlUiWXiocskj9BvGd";
const REQUEST = "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f";

describe("two-step password sealing", () => {
  it("opens a password sealed by the Android app", async () => {
    const key = await crypto.subtle.importKey("pkcs8", fromBase64(PKCS8) as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    expect(await openSealed(key, REQUEST, SEALED)).toBe("pässword 123");
  });

  it("does not open for another request, another key, or altered bytes", async () => {
    const key = await crypto.subtle.importKey("pkcs8", fromBase64(PKCS8) as BufferSource, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    expect(await openSealed(key, "other-request", SEALED)).toBeNull();
    expect(await openSealed((await newTvKeys()).privateKey, REQUEST, SEALED)).toBeNull();
    const raw = fromBase64(SEALED);
    raw[raw.length - 1] ^= 1;
    expect(await openSealed(key, REQUEST, toBase64(raw))).toBeNull();
    expect(await openSealed(key, REQUEST, "not base64 at all")).toBeNull();
  });

  it("makes a 91-byte P-256 public key", async () => {
    const keys = await newTvKeys();
    expect(fromBase64(keys.publicKey)).toHaveLength(91);
    expect(keys.publicKey).toMatch(/^[A-Za-z0-9+/]{80,200}={0,2}$/); // what the control plane accepts
  });
});

describe("check code", () => {
  it("matches PBKDF2 as Java computes it, in the 1234 5678 shape", async () => {
    const { publicKey } = await newTvKeys();
    const bits = pbkdf2Sync(publicKey, "cablegram-tg-key-check-v1", 100_000, 8, "sha256");
    let n = 0;
    for (const b of bits) n = (n * 256 + b) % 100_000_000;
    const digits = String(n).padStart(8, "0");
    expect(await checkCode(publicKey)).toBe(`${digits.slice(0, 4)} ${digits.slice(4)}`);
  });
});

describe("login link", () => {
  it("is the base64url token and satisfies the control plane's pattern", () => {
    const token = Uint8Array.from({ length: 32 }, (_, i) => (i * 37 + 250) & 255);
    const link = loginLink(token);
    expect(link).toMatch(LOGIN_LINK);
    expect(link.slice("tg://login?token=".length)).not.toMatch(/[+/=]/);
    expect(link.startsWith("tg://login?token=")).toBe(true);
  });
});

describe("session storage", () => {
  const memory = () => {
    const m = new Map<string, string>();
    return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) };
  };
  it("keeps one session per account, falls back to the developer one, and clears both", () => {
    const s = memory();
    expect(savedSession("a", s)).toBeNull();
    s.setItem("cablegram.tv.tgSession", "dev");
    expect(savedSession("a", s)).toBe("dev");
    saveSession("a", "mine", s);
    expect(savedSession("a", s)).toBe("mine");
    expect(savedSession("b", s)).toBe("dev");
    clearSession("a", s);
    expect(savedSession("a", s)).toBeNull();
  });
});
