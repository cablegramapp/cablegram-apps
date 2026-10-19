import { ReadCache } from "./cache";
import { SourceError, type ByteSource } from "./types";

export interface ChunkedOptions {
  /** Bytes per request. Default 1 MiB. */
  chunkSize?: number;
  /** Bytes kept in memory. Default 64 MiB. */
  cacheBytes?: number;
  /** Bytes fetched ahead of the last read while playing. Default 8 MiB. */
  readAheadBytes?: number;
}

interface Inflight {
  index: number;
  promise: Promise<Uint8Array>;
  ctl: AbortController;
  background: boolean;
}

const noop = () => undefined;

export const MiB = 1024 * 1024;

/**
 * Reads a file as fixed-size aligned chunks with an LRU cache and sequential read-ahead. Never has more than one
 * chunk request in flight (a foreground read aborts a background read-ahead). Subclasses implement fetchChunk.
 */
export abstract class ChunkedSource implements ByteSource {
  size: number | null = null;

  protected readonly chunkSize: number;
  private readonly cache: ReadCache;
  private readonly readAheadChunks: number;
  private inflight: Inflight | null = null;
  private fg: Promise<unknown> = Promise.resolve();
  private prefetchGen = 0;
  private closed = false;

  constructor(opts: ChunkedOptions = {}) {
    this.chunkSize = opts.chunkSize ?? MiB;
    this.cache = new ReadCache(opts.cacheBytes ?? 64 * MiB);
    this.readAheadChunks = Math.floor((opts.readAheadBytes ?? 8 * MiB) / this.chunkSize);
  }

  /** Bytes of chunk `index` (a short or empty result means the end of the file). Must honour `signal`. */
  protected abstract fetchChunk(index: number, signal: AbortSignal): Promise<Uint8Array>;

  async open(): Promise<void> {
    await this.ensureChunk(0);
  }

  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array> {
    const run = this.fg.then(() => this.readNow(offset, length, signal));
    this.fg = run.catch(() => undefined);
    return run;
  }

  close(): void {
    this.closed = true;
    this.prefetchGen++;
    this.inflight?.ctl.abort();
    this.cache.clear();
  }

  private async readNow(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array> {
    if (signal?.aborted) throw new SourceError("source_aborted", "read aborted");
    if (this.size !== null) length = Math.max(0, Math.min(length, this.size - offset));
    if (length === 0) return new Uint8Array(0);
    this.prefetchGen++; // stop the read-ahead loop; ensureChunk adopts or aborts what is in flight
    const first = Math.floor(offset / this.chunkSize);
    const last = Math.floor((offset + length - 1) / this.chunkSize);
    const out = new Uint8Array(length);
    let filled = 0;
    for (let i = first; i <= last; i++) {
      const chunk = await this.ensureChunk(i, signal);
      const from = i === first ? offset - i * this.chunkSize : 0;
      const part = chunk.subarray(from, Math.min(chunk.length, from + length - filled));
      out.set(part, filled);
      filled += part.length;
      if (chunk.length < this.chunkSize) break; // end of file
    }
    this.startPrefetch(last + 1);
    return filled === length ? out : out.subarray(0, filled);
  }

  private async ensureChunk(index: number, signal?: AbortSignal): Promise<Uint8Array> {
    const cached = this.cache.get(index);
    if (cached) return cached;
    if (this.inflight && this.inflight.index === index) {
      this.inflight.background = false;
      return this.inflight.promise;
    }
    if (this.inflight) {
      this.inflight.ctl.abort();
      await this.inflight.promise.catch(() => undefined);
    }
    return this.startFetch(index, false, signal);
  }

  private startFetch(index: number, background: boolean, outer?: AbortSignal): Promise<Uint8Array> {
    const ctl = new AbortController();
    if (outer) {
      if (outer.aborted) ctl.abort();
      else outer.addEventListener("abort", () => ctl.abort());
    }
    const promise = this.fetchChunk(index, ctl.signal).then((chunk) => {
      this.cache.set(index, chunk);
      return chunk;
    });
    const entry: Inflight = { index, promise, ctl, background };
    this.inflight = entry;
    const clear = () => {
      if (this.inflight === entry) this.inflight = null;
    };
    promise.then(clear, clear);
    return promise;
  }

  private startPrefetch(from: number): void {
    if (this.closed || this.readAheadChunks <= 0) return;
    const gen = ++this.prefetchGen;
    void (async () => {
      for (let i = from; i < from + this.readAheadChunks; i++) {
        if (gen !== this.prefetchGen || this.closed) return;
        if (this.size !== null && i * this.chunkSize >= this.size) return;
        if (this.cache.has(i)) continue;
        if (this.inflight) {
          // something else is loading: wait for it (our handler runs after the one that clears `inflight`), then re-check
          await this.inflight.promise.then(noop, noop);
          i--;
          continue;
        }
        try {
          await this.startFetch(i, true);
        } catch {
          return; // aborted or failed: the next foreground read reports it
        }
      }
    })();
  }
}
