import { describe, expect, it } from "vitest";
import { createApi, parseServerCapabilities, parseTitle, relayUrl, servingPhone } from "../src/api";
import { pickNext } from "../src/nav";
import { normalizeServerUrl, setServerUrl, serverUrl } from "../src/session";

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

describe("parseTitle", () => {
  it("hides resume for completed titles and reads sources", () => {
    const t = parseTitle({ id: "1", title: "A", progress: { state: "completed", position_seconds: 50 },
      sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
    expect(t.resumeSeconds).toBeUndefined();
    expect(t.sources[0].originIdentity).toBe("x");
  });
});

describe("relayUrl", () => {
  it("carries the capability and ticket as query parameters", () => {
    const u = new URL(relayUrl("https://api.example/", "ph 1", "media/abc", "cap", "tick"));
    expect(u.pathname).toBe("/relay/v1/p/ph%201/media/abc");
    expect(u.searchParams.get("token")).toBe("cap");
    expect(u.searchParams.get("rt")).toBe("tick");
  });
});

describe("servingPhone", () => {
  it("prefers the serving device and skips revoked phones", () => {
    const d = [{ id: "a", kind: "phone", revokedAt: "x" }, { id: "b", kind: "phone" }, { id: "c", kind: "phone" }];
    expect(servingPhone(d, "c")?.id).toBe("c");
    expect(servingPhone(d, "a")?.id).toBe("b");
  });
});

describe("resolvePlayback", () => {
  const phoneTitle = parseTitle({ id: "1", title: "A", sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
  it("plays a phone copy through the relay", async () => {
    const api = createApi({ base: "https://api.example", token: () => "t", fetch: async (url) =>
      url.endsWith("api/me") ? json(200, { devices: [{ id: "p1", kind: "phone" }] }) : json(200, { ticket: "rt1" }) });
    const p = await api.resolvePlayback(phoneTitle, "cap");
    expect(p.status).toBe("ready");
    expect(p.url).toContain("/relay/v1/p/p1/media/x");
  });
  it("refuses private and Telegram-only titles with an explanation", async () => {
    const api = createApi({ fetch: async () => json(200, {}) });
    const priv = parseTitle({ id: "2", sources: [{ kind: "phone_local", origin_identity: "x", private: true }] });
    const tg = parseTitle({ id: "3", sources: [{ kind: "telegram", origin_identity: "tg:1:2" }] });
    expect((await api.resolvePlayback(priv, undefined)).status).toBe("unsupported");
    expect((await api.resolvePlayback(tg, undefined)).message).toMatch(/Telegram/);
  });
  it("maps a PIN failure to its status", async () => {
    const api = createApi({ fetch: async () => json(403, { error: "wrong_pin" }) });
    expect(await api.unlockProfile("p", "0000")).toBe(403);
  });
});

describe("pickNext", () => {
  const r = (l: number, t: number) => ({ left: l, top: t, right: l + 100, bottom: t + 100 });
  it("moves in the pressed direction and prefers the aligned neighbour", () => {
    const from = r(0, 0);
    expect(pickNext(from, [r(200, 0), r(200, 300)], "right")).toBe(0);
    expect(pickNext(from, [r(0, 200), r(300, 200)], "down")).toBe(0);
    expect(pickNext(from, [r(200, 0)], "left")).toBe(-1);
  });
});

import { pairLink } from "../src/pairlink";
describe("pairLink", () => {
  it("matches the Android TV link shape", () => {
    expect(pairLink("https://app.example/", "123456", "LG TV")).toBe("https://app.example/?pair=123456&tv=LG%20TV");
  });
});

describe("preflight", () => {
  it("passes video through and reads the relay's error code", async () => {
    const { preflight, relayMessage } = await import("../src/api");
    expect(await preflight("u", async () => new Response("x", { status: 206 }))).toBeNull();
    const refused = await preflight("u", async () => json(429, { error: "relay_streams_exceeded" }));
    expect(refused).toEqual({ status: 429, error: "relay_streams_exceeded" });
    expect(relayMessage(429, "relay_streams_exceeded")).toMatch(/Too many relay streams/);
    expect(await preflight("u", async () => { throw new Error("offline"); })).toEqual({ status: null });
  });
});

describe("server capabilities and runtime server URL", () => {
  it("parses Community capabilities and skips subtitle and relay requests when disabled", async () => {
    const calls: string[] = [];
    const api = createApi({
      base: "https://community.example",
      fetch: async (url) => {
        calls.push(url);
        if (url.endsWith("api/server")) {
          return json(200, {
            api_version: 1,
            edition: "community",
            auth: { mode: "device" },
            features: { subtitles: false, relay: false, artwork: false },
          });
        }
        return json(500, {});
      },
    });
    const caps = await api.server();
    expect(caps.edition).toBe("community");
    expect(caps.authMode).toBe("device");
    expect(caps.features.subtitles).toBe(false);
    expect(caps.features.relay).toBe(false);
    expect(caps.features.telegram).toBe(true);

    // Subtitles should not make an HTTP request when subtitles=false
    const subs = await api.subtitles("item-1");
    expect(subs).toEqual([]);
    expect(calls).toEqual(["https://community.example/api/server"]);

    // Phone-only title should report relay unsupported without calling /api/me or /api/relay/ticket
    const phoneTitle = parseTitle({ id: "1", title: "A", sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
    const pb = await api.resolvePlayback(phoneTitle, "cap");
    expect(pb.status).toBe("unsupported");
    expect(pb.message).toMatch(/Relay playback is not enabled/);
  });

  it("normalizes HTTPS and LAN HTTP server URLs and rejects public HTTP", () => {
    expect(normalizeServerUrl("https://tv.example.com/")).toBe("https://tv.example.com");
    expect(normalizeServerUrl("http://192.168.1.20:3000/")).toBe("http://192.168.1.20:3000");
    expect(normalizeServerUrl("http://tv.example.com")).toBeUndefined();
    expect(setServerUrl("https://self.example.com/")).toBe("https://self.example.com");
    expect(serverUrl()).toBe("https://self.example.com");
    setServerUrl(null);
    expect(serverUrl()).toBeUndefined();
  });

  it("parses default capabilities when fields are omitted", () => {
    const caps = parseServerCapabilities({});
    expect(caps.features.telegram).toBe(true);
    expect(caps.features.lanPlayback).toBe(true);
    expect(caps.features.artwork).toBe(false);
  });
});
