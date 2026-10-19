import { describe, expect, it } from "vitest";
import { TelegramRpcError, TelegramSource, type TelegramFileApi } from "../src/source/telegram";
import { SourceError } from "../src/source/types";

const MiB = 1024 * 1024;
const FILE_SIZE = 3 * MiB + 1234;
const byteAt = (i: number) => (i * 7 + (i >> 10)) & 255;

class FakeApi implements TelegramFileApi {
  calls: Array<{ offset: number; limit: number }> = [];
  refreshes = 0;
  migrations: number[] = [];
  /** Errors to throw on successive calls, before serving normally. */
  script: Array<string | undefined> = [];
  inFlight = 0;
  peak = 0;

  async getFile(offset: number, limit: number): Promise<Uint8Array> {
    this.calls.push({ offset, limit });
    const err = this.script.shift();
    if (err) throw new TelegramRpcError(err);
    this.inFlight++;
    this.peak = Math.max(this.peak, this.inFlight);
    await new Promise((r) => setTimeout(r, 1));
    this.inFlight--;
    // Telegram's own rules: reject anything it would reject
    if (offset % 4096 || limit % 4096 || MiB % limit || Math.floor(offset / MiB) !== Math.floor((offset + limit - 1) / MiB)) throw new TelegramRpcError("LIMIT_INVALID");
    const end = Math.min(offset + limit, FILE_SIZE);
    return Uint8Array.from({ length: Math.max(0, end - offset) }, (_, i) => byteAt(offset + i));
  }
  async refreshReference(): Promise<void> {
    this.refreshes++;
  }
  async migrate(dc: number): Promise<void> {
    this.migrations.push(dc);
  }
}

const make = (api: FakeApi, extra = {}) => new TelegramSource(api, FILE_SIZE, { readAheadBytes: 0, sleep: async () => {}, ...extra });
const expectBytes = (got: Uint8Array, from: number) => expect(Array.from(got)).toEqual(Array.from({ length: got.length }, (_, i) => byteAt(from + i)));

describe("TelegramSource", () => {
  it("knows its size without a request", async () => {
    const api = new FakeApi();
    const src = make(api);
    await src.open();
    expect(src.size).toBe(FILE_SIZE);
    expect(api.calls).toHaveLength(0);
  });

  it("always requests whole 1 MiB-aligned chunks, whatever the read looks like", async () => {
    const api = new FakeApi();
    const src = make(api);
    const got = await src.read(MiB - 10, 30); // crosses a chunk boundary
    expectBytes(got, MiB - 10);
    expect(api.calls).toEqual([
      { offset: 0, limit: MiB },
      { offset: MiB, limit: MiB },
    ]);
  });

  it("a seek reads the right chunk and only that one", async () => {
    const api = new FakeApi();
    const src = make(api);
    const got = await src.read(2 * MiB + 500, 100);
    expectBytes(got, 2 * MiB + 500);
    expect(api.calls).toEqual([{ offset: 2 * MiB, limit: MiB }]);
  });

  it("returns the short tail and nothing past the end", async () => {
    const src = make(new FakeApi());
    const tail = await src.read(3 * MiB, 5000);
    expect(tail.length).toBe(1234);
    expectBytes(tail, 3 * MiB);
    expect((await src.read(FILE_SIZE, 10)).length).toBe(0);
  });

  it("caches chunks and never has two requests in flight", async () => {
    const api = new FakeApi();
    const src = make(api, { readAheadBytes: 2 * MiB });
    await src.read(0, 10);
    await src.read(5, 10);
    await new Promise((r) => setTimeout(r, 30));
    expect(api.calls.map((c) => c.offset)).toEqual([0, MiB, 2 * MiB]); // chunk 0 once, then read-ahead
    expect(api.peak).toBe(1);
  });

  it("sits out a FLOOD_WAIT and retries", async () => {
    const api = new FakeApi();
    api.script = ["FLOOD_WAIT_3"];
    const sleeps: number[] = [];
    const src = make(api, { sleep: async (ms: number) => void sleeps.push(ms) });
    expectBytes(await src.read(0, 8), 0);
    expect(sleeps).toEqual([3250]);
    expect(api.calls).toHaveLength(2);
  });

  it("gives up on a flood wait longer than allowed", async () => {
    const api = new FakeApi();
    api.script = ["FLOOD_WAIT_900"];
    const err = await make(api).read(0, 8).catch((e) => e);
    expect(err).toBeInstanceOf(SourceError);
    expect(err).toMatchObject({ kind: "telegram_flood", code: "FLOOD_WAIT_900" });
  });

  it("refreshes an expired file reference once and retries", async () => {
    const api = new FakeApi();
    api.script = ["FILE_REFERENCE_EXPIRED"];
    expectBytes(await make(api).read(0, 8), 0);
    expect(api.refreshes).toBe(1);
  });

  it("stops if the reference is still rejected after a refresh", async () => {
    const api = new FakeApi();
    api.script = ["FILE_REFERENCE_EXPIRED", "FILE_REFERENCE_EXPIRED"];
    const err = await make(api).read(0, 8).catch((e) => e);
    expect(err).toMatchObject({ kind: "telegram_file" });
    expect(api.refreshes).toBe(1);
  });

  it("follows FILE_MIGRATE_X to the other data centre", async () => {
    const api = new FakeApi();
    api.script = ["FILE_MIGRATE_4"];
    expectBytes(await make(api).read(0, 8), 0);
    expect(api.migrations).toEqual([4]);
  });

  it("classifies the rest", async () => {
    for (const [rpc, kind] of [
      ["AUTH_KEY_UNREGISTERED", "telegram_auth"],
      ["SESSION_REVOKED", "telegram_auth"],
      ["FILE_ID_INVALID", "telegram_file"],
      ["SOMETHING_NEW", "telegram_error"],
    ] as const) {
      const api = new FakeApi();
      api.script = [rpc];
      const err = await make(api).read(0, 8).catch((e) => e);
      expect(err, rpc).toMatchObject({ kind, code: rpc });
    }
  });

  it("reports a broken connection as a network error", async () => {
    const api = new FakeApi();
    api.getFile = async () => {
      throw new Error("socket closed");
    };
    const err = await make(api).read(0, 8).catch((e) => e);
    expect(err).toMatchObject({ kind: "source_network" });
  });

  it("aborts a read in flight", async () => {
    const api = new FakeApi();
    const src = make(api);
    const ctl = new AbortController();
    const p = src.read(0, 8, ctl.signal);
    ctl.abort();
    await expect(p).rejects.toMatchObject({ kind: "source_aborted" });
  });
});
