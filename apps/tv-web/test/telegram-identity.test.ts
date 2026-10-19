import { describe, expect, it } from "vitest";
import { parseTelegramIdentity, peerOf } from "../src/telegram-identity";

describe("parseTelegramIdentity", () => {
  it("reads tg:<chat>:<message>", () => {
    expect(parseTelegramIdentity("tg:-1001234567890:42")).toEqual({ chatId: "-1001234567890", messageId: 42 });
    expect(parseTelegramIdentity("tg:777:1")).toEqual({ chatId: "777", messageId: 1 });
  });
  it("rejects anything else", () => {
    for (const bad of [undefined, "", "tg:1", "tg:a:2", "tg:1:0", "tg:1:2:3", "http://x", "s1"]) expect(parseTelegramIdentity(bad)).toBeNull();
  });
});

describe("peerOf", () => {
  it("maps TDLib chat ids to MTProto peers", () => {
    expect(peerOf("-1001234567890")).toEqual({ kind: "channel", id: "1234567890" });
    expect(peerOf("-4242")).toEqual({ kind: "chat", id: "4242" });
    expect(peerOf("99")).toEqual({ kind: "user", id: "99" });
  });
});
