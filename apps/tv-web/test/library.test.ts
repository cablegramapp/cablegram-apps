import { describe, expect, it } from "vitest";
import { parseTitle } from "../src/api";
import { initialHud, reduceHud } from "../src/hud";
import type { HudContext } from "../src/hud";
import {
  buildShelves, displayTitle, formatRuntime, metadataParts, monogram, placeholderColors, privateThumbnail, remainingLabel, showTitle,
  sourceBadge, toEntries,
} from "../src/library";

const t = (raw: Record<string, unknown>) => parseTitle({ media_type: "movie", ...raw });

describe("artwork matches the Android TV app", () => {
  it("buckets placeholder colours like Theme.kt", () => {
    // Expected buckets computed with Kotlin's String.hashCode mixing: Sintel 4, Big Buck Bunny 3, Mason 6.
    expect(placeholderColors("Sintel")[0]).toBe("#6b6a2e");
    expect(placeholderColors("Big Buck Bunny")[0]).toBe("#3b4f8a");
    expect(placeholderColors("Mason")[0]).toBe("#4a3f7a");
  });
  it("picks private stand-ins with floorMod", () => {
    expect(privateThumbnail("Sintel")).toBe("img/private_thumb_plum.webp");
  });
  it("draws monograms from the first two words", () => {
    expect(monogram("Big Buck Bunny")).toBe("BB");
    expect(monogram("Sintel")).toBe("SI");
    expect(monogram("--")).toBe("C");
  });
});

describe("labels", () => {
  it("hides internal file names and formats episodes", () => {
    expect(showTitle(t({ id: "1", title: "Movie.2019.1080p.WEBRip" }))).toBe("Personal video");
    expect(displayTitle(t({ id: "1", title: "Silo", media_type: "tv", season_number: 1, episode_number: 9 }))).toBe("Silo · S01E09");
  });
  it("formats runtime and time left", () => {
    expect(formatRuntime(6720)).toBe("1h 52m");
    expect(formatRuntime(10)).toBe("1m");
    expect(remainingLabel(t({ id: "1", duration_seconds: 3600, progress: { position_seconds: 600, state: "paused" } }))).toBe("50m left");
  });
  it("lists metadata and sources in Android's order", () => {
    expect(metadataParts(t({ id: "1", year: 2010, duration_seconds: 888, genres: ["A", "B", "C"] }))).toEqual(["2010", "15m", "A, B"]);
    expect(sourceBadge(["phone_local", "telegram", "own_cloud"])).toBe("Telegram · Cloud · Phone");
  });
  it("never shows a private title's artwork", () => {
    const v = t({ id: "1", poster_url: "p", sources: [{ kind: "phone_local", private: true }] });
    expect(v.posterUrl).toBeUndefined();
    expect(v.isPrivate).toBe(true);
  });
});

describe("shelves", () => {
  const videos = [
    t({ id: "m1", title: "Old", created_at: "2026-01-01", genres: ["Drama"], in_my_list: true }),
    t({ id: "m2", title: "New", created_at: "2026-02-01", genres: ["Comedy"], progress: { position_seconds: 5, state: "paused" } }),
    t({ id: "e1", title: "Show", media_type: "tv", tmdb_id: 7, season_number: 1, episode_number: 2, created_at: "2026-03-01" }),
    t({ id: "e2", title: "Show", media_type: "tv", tmdb_id: 7, season_number: 1, episode_number: 1, created_at: "2026-03-02" }),
  ];
  it("groups episodes into one show, sorted by episode", () => {
    const show = toEntries(videos).filter((e) => e.kind === "show")[0];
    expect(show.kind === "show" && show.show.episodes.map((e) => e.id)).toEqual(["e2", "e1"]);
  });
  it("builds Continue, Recently added, My List, then genres", () => {
    expect(buildShelves(videos, "all", null).map((s) => s.id)).toEqual(["continue", "recent", "mylist", "genre:Comedy", "genre:Drama"]);
    expect(buildShelves(videos, "all", null)[1].entries.map((e) => e.id)).toEqual(["tmdb:7", "m2", "m1"]);
  });
  it("keeps Continue and My List as single lists", () => {
    expect(buildShelves(videos, "watchlist", null).map((s) => s.id)).toEqual(["continue"]);
    expect(buildShelves(videos, "mylist", null)[0].entries.map((e) => e.id)).toEqual(["m1"]);
  });
});

describe("player remote", () => {
  const ctx: HudContext = { isPlaying: true, positionMs: 60_000, durationMs: 600_000, audioTracks: [], subtitleTracks: [],
    selectedAudioId: null, selectedSubtitleId: null, speed: 1, aspect: "Fit" };
  it("jumps on a first press and skims on the next", () => {
    const [first, effects] = reduceHud(initialHud, "right", 0, ctx);
    expect(effects).toEqual([{ type: "seekBy", deltaMs: 10_000 }]);
    const [second] = reduceHud(first, "right", 0, ctx);
    expect(second.focus).toBe("timeline");
    expect(second.previewPositionMs).toBe(70_000);
  });
  it("OK pauses and shows the controls; Back exits only when they are hidden", () => {
    const [shown, effects] = reduceHud(initialHud, "ok", 0, ctx);
    expect(effects).toEqual([{ type: "pause" }]);
    expect(shown.controlsVisible).toBe(true);
    expect(reduceHud(shown, "back", 0, ctx)[0].controlsVisible).toBe(false);
    expect(reduceHud(initialHud, "back", 0, ctx)[1]).toEqual([{ type: "exit" }]);
  });
});
