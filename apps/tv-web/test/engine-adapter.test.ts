import { describe, expect, it } from "vitest";
import { engineFailureMessage, insertCues, silentVideoNotice, trackLabel } from "../src/engine-adapter";

describe("trackLabel", () => {
  it("uses the language name and a distinct track name", () => {
    expect(trackLabel({ language: "eng" }, 0)).toBe("English");
    expect(trackLabel({ language: "deu", name: "Commentary" }, 1)).toBe("German · Commentary");
    expect(trackLabel({ language: "eng", name: "English" }, 0)).toBe("English");
    expect(trackLabel({ language: "xyz" }, 0)).toBe("XYZ");
    expect(trackLabel({}, 2)).toBe("Track 3");
  });
});

describe("insertCues", () => {
  it("keeps cues sorted and drops duplicates re-read after a seek", () => {
    const list: { start: number; end: number; text: string }[] = [];
    insertCues(list, [{ start: 5, end: 6, text: "b" }, { start: 1, end: 2, text: "a" }]);
    insertCues(list, [{ start: 1, end: 2, text: "a" }, { start: 3, end: 4, text: "c" }]);
    expect(list.map((c) => c.text)).toEqual(["a", "c", "b"]);
  });
});

describe("engineFailureMessage", () => {
  const err = (kind: string, status?: number, code?: string) => ({ kind, status, code }) as never;
  it("explains relay and Telegram failures, and lets format problems fall back", () => {
    expect(engineFailureMessage(err("source_http", 503, "phone_offline"))).toContain("phone isn't reachable");
    expect(engineFailureMessage(err("source_http", 429, "relay_streams_exceeded"))).toContain("Too many relay streams");
    expect(engineFailureMessage(err("source_network"))).toContain("Can't reach Cablegram");
    expect(engineFailureMessage(err("telegram_flood"))).toContain("Telegram");
    expect(engineFailureMessage(err("unsupported_codec"))).toBeNull();
    expect(engineFailureMessage(err("decode"))).toBeNull();
  });
});

describe("silentVideoNotice", () => {
  it("tells the viewer only when there is sound this TV cannot decode and nothing else to hear", () => {
    expect(silentVideoNotice(0, ["ac3"])).toContain("Dolby Digital (AC-3)");
    expect(silentVideoNotice(0, ["eac3", "eac3", "dts"])).toContain("Dolby Digital Plus (E-AC-3), DTS");
    expect(silentVideoNotice(1, ["ac3"])).toBeNull();
    expect(silentVideoNotice(0, [])).toBeNull();
  });
});
