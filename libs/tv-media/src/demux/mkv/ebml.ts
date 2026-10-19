/** EBML primitives. Numbers only; exact up to 2^53, which covers any file size. */

export const ID = {
  EBML: 0x1a45dfa3,
  Segment: 0x18538067,
  SeekHead: 0x114d9b74,
  Seek: 0x4dbb,
  SeekID: 0x53ab,
  SeekPosition: 0x53ac,
  Info: 0x1549a966,
  TimestampScale: 0x2ad7b1,
  Duration: 0x4489,
  Tracks: 0x1654ae6b,
  TrackEntry: 0xae,
  TrackNumber: 0xd7,
  TrackType: 0x83,
  CodecID: 0x86,
  CodecPrivate: 0x63a2,
  Language: 0x22b59c,
  LanguageBCP47: 0x22b59d,
  Name: 0x536e,
  DefaultDuration: 0x23e383,
  Video: 0xe0,
  PixelWidth: 0xb0,
  PixelHeight: 0xba,
  Audio: 0xe1,
  SamplingFrequency: 0xb5,
  Channels: 0x9f,
  ContentEncodings: 0x6d80,
  ContentEncoding: 0x6240,
  ContentCompression: 0x5034,
  ContentCompAlgo: 0x4254,
  ContentCompSettings: 0x4255,
  Cues: 0x1c53bb6b,
  CuePoint: 0xbb,
  CueTime: 0xb3,
  CueTrackPositions: 0xb7,
  CueTrack: 0xf7,
  CueClusterPosition: 0xf1,
  Cluster: 0x1f43b675,
  Timestamp: 0xe7,
  SimpleBlock: 0xa3,
  BlockGroup: 0xa0,
  Block: 0xa1,
  BlockDuration: 0x9b,
  ReferenceBlock: 0xfb,
  Tags: 0x1254c367,
  Chapters: 0x1043a770,
  Attachments: 0x1941a469,
  Void: 0xec,
} as const;

/** Level-1 elements: seeing one of these inside a cluster means an unknown-size cluster has ended. */
export const LEVEL1 = new Set<number>([ID.SeekHead, ID.Info, ID.Tracks, ID.Cues, ID.Cluster, ID.Tags, ID.Chapters, ID.Attachments]);

export interface Header {
  id: number;
  /** Payload size, or -1 when unknown. */
  size: number;
  /** Bytes taken by id + size. */
  header: number;
}

/** Element header at p, or null when the buffer ends inside it or the bytes are not a valid header. */
export function readHeader(b: Uint8Array, p: number): Header | null {
  if (p >= b.length) return null;
  const first = b[p];
  if (first === 0) return null;
  const idLen = first >= 0x80 ? 1 : first >= 0x40 ? 2 : first >= 0x20 ? 3 : first >= 0x10 ? 4 : 0;
  if (idLen === 0 || p + idLen >= b.length) return null;
  let id = 0;
  for (let i = 0; i < idLen; i++) id = id * 256 + b[p + i];
  const v = readVint(b, p + idLen);
  if (!v) return null;
  return { id, size: v.value, header: idLen + v.length };
}

/** Variable-length integer with the length marker removed; value -1 means "unknown size" (all ones). */
export function readVint(b: Uint8Array, p: number): { value: number; length: number } | null {
  if (p >= b.length) return null;
  const first = b[p];
  if (first === 0) return null;
  let length = 1;
  while (!(first & (0x80 >> (length - 1)))) length++;
  if (p + length > b.length) return null;
  let value = first & (0xff >> length);
  let allOnes = value === 0xff >> length;
  for (let i = 1; i < length; i++) {
    value = value * 256 + b[p + i];
    if (b[p + i] !== 0xff) allOnes = false;
  }
  return { value: allOnes ? -1 : value, length };
}

export function readUInt(b: Uint8Array, start: number, end: number): number {
  let v = 0;
  for (let i = start; i < end; i++) v = v * 256 + b[i];
  return v;
}

export function readFloat(b: Uint8Array, start: number, end: number): number {
  const view = new DataView(b.buffer, b.byteOffset + start, end - start);
  return end - start === 4 ? view.getFloat32(0) : end - start === 8 ? view.getFloat64(0) : 0;
}

export function readString(b: Uint8Array, start: number, end: number): string {
  let e = end;
  while (e > start && b[e - 1] === 0) e--;
  let s = "";
  for (let i = start; i < e; i++) s += String.fromCharCode(b[i]);
  try {
    return decodeURIComponent(escape(s)); // UTF-8
  } catch {
    return s;
  }
}
