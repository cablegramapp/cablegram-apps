export class QuotaError extends Error {
  constructor() {
    super("SourceBuffer quota exceeded");
    this.name = "QuotaError";
  }
}

/** Serialises operations on one SourceBuffer: each waits for the previous updateend. */
export class SourceBufferQueue {
  private tail: Promise<unknown> = Promise.resolve();

  constructor(readonly sb: SourceBuffer) {}

  append(data: Uint8Array): Promise<void> {
    return this.run(() => {
      try {
        this.sb.appendBuffer(data as BufferSource);
      } catch (e) {
        if ((e as DOMException).name === "QuotaExceededError") throw new QuotaError();
        throw e;
      }
    });
  }

  remove(start: number, end: number): Promise<void> {
    return this.run(() => this.sb.remove(start, end));
  }

  /** Drops queued work and aborts the current append/remove; the queue is usable afterwards. */
  reset(): void {
    this.tail = Promise.resolve();
    try {
      this.sb.abort();
    } catch {
      // the MediaSource is not open any more
    }
  }

  private run(op: () => void): Promise<void> {
    const next = this.tail.then(() => this.exec(op));
    this.tail = next.catch(() => undefined);
    return next;
  }

  private exec(op: () => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const sb = this.sb;
      const done = () => {
        sb.removeEventListener("updateend", done);
        sb.removeEventListener("error", fail);
        resolve();
      };
      const fail = () => {
        sb.removeEventListener("updateend", done);
        sb.removeEventListener("error", fail);
        reject(new Error("SourceBuffer error"));
      };
      sb.addEventListener("updateend", done);
      sb.addEventListener("error", fail);
      try {
        op();
      } catch (e) {
        sb.removeEventListener("updateend", done);
        sb.removeEventListener("error", fail);
        reject(e);
      }
    });
  }
}

export function rangesOf(ranges: TimeRanges): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (let i = 0; i < ranges.length; i++) out.push([ranges.start(i), ranges.end(i)]);
  return out;
}
