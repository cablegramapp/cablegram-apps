import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import type { ByteSource } from "../src/source/types";

export const FIXTURES = join(__dirname, "..", "fixtures");

export function fixturePath(name: string): string {
  return join(FIXTURES, name);
}

export function hasFixture(name: string): boolean {
  return existsSync(fixturePath(name));
}

/** ByteSource over a whole file in memory; counts reads so tests can assert access patterns. */
export class FileSource implements ByteSource {
  readonly size: number;
  readonly reads: Array<[number, number]> = [];
  private readonly data: Uint8Array;

  constructor(path: string) {
    const buf = readFileSync(path);
    this.data = new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    this.size = this.data.length;
  }
  async open(): Promise<void> {}
  async read(offset: number, length: number): Promise<Uint8Array> {
    this.reads.push([offset, length]);
    return this.data.subarray(offset, Math.min(this.data.length, offset + length));
  }
  close(): void {}
}

export interface ProbePacket {
  stream_index: number;
  pts?: number;
  dts?: number;
  duration?: number;
  size: string;
  flags: string;
  pos?: string;
}

export interface Probe {
  streams: Array<{ index: number; codec_name: string; codec_type: string; time_base: string }>;
  packets: ProbePacket[];
}

export function loadProbe(name: string): Probe {
  return JSON.parse(readFileSync(fixturePath(name + ".packets.json"), "utf8")) as Probe;
}
