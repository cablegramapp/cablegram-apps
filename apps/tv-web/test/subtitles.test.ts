import { describe, expect, it } from "vitest";
import { cueText, parseVtt, startWithSmart } from "../src/subtitles";

const vtt = `WEBVTT

1
00:00:01.000 --> 00:00:03.500
<i>Hello</i> &amp; welcome

00:00:03,000 --> 00:00:05,000 line:90%
Second line
over two rows

01:00:00.000 --> 01:00:02.000
An hour in`;

describe("parseVtt", () => {
  it("reads WebVTT and SRT-style times and drops tags", () => {
    const cues = parseVtt(vtt);
    expect(cues.map((c) => c.start)).toEqual([1, 3, 3600]);
    expect(cues[0].text).toBe("Hello & welcome");
    expect(cues[1].text).toBe("Second line\nover two rows");
  });
  it("finds the text at a time, joining overlaps", () => {
    const cues = parseVtt(vtt);
    expect(cueText(cues, 0.5)).toBe("");
    expect(cueText(cues, 2)).toBe("Hello & welcome");
    expect(cueText(cues, 3.2)).toBe("Hello & welcome\nSecond line\nover two rows");
    expect(cueText(cues, 3601)).toBe("An hour in");
  });
});

describe("startWithSmart", () => {
  const smart = { id: "s2", label: "English · Cablegram", content: "" };
  it("shows a new or changed Cablegram subtitle even over an older Off", () => {
    expect(startWithSmart(smart, undefined)).toBe(true);
    expect(startWithSmart(smart, { off: true, smartId: "s1" })).toBe(true);
  });
  it("keeps the viewer's choice for the same subtitle", () => {
    expect(startWithSmart(smart, { off: true, smartId: "s2" })).toBe(false);
    expect(startWithSmart(undefined, undefined)).toBe(false);
  });
});
