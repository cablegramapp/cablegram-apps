// The library's shelves, shows and labels: a port of the Android TV app's LibraryScreen.kt logic, kept pure so it is
// testable and the two TVs show the same rows in the same order.
import type { Title } from "./types";

export const RECEIVING_TITLE = "Receiving...";

const GRADIENTS: Array<[string, string]> = [
  ["#8a5a2b", "#1e140c"], ["#2d6b66", "#0c1a19"], ["#6e3558", "#1a0d16"], ["#3b4f8a", "#0e1322"],
  ["#6b6a2e", "#19180b"], ["#8a3f32", "#1f0e0b"], ["#4a3f7a", "#110f1f"], ["#2f6b45", "#0b1a10"],
];

/** Java's String.hashCode, so a title gets the same colours here as on Android TV. */
function javaHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return h;
}

/** The same title always gets the same colours, so a missing poster still looks deliberate. */
export function placeholderColors(seed: string): [string, string] {
  // String.hashCode() clusters for similar titles; mix it before bucketing (as Theme.kt does).
  const mixed = Math.imul(javaHash(seed), -0x61c88647) >>> 16;
  return GRADIENTS[mixed % GRADIENTS.length];
}

const PRIVATE_THUMBS = ["midnight", "plum", "teal", "ember", "aurora", "forest"];

/** The branded stand-in for a private title: the TV never shows a private video's own artwork. */
export function privateThumbnail(key: string): string {
  const n = PRIVATE_THUMBS.length;
  return `img/private_thumb_${PRIVATE_THUMBS[((javaHash(key) % n) + n) % n]}.webp`;
}

const isLetterOrDigit = (c: string | undefined) => !!c && (c.toLowerCase() !== c.toUpperCase() || (c >= "0" && c <= "9"));

/** One or two letters that identify a title on its placeholder artwork. */
export function monogram(title: string): string {
  const words = title.split(/[ \-_.:]/).filter((w) => isLetterOrDigit(w[0]));
  if (!words.length) return "C";
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

function looksLikeInternalIdentifier(s: string): boolean {
  if (s.length >= 12 && /^[0-9_-]+$/.test(s)) return true;
  const n = s.toLowerCase();
  return /webrip|bluray|softsub|1080p|2160p/.test(n) || /^(hot|telegram|file)[ _-]/.test(n);
}

export function showTitle(t: Title): string {
  const candidate = t.title?.trim();
  return candidate && candidate !== RECEIVING_TITLE && !looksLikeInternalIdentifier(candidate) ? candidate : "Personal video";
}

const pad2 = (n: number) => (n < 10 ? `0${n}` : String(n));

export function displayTitle(t: Title): string {
  const base = showTitle(t);
  return t.mediaType === "tv" && t.season != null && t.episode != null ? `${base} · S${pad2(t.season)}E${pad2(t.episode)}` : base;
}

export function episodeBadge(t: Title): string | undefined {
  if (t.season != null && t.episode != null) return `S${t.season} · E${t.episode}`;
  if (t.episode != null) return `E${t.episode}`;
  return undefined;
}

export const episodeCaption = (t: Title) => t.episodeTitle?.trim() || (t.episode != null ? `Episode ${t.episode}` : displayTitle(t));

/** "1h 52m" / "48m". */
export function formatRuntime(seconds: number): string {
  const minutes = Math.floor((seconds + 30) / 60);
  return minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${Math.max(1, minutes)}m`;
}

export function remainingLabel(t: Title): string | undefined {
  const p = t.resumeSeconds, d = t.durationSeconds;
  if (!p || !d || p <= 0 || d <= 0 || p >= d) return undefined;
  return `${formatRuntime(d - p)} left`;
}

export function progressFraction(t: Title): number {
  const p = t.resumeSeconds ?? 0;
  if (!t.durationSeconds) return p > 0 ? 0.35 : 0;
  return Math.min(1, Math.max(0, p / t.durationSeconds));
}

export function metadataParts(t: Title): string[] {
  return [
    t.mediaType === "tv" ? episodeBadge(t) : t.year != null ? String(t.year) : undefined,
    t.durationSeconds && t.durationSeconds > 0 ? formatRuntime(t.durationSeconds) : undefined,
    t.genres.slice(0, 2).join(", ") || undefined,
  ].filter((p): p is string => !!p);
}

/** The poster badge naming where a title plays from, e.g. "Phone · Telegram". */
export function sourceBadge(kinds: string[]): string | undefined {
  const names: string[] = [];
  ([["telegram", "Telegram"], ["own_cloud", "Cloud"], ["cloud_object", "Cloud"], ["phone_local", "Phone"], ["web", "Web"]] as const)
    .forEach(([kind, name]) => { if (kinds.indexOf(kind) >= 0 && names.indexOf(name) < 0) names.push(name); });
  return names.join(" · ") || undefined;
}

export interface Show { id: string; title: string; episodes: Title[]; poster?: string; addedAt: string }

export type Entry =
  | { kind: "movie"; id: string; title: string; video: Title }
  | { kind: "show"; id: string; title: string; show: Show };

const max = (a: string, b: string) => (a > b ? a : b);

function makeShow(id: string, episodes: Title[]): Show {
  const sorted = episodes.slice().sort((a, b) =>
    (a.season ?? Infinity) - (b.season ?? Infinity) || (a.episode ?? Infinity) - (b.episode ?? Infinity) ||
    (a.addedAt < b.addedAt ? -1 : a.addedAt > b.addedAt ? 1 : 0));
  const posterUrl = sorted.map((e) => e.posterUrl).filter(Boolean)[0];
  const priv = sorted.some((e) => e.isPrivate);
  return {
    id, episodes: sorted, title: showTitle(sorted[0]),
    poster: posterUrl ?? (priv ? privateThumbnail(id) : undefined),
    addedAt: sorted.map((e) => e.addedAt).reduce(max, ""),
  };
}

export const tvShowKey = (t: Title) => (t.tmdbId != null ? `tmdb:${t.tmdbId}` : `title:${showTitle(t).trim().toLowerCase()}`);

/** Movies stay single; episodes group into one entry per show. */
export function toEntries(videos: Title[]): Entry[] {
  const groups: Record<string, Title[]> = {};
  const order: string[] = [];
  videos.filter((v) => v.mediaType === "tv").forEach((v) => {
    const k = tvShowKey(v);
    if (!groups[k]) { groups[k] = []; order.push(k); }
    groups[k].push(v);
  });
  const movies: Entry[] = videos.filter((v) => v.mediaType !== "tv").map((v) => ({ kind: "movie", id: v.id, title: showTitle(v), video: v }));
  const shows: Entry[] = order.map((k) => { const show = makeShow(k, groups[k]); return { kind: "show", id: k, title: show.title, show }; });
  return movies.concat(shows);
}

export const entryAddedAt = (e: Entry) => (e.kind === "movie" ? e.video.addedAt : e.show.addedAt);
export const entryInMyList = (e: Entry) => (e.kind === "movie" ? e.video.inMyList : e.show.episodes.some((x) => x.inMyList));
export const heroEpisode = (s: Show) => s.episodes.reduce((a, b) => ((b.resumeSeconds ?? 0) > (a.resumeSeconds ?? 0) ? b : a));
/** The episode to land on: the one in progress, else the first. */
export const nextEpisode = (s: Show) => s.episodes.filter((e) => (e.resumeSeconds ?? 0) > 0)[0] ?? s.episodes[0];
export const entryProgress = (e: Entry) => progressFraction(e.kind === "movie" ? e.video : heroEpisode(e.show));
export const entryOverview = (e: Entry) => (e.kind === "movie" ? e.video.overview : heroEpisode(e.show).overview);
export const entryBackdrop = (e: Entry) => (e.kind === "movie" ? e.video.backdropUrl : e.show.episodes.map((x) => x.backdropUrl).filter(Boolean)[0]);
export const entryIds = (e: Entry) => (e.kind === "movie" ? [e.video.id] : e.show.episodes.map((x) => x.id));
const resumeOf = (e: Entry) => (e.kind === "movie" ? e.video.resumeSeconds ?? 0 : Math.max(...e.show.episodes.map((x) => x.resumeSeconds ?? 0)));

export function seasonSummary(s: Show): string {
  const seasons: number[] = [];
  s.episodes.forEach((e) => { if (e.season != null && seasons.indexOf(e.season) < 0) seasons.push(e.season); });
  const count = s.episodes.length === 1 ? "1 episode" : `${s.episodes.length} episodes`;
  return seasons.length > 1 ? `${seasons.length} seasons · ${count}` : count;
}

function distinct(list: string[]): string[] { return list.filter((x, i) => list.indexOf(x) === i); }

export function entryMetadata(e: Entry): string[] {
  if (e.kind === "movie") return metadataParts(e.video);
  const genres = distinct(([] as string[]).concat(...e.show.episodes.map((x) => x.genres))).slice(0, 2).join(", ");
  return ["TV series", seasonSummary(e.show), genres].filter(Boolean);
}

export function entryRemaining(e: Entry): string | undefined {
  if (e.kind === "movie") return remainingLabel(e.video);
  const ep = heroEpisode(e.show);
  if (!(ep.resumeSeconds ?? 0)) return undefined;
  return [episodeBadge(ep), remainingLabel(ep)].filter(Boolean).join(" · ");
}

export function entryHint(e: Entry): string {
  const list = entryInMyList(e) ? "Hold OK to remove from list" : "Hold OK to add to My List";
  const first = e.kind === "show" ? "OK for episodes" : (e.video.resumeSeconds ?? 0) > 0 ? "OK to resume" : "OK to play";
  return `${first}  ·  ${list}`;
}

export function entrySourceBadge(e: Entry): string | undefined {
  return sourceBadge(e.kind === "movie" ? e.video.sourceKinds : ([] as string[]).concat(...e.show.episodes.map((x) => x.sourceKinds)));
}

export function allGenres(videos: Title[]): string[] {
  return distinct(([] as string[]).concat(...videos.map((v) => v.genres)).map((g) => g.trim()).filter(Boolean)).sort();
}

export type LibraryType = "all" | "movie" | "tv" | "watchlist" | "mylist";
export interface Shelf { id: string; title: string; entries: Entry[] }

const byAddedDesc = (a: Entry, b: Entry) => (entryAddedAt(a) < entryAddedAt(b) ? 1 : entryAddedAt(a) > entryAddedAt(b) ? -1 : 0);

/** Continue watching, Recently added, My List, then one shelf per genre; Continue and My List alone are single lists. */
export function buildShelves(videos: Title[], type: LibraryType, genre: string | null): Shelf[] {
  const typed = type === "tv" ? videos.filter((v) => v.mediaType === "tv")
    : type === "movie" ? videos.filter((v) => v.mediaType !== "tv")
    : type === "watchlist" ? videos.filter((v) => (v.resumeSeconds ?? 0) > 0)
    : type === "mylist" ? videos.filter((v) => v.inMyList)
    : videos;
  const scoped = genre ? typed.filter((v) => v.genres.indexOf(genre) >= 0) : typed;
  const catalog = toEntries(scoped).sort(byAddedDesc);
  if (!catalog.length) return [];
  const continueWatching = toEntries(scoped.filter((v) => (v.resumeSeconds ?? 0) > 0)).sort((a, b) => resumeOf(b) - resumeOf(a));
  if (type === "watchlist") return [{ id: "continue", title: "Continue watching", entries: continueWatching }];
  if (type === "mylist") return [{ id: "mylist", title: "My List", entries: catalog }];
  const shelves: Shelf[] = [];
  if (continueWatching.length) shelves.push({ id: "continue", title: "Continue watching", entries: continueWatching });
  shelves.push({ id: "recent", title: "Recently added", entries: catalog.slice(0, 24) });
  const myList = toEntries(scoped.filter((v) => v.inMyList)).sort(byAddedDesc);
  if (myList.length) shelves.push({ id: "mylist", title: "My List", entries: myList });
  if (!genre) {
    allGenres(scoped).forEach((g) => {
      const entries = toEntries(scoped.filter((v) => v.genres.indexOf(g) >= 0)).sort(byAddedDesc);
      if (entries.length) shelves.push({ id: `genre:${g}`, title: g, entries });
    });
  }
  return shelves;
}
