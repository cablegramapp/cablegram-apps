# CAB-32 verification — 2026-10-08

Branch: `feature/CAB-32-system-media-pickers`, based on apps main `dca9262` (including merged CAB-28).
Tests were performed directly on emulators as requested; no real-device coverage is claimed.
See [test plan](cab-32-plan.md) and [release permission audit](cab-32-permissions.md).

## Passing checks

- Phone: `assembleDebug testDebugUnitTest` with isolated application suffix `.cab32` and local API
  `http://127.0.0.1:3288/`. Final build passed; 293 tests, zero failures/errors/skips.
- TV: `assembleDebug testDebugUnitTest` with suffix `.cab32` and `ALLOW_NO_TELEGRAM=true` because
  local TV Telegram credentials were unavailable. Build passed; 208 tests, zero failures/errors/skips.
- Production-package `processReleaseMainManifest` in both app directories, then
  `node scripts/audit-release-permissions.mjs` (also each app separately): no unexpected or missing
  permissions. Both package-local AndroidX receiver permissions retain signature protection. The
  guard runs before signing in the release workflow. Remote CI was not run in this session.
- Telegram package sync and whitespace checks passed.

## Emulator observations

Pixel_9 (`emulator-5556`, API 37), package `app.cablegram.phone.cab32`, disposable local account;
CG_TV_1080p (`emulator-5554`, API 36), package `app.cablegram.cab32`. Existing app/account data
was preserved. Only synthetic videos were imported; no real provider credentials were used.

Maestro passed file-picker cancellation, selection/import of SelectedClip.mp4, absence of the
unselected control from the library, folder selection/grant, nested browsing and FolderClip.mp4 import.
The remembered folder reopened after an application restart. A revoked folder grant produced
`Folder access could not be saved. Choose the folder again.` Reselecting the folder restored browsing
and cleared the error. The TV emulator reached its pairing screen; no new TV playback claim is made.

Native `PickerAccessInstrumentation` independently opened the selected MP4s, checked their bytes,
confirmed persisted read grants and denied an unselected document. It checked access after application
restart, before folder revocation, after regrant and after a full emulator reboot with the final phone build.
Raw successful results are in [evidence](cab-32-evidence/). The custom test APK is isolated to `.cab32`
and was removed after testing; owner installations were untouched.

## Failures and corrective runs

- Initial folder selection failed because metadata was queried using a tree URI. The provider needed
  the tree's document URI. Corrected the conversion, rebuilt and passed the folder/import/reopen checks.
- A title assertion immediately after import missed the title while the added-video card occupied the
  screen. The library index and independent byte/grant check established the import; a subsequent
  inspected library screen showed SelectedClip and the UI assertion passed.
- A notification dialog selector was absent after Maestro's launch handling; inspected the actual
  account screen and continued with its observed selectors.
- Cleanup of dormant browse state briefly left an orphaned property setter. Compilation caught it;
  removed it and the final build/full unit suite passed.
- Emulator reboot exceeded the 45-second boot observation window. A System UI ANR was captured in
  `cab-32-evidence/emulator-system-ui-anr.png`. After boot completed, the bounded native grant check passed.
- The cached Maestro device server died after reboot. A fresh CLI driver connected but the UI launch
  ended before navigation appeared. Device logs showed the custom test instrumentation finishing and
  force-stopping the target app. A direct normal activity launch returned `Status: ok` and no matching
  fatal app exception. Removed only the isolated test APK before the final corrective UI run.
  That run passed all folder reopening/nested browsing assertions in 23 seconds; see
  `cab-32-evidence/reopen-folder-clean.xml`. The initial failed report remains alongside it.

Each corrective run followed inspection and an identified change in code, test setup or connection;
failed attempts are retained rather than counted as passes. Old CAB-28 real-device results are not used.

## Limits

Android 26–32 and third-party/cloud document providers have no emulator acceptance evidence in this
session. Device reinstallation intentionally loses SAF grants; users select files/folders again.
Release signing, publishing and Play foreground-service declarations were not performed. These are
separate from removing the broad media permissions. See the audit for existing sensitive permissions.

## Multiple-folder follow-up — 2026-10-08

The Import screen now labels the remembered list **Selected folders**, offers **Add folder**, and
provides a removal control for each folder with confirmation. Android selects one tree per picker
visit; additions keep previous selections. Removal releases that tree's saved grant and forgets the
selection, while keeping imported library entries and independent file/folder grants.

- Updated phone build and 293 unit tests passed; Telegram sync and whitespace checks passed.
- Pixel_9/API 37: added CAB32SecondFolder alongside CAB32Folder and the already remembered Nested
  selection. Cancellation kept the selection; all folders persisted through application restart, and
  both primary folders opened. [UI flow](cab-32-evidence/multiple-folders.yaml) passed in 49 seconds.
- Confirmed removal of CAB32SecondFolder, restarted the app, verified it remained absent, and browsed
  CAB32Folder/Nested/FolderClip.mp4. The [corrective flow](cab-32-evidence/remove-folder-corrective.yaml)
  passed in 29 seconds.
- Native checks verified three selected/readable grants before removal, then two after removal. The
  removed tree had no persisted grant and listing it threw SecurityException; the remaining grants
  stayed readable, and both imported library entries remained. See
  [before](cab-32-evidence/multiple-folder-grants.txt) and
  [after](cab-32-evidence/removed-folder-grants.txt). The isolated test APK was removed afterward.
- The initial native check expected two selections but found the existing Nested selection as a third;
  corrected the test precondition. The initial removal UI check used a resource ID that was not exposed
  in the dialog window. Inspected the actual dialog, switched to its visible text, and reran from the
  open confirmation. Initial failed reports remain in the evidence directory.

This follow-up did not execute duplicate reselection or removal/reselection of the folder backing
FolderClip. Those remain additional planned scenarios; the revoked/regranted access checks above
were performed in the original CAB-32 run. No new TV or real-device coverage is claimed.
