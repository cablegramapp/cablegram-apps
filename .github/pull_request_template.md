## What and why

## Checklist

- [ ] Commits are signed off (`git commit -s`), as described in CONTRIBUTING.md.
- [ ] `./gradlew testDebugUnitTest` passes in each app you changed.
- [ ] A change to the Telegram package is made in both apps (`node scripts/check-telegram-sync.mjs`).
- [ ] A change to what the apps expect from the servers updates `contracts/`.
- [ ] No secrets, tokens or login links in code, tests or logs.
