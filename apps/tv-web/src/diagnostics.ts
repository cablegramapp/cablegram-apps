// On-TV diagnostics: what this TV's browser can do, how fast it is, how big its MSE buffer is, and whether the tv-media
// engine plays a bundled test clip (1080p H.264, two audio tracks, subtitles). Results go on screen and into a compact
// string that Settings → Diagnostics also shows as a QR code, so they can be read from a photo or scanned.
import { buildFragment, buildInitSegment, createPlayer, MemorySource, MkvDemuxer, mimeType } from "tv-media";
import type { Sample, Track } from "tv-media";

export type Status = "ok" | "warn" | "fail" | "info";
export interface Row { id: string; label: string; status: Status; detail: string }
export type Facts = Record<string, string | number | boolean>;

/** MB/s of cpuBench() on an Apple-silicon laptop (V8 in Node 26, five runs 2590 to 3010): the reference for "N times slower". */
export const CPU_REFERENCE_MBPS = 2700;

export class Report {
  readonly rows: Row[] = [];
  readonly facts: Facts = {};

  constructor(private readonly onChange: (rows: Row[]) => void = () => undefined) {}

  add(id: string, label: string, status: Status, detail: string): void {
    const row = { id, label, status, detail };
    const at = this.rows.findIndex((r) => r.id === id);
    if (at >= 0) this.rows[at] = row;
    else this.rows.push(row);
    this.onChange(this.rows);
  }
}

// ---- pure helpers ---------------------------------------------------------------------------------------------------

export const VIDEO_TYPES: Array<[string, string]> = [
  ["H.264", 'video/mp4; codecs="avc1.640028"'],
  ["HEVC", 'video/mp4; codecs="hvc1.1.6.L120.90"'],
  ["VP9", 'video/mp4; codecs="vp09.00.40.08"'],
  ["AV1", 'video/mp4; codecs="av01.0.08M.08"'],
];
export const AUDIO_TYPES: Array<[string, string]> = [
  ["AAC", 'audio/mp4; codecs="mp4a.40.2"'],
  ["AC-3", 'audio/mp4; codecs="ac-3"'],
  ["E-AC-3", 'audio/mp4; codecs="ec-3"'],
  ["MP3", 'audio/mp4; codecs="mp4a.6B"'],
  ["Opus", 'audio/mp4; codecs="opus"'],
];

/** "H.264 yes, HEVC no, …" plus a 0/1 string in table order for the compact summary. */
export function decoderMatrix(types: Array<[string, string]>, supported: (mime: string) => boolean): { text: string; bits: string } {
  const flags = types.map(([, mime]) => { try { return supported(mime); } catch { return false; } });
  return {
    text: types.map(([name], i) => `${name} ${flags[i] ? "yes" : "no"}`).join(", "),
    bits: flags.map((f) => (f ? "1" : "0")).join(""),
  };
}

export function chromiumVersion(ua: string): number | null {
  const m = /Chrom(?:e|ium)\/(\d+)/.exec(ua);
  return m ? Number(m[1]) : null;
}

export function slowdown(mbps: number, reference = CPU_REFERENCE_MBPS): number {
  return mbps > 0 ? reference / mbps : Infinity;
}

/** Everything worth reading from a photo or QR code, in under ~900 characters. */
export function compactSummary(facts: Facts, rows: Row[]): string {
  const state = rows.map((r) => (r.status === "ok" ? "+" : r.status === "fail" ? "!" : r.status === "warn" ? "~" : ".")).join("");
  return JSON.stringify({ v: 1, ...facts, rows: state });
}

// ---- checks ---------------------------------------------------------------------------------------------------------

/** Reads a bundled file. XHR rather than fetch: fetch cannot read file:// in the packaged TV apps. */
export function loadBytes(url: string): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open("GET", url);
    x.responseType = "arraybuffer";
    x.onload = () => ((x.status === 0 || (x.status >= 200 && x.status < 300)) && x.response ? resolve(new Uint8Array(x.response as ArrayBuffer)) : reject(new Error(`HTTP ${x.status}`)));
    x.onerror = () => reject(new Error("could not read the file"));
    x.send();
  });
}

export function checkEnvironment(r: Report): void {
  const ua = navigator.userAgent;
  const nav = navigator as Navigator & { deviceMemory?: number };
  const chrome = chromiumVersion(ua);
  r.facts.ua = ua.replace(/^Mozilla\/5\.0 /, "").slice(0, 110);
  r.facts.chrome = chrome ?? 0;
  r.add("env", "Browser", "info", `Chromium ${chrome ?? "?"} · ${window.innerWidth}×${window.innerHeight} at ${window.devicePixelRatio}× · ${nav.hardwareConcurrency ?? "?"} cores${nav.deviceMemory ? ` · ${nav.deviceMemory} GB` : ""}`);
  const subtle = !!(window.crypto && window.crypto.subtle);
  r.facts.subtle = subtle;
  r.add("secure", "Secure context", subtle ? "ok" : "fail", `${location.protocol}// origin · crypto.subtle ${subtle ? "present (Telegram sign-in can work)" : "missing: direct Telegram playback cannot work here"}`);
  r.add("platform", "Language features", "info", `WebAssembly ${typeof WebAssembly !== "undefined" ? "yes" : "no"} · BigInt ${typeof (window as unknown as { BigInt?: unknown }).BigInt !== "undefined" ? "yes" : "no"} · MediaSource ${typeof MediaSource !== "undefined" ? "yes" : "no"}`);
  if (typeof MediaSource === "undefined") r.add("mse", "Media Source Extensions", "fail", "missing: the engine cannot run, the plain player is used");
}

export function checkDecoders(r: Report): void {
  if (typeof MediaSource === "undefined") return;
  const v = decoderMatrix(VIDEO_TYPES, (m) => MediaSource.isTypeSupported(m));
  const a = decoderMatrix(AUDIO_TYPES, (m) => MediaSource.isTypeSupported(m));
  r.facts.vdec = v.bits;
  r.facts.adec = a.bits;
  r.add("video", "Video decoders (MSE)", v.bits[0] === "1" ? "ok" : "fail", v.text);
  r.add("audio", "Audio decoders (MSE)", a.bits[0] === "1" ? "ok" : "warn", a.text);
}

/** XOR-multiply over 8 MB, repeated: a rough stand-in for the byte-shuffling the engine does. MB/s. */
export function cpuBench(): number {
  const n = 1 << 21;
  const a = new Uint32Array(n);
  for (let i = 0; i < n; i++) a[i] = (i * 2654435761) >>> 0;
  const rounds = 6;
  let h = 0;
  const t = performance.now();
  for (let k = 0; k < rounds; k++) for (let i = 0; i < n; i++) h = (Math.imul(h ^ a[i], 16777619) ^ (h >>> 13)) | 0;
  const ms = Math.max(1, performance.now() - t);
  (cpuBench as unknown as { sink: number }).sink = h; // keep the loop observable
  return (n * 4 * rounds) / 1048576 / (ms / 1000);
}

export async function checkSpeed(r: Report): Promise<void> {
  const mbps = cpuBench();
  const factor = slowdown(mbps);
  r.facts.cpu = Math.round(mbps);
  r.add("cpu", "JavaScript speed", factor > 15 ? "warn" : "info", `${Math.round(mbps)} MB/s, about ${factor < 1.5 ? "as fast as" : `${factor.toFixed(1)}× slower than`} a laptop`);
  try {
    const mod = await import("@cryptography/aes");
    // Telegram's cipher (AES-256-IGE), the cost of every downloaded byte.
    const key = new Uint8Array(32).fill(1) as never;
    const iv = new Uint8Array(32).fill(2) as never;
    const data = new Uint8Array(4 * 1048576).fill(7) as never;
    const ige = new mod.IGE(key, iv);
    const t = performance.now();
    ige.decrypt(data);
    const rate = 4 / ((performance.now() - t) / 1000);
    r.facts.aes = Math.round(rate * 10) / 10;
    r.add("aes", "Telegram decryption", rate >= 5 ? "ok" : rate >= 2.5 ? "warn" : "fail", `${rate.toFixed(1)} MB/s (a 20 Mbit/s video needs 2.5 MB/s, a 4 Mbit/s one 0.5)`);
  } catch (e) {
    r.add("aes", "Telegram decryption", "info", `not measured (${(e as Error).message})`);
  }
}

export async function checkNetwork(r: Report, apiBase: string): Promise<void> {
  const t = Date.now();
  try {
    const res = await fetch(`${apiBase.replace(/\/+$/, "")}/api/me`);
    r.facts.api = res.status;
    r.add("api", "Cablegram server", res.status === 401 || res.ok ? "ok" : "warn", `reachable (HTTP ${res.status}, ${Date.now() - t} ms); the browser accepts its CORS answer`);
  } catch (e) {
    r.facts.api = 0;
    r.add("api", "Cablegram server", "fail", `blocked or offline (${(e as Error).message}). If the internet works, the server's CORS must allow this app's origin (${location.origin})`);
  }
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
async function until(cond: () => boolean, ms: number): Promise<boolean> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (cond()) return true;
    await wait(50);
  }
  return cond();
}

/** Plays the bundled clip through the engine and reports start-up time, dropped frames, audio switching, seeking and cues. */
export async function checkPlayback(r: Report, clip: Uint8Array, mount: HTMLElement): Promise<void> {
  if (typeof MediaSource === "undefined") return;
  const video = document.createElement("video");
  video.muted = true;
  video.setAttribute("playsinline", "");
  mount.appendChild(video);
  const errors: string[] = [];
  let cues = 0;
  const t0 = Date.now();
  const player = createPlayer(video, new MemorySource(clip));
  player.on("error", (e) => errors.push(`${e.kind}: ${e.message}`));
  player.on("cues", (_id, list) => { cues += list.length; });
  try {
    const info = await player.ready;
    const v = info.tracks.find((t) => t.kind === "video");
    r.add("tracks", "Test clip", "info", `${v ? `${v.codec} ${v.width}×${v.height}` : "no video"}, ${player.audioTracks.length} audio, ${player.subtitleTracks.length} subtitle track(s), read in ${Date.now() - t0} ms`);
    const started = Date.now();
    await video.play().catch(() => undefined);
    const picture = await until(() => video.currentTime > 0.3 || errors.length > 0, 20000);
    if (!picture || errors.length) throw new Error(errors[0] ?? "no picture within 20 s");
    const firstPicture = Date.now() - started;
    r.facts.first = firstPicture;
    r.add("first", "Time to first picture", firstPicture < 2500 ? "ok" : "warn", `${firstPicture} ms after pressing play`);

    await until(() => video.currentTime >= 1.2 || errors.length > 0, 8000);
    const q = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : null;
    if (q) {
      r.facts.frames = `${q.totalVideoFrames - q.droppedVideoFrames}/${q.totalVideoFrames}`;
      r.add("frames", "1080p decoding", q.droppedVideoFrames <= Math.max(2, q.totalVideoFrames * 0.1) ? "ok" : "fail", `${q.totalVideoFrames} frames shown, ${q.droppedVideoFrames} dropped in ${video.currentTime.toFixed(1)} s`);
    } else r.add("frames", "1080p decoding", "info", `reached ${video.currentTime.toFixed(1)} s (this browser does not count frames)`);

    r.add("cues", "Subtitles from the file", cues > 0 ? "ok" : "fail", cues > 0 ? `${cues} cue(s) read` : "none read");
    const second = player.audioTracks[1];
    if (second) {
      try {
        await player.selectAudio(second.id);
        const before = video.currentTime;
        const moved = await until(() => video.currentTime > before + 0.3 || errors.length > 0, 8000);
        r.facts.audioSwitch = moved && !errors.length;
        r.add("switch", "Switching audio track", moved && !errors.length ? "ok" : "fail", moved && !errors.length ? "switched while playing" : errors[0] ?? "playback stopped after switching");
      } catch (e) {
        r.add("switch", "Switching audio track", "fail", (e as Error).message);
      }
    }
    await until(() => video.currentTime >= 2.0 || video.ended || errors.length > 0, 8000);
    video.currentTime = 0.4;
    const seeked = await until(() => !video.seeking && video.currentTime >= 0.4 && video.readyState >= 3, 8000);
    r.facts.seek = seeked;
    r.add("seek", "Seeking", seeked && !errors.length ? "ok" : "fail", seeked ? "jumped back and kept playing" : errors[0] ?? "did not recover");
  } catch (e) {
    r.facts.playback = "fail";
    r.add("play", "Playing the test clip", "fail", (e as Error).message);
  } finally {
    player.destroy();
    if (video.parentNode) video.parentNode.removeChild(video);
  }
}

const appendOnce = (sb: SourceBuffer, data: Uint8Array) =>
  new Promise<void>((resolve, reject) => {
    const done = () => { sb.removeEventListener("updateend", done); sb.removeEventListener("error", fail); resolve(); };
    const fail = () => { sb.removeEventListener("updateend", done); sb.removeEventListener("error", fail); reject(new Error("SourceBuffer error")); };
    sb.addEventListener("updateend", done);
    sb.addEventListener("error", fail);
    try { sb.appendBuffer(data as BufferSource); } catch (e) { sb.removeEventListener("updateend", done); sb.removeEventListener("error", fail); reject(e); }
  });

/**
 * Appends the clip's video again and again (never playing, so nothing is evicted) until the TV refuses with
 * QuotaExceededError, to learn the MSE video buffer size. Capped so a TV with a huge buffer is not run out of memory.
 */
export async function measureBuffer(r: Report, clip: Uint8Array, progress: (mb: number) => void, capMb = 200): Promise<void> {
  r.add("buffer", "MSE buffer size", "info", "measuring…");
  const demuxer = new MkvDemuxer(new MemorySource(clip));
  const info = await demuxer.init();
  const track: Track | undefined = info.tracks.find((t) => t.kind === "video");
  if (!track) { r.add("buffer", "MSE buffer size", "fail", "the test clip has no video"); return; }
  demuxer.setEnabledTracks([track.id]);
  const samples: Sample[] = [];
  for (let b = await demuxer.next(1 << 20); b; b = await demuxer.next(1 << 20)) samples.push(...b);
  const span = samples.reduce((m, s) => Math.max(m, s.dts + s.duration), 0) - samples[0].dts;
  const shift = Math.max(0, -samples[0].dts);

  const ms = new MediaSource();
  const video = document.createElement("video");
  const url = URL.createObjectURL(ms);
  video.src = url;
  await new Promise<void>((resolve) => ms.addEventListener("sourceopen", () => resolve(), { once: true }));
  let accepted = 0;
  let seconds = 0;
  let capped = false;
  try {
    const sb = ms.addSourceBuffer(mimeType(track));
    await appendOnce(sb, buildInitSegment(track));
    const started = Date.now();
    for (let rep = 0; ; rep++) {
      const fragment = buildFragment(track, samples, rep + 1, shift + rep * span);
      try {
        await appendOnce(sb, fragment);
      } catch (e) {
        if ((e as DOMException).name === "QuotaExceededError") break;
        throw e;
      }
      accepted += fragment.length;
      seconds += span / track.timescale;
      progress(accepted / 1048576);
      if (accepted / 1048576 >= capMb) { capped = true; break; }
      if (Date.now() - started > 120_000) { capped = true; break; }
    }
  } finally {
    video.removeAttribute("src");
    URL.revokeObjectURL(url);
  }
  const mb = Math.round(accepted / 1048576);
  r.facts.bufferMB = mb;
  r.facts.bufferCapped = capped;
  r.add("buffer", "MSE buffer size", capped ? "ok" : mb >= 60 ? "ok" : "warn", `${capped ? "at least " : ""}${mb} MB of 1080p video (${Math.round(seconds)} s of this clip) before the TV refused more${capped ? " (test stopped at its limit)" : ""}`);
}
