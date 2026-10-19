import { describe, expect, it } from "vitest";
import { AUDIO_TYPES, chromiumVersion, compactSummary, decoderMatrix, Report, slowdown, VIDEO_TYPES } from "../src/diagnostics";

describe("diagnostics helpers", () => {
  it("lists decoders as words and as a bit string", () => {
    const m = decoderMatrix(VIDEO_TYPES, (mime) => mime.includes("avc1") || mime.includes("hvc1"));
    expect(m.text).toBe("H.264 yes, HEVC yes, VP9 no, AV1 no");
    expect(m.bits).toBe("1100");
    expect(decoderMatrix(AUDIO_TYPES, () => { throw new Error("no MSE"); }).bits).toBe("00000");
  });

  it("reads the Chromium version from the user agent", () => {
    expect(chromiumVersion("Mozilla/5.0 (SMART-TV; Linux; Tizen) AppleWebKit/537.36 Chrome/55.0.2883.75 Safari/537.36 Tizen")).toBe(55);
    expect(chromiumVersion("Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 Chrome/53.0.2785.34 Safari/537.36")).toBe(53);
    expect(chromiumVersion("curl/8")).toBeNull();
  });

  it("compares speed with the laptop reference", () => {
    expect(slowdown(600, 1200)).toBe(2);
    expect(slowdown(0)).toBe(Infinity);
  });

  it("replaces a row with the same id and summarises results in one short line", () => {
    const seen: number[] = [];
    const r = new Report((rows) => seen.push(rows.length));
    r.add("a", "A", "info", "measuring…");
    r.add("b", "B", "ok", "fine");
    r.add("a", "A", "fail", "broken");
    expect(r.rows.map((x) => x.status)).toEqual(["fail", "ok"]);
    expect(seen).toEqual([1, 2, 2]);
    r.facts.chrome = 55;
    const text = compactSummary(r.facts, r.rows);
    expect(JSON.parse(text)).toEqual({ v: 1, chrome: 55, rows: "!+" });
    expect(text.length).toBeLessThan(900);
  });
});
