# Using Cablegram

Cablegram plays videos from your Android phone or connected sources on an Android TV or Google TV.
The phone and TV apps are open source; the Cablegram servers run separately.

## Connect your TV

Open Cablegram on the TV, then use the phone app to pair it with the code shown on the TV.
Choose a profile on the TV before playback. For videos stored on your phone, keep the phone
reachable; using the same Wi-Fi lets the TV stream directly from it.

iPhone users can use the [web remote](https://app.cablegram.app/) in Safari and choose
**Share → Add to Home Screen**. It controls the TV and browses the household library, but cannot
serve videos stored on the iPhone. See the [web remote guide](../apps/web-remote/README.md).

## Choose files and folders

The Android phone app uses the system file and folder pickers. Cablegram can read the files or
folders you choose without requesting access to your entire media library. Files stay in their
original location.

You can remember more than one folder. Removing a remembered folder releases its access; it does
not delete the original files or remove imported library entries. Videos that relied on that
access may need selecting again. If you reinstall the app, revoke access, or move a file, use the
picker again. Android may prevent selecting a storage root; choose a subfolder or individual files.

## Add subtitles

In video details on the phone, choose **Attach subtitle file**, then select an `.srt` or `.vtt`
file, up to 2,000,000 bytes. Check the language and text preview before confirming. You do not need
a subtitle-provider account for a local file.

Confirmation saves the parsed subtitle text to your household so paired TVs can use it. WebVTT
styling and positioning are not retained. You can adjust timing or remove the saved track later.
Restart playback on the TV to load a newly attached track. Cancelling the picker or choosing an
invalid file keeps your existing track. This feature requires a phone and server version that
support local subtitle attachment.

Provider search is optional and uses your own OpenSubtitles or SubDL credentials on the phone.
See the [subtitle contract](../contracts/subtitles.md) for storage and data-flow details.

## Privacy and checking a release

Telegram authentication runs through TDLib on each device. Same-Wi-Fi playback streams from the
phone; remote playback can pass through Cablegram's relay. The relay forwards video bytes without
storing them and is not end-to-end encrypted. See the [privacy policy](https://cablegram.app/privacy.html)
for service data handling and account deletion.

To check a downloaded APK, its signature, or its build attestation, follow [VERIFY.md](../VERIFY.md).
The [API and protocol contracts](../contracts/) describe what the public apps send and receive.

## Get help

If a phone video is unavailable, check that the phone is online and still has access to the selected
file or folder. Try selecting it again if access was revoked or the file moved. If a TV is waiting
for private-video approval, open Cablegram or its notification on the phone and choose **Allow once**.

Report bugs through this repository's Issues tab with the app version, device models, and steps to
reproduce. Keep passwords, tokens, and personal media out of reports. Report security problems
privately using [SECURITY.md](../SECURITY.md).
