import { ChunkedSource, MiB, type ChunkedOptions } from "./chunked";
import { SourceError } from "./types";

/** What TelegramSource needs from a Telegram client; the app implements it on top of GramJS (see README). */
export interface TelegramFileApi {
  /**
   * upload.getFile for [offset, offset+limit). `offset` and `limit` are already aligned to Telegram's rules.
   * Failures must be thrown as TelegramRpcError (RPC error message such as "FLOOD_WAIT_5").
   */
  getFile(offset: number, limit: number, signal: AbortSignal): Promise<Uint8Array>;
  /** Re-fetches the message so the next getFile uses a fresh file_reference. */
  refreshReference(): Promise<void>;
  /** Moves the download connection to another data centre after FILE_MIGRATE_X. */
  migrate?(dc: number): Promise<void>;
}

export class TelegramRpcError extends Error {
  constructor(
    /** RPC error message, e.g. "FILE_REFERENCE_EXPIRED" or "FLOOD_WAIT_12". */
    readonly rpc: string,
  ) {
    super(rpc);
    this.name = "TelegramRpcError";
  }
}

export interface TelegramSourceOptions extends ChunkedOptions {
  /** Longest FLOOD_WAIT we will sit out, in seconds. Default 30. */
  maxFloodWaitSec?: number;
  /** Retries per chunk for flood waits, migrations and reference refreshes. Default 4. */
  maxRetries?: number;
  sleep?: (ms: number) => Promise<void>;
}

/**
 * Telegram files as a ByteSource. Telegram's rules (core.telegram.org/api/files): offset and limit are multiples of
 * 4 KiB, 1 MiB is a multiple of limit, and a request may not cross a 1 MiB boundary. So every request is one whole,
 * 1 MiB-aligned chunk and reads slice from the cache. One request at a time (the plan allows two).
 * CDN downloads are not used: getFile is expected to be called with cdn_supported = false.
 */
export class TelegramSource extends ChunkedSource {
  private readonly maxFloodWaitSec: number;
  private readonly maxRetries: number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(
    private readonly api: TelegramFileApi,
    /** Size in bytes from the document, so reads never ask past the end. */
    fileSize: number,
    opts: TelegramSourceOptions = {},
  ) {
    super({ ...opts, chunkSize: MiB });
    this.size = fileSize;
    this.maxFloodWaitSec = opts.maxFloodWaitSec ?? 30;
    this.maxRetries = opts.maxRetries ?? 4;
    this.sleep = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  async open(): Promise<void> {
    // nothing to learn: the size came with the document
  }

  protected async fetchChunk(index: number, signal: AbortSignal): Promise<Uint8Array> {
    let refreshed = false;
    for (let attempt = 0; ; attempt++) {
      if (signal.aborted) throw new SourceError("source_aborted", "request aborted");
      try {
        return await this.api.getFile(index * MiB, MiB, signal);
      } catch (e) {
        if (signal.aborted) throw new SourceError("source_aborted", "request aborted");
        const rpc = e instanceof TelegramRpcError ? e.rpc : null;
        if (rpc === null) throw new SourceError("source_network", `telegram: ${(e as Error).message}`);

        const flood = /^(?:FLOOD_PREMIUM_WAIT|FLOOD_WAIT)_(\d+)$/.exec(rpc);
        if (flood) {
          const wait = Number(flood[1]);
          if (wait > this.maxFloodWaitSec || attempt >= this.maxRetries) throw new SourceError("telegram_flood", `Telegram asks to wait ${wait} s`, undefined, rpc);
          await this.sleep(wait * 1000 + 250);
          continue;
        }
        if (rpc.startsWith("FILE_REFERENCE_")) {
          if (refreshed) throw new SourceError("telegram_file", "file reference is still rejected after a refresh", undefined, rpc);
          refreshed = true;
          await this.api.refreshReference();
          continue;
        }
        const migrate = /^(?:FILE|NETWORK|PHONE|USER)_MIGRATE_(\d+)$/.exec(rpc);
        if (migrate && this.api.migrate && attempt < this.maxRetries) {
          await this.api.migrate(Number(migrate[1]));
          continue;
        }
        if (/^(AUTH_KEY_|SESSION_|USER_DEACTIVATED|API_ID_INVALID)/.test(rpc)) throw new SourceError("telegram_auth", `Telegram session problem: ${rpc}`, undefined, rpc);
        if (/^(FILE_ID_INVALID|LOCATION_INVALID|MESSAGE_ID_INVALID|CHANNEL_|FILE_MIGRATE)/.test(rpc)) throw new SourceError("telegram_file", `Telegram cannot serve the file: ${rpc}`, undefined, rpc);
        throw new SourceError("telegram_error", `Telegram error: ${rpc}`, undefined, rpc);
      }
    }
  }
}
