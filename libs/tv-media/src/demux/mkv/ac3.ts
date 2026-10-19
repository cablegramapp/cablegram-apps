/** Builds dac3 / dec3 payloads from the first frame, since Matroska carries no codec configuration for AC-3. */

class Bits {
  pos = 0;
  constructor(private readonly b: Uint8Array) {}
  read(n: number): number {
    let v = 0;
    for (let i = 0; i < n; i++) {
      const byte = this.b[this.pos >> 3] ?? 0;
      v = (v << 1) | ((byte >> (7 - (this.pos & 7))) & 1);
      this.pos++;
    }
    return v >>> 0;
  }
}

function pack(fields: Array<[number, number]>): Uint8Array {
  let bits = 0;
  let n = 0;
  for (const [v, w] of fields) {
    bits = (bits << w) | v;
    n += w;
  }
  const bytes = Math.ceil(n / 8);
  bits <<= bytes * 8 - n;
  return Uint8Array.from({ length: bytes }, (_, i) => (bits >>> ((bytes - 1 - i) * 8)) & 255);
}

export function dac3FromFrame(frame: Uint8Array): Uint8Array | null {
  if (frame.length < 8 || frame[0] !== 0x0b || frame[1] !== 0x77) return null;
  const r = new Bits(frame);
  r.read(16); // syncword
  r.read(16); // crc1
  const fscod = r.read(2);
  const frmsizecod = r.read(6);
  const bsid = r.read(5);
  const bsmod = r.read(3);
  const acmod = r.read(3);
  if (acmod & 1 && acmod !== 1) r.read(2);
  if (acmod & 4) r.read(2);
  if (acmod === 2) r.read(2);
  const lfeon = r.read(1);
  return pack([
    [fscod, 2],
    [bsid, 5],
    [bsmod, 3],
    [acmod, 3],
    [lfeon, 1],
    [frmsizecod >> 1, 5],
    [0, 5],
  ]);
}

const SAMPLE_RATES = [48000, 44100, 32000];
const BLOCKS = [1, 2, 3, 6];

export function dec3FromFrame(frame: Uint8Array): Uint8Array | null {
  if (frame.length < 8 || frame[0] !== 0x0b || frame[1] !== 0x77) return null;
  const r = new Bits(frame);
  r.read(16);
  r.read(2); // strmtyp
  r.read(3); // substreamid
  const frmsiz = r.read(11);
  let fscod = r.read(2);
  let blocks: number;
  if (fscod === 3) {
    r.read(2); // fscod2: half rate; keep fscod 3 out of the data-rate calculation
    blocks = 6;
  } else blocks = BLOCKS[r.read(2)];
  const acmod = r.read(3);
  const lfeon = r.read(1);
  const bsid = r.read(5);
  if (fscod === 3) fscod = 0;
  const rate = SAMPLE_RATES[fscod] ?? 48000;
  const dataRate = Math.round((((frmsiz + 1) * 2 * 8 * rate) / (blocks * 256)) / 1000);
  return pack([
    [dataRate, 13],
    [0, 3], // num_ind_sub - 1
    [fscod, 2],
    [bsid, 5],
    [0, 1],
    [0, 1],
    [0, 3], // bsmod
    [acmod, 3],
    [lfeon, 1],
    [0, 3],
    [0, 4], // num_dep_sub
    [0, 1],
  ]);
}
