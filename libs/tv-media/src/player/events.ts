import { MediaError, type SubtitleCue } from "../demux/types";
import { SourceError } from "../source/types";

export type PlayerErrorKind =
  | "unsupported_container"
  | "unsupported_codec"
  | "source_http"
  | "source_network"
  | "telegram_flood"
  | "telegram_auth"
  | "telegram_file"
  | "telegram_error"
  | "malformed"
  | "decode"
  | "buffer";

export class PlayerError extends Error {
  constructor(
    readonly kind: PlayerErrorKind,
    message: string,
    /** HTTP status and the server's error code for `source_http`. */
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "PlayerError";
  }
}

export function toPlayerError(e: unknown): PlayerError {
  if (e instanceof PlayerError) return e;
  if (e instanceof SourceError) {
    const kind = e.kind === "source_aborted" ? "source_network" : e.kind;
    return new PlayerError(kind, e.message, e.status, e.code);
  }
  if (e instanceof MediaError) return new PlayerError(e.kind, e.message);
  return new PlayerError("malformed", e instanceof Error ? e.message : String(e));
}

export interface PlayerEvents {
  cues: (trackId: number, cues: SubtitleCue[]) => void;
  error: (error: PlayerError) => void;
  /** True while the engine is waiting for data the playhead needs. */
  buffering: (waiting: boolean) => void;
  /** The selected subtitle track changed (null = off). */
  subtitle: (trackId: number | null) => void;
}

export class Emitter<E extends { [K in keyof E]: (...args: any[]) => void }> {
  private readonly handlers: { [K in keyof E]?: Array<E[K]> } = {};

  on<K extends keyof E>(event: K, fn: E[K]): () => void {
    (this.handlers[event] ??= []).push(fn);
    return () => {
      this.handlers[event] = (this.handlers[event] ?? []).filter((h) => h !== fn);
    };
  }

  protected emit<K extends keyof E>(event: K, ...args: Parameters<E[K]>): void {
    for (const h of this.handlers[event] ?? []) h(...args);
  }
}
