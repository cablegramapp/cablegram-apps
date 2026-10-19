import type { ByteSource } from "../source/types";

export type TrackKind = "video" | "audio" | "subtitle";

/** Normalised codec names. Anything the demuxer does not know keeps its raw sample-entry or codec id. */
export type CodecName =
  | "h264"
  | "hevc"
  | "vp9"
  | "av1"
  | "aac"
  | "ac3"
  | "eac3"
  | "mp3"
  | "opus"
  | "srt"
  | "ass"
  | "webvtt"
  | "tx3g"
  | (string & {});

export interface Track {
  /** Unique within the file; the key used by Sample.trackId and the engine's selection calls. */
  id: number;
  kind: TrackKind;
  codec: CodecName;
  /** MP4 sample-entry fourcc (avc1, hvc1, mp4a, ...) when known. */
  fourcc?: string;
  /** Ticks per second for this track's Sample timestamps. */
  timescale: number;
  language?: string;
  name?: string;
  width?: number;
  height?: number;
  channels?: number;
  sampleRate?: number;
  /**
   * Codec configuration as the muxer needs it: avcC / hvcC / dac3 / dec3 / dOps box payloads,
   * or the AudioSpecificConfig for AAC. Absent for codecs without one.
   */
  config?: Uint8Array;
}

export interface SubtitleCue {
  /** Seconds. */
  start: number;
  end: number;
  text: string;
}

export interface Sample {
  trackId: number;
  /** Decode and presentation time in the track's timescale ticks (dts may be negative after edit-list shifting). */
  dts: number;
  pts: number;
  duration: number;
  keyframe: boolean;
  data: Uint8Array;
  /** Set on subtitle tracks instead of meaningful data; null for an empty (gap) sample. */
  cue?: SubtitleCue | null;
}

export interface MediaInfo {
  container: "mp4" | "mkv";
  /** Seconds; 0 when unknown. */
  duration: number;
  /**
   * Earliest decode time in seconds, 0 or negative (B-frame video starts its dts before pts 0). The muxer needs
   * dts >= 0, so the sink shifts the timeline by -minDts.
   */
  minDts: number;
  tracks: Track[];
}

export interface Demuxer {
  init(): Promise<MediaInfo>;
  /** Restrict next() to these tracks (default: all). Unselected tracks cost no bandwidth. */
  setEnabledTracks(ids: number[]): void;
  /** Moves to the keyframe at or before timeSec and returns its time in seconds. */
  seek(timeSec: number, signal?: AbortSignal): Promise<number>;
  /** Interleaved samples in decode order, about maxBytes of payload; null at the end. */
  next(maxBytes: number, signal?: AbortSignal): Promise<Sample[] | null>;
}

export type MediaErrorKind = "unsupported_container" | "unsupported_codec" | "malformed";

export class MediaError extends Error {
  constructor(
    readonly kind: MediaErrorKind,
    message: string,
  ) {
    super(message);
    this.name = "MediaError";
  }
}

export type { ByteSource };
