import type { ByteSource } from "../../source/types";
import { concat } from "../bytes";
import { MediaError, type Demuxer, type MediaInfo, type Sample, type Track } from "../types";
import { dac3FromFrame, dec3FromFrame } from "./ac3";
import { ID, LEVEL1, readFloat, readHeader, readString, readUInt, readVint } from "./ebml";
import { assText, srtText, vttText } from "./subs";

/** A frame as stored in the file, after lacing and header-stripping are undone. */
interface Raw {
  track: number;
  pts: number;
  keyframe: boolean;
  /** Ticks; from BlockDuration, else the track's DefaultDuration, else undefined. */
  duration?: number;
  data: Uint8Array;
}

interface TrackInfo {
  track: Track;
  type: number;
  defaultDuration?: number;
  /** Bytes removed from the start of every frame (ContentCompression, algorithm 3). */
  prefix?: Uint8Array;
}

interface Cue {
  time: number;
  pos: number;
}

/** Per-track conversion of Raw frames to Samples: dts assignment for reordered video, then durations. */
class Pipe {
  private fifo: Raw[] = []; // video: decode order, waiting for lookahead
  private sortedPts: number[] = [];
  private held: { raw: Raw; dts: number } | null = null;
  private lastDuration = 0;

  constructor(
    private readonly info: TrackInfo,
    private readonly shift: number,
    private readonly lookahead: number,
  ) {}

  push(raw: Raw, out: Sample[]): void {
    if (this.info.track.kind === "video") {
      this.fifo.push(raw);
      insertSorted(this.sortedPts, raw.pts);
      while (this.fifo.length > this.lookahead) this.stage1(out);
    } else this.stage2(raw, raw.pts, out);
  }

  flush(out: Sample[]): void {
    while (this.fifo.length) this.stage1(out);
    if (this.held) {
      const d = Math.round(this.held.raw.duration ?? this.info.defaultDuration ?? this.lastDuration);
      this.emit(this.held, d || 1, out);
      this.held = null;
    }
  }

  reset(): void {
    this.fifo = [];
    this.sortedPts = [];
    this.held = null;
  }

  private stage1(out: Sample[]): void {
    const raw = this.fifo.shift()!;
    const dts = this.sortedPts.shift()! - this.shift;
    this.stage2(raw, dts, out);
  }

  private stage2(raw: Raw, dts: number, out: Sample[]): void {
    if (this.info.track.kind === "subtitle") {
      this.emit({ raw, dts }, raw.duration ?? this.info.defaultDuration ?? this.info.track.timescale * 2, out);
      return;
    }
    // Durations come from the next dts: they must tile the timeline exactly, and DefaultDuration (e.g. 21.33 ms
    // AAC frames) does not survive rounding to whole ticks.
    if (this.held) {
      const d = dts - this.held.dts;
      this.emit(this.held, d > 0 ? d : Math.round(this.info.defaultDuration ?? 1) || 1, out);
    }
    this.held = { raw, dts };
  }

  private emit(h: { raw: Raw; dts: number }, duration: number, out: Sample[]): void {
    this.lastDuration = duration;
    const t = this.info.track;
    const sample: Sample = { trackId: t.id, dts: h.dts, pts: h.raw.pts, duration, keyframe: h.raw.keyframe, data: h.raw.data };
    if (t.kind === "subtitle") {
      const text = t.codec === "ass" ? assText(h.raw.data) : t.codec === "webvtt" ? vttText(h.raw.data) : srtText(h.raw.data);
      const start = h.raw.pts / t.timescale;
      sample.cue = text ? { start, end: start + duration / t.timescale, text } : null;
    }
    out.push(sample);
  }
}

function insertSorted(a: number[], v: number): void {
  let i = a.length;
  while (i > 0 && a[i - 1] > v) i--;
  a.splice(i, 0, v);
}

const REORDER_LOOKAHEAD = 16;
const TEXT_SUBTITLES = new Set(["srt", "ass", "webvtt"]);

export class MkvDemuxer implements Demuxer {
  private segStart = 0;
  private segEnd = 0;
  private ticksPerSec = 1000;
  private infos: TrackInfo[] = [];
  private cues: Cue[] = [];
  private cuesPos = -1;
  private firstCluster = 0;
  private enabled = new Set<number>();
  private reorderShift = 0;
  private pipes = new Map<number, Pipe>();
  private carry: Raw[] = [];
  private flushed = false;

  // raw cursor
  private pos = 0;
  private cluster: { end: number; ts: number } | null = null;

  constructor(private readonly source: ByteSource) {}

  async init(): Promise<MediaInfo> {
    const size = this.source.size;
    const head = await this.source.read(0, 64);
    const ebml = readHeader(head, 0);
    if (!ebml || ebml.id !== ID.EBML || ebml.size < 0) throw new MediaError("malformed", "mkv: no EBML header");
    let p = ebml.header + ebml.size;
    const seg = readHeader(await this.source.read(p, 16), 0);
    if (!seg || seg.id !== ID.Segment) throw new MediaError("malformed", "mkv: no Segment");
    this.segStart = p + seg.header;
    this.segEnd = seg.size < 0 ? size ?? Infinity : Math.min(this.segStart + seg.size, size ?? Infinity);

    let duration = 0;
    let tsScale = 1000000;
    p = this.segStart;
    let sawTracks = false;
    while (p < this.segEnd) {
      const h = readHeader(await this.source.read(p, 16), 0);
      if (!h || h.size < 0) break;
      if (h.id === ID.Cluster) {
        this.firstCluster = p;
        break;
      }
      const bodyStart = p + h.header;
      if (h.id === ID.SeekHead) {
        const body = await this.source.read(bodyStart, h.size);
        this.parseSeekHead(body);
      } else if (h.id === ID.Info) {
        const body = await this.source.read(bodyStart, h.size);
        let rawDuration = 0;
        forEach(body, 0, body.length, (id, s, e) => {
          if (id === ID.TimestampScale) tsScale = readUInt(body, s, e);
          else if (id === ID.Duration) rawDuration = readFloat(body, s, e);
        });
        this.ticksPerSec = 1e9 / tsScale;
        duration = (rawDuration * tsScale) / 1e9;
      } else if (h.id === ID.Tracks) {
        const body = await this.source.read(bodyStart, h.size);
        this.parseTracks(body);
        sawTracks = true;
      }
      p = bodyStart + h.size;
    }
    if (!sawTracks) throw new MediaError("malformed", "mkv: no Tracks element");
    if (!this.firstCluster) throw new MediaError("malformed", "mkv: no clusters");
    for (const i of this.infos) i.track.timescale = this.ticksPerSec;

    await this.loadCues();
    await this.peek();

    const tracks = this.infos.map((i) => i.track);
    for (const t of tracks) if (this.readable(t)) this.enabled.add(t.id);
    if (!duration && this.cues.length) duration = this.cues[this.cues.length - 1].time / this.ticksPerSec;
    return { container: "mkv", duration, minDts: this.minDts(), tracks };
  }

  setEnabledTracks(ids: number[]): void {
    this.enabled = new Set(ids);
    for (const id of [...this.pipes.keys()]) if (!this.enabled.has(id)) this.pipes.delete(id);
  }

  async seek(timeSec: number, signal?: AbortSignal): Promise<number> {
    const video = this.infos.find((i) => i.track.kind === "video" && this.enabled.has(i.track.id));
    let target = Math.max(0, Math.round(timeSec * this.ticksPerSec));
    this.resetCursor();
    let raws: Raw[] = [];
    let keyIdx = 0;
    let keyPts = target;
    for (;;) {
      const loc = await this.locate(target);
      this.resetCursor(loc.pos);
      raws = [];
      let bytes = 0;
      let lastKey = -1;
      for (;;) {
        const got = await this.readRaw(512 * 1024, signal);
        if (!got) break;
        let beyond = false;
        for (const r of got) {
          bytes += r.data.length;
          raws.push(r);
          if (video && r.track === video.track.id && r.keyframe) {
            if (r.pts > target) beyond = true;
            else if (!beyond) lastKey = raws.length - 1;
          }
        }
        if (beyond || bytes > 48 * 1024 * 1024) break;
      }
      if (!video) break;
      if (lastKey >= 0) {
        keyIdx = lastKey;
        keyPts = raws[lastKey].pts;
        break;
      }
      if (loc.pos <= this.firstCluster) {
        // nothing before the target: start at the first video keyframe we found
        const first = raws.findIndex((r) => r.track === video.track.id && r.keyframe);
        keyIdx = Math.max(0, first);
        keyPts = raws[keyIdx]?.pts ?? target;
        break;
      }
      target = Math.max(0, loc.ts - 1);
    }

    // Keep from the keyframe on: video from its index, other tracks from the keyframe's time.
    this.carry = [];
    const videoId = video?.track.id;
    raws.forEach((r, i) => {
      if (r.track === videoId) {
        if (i >= keyIdx) this.carry.push(r);
      } else if (this.infos.find((x) => x.track.id === r.track)?.track.kind === "subtitle") {
        if (r.pts + (r.duration ?? 0) > keyPts) this.carry.push(r);
      } else if (r.pts >= keyPts) this.carry.push(r);
    });
    this.pipes.clear();
    this.flushed = false;
    return keyPts / this.ticksPerSec;
  }

  async next(maxBytes: number, signal?: AbortSignal): Promise<Sample[] | null> {
    const out: Sample[] = [];
    let raws: Raw[] | null;
    if (this.carry.length) {
      raws = this.carry;
      this.carry = [];
    } else raws = await this.readRaw(maxBytes, signal);

    if (raws) {
      for (const r of raws) this.pipe(r.track)?.push(r, out);
      return out;
    }
    if (this.flushed) return null;
    this.flushed = true;
    for (const p of this.pipes.values()) p.flush(out);
    return out.length ? out : null;
  }

  // ---- header parsing ---------------------------------------------------------------------------------------------

  private parseSeekHead(body: Uint8Array): void {
    forEach(body, 0, body.length, (id, s, e) => {
      if (id !== ID.Seek) return;
      let seekId = 0;
      let position = -1;
      forEach(body, s, e, (cid, cs, ce) => {
        if (cid === ID.SeekID) seekId = readUInt(body, cs, ce);
        else if (cid === ID.SeekPosition) position = readUInt(body, cs, ce);
      });
      if (seekId === ID.Cues) this.cuesPos = position;
    });
  }

  private parseTracks(body: Uint8Array): void {
    forEach(body, 0, body.length, (id, s, e) => {
      if (id !== ID.TrackEntry) return;
      let number = 0;
      let type = 0;
      let codecId = "";
      let language = "eng";
      let name: string | undefined;
      let priv: Uint8Array | undefined;
      let defaultDuration: number | undefined;
      let width: number | undefined;
      let height: number | undefined;
      let channels: number | undefined;
      let sampleRate: number | undefined;
      let prefix: Uint8Array | undefined;
      let compressed = false; // a compression other than header stripping: the frames are not usable as stored
      forEach(body, s, e, (cid, cs, ce) => {
        switch (cid) {
          case ID.TrackNumber: number = readUInt(body, cs, ce); break;
          case ID.TrackType: type = readUInt(body, cs, ce); break;
          case ID.CodecID: codecId = readString(body, cs, ce); break;
          case ID.CodecPrivate: priv = body.subarray(cs, ce); break;
          case ID.Language: language = readString(body, cs, ce); break;
          case ID.LanguageBCP47: language = readString(body, cs, ce); break;
          case ID.Name: name = readString(body, cs, ce); break;
          case ID.DefaultDuration: defaultDuration = readUInt(body, cs, ce); break;
          case ID.Video:
            forEach(body, cs, ce, (vid, vs, ve) => {
              if (vid === ID.PixelWidth) width = readUInt(body, vs, ve);
              else if (vid === ID.PixelHeight) height = readUInt(body, vs, ve);
            });
            break;
          case ID.Audio:
            forEach(body, cs, ce, (aid, as, ae) => {
              if (aid === ID.Channels) channels = readUInt(body, as, ae);
              else if (aid === ID.SamplingFrequency) sampleRate = Math.round(readFloat(body, as, ae));
            });
            break;
          case ID.ContentEncodings:
            forEach(body, cs, ce, (eid, es, ee) => {
              if (eid !== ID.ContentEncoding) return;
              forEach(body, es, ee, (xid, xs, xe) => {
                if (xid !== ID.ContentCompression) return;
                let algo = 0;
                let settings: Uint8Array | undefined;
                forEach(body, xs, xe, (yid, ys, ye) => {
                  if (yid === ID.ContentCompAlgo) algo = readUInt(body, ys, ye);
                  else if (yid === ID.ContentCompSettings) settings = body.subarray(ys, ye);
                });
                if (algo === 3) prefix = settings;
                else compressed = true;
              });
            });
            break;
        }
      });
      const kind = type === 1 ? "video" : type === 2 ? "audio" : type === 0x11 ? "subtitle" : null;
      if (!kind) return;
      const track: Track = { id: number, kind, codec: codecId, timescale: 1000 };
      if (language && language !== "und") track.language = language;
      if (name) track.name = name;
      if (width) track.width = width;
      if (height) track.height = height;
      if (channels) track.channels = channels;
      if (sampleRate) track.sampleRate = sampleRate;
      mapCodec(track, codecId, priv);
      // Reported under a name no decoder accepts, so the engine skips it instead of feeding it garbage.
      if (compressed) track.codec = `${track.codec}+compressed` as typeof track.codec;
      this.infos.push({ track, type, defaultDuration, prefix });
    });
    // DefaultDuration is in ns; convert once the timescale is known
    for (const i of this.infos) if (i.defaultDuration) i.defaultDuration = (i.defaultDuration * this.ticksPerSec) / 1e9;
  }

  private async loadCues(): Promise<void> {
    if (this.cuesPos < 0) return;
    const at = this.segStart + this.cuesPos;
    const h = readHeader(await this.source.read(at, 16), 0);
    if (!h || h.id !== ID.Cues || h.size < 0) return;
    const body = await this.source.read(at + h.header, h.size);
    const wanted = (this.infos.find((i) => i.track.kind === "video") ?? this.infos[0])?.track.id;
    const cues: Cue[] = [];
    forEach(body, 0, body.length, (id, s, e) => {
      if (id !== ID.CuePoint) return;
      let time = -1;
      let track = -1;
      let pos = -1;
      forEach(body, s, e, (cid, cs, ce) => {
        if (cid === ID.CueTime) time = readUInt(body, cs, ce);
        else if (cid === ID.CueTrackPositions && track < 0) {
          forEach(body, cs, ce, (pid, ps, pe) => {
            if (pid === ID.CueTrack) track = readUInt(body, ps, pe);
            else if (pid === ID.CueClusterPosition) pos = readUInt(body, ps, pe);
          });
        }
      });
      if (time >= 0 && pos >= 0 && (track === wanted || track < 0)) cues.push({ time, pos: this.segStart + pos });
    });
    cues.sort((a, b) => a.time - b.time);
    this.cues = cues;
  }

  /** Reads the start of the first cluster: AC-3 configs, and the dts shift of reordered video. */
  private async peek(): Promise<void> {
    for (const i of this.infos) if (this.readable(i.track)) this.enabled.add(i.track.id);
    this.resetCursor(this.firstCluster);
    const frames: Raw[] = [];
    let bytes = 0;
    while (bytes < 2 * 1024 * 1024) {
      const got = await this.readRaw(512 * 1024);
      if (!got) break;
      for (const r of got) bytes += r.data.length;
      frames.push(...got);
      if (frames.filter((f) => this.infos.find((i) => i.track.id === f.track)?.track.kind === "video").length >= 64) break;
    }
    for (const i of this.infos) {
      const t = i.track;
      if (t.codec !== "ac3" && t.codec !== "eac3") continue;
      const first = frames.find((f) => f.track === t.id);
      if (first) t.config = (t.codec === "ac3" ? dac3FromFrame : dec3FromFrame)(first.data) ?? undefined;
    }
    const video = this.infos.find((i) => i.track.kind === "video");
    if (video) {
      const vf = frames.filter((f) => f.track === video.track.id);
      const sorted = vf.map((f) => f.pts).sort((a, b) => a - b);
      let shift = 0;
      // an incomplete tail would look like reordering, so ignore the last frames of the window
      const usable = Math.max(0, vf.length - REORDER_LOOKAHEAD);
      for (let k = 0; k < usable; k++) shift = Math.max(shift, sorted[k] - vf[k].pts);
      this.reorderShift = shift;
      this.firstVideoPts = vf.length ? sorted[0] : 0;
    }
    this.resetCursor(this.firstCluster);
  }

  private firstVideoPts = 0;

  private minDts(): number {
    // video dts starts at (first pts - shift); everything else at its pts, which is >= 0
    if (!this.reorderShift) return 0;
    return Math.min(0, (this.firstVideoPts - this.reorderShift) / this.ticksPerSec);
  }

  private readable(t: Track): boolean {
    if (t.kind === "subtitle") return TEXT_SUBTITLES.has(t.codec);
    return true;
  }

  private pipe(trackId: number): Pipe | undefined {
    if (!this.enabled.has(trackId)) return undefined;
    let p = this.pipes.get(trackId);
    if (!p) {
      const info = this.infos.find((i) => i.track.id === trackId);
      if (!info) return undefined;
      p = new Pipe(info, info.track.kind === "video" ? this.reorderShift : 0, REORDER_LOOKAHEAD);
      this.pipes.set(trackId, p);
    }
    return p;
  }

  // ---- raw reading ------------------------------------------------------------------------------------------------

  private resetCursor(pos = this.firstCluster): void {
    this.pos = pos;
    this.cluster = null;
    this.carry = [];
    this.pipes.clear();
    this.flushed = false;
  }

  /** Frames of roughly maxBytes, in file order; null at the end of the segment. */
  private async readRaw(maxBytes: number, signal?: AbortSignal): Promise<Raw[] | null> {
    const out: Raw[] = [];
    let bytes = 0;
    while (bytes < maxBytes) {
      if (!this.cluster) {
        if (this.pos >= this.segEnd) break;
        const h = readHeader(await this.source.read(this.pos, 16, signal), 0);
        if (!h) break;
        if (h.id === ID.Cluster) {
          this.cluster = { end: h.size < 0 ? this.segEnd : this.pos + h.header + h.size, ts: 0 };
          this.pos += h.header;
        } else {
          if (h.size < 0) break;
          this.pos += h.header + h.size;
        }
        continue;
      }
      const c = this.cluster;
      if (this.pos >= c.end) {
        this.cluster = null;
        continue;
      }
      let want = Math.min(Math.max(maxBytes - bytes, 64 * 1024), c.end - this.pos);
      let buf = await this.source.read(this.pos, want, signal);
      if (buf.length === 0) {
        this.cluster = null;
        this.pos = this.segEnd;
        break;
      }
      let at = 0;
      let ended = false;
      for (;;) {
        const h = readHeader(buf, at);
        if (!h) break;
        if (LEVEL1.has(h.id)) {
          // an unknown-size cluster ends where the next top-level element begins
          this.cluster = null;
          ended = true;
          break;
        }
        if (h.size < 0) break;
        const total = h.header + h.size;
        if (this.pos + at + total > c.end) {
          // an element that runs past its cluster: a damaged size. Drop the rest of the cluster rather than read it.
          at = c.end - this.pos; // `this.pos += at` below lands on the cluster end
          this.cluster = null;
          ended = true;
          break;
        }
        if (at + total > buf.length) {
          if (at === 0) {
            // a single element larger than the window: read exactly it
            want = total;
            buf = await this.source.read(this.pos, want, signal);
            if (buf.length < total) {
              this.cluster = null;
              this.pos = this.segEnd;
              return out.length ? out : null;
            }
            continue;
          }
          break;
        }
        const s = at + h.header;
        const e = s + h.size;
        if (h.id === ID.Timestamp) c.ts = readUInt(buf, s, e);
        else if (h.id === ID.SimpleBlock) bytes += this.addBlock(buf, s, e, c.ts, undefined, undefined, out);
        else if (h.id === ID.BlockGroup) bytes += this.addGroup(buf, s, e, c.ts, out);
        at += total;
      }
      this.pos += at;
      if (ended) continue;
      if (at === 0) {
        // cannot parse at all: bad data, stop instead of looping
        this.cluster = null;
        this.pos = this.segEnd;
        break;
      }
    }
    return out.length ? out : null;
  }

  private addGroup(buf: Uint8Array, s: number, e: number, clusterTs: number, out: Raw[]): number {
    let block: [number, number] | null = null;
    let duration: number | undefined;
    let keyframe = true;
    forEach(buf, s, e, (id, cs, ce) => {
      if (id === ID.Block) block = [cs, ce];
      else if (id === ID.BlockDuration) duration = readUInt(buf, cs, ce);
      else if (id === ID.ReferenceBlock) keyframe = false;
    });
    return block ? this.addBlock(buf, block[0], block[1], clusterTs, keyframe, duration, out) : 0;
  }

  private addBlock(buf: Uint8Array, s: number, e: number, clusterTs: number, groupKey: boolean | undefined, groupDuration: number | undefined, out: Raw[]): number {
    const tn = readVint(buf, s);
    if (!tn || s + tn.length + 3 > e) return 0;
    const trackNo = tn.value;
    if (!this.enabled.has(trackNo)) return 0;
    const info = this.infos.find((i) => i.track.id === trackNo);
    if (!info) return 0;
    let p = s + tn.length;
    const rel = (buf[p] << 8) | buf[p + 1];
    const tc = rel >= 0x8000 ? rel - 0x10000 : rel;
    const flags = buf[p + 2];
    p += 3;
    const keyframe = groupKey !== undefined ? groupKey : (flags & 0x80) !== 0;
    const lacing = (flags >> 1) & 3;

    const frames: Array<[number, number]> = [];
    if (lacing === 0) frames.push([p, e]);
    else {
      const count = buf[p++] + 1;
      const sizes: number[] = [];
      if (lacing === 2) {
        // Xiph
        for (let i = 0; i < count - 1; i++) {
          let sz = 0;
          let b: number;
          do {
            b = buf[p++];
            sz += b;
          } while (b === 255);
          sizes.push(sz);
        }
      } else if (lacing === 3) {
        // EBML
        const first = readVint(buf, p)!;
        p += first.length;
        sizes.push(first.value);
        for (let i = 1; i < count - 1; i++) {
          const v = readVint(buf, p)!;
          p += v.length;
          const bias = Math.pow(2, 7 * v.length - 1) - 1;
          sizes.push(sizes[i - 1] + (v.value - bias));
        }
      } else {
        // fixed
        const each = Math.floor((e - p) / count);
        for (let i = 0; i < count - 1; i++) sizes.push(each);
      }
      let used = 0;
      for (const sz of sizes) used += sz;
      sizes.push(e - p - used);
      for (const sz of sizes) {
        frames.push([p, p + sz]);
        p += sz;
      }
    }

    let bytes = 0;
    const perFrame = frames.length > 1 ? info.defaultDuration : undefined;
    frames.forEach(([a, b], i) => {
      let data = buf.subarray(a, b);
      if (info.prefix) data = concat([info.prefix, data]);
      const duration = frames.length === 1 ? groupDuration ?? info.defaultDuration : perFrame;
      out.push({ track: trackNo, pts: clusterTs + tc + Math.round(i * (perFrame ?? 0)), keyframe, duration, data });
      bytes += data.length;
    });
    return bytes;
  }

  // ---- seeking without reading everything -------------------------------------------------------------------------

  /** Byte offset of a cluster whose timestamp is <= target (and that cluster's timestamp). */
  private async locate(target: number): Promise<{ pos: number; ts: number }> {
    if (this.cues.length) {
      let best: Cue | null = null;
      for (const c of this.cues) {
        if (c.time <= target) best = c;
        else break;
      }
      if (best) return { pos: best.pos, ts: best.time };
      return { pos: this.firstCluster, ts: 0 };
    }

    // No Cues: bisect over byte positions, resyncing on cluster ids.
    const size = this.segEnd === Infinity ? this.source.size ?? 0 : this.segEnd;
    const first = await this.clusterAt(this.firstCluster, size);
    let best = first ?? { pos: this.firstCluster, ts: 0 };
    if (!first || first.ts > target) return best;
    let lo = best.pos + 1;
    let hi = size;
    for (let i = 0; i < 24 && hi - lo > 16 * 1024; i++) {
      const mid = Math.floor((lo + hi) / 2);
      const c = await this.clusterAt(mid, size);
      if (!c || c.pos >= hi) {
        hi = mid;
      } else if (c.ts <= target) {
        best = c;
        lo = c.pos + 1;
      } else {
        hi = mid;
      }
    }
    return best;
  }

  /** First cluster that starts at or after `from`, found by scanning for its id. */
  private async clusterAt(from: number, limit: number): Promise<{ pos: number; ts: number } | null> {
    const window = 256 * 1024;
    for (let off = from; off < limit; off += window - 16) {
      const buf = await this.source.read(off, Math.min(window, limit - off));
      for (let i = 0; i + 4 < buf.length; i++) {
        if (buf[i] !== 0x1f || buf[i + 1] !== 0x43 || buf[i + 2] !== 0xb6 || buf[i + 3] !== 0x75) continue;
        const sz = readVint(buf, i + 4);
        if (!sz) continue;
        const childAt = i + 4 + sz.length;
        if (buf[childAt] !== 0xe7) continue;
        const ts = readVint(buf, childAt + 1);
        if (!ts || childAt + 1 + ts.length + ts.value > buf.length) continue;
        return { pos: off + i, ts: readUInt(buf, childAt + 1 + ts.length, childAt + 1 + ts.length + ts.value) };
      }
    }
    return null;
  }
}

function forEach(buf: Uint8Array, start: number, end: number, fn: (id: number, s: number, e: number) => void): void {
  let p = start;
  while (p < end) {
    const h = readHeader(buf, p);
    if (!h || h.size < 0) return;
    const s = p + h.header;
    const e = Math.min(s + h.size, end);
    fn(h.id, s, e);
    p = s + h.size;
  }
}

function mapCodec(track: Track, codecId: string, priv: Uint8Array | undefined): void {
  switch (codecId) {
    case "V_MPEG4/ISO/AVC":
      track.codec = "h264";
      track.fourcc = "avc1";
      track.config = priv;
      break;
    case "V_MPEGH/ISO/HEVC":
      track.codec = "hevc";
      track.fourcc = "hvc1";
      track.config = priv;
      break;
    case "V_VP9":
      track.codec = "vp9";
      break;
    case "V_AV1":
      track.codec = "av1";
      track.config = priv;
      break;
    case "A_AAC":
      track.codec = "aac";
      track.config = priv;
      break;
    case "A_AC3":
      track.codec = "ac3";
      break;
    case "A_EAC3":
      track.codec = "eac3";
      break;
    case "A_MPEG/L3":
      track.codec = "mp3";
      break;
    case "A_OPUS":
      track.codec = "opus";
      track.config = priv ? dopsFromOpusHead(priv) : undefined;
      break;
    case "S_TEXT/UTF8":
      track.codec = "srt";
      break;
    case "S_TEXT/ASS":
    case "S_TEXT/SSA":
      track.codec = "ass";
      break;
    case "S_TEXT/WEBVTT":
      track.codec = "webvtt";
      break;
  }
}

/** OpusHead (RFC 7845, little-endian) to the dOps box payload (big-endian, version 0). */
function dopsFromOpusHead(h: Uint8Array): Uint8Array | undefined {
  if (h.length < 19 || String.fromCharCode(...h.subarray(0, 8)) !== "OpusHead") return undefined;
  const out = new Uint8Array(h.length - 8);
  out[0] = 0; // version
  out[1] = h[9]; // channel count
  out[2] = h[11]; // pre-skip, to big-endian
  out[3] = h[10];
  out[4] = h[15]; // input sample rate
  out[5] = h[14];
  out[6] = h[13];
  out[7] = h[12];
  out[8] = h[17]; // output gain
  out[9] = h[16];
  out.set(h.subarray(18), 10); // mapping family and optional table
  return out;
}
