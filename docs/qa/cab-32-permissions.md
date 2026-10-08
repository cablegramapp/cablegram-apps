# CAB-32: system-selected media and release permission audit

Local files use `ACTION_OPEN_DOCUMENT` (multiple videos) and folders use `ACTION_OPEN_DOCUMENT_TREE`.
Read grants are persisted before indexing or remembering a selection. Import remembers chosen folders;
opening one lists its video files and subfolders. Revoked access reports a reselect message. Cablegram
does not enumerate MediaStore or automatically search the device after reinstall. Re-select files to
restore access; a folder grant covers that folder and its descendants, not the whole storage volume.

Android prevents choosing some roots (including internal-storage and Download roots on Android 11+).
Choose a subfolder or use Choose videos for individual files. Files remain in their original storage.
SAF access is subject to provider availability and files being moved/deleted. The implementation uses
read-only persisted access; it does not request broad storage permissions on older Android versions.

References: [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files),
[Play restricted-permission alternatives](https://support.google.com/googleplay/android-developer/answer/16935362),
[Play sensitive permissions](https://support.google.com/googleplay/android-developer/answer/16558241).
System pickers support this feature, so retaining broad media permissions and filing a broad-access
declaration is unnecessary for this implementation.

## Merged release manifests — audited 2026-10-08

Generated with `./gradlew processReleaseMainManifest` independently in both app directories, using
production application IDs and the release dependency graph. No signing/packaging is necessary for
this audit. `node scripts/audit-release-permissions.mjs` checks the generated manifests against the
reviewed permissions; it fails if any permission is added or removed. Re-run after dependency changes.

| Permission | Phone | TV | Purpose / handling |
|---|---|---|---|
| INTERNET | Yes | Yes | API, provider and playback networking |
| ACCESS_NETWORK_STATE | Yes | Yes | Connection state |
| CHANGE_WIFI_MULTICAST_STATE | Yes | Yes | Local TV discovery; normal permission |
| FOREGROUND_SERVICE | Yes | No | User-visible sharing and transfers |
| FOREGROUND_SERVICE_CONNECTED_DEVICE | Yes | No | LAN sharing; foreground service type requires applicable Play declaration |
| FOREGROUND_SERVICE_DATA_SYNC | Yes | No | User-requested transfers; foreground service type requires applicable Play declaration |
| POST_NOTIFICATIONS | Yes | No | Runtime permission; currently requested at app startup; denial supported |
| CAMERA | Yes | No | Runtime permission only for QR pairing; code entry remains an alternative |
| WAKE_LOCK | Yes | No | Keep active sharing/transfer work awake; normal permission |
| Package-local DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | Yes | Yes | AndroidX Core 1.15.0 signature-level protection for internal receivers |

All Android permissions above originate in each app's own manifest. AndroidX Core adds the package-local
signature permission. Cast dependencies add specific package/service visibility queries, not
`QUERY_ALL_PACKAGES`. No dependency adds another dangerous or special permission in these merged manifests.

Both releases have **no** READ_MEDIA_VIDEO, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED,
READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE. They also have no audio recording,
location, contacts, SMS/call-log, broad package visibility, installer or overlay permission. Existing
foreground-service declarations remain a separate Play submission requirement; this change does not
claim those declarations were submitted or approved. See the Data safety draft for the broader release checklist.
