import { describe, expect, it } from "vitest";
import { Slots } from "../src/telegram-slots";

const deferred = () => { let resolve!: () => void; const promise = new Promise<void>((r) => { resolve = r; }); return { promise, resolve }; };

describe("Slots", () => {
  it("never has more than the limit in flight, even after callers gave up", async () => {
    const slots = new Slots(2);
    let live = 0, peak = 0;
    const jobs = [deferred(), deferred(), deferred()];
    const work = (i: number) => () => { live++; peak = Math.max(peak, live); return jobs[i].promise.then(() => { live--; }); };
    const first = new AbortController();
    const a = slots.run(first.signal, work(0));
    const b = slots.run(new AbortController().signal, work(1));
    first.abort(); // a seek: stop waiting for the first request, which is still out there
    await expect(a).rejects.toMatchObject({ kind: "source_aborted" });
    const c = slots.run(new AbortController().signal, work(2)); // must wait: two requests are still in flight
    await Promise.resolve();
    expect(live).toBe(2);
    jobs[0].resolve();
    await Promise.resolve(); await Promise.resolve();
    jobs[1].resolve(); jobs[2].resolve();
    await Promise.all([b, c]);
    expect(peak).toBe(2);
  });

  it("drops a caller that aborts while queued without starting its work", async () => {
    const slots = new Slots(1);
    const hold = deferred();
    const first = slots.run(new AbortController().signal, () => hold.promise);
    const ac = new AbortController();
    let started = false;
    const queued = slots.run(ac.signal, async () => { started = true; });
    ac.abort();
    await expect(queued).rejects.toMatchObject({ kind: "source_aborted" });
    hold.resolve();
    await first;
    expect(started).toBe(false);
  });
});
