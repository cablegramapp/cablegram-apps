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

## What this does and does not show

It shows that an APK was built from a public commit and signed with the release key. It does not show that
the Cablegram servers are honest: they are not part of this repository. Builds are not yet reproducible, so
you cannot rebuild the identical file yourself; that is planned.
