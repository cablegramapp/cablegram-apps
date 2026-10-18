import { describe, expect, it } from "vitest";
import { createApi, parseTitle, relayUrl, servingPhone } from "../src/api";
import { pickNext } from "../src/nav";

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
