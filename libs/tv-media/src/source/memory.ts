import type { ByteSource } from "./types";

/** A file already in memory (a bundled clip, a test fixture). */
export class MemorySource implements ByteSource {
  readonly size: number;

  constructor(private readonly bytes: Uint8Array) {
    this.size = bytes.length;
  }

  async open(): Promise<void> {}

  async read(offset: number, length: number): Promise<Uint8Array> {
    return this.bytes.subarray(offset, Math.min(this.bytes.length, offset + length));
  }

  close(): void {}
}
