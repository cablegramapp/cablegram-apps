# TV signature-video journeys — 2026-10-08

## Behavior

- A cold launch and a new launcher opening of the existing TV activity select a random signature
  clip. It plays once, full screen, with a focused **Skip** button. Select/OK activates Skip;
  Back also dismisses the opening clip. Normal activity recreation does not create another opening.
- Every resolved playback attempt, including immediately ready and demo titles, enters a signature
  preparation screen. Movie resolution runs in parallel. A ready movie waits for the first complete
  clip cycle; longer preparation keeps the same clip looping and starts the movie as soon as it is
  ready after that first cycle. Each new attempt selects a new random clip from the existing list.
- Private approval keeps its phone action; phone-offline preparation keeps its recovery instructions.
  Back cancels the pending attempt. Attempt IDs reject late clip callbacks and stale ready results.
- Signature playback is released when its screen disappears. A clip error lets a ready movie through;
  an eight-second no-start or fifteen-second no-progress watchdog prevents a decorative clip from
  blocking the app. These limits do not replace approval or movie preparation checks.

## Passing checks

- Full TV unit suite: **212 tests**, zero failures, errors, or skips. Includes four new gate checks for
  ready-before-clip, clip-before-ready, superseded attempts, and cancellation.
- Final isolated debug and instrumentation builds passed: `.signatureqa`, arm64-v8a,
  `ALLOW_NO_TELEGRAM=true`, unavailable local API `http://127.0.0.1:1/` for acceptance checks.
  The ordinary API default remains unchanged in source configuration.
- CG_TV_1080p emulator, API 36, 1920×1080: Maestro passed cold launch → Skip → Home → warm launcher
  opening → Back. [Flow](signature-journeys-evidence/open-and-reopen.yaml).
- Native test used the real view model and `CablegramApp`, an isolated demo catalog, and a downloaded
  copy of the existing ten-second public signature clip. It verified automatic opening completion,
  fresh opening IDs, rejection of an old opening callback, a full clip before an immediately ready
  demo movie, cancellation, and physical Select/OK activating the focused Skip button.
  [Result](signature-journeys-evidence/journeys-instrumentation.txt).
- Maestro passed the preparing heading, ready-after-clip message, and screenshot assertions during
  that native test. [Flow](signature-journeys-evidence/preparing.yaml),
  [preparing screenshot](signature-journeys-evidence/preparing-ui/2026-10-08_191204/preparing/takeScreenshot/preparing-signature.png).
- Native approval regression with the updated shared video component verified at least two real
  loop boundaries, gaps between decoder advances below two seconds, Back cancellation, and release.
  [Result](signature-journeys-evidence/approval-regression.txt).
- A second native journey test verified that a missing clip does not block opening or ready playback.
  It also captured and visually checked the loaded full-screen opening clip and Skip button.
  [Result](signature-journeys-evidence/fallback-instrumentation.txt),
  [opening screenshot](signature-journeys-evidence/opening-playing.png).
- Whitespace check passed. Isolated QA packages and the temporary emulator clip were removed;
  existing app installations and accounts were preserved.

## Test coordination failures retained

The first approval regression observed five continuous loops, but the external Back action arrived
past the harness's sixty-second deadline. Its failed result is retained as
`approval-regression-missed-back.txt`. Added an optional harness argument to send the real Back key
immediately after two observed loop boundaries; that corrected native run passed. Both concurrent
Maestro approval flows arrived after the short-lived harness screen had already closed and failed
on the initial heading assertion. Their reports remain under `approval-ui` and
`approval-corrected-ui`; neither is counted as a UI pass. The passing opening and preparation flows
and native Back/release checks above are separate observations.

## Limits

No real-device acceptance or complete authenticated phone-to-TV approval journey was performed.
A ready demo URL exercised the production view-model/UI gate, not playback from every serving source.
Long preparation ordering has unit evidence and continuous-loop device evidence; a slow authenticated
backend was not separately exercised. Missing-file fallback was observed; HTTP outages, prolonged
decoder stalls, backgrounding during an active movie, and system font scaling were not individually
accepted. Opening and preparation screenshots were visually inspected after decoding began.
