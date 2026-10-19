/** Chunk-aligned LRU keyed by chunk index, bounded by total bytes. */
export class ReadCache {
  private readonly chunks = new Map<number, Uint8Array>(); // insertion order = recency (oldest first)
  private bytes = 0;

  constructor(private readonly budgetBytes: number) {}

  has(index: number): boolean {
    return this.chunks.has(index);
  }

  get(index: number): Uint8Array | undefined {
    const chunk = this.chunks.get(index);
    if (chunk) {
      this.chunks.delete(index);
      this.chunks.set(index, chunk);
    }
    return chunk;
  }

  set(index: number, chunk: Uint8Array): void {
    const old = this.chunks.get(index);
    if (old) {
      this.bytes -= old.length;
      this.chunks.delete(index);
    }
    this.chunks.set(index, chunk);
    this.bytes += chunk.length;
    // Always keep the newest chunk, even if it alone is over budget.
    for (const key of this.chunks.keys()) {
      if (this.bytes <= this.budgetBytes || this.chunks.size <= 1) break;
      if (key === index) continue;
      this.bytes -= this.chunks.get(key)!.length;
      this.chunks.delete(key);
    }
  }

  clear(): void {
    this.chunks.clear();
    this.bytes = 0;
  }

  get size(): number {
    return this.bytes;
  }
}
