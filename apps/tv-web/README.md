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

Plays: copies on the phone (through the relay) and copies in the household's own cloud (`/api/playback/resolve`).

Not the same as Android TV yet:
- **Telegram-hosted titles.** The Android TV app reads them with TDLib, which does not run in a TV web view. The plan
  is an MTProto client in JS (for example mtcute) published as its own open-source library; until then the TV says so.
- **Private titles** (need phone approval, `/api/playback/private-approvals`).
- **Phone over LAN.** A browser cannot pin the phone's self-signed certificate, so the relay carries phone copies.
- **Subtitles inside the video file** (for example SubRip in MKV). Browsers don't expose them and the phone serves only
  `media/` and `poster/`; Cablegram subtitles (picked and synced on the phone) do show, drawn by the player.
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

For design work without a phone, `node scripts/mock-api.mjs 8788` serves sample titles (set `MOCK_VIDEO=<file.webm>` to
make them play); build with `VITE_API_BASE=http://localhost:8788`.

## Build, test, package

```sh
npm install
npm test                 # api, library shelves, artwork, player remote and D-pad navigation tests
npm run build            # type-check + legacy-compatible bundle (Chromium 53+) in dist/
npm run package:webos    # stages build/webos, then: ares-package build/webos -o build   (npm i -g @webos-tools/cli)
npm run package:tizen    # stages build/tizen, then: tizen package -t wgt -s <cert profile> -- build/tizen
```

The webOS package builds and validates with `ares-package`. The Tizen package needs a Samsung certificate profile
(Tizen Studio Certificate Manager), which is yours to create.
