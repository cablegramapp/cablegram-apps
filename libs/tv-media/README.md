# tv-media

Plays MP4 and Matroska (MKV) on Samsung Tizen and LG webOS TVs. It demuxes the file in TypeScript, repackages the
samples as fragmented MP4 and feeds the TV's own hardware decoders through Media Source Extensions. Byte sources are
HTTP range requests and Telegram (MTProto).

Status: milestone 1, in progress. Plan: `docs/plans/tv-media-engine-m1.md`.

Licence: to be decided (the package is `UNLICENSED` until then).

Runs on Chromium 53 (webOS 4): pure TypeScript, no WebAssembly, no BigInt.

## Development

```
npm install
npm run fixtures   # needs ffmpeg; writes fixtures/ (not committed)
npm test
```

## Telegram

`TelegramSource` reads a Telegram file through a small `TelegramFileApi` (`getFile`, `refreshReference`, optional
`migrate`). The library does not depend on GramJS; the app implements the interface on top of it (milestone T8).
It requests whole 1 MiB-aligned chunks (Telegram's offset/limit rules), keeps one request in flight, sits out
`FLOOD_WAIT_X` up to 30 s, refreshes `FILE_REFERENCE_*` once, follows `FILE_MIGRATE_X`, and maps the rest to typed
errors (`telegram_flood`, `telegram_auth`, `telegram_file`, `telegram_error`). CDN redirects never happen because
`getFile` is called with `cdn_supported = false`.

### GramJS on old TV engines (T7.1, static check, 2026-10-19)

GramJS 2.26.22 (MIT) bundled for the browser with esbuild at target ES2015: no WebAssembly; its big-integer library
falls back to plain arrays when `BigInt` is missing (so webOS 4 / Chromium 53 can work); it needs the `buffer` package
(bundled), `Object.values` and `String.prototype.padStart` (core-js from the legacy build covers both), a WebSocket to
Telegram's `*.web.telegram.org/apiws`, and `crypto.subtle` (SHA-1/256, PBKDF2) which browsers expose only to secure
contexts. **Still to verify on a running engine** (T9: Tizen simulator, Chromium 55; webOS 26 simulator): that the
bundle loads, that the app origin has `crypto.subtle`, and that the WebSocket handshake completes. Until then, treat
direct Telegram playback as "newer TVs only".
