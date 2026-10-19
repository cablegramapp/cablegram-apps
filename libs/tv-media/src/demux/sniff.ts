export type Container = "mp4" | "mkv" | null;

export function sniffContainer(head: Uint8Array): Container {
  if (head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3) return "mkv";
  if (head.length >= 8) {
    const type = String.fromCharCode(head[4], head[5], head[6], head[7]);
    if (["ftyp", "moov", "mdat", "free", "skip", "wide", "styp", "moof"].includes(type)) return "mp4";
  }
  return null;
}
