import { ChunkedSource, type ChunkedOptions } from "./chunked";
import { SourceError } from "./types";

export interface HttpRangeOptions extends ChunkedOptions {
  headers?: Record<string, string>;
  /** 429 retries before giving up. Default 4. */
  maxRetries?: number;
  /** Back-off per attempt in ms on 429 (attempt n waits n × this). Default 2000. */
  backoffMs?: number;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
}

/**
 * HTTP range reader. Never has more than one request in flight, so one TV holds at most one relay stream.
 * The caller supplies the full URL (ticket and capability included).
 */
export class HttpRangeSource extends ChunkedSource {
  private readonly maxRetries: number;
  private readonly backoffMs: number;
  private readonly doFetch: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(
    private readonly url: string,
    private readonly opts: HttpRangeOptions = {},
  ) {
    super(opts);
    this.maxRetries = opts.maxRetries ?? 4;
    this.backoffMs = opts.backoffMs ?? 2000;
    this.doFetch = opts.fetch ?? ((input, init) => fetch(input, init));
    this.sleep = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  protected async fetchChunk(index: number, signal: AbortSignal): Promise<Uint8Array> {
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
        // A server that ignored Range sends the whole file with 200: take the first chunk and drop the rest.
        body = res.status === 200 && res.body ? await readUpTo(res.body, this.chunkSize) : new Uint8Array(await res.arrayBuffer());
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
    else if (res.status === 200) {
      // the whole file came back (or its first chunk, if we stopped reading): Content-Length is the file size
      const length = Number(res.headers.get("Content-Length"));
      if (length > 0) this.size = length;
      else if (got < this.chunkSize) this.size = got;
    }
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

/** Reads at most `max` bytes of a stream, then cancels it so the rest is never downloaded. */
async function readUpTo(stream: ReadableStream<Uint8Array>, max: number): Promise<Uint8Array> {
  const reader = stream.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  while (total < max) {
    const { done, value } = await reader.read();
    if (done || !value) break;
    parts.push(value);
    total += value.length;
  }
  if (total >= max) await reader.cancel().catch(() => undefined);
  const out = new Uint8Array(Math.min(total, max));
  let at = 0;
  for (const part of parts) {
    const take = Math.min(part.length, out.length - at);
    out.set(part.subarray(0, take), at);
    at += take;
    if (at >= out.length) break;
  }
  return out;
}
