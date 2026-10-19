import { MediaError, type Track } from "../demux/types";

const hex2 = (n: number) => (n < 16 ? "0" : "") + n.toString(16).toUpperCase();

/** RFC 6381 codec string for MediaSource.isTypeSupported / addSourceBuffer. */
export function codecString(track: Track): string {
  const cfg = track.config;
  switch (track.codec) {
    case "h264": {
      if (!cfg || cfg.length < 4) throw new MediaError("unsupported_codec", "h264 without avcC");
      return `${track.fourcc === "avc3" ? "avc3" : "avc1"}.${hex2(cfg[1])}${hex2(cfg[2])}${hex2(cfg[3])}`;
    }
    case "hevc": {
      if (!cfg || cfg.length < 13) throw new MediaError("unsupported_codec", "hevc without hvcC");
      const space = ["", "A", "B", "C"][cfg[1] >> 6];
      const tier = cfg[1] & 0x20 ? "H" : "L";
      const profile = cfg[1] & 0x1f;
      // general_profile_compatibility_flags, bit-reversed, as hex without leading zeros
      let flags = (cfg[2] << 24) | (cfg[3] << 16) | (cfg[4] << 8) | cfg[5];
      let rev = 0;
      for (let i = 0; i < 32; i++) {
        rev = (rev << 1) | (flags & 1);
        flags >>>= 1;
      }
      const constraints: string[] = [];
      let last = 5;
      while (last >= 0 && cfg[6 + last] === 0) last--;
      for (let i = 0; i <= last; i++) constraints.push(cfg[6 + i].toString(16).toUpperCase());
      const parts = [`${track.fourcc === "hev1" ? "hev1" : "hvc1"}.${space}${profile}`, (rev >>> 0).toString(16).toUpperCase(), `${tier}${cfg[12]}`, ...constraints];
      return parts.join(".");
    }
    case "aac": {
      if (!cfg || cfg.length < 2) throw new MediaError("unsupported_codec", "aac without AudioSpecificConfig");
      let aot = cfg[0] >> 3;
      if (aot === 31) aot = 32 + (((cfg[0] & 7) << 3) | (cfg[1] >> 5));
      return `mp4a.40.${aot}`;
    }
    case "mp3":
      return "mp4a.6B";
    case "ac3":
      return "ac-3";
    case "eac3":
      return "ec-3";
    case "opus":
      return "opus";
    default:
      throw new MediaError("unsupported_codec", `no fragmented-MP4 mapping for ${track.codec}`);
  }
}

export function mimeType(track: Track): string {
  return `${track.kind === "video" ? "video" : "audio"}/mp4; codecs="${codecString(track)}"`;
}
