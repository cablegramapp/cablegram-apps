# Telegram password keyboard choice — 2026-10-08

## Behavior

- When Telegram requests its two-step verification password, both Settings and the title playback prompt show `custom/pass.mp4` prominently beside **TV keyboard** and **Phone keyboard**. The ten-second custom clip loops while the screen remains visible. Video failure uses the existing branding fallback and leaves choices usable.
- TV keyboard focuses a masked password field and opens the native TV keyboard. Sign in and keyboard Done submit through the existing Telegram session callback; the field is cleared after submission. Busy state prevents another submit.
- Phone keyboard explicitly starts the existing encrypted password-notification flow. Instructions explain opening the Cablegram notification, selecting Enter password, and sending the password. Recomposition does not send another request. A button can send the prompt again; the backend keeps an active request rather than duplicating it.
- Choose keyboard and Back return to the choice, withdrawing the phone request when leaving phone mode. Screen disposal also withdraws it. Request creation and cleanup share one lifecycle effect, including immediate sign-in completion.
- Telegram no longer automatically asks the phone when it first enters NeedsPassword. Phone request errors are separate from Telegram password rejection; absent pairing, API rejection, and connection failures display guidance for retrying or using the TV keyboard.
- Existing title playback-through-phone alternatives and automatic playback after successful TV sign-in remain available. Settings displays the chooser across its body rather than inside the narrow scrolling connection panel.

## Passing checks

- Requested public R2 object downloaded successfully; actual MP4 duration approximately 10 seconds. Settings Maestro screenshots also show the clip decoded directly from its public R2 URL.
- Isolated debug/instrumentation builds passed: `.passwordqa`, arm64-v8a, ALLOW_NO_TELEGRAM=true, unavailable local API `http://127.0.0.1:1/`. Ordinary build defaults are unchanged. [Build](telegram-password-choice-evidence/build.txt).
- **18 TelegramSessionTest and PasswordSealTest regressions passed**, zero failures/errors/skips. Shared Telegram package synchronization passed. [Counts](telegram-password-choice-evidence/unit-results.txt), [regressions](telegram-password-choice-evidence/regressions-build.txt).
- CG_TV_1080p emulator, API36 arm64, 1920×1080: native acceptance used the real password screen, controlled statuses/callbacks, and actual downloaded pass.mp4. It observed a complete clip loop, zero initial phone requests, physical Down/Select choosing phone, no duplicate request after busy recomposition, Back cancelling phone mode, continuous retained video, TV IME opening, dummy password typing and physical Enter/Done submission, missing-clip choices, automatic Connected completion, and request/media cleanup. [Result](telegram-password-choice-evidence/instrumentation.txt).
- [Choice screenshot](telegram-password-choice-evidence/keyboard-choice.png), [phone instructions](telegram-password-choice-evidence/phone-keyboard.png), [TV keyboard before typing](telegram-password-choice-evidence/tv-keyboard.png). Screenshots were visually inspected; password typing used dummy data, not a live Telegram credential.
- Maestro passed choices → physical Phone selection → instructions → Back to choices → Back to leave on the playback entry point. [Flow](telegram-password-choice-evidence/keyboard-choice.yaml), [output](telegram-password-choice-evidence/maestro.txt), [bounded host](telegram-password-choice-evidence/maestro-host.txt).
- The same Maestro flow passed on the actual AboutSettingsPanel password branch using its normal public R2 URL. [Output](telegram-password-choice-evidence/settings-maestro.txt), [host](telegram-password-choice-evidence/settings-host.txt), [Settings screenshot](telegram-password-choice-evidence/settings-maestro-ui/.maestro/tests/2026-10-08_210745/keyboard-choice/takeScreenshot/keyboard-choice.png).
- Source whitespace check passed. Isolated TV QA packages and temporary emulator clip were removed, preserving existing installations/accounts.

## Retained failures and correction

The initial fixture copied into a nonexistent app files directory; media installation was corrected. [Result](telegram-password-choice-evidence/instrumentation-media-install-failed.txt). The second fixture used a text-search API unsupported by the Compose accessibility provider; recursive accessibility-tree traversal corrected the lookup. [Result](telegram-password-choice-evidence/instrumentation-accessibility-lookup-failed.txt).

The next run passed looping, selection, TV keyboard submission, and missing media, then failed immediate completion/cleanup. Phone request creation previously happened in the click before its cleanup effect was installed. Creation and withdrawal now share the phone-mode lifecycle effect, and the complete native test passed. [Failed result](telegram-password-choice-evidence/instrumentation-completion-cleanup-failed.txt), [plan](telegram-password-choice-evidence/plan.md).

## Limits

No physical TV or authenticated phone-to-TV notification/password journey was run. Native UI callbacks are controlled spies, not real Telegram authentication; they establish choice/submission/wiring behavior. Existing encryption/session regression tests establish their tested properties separately. Phone prompt delivery, expiry, request withdrawal at the server, and real API-error responses require authenticated integration acceptance. Maestro hosts the production UI components with controlled password status, not a signed-in household. Font scaling and every physical TV keyboard implementation remain unverified.
