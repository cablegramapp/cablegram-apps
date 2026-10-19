import { Reader } from "../bytes";
import { MediaError } from "../types";

/** Flat per-sample index for one track, built from the sample tables (stbl). */
export interface SampleIndex {
  count: number;
  offset: Float64Array;
  size: Uint32Array;
  /** Decode time in ticks, edit-list shift already applied. */
  dts: Float64Array;
  /** Composition offset in ticks (pts = dts + cto). */
  cto: Int32Array;
  /** 1 = sync sample. */
  sync: Uint8Array;
  /** Ticks to the next sample (last one repeats the previous). */
  duration: Uint32Array;
}

export interface StblBoxes {
  stts?: Uint8Array;
  ctts?: Uint8Array;
  stsz?: Uint8Array;
  stz2?: Uint8Array;
  stsc?: Uint8Array;
  stco?: Uint8Array;
  co64?: Uint8Array;
  stss?: Uint8Array;
}

/** Each argument is the payload of the box including its 4-byte version/flags. */
export function buildSampleIndex(b: StblBoxes, shift: number): SampleIndex {
  if (!b.stts || !b.stsc || (!b.stsz && !b.stz2) || (!b.stco && !b.co64)) {
    throw new MediaError("malformed", "mp4: incomplete sample table");
  }

  // sizes
  let size: Uint32Array;
  if (b.stsz) {
    const r = new Reader(b.stsz, 4);
    const constant = r.u32();
    const count = r.u32();
    size = new Uint32Array(count);
    if (constant) size.fill(constant);
    else for (let i = 0; i < count; i++) size[i] = r.u32();
  } else {
    const r = new Reader(b.stz2!, 4);
    r.skip(3);
    const field = r.u8();
    const count = r.u32();
    size = new Uint32Array(count);
    for (let i = 0; i < count; i++) {
      if (field === 16) size[i] = r.u16();
      else if (field === 8) size[i] = r.u8();
      else {
        const byte = r.buf[r.pos + (i >> 1)];
        size[i] = i & 1 ? byte & 15 : byte >> 4;
      }
    }
  }
  const n = size.length;

  // times
  const dts = new Float64Array(n);
  const duration = new Uint32Array(n);
  {
    const r = new Reader(b.stts, 4);
    const entries = r.u32();
    let t = 0;
    let i = 0;
    for (let e = 0; e < entries && i < n; e++) {
      const c = r.u32();
      const d = r.u32();
      for (let k = 0; k < c && i < n; k++, i++) {
        dts[i] = t - shift;
        duration[i] = d;
        t += d;
      }
    }
    if (i < n) throw new MediaError("malformed", "mp4: stts shorter than stsz");
  }

  const cto = new Int32Array(n);
  if (b.ctts) {
    const r = new Reader(b.ctts, 4);
    const entries = r.u32();
    let i = 0;
    for (let e = 0; e < entries && i < n; e++) {
      const c = r.u32();
      const o = r.i32();
      for (let k = 0; k < c && i < n; k++, i++) cto[i] = o;
    }
  }

  const sync = new Uint8Array(n);
  if (b.stss) {
    const r = new Reader(b.stss, 4);
    const c = r.u32();
    for (let i = 0; i < c; i++) {
      const s = r.u32() - 1;
      if (s >= 0 && s < n) sync[s] = 1;
    }
  } else {
    sync.fill(1);
  }

  // offsets: chunk table + samples-per-chunk
  let chunkOffsets: Float64Array;
  {
    const r = new Reader((b.stco ?? b.co64)!, 4);
    const c = r.u32();
    chunkOffsets = new Float64Array(c);
    for (let i = 0; i < c; i++) chunkOffsets[i] = b.stco ? r.u32() : r.u64();
  }
  const offset = new Float64Array(n);
  {
    const r = new Reader(b.stsc, 4);
    const entries = r.u32();
    const first = new Uint32Array(entries);
    const per = new Uint32Array(entries);
    for (let e = 0; e < entries; e++) {
      first[e] = r.u32();
      per[e] = r.u32();
      r.skip(4);
    }
    let sample = 0;
    for (let e = 0; e < entries; e++) {
      const lastChunk = e + 1 < entries ? first[e + 1] - 1 : chunkOffsets.length;
      for (let chunk = first[e]; chunk <= lastChunk && sample < n; chunk++) {
        let pos = chunkOffsets[chunk - 1];
        for (let k = 0; k < per[e] && sample < n; k++, sample++) {
          offset[sample] = pos;
          pos += size[sample];
        }
      }
    }
    if (sample < n) throw new MediaError("malformed", "mp4: stsc/stco do not cover every sample");
  }

  return { count: n, offset, size, dts, cto, sync, duration };
}

/** Index of the last sample with dts <= t, or -1. */
export function lastAtOrBefore(dts: Float64Array, count: number, t: number): number {
  let lo = 0;
  let hi = count - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (dts[mid] <= t) {
      ans = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return ans;
}
