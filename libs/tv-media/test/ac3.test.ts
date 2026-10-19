import { describe, expect, it } from "vitest";
import { dec3FromFrame } from "../src/demux/mkv/ac3";

/** Writes [value, width] fields MSB first. */
function bitsOf(fields: Array<[number, number]>): Uint8Array {
  const out = new Uint8Array(16);
  let pos = 0;
  for (const [v, w] of fields) for (let i = w - 1; i >= 0; i--, pos++) if ((v >>> i) & 1) out[pos >> 3] |= 0x80 >> (pos & 7);
  return out;
}

describe("dec3FromFrame", () => {
  it("keeps the data rate and every field of a 384 kbit/s 5.1 E-AC-3 frame", () => {
    const frame = bitsOf([
      [0x0b77, 16], [0, 2], [0, 3], [767, 11], // strmtyp, substreamid, frmsiz: 1536 bytes
      [0, 2], [3, 2], [7, 3], [1, 1], [16, 5], // 48 kHz, 6 blocks, 3/2, LFE, bsid 16
    ]);
    const dec3 = dec3FromFrame(frame)!;
    expect(dec3).toHaveLength(5);
    const field = (start: number, width: number) => {
      let v = 0;
      for (let i = start; i < start + width; i++) v = v * 2 + ((dec3[i >> 3] >> (7 - (i & 7))) & 1);
      return v;
    };
    expect(field(0, 13)).toBe(384); // data_rate
    expect(field(13, 3)).toBe(0); // num_ind_sub
    expect(field(16, 2)).toBe(0); // fscod
    expect(field(18, 5)).toBe(16); // bsid
    expect(field(28, 3)).toBe(7); // acmod
    expect(field(31, 1)).toBe(1); // lfeon
  });
});
