// Bundled by perf-throttle.mjs and run in Chrome under CPU throttling.
import "../src/telegram-env";
import { IGE } from "telegram/crypto/IGE";
import { createPlayer, HttpRangeSource } from "tv-media";

declare global { interface Window { perf: unknown } }

window.perf = {
  /** AES-256-IGE decryption, the cipher GramJS runs on every downloaded byte. Returns MB/s. */
  ige(mb: number): number {
    const ige = new IGE(Buffer.alloc(32, 1), Buffer.alloc(32, 2));
    const data = Buffer.alloc(mb * 1048576, 7);
    const t = performance.now();
    ige.decryptIge(data);
    return mb / ((performance.now() - t) / 1000);
  },
  /** Demux + remux + append until [seconds] of media are buffered. Nothing is played, so decoding is not measured. */
  async pump(url: string, seconds: number): Promise<{ wallSec: number; mediaSec: number; mb: number }> {
    const video = document.createElement("video");
    const source = new HttpRangeSource(url);
    const t0 = performance.now();
    const player = createPlayer(video, source, { aheadSec: seconds, batchBytes: 1048576 });
    await player.ready;
    for (;;) {
      const b = video.buffered;
      if (b.length && b.end(b.length - 1) >= seconds) break;
      if (performance.now() - t0 > 120000) break;
      await new Promise((r) => setTimeout(r, 25));
    }
    const wallSec = (performance.now() - t0) / 1000;
    const b = video.buffered;
    const mediaSec = b.length ? b.end(b.length - 1) : 0;
    player.destroy();
    return { wallSec, mediaSec, mb: (mediaSec * 74) / 30 };
  },
};
