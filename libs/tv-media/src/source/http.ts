import { ReadCache } from "./cache";
import { SourceError, type ByteSource } from "./types";

export interface HttpRangeOptions {
  headers?: Record<string, string>;
  /** Bytes per request. Default 1 MiB. */
  chunkSize?: number;
  /** Bytes kept in memory. Default 64 MiB. */
  cacheBytes?: number;
  /** Bytes fetched ahead of the last read while playing. Default 8 MiB. */
  readAheadBytes?: number;
  /** 429 retries before giving up. Default 4. */
  maxRetries?: number;
  /** Back-off per attempt in ms on 429 (attempt n waits n × this). Default 2000. */
  backoffMs?: number;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
}

interface Inflight {
  index: number;
  promise: Promise<Uint8Array>;
  ctl: AbortController;
  background: boolean;
}

const MiB = 1024 * 1024;

/**
 * HTTP range reader. Never has more than one request in flight, so one TV holds at most one relay stream.
 * The caller supplies the full URL (ticket and capability included).
 */
export class HttpRangeSource implements ByteSource {
  size: number | null = null;

  private readonly chunkSize: number;
  private readonly cache: ReadCache;
  private readonly readAheadChunks: number;
  private readonly maxRetries: number;
  private readonly backoffMs: number;
  private readonly doFetch: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;
  private inflight: Inflight | null = null;
  private fg: Promise<unknown> = Promise.resolve();
  private prefetchGen = 0;
  private closed = false;

  constructor(
    private readonly url: string,
    private readonly opts: HttpRangeOptions = {},
  ) {
    this.chunkSize = opts.chunkSize ?? MiB;
    this.cache = new ReadCache(opts.cacheBytes ?? 64 * MiB);
    this.readAheadChunks = Math.floor((opts.readAheadBytes ?? 8 * MiB) / this.chunkSize);
    this.maxRetries = opts.maxRetries ?? 4;
    this.backoffMs = opts.backoffMs ?? 2000;
    this.doFetch = opts.fetch ?? ((input, init) => fetch(input, init));
    this.sleep = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

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
        if (this.inflight) return; // a foreground read took over
        try {
          await this.startFetch(i, true);
        } catch {
          return; // aborted or failed: the next foreground read reports it
        }
      }
    })();
  }

  private async fetchChunk(index: number, signal: AbortSignal): Promise<Uint8Array> {
    const start = index * this.chunkSize;
    const end = start + this.chunkSize - 1;
    for (let attempt = 1; ; attempt++) {
      if (signal.aborted) throw new SourceError("source_aborted", "request aborted");
      let res: Response;
      try {
        res = await this.doFetch(this.url, {
          headers: { ...this.opts.headers, Range: `bytes=${start}-${end}` },
          signal,
        });
      } catch (e) {
        if (signal.aborted) throw new SourceError("source_aborted", "request aborted");
        throw new SourceError("source_network", `network error: ${(e as Error).message}`);
      }
      if (res.status === 416) {
        // Past the end of the file.
        if (this.size === null) this.size = start;
        return new Uint8Array(0);
      }
      if (res.status === 429 && attempt <= this.maxRetries) {
        await this.sleep(this.backoffMs * attempt);
        continue;
      }
      if (res.status !== 200 && res.status !== 206) {
        throw new SourceError("source_http", `HTTP ${res.status}`, res.status, await readErrorCode(res));
      }
      if (res.status === 200 && start > 0) {
        throw new SourceError("source_http", "server ignores Range requests", 200);
      }
      let body: Uint8Array;
      try {
        body = new Uint8Array(await res.arrayBuffer());
      } catch {
        if (signal.aborted) throw new SourceError("source_aborted", "request aborted");
        throw new SourceError("source_network", "connection lost while reading");
      }
      this.learnSize(res, start, body.length);
      return body.length > this.chunkSize ? body.subarray(0, this.chunkSize) : body;
    }
  }

  private learnSize(res: Response, start: number, got: number): void {
    const range = res.headers.get("Content-Range"); // bytes 0-1023/4096
    const total = range ? /\/(\d+)\s*$/.exec(range) : null;
    if (total) this.size = Number(total[1]);
    else if (res.status === 200) this.size = got;
    else if (this.size === null && got < this.chunkSize) this.size = start + got;
  }
}

async function readErrorCode(res: Response): Promise<string | undefined> {
  try {
    const body = JSON.parse(await res.text()) as { error?: unknown };
    return typeof body.error === "string" ? body.error : undefined;
  } catch {
    return undefined;
  }
}
