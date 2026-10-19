import { concat } from "../src/demux/bytes";
import { ID, readHeader } from "../src/demux/mkv/ebml";

/** Test-only EBML rewriting, to build MKV variants that ffmpeg will not produce (no mkvmerge needed). */

function idBytes(id: number): Uint8Array {
  const out: number[] = [];
  for (let v = id; v > 0; v = Math.floor(v / 256)) out.unshift(v % 256);
  return Uint8Array.from(out);
}

function sizeBytes(n: number, unknown = false): Uint8Array {
  if (unknown) return Uint8Array.from([0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]);
  let len = 1;
  while (n >= Math.pow(2, 7 * len) - 1) len++;
  const out = new Uint8Array(len);
  let v = n;
  for (let i = len - 1; i >= 0; i--) {
    out[i] = v % 256;
    v = Math.floor(v / 256);
  }
  out[0] |= 0x80 >> (len - 1);
  return out;
}

export function element(id: number, payload: Uint8Array, unknownSize = false): Uint8Array {
  return concat([idBytes(id), sizeBytes(payload.length, unknownSize), payload]);
}

interface El {
  id: number;
  start: number;
  bodyStart: number;
  end: number;
}

function children(buf: Uint8Array, start: number, end: number): El[] {
  const out: El[] = [];
  let p = start;
  while (p < end) {
    const h = readHeader(buf, p);
    if (!h || h.size < 0) break;
    out.push({ id: h.id, start: p, bodyStart: p + h.header, end: p + h.header + h.size });
    p += h.header + h.size;
  }
  return out;
}

function uint(v: number): Uint8Array {
  return v === 0 ? Uint8Array.from([0]) : Uint8Array.from(idBytes(v));
}

export interface Rewrite {
  /** Remove SeekHead and Cues, as in files written by streaming muxers. */
  dropIndex?: boolean;
  /** Write the Segment with unknown size. */
  unknownSegmentSize?: boolean;
  /** Strip these leading bytes from every frame of the track and declare ContentCompression header stripping. */
  stripHeader?: { track: number; prefix: Uint8Array };
}

export function rewriteMkv(src: Uint8Array, opts: Rewrite): Uint8Array {
  const top = children(src, 0, src.length);
  const header = top.find((e) => e.id === ID.EBML)!;
  const segment = top.find((e) => e.id === ID.Segment)!;
  const parts: Uint8Array[] = [src.subarray(header.start, header.end)];

  const body: Uint8Array[] = [];
  for (const el of children(src, segment.bodyStart, segment.end)) {
    const raw = src.subarray(el.start, el.end);
    if (el.id === ID.SeekHead || el.id === ID.Cues) {
      if (!opts.dropIndex) body.push(raw);
      continue;
    }
    if (el.id === ID.Tracks && opts.stripHeader) {
      body.push(rewriteTracks(src, el, opts.stripHeader));
    } else if (el.id === ID.Cluster && opts.stripHeader) {
      body.push(rewriteCluster(src, el, opts.stripHeader));
    } else body.push(raw);
  }
  parts.push(element(ID.Segment, concat(body), opts.unknownSegmentSize));
  return concat(parts);
}

function rewriteTracks(src: Uint8Array, tracks: El, strip: { track: number; prefix: Uint8Array }): Uint8Array {
  const entries: Uint8Array[] = [];
  for (const entry of children(src, tracks.bodyStart, tracks.end)) {
    if (entry.id !== ID.TrackEntry) {
      entries.push(src.subarray(entry.start, entry.end));
      continue;
    }
    const kids = children(src, entry.bodyStart, entry.end);
    const num = kids.find((k) => k.id === ID.TrackNumber)!;
    let n = 0;
    for (let i = num.bodyStart; i < num.end; i++) n = n * 256 + src[i];
    const parts = kids.map((k) => src.subarray(k.start, k.end));
    if (n === strip.track) {
      const compression = element(ID.ContentCompression, concat([element(ID.ContentCompAlgo, uint(3)), element(ID.ContentCompSettings, strip.prefix)]));
      const encoding = element(ID.ContentEncoding, concat([element(0x5031, uint(0)), element(0x5032, uint(1)), element(0x5033, uint(0)), compression]));
      parts.push(element(ID.ContentEncodings, encoding));
    }
    entries.push(element(ID.TrackEntry, concat(parts)));
  }
  return element(ID.Tracks, concat(entries));
}

function rewriteCluster(src: Uint8Array, cluster: El, strip: { track: number; prefix: Uint8Array }): Uint8Array {
  const parts: Uint8Array[] = [];
  for (const el of children(src, cluster.bodyStart, cluster.end)) {
    if (el.id !== ID.SimpleBlock) {
      parts.push(src.subarray(el.start, el.end));
      continue;
    }
    const track = src[el.bodyStart] & 0x7f; // single-byte track numbers in these fixtures
    if (track !== strip.track) {
      parts.push(src.subarray(el.start, el.end));
      continue;
    }
    const head = 4; // track(1) + timecode(2) + flags(1)
    const frame = src.subarray(el.bodyStart + head, el.end);
    for (let i = 0; i < strip.prefix.length; i++) {
      if (frame[i] !== strip.prefix[i]) throw new Error("frame does not start with the prefix to strip");
    }
    parts.push(element(ID.SimpleBlock, concat([src.subarray(el.bodyStart, el.bodyStart + head), frame.subarray(strip.prefix.length)])));
  }
  return element(ID.Cluster, concat(parts));
}
