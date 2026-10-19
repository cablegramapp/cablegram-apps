import { describe, expect, it } from "vitest";
import { MkvDemuxer } from "../src/demux/mkv";
import type { Sample } from "../src/demux/types";
import { FileSource, fixturePath, hasFixture, loadProbe } from "./helpers";

const MKVS = ["mkv-srt.mkv", "mkv-ac3-ass.mkv", "mkv-two-audio.mkv", "mkv-no-cues.mkv", "webm-vp9-opus.webm"].filter(hasFixture);

export async function readAll(d: MkvDemuxer, batch = 128 * 1024): Promise<Sample[]> {
  const all: Sample[] = [];
  for (let b = await d.next(batch); b; b = await d.next(batch)) all.push(...b);
  return all;
}

describe.each(MKVS)("mkv demuxer: %s", (name) => {
  it("matches ffprobe frame by frame", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const probe = loadProbe(name);
    expect(info.container).toBe("mkv");
    expect(info.tracks).toHaveLength(probe.streams.length);
    const samples = await readAll(d);
    for (const [i, stream] of probe.streams.entries()) {
      const track = info.tracks[i];
      const want = probe.packets.filter((p) => p.stream_index === stream.index);
      const got = samples.filter((s) => s.trackId === track.id);
      expect(got.length, `${name} stream ${i} (${track.codec}) count`).toBe(want.length);
      // ffprobe moves audio earlier by the codec's priming delay; compare timestamps relative to the first packet
      const shift = got[0].pts - want[0].pts!;
      want.forEach((p, k) => {
        const s = got[k];
        const ok = s.pts - shift === p.pts && s.data.length === Number(p.size) && s.keyframe === p.flags.startsWith("K");
        if (!ok) throw new Error(`${name} stream ${i} packet ${k}: got pts=${s.pts} size=${s.data.length} key=${s.keyframe}, ffprobe ${JSON.stringify(p)}`);
      });
    }
  });

  it("gives every track strictly increasing dts, positive durations, and dts <= pts for video", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const samples = await readAll(d);
    for (const t of info.tracks) {
      const list = samples.filter((s) => s.trackId === t.id);
      if (t.kind === "subtitle") continue;
      for (let k = 0; k < list.length; k++) {
        expect(list[k].duration, `${t.codec} #${k} duration`).toBeGreaterThan(0);
        if (k > 0) expect(list[k].dts, `${t.codec} #${k} dts order`).toBeGreaterThan(list[k - 1].dts);
        if (t.kind === "video") expect(list[k].pts - list[k].dts, `${t.codec} #${k} cto`).toBeGreaterThanOrEqual(0);
      }
    }
    const minDts = Math.min(...info.tracks.filter((t) => t.kind !== "subtitle").map((t) => samples.find((s) => s.trackId === t.id)!.dts / t.timescale));
    expect(info.minDts).toBeCloseTo(Math.min(0, minDts), 3);
  });
});

describe("mkv demuxer details", () => {
  it.skipIf(!hasFixture("mkv-srt.mkv"))("describes tracks and builds codec configs", async () => {
    const info = await new MkvDemuxer(new FileSource(fixturePath("mkv-srt.mkv"))).init();
    expect(info.duration).toBeGreaterThan(11);
    expect(info.duration).toBeLessThan(13);
    const v = info.tracks.find((t) => t.kind === "video")!;
    expect([v.codec, v.width, v.height]).toEqual(["h264", 320, 180]);
    expect(v.config![0]).toBe(1); // avcC
    const a = info.tracks.find((t) => t.kind === "audio")!;
    expect([a.codec, a.sampleRate, a.channels]).toEqual(["aac", 48000, 1]);
    expect(info.tracks.find((t) => t.kind === "subtitle")!.codec).toBe("srt");
  });

  it.skipIf(!hasFixture("mkv-ac3-ass.mkv"))("derives dac3 from the first AC-3 frame", async () => {
    const info = await new MkvDemuxer(new FileSource(fixturePath("mkv-ac3-ass.mkv"))).init();
    const a = info.tracks.find((t) => t.kind === "audio")!;
    expect(a.codec).toBe("ac3");
    expect(a.config).toHaveLength(3);
    // fscod(2) bsid(5) bsmod(3) acmod(3) lfeon(1) bit_rate_code(5) reserved(5)
    const bits = (a.config![0] << 16) | (a.config![1] << 8) | a.config![2];
    expect(bits >> 22).toBe(0); // 48 kHz
    expect((bits >> 17) & 31).toBe(8); // bsid of plain AC-3
    expect((bits >> 11) & 7).toBe(1); // mono
    expect((bits >> 10) & 1).toBe(0); // no LFE
    expect((bits >> 5) & 31).toBe(6); // 96 kbit/s
  });

  it.skipIf(!hasFixture("webm-vp9-opus.webm"))("converts OpusHead to dOps", async () => {
    const info = await new MkvDemuxer(new FileSource(fixturePath("webm-vp9-opus.webm"))).init();
    const a = info.tracks.find((t) => t.kind === "audio")!;
    expect(a.codec).toBe("opus");
    expect(a.config![0]).toBe(0);
    expect(a.config![1]).toBe(1); // channels
    expect(info.tracks.find((t) => t.kind === "video")!.codec).toBe("vp9");
  });

  it.skipIf(!hasFixture("mkv-srt.mkv"))("returns SRT cues", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath("mkv-srt.mkv")));
    const info = await d.init();
    const sub = info.tracks.find((t) => t.kind === "subtitle")!;
    const cues = (await readAll(d)).filter((s) => s.trackId === sub.id && s.cue).map((s) => s.cue!);
    expect(cues.map((c) => c.text)).toEqual(["Hello world", "Second line\nwith a break"]);
    expect(cues[0].start).toBeCloseTo(1, 2);
    expect(cues[0].end).toBeCloseTo(3, 2);
  });

  it.skipIf(!hasFixture("mkv-ac3-ass.mkv"))("returns ASS cues as plain text", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath("mkv-ac3-ass.mkv")));
    const info = await d.init();
    const sub = info.tracks.find((t) => t.kind === "subtitle")!;
    expect(sub.codec).toBe("ass");
    const cues = (await readAll(d)).filter((s) => s.trackId === sub.id && s.cue).map((s) => s.cue!);
    expect(cues.map((c) => c.text)).toEqual(["Styled text, with comma", "Line one\nLine two"]);
  });

  it.skipIf(!hasFixture("mkv-two-audio.mkv"))("reads only the enabled audio track", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath("mkv-two-audio.mkv")));
    const info = await d.init();
    const audio = info.tracks.filter((t) => t.kind === "audio");
    expect(audio).toHaveLength(2);
    d.setEnabledTracks([info.tracks.find((t) => t.kind === "video")!.id, audio[1].id]);
    const ids = new Set((await readAll(d)).map((s) => s.trackId));
    expect(ids.has(audio[0].id)).toBe(false);
    expect(ids.has(audio[1].id)).toBe(true);
  });
});

describe.each(["mkv-srt.mkv", "mkv-no-cues.mkv"].filter(hasFixture))("mkv seeking: %s", (name) => {
  it("lands on the keyframe at or before the target and starts every track there", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const video = info.tracks.find((t) => t.kind === "video")!;
    const audio = info.tracks.find((t) => t.kind === "audio")!;
    for (const target of [7.3, 0.5, 11.2]) {
      const t = await d.seek(target);
      expect(t).toBeLessThanOrEqual(target);
      expect(t).toBeGreaterThan(target - 1.1);
      const batch = (await d.next(64 * 1024))!;
      const v = batch.find((s) => s.trackId === video.id)!;
      expect(v.keyframe).toBe(true);
      expect(v.pts / video.timescale).toBeCloseTo(t, 3);
      const a = batch.find((s) => s.trackId === audio.id)!;
      expect(a.pts / audio.timescale).toBeGreaterThanOrEqual(t - 0.001);
      expect(a.pts / audio.timescale).toBeLessThan(t + 0.1);
    }
  });

  it("reads to the end after a seek", async () => {
    const d = new MkvDemuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const video = info.tracks.find((t) => t.kind === "video")!;
    const t = await d.seek(8);
    const rest = (await readAll(d)).filter((s) => s.trackId === video.id);
    expect(rest.length).toBeGreaterThan(50);
    expect(rest[0].pts / video.timescale).toBeCloseTo(t, 3);
  });
});

import { rewriteMkv } from "./mkvtools";
import { readFileSync } from "node:fs";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { FIXTURES } from "./helpers";

/** Same file with the index removed and/or headers stripped, written next to the fixtures. */
function variant(source: string, name: string, opts: Parameters<typeof rewriteMkv>[1]): string {
  const out = join(FIXTURES, name);
  writeFileSync(out, rewriteMkv(new Uint8Array(readFileSync(fixturePath(source))), opts));
  return out;
}

describe.skipIf(!hasFixture("mkv-srt.mkv"))("mkv without an index", () => {
  it("really has no Cues, and seeking bisects over the clusters", async () => {
    const path = variant("mkv-srt.mkv", "mkv-srt.nocues.mkv", { dropIndex: true });
    const src = new FileSource(path);
    const d = new MkvDemuxer(src);
    const info = await d.init();
    expect((d as unknown as { cues: unknown[] }).cues).toHaveLength(0);
    const video = info.tracks.find((t) => t.kind === "video")!;
    for (const target of [0.2, 5.5, 11.5]) {
      const t = await d.seek(target);
      expect(t).toBeLessThanOrEqual(target);
      expect(t).toBeGreaterThan(target - 1.1);
      const batch = (await d.next(64 * 1024))!;
      expect(batch.find((s) => s.trackId === video.id)!.keyframe).toBe(true);
    }
  });

  it("copes with an unknown-size Segment", async () => {
    const path = variant("mkv-srt.mkv", "mkv-srt.unknown-size.mkv", { dropIndex: true, unknownSegmentSize: true });
    const d = new MkvDemuxer(new FileSource(path));
    await d.init();
    const samples = await readAll(d);
    expect(samples.length).toBeGreaterThan(400);
  });

  it("restores stripped frame headers", async () => {
    const info0 = await new MkvDemuxer(new FileSource(fixturePath("mkv-srt.mkv"))).init();
    const video0 = info0.tracks.find((t) => t.kind === "video")!;
    const original = (await readAll(new MkvDemuxer(new FileSource(fixturePath("mkv-srt.mkv"))))).filter((s) => s.trackId === video0.id);
    // H.264 in Matroska is length-prefixed; frames below 64 KiB all start with two zero bytes
    const prefix = Uint8Array.from([0, 0]);
    expect(original.every((s) => s.data[0] === 0 && s.data[1] === 0)).toBe(true);
    const path = variant("mkv-srt.mkv", "mkv-srt.stripped.mkv", { dropIndex: true, stripHeader: { track: video0.id, prefix } });
    const stripped = (await readAll(new MkvDemuxer(new FileSource(path)))).filter((s) => s.trackId === video0.id);
    expect(stripped.length).toBe(original.length);
    stripped.forEach((s, i) => expect(Array.from(s.data), `frame ${i}`).toEqual(Array.from(original[i].data)));
  });
});
