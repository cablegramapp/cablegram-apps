# Telegram client credentials from the server

Both native apps fetch the application API ID/hash after Cablegram authentication from
`GET /api/telegram/client-config`. TDLib receives the fetched values in memory; BuildConfig and
release CI no longer contain them. User Telegram authentication remains direct to Telegram.

The endpoint rejects anonymous/revoked callers, browser tokens and temporary TVs without direct
permission. Missing/invalid server configuration returns a fixed 503. Responses are private/no-store.
Production clients require HTTPS, reject redirects and disable HTTP caching for this request.
Debug builds allow HTTP only for localhost, 127.0.0.1 and the emulator host 10.0.2.2.
Network/JSON failures produce fixed messages without retaining response data in error causes.
A failed startup is retryable without replacing TDLib or wiping the account database. The API ID
actually used by each session is retained for verification when approving another device's login.

## Validation (2026-10-08)

- Phone full unit suite: 298 tests, no failures; debug arm64 APK built.
- TV full unit suite: 217 tests, no failures; debug arm64 APK built.
- Control-plane Telegram contracts: 44 tests passed; typecheck and lint passed.
- Tests cover authenticated retrieval, phone token renewal, malformed identity, HTTP rejection,
  redirect rejection, sanitized failures and retry without replacing the session.
- Shared Telegram sources/tests match (`node scripts/check-telegram-sync.mjs`).
- APK inspection: no Telegram credential fields in either generated BuildConfig, and the existing
  API hash was absent from both APKs' DEX, resource tables and native libraries. Values were never printed.
- Existing local server credentials match the previous phone/TV application identity.
- Changed code and new credential tests passed redacted Gitleaks checks; whitespace checks passed.

## Rollout and limits

Deploy the control-plane endpoint first, retaining the existing TELEGRAM_API_ID/TELEGRAM_API_HASH
in the server environment. Then distribute updated apps. No production deployment or emulator
installation was performed for this change; authenticated Telegram login has not been retested
on a device. The previous manually installed pairing build remains available for the user.

A fresh app process needs the server before TDLib starts because there is no persistent cache or
embedded fallback. A running session does not fetch credentials per movie. Remote delivery removes
static APK exposure but an authenticated running client can still reveal these application credentials.
