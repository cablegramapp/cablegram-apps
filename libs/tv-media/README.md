# tv-media

Plays MP4 and Matroska (MKV/WebM) on Samsung Tizen and LG webOS TVs. It demuxes the file in TypeScript, repackages the
samples as fragmented MP4 and feeds the TV's own hardware decoders through Media Source Extensions (MSE). Byte sources
are HTTP range requests and Telegram (MTProto). No software video decoding, no WebAssembly.

Status: milestone 1 (`docs/plans/tv-media-engine-m1.md`). Licence: [Apache-2.0](LICENSE), copyright Seep Labs
(the rest of this repository is GPL-3.0; Apache-2.0 code can be used from it). Name: working name `tv-media`.

## Use

```ts
import { createPlayer, HttpRangeSource } from "tv-media";

const player = createPlayer(videoElement, new HttpRangeSource(url), { startAt: 120 });
player.on("cues", (trackId, cues) => { /* draw them yourself */ });
player.on("error", (e) => { /* e.kind, e.status, e.code */ });
const info = await player.ready;                // duration, tracks
player.audioTracks; player.subtitleTracks;      // text subtitles in the file
await player.selectAudio(player.audioTracks[1].id);
player.cuesOf(trackId);                         // everything read so far
player.destroy();
```

The `<video>` element keeps doing time, pause, speed and events; the engine only feeds it. Options: `startAt`,
`aheadSec` (default 60), `behindSec` (default 30), `batchBytes` (default 1 MiB).

Errors (`PlayerError.kind`): `unsupported_container`, `unsupported_codec`, `malformed`, `decode` (from the media
element), `source_http` (`status`, `code` from the server's JSON), `source_network`, `telegram_flood`, `telegram_auth`,
`telegram_file`, `telegram_error`, `buffer`. An app can retry `unsupported_*` and `decode` with a plain `<video src>`.

## What it reads

| | |
|---|---|
| Containers | MP4/MOV (fast-start or `moov` at the end; fragmented MP4 not yet), Matroska/WebM (Cues, no Cues, unknown-size segments and clusters, lacing, stripped frame headers) |
| Video | H.264, HEVC. VP9/AV1 are recognised but not muxed yet (they need `vpcC`/`av1C`) |
| Audio | AAC, AC-3, E-AC-3, MP3, Opus; a track is offered only if `MediaSource.isTypeSupported` accepts it |
| Text subtitles | SubRip, ASS/SSA (plain text: override tags removed), WebVTT, 3GPP timed text, MP4 WebVTT |
| Not read | Image subtitles (PGS, VobSub, DVB), AVI/MPEG-TS/FLV, DRM, live streams, software audio decoding (DTS, TrueHD) |
| Sources | `HttpRangeSource(url)` (one request in flight, 429 back-off, 64 MiB cache, read-ahead), `TelegramSource(api, size)` |

Seeking lands on the keyframe at or before the target (MP4: sample table; MKV: Cues, else bisecting over cluster ids).
Audio track switching keeps the video buffer when the codec is the same and starts a new MediaSource at the playhead
otherwise (`changeType` needs Chrome 70). B-frame video: MP4 uses the edit list, MKV derives decode times from a
16-frame reorder window; the sink shifts the timeline by `MediaInfo.minDts` because `tfdt` cannot be negative.

## Browser floor

TypeScript target ES2017, no WebAssembly, no BigInt: written for LG webOS 4 (Chromium 53). The demuxers and muxer are
checked against ffprobe on every fixture; playback is checked in Chrome for Testing 156 and in the Tizen TV simulator
(Chromium 55). **Not yet run on a real TV.**

## Telegram

`TelegramSource` reads a Telegram file through a small `TelegramFileApi` (`getFile`, `refreshReference`, optional
`migrate`). The library does not depend on GramJS; the app implements the interface on top of it (`apps/tv-web/src/telegram.ts`).
It requests whole 1 MiB-aligned chunks (Telegram's offset/limit rules), keeps one request in flight, sits out
`FLOOD_WAIT_X` up to 30 s, refreshes `FILE_REFERENCE_*` once, follows `FILE_MIGRATE_X`, and maps the rest to typed
errors. CDN redirects never happen because `getFile` is called with `cdn_supported = false`.

GramJS 2.26.22 (MIT) on old engines: no WebAssembly, big-integer falls back when `BigInt` is missing, and it needs the
`buffer` package, a few Node shims (`util.inspect`, `events`, its own `crypto.js`; see `apps/tv-web/vite.config.ts`),
`crypto.subtle` (SHA-1/256, PBKDF2) and a WebSocket to Telegram. Verified: MTProto handshake in desktop Chrome and the
bundle loading in the Tizen simulator (Chromium 55, `crypto.subtle` present on the `file://` origin). Not verified: webOS 4
(Chromium 53), and a download from a real signed-in account.

## MSE buffer limits

The engine keeps at most 60 s ahead and 30 s behind the playhead by default and evicts on `QuotaExceededError`
(drop everything but 2 s behind, then wait for playback). The limits of real TVs are **not measured yet**; desktop Chrome
never hit its quota with the test files. Record per model below when a TV is available.

| Model | Chromium | MSE quota / behaviour | Notes |
|---|---|---|---|
| Chrome for Testing 156 (macOS) | 156 | not reached | H.264, HEVC, AAC, Opus; AC-3 refused |
| Tizen TV simulator | 55 | not reached | H.264, HEVC, AAC, Opus; AC-3 refused |

## Develop

```sh
npm install
npm run fixtures        # needs ffmpeg; writes fixtures/ (not committed)
npm test                # type-check + unit tests (demuxers vs ffprobe, muxer vs ffprobe/ffmpeg, sources)
npm run test:browser    # headless Chrome for Testing (CHROME_PATH or the Playwright cache): playback, seek, buffer policy, audio switch, cues
```

`mkvmerge` is not required: the no-Cues, unknown-size and stripped-header MKV variants are built in `test/mkvtools.ts`.
