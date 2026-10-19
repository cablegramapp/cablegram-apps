// Cablegram subtitles: the track a person picked and synchronized on the phone, delivered inline as WebVTT with the
// playback (GET /api/subtitles/playback/item/:id). The player draws them itself instead of using <track>: a blob or
// cross-origin track is unreliable on old TV browsers, and drawing them keeps the look the same on every TV.

export interface SmartSubtitle { id: string; label: string; content: string }
export interface Cue { start: number; end: number; text: string }

const TIME = /(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/;

function seconds(stamp: string): number | null {
  const m = TIME.exec(stamp);
  if (!m) return null;
  return (m[1] ? +m[1] : 0) * 3600 + +m[2] * 60 + +m[3] + +(m[4] + "00").slice(0, 3) / 1000;
}

/** WebVTT (or SRT) to cues, sorted by start. Styling tags are dropped; line breaks are kept. */
export function parseVtt(text: string): Cue[] {
  const cues: Cue[] = [];
  const blocks = text.replace(/\r\n?/g, "\n").split(/\n{2,}/);
  for (const block of blocks) {
    const lines = block.split("\n");
    const at = lines.findIndex((l) => l.indexOf("-->") >= 0);
    if (at < 0) continue;
    const [from, to] = lines[at].split("-->");
    const start = seconds(from), end = seconds(to);
    if (start == null || end == null) continue;
    const body = lines.slice(at + 1).join("\n").replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").trim();
    if (body) cues.push({ start, end, text: body });
  }
  return cues.sort((a, b) => a.start - b.start);
}

/** The text showing at [time] (seconds); overlapping cues are joined. */
export function cueText(cues: Cue[], time: number): string {
  let lo = 0, hi = cues.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cues[mid].start <= time) lo = mid + 1; else hi = mid; }
  const out: string[] = [];
  // Cues are short; look back a few in case an earlier, longer one still covers [time].
  for (let i = lo - 1; i >= 0 && i >= lo - 8; i--) if (cues[i].end > time) out.unshift(cues[i].text);
  return out.join("\n");
}

const KEY = "cablegram.tv.subtitles";

/** The viewer's subtitle choice for one video: Off, or which Cablegram subtitle it was made for. */
export interface SubtitleChoice { off: boolean; smartId?: string }

function readAll(): Record<string, SubtitleChoice> {
  try { return JSON.parse(localStorage.getItem(KEY) || "{}") || {}; } catch { return {}; }
}

export function savedChoice(videoId: string): SubtitleChoice | undefined { return readAll()[videoId]; }

export function saveChoice(videoId: string, choice: SubtitleChoice) {
  const all = readAll();
  all[videoId] = choice;
  try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* storage blocked */ }
}

/**
 * Whether to start with the Cablegram subtitle on, as the Android TV app does: one the person just added or changed
 * shows even over an older "Off" for this video; once they choose again for that subtitle, that choice is kept.
 */
export function startWithSmart(smart: SmartSubtitle | undefined, saved: SubtitleChoice | undefined): boolean {
  if (!smart) return false;
  if (!saved || saved.smartId !== smart.id) return true;
  return !saved.off;
}
