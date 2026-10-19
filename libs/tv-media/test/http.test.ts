import { describe, expect, it } from "vitest";
import { HttpRangeSource } from "../src/source/http";
import { SourceError } from "../src/source/types";

const CHUNK = 16;
const FILE = Uint8Array.from({ length: 100 }, (_, i) => i);

interface Fake {
  fetch: typeof fetch;
  ranges: string[];
  maxInFlight: () => number;
}

function fakeServer(script: (call: number) => Response | null = () => null): Fake {
  const ranges: string[] = [];
  let inFlight = 0;
  let peak = 0;
  const f = (async (_url: unknown, init?: RequestInit) => {
    const range = (init!.headers as Record<string, string>).Range;
    ranges.push(range);
    const scripted = script(ranges.length);
    if (scripted) return scripted;
    inFlight++;
    peak = Math.max(peak, inFlight);
    try {
      await new Promise((r) => setTimeout(r, 2));
      if (init!.signal?.aborted) throw new DOMException("aborted", "AbortError");
      const [a, b] = range.slice(6).split("-").map(Number);
      if (a >= FILE.length) return new Response("", { status: 416 });
      const body = FILE.slice(a, Math.min(b + 1, FILE.length));
      return new Response(body, {
        status: 206,
        headers: { "Content-Range": `bytes ${a}-${a + body.length - 1}/${FILE.length}` },
      });
    } finally {
      inFlight--;
    }
  }) as typeof fetch;
  return { fetch: f, ranges, maxInFlight: () => peak };
}

const make = (fake: Fake, extra = {}) =>
  new HttpRangeSource("http://x/f", { chunkSize: CHUNK, readAheadBytes: 0, fetch: fake.fetch, sleep: async () => {}, ...extra });

describe("HttpRangeSource", () => {
  it("learns the size and returns aligned and unaligned reads", async () => {
    const fake = fakeServer();
    const src = make(fake);
    await src.open();
    expect(src.size).toBe(100);
    expect(Array.from(await src.read(10, 10))).toEqual(Array.from(FILE.slice(10, 20))); // spans chunks 0 and 1
    expect(fake.ranges).toEqual(["bytes=0-15", "bytes=16-31"]);
  });

  it("serves repeated reads from the cache", async () => {
    const fake = fakeServer();
    const src = make(fake);
    await src.open();
    await src.read(0, 8);
    await src.read(4, 8);
    expect(fake.ranges).toHaveLength(1);
  });

  it("returns fewer bytes at the end of the file", async () => {
    const src = make(fakeServer());
    await src.open();
    const tail = await src.read(90, 50);
    expect(Array.from(tail)).toEqual(Array.from(FILE.slice(90)));
    expect((await src.read(100, 5)).length).toBe(0);
  });

  it("never has two requests in flight, even with read-ahead and overlapping reads", async () => {
    const fake = fakeServer();
    const src = make(fake, { readAheadBytes: CHUNK * 3 });
    await src.open();
    await Promise.all([src.read(0, 40), src.read(48, 40), src.read(20, 5)]);
    await new Promise((r) => setTimeout(r, 30));
    expect(fake.maxInFlight()).toBe(1);
  });

  it("reads ahead sequentially, then a far seek cancels the read-ahead", async () => {
    const fake = fakeServer();
    const src = make(fake, { readAheadBytes: CHUNK * 2 });
    await src.open();
    await src.read(0, 4);
    await new Promise((r) => setTimeout(r, 30));
    expect(fake.ranges).toEqual(["bytes=0-15", "bytes=16-31", "bytes=32-47"]);
    await src.read(80, 4);
    expect(fake.ranges).toContain("bytes=80-95");
  });

  it("rejects a read whose signal is already aborted", async () => {
    const src = make(fakeServer());
    await src.open();
    const ctl = new AbortController();
    ctl.abort();
    await expect(src.read(0, 4, ctl.signal)).rejects.toMatchObject({ kind: "source_aborted" });
  });

  it("aborts an in-flight read", async () => {
    const src = make(fakeServer());
    await src.open();
    const ctl = new AbortController();
    const p = src.read(64, 4, ctl.signal);
    ctl.abort();
    await expect(p).rejects.toMatchObject({ kind: "source_aborted" });
  });

  it("backs off on 429 then succeeds", async () => {
    const sleeps: number[] = [];
    const busy = () => new Response('{"error":"relay_streams_exceeded"}', { status: 429 });
    const fake = fakeServer((n) => (n <= 2 ? busy() : null));
    const src = make(fake, { sleep: async (ms: number) => void sleeps.push(ms), backoffMs: 2000 });
    await src.open();
    expect(sleeps).toEqual([2000, 4000]);
    expect(src.size).toBe(100);
  });

  it("gives up after the retries with the status and server code", async () => {
    const fake = fakeServer(() => new Response('{"error":"relay_streams_exceeded"}', { status: 429 }));
    const src = make(fake);
    const err = await src.open().catch((e) => e);
    expect(err).toBeInstanceOf(SourceError);
    expect(err).toMatchObject({ kind: "source_http", status: 429, code: "relay_streams_exceeded" });
    expect(fake.ranges).toHaveLength(5); // first try + 4 retries
  });

  it("surfaces other HTTP failures without retrying", async () => {
    const fake = fakeServer(() => new Response('{"error":"relay_offline"}', { status: 503 }));
    const err = await make(fake).open().catch((e) => e);
    expect(err).toMatchObject({ kind: "source_http", status: 503, code: "relay_offline" });
    expect(fake.ranges).toHaveLength(1);
  });

  it("reports a server that ignores Range", async () => {
    const f = (async () => new Response(FILE, { status: 200 })) as typeof fetch;
    const src = new HttpRangeSource("http://x", { chunkSize: CHUNK, fetch: f });
    // chunk 0 with a 200 is accepted (whole body, trimmed); later chunks must fail.
    await src.open();
    await expect(src.read(32, 4)).rejects.toMatchObject({ kind: "source_http" });
  });
});
