export interface ByteSource {
  /** Total size in bytes, known after open(); null when the server does not say. */
  readonly size: number | null;
  open(): Promise<void>;
  /** Exactly [offset, offset+length), or fewer bytes at the end of the file. */
  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array>;
  close(): void;
}

export type SourceErrorKind =
  | "source_http"
  | "source_network"
  | "source_aborted"
  /** Telegram asked us to slow down for longer than we are willing to wait. */
  | "telegram_flood"
  /** The session is not signed in (any more). */
  | "telegram_auth"
  /** The file or message is gone or the reference could not be refreshed. */
  | "telegram_file"
  | "telegram_error";

export class SourceError extends Error {
  constructor(
    readonly kind: SourceErrorKind,
    message: string,
    /** HTTP status, when the server answered. */
    readonly status?: number,
    /** The server's `error` code from a JSON body, e.g. `relay_streams_exceeded`, or Telegram's RPC error message. */
    readonly code?: string,
  ) {
    super(message);
    this.name = "SourceError";
  }
}
