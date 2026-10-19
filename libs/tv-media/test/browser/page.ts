import { HttpRangeSource } from "../../src/source/http";
import { createPlayer, type Player, type PlayerOptions } from "../../src/player/engine";

declare global {
  interface Window {
    t: {
      video: HTMLVideoElement;
      player: Player | null;
      errors: string[];
      cues: Array<{ track: number; text: string; start: number }>;
      open(url: string, opts?: PlayerOptions): Promise<unknown>;
      supported(mime: string): boolean;
      state(): { time: number; paused: boolean; ended: boolean; ranges: Array<[number, number]>; error: string | null };
    };
  }
}

const video = document.getElementById("v") as HTMLVideoElement;
window.t = {
  video,
  player: null,
  errors: [],
  cues: [],
  async open(url, opts) {
    const player = createPlayer(video, new HttpRangeSource(url), opts);
    window.t.player = player;
    player.on("error", (e) => window.t.errors.push(`${e.kind}: ${e.message}`));
    player.on("cues", (track, cues) => cues.forEach((c) => window.t.cues.push({ track, text: c.text, start: c.start })));
    const info = await player.ready;
    return { duration: info.duration, tracks: info.tracks.map((t) => ({ id: t.id, kind: t.kind, codec: t.codec })) };
  },
  supported: (mime) => MediaSource.isTypeSupported(mime),
  state() {
    const r: Array<[number, number]> = [];
    for (let i = 0; i < video.buffered.length; i++) r.push([video.buffered.start(i), video.buffered.end(i)]);
    return { time: video.currentTime, paused: video.paused, ended: video.ended, ranges: r, error: video.error ? video.error.message : null };
  },
};
