import { concat } from "../demux/bytes";
import { MediaError, type Sample, type Track } from "../demux/types";

const u8 = (...v: number[]) => Uint8Array.from(v);
function u16(v: number): Uint8Array {
  return u8((v >> 8) & 255, v & 255);
}
function u32(v: number): Uint8Array {
  return u8((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
}
function u64(v: number): Uint8Array {
  return concat([u32(Math.floor(v / 4294967296)), u32(v >>> 0)]);
}
const zeros = (n: number) => new Uint8Array(n);
function str(s: string): Uint8Array {
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function box(type: string, ...payload: Uint8Array[]): Uint8Array {
  const body = concat(payload);
  return concat([u32(8 + body.length), str(type), body]);
}
const fullBox = (type: string, version: number, flags: number, ...payload: Uint8Array[]) =>
  box(type, u8(version, (flags >> 16) & 255, (flags >> 8) & 255, flags & 255), ...payload);

const MATRIX = concat([u32(0x10000), zeros(12), u32(0x10000), zeros(12), u32(0x40000000)]);

/** ftyp + moov with an empty sample table and mvex: the init segment for one SourceBuffer. */
export function buildInitSegment(track: Track): Uint8Array {
  const isVideo = track.kind === "video";
  if (track.kind === "subtitle") throw new MediaError("unsupported_codec", "subtitles are not muxed");

  const ftyp = box("ftyp", str("iso6"), u32(1), str("iso6"), str("mp41"), str("isom"), str("msdh"));
  const mvhd = fullBox("mvhd", 0, 0, u32(0), u32(0), u32(track.timescale), u32(0), u32(0x10000), u16(0x100), zeros(10), MATRIX, zeros(24), u32(track.id + 1));
  const tkhd = fullBox(
    "tkhd",
    0,
    3,
    u32(0),
    u32(0),
    u32(track.id),
    zeros(4),
    u32(0),
    zeros(8),
    u16(0),
    u16(0),
    u16(isVideo ? 0 : 0x100),
    zeros(2),
    MATRIX,
    u32((track.width ?? 0) * 65536),
    u32((track.height ?? 0) * 65536),
  );
  const mdhd = fullBox("mdhd", 0, 0, u32(0), u32(0), u32(track.timescale), u32(0), u16(0x55c4), u16(0));
  const hdlr = fullBox("hdlr", 0, 0, u32(0), str(isVideo ? "vide" : "soun"), zeros(12), str(isVideo ? "VideoHandler" : "SoundHandler"), u8(0));
  const dinf = box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1)));
  const mhd = isVideo ? fullBox("vmhd", 0, 1, zeros(8)) : fullBox("smhd", 0, 0, zeros(4));
  const stbl = box(
    "stbl",
    fullBox("stsd", 0, 0, u32(1), sampleEntry(track)),
    fullBox("stts", 0, 0, u32(0)),
    fullBox("stsc", 0, 0, u32(0)),
    fullBox("stsz", 0, 0, u32(0), u32(0)),
    fullBox("stco", 0, 0, u32(0)),
  );
  const trak = box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", mhd, dinf, stbl)));
  const mvex = box("mvex", fullBox("trex", 0, 0, u32(track.id), u32(1), u32(0), u32(0), u32(0)));
  return concat([ftyp, box("moov", mvhd, trak, mvex)]);
}

function need(track: Track): Uint8Array {
  if (!track.config) throw new MediaError("unsupported_codec", `${track.codec} track without codec configuration`);
  return track.config;
}

function sampleEntry(track: Track): Uint8Array {
  if (track.kind === "video") {
    let type: string;
    let cfg: Uint8Array;
    if (track.codec === "h264") {
      type = track.fourcc === "avc3" ? "avc3" : "avc1";
      cfg = box("avcC", need(track));
    } else if (track.codec === "hevc") {
      type = track.fourcc === "hev1" ? "hev1" : "hvc1";
      cfg = box("hvcC", need(track));
    } else throw new MediaError("unsupported_codec", `cannot mux ${track.codec}`);
    return box(
      type,
      zeros(6),
      u16(1),
      zeros(16),
      u16(track.width ?? 0),
      u16(track.height ?? 0),
      u32(0x480000),
      u32(0x480000),
      zeros(4),
      u16(1),
      zeros(32),
      u16(0x18),
      u16(0xffff),
      cfg,
    );
  }

  let type: string;
  let cfg: Uint8Array;
  switch (track.codec) {
    case "aac":
      type = "mp4a";
      cfg = esds(0x40, need(track), track.id);
      break;
    case "mp3":
      type = "mp4a";
      cfg = esds(0x6b, undefined, track.id);
      break;
    case "ac3":
      type = "ac-3";
      cfg = box("dac3", need(track));
      break;
    case "eac3":
      type = "ec-3";
      cfg = box("dec3", need(track));
      break;
    case "opus":
      type = "Opus";
      cfg = box("dOps", need(track));
      break;
    default:
      throw new MediaError("unsupported_codec", `cannot mux ${track.codec}`);
  }
  const rate = track.codec === "opus" ? 48000 : track.sampleRate ?? 48000;
  return box(type, zeros(6), u16(1), zeros(8), u16(track.channels ?? 2), u16(16), zeros(4), u32(Math.min(rate, 65535) * 65536), cfg);
}

function descr(tag: number, ...payload: Uint8Array[]): Uint8Array {
  const body = concat(payload);
  return concat([u8(tag, 0x80, 0x80, 0x80, body.length), body]);
}

function esds(objectType: number, asc: Uint8Array | undefined, id: number): Uint8Array {
  const dec = descr(0x04, u8(objectType, 0x15), zeros(3), zeros(4), zeros(4), ...(asc ? [descr(0x05, asc)] : []));
  const es = descr(0x03, u16(id), u8(0), dec, descr(0x06, u8(2)));
  return fullBox("esds", 0, 0, es);
}

const SYNC = 0x02000000;
const NON_SYNC = 0x01010000;

/**
 * moof + mdat for consecutive samples of one track. `offset` ticks are added to every dts and pts so the
 * first decode time is not negative (tfdt is unsigned); callers pick one offset in seconds for all tracks.
 */
export function buildFragment(track: Track, samples: Sample[], sequence: number, offset = 0): Uint8Array {
  if (samples.length === 0) throw new MediaError("malformed", "empty fragment");
  const baseDts = samples[0].dts + offset;
  if (baseDts < 0) throw new MediaError("malformed", `negative decode time ${baseDts}; raise the timestamp offset`);

  const entries: Uint8Array[] = [];
  let dataSize = 0;
  for (const s of samples) {
    entries.push(u32(s.duration), u32(s.data.length), u32(s.keyframe ? SYNC : NON_SYNC), u32(s.pts - s.dts));
    dataSize += s.data.length;
  }
  const mfhd = fullBox("mfhd", 0, 0, u32(sequence));
  const tfhd = fullBox("tfhd", 0, 0x020000, u32(track.id)); // default-base-is-moof
  const tfdt = fullBox("tfdt", 1, 0, u64(baseDts));
  // flags: data-offset | sample-duration | sample-size | sample-flags | sample-composition-time-offset; v1 = signed offsets
  const trunHead = [u32(samples.length), u32(0)];
  const trunLen = 8 + 4 + 4 + 4 + 16 * samples.length;
  const traf = box("traf", tfhd, tfdt, fullBox("trun", 1, 0x000f01, ...trunHead, ...entries));
  const moofLen = 8 + mfhd.length + traf.length;
  // patch data_offset (first 4 bytes after sample_count) to point past moof + mdat header
  const trunStart = moofLen - trunLen;
  const moof = box("moof", mfhd, traf);
  new DataView(moof.buffer, moof.byteOffset).setUint32(trunStart + 8 + 4 + 4, moofLen + 8);
  const mdat = concat([u32(8 + dataSize), str("mdat")]);
  return concat([moof, mdat, ...samples.map((s) => s.data)]);
}
