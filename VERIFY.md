# Checking that an app matches a release

Every release is built by [the Release workflow](.github/workflows/release.yml) from a tagged commit of this
repository. The release lists each APK with a `SHA256SUMS` file, and each APK carries a build attestation.

## 1. The file you downloaded

```sh
sha256sum cablegram-phone-v0.2.0.apk       # macOS: shasum -a 256
grep cablegram-phone-v0.2.0.apk SHA256SUMS
```

The two hashes must be the same. Download `SHA256SUMS` from the release page itself. A hash you were sent
with the APK by someone else proves nothing.

## 2. That GitHub built it from this repository

```sh
gh attestation verify cablegram-phone-v0.2.0.apk --repo cablegramapp/cablegram-apps
```

This checks that the release workflow in this repository, at the tagged commit, produced that exact file.

## 3. An app already installed on a device

The signing certificate identifies the publisher. Compare it with the one on the release:

```sh
apksigner verify --print-certs cablegram-phone-v0.2.0.apk        # from the release
adb shell pm path app.cablegram.phone                             # TV: app.cablegram
adb pull <path printed above> installed.apk
apksigner verify --print-certs installed.apk
sha256sum installed.apk
```

The certificate SHA-256 digests must be the same. Installed files can differ from the download when Google Play
delivers a split APK for your device; then compare the certificate digest, not the file hash.

## If you installed from Google Play

Play delivers a version of the app built for your device, so its file hash is not the one in `SHA256SUMS`.
If the app was published with Play App Signing, its signing certificate is Google's and not the one on the
GitHub release. Which certificate Play uses is stated on the app's Play page or in the release notes; the
GitHub release is the copy you can check against this repository.

## What this does and does not show

It shows that an APK was built from a public commit and signed with the release key. It does not show that
the Cablegram servers are honest: they are not part of this repository. Builds are not yet reproducible, so
you cannot rebuild the identical file yourself; that is planned.

## The web remote

The web remote is a web app, not an APK, so there is no signature to check. It is published by the
[Web remote workflow](.github/workflows/web-remote.yml) from a `web-v*` tag. The live site serves
`/version.txt` (the tag and the commit) and `/SHA256SUMS` (a hash of every file). Check out that commit and
compare:

```sh
curl -s https://app.cablegram.app/version.txt     # line 1: the tag, line 2: the commit
git checkout <that commit>
cd apps/web-remote
GITHUB_REF_NAME=<that tag> GITHUB_SHA=<that commit> npm run build
cd dist && curl -s https://app.cablegram.app/SHA256SUMS | sha256sum -c -
```

Every line must say `OK`. A web app is delivered fresh each time it loads, so this
shows what is served now, not what a later visit will serve. The app never holds a Telegram session, which is
what the code in `src/` lets you confirm.
