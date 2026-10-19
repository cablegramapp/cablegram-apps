import { SourceError } from "tv-media";

/** Tells an abort apart from a Telegram failure. */
const aborted = () => new SourceError("source_aborted", "request aborted");

/**
 * At most `limit` MTProto downloads at once. A request cannot be cancelled once sent, so a seek stops *waiting* for it
 * (the caller's signal) but its slot stays taken until Telegram answers, which keeps the total in flight at the limit.
 */
export class Slots {
  private free: number;
  private readonly queue: Array<() => void> = [];
  constructor(limit: number) { this.free = limit; }

  async run<T>(signal: AbortSignal, work: () => Promise<T>): Promise<T> {
    if (signal.aborted) throw aborted();
    if (this.free === 0) await this.wait(signal);
    else this.free--;
    const release = () => { const next = this.queue.shift(); if (next) next(); else this.free++; };
    const job = work();
    job.then(release, release);
    return new Promise<T>((resolve, reject) => {
      const onAbort = () => reject(aborted());
      if (signal.aborted) return onAbort();
      signal.addEventListener("abort", onAbort, { once: true });
      job.then((v) => { signal.removeEventListener("abort", onAbort); resolve(v); }, (e) => { signal.removeEventListener("abort", onAbort); reject(e); });
    });
  }

  private wait(signal: AbortSignal): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const turn = () => { signal.removeEventListener("abort", onAbort); resolve(); };
      const onAbort = () => { const i = this.queue.indexOf(turn); if (i >= 0) this.queue.splice(i, 1); reject(aborted()); };
      signal.addEventListener("abort", onAbort, { once: true });
      this.queue.push(turn);
    });
  }
}
