import { MkvDemuxer } from "../demux/mkv";
import { Mp4Demuxer } from "../demux/mp4";
import { sniffContainer } from "../demux/sniff";
import { MediaError, type Demuxer, type MediaInfo, type Sample, type SubtitleCue, type Track } from "../demux/types";
import { buildFragment, buildInitSegment } from "../mux/fmp4";
import { mimeType } from "../mux/codecs";
import { QuotaError, SourceBufferQueue, rangesOf } from "../sink/mse";
import type { ByteSource } from "../source/types";
import { Emitter, PlayerError, toPlayerError, type PlayerEvents } from "./events";

export interface PlayerOptions {
  startAt?: number;
  /** Seconds of media to keep buffered ahead of the playhead. Default 60. */
  aheadSec?: number;
  /** Seconds to keep behind the playhead. Default 30. */
  behindSec?: number;
  /** Bytes of source data per demux batch (= per appended fragment per track). Default 1 MiB. */
  batchBytes?: number;
}

interface Lane {
  track: Track;
  queue: SourceBufferQueue;
  sequence: number;
  /** Track id written into this SourceBuffer's init segment and fragments; stays fixed when the source track changes. */
  muxId: number;
}

const TEXT_SUBTITLES = new Set(["srt", "ass", "webvtt", "tx3g"]);
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export class Player extends Emitter<PlayerEvents> {
  readonly ready: Promise<MediaInfo>;
  info: MediaInfo | null = null;

  private readonly aheadSec: number;
  private readonly behindSec: number;
  private readonly batchBytes: number;
  private demuxer: Demuxer | null = null;
  private ms: MediaSource | null = null;
  private url: string | null = null;
  private lanes: Lane[] = [];
  private offsetSec = 0;
  private gen = 0;
  private abort = new AbortController();
  private pumping: Promise<void> = Promise.resolve();
  private eof = false;
  private destroyed = false;
  private waiting = false;
  private suppressSeek = false;
  private switching: Promise<void> = Promise.resolve();
  private subtitleId: number | null = null;
  private readonly seenCues = new Map<number, Map<string, SubtitleCue>>();
  private readonly onStall = () => this.setWaiting(true);
  private readonly onResume = () => this.setWaiting(false);
  private readonly onSeeking = () => {
    if (this.suppressSeek) {
      this.suppressSeek = false;
      return;
    }
    void this.handleSeeking();
  };
  private readonly onVideoError = () => {
    const err = this.video.error;
    this.fail(new PlayerError("decode", err ? `media element error ${err.code}: ${err.message}` : "media element error"));
  };

  constructor(
    private readonly video: HTMLVideoElement,
    private readonly source: ByteSource,
    private readonly opts: PlayerOptions = {},
  ) {
    super();
    this.aheadSec = opts.aheadSec ?? 60;
    this.behindSec = opts.behindSec ?? 30;
    this.batchBytes = opts.batchBytes ?? 1024 * 1024;
    this.ready = this.start();
    this.ready.catch(() => undefined); // reported through the "error" event as well
  }

  destroy(): void {
    this.destroyed = true;
    this.gen++;
    this.abort.abort();
    this.video.removeEventListener("seeking", this.onSeeking);
    this.video.removeEventListener("error", this.onVideoError);
    this.video.removeEventListener("waiting", this.onStall);
    this.video.removeEventListener("playing", this.onResume);
    this.video.removeEventListener("canplay", this.onResume);
    this.source.close();
    if (this.url) {
      this.video.removeAttribute("src");
      this.video.load();
      URL.revokeObjectURL(this.url);
      this.url = null;
    }
  }

  // ---- tracks --------------------------------------------------------------------------------------------------

  get audioTracks(): Track[] {
    return this.info ? this.info.tracks.filter((t) => t.kind === "audio") : [];
  }

  /** Text subtitle tracks inside the file; image subtitles are not offered. */
  get subtitleTracks(): Track[] {
    return this.info ? this.info.tracks.filter((t) => t.kind === "subtitle" && TEXT_SUBTITLES.has(t.codec)) : [];
  }

  /** Audio tracks in the file that this TV's decoders refuse (for example AC-3 on some TVs): the video plays without them. */
  get unplayableAudio(): Track[] {
    return this.audioTracks.filter((t) => !this.supported(t));
  }

  get currentAudio(): Track | null {
    return this.lanes.find((l) => l.track.kind === "audio")?.track ?? null;
  }

  get currentSubtitle(): number | null {
    return this.subtitleId;
  }

  /** Every cue read so far for a track, in file order. The app draws; the engine only reads. */
  cuesOf(trackId: number): SubtitleCue[] {
    return [...(this.seenCues.get(trackId)?.values() ?? [])];
  }

  selectSubtitle(trackId: number | null): void {
    this.subtitleId = trackId;
    this.emit("subtitle", trackId);
  }

  /** Switches the audio track at the playhead. Resolves when the new audio is queued. */
  selectAudio(trackId: number): Promise<void> {
    const run = this.switching.then(() => this.doSelectAudio(trackId));
    this.switching = run.catch(() => undefined);
    return run;
  }

  private async doSelectAudio(trackId: number): Promise<void> {
    await this.ready;
    const next = this.audioTracks.find((t) => t.id === trackId);
    if (!next) throw new PlayerError("unsupported_codec", `no audio track ${trackId}`);
    const lane = this.lanes.find((l) => l.track.kind === "audio");
    if (lane && lane.track.id === next.id) return;
    if (!this.supported(next)) throw new PlayerError("unsupported_codec", `the TV's decoders refuse audio track ${trackId}`);
    const demuxer = this.demuxer!;
    const t = this.video.currentTime;
    const wasPlaying = !this.video.paused;
    const rate = this.video.playbackRate;

    // stop pumping and drop reads in flight
    const gen = ++this.gen;
    this.abort.abort();
    this.abort = new AbortController();
    await this.pumping;

    try {
      const video = this.lanes.find((l) => l.track.kind === "video")?.track;
      const text = this.subtitleTracks;
      demuxer.setEnabledTracks([video, next, ...text].filter((x): x is Track => !!x).map((x) => x.id));
      if (lane && mimeType(lane.track) === mimeType(next)) {
        // Same codec: keep the MediaSource and the video buffer; swap only the audio buffer.
        lane.queue.reset();
        const r = rangesOf(lane.queue.sb.buffered);
        if (r.length) await lane.queue.remove(0, Math.max(r[r.length - 1][1], this.ms?.duration ?? 0) + 1);
        lane.track = next;
        await lane.queue.append(buildInitSegment(muxTrack(lane)));
        await demuxer.seek(t, this.abort.signal);
      } else {
        // Different codec: a SourceBuffer cannot change type everywhere (changeType needs Chrome 70), so start a new
        // MediaSource at the playhead.
        this.teardownMediaSource();
        this.lanes = [];
        await this.attach([video, next].filter((x): x is Track => !!x), this.info!.duration);
        await demuxer.seek(t, this.abort.signal);
        if (t > 0.001) {
          this.suppressSeek = true;
          this.video.currentTime = t;
        }
        this.video.playbackRate = rate;
        if (wasPlaying) void this.video.play().catch(() => undefined);
      }
      if (gen === this.gen) this.startPump();
    } catch (e) {
      const err = toPlayerError(e);
      this.fail(err);
      throw err;
    }
  }

  private teardownMediaSource(): void {
    for (const lane of this.lanes) lane.queue.reset();
    this.video.removeAttribute("src");
    this.video.load();
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = null;
    this.ms = null;
  }

  // ---- startup -------------------------------------------------------------------------------------------------

  private async start(): Promise<MediaInfo> {
    try {
      await this.source.open();
      const head = await this.source.read(0, 16);
      const container = sniffContainer(head);
      if (container === "mp4") this.demuxer = new Mp4Demuxer(this.source);
      else if (container === "mkv") this.demuxer = new MkvDemuxer(this.source);
      else throw new MediaError("unsupported_container", "unknown container");
      const info = await this.demuxer.init();
      this.info = info;

      const video = info.tracks.find((t) => t.kind === "video" && this.supported(t));
      const audio = info.tracks.find((t) => t.kind === "audio" && this.supported(t));
      if (!video && !audio) throw new MediaError("unsupported_codec", "no playable track: the TV's decoders refuse every codec");
      if (info.tracks.some((t) => t.kind === "video") && !video) throw new MediaError("unsupported_codec", "the TV's decoders refuse the video codec");
      // Subtitle tracks are tiny, so every text one is read for the menu; image subtitles are never read.
      const text = info.tracks.filter((t) => t.kind === "subtitle" && TEXT_SUBTITLES.has(t.codec));
      this.demuxer.setEnabledTracks([video, audio, ...text].filter((t): t is Track => !!t).map((t) => t.id));
      this.offsetSec = -info.minDts;

      await this.attach([video, audio].filter((t): t is Track => !!t), info.duration);
      this.video.addEventListener("seeking", this.onSeeking);
      this.video.addEventListener("error", this.onVideoError);
      this.video.addEventListener("waiting", this.onStall);
      this.video.addEventListener("playing", this.onResume);
      this.video.addEventListener("canplay", this.onResume);

      const startAt = this.opts.startAt ?? 0;
      await this.demuxer.seek(startAt, this.abort.signal);
      if (startAt > 0) {
        this.suppressSeek = true; // the demuxer is already there; the seeking event would only restart the reads
        this.video.currentTime = startAt;
      }
      this.startPump();
      return info;
    } catch (e) {
      const err = toPlayerError(e);
      this.fail(err);
      throw err;
    }
  }

  /** Whether this TV's decoders accept the track (MediaSource.isTypeSupported). */
  canPlay(track: Track): boolean {
    return this.supported(track);
  }

  private supported(track: Track): boolean {
    try {
      return MediaSource.isTypeSupported(mimeType(track));
    } catch {
      return false;
    }
  }

  private async attach(tracks: Track[], duration: number): Promise<void> {
    const ms = new MediaSource();
    this.ms = ms;
    this.url = URL.createObjectURL(ms);
    const opened = new Promise<void>((resolve) => ms.addEventListener("sourceopen", () => resolve(), { once: true }));
    this.video.src = this.url;
    await opened;
    if (duration > 0) ms.duration = duration;
    for (const track of tracks) {
      const sb = ms.addSourceBuffer(mimeType(track));
      sb.timestampOffset = -this.offsetSec;
      const queue = new SourceBufferQueue(sb);
      this.lanes.push({ track, queue, sequence: 1, muxId: track.kind === "video" ? 1 : 2 });
    }
    // All SourceBuffers must exist before the first init segment arrives.
    for (const lane of this.lanes) await lane.queue.append(buildInitSegment(muxTrack(lane)));
  }

  // ---- pumping -------------------------------------------------------------------------------------------------

  private startPump(): void {
    const gen = ++this.gen;
    this.eof = false;
    this.pumping = this.pump(gen).catch((e) => {
      if (gen === this.gen && !this.destroyed) this.fail(toPlayerError(e));
    });
  }

  private async pump(gen: number): Promise<void> {
    const demuxer = this.demuxer!;
    const signal = this.abort.signal;
    while (gen === this.gen) {
      if (this.bufferedAhead() >= this.aheadSec) {
        await sleep(250);
        await this.evict(gen);
        continue;
      }
      const batch = await demuxer.next(this.batchBytes, signal);
      if (gen !== this.gen) return;
      if (!batch) {
        this.eof = true;
        if (this.ms && this.ms.readyState === "open") this.ms.endOfStream();
        return;
      }
      this.emitCues(batch);
      for (const lane of this.lanes) {
        const samples = batch.filter((s) => s.trackId === lane.track.id);
        if (samples.length === 0) continue;
        const offsetTicks = Math.round(this.offsetSec * lane.track.timescale);
        const fragment = buildFragment(muxTrack(lane), samples, lane.sequence++, offsetTicks);
        await this.appendWithRoom(lane, fragment, gen);
        if (gen !== this.gen) return;
      }
      await this.evict(gen);
    }
  }

  private async appendWithRoom(lane: Lane, data: Uint8Array, gen: number): Promise<void> {
    for (let attempt = 0; gen === this.gen; attempt++) {
      try {
        await lane.queue.append(data);
        return;
      } catch (e) {
        if (!(e instanceof QuotaError)) throw e;
        if (attempt === 0) {
          await this.evict(gen, true);
        } else {
          // Nothing left behind the playhead to drop: wait for playback to free space.
          await sleep(500);
        }
      }
    }
  }

  private emitCues(batch: Sample[]): void {
    const fresh = new Map<number, SubtitleCue[]>();
    for (const sample of batch) {
      const cue = sample.cue;
      if (!cue) continue;
      let seen = this.seenCues.get(sample.trackId);
      if (!seen) this.seenCues.set(sample.trackId, (seen = new Map()));
      const key = `${cue.start}|${cue.text}`;
      if (seen.has(key)) continue; // re-read after a seek
      seen.set(key, cue);
      const list = fresh.get(sample.trackId) ?? [];
      list.push(cue);
      fresh.set(sample.trackId, list);
    }
    fresh.forEach((cues, id) => this.emit("cues", id, cues));
  }

  private bufferedAhead(): number {
    const t = this.video.currentTime;
    for (const [a, b] of rangesOf(this.video.buffered)) {
      if (t >= a - 0.5 && t <= b) return b - t;
    }
    return 0;
  }

  private async evict(gen: number, hard = false): Promise<void> {
    const keep = hard ? 2 : this.behindSec;
    const cut = this.video.currentTime - keep;
    if (cut <= 0) return;
    for (const lane of this.lanes) {
      if (gen !== this.gen) return;
      const ranges = rangesOf(lane.queue.sb.buffered);
      if (ranges.length === 0 || ranges[0][0] >= cut) continue;
      await lane.queue.remove(0, cut);
    }
  }

  // ---- seeking -------------------------------------------------------------------------------------------------

  private async handleSeeking(): Promise<void> {
    if (this.destroyed || !this.demuxer) return;
    const t = this.video.currentTime;
    const ranges = rangesOf(this.video.buffered);
    const inside = ranges.some(([a, b]) => t >= a && t <= b);
    if (inside) {
      // data is there; make sure the pump keeps filling from where it is
      return;
    }
    // Outside what is buffered: restart demuxing at the keyframe before t.
    const gen = ++this.gen;
    this.abort.abort();
    this.abort = new AbortController();
    await this.pumping;
    if (this.destroyed || gen !== this.gen) return;
    try {
      for (const lane of this.lanes) lane.queue.reset();
      for (const lane of this.lanes) {
        const r = rangesOf(lane.queue.sb.buffered);
        if (r.length) await lane.queue.remove(0, Math.max(r[r.length - 1][1], this.ms?.duration ?? 0) + 1);
      }
      await this.demuxer.seek(t, this.abort.signal);
      if (gen !== this.gen) return;
      this.startPump();
    } catch (e) {
      if (gen === this.gen && !this.destroyed) this.fail(toPlayerError(e));
    }
  }

  private setWaiting(v: boolean): void {
    if (this.waiting !== v) {
      this.waiting = v;
      this.emit("buffering", v);
    }
  }

  private fail(err: PlayerError): void {
    if (this.destroyed) return;
    this.emit("error", err);
  }
}

function muxTrack(lane: Lane): Track {
  return { ...lane.track, id: lane.muxId };
}

export function createPlayer(video: HTMLVideoElement, source: ByteSource, opts?: PlayerOptions): Player {
  return new Player(video, source, opts);
}
