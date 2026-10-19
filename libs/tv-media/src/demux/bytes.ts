/** Big-endian cursor over a Uint8Array. Numbers only (no BigInt), exact up to 2^53. */
export class Reader {
  readonly view: DataView;
  pos = 0;

  constructor(
    readonly buf: Uint8Array,
    pos = 0,
    readonly end = buf.length,
  ) {
    this.view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    this.pos = pos;
  }

  get left(): number {
    return this.end - this.pos;
  }
  u8(): number {
    return this.buf[this.pos++];
  }
  u16(): number {
    const v = this.view.getUint16(this.pos);
    this.pos += 2;
    return v;
  }
  u24(): number {
    const v = (this.buf[this.pos] << 16) | (this.buf[this.pos + 1] << 8) | this.buf[this.pos + 2];
    this.pos += 3;
    return v;
  }
  u32(): number {
    const v = this.view.getUint32(this.pos);
    this.pos += 4;
    return v;
  }
  i32(): number {
    const v = this.view.getInt32(this.pos);
    this.pos += 4;
    return v;
  }
  u64(): number {
    const hi = this.view.getUint32(this.pos);
    const lo = this.view.getUint32(this.pos + 4);
    this.pos += 8;
    return hi * 4294967296 + lo;
  }
  fourcc(): string {
    const s = String.fromCharCode(this.buf[this.pos], this.buf[this.pos + 1], this.buf[this.pos + 2], this.buf[this.pos + 3]);
    this.pos += 4;
    return s;
  }
  skip(n: number): void {
    this.pos += n;
  }
  bytes(n: number): Uint8Array {
    const b = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return b;
  }
}

/** UTF-8 decode that works on Chromium 53 (TextDecoder exists there, but keep a fallback for odd runtimes). */
export function utf8(bytes: Uint8Array): string {
  if (typeof TextDecoder !== "undefined") return new TextDecoder("utf-8").decode(bytes);
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return decodeURIComponent(escape(s));
}

export function concat(parts: Uint8Array[]): Uint8Array {
  let n = 0;
  for (const p of parts) n += p.length;
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}
