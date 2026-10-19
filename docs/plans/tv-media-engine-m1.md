# Plan: TV media engine, milestone 1 (our own demuxing, feeding the TV's player)

Status: built on `feat/tv-web-tizen-webos` (T0–T8, T10 done). T9 passed 2026-10-19 in the Tizen and webOS 26 simulators with two generated H.264/AAC test files (MP4, MKV with SRT) over the real relay; not yet with the founder's own titles. T7.2 passed in the Tizen simulator with a real signed-in account (Telegram MKV and MP4); real TVs and webOS 4 are untested.
Implementer: a Sonnet session. Read this whole file first; it is self-contained.

## 1. Why

The Samsung (Tizen) and LG (webOS) app in `apps/tv-web` plays with a plain `<video src=URL>`. On these TVs that means:

- **No subtitles from inside the file.** LG and Samsung browsers do not expose an MKV's subtitle tracks to a web app.
  Only Cablegram subtitles (WebVTT synced on the phone, `src/subtitles.ts`) show today.
- **Seeking some MKVs stalls** (seen in the Tizen simulator with a 1.2 GB H.264/AAC MKV; another MKV seeks fine).
- **The relay's stream limit is hit by one TV.** The free plan allows 2 concurrent relay streams per household
  (`relay_max_streams: 2`, CableGram repo `apps/control-plane/src/relay/entitlements.ts`), counted per open HTTP
  request. A browser `<video>` opens parallel range requests, so it can use both slots; the relay then answers 429 and
  the player reports a "format error". `src/main.ts` now retries, but the cause stays.
- **No direct Telegram playback.** TDLib does not run in a TV web view, so Telegram titles have no playable URL.

Milestone 1 replaces the URL-based path with our own pipeline: **byte source → demuxer → fragmented MP4 →
Media Source Extensions (MSE) → the TV's hardware decoders.** The same pipeline reads relay copies (HTTP range
requests) and Telegram files (MTProto chunks). It is the first milestone of an open-source TV playback library, so it
is built as a separate library with no Cablegram-specific code.

## 2. Decisions already made (do not re-open)

1. **Decoding stays on the TV.** We only demux and repackage. No software video decoding (no 4K in WebAssembly, and it
   avoids codec patent licensing). Software *audio* decoding is out of scope for this milestone (see §9).
2. **Output is MSE with fragmented MP4 (fMP4)**, on both brands. H.264/HEVC in WebM is not accepted by MSE, so MKV
   content is repackaged into fMP4, not passed through.
3. **Pure TypeScript, no WebAssembly in milestone 1.** LG webOS 4 (2018/19) runs Chromium 53, which has no WebAssembly
   (shipped in Chrome 57) and no BigInt (Chrome 67). The demuxers and muxer are written in TypeScript and must run on
   Chromium 53 (the app already builds a legacy bundle with `@vitejs/plugin-legacy`, target `chrome >= 53`).
4. **One request at a time per title** from the HTTP source, so one TV never holds more than one relay stream.
5. **The existing UI stays.** `src/main.ts` player screen, `src/hud.ts` (remote state machine) and the subtitle
   renderer keep working; the engine sits behind a small interface (§5.6). The plain `<video src>` path remains as a
   fallback.
6. **Telegram client library: GramJS** ([gram-js/gramjs](https://github.com/gram-js/gramjs), MIT). It uses a
   JavaScript big-integer library instead of native BigInt, so it can run on older TV browsers. mtcute needs native
   BigInt, which rules it out on webOS 4. **Verify GramJS actually runs on Chromium 53 early (task T7.1)**; if it does
   not, Telegram playback requires webOS 5+/Tizen 5.5+ and that is acceptable.

## 3. Open questions for the founder (ask before the step that needs them; do not guess)

- **Library licence.** The repo is GPL-3.0. A library meant for other developers and for LG/Samsung is usually
  LGPL-2.1+, MPL-2.0 or Apache-2.0. Ask before §10 step "publish"; until then put `"license": "UNLICENSED"` in
  the library's `package.json` and a one-line README note that the licence is to be decided.
- **Library name.** Working name `tv-media` (folder `libs/tv-media`). Ask before publishing to npm.
- **Telegram api_id/api_hash for the TV.** Android reads them from build config (`TelegramClientCredentials.kt`).
  For the web app use `VITE_TG_API_ID` / `VITE_TG_API_HASH` at build time; never commit values. Ask the founder which
  credentials to use.

## 4. Scope

**In:**
- Containers: MP4/MOV (fast-start and moov-at-end) and Matroska/WebM (MKV).
- Video codecs passed through: H.264 (avc1), HEVC (hev1/hvc1). VP9/AV1 only if the TV's MSE accepts them in fMP4
  (`isTypeSupported` decides; no special work).
- Audio codecs passed through: AAC, AC-3, E-AC-3, MP3, Opus (each only if `MediaSource.isTypeSupported` accepts it).
- Multiple audio tracks with switching.
- Text subtitles from inside the file: MKV `S_TEXT/UTF8` (SRT), `S_TEXT/ASS` and `S_TEXT/SSA` (plain text in M1,
  styling later), `S_TEXT/WEBVTT`; MP4 `tx3g` and `wvtt`. Rendered by the existing overlay renderer.
- Seeking: MP4 via the sample table; MKV via Cues (usually at the end of the file) with a fallback (§5.3).
- Byte sources: HTTP range (relay, also any CORS-enabled URL) and Telegram (MTProto `upload.getFile`).
- Buffer management sized for TVs; resume position; progress reporting as today.

**Out (later milestones):**
- Image subtitles (PGS, VobSub, DVB): list them in the menu as unavailable.
- ASS styling and positioning, karaoke.
- Software audio decoding for codecs the TV refuses (e.g. DTS, TrueHD) → for now pick another audio track, else
  fall back to plain `<video src>`, else show the Android-style error.
- Samsung Tizen WASM Player back end (Samsung-only; app feeds packets). Keep the muxer/sink boundary clean so it can
  be added as another sink.
- AVI, MPEG-TS, FLV containers.
- TV-side Telegram sign-in UI (QR login, 2FA password). M1 uses a session string provided by a developer (§7).
- DRM. Live streams.

## 5. Architecture

### 5.1 Layout

```
libs/tv-media/                  the library (no Cablegram code, no DOM outside sinks)
  package.json  tsconfig.json  vitest.config.ts  README.md
  src/
    index.ts                    public API (createPlayer, types)
    source/
      types.ts                  ByteSource interface
      http.ts                   HttpRangeSource (one request at a time, retries, 429 back-off)
      telegram.ts               TelegramSource (GramJS upload.getFile, 1 MB-aligned chunks) — optional entry point
      cache.ts                  ReadCache (LRU of chunks; read-ahead window)
    demux/
      types.ts                  Demuxer interface, Track, Sample, SubtitleCue
      sniff.ts                  container detection from the first bytes
      mp4/                      box reader, moov/stbl parsing, sample index, tx3g/wvtt
      mkv/                      EBML reader, SeekHead/Info/Tracks/Cues/Cluster, CodecPrivate, block lacing
    mux/
      fmp4.ts                   init segment (ftyp+moov with avcC/hvcC/esds/dac3/dec3/dOps) and fragments (moof+mdat)
      codecs.ts                 RFC 6381 codec strings from CodecPrivate (avc1.PPCCLL, hvc1..., mp4a.40.2, ac-3, ec-3)
    sink/
      mse.ts                    MediaSource + SourceBuffers, append queue, eviction, QuotaExceededError handling
    player/
      engine.ts                 orchestrates source→demux→mux→sink; seek; track switch; buffering policy
      events.ts                 state, errors, buffering, track lists
  test/                         unit tests + fixtures (generated, see §8)
apps/tv-web/
  package.json                  "tv-media": "file:../../libs/tv-media"
  src/engine-adapter.ts         maps the engine to what playerScreen() needs (§5.6)
```

`apps/tv-web` is a standalone npm project (no workspaces). Keep it that way; reference the library with a `file:`
dependency and let Vite bundle it. The library has its own `tsconfig.json` with `"target": "ES2017"` and
`"lib": ["ES2017", "DOM"]`, like `apps/tv-web`.

### 5.2 Byte sources

```ts
interface ByteSource {
  readonly size: number | null;            // known after open()
  open(): Promise<void>;
  read(offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array>; // exactly [offset, offset+length) or less at EOF
  close(): void;
}
```

- **HttpRangeSource(url, headers?)**: `Range: bytes=a-b`, one request in flight per source. Read chunks of 1 MB
  (configurable); a ReadCache keeps recent chunks and reads ahead sequentially while playing. On 429 with
  `relay_streams_exceeded`, wait 2 s × attempt (max 4) and retry; surface other failures with the HTTP status and the
  server's `error` code so `apps/tv-web` can keep using `relayMessage()` from `src/api.ts`. Relay URLs carry the
  ticket as `rt` and the LAN capability as `token`; the source never builds URLs itself.
- **TelegramSource(client, location)**: wraps a GramJS client. Telegram's rules (from core.telegram.org/api/files):
  `offset` and `limit` divisible by 4 KB, `1 MB % limit == 0`, and a request may not cross a 1 MB boundary
  (`offset / 1MiB == (offset + limit - 1) / 1MiB`). So always request whole aligned 1 MB chunks and slice.
  Handle `FILE_MIGRATE_X` (other DC), `FLOOD_WAIT_X` (wait X s), `FILE_REFERENCE_EXPIRED` (re-fetch the message to
  refresh the `file_reference`, then retry), and `upload.fileCdnRedirect` (CDN download with AES-CTR decryption, see
  core.telegram.org/cdn; check whether GramJS handles it and test). Use a dedicated download connection to the media
  DC. Keep at most 2 chunk requests in flight.
- **ReadCache**: chunk-aligned LRU, default 64 MB budget (TVs have little memory), read-ahead of 8–16 MB while
  playing, cancelled on seek.

### 5.3 Demuxers

```ts
interface Demuxer {
  init(): Promise<MediaInfo>;               // tracks, duration, codec configs, subtitle tracks
  seek(timeSec: number): Promise<number>;   // returns the keyframe time actually used
  next(maxBytes: number): Promise<Sample[] | null>; // interleaved samples in decode order; null at end
}
interface Sample { trackId: number; dts: number; pts: number; duration: number; keyframe: boolean; data: Uint8Array }
```

- **MP4**: parse `moov` (find it at the start, or read the last part of the file when `mdat` comes first).
  Build sample tables from `stts/ctts/stsz/stsc/stco|co64/stss`. Fragmented MP4 input (`moof`) may be passed through
  with minimal rewriting. Seek = nearest sync sample at or before the target. Subtitles: `tx3g` (3GPP timed text) and
  `wvtt` samples to cues.
- **MKV**: EBML reader with unknown-size elements. Read `SeekHead` to find `Info`, `Tracks`, `Cues` (often at the end:
  one extra ranged read). Blocks: `SimpleBlock` and `BlockGroup` (with `BlockDuration` for subtitles), lacing
  (Xiph, EBML, fixed), `TimestampScale`, `ContentCompression` header stripping (common in some MKVs: re-add stripped
  bytes). Seek with Cues; **no Cues** → estimate the byte position from bitrate, read forward to the next Cluster
  (resync on the Cluster ID `1F 43 B6 75`), refine. Subtitle tracks: SRT text as is; ASS/SSA: take the Text field
  from the dialogue line and strip `{\...}` override tags.
- Container sniffing: `ftyp`/`moov`/`mdat` at the start → MP4; EBML header `1A 45 DF A3` → MKV; else unsupported.

### 5.4 Muxer (fMP4)

- Init segment per SourceBuffer (one video, one audio): `ftyp`, `moov` with `mvex/trex`, sample entries
  `avc1`(avcC from MKV `CodecPrivate` / MP4 `avcC`), `hvc1`(hvcC), `mp4a`(esds from AAC AudioSpecificConfig),
  `ac-3`(dac3), `ec-3`(dec3), `mp3`→`.mp3` (mp4a object type 0x6B), `Opus`(dOps).
- Fragments: `moof` (`mfhd`, `traf` with `tfhd`, `tfdt`, `trun` with per-sample durations, sizes, flags,
  composition offsets) + `mdat`. Fragment length ~1–2 s of media.
- Codec strings for `isTypeSupported`/`addSourceBuffer` (RFC 6381) built from the codec config, e.g.
  `avc1.64001F`, `hvc1.1.6.L120.90`, `mp4a.40.2`, `ac-3`, `ec-3`.
- AC-3/E-AC-3 in MKV: build `dac3`/`dec3` by parsing the first frame's syncinfo/bsi (no CodecPrivate in MKV).

### 5.5 Sink (MSE) and engine

- One `MediaSource`, two SourceBuffers (video, audio), `mode = "segments"`. Append queue that waits for
  `updateend`. Check `MediaSource.isTypeSupported(...)` per track before choosing tracks.
- Buffer policy: keep ≤ 60 s ahead and ≤ 30 s behind the playhead; `remove()` older ranges; on
  `QuotaExceededError` evict more and retry. Samsung and LG MSE buffers are small (tens to ~100+ MB): measure on
  the real TVs and record the numbers in the README.
- **Seek**: abort source reads, `sourceBuffer.abort()`, demuxer `seek()`, set `timestampOffset` if needed, append
  from the keyframe, then set `video.currentTime`.
- **Audio track switch**: `changeType()` exists only from Chrome 70, so on older TVs recreate the `MediaSource`
  at the current position; on newer ones, if the codec is the same, `abort()` + remove + append the new init
  segment.
- Subtitles: the engine emits cues per subtitle track as samples are demuxed (cues only arrive for the part read so
  far — fine for playback; seeking re-reads). It does not draw.
- Errors: typed (`unsupported_container`, `unsupported_codec`, `source_http` with status/code, `telegram_*`,
  `decode` from the media element). Never swallow.

### 5.6 Interface used by apps/tv-web

```ts
const player = createPlayer(videoElement, source, { startAt?: number });
player.ready: Promise<MediaInfo>;
player.audioTracks / player.subtitleTracks / selectAudio(id) / selectSubtitle(id | null)
player.on("cues", (trackId, cues) => ...)   // feed src/subtitles.ts renderer
player.on("error", (e) => ...)
player.destroy()
```

`apps/tv-web/src/main.ts` `playerScreen()` keeps using the `<video>` element for time, play/pause and events, so
`src/hud.ts` and progress saving need no change. `src/subtitles.ts` gets embedded cues merged with Cablegram cues;
the menu lists Cablegram subtitles first, then embedded tracks (label from language + track name, "· in file").
Choose the engine for phone copies (relay URL) and Telegram titles; keep plain `<video src>` for cloud URLs unless
the container needs the engine; fall back to plain `<video src>` when the engine reports `unsupported_*`.

## 6. Tasks and acceptance criteria (do them in this order)

Commit after each task that passes its checks (Conventional Commits style as in `git log`, e.g.
`feat(tv-media): ...`; end messages with the attribution line the session prompt gives). Do not push; ask first.

| # | Task | Done when |
|---|---|---|
| T0 | Scaffold `libs/tv-media` (package.json, tsconfig, vitest, README with scope + licence TBD), fixtures script (§8), wire `file:` dependency into `apps/tv-web`, legacy build still passes | `npm test` in both folders passes; `apps/tv-web` `npm run build` passes |
| T1 | `HttpRangeSource` + `ReadCache` | unit tests with a fake fetch: alignment, one request in flight, read-ahead, abort on seek, 429 back-off then error with status/code |
| T2 | MP4 demuxer (fast-start and moov-at-end, tx3g/wvtt) | parses all MP4 fixtures; sample counts/timestamps match `ffprobe -show_packets` output stored with the fixtures; seek returns the right keyframe |
| T3 | fMP4 muxer + codec strings (avc1, hvc1, mp4a, ac-3, ec-3, mp3, Opus) | init segments and fragments pass `ffprobe` (write them to disk in a test); codec strings unit-tested |
| T4 | MSE sink + engine (play, seek, buffer eviction) for MP4 | in headless Chrome (Playwright, see §8): MP4 fixtures play, `currentTime` advances, seek to 80 % works, buffered ranges stay within policy |
| T5 | MKV demuxer (EBML, lacing, header stripping, Cues + no-Cues fallback, SRT/ASS/WebVTT) | parses all MKV fixtures; samples match `ffprobe`; seek with and without Cues; subtitle cues correct |
| T6 | Audio track switching + subtitle tracks through the engine | headless Chrome: switch audio on the two-audio fixture without restarting from 0; subtitle cues emitted for SRT and ASS fixtures |
| T7 | `TelegramSource` with GramJS | T7.1: GramJS loads and connects in the Chromium-53 legacy bundle (or document that it can't). T7.2: download aligned chunks from a test channel message; seek reads the right chunk; FLOOD_WAIT/FILE_REFERENCE_EXPIRED handled (unit tests with a fake client) |
| T8 | Integrate into `apps/tv-web` (§5.6), menu, fallback rules, Telegram titles behind a build flag `VITE_TG_DIRECT=1` | app unit tests pass; mock API (`scripts/mock-api.mjs`) gains an MKV fixture with embedded SRT; headless run shows embedded subtitles and audio switching; existing tests still pass |
| T9 | Simulators: Tizen TV simulator and webOS 26 simulator (how to run them: see "Simulators" below) | play the founder's real relay titles in both simulators: the H.264/AAC MKV that did not seek now seeks; embedded SRT shows; only one relay request in flight per TV (check in the dev proxy log) |
| T10 | README for the library (API, supported formats, browser floor, measured MSE buffer limits) and update `apps/tv-web/README.md` | docs written; the "Not the same as Android TV yet" list updated |

Real-TV verification (LG 2023/24 and Samsung 2023/24, then older models) happens when the founder has the TVs;
record results per model in `libs/tv-media/README.md`.

## 7. Telegram details

- Catalog sources for Telegram have `kind: "telegram"` and `origin_identity` `tg:<chat_id>:<message_id>` where
  `chat_id` is a TDLib chat id (channels: `-100<channel_id>`). With MTProto: strip `-100` → `channel_id`, resolve the
  channel's `access_hash` (from the account's dialogs or `channels.getChannels` via a cached entity), then
  `channels.getMessages` → `MessageMediaDocument` → `InputDocumentFileLocation(id, access_hash, file_reference,
  thumb_size="")`. Reference: Android `apps/android-tv/.../TvTelegram.kt` (`streamUrl`), `telegram/TelegramFileReader.kt`
  (windowed reading), `telegram/TelegramLibrarySync.kt` (how identities are built).
- M1 session: a GramJS `StringSession` supplied in development via `localStorage["cablegram.tv.tgSession"]` (set
  through the debugger). Never log it; never commit one. TV sign-in UI is a later milestone.
- Respect Telegram's limits: dedicated download connection, ≤ 2 chunk requests in flight, back off on FLOOD_WAIT.
- NOTICE requirement: forks use their own api_id/api_hash; the app must not call itself "Telegram".

## 8. Testing

- **Fixtures** are generated, not committed: `libs/tv-media/scripts/make-fixtures.sh` uses ffmpeg (installed at
  `/opt/homebrew/bin/ffmpeg`) to create short (10–20 s) files from `testsrc2`/`sine`:
  MP4 fast-start H.264+AAC; MP4 moov-at-end; MP4 with tx3g; MKV H.264+AAC+SRT; MKV H.264+AC-3+ASS; MKV with two audio
  tracks; MKV without Cues (`-cues_to_front 0` and strip Cues, or `mkvmerge --no-cues` if available); MKV with header
  stripping (`mkvmerge --compression 0:header_removal`, only if mkvmerge exists; else skip with a note); HEVC MP4;
  WebM VP9+Opus. Store `ffprobe -show_packets -of json` next to each for comparisons. Add `fixtures/` to `.gitignore`.
- **Unit tests**: Vitest, Node environment, read fixtures from disk through a file ByteSource.
- **Browser tests**: Playwright with Chrome for Testing (already cached at
  `~/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app`; playwright-core can
  be installed in the scratch dir as in this session). Serve fixtures with `vite preview` or a tiny static server
  that supports Range. Note: check `MediaSource.isTypeSupported` for H.264/AAC/AC-3 in that browser first; if AC-3 is
  unsupported there, test AC-3 muxing with ffprobe only.
- **Old-browser check**: the legacy bundle must load in the Tizen simulator (Chromium-55 user agent, older engine
  behaviour) without errors.

### Simulators (from this session's notes)

- Run apps from a shell with `env -u ELECTRON_RUN_AS_NODE` (VS Code's shell sets it and breaks Electron/NW apps).
- Dev proxy: `node apps/tv-web/scripts/dev-proxy.mjs 8787`; build with `VITE_API_BASE=http://localhost:8787`.
- LG: `~/sources/masoud/webOS_TV_26_Simulator_1.5.0/webOS_TV_26_Simulator_1.5.0.app/Contents/MacOS/webOS_TV_26_Simulator_1.5.0 <tv-web>/build/webos`
  after `npm run package:webos`.
- Samsung: `~/tizen-studio/tools/sec-tv-simulator/nwjs.app/Contents/MacOS/nwjs --remote-debugging-port=9333 --disableInspector --platform tizentv --tizentvversion 2.0 --resolution 1920X1080 --file <tv-web>/build/tizen/index.html`
  after `npm run package:tizen`; load the app through CDP on the `ripple.html` page:
  `requirejs("ripple/worker").installHtmlApp([{ path, name: "index.html" }])`. Inspect the app frame
  (`.../appLauncher/app/CblGramTV1/index.html`) with playwright-core `connectOverCDP("http://localhost:9333")`.
- Screenshots: capture the simulator window only (`screencapture -l <window id>`), never the full screen.
- The VPS (api.cablegram.app) is a test server; the founder's Pixel 8 phone serves the relay titles and must be
  online.

## 9. Risks and how to handle them

- **MSE codec support differs per TV.** Always gate on `isTypeSupported`; pick the first supported audio track;
  fall back to plain `<video src>`. Record per-model results.
- **TV MSE buffer limits are small.** Conservative policy (§5.5), evict on QuotaExceededError, measure on real TVs.
- **CPU on old TVs.** Demuxing and muxing are byte copying; keep allocations low (reuse buffers, subarray instead of
  slice where safe). Measure append rate in the simulator; target ≥ 5× real time for a 4 Mbit/s stream.
- **MKV oddities** (no Cues, header stripping, unknown-size clusters, B-frames without CTTS equivalent → compute
  composition offsets from block timestamps in decode order). Cover each with a fixture.
- **GramJS on old engines** may fail (T7.1). Acceptable outcome: Telegram direct playback only on newer TVs.
- **Telegram CDN files** need AES-CTR decryption; if GramJS lacks it, implement per core.telegram.org/cdn.

## 10. After milestone 1 (not part of this work)

TV Telegram sign-in UI; ASS styling; software audio decoding (e.g. AC-3 via a small decoder where the TV lacks it);
Tizen WASM Player sink; licence decision and publishing the library; grant applications (NLnet / Prototype Fund) and
approaching LG/Samsung with a demo on real TVs.
