import { describe, expect, it } from "vitest";
import { Mp4Demuxer } from "../src/demux/mp4";
import type { Sample } from "../src/demux/types";
import { FileSource, hasFixture, fixturePath, loadProbe } from "./helpers";

const MP4S = ["mp4-faststart.mp4", "mp4-moov-end.mp4", "mp4-tx3g.mp4", "mp4-hevc.mp4", "mp4-ac3.mp4"];

async function readAll(d: Mp4Demuxer): Promise<Sample[]> {
  const all: Sample[] = [];
  for (let b = await d.next(256 * 1024); b; b = await d.next(256 * 1024)) all.push(...b);
  return all;
}

describe.each(MP4S.filter(hasFixture))("mp4 demuxer: %s", (name) => {
  it("matches ffprobe packet by packet", async () => {
    const d = new Mp4Demuxer(new FileSource(fixturePath(name)));
    const info = await d.init();
    const probe = loadProbe(name);
    expect(info.tracks).toHaveLength(probe.streams.length);

    const samples = await readAll(d);
    for (const [i, stream] of probe.streams.entries()) {
      // tracks are in file order, like ffprobe's stream indexes
      const track = info.tracks[i];
      const want = probe.packets.filter((p) => p.stream_index === stream.index);
      let got = samples.filter((s) => s.trackId === track.id);
      // the file ends with an empty gap sample that ffprobe does not list
      if (track.kind === "subtitle") got = got.slice(0, want.length);
      expect(got.length, `${name} stream ${i} count`).toBe(want.length);
      for (const [k, p] of want.entries()) {
        const s = got[k];
        if (s.dts !== p.dts || s.pts !== p.pts || s.data.length !== Number(p.size) || s.keyframe !== p.flags.startsWith("K")) {
          throw new Error(`${name} stream ${i} packet ${k}: got dts=${s.dts} pts=${s.pts} size=${s.data.length} key=${s.keyframe}, ffprobe ${JSON.stringify(p)}`);
        }
      }
    }
  });

  it("describes the tracks", async () => {
    const info = await new Mp4Demuxer(new FileSource(fixturePath(name))).init();
    expect(info.duration).toBeGreaterThan(11);
    expect(info.duration).toBeLessThan(13);
    const video = info.tracks.find((t) => t.kind === "video")!;
    expect(video.width).toBe(320);
    expect(video.height).toBe(180);
    expect(video.config?.length).toBeGreaterThan(4);
    expect(video.codec).toBe(name.includes("hevc") ? "hevc" : "h264");
    const audio = info.tracks.find((t) => t.kind === "audio")!;
    expect(audio.codec).toBe(name.includes("ac3") ? "ac3" : "aac");
    expect(audio.sampleRate).toBe(48000);
    expect(audio.channels).toBe(name.includes("ac3") ? 1 : 1);
    expect(audio.config?.length).toBeGreaterThan(0);
  });
});

describe("mp4 demuxer details", () => {
  it.skipIf(!hasFixture("mp4-moov-end.mp4"))("finds a moov at the end with few reads", async () => {
    const src = new FileSource(fixturePath("mp4-moov-end.mp4"));
    await new Mp4Demuxer(src).init();
    expect(src.reads.length).toBeLessThan(8);
  });

  it.skipIf(!hasFixture("mp4-faststart.mp4"))("seeks to the keyframe at or before the target", async () => {
    const d = new Mp4Demuxer(new FileSource(fixturePath("mp4-faststart.mp4")));
    const info = await d.init();
    const video = info.tracks.find((t) => t.kind === "video")!;
    const audio = info.tracks.find((t) => t.kind === "audio")!;
    const t = await d.seek(7.3); // keyframes every 25 frames = 1 s
    expect(t).toBe(7);
    const batch = (await d.next(64 * 1024))!;
    const firstVideo = batch.find((s) => s.trackId === video.id)!;
    expect(firstVideo.keyframe).toBe(true);
    expect(firstVideo.pts / video.timescale).toBeCloseTo(7, 3);
    const firstAudio = batch.find((s) => s.trackId === audio.id)!;
    expect(firstAudio.dts / audio.timescale).toBeGreaterThanOrEqual(7 - 0.001);
    expect(firstAudio.dts / audio.timescale).toBeLessThan(7.1);
  });

  it.skipIf(!hasFixture("mp4-faststart.mp4"))("reads only the enabled tracks", async () => {
    const d = new Mp4Demuxer(new FileSource(fixturePath("mp4-faststart.mp4")));
    const info = await d.init();
    const video = info.tracks.find((t) => t.kind === "video")!;
    d.setEnabledTracks([video.id]);
    const samples = await readAll(d);
    expect(new Set(samples.map((s) => s.trackId))).toEqual(new Set([video.id]));
  });

  it.skipIf(!hasFixture("mp4-tx3g.mp4"))("turns tx3g samples into cues", async () => {
    const d = new Mp4Demuxer(new FileSource(fixturePath("mp4-tx3g.mp4")));
    const info = await d.init();
    const sub = info.tracks.find((t) => t.kind === "subtitle")!;
    expect(sub.codec).toBe("tx3g");
    const cues = (await readAll(d)).filter((s) => s.trackId === sub.id && s.cue).map((s) => s.cue!);
    expect(cues.map((c) => c.text)).toEqual(["Hello world", "Second line\nwith a break"]);
    expect(cues[0].start).toBeCloseTo(1, 2);
    expect(cues[0].end).toBeCloseTo(3, 2);
  });
});
