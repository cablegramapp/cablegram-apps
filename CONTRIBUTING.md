# Contributing

Thanks for helping. This repository holds the phone and TV apps and their contracts. The servers are closed
source, so changes that need a server change are discussed in an issue first.

## Before you open a pull request

- Open an issue for anything larger than a small fix.
- Run the unit tests of the app you changed (`./gradlew testDebugUnitTest` in `apps/android-phone` or
  `apps/android-tv`) and `node scripts/check-telegram-sync.mjs`.
- The Telegram package exists in both apps. Change both copies the same way.
- Changing the `TelegramApi` surface (the allowlist in `TelegramApiSurfaceTest`) needs a clear reason in the
  pull request, because it is what the trust claim in the README rests on.
- Never commit secrets: API ids and hashes, keystores, tokens. CI runs gitleaks.

## Sign-off

Sign your commits with the [Developer Certificate of Origin](https://developercertificate.org/):
`git commit -s`. Your contribution is licensed under GPL-3.0, like the rest of the repository.
