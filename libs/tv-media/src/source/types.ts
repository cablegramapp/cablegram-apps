export interface ByteSource {
  /** Total size in bytes, known after open(); null when the server does not say. */
  readonly size: number | null;
  open(): Promise<void>;
  /** Exactly [offset, offset+length), or fewer bytes at the end of the file. */
  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array>;
  close(): void;
}

export type SourceErrorKind = "source_http" | "source_network" | "source_aborted";

export class SourceError extends Error {
  constructor(
    readonly kind: SourceErrorKind,
    message: string,
    /** HTTP status, when the server answered. */
    readonly status?: number,
    /** The server's `error` code from a JSON body, e.g. `relay_streams_exceeded`. */
    readonly code?: string,
  ) {
    super(message);
    this.name = "SourceError";
  }
}
