import { Reader } from "../bytes";

export interface Box {
  type: string;
  /** Absolute offsets in the buffer. */
  start: number;
  dataStart: number;
  end: number;
}

/** Child boxes in [start, end) of buf. A size of 0 runs to `end`. Stops quietly at a truncated box. */
export function* childBoxes(buf: Uint8Array, start: number, end: number): Generator<Box> {
  const r = new Reader(buf, start, end);
  while (r.left >= 8) {
    const boxStart = r.pos;
    let size = r.u32();
    const type = r.fourcc();
    let header = 8;
    if (size === 1) {
      if (r.left < 8) return;
      size = r.u64();
      header = 16;
    } else if (size === 0) {
      size = end - boxStart;
    }
    if (size < header || boxStart + size > end) return;
    yield { type, start: boxStart, dataStart: boxStart + header, end: boxStart + size };
    r.pos = boxStart + size;
  }
}

export function findBox(buf: Uint8Array, parent: Box | { dataStart: number; end: number }, type: string): Box | undefined {
  for (const b of childBoxes(buf, parent.dataStart, parent.end)) if (b.type === type) return b;
  return undefined;
}

export function findPath(buf: Uint8Array, parent: Box, ...types: string[]): Box | undefined {
  let cur: Box | undefined = parent;
  for (const t of types) {
    if (!cur) return undefined;
    cur = findBox(buf, cur, t);
  }
  return cur;
}
