// The player's remote-control state machine: a port of the Android TV app's PlaybackHud.kt, so D-pad behaviour in the
// player is the same on every TV. Audio and subtitle delay rows are left out: an HTML <video> cannot shift either.

export type DpadKey = "up" | "down" | "left" | "right" | "ok" | "back";
export type ControlsFocus = "rewind" | "playPause" | "fastForward" | "timeline" | "audioSubtitles" | "playerSettings";
export type MenuSection = "audio" | "subtitles";
export type OverlayMenu = "closed" | "audioSubtitles" | "playerSettings";
export type SettingsRow = "speed" | "aspect";
export const SETTINGS_ROWS: SettingsRow[] = ["speed", "aspect"];

export const ASPECTS = ["Fit", "Fill", "Crop", "16:9", "4:3", "Zoom"] as const;
export type Aspect = (typeof ASPECTS)[number];
export const SPEEDS = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4];

export const SEEK_STEP_MS = 10_000;
export const AUTO_HIDE_MS = 4_000;
export const SEEK_FLASH_MS = 1_500;
/** Quiet time on the timeline before a skimmed position lands. */
export const SCRUB_COMMIT_MS = 1_200;

export interface HudTrack { id: string | null; label: string }

export interface HudContext {
  isPlaying: boolean; positionMs: number; durationMs: number;
  audioTracks: HudTrack[]; subtitleTracks: HudTrack[];
  selectedAudioId: string | null; selectedSubtitleId: string | null;
  speed: number; aspect: Aspect;
}

export type HudEffect =
  | { type: "exit" } | { type: "play" } | { type: "pause" }
  | { type: "seekBy"; deltaMs: number } | { type: "seekTo"; positionMs: number }
  | { type: "audio"; id: string } | { type: "subtitle"; id: string | null }
  | { type: "speed"; rate: number } | { type: "aspect"; mode: Aspect };

export interface HudState {
  controlsVisible: boolean; overlay: OverlayMenu; focus: ControlsFocus; menuSection: MenuSection; menuIndex: number;
  settingsRow: SettingsRow; previewPositionMs: number | null; seekFlashDeltaMs: number | null; holdMultiplier: number;
}

export const initialHud: HudState = {
  controlsVisible: false, overlay: "closed", focus: "playPause", menuSection: "audio", menuIndex: 0,
  settingsRow: "speed", previewPositionMs: null, seekFlashDeltaMs: null, holdMultiplier: 1,
};

export const menuOpen = (s: HudState) => s.overlay !== "closed";
export const timelineActive = (s: HudState) => s.controlsVisible && !menuOpen(s) && s.focus === "timeline";

export function holdMultiplier(repeatCount: number): number {
  return repeatCount >= 24 ? 10 : repeatCount >= 12 ? 4 : repeatCount >= 6 ? 2 : 1;
}
const seekStepMs = (repeatCount: number) => SEEK_STEP_MS * holdMultiplier(repeatCount);

export function formatSpeed(rate: number): string { return `${rate}×`; }

export function nextSpeed(current: number, delta: number): number {
  let i = SPEEDS.indexOf(current);
  if (i < 0) i = SPEEDS.findIndex((s) => s >= current);
  if (i < 0) i = SPEEDS.length - 1;
  return SPEEDS[Math.min(SPEEDS.length - 1, Math.max(0, i + delta))];
}

export function nextAspect(current: Aspect, delta: number): Aspect {
  const i = ASPECTS.indexOf(current);
  return ASPECTS[Math.min(ASPECTS.length - 1, Math.max(0, i + delta))];
}

export function menuOptions(section: MenuSection, ctx: HudContext): HudTrack[] {
  return section === "audio" ? ctx.audioTracks : [{ id: null, label: "Off" } as HudTrack].concat(ctx.subtitleTracks);
}

type Result = [HudState, HudEffect[]];

export function reduceHud(state: HudState, key: DpadKey, repeatCount: number, ctx: HudContext): Result {
  if (menuOpen(state)) return state.overlay === "playerSettings" ? reduceSettings(state, key, ctx) : reduceTracksMenu(state, key, ctx);
  if (!state.controlsVisible) return reduceFullscreen(state, key, repeatCount, ctx);
  return reduceControls(state, key, repeatCount, ctx);
}

const showControls = (s: HudState): HudState => ({
  ...s, controlsVisible: true, overlay: "closed", focus: "playPause", previewPositionMs: null, seekFlashDeltaMs: null, holdMultiplier: 1,
});
export const hideControls = (s: HudState): HudState => ({
  ...s, controlsVisible: false, overlay: "closed", focus: "playPause", previewPositionMs: null, seekFlashDeltaMs: null, holdMultiplier: 1,
});
const enterTimeline = (s: HudState, ctx: HudContext): HudState => ({
  ...s, focus: "timeline", previewPositionMs: ctx.positionMs, holdMultiplier: 1, seekFlashDeltaMs: null,
});

/**
 * A first press jumps right away. Another press while the seek flash is still up (or a held key) means the viewer is
 * skimming, so hand over to the timeline: the steps accumulate as a preview and land once the remote goes quiet.
 */
function fullscreenSeek(state: HudState, delta: number, repeatCount: number, ctx: HudContext): Result {
  if (state.seekFlashDeltaMs == null && repeatCount === 0) {
    return [{ ...state, seekFlashDeltaMs: delta, controlsVisible: false }, [{ type: "seekBy", deltaMs: delta }]];
  }
  const step = seekStepMs(repeatCount);
  const duration = Math.max(0, ctx.durationMs);
  let target = Math.max(0, ctx.positionMs + (delta < 0 ? -step : step));
  if (duration > 0) target = Math.min(duration, target);
  return [{ ...state, controlsVisible: true, overlay: "closed", focus: "timeline", previewPositionMs: target,
    holdMultiplier: holdMultiplier(repeatCount), seekFlashDeltaMs: null }, []];
}

function reduceFullscreen(state: HudState, key: DpadKey, repeatCount: number, ctx: HudContext): Result {
  switch (key) {
    case "up": case "down": return [showControls(state), []];
    case "left": return fullscreenSeek(state, -SEEK_STEP_MS, repeatCount, ctx);
    case "right": return fullscreenSeek(state, SEEK_STEP_MS, repeatCount, ctx);
    case "ok": return [showControls(state), ctx.isPlaying ? [{ type: "pause" }] : []];
    case "back": return [state, [{ type: "exit" }]];
  }
}

function reduceControls(state: HudState, key: DpadKey, repeatCount: number, ctx: HudContext): Result {
  if (key === "back") return [hideControls(state), []];
  const to = (focus: ControlsFocus): Result => [{ ...state, focus, seekFlashDeltaMs: null }, []];
  switch (state.focus) {
    case "playPause":
      if (key === "left") return to("rewind");
      if (key === "right") return to("fastForward");
      if (key === "down") return [enterTimeline(state, ctx), []];
      if (key === "ok") return [{ ...state, seekFlashDeltaMs: null }, [{ type: ctx.isPlaying ? "pause" : "play" }]];
      return [state, []];
    case "rewind":
      if (key === "right") return to("playPause");
      if (key === "down") return [enterTimeline(state, ctx), []];
      if (key === "ok") return [state, [{ type: "seekBy", deltaMs: -SEEK_STEP_MS }]];
      return [state, []];
    case "fastForward":
      if (key === "left") return to("playPause");
      if (key === "down") return [enterTimeline(state, ctx), []];
      if (key === "ok") return [state, [{ type: "seekBy", deltaMs: SEEK_STEP_MS }]];
      return [state, []];
    case "timeline": return reduceTimeline(state, key, repeatCount, ctx);
    case "audioSubtitles":
      if (key === "up") return [enterTimeline(state, ctx), []];
      if (key === "ok") return [openTracksMenu(state, ctx), []];
      if (key === "right") return to("playerSettings");
      return [state, []];
    case "playerSettings":
      if (key === "up") return [enterTimeline(state, ctx), []];
      if (key === "ok") return [{ ...state, overlay: "playerSettings", settingsRow: "speed" }, []];
      if (key === "left") return to("audioSubtitles");
      return [state, []];
  }
}

function reduceTimeline(state: HudState, key: DpadKey, repeatCount: number, ctx: HudContext): Result {
  const current = state.previewPositionMs ?? ctx.positionMs;
  const duration = Math.max(0, ctx.durationMs);
  const step = seekStepMs(repeatCount);
  const multiplier = holdMultiplier(repeatCount);
  switch (key) {
    case "left": return [{ ...state, previewPositionMs: Math.max(0, current - step), holdMultiplier: multiplier, seekFlashDeltaMs: null }, []];
    case "right": {
      const next = duration > 0 ? Math.min(duration, current + step) : current + step;
      return [{ ...state, previewPositionMs: next, holdMultiplier: multiplier, seekFlashDeltaMs: null }, []];
    }
    case "up": return [{ ...state, focus: "playPause", previewPositionMs: null, holdMultiplier: 1 }, []];
    case "down": return [{ ...state, focus: "audioSubtitles", previewPositionMs: null, holdMultiplier: 1 }, []];
    case "ok": return [{ ...state, previewPositionMs: null, holdMultiplier: 1 }, [{ type: "seekTo", positionMs: Math.max(0, current) }, { type: "play" }]];
    case "back": return [hideControls(state), []];
  }
}

function reduceTracksMenu(state: HudState, key: DpadKey, ctx: HudContext): Result {
  const options = menuOptions(state.menuSection, ctx);
  const last = Math.max(0, options.length - 1);
  switch (key) {
    case "back": return [{ ...state, overlay: "closed", focus: "audioSubtitles", menuIndex: 0 }, []];
    case "up": return [{ ...state, menuIndex: Math.max(0, state.menuIndex - 1) }, []];
    case "down": return [{ ...state, menuIndex: Math.min(last, state.menuIndex + 1) }, []];
    case "left": return [switchSection(state, "audio", ctx), []];
    case "right": return [switchSection(state, "subtitles", ctx), []];
    case "ok": {
      const option = options[state.menuIndex];
      if (!option) return [state, []];
      if (state.menuSection === "audio") return [state, option.id != null ? [{ type: "audio", id: option.id }] : []];
      return [state, [{ type: "subtitle", id: option.id }]];
    }
  }
}

function reduceSettings(state: HudState, key: DpadKey, ctx: HudContext): Result {
  const i = SETTINGS_ROWS.indexOf(state.settingsRow);
  switch (key) {
    case "back": return [{ ...state, overlay: "closed", focus: "playerSettings", settingsRow: "speed" }, []];
    case "up": return [{ ...state, settingsRow: SETTINGS_ROWS[Math.max(0, i - 1)] }, []];
    case "down": return [{ ...state, settingsRow: SETTINGS_ROWS[Math.min(SETTINGS_ROWS.length - 1, i + 1)] }, []];
    case "left": case "right": {
      const d = key === "left" ? -1 : 1;
      return [state, [state.settingsRow === "speed" ? { type: "speed", rate: nextSpeed(ctx.speed, d) } : { type: "aspect", mode: nextAspect(ctx.aspect, d) }]];
    }
    case "ok": return [state, []];
  }
}

function openTracksMenu(state: HudState, ctx: HudContext): HudState {
  const i = menuOptions("audio", ctx).findIndex((o) => o.id === ctx.selectedAudioId);
  return { ...state, overlay: "audioSubtitles", menuSection: "audio", menuIndex: Math.max(0, i) };
}

function switchSection(state: HudState, section: MenuSection, ctx: HudContext): HudState {
  const selected = section === "audio" ? ctx.selectedAudioId : ctx.selectedSubtitleId;
  const i = menuOptions(section, ctx).findIndex((o) => o.id === selected);
  return { ...state, menuSection: section, menuIndex: Math.max(0, i) };
}

export function formatTime(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
  const p = (n: number) => (n < 10 ? `0${n}` : String(n));
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}
