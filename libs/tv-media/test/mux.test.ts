import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Mp4Demuxer } from "../src/demux/mp4";
import { MediaError, type Sample, type Track } from "../src/demux/types";
import { concat } from "../src/demux/bytes";
import { codecString, mimeType } from "../src/mux/codecs";
import { buildFragment, buildInitSegment } from "../src/mux/fmp4";
import { FIXTURES, FileSource, fixturePath, hasFixture } from "./helpers";

const base: Track = { id: 1, kind: "video", codec: "h264", timescale: 1000 };

describe("codec strings", () => {
  it("h264 from avcC", () => {
    expect(codecString({ ...base, config: Uint8Array.from([1, 0x64, 0x00, 0x1f, 0xff]) })).toBe("avc1.64001F");
    expect(codecString({ ...base, fourcc: "avc3", config: Uint8Array.from([1, 0x42, 0xc0, 0x1e]) })).toBe("avc3.42C01E");
  });

  it("hevc from hvcC", () => {
    const cfg = new Uint8Array(23);
    cfg[1] = 0x01; // profile 1, main tier
    cfg.set([0x60, 0, 0, 0], 2); // compatibility flags
    cfg[6] = 0x90; // constraint flags
    cfg[12] = 120;
    expect(codecString({ ...base, codec: "hevc", fourcc: "hvc1", config: cfg })).toBe("hvc1.1.6.L120.90");
    cfg[1] = 0x20 | 2; // high tier, profile 2
    cfg.set([0x20, 0, 0, 0], 2);
    cfg[6] = 0xb0;
    cfg[7] = 0x01;
    expect(codecString({ ...base, codec: "hevc", fourcc: "hev1", config: cfg })).toBe("hev1.2.4.H120.B0.1");
  });

  it("audio codecs", () => {
    const a: Track = { ...base, kind: "audio", timescale: 48000 };
    expect(codecString({ ...a, codec: "aac", config: Uint8Array.from([0x11, 0x88]) })).toBe("mp4a.40.2");
    expect(codecString({ ...a, codec: "aac", config: Uint8Array.from([0x2b, 0x92, 0x08, 0x00]) })).toBe("mp4a.40.5");
    expect(codecString({ ...a, codec: "ac3" })).toBe("ac-3");
    expect(codecString({ ...a, codec: "eac3" })).toBe("ec-3");
    expect(codecString({ ...a, codec: "mp3" })).toBe("mp4a.6B");
    expect(codecString({ ...a, codec: "opus" })).toBe("opus");
    expect(mimeType({ ...a, codec: "ac3" })).toBe('audio/mp4; codecs="ac-3"');
  });

  it("rejects what it cannot mux", () => {
    expect(() => codecString({ ...base, codec: "h264" })).toThrow(MediaError);
    expect(() => codecString({ ...base, codec: "vp9" })).toThrow(MediaError);
  });
});

const MP4S = ["mp4-faststart.mp4", "mp4-moov-end.mp4", "mp4-hevc.mp4", "mp4-ac3.mp4", "mp4-mp3.mp4", "mp4-opus.mp4"].filter(hasFixture);

describe.each(MP4S)("fragmented MP4 from %s", (name) => {
  it("is read back by ffprobe and decodes with ffmpeg", async () => {
    const d = new Mp4Demuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const all: Sample[] = [];
    for (let b = await d.next(1 << 20); b; b = await d.next(1 << 20)) all.push(...b);
    const out = join(FIXTURES, "mux-out");
    mkdirSync(out, { recursive: true });

    for (const track of info.tracks.filter((t) => t.kind !== "subtitle")) {
      const samples = all.filter((s) => s.trackId === track.id);
      const offset = Math.max(0, -samples[0].dts);
      const parts = [buildInitSegment(track)];
      // fragments of about one second
      let seq = 1;
      let from = 0;
      for (let i = 1; i <= samples.length; i++) {
        const lastOfFragment = i === samples.length || (samples[i].keyframe && samples[i].dts - samples[from].dts >= track.timescale) || (track.kind === "audio" && samples[i].dts - samples[from].dts >= track.timescale);
        if (lastOfFragment) {
          parts.push(buildFragment(track, samples.slice(from, i), seq++, offset));
          from = i;
        }
      }
      const file = join(out, `${name}.${track.kind}.mp4`);
      writeFileSync(file, concat(parts));

      const probe = JSON.parse(
        execFileSync("ffprobe", ["-v", "error", "-show_entries", "packet=pts,dts,size,flags", "-of", "json", file], { maxBuffer: 1 << 26 }).toString(),
      ) as { packets: Array<{ pts: number; dts: number; size: string; flags: string }> };
      expect(probe.packets).toHaveLength(samples.length);
      samples.forEach((s, k) => {
        const p = probe.packets[k];
        const got = { dts: p.dts, pts: p.pts, size: Number(p.size), key: p.flags.startsWith("K") };
        expect(got, `${name} ${track.kind} packet ${k}`).toEqual({ dts: s.dts + offset, pts: s.pts + offset, size: s.data.length, key: s.keyframe });
      });

      // a full decode must be clean (checks avcC/hvcC/esds/dac3/dOps are what the decoders expect)
      const dec = spawnSync("ffmpeg", ["-v", "error", "-i", file, "-f", "null", "-"]);
      expect(dec.stderr.toString()).toBe("");
      expect(dec.status).toBe(0);
    }
  });
});
