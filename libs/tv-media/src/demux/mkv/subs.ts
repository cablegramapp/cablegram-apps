import { utf8 } from "../bytes";

const clean = (s: string) => s.replace(/\r\n?/g, "\n").trim();

export function srtText(data: Uint8Array): string {
  return clean(utf8(data));
}

/** ASS/SSA block: "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text"; Text may contain commas. */
export function assText(data: Uint8Array): string {
  const line = utf8(data);
  let idx = -1;
  for (let i = 0; i < 8; i++) {
    idx = line.indexOf(",", idx + 1);
    if (idx < 0) return "";
  }
  return clean(
    line
      .slice(idx + 1)
      .replace(/\{[^}]*\}/g, "")
      .replace(/\\[Nn]/g, "\n")
      .replace(/\\h/g, " "),
  );
}

/** WebVTT block: optional identifier + settings lines are carried in BlockAdditions in Matroska; the block is the text. */
export function vttText(data: Uint8Array): string {
  const lines = clean(utf8(data)).split("\n");
  // Matroska stores "settings\nidentifier\npayload"; payload is everything from the third line on.
  return lines.length >= 3 ? lines.slice(2).join("\n") : lines.join("\n");
}
