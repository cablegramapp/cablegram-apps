# Phone LAN credential storage verification

Paired TVs are now saved as one AES-GCM encrypted record in SecretStore, using Android Keystore.
The encrypted record includes LAN capabilities, pairing PINs, device IDs, trust and Cast metadata.
Authenticated additional data binds this ciphertext to the pairing record; account-token ciphertext
cannot be substituted for it. No LAN credential is written to tvs_json or lan_token anymore.

Migration commits ciphertext before removing both legacy plaintext keys. It is idempotent, retains
existing pairing metadata and confirms ciphertext durability before retrying interrupted cleanup.
Corrupt ciphertext or a lost Keystore key returns no pairings and never falls back to plaintext;
those exceptional cases require pairing again. Normal upgrades preserve existing pairings.
PIN-only legacy records remain metadata only and never gain a LAN capability.

## Validation (2026-10-08)

- Phone unit suite: 298 tests passed; isolated arm64 debug app and instrumentation APK built.
- Real Android Keystore acceptance on phone emulator emulator-5556 (API 36), package
  app.cablegram.phone.lanstoreqa: [acceptance result](lan-credential-storage-evidence/acceptance.txt).
- Acceptance covered two-TV migration, preservation of trust/expiry/Cast metadata, no plaintext
  PINs or capabilities in preference files, capability/device/Cast updates, removal, clearing,
  new pairing, interrupted migration cleanup, ciphertext substitution, corruption, key loss
  and PIN-only migration.
- Force-stopped the isolated app and checked persistence in a fresh instrumentation process:
  [restart result](lan-credential-storage-evidence/restart.txt).
- Shared Telegram source/test consistency, whitespace checks and redacted secret scans passed.
- Removed both QA packages after testing. Existing phone/TV installations were preserved.

This verifies storage and migration on an emulator. No physical-device or end-to-end LAN movie
playback test was run for this change. The existing capability authorization and HTTPS transport
continue to be covered by the phone unit tests.
