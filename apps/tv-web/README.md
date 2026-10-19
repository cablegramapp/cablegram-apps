# Cablegram TV (Samsung Tizen and LG webOS)

One plain TypeScript + Vite web app, packaged twice: a Tizen `.wgt` for Samsung TVs and a webOS `.ipk` for LG TVs.
It follows the same contracts as the Android TV app (`../../contracts`): pairing PIN, profile picker and PIN,
library, resume position and progress.

## Status

The screens follow the Android TV app (`../android-tv`) one to one: same layout, copy, colours, artwork and remote
behaviour. Sizes are the Android dp values doubled (a 1080p TV is 960 dp wide). `src/library.ts` and `src/hud.ts` port
`LibraryScreen.kt` and `PlaybackHud.kt`, so shelves, show grouping, placeholder colours and the player's D-pad state
machine match and are unit-tested.

Works: pairing with PIN boxes and QR code, several accounts on one TV ("Add profile" pairs another), "Who's watching?",
profile PIN keypad (remote number keys too), profile lock (waits for a phone to allow), TV session actions, unpair with a
confirmation (revokes on the server), the library (menu rail, filters, genre cycle, clock, ambient backdrop, hero,
Continue watching / Recently added / My List / genre shelves, shows with an episode row, Hold OK for My List, Back
behaviour with "Press Back again to exit"), Settings, the connected-TV welcome, the player HUD (seek flash, transport,
timeline skimming with preview, Audio & Subtitles with Cablegram subtitles, Player Settings) and the Android error screens.

Plays: copies on the phone (through the relay, with the tv-media engine below) and copies in the household's own cloud
(`/api/playback/resolve`, plain `<video>`).

## Playback engine (`../../libs/tv-media`)

Phone copies do not use `<video src>`. `src/engine-adapter.ts` hands the relay URL to the tv-media library, which reads
the file with HTTP range requests (one request at a time, so one TV holds one relay stream), demuxes MP4 or MKV in
TypeScript, repackages the samples as fragmented MP4 and feeds the TV's own decoders through Media Source Extensions.
What that adds on a TV: **subtitles and audio tracks from inside the file** (listed in Audio & Subtitles, "· in file"),
MKV seeking that does not stall, and a single relay stream per TV. If the engine meets a format it cannot handle
(`unsupported_container`, `unsupported_codec`, a decode error) the player falls back to the plain `<video src>`.

The relay must let the browser read `Content-Range`: the control plane's CORS (`apps/control-plane/src/http/cors.ts`)
needs `access-control-expose-headers: content-range, accept-ranges` for the TV origin. Without it the engine cannot
learn the file size (MP4 and indexed MKV still play; seeking in an MKV without an index needs it).

### Telegram titles (`VITE_TG_DIRECT=1`)

Telegram titles play over MTProto from the TV itself: a GramJS client (lazy chunk, only in builds with the flag) reads
the video's bytes and the same engine plays them. The application's `api_id`/`api_hash` are fetched at run time from the
control plane (`GET /api/telegram/client-config`, allowed per TV) and are never in this repository. The account session
is a GramJS `StringSession` that a developer puts in `localStorage["cablegram.tv.tgSession"]` through the TV's debugger
(`TG_API_ID=… TG_API_HASH=… node scripts/tg-session.mjs` signs an account in and prints one; treat it like a password).
A TV sign-in screen is a later milestone. Without a session the TV says "Telegram isn't set up on this TV yet".
Builds without the flag contain no Telegram code. Verified so far: the GramJS bundle loads and completes the MTProto
handshake in desktop Chrome, loads in the Tizen simulator and fails politely without a session; downloading a real
file needs a signed-in account and has not been run.

Not the same as Android TV yet:
- **Telegram-hosted titles** work only in `VITE_TG_DIRECT=1` builds with a developer-provided session (see above); the
  TV has no Telegram sign-in screen yet, and the Android TV app's TDLib does not run in a TV web view.
- **Private titles** (need phone approval, `/api/playback/private-approvals`).
- **Phone over LAN.** A browser cannot pin the phone's self-signed certificate, so the relay carries phone copies.
- **Image subtitles** (PGS, VobSub) inside a file are not shown; text subtitles in the file (SubRip, ASS as plain text,
  WebVTT, 3GPP timed text) and Cablegram subtitles (picked and synced on the phone) are, drawn by the player.
- **Audio the TV cannot decode** (for example DTS, TrueHD): the engine lists only tracks the TV's decoders accept.
- Remote commands from the phone, the opening clip and prepare timeline, audio/subtitle delay (an HTML `<video>`
  cannot shift either), UI languages other than English.

## Server requirement: CORS

The control plane only answers browser origins listed in `WEB_APP_ORIGINS` (`apps/control-plane/src/http/cors.ts`).
A packaged TV app loads from `file://` (Tizen) or a local origin (webOS), so one of these is needed before a real TV works:
allow those origins on the control plane, or host the app on an origin we control and point the TV at it.
For simulators and local testing use the dev proxy instead:

```sh
node scripts/dev-proxy.mjs 8787 &
VITE_API_BASE=http://localhost:8787 npm run build
```

For design work without a phone, `node scripts/mock-api.mjs 8788` serves sample titles (set `MOCK_VIDEO=<file>` to make
them play; Range requests work, so MP4 and MKV go through the engine); build with `VITE_API_BASE=http://localhost:8788`.

## Build, test, package

```sh
npm install
npm test                 # api, library shelves, artwork, player remote and D-pad navigation tests
npm run build            # type-check + legacy-compatible bundle (Chromium 53+) in dist/
npm run e2e              # headless Chrome against the mock API: pairing, engine playback, audio switch, subtitles, fallback, Telegram messages
npm run package:webos    # stages build/webos, then: ares-package build/webos -o build   (npm i -g @webos-tools/cli)
npm run package:tizen    # stages build/tizen, then: tizen package -t wgt -s <cert profile> -- build/tizen
```

The webOS package builds and validates with `ares-package`. The Tizen package needs a Samsung certificate profile
(Tizen Studio Certificate Manager), which is yours to create.

## Simulators

Run apps from a shell with `env -u ELECTRON_RUN_AS_NODE` (VS Code's shell sets it and breaks Electron and NW.js apps).
`npm run e2e` needs the fixtures: `npm run fixtures` in `../../libs/tv-media` (needs ffmpeg).

- **Samsung (Tizen simulator, Chromium 55):** build with the mock API (`VITE_API_BASE=http://localhost:8788 VITE_TG_DIRECT=1 npm run build`),
  `npm run package:tizen`, start the mock with `MOCK_VIDEO=../../libs/tv-media/fixtures/mkv-demo.mkv`, then
  `~/tizen-studio/tools/sec-tv-simulator/nwjs.app/Contents/MacOS/nwjs --remote-debugging-port=9333 --disableInspector --platform tizentv --tizentvversion 2.0 --resolution 1920X1080 --file <build/tizen/index.html>`.
  The simulator keeps the first installed copy of the app: copy `build/tizen/.` over
  `…/nwjs.app/Contents/Resources/app.nw/appLauncher/app/CblGramTV1/` to update it. `node scripts/sim-tizen.mjs` then drives
  the app over CDP (engine playback, audio switch, embedded subtitle, seek, GramJS chunk).
- **LG (webOS TV 26 simulator, Electron 28 / Chromium 120):** run its binary with `build/webos` after `npm run package:webos`.
  It lists the app in its launcher, but the app's page is not exposed over `--remote-debugging-port`, so it was not driven
  automatically; Chromium 120 behaves like the desktop Chrome that `npm run e2e` uses.
