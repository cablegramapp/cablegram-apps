# Cablegram apps

The Android phone app and Android TV app for [Cablegram](https://cablegram.app): play the videos you
already have on your TV, from your phone. Licensed under [GPL-3.0](LICENSE).

**The apps are open source. The Cablegram servers are not.** This repository holds the two apps and the
contracts they follow. The account, pairing, catalog and relay services run separately and are closed source,
the same model Telegram uses for its own apps.

## What you can check here

- **Telegram credentials go straight to Telegram.** Both apps use TDLib on the device. Your login code,
  password and session never leave it. The Cablegram server only stores which Telegram account and library
  channel a household uses, and passes a TV's login link (`tg://login?token=`) to the phone so you can approve
  it. See [contracts/telegram-link.md](contracts/telegram-link.md); the server parses those bodies strictly and
  rejects unknown fields.
- **The Telegram surface is small and tested.** `TelegramApiSurfaceTest` (in each app, under
  `src/test/java/app/cablegram/telegram`) uses reflection to assert that the methods of `TelegramApi` equal a
  fixed allowlist, and that `uploadVideo` is the only one that sends anything. Adding a method fails the test
  until someone changes the allowlist in review.
- **Nothing is logged that shouldn't be.** `TelegramLogAuditTest` covers log output.
- **Cablegram never stores your video files.** The phone serves them over your local network, or through a
  relay that forwards bytes without keeping them ([contracts/relay-protocol.md](contracts/relay-protocol.md)).

## Layout

| Path | What |
|---|---|
| `apps/android-phone` | Phone app (Kotlin, Jetpack Compose). Package `app.cablegram.phone`. |
| `apps/android-tv` | TV app (Kotlin, Compose for TV, LibVLC). Package `app.cablegram`. |
| `contracts/` | The API and protocols the apps speak: [control API](contracts/control-api.yaml), [pairing](contracts/pairing.md), [LAN media](contracts/lan-media.md), [remote commands](contracts/remote-and-lan.md), [relay](contracts/relay-protocol.md), [Telegram link](contracts/telegram-link.md). |
| `scripts/check-telegram-sync.mjs` | Fails when the phone and TV copies of the Telegram package differ. |

The Telegram package is copied into both apps on purpose, until a shared module exists. Keep both copies
identical.

## Building

You need JDK 17 and the Android SDK (compileSdk 35). Each app is its own Gradle project:

```sh
cd apps/android-phone   # or apps/android-tv
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Telegram features need your own API credentials. Register an application at <https://my.telegram.org>, then
pass them as `-PTELEGRAM_API_ID=... -PTELEGRAM_API_HASH=...` or put `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`
in the app's git-ignored `local.properties`. Without them the app builds with Telegram switched off.

By default the apps talk to `https://api.cablegram.app/`. Override it with `-PCABLEGRAM_API_BASE=https://...`.
The backend is not part of this repository, so a build pointed at your own host needs a server that implements
the [contracts](contracts/); we don't provide or support one.

To keep the APK small, add `-PCABLEGRAM_ABIS=arm64-v8a` (phone) or the ABI of your device.

## Releases

Tagged releases are built and signed by the [Release workflow](.github/workflows/release.yml) and published
with checksums. See [VERIFY.md](VERIFY.md) to check a download or an installed app.

## Forks

Cablegram is an unofficial app and is not made or endorsed by Telegram. A fork must use its own Telegram
`api_id` and `api_hash`, and must not call itself "Telegram". Third-party licences are in [NOTICE](NOTICE).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities as described in [SECURITY.md](SECURITY.md).
