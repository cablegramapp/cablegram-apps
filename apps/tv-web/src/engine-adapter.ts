// Glue between the tv-media engine and the player screen: turns the engine's tracks into menu entries, keeps the
// embedded subtitle cues sorted for drawing, and maps engine errors to the app's wording.
import { createPlayer, HttpRangeSource } from "tv-media";
import type { Player, PlayerError, Track } from "tv-media";
import { relayMessage } from "./api";
import type { HudTrack } from "./hud";
import type { Cue } from "./subtitles";

const LANGUAGES: Record<string, string> = {
  eng: "English", deu: "German", ger: "German", fra: "French", fre: "French", spa: "Spanish", ita: "Italian",
  por: "Portuguese", rus: "Russian", jpn: "Japanese", kor: "Korean", zho: "Chinese", chi: "Chinese", ara: "Arabic",
  tur: "Turkish", nld: "Dutch", dut: "Dutch", pol: "Polish", swe: "Swedish", fas: "Persian", per: "Persian",
  hin: "Hindi", ukr: "Ukrainian", ces: "Czech", cze: "Czech", ell: "Greek", gre: "Greek", heb: "Hebrew",
};

export const EMBEDDED = "emb:";

/** "English · Commentary" from a track's language and name; falls back to "Track 2". */
export function trackLabel(t: { language?: string; name?: string }, position: number): string {
  const lang = t.language ? LANGUAGES[t.language.toLowerCase()] ?? t.language.toUpperCase() : "";
  const name = t.name && t.name.toLowerCase() !== lang.toLowerCase() ? t.name : "";
  return [lang, name].filter(Boolean).join(" · ") || `Track ${position + 1}`;
}

/** Inserts keeping the list sorted by start time; cues re-read after a seek are ignored. */
export function insertCues(list: Cue[], fresh: Cue[]): void {
  for (const c of fresh) {
    let i = list.length;
    while (i > 0 && list[i - 1].start > c.start) i--;
    if (i > 0 && list[i - 1].start === c.start && list[i - 1].text === c.text) continue;
    list.splice(i, 0, { start: c.start, end: c.end, text: c.text });
  }
}

export interface EngineHandle {
  player: Player;
  audioTracks(): HudTrack[];
  selectedAudio(): string | null;
  selectAudio(id: string): void;
  /** Text subtitles inside the file, ids "emb:<track id>". */
  subtitleTracks(): HudTrack[];
  cues(id: string): Cue[];
  destroy(): void;
}

export interface EngineEvents {
  onError(e: PlayerError): void;
}

export function openEngine(video: HTMLVideoElement, url: string, startAt: number, events: EngineEvents): EngineHandle {
  const player = createPlayer(video, new HttpRangeSource(url), { startAt: startAt > 0 ? startAt : undefined });
  const cues = new Map<string, Cue[]>();
  player.on("cues", (trackId, fresh) => {
    const key = EMBEDDED + trackId;
    let list = cues.get(key);
    if (!list) cues.set(key, (list = []));
    insertCues(list, fresh);
  });
  player.on("error", events.onError);
  return {
    player,
    audioTracks: () => player.audioTracks.filter((t) => player.canPlay(t)).map((t, i) => ({ id: EMBEDDED + t.id, label: trackLabel(t, i) })),
    selectedAudio: () => (player.currentAudio ? EMBEDDED + player.currentAudio.id : null),
    selectAudio: (id) => { void player.selectAudio(Number(id.slice(EMBEDDED.length))).catch(() => undefined); },
    subtitleTracks: () => player.subtitleTracks.map((t: Track, i: number) => ({ id: EMBEDDED + t.id, label: `${trackLabel(t, i)} · in file` })),
    cues: (id) => cues.get(id) ?? [],
    destroy: () => player.destroy(),
  };
}

/** What to tell the viewer, or null when the engine failed in a way plain <video> might still survive. */
export function engineFailureMessage(e: PlayerError): string | null {
  switch (e.kind) {
    case "source_http": return relayMessage(e.status ?? null, e.code);
    case "source_network": return relayMessage(null, undefined);
    case "telegram_flood": return "Telegram is limiting downloads right now. Try again in a few minutes.";
    case "telegram_auth": return "This TV isn't signed in to Telegram any more.";
    case "telegram_file": return "This video is no longer available in Telegram.";
    case "telegram_error": return "Telegram couldn't deliver this video.";
    default: return null; // unsupported_*, decode, malformed, buffer: fall back to the plain player
  }
}
