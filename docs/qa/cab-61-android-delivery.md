# CAB-61 Android delivery review — 2026-10-17

Reviewed `main` at `6bc6250e7d76a355b13fafaa73d2e0079e6d7baa`.

This is a point-in-time QA record for 2026-10-17, not living documentation. Test counts,
artifact byte sizes, and repository settings below describe the recorded runs and checks;
later validation should be recorded separately rather than replacing these measurements.

The main implementation already exists in `68751f9` (CAB-8): Android regression tests on
PRs that touch Android/release inputs and every main push, read-only PR Gradle caches,
separate APK/bundle invocations, exhaustive APK signing/verification, and checksums
for all four public-intent APKs. `VERIFY.md` documents the artifact policy.

This follow-up changes `.github/workflows/ci.yml`: capture the changed-file list before
matching it. Previously, `grep -q` could exit early and give `git diff` SIGPIPE under
GitHub's `bash -e -o pipefail`, making a large relevant PR silently skip Android tests.
A failed `git diff` also silently selected `android=false`. Diff failures now fail the
filter job before it writes an output. Main pushes and manual runs still select both apps.

## Validation

Local environment: macOS arm64, JDK 21 (CI specifies 17), Gradle wrapper 8.14.4,
installed Android SDK, existing dependency cache, offline Gradle execution. The first
sandboxed Gradle attempts could not write the home-directory cache lock; reruns with
cache access succeeded.

From each Android app directory:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew testDebugUnitTest --rerun --offline --console=plain
```

Both test tasks executed freshly: phone **375 tests**, TV **290 tests**, zero failures,
errors, or skips. No product source was changed.

Other completed checks:

- `scripts/release/test.sh`: **25 passed, 0 failed**. Includes missing/duplicate/unexpected
  APKs, missing/duplicate bundles, signing/verification failures, missing credentials,
  checksum coverage, and agreement with both Gradle ABI configurations. Signing is stubbed
  in this fixture suite.
- Parsed both workflow YAML files with PyYAML and executed the actual filter `run` block
  using `bash --noprofile --norc -e -o pipefail`, a temporary `git` fixture, and a temporary
  `GITHUB_OUTPUT`. **Nine cases passed**: main, manual dispatch, docs-only PR, phone PR,
  TV PR, packaging PR, release-workflow PR, a large PR with an Android path followed by
  1,000,000 filler paths, and a failed diff (exit 42).
- Repeated the last two cases against the original filter from `HEAD`: both incorrectly
  returned success with `android=false`. The revised filter selects Android for the
  large PR, and returns exit 42 without any output for the failed diff. This safely
  demonstrates failure propagation without adding a failing product test or triggering CI.
- `bash -n scripts/release/package-artifacts.sh scripts/release/checksums.sh scripts/release/test.sh`.
- `git diff --check`.

## Artifact policy

The existing documented policy is retained: two sideload APKs per app, `armeabi-v7a`
and `arm64-v8a`, no universal APK, and one separate Play bundle per app. Only APKs and
`SHA256SUMS` are intended for the GitHub release; bundles are workflow artifacts.

For a tag `<tag>`, the deterministic public-intent manifest is:

```text
cablegram-phone-armeabi-v7a-<tag>.apk
cablegram-phone-arm64-v8a-<tag>.apk
cablegram-tv-armeabi-v7a-<tag>.apk
cablegram-tv-arm64-v8a-<tag>.apk
SHA256SUMS
```

`package-artifacts.sh` requires exactly one build output per ABI and exactly one bundle,
signs and verifies each, and rejects unexpected APKs. `checksums.sh` requires exactly
the four named APKs and writes/verifies one checksum for each. No first-match APK
selection remains. Signing secrets and release publishing behavior were not changed.

## Executed artifact builds

With the same `JAVA_HOME`/`ANDROID_HOME` as above, executed locally without signing
credentials:

```sh
./gradlew assembleRelease bundleRelease --offline --console=plain
./gradlew assembleRelease --offline --console=plain
./gradlew bundleRelease --offline --console=plain
```

The combined builds succeeded for both apps, each producing metadata type `SINGLE`
with no ABI filter:

| App | Combined APK | APK bytes | Bundle | Bundle bytes |
| --- | --- | ---: | --- | ---: |
| Phone | `cablegram-phone-release-unsigned.apk` | 94,146,788 | `cablegram-phone-release.aab` | 45,512,989 |
| TV | `cablegram-tv-release-unsigned.apk` | 292,601,564 | `cablegram-tv-release.aab` | 132,177,978 |

This reproduces why the workflow must keep the two invocations separate.

The separate APK invocations succeeded and replaced the universal APKs with:

| Build output | Bytes | Native ABI verified from ZIP entries |
| --- | ---: | --- |
| `cablegram-phone-armeabi-v7a-release-unsigned.apk` | 21,076,418 | `armeabi-v7a` |
| `cablegram-phone-arm64-v8a-release-unsigned.apk` | 27,696,670 | `arm64-v8a` |
| `cablegram-tv-armeabi-v7a-release-unsigned.apk` | 60,625,330 | `armeabi-v7a` |
| `cablegram-tv-arm64-v8a-release-unsigned.apk` | 79,936,424 | `arm64-v8a` |

AGP metadata lists the split outputs as `ONE_OF_MANY` with the matching ABI filter.
The subsequent separate bundle invocations also succeeded and preserved both APKs for
each app. Final unsigned bundles were 45,512,987 bytes (phone) and 132,177,978 bytes (TV).
The phone bundle is two bytes smaller than the 45,512,989-byte bundle from the combined
invocation above. These are measurements from separate builds, not a transcription error
or a claim that the builds are byte-for-byte reproducible.

## Real local signing and checksums

Generated a one-day RSA key with `keytool -genkeypair` in a temporary directory, then
ran the actual packaging script once per app with that same disposable key:

```sh
scripts/release/package-artifacts.sh phone v0.0.0-cab61-local apps/android-phone
scripts/release/package-artifacts.sh tv v0.0.0-cab61-local apps/android-tv
scripts/release/checksums.sh v0.0.0-cab61-local "$OUT_APK"
```

For those commands, `KEYSTORE`, `KEYSTORE_PASSWORD`, and `KEY_ALIAS` referenced only the
disposable key. `OUT_APK` and `OUT_AAB` were temporary directories; `APKSIGNER` selected
SDK build-tools 36.1.0 and `JARSIGNER` selected JDK 21. **All four real APK signatures,
both real bundle signatures, and all four APK checksums verified successfully.** The
temporary key and signed outputs were deleted automatically afterward. The local tag
argument was only a filename component; no Git tag was created.

Builds emitted existing Kotlin/R8 metadata parsing warnings, unstripped native-library
warnings, source deprecations, and Gradle 9 compatibility warnings. These warnings are
outside this workflow-only change; successful compilation alone does not verify
minified release runtime behavior. No device/runtime behavior is claimed here.

## Release-environment limits

Production signing secrets, attestations, artifact upload/download, and public release
creation are not exercised by this local review.
No tag, deployment, public release, or external artifact upload was created, and CAB-61
was not moved to Done. Local verification does not establish GitHub CI success.

### Repository-settings follow-up — 2026-10-17

A read-only GitHub API check of the effective rules for `main` found repository ruleset
`24204786`. Its required checks are **only `telegram-sync` and `gitleaks`**, with the branch
required to be up to date. Neither `android (android-phone)` nor `android (android-tv)` is
required, so their failures currently do not block merging through this status-check rule.
The classic branch-protection endpoint returned HTTP 404; the effective rules endpoint
succeeded and supplied the ruleset result above.

To enforce the intended Android gate, require both exact Android job names in the ruleset.
Also require `changes`, so a failed file-filter job cannot leave only skipped downstream
Android jobs. Intentional Android skips for docs-only PRs can still satisfy the Android
checks. No repository setting was changed during this check.

### Hosted CI follow-up — 2026-10-17

[PR #96's first CI run](https://github.com/seep-labs/cablegram-apps/actions/runs/39040374910)
failed both Android jobs (phone 8 of 312, TV 1 of 285), each with
`UnsupportedOperationException at DefaultSdkProvider.java:170`. `main` has failed the same
way since `6bc6250` (#95), which moved tests onto Robolectric 4.16. Robolectric needs JDK 21
for the apps' SDK 36, while CI and release used JDK 17. The local runs above used JDK 21,
which is why they passed. Both workflows now set up JDK 21; `sourceCompatibility` and
`jvmTarget` stay at 17, so the app bytecode is unchanged.

Queries used: `GET /repos/cablegramapp/cablegram-apps/branches/main/protection`,
`GET /repos/cablegramapp/cablegram-apps/rules/branches/main`, and `gh pr view --json
number,url,baseRefName,headRefName,statusCheckRollup`.
