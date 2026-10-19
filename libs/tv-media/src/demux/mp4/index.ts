import type { ByteSource } from "../../source/types";
import { Reader, utf8 } from "../bytes";
import { MediaError, type Demuxer, type MediaInfo, type Sample, type SubtitleCue, type Track } from "../types";
import { childBoxes, findBox, findPath, type Box } from "./boxes";
import { buildSampleIndex, lastAtOrBefore, type SampleIndex } from "./tables";

interface TrackState {
  track: Track;
  index: SampleIndex;
  cursor: number;
}

export class Mp4Demuxer implements Demuxer {
  private tracks: TrackState[] = [];
  private enabled = new Set<number>();

  constructor(private readonly source: ByteSource) {}

  async init(): Promise<MediaInfo> {
    const moov = await this.loadMoov();
    const buf = moov.buf;
    const root = moov.box;
    if (findBox(buf, root, "mvex")) throw new MediaError("unsupported_container", "mp4: fragmented files are not supported yet");

    let movieTimescale = 1000;
    let movieDuration = 0;
    const mvhd = findBox(buf, root, "mvhd");
    if (mvhd) {
      const r = new Reader(buf, mvhd.dataStart);
      const version = r.u8();
      r.skip(3);
      if (version === 1) {
        r.skip(16);
        movieTimescale = r.u32();
        movieDuration = r.u64();
      } else {
        r.skip(8);
        movieTimescale = r.u32();
        movieDuration = r.u32();
      }
    }

    let maxEnd = 0;
    for (const trak of childBoxes(buf, root.dataStart, root.end)) {
      if (trak.type !== "trak") continue;
      const state = this.parseTrak(buf, trak, movieTimescale);
      if (!state) continue;
      this.tracks.push(state);
      this.enabled.add(state.track.id);
      const ix = state.index;
      if (ix.count) {
        const end = (ix.dts[ix.count - 1] + ix.duration[ix.count - 1]) / state.track.timescale;
        if (state.track.kind !== "subtitle") maxEnd = Math.max(maxEnd, end);
      }
    }
    const duration = movieDuration ? movieDuration / movieTimescale : maxEnd;
    return { container: "mp4", duration, tracks: this.tracks.map((t) => t.track) };
  }

  setEnabledTracks(ids: number[]): void {
    this.enabled = new Set(ids);
  }

  async seek(timeSec: number): Promise<number> {
    let keyTime = Math.max(0, timeSec);
    const video = this.tracks.find((t) => t.track.kind === "video" && this.enabled.has(t.track.id));
    if (video) {
      const ix = video.index;
      let i = lastAtOrBefore(ix.dts, ix.count, keyTime * video.track.timescale);
      if (i < 0) i = 0;
      while (i > 0 && !ix.sync[i]) i--;
      video.cursor = i;
      keyTime = (ix.dts[i] + ix.cto[i]) / video.track.timescale; // presentation time of the keyframe
    }
    for (const t of this.tracks) {
      if (t === video) continue;
      const ix = t.index;
      const ticks = keyTime * t.track.timescale;
      if (t.track.kind === "subtitle") {
        // keep a cue that is still showing at the seek target
        let i = lastAtOrBefore(ix.dts, ix.count, ticks);
        t.cursor = i >= 0 && ix.dts[i] + ix.duration[i] > ticks ? i : i + 1;
      } else {
        let i = lastAtOrBefore(ix.dts, ix.count, ticks);
        if (i < 0) i = 0;
        else if (ix.dts[i] < ticks && i + 1 < ix.count) i++; // first sample at or after the keyframe time
        while (i > 0 && !ix.sync[i]) i--;
        t.cursor = i;
      }
    }
    return keyTime;
  }

  async next(maxBytes: number, signal?: AbortSignal): Promise<Sample[] | null> {
    const picked: Array<{ t: TrackState; i: number }> = [];
    let bytes = 0;
    for (;;) {
      let best: TrackState | null = null;
      let bestTime = Infinity;
      for (const t of this.tracks) {
        if (!this.enabled.has(t.track.id) || t.cursor >= t.index.count) continue;
        const time = t.index.dts[t.cursor] / t.track.timescale;
        if (time < bestTime) {
          bestTime = time;
          best = t;
        }
      }
      if (!best || (picked.length > 0 && bytes >= maxBytes)) break;
      picked.push({ t: best, i: best.cursor });
      bytes += best.index.size[best.cursor];
      best.cursor++;
    }
    if (picked.length === 0) return null;

    // One read per run of contiguous samples.
    const out: Sample[] = [];
    let k = 0;
    while (k < picked.length) {
      const startOff = picked[k].t.index.offset[picked[k].i];
      let endOff = startOff;
      let j = k;
      while (j < picked.length && picked[j].t.index.offset[picked[j].i] === endOff) {
        endOff += picked[j].t.index.size[picked[j].i];
        j++;
      }
      const data = await this.source.read(startOff, endOff - startOff, signal);
      if (data.length < endOff - startOff) throw new MediaError("malformed", "mp4: file ends inside a sample");
      for (let m = k; m < j; m++) {
        const { t, i } = picked[m];
        const ix = t.index;
        const from = ix.offset[i] - startOff;
        const payload = data.subarray(from, from + ix.size[i]);
        const sample: Sample = {
          trackId: t.track.id,
          dts: ix.dts[i],
          pts: ix.dts[i] + ix.cto[i],
          duration: ix.duration[i],
          keyframe: ix.sync[i] === 1,
          data: payload,
        };
        if (t.track.kind === "subtitle") sample.cue = parseCue(t.track.codec, payload, sample.dts / t.track.timescale, sample.duration / t.track.timescale);
        out.push(sample);
      }
      k = j;
    }
    return out;
  }

  private async loadMoov(): Promise<{ buf: Uint8Array; box: Box }> {
    const size = this.source.size;
    let off = 0;
    for (let guard = 0; guard < 64; guard++) {
      if (size !== null && off >= size) break;
      const head = await this.source.read(off, 16);
      if (head.length < 8) break;
      const r = new Reader(head);
      let boxSize = r.u32();
      const type = r.fourcc();
      if (boxSize === 1) boxSize = r.u64();
      else if (boxSize === 0) boxSize = (size ?? off) - off;
      if (boxSize < 8) throw new MediaError("malformed", "mp4: bad box size");
      if (type === "moov") {
        const buf = await this.source.read(off, boxSize);
        if (buf.length < boxSize) throw new MediaError("malformed", "mp4: truncated moov");
        const box = [...childBoxes(buf, 0, buf.length)][0];
        return { buf, box };
      }
      off += boxSize;
    }
    throw new MediaError("malformed", "mp4: no moov box");
  }

  private parseTrak(buf: Uint8Array, trak: Box, movieTimescale: number): TrackState | null {
    const tkhd = findBox(buf, trak, "tkhd");
    const mdia = findBox(buf, trak, "mdia");
    if (!tkhd || !mdia) return null;

    const tr = new Reader(buf, tkhd.dataStart);
    const tv = tr.u8();
    tr.skip(3);
    tr.skip(tv === 1 ? 16 : 8);
    const id = tr.u32();

    const mdhd = findBox(buf, mdia, "mdhd");
    const hdlr = findBox(buf, mdia, "hdlr");
    const stbl = findPath(buf, mdia, "minf", "stbl");
    if (!mdhd || !hdlr || !stbl) return null;

    const mr = new Reader(buf, mdhd.dataStart);
    const mv = mr.u8();
    mr.skip(3);
    mr.skip(mv === 1 ? 16 : 8);
    const timescale = mr.u32();
    mr.skip(mv === 1 ? 8 : 4);
    const lang = mr.u16();
    const language = langCode(lang);

    const hr = new Reader(buf, hdlr.dataStart);
    hr.skip(8);
    const handler = hr.fourcc();
    const kind = handler === "vide" ? "video" : handler === "soun" ? "audio" : handler === "text" || handler === "sbtl" || handler === "subt" ? "subtitle" : null;
    if (!kind) return null;

    const payload = (type: string): Uint8Array | undefined => {
      const b = findBox(buf, stbl, type);
      return b ? buf.subarray(b.dataStart, b.end) : undefined;
    };
    const stsd = findBox(buf, stbl, "stsd");
    if (!stsd) return null;
    const track = parseSampleEntry(buf, stsd, kind, id, timescale, language);
    if (!track) return null;

    let shift = 0;
    const elst = findPath(buf, trak, "edts", "elst");
    if (elst && kind !== "subtitle") shift = editShift(buf, elst, movieTimescale, timescale);

    const index = buildSampleIndex(
      {
        stts: payload("stts"),
        ctts: payload("ctts"),
        stsz: payload("stsz"),
        stz2: payload("stz2"),
        stsc: payload("stsc"),
        stco: payload("stco"),
        co64: payload("co64"),
        stss: payload("stss"),
      },
      shift,
    );
    return { track, index, cursor: 0 };
  }
}

/** Media time of the first edit when it starts the track late (typical for B-frame video): the dts shift. */
function editShift(buf: Uint8Array, elst: Box, _movieTimescale: number, _mediaTimescale: number): number {
  const r = new Reader(buf, elst.dataStart);
  const version = r.u8();
  r.skip(3);
  const count = r.u32();
  for (let i = 0; i < count; i++) {
    let mediaTime: number;
    if (version === 1) {
      r.skip(8);
      mediaTime = r.u64();
      r.skip(4);
    } else {
      r.skip(4);
      mediaTime = r.i32();
      r.skip(4);
    }
    if (mediaTime === -1) continue; // empty edit: a delay, not a shift
    return mediaTime > 0 ? mediaTime : 0;
  }
  return 0;
}

function langCode(v: number): string | undefined {
  const a = (v >> 10) & 31;
  const b = (v >> 5) & 31;
  const c = v & 31;
  if (!a || !b || !c) return undefined;
  const s = String.fromCharCode(a + 0x60, b + 0x60, c + 0x60);
  return s === "und" ? undefined : s;
}

function parseSampleEntry(buf: Uint8Array, stsd: Box, kind: Track["kind"], id: number, timescale: number, language?: string): Track | null {
  const entry = [...childBoxes(buf, stsd.dataStart + 8, stsd.end)][0];
  if (!entry) return null;
  const fourcc = entry.type;
  const track: Track = { id, kind, codec: fourcc, fourcc, timescale, language };

  if (kind === "video") {
    const r = new Reader(buf, entry.dataStart + 24);
    track.width = r.u16();
    track.height = r.u16();
    const kids = entry.dataStart + 78;
    const config = (type: string) => {
      const b = findBox(buf, { dataStart: kids, end: entry.end }, type);
      return b ? buf.subarray(b.dataStart, b.end) : undefined;
    };
    if (fourcc === "avc1" || fourcc === "avc3") {
      track.codec = "h264";
      track.config = config("avcC");
    } else if (fourcc === "hvc1" || fourcc === "hev1") {
      track.codec = "hevc";
      track.config = config("hvcC");
    } else if (fourcc === "vp09") track.codec = "vp9";
    else if (fourcc === "av01") {
      track.codec = "av1";
      track.config = config("av1C");
    }
  } else if (kind === "audio") {
    const r = new Reader(buf, entry.dataStart + 8);
    const version = r.u16();
    r.skip(6);
    track.channels = r.u16();
    r.skip(6);
    track.sampleRate = r.u32() >>> 16;
    const kids = entry.dataStart + 28 + (version === 1 ? 16 : 0);
    const kid = (type: string) => findBox(buf, { dataStart: kids, end: entry.end }, type);
    if (fourcc === "mp4a") {
      const esds = kid("esds");
      const es = esds ? parseEsds(buf.subarray(esds.dataStart + 4, esds.end)) : null;
      if (es && es.objectType === 0x6b) track.codec = "mp3";
      else if (es && (es.objectType === 0x40 || es.objectType === 0x66 || es.objectType === 0x67 || es.objectType === 0x68)) {
        track.codec = "aac";
        track.config = es.config;
      } else track.codec = `mp4a.${es ? es.objectType.toString(16) : "?"}`;
    } else if (fourcc === "ac-3") {
      track.codec = "ac3";
      const b = kid("dac3");
      if (b) track.config = buf.subarray(b.dataStart, b.end);
    } else if (fourcc === "ec-3") {
      track.codec = "eac3";
      const b = kid("dec3");
      if (b) track.config = buf.subarray(b.dataStart, b.end);
    } else if (fourcc === "Opus") {
      track.codec = "opus";
      const b = kid("dOps");
      if (b) track.config = buf.subarray(b.dataStart, b.end);
    } else if (fourcc === ".mp3" || fourcc === "mp3 ") track.codec = "mp3";
  } else {
    if (fourcc === "tx3g") track.codec = "tx3g";
    else if (fourcc === "wvtt") track.codec = "webvtt";
    else return null; // image or unknown subtitle formats are not offered
  }
  return track;
}

function parseEsds(b: Uint8Array): { objectType: number; config?: Uint8Array } | null {
  const r = new Reader(b);
  const len = () => {
    let n = 0;
    for (let i = 0; i < 4 && r.left > 0; i++) {
      const v = r.u8();
      n = (n << 7) | (v & 0x7f);
      if (!(v & 0x80)) break;
    }
    return n;
  };
  if (r.left < 2 || r.u8() !== 0x03) return null;
  len();
  r.skip(2);
  const flags = r.u8();
  if (flags & 0x80) r.skip(2);
  if (flags & 0x40) r.skip(r.u8());
  if (flags & 0x20) r.skip(2);
  if (r.left < 2 || r.u8() !== 0x04) return null;
  len();
  const objectType = r.u8();
  r.skip(12);
  let config: Uint8Array | undefined;
  if (r.left >= 2 && r.u8() === 0x05) {
    const n = len();
    config = b.subarray(r.pos, r.pos + n);
  }
  return { objectType, config };
}

function parseCue(codec: string, data: Uint8Array, start: number, dur: number): SubtitleCue | null {
  if (codec === "tx3g") {
    if (data.length < 2) return null;
    const n = (data[0] << 8) | data[1];
    const text = utf8(data.subarray(2, 2 + n));
    return n ? { start, end: start + dur, text } : null;
  }
  if (codec === "webvtt") {
    const parts: string[] = [];
    for (const b of childBoxes(data, 0, data.length)) {
      if (b.type !== "vttc") continue;
      const payl = findBox(data, b, "payl");
      if (payl) parts.push(utf8(data.subarray(payl.dataStart, payl.end)));
    }
    return parts.length ? { start, end: start + dur, text: parts.join("\n") } : null;
  }
  return null;
}
