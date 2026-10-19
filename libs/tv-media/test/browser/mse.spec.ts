import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { hasFixture } from "../helpers";
import { chromeAvailable, startHarness, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => {
  if (chromeAvailable) h = await startHarness();
});
afterAll(async () => {
  await h?.close();
});

const waitFor = async (fn: () => Promise<boolean>, ms = 20000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await fn()) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("timed out");
};

const state = () => h.page.evaluate(() => window.t.state());
const play = (name: string, opts: object = {}) =>
  h.page.evaluate(
    async ([url, o]) => {
      const info = await window.t.open(url as string, o as object);
      await window.t.video.play();
      return info;
    },
    [`${h.base}/fixtures/${name}`, opts],
  );

describe.skipIf(!chromeAvailable)("MSE playback in Chrome", () => {
  it.each(["mp4-faststart.mp4", "mp4-moov-end.mp4"].filter(hasFixture))("plays %s", async (name) => {
    await h.page.reload();
    await play(name);
    await waitFor(async () => (await state()).time > 2);
    expect(await h.page.evaluate(() => window.t.errors)).toEqual([]);
    expect((await state()).error).toBeNull();
  });

  it.skipIf(!hasFixture("mp4-faststart.mp4"))("plays to the end", async () => {
    await h.page.reload();
    await play("mp4-faststart.mp4");
    await waitFor(async () => (await state()).ended, 30000);
  });

  describe.skipIf(!hasFixture("mp4-long.mp4"))("seek and buffer policy (2-minute file)", () => {
    it("seeks to 80 % and back, and plays on", async () => {
      await h.page.reload();
      h.requests.length = 0;
      await play("mp4-long.mp4");
      await waitFor(async () => (await state()).time > 1);

      await h.page.evaluate(() => (window.t.video.currentTime = 96));
      await waitFor(async () => (await state()).time > 98);
      let st = await state();
      expect(st.ranges.some(([a, b]) => a <= st.time && b >= st.time)).toBe(true);

      await h.page.evaluate(() => (window.t.video.currentTime = 10));
      await waitFor(async () => (await state()).time > 12);

      expect(await h.page.evaluate(() => window.t.errors)).toEqual([]);
      expect((await state()).error).toBeNull();
      st = await state();
      expect(st.paused).toBe(false);
      // never more than one range request at a time against the server
      expect(Math.max(...h.requests.map((r) => r.inFlight))).toBe(1);
    });

    it("keeps the buffer inside the ahead/behind policy", async () => {
      await h.page.reload();
      await h.page.evaluate(() => void 0);
      const ahead = 10;
      const behind = 5;
      await play("mp4-long.mp4", { aheadSec: ahead, behindSec: behind, batchBytes: 128 * 1024 });
      await h.page.evaluate(() => (window.t.video.playbackRate = 4));
      let maxAhead = 0;
      let maxBehind = 0;
      await waitFor(async () => {
        const st = await state();
        for (const [a, b] of st.ranges) {
          if (st.time >= a && st.time <= b) {
            maxAhead = Math.max(maxAhead, b - st.time);
            if (st.time > 20) maxBehind = Math.max(maxBehind, st.time - a);
          }
        }
        return st.time > 40;
      }, 40000);
      expect(maxAhead).toBeLessThan(ahead + 4);
      expect(maxBehind).toBeGreaterThan(0);
      expect(maxBehind).toBeLessThan(behind + 4);
      expect(await h.page.evaluate(() => window.t.errors)).toEqual([]);
    });
  });
});
