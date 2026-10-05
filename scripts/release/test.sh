#!/usr/bin/env bash
# Checks the release packaging scripts against fixture build outputs with stand-in signing tools: no real key, no
# network, nothing is built, tagged or published. Run: scripts/release/test.sh
#
# Also checks that the ABIs the scripts expect match the `splits` block of both apps' build.gradle.kts.
# With REAL_SIGNING=1 (needs ANDROID_HOME, keytool and jarsigner) it also signs fixture APKs with a throwaway key.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0 failed=0

ok() { pass=$((pass + 1)); echo "ok   - $1"; }
bad() { failed=$((failed + 1)); echo "FAIL - $1"; }
expect_pass() { local name=$1; shift; if "$@" >"$work/out" 2>&1; then ok "$name"; else bad "$name"; sed 's/^/       /' "$work/out"; fi; }
expect_fail() { # name, expected message fragment, command...
  local name=$1 msg=$2; shift 2
  if "$@" >"$work/out" 2>&1; then bad "$name (succeeded)"; elif grep -q -F -- "$msg" "$work/out"; then ok "$name"; else bad "$name (wrong error)"; sed 's/^/       /' "$work/out"; fi
}

# Stand-ins: apksigner copies the input to --out; either tool fails when FAKE_<TOOL>_FAIL is set.
mkdir -p "$work/bin"
cat > "$work/bin/apksigner" <<'STUB'
#!/usr/bin/env bash
if [[ "$1" == sign ]]; then
  [[ -z "${FAKE_SIGN_FAIL:-}" ]] || { echo "signing refused" >&2; exit 1; }
  while [[ $# -gt 0 ]]; do case "$1" in --out) out=$2; shift 2 ;; *) in=$1; shift ;; esac; done
  cp "$in" "$out"
else
  [[ -z "${FAKE_VERIFY_FAIL:-}" ]] || { echo "DOES NOT VERIFY" >&2; exit 1; }
  echo "Signer #1 certificate DN: CN=fixture"
fi
STUB
cat > "$work/bin/jarsigner" <<'STUB'
#!/usr/bin/env bash
[[ -z "${FAKE_JAR_FAIL:-}" ]] || { echo "jar signing refused" >&2; exit 1; }
echo "jar verified."
STUB
chmod +x "$work/bin/apksigner" "$work/bin/jarsigner"
echo fixture-keystore > "$work/release.jks"

# A fixture module with the build outputs of a release build.
module() { # dir, app, abis...
  local dir=$1 app=$2; shift 2
  rm -rf "$dir"; mkdir -p "$dir/build/outputs/apk/release" "$dir/build/outputs/bundle/release"
  for abi in "$@"; do echo "$app $abi" > "$dir/build/outputs/apk/release/cablegram-$app-$abi-release-unsigned.apk"; done
  echo "$app bundle" > "$dir/build/outputs/bundle/release/cablegram-$app-release.aab"
}
package() { # module dir, app, tag; runs in a fresh output dir
  rm -rf "$work/dist" "$work/dist-play"
  ( cd "$work" && APKSIGNER="$work/bin/apksigner" JARSIGNER="$work/bin/jarsigner" KEYSTORE="$work/release.jks" \
      KEYSTORE_PASSWORD=pw KEY_ALIAS=alias "$here/package-artifacts.sh" "${2:-phone}" "${3:-v1.0.0}" "$1" )
}

m="$work/mod"
module "$m" phone armeabi-v7a arm64-v8a
expect_pass "signs one APK per ABI plus the bundle" package "$m"
[[ -f "$work/dist/cablegram-phone-armeabi-v7a-v1.0.0.apk" && -f "$work/dist/cablegram-phone-arm64-v8a-v1.0.0.apk" && -f "$work/dist-play/cablegram-phone-v1.0.0.aab" ]] && ok "outputs have the published names" || bad "outputs have the published names"
[[ $(ls "$work/dist" | wc -l) -eq 2 ]] && ok "no universal APK is produced" || bad "no universal APK is produced"

module "$m" phone arm64-v8a
expect_fail "fails when an ABI is missing" "no release APK for armeabi-v7a" package "$m"

module "$m" phone armeabi-v7a arm64-v8a
cp "$m/build/outputs/apk/release/cablegram-phone-arm64-v8a-release-unsigned.apk" "$m/build/outputs/apk/release/cablegram-phone-arm64-v8a-extra.apk"
expect_fail "fails on a duplicate ABI" "more than one release APK for arm64-v8a" package "$m"

module "$m" phone armeabi-v7a arm64-v8a
echo u > "$m/build/outputs/apk/release/cablegram-phone-universal-release-unsigned.apk"
expect_fail "fails on an unexpected universal APK" "unexpected APK" package "$m"

module "$m" phone armeabi-v7a arm64-v8a; rm "$m"/build/outputs/bundle/release/*.aab
expect_fail "fails without a bundle" "no app bundle" package "$m"

module "$m" phone armeabi-v7a arm64-v8a; echo b > "$m/build/outputs/bundle/release/second.aab"
expect_fail "fails on two bundles" "more than one app bundle" package "$m"

module "$m" phone armeabi-v7a arm64-v8a
FAKE_SIGN_FAIL=1 expect_fail "fails when signing fails" "signing failed" package "$m"
[[ -z "$(ls "$work/dist" 2>/dev/null)" ]] && ok "no unsigned file is left behind" || bad "no unsigned file is left behind"
FAKE_VERIFY_FAIL=1 expect_fail "fails when a signature does not verify" "does not verify" package "$m"
[[ -z "$(ls "$work/dist" 2>/dev/null)" ]] && ok "no unverified file is left behind" || bad "no unverified file is left behind"
FAKE_JAR_FAIL=1 expect_fail "fails when the bundle cannot be signed" "bundle signing failed" package "$m"

expect_fail "fails without a keystore" "keystore not found" bash -c "cd '$work' && APKSIGNER='$work/bin/apksigner' KEYSTORE=/nonexistent KEYSTORE_PASSWORD=pw KEY_ALIAS=a '$here/package-artifacts.sh' phone v1 '$m'"
expect_fail "fails without the keystore password" "KEYSTORE_PASSWORD" bash -c "cd '$work' && env -u KEYSTORE_PASSWORD APKSIGNER='$work/bin/apksigner' KEYSTORE='$work/release.jks' KEY_ALIAS=a '$here/package-artifacts.sh' phone v1 '$m'"

# Checksums over the artifacts of both apps.
sums=$work/release
fill() { rm -rf "$sums"; mkdir -p "$sums"; for app in phone tv; do for abi in armeabi-v7a arm64-v8a; do echo "$app $abi" > "$sums/cablegram-$app-$abi-v1.0.0.apk"; done; done; }
fill
expect_pass "checksums cover all four APKs" "$here/checksums.sh" v1.0.0 "$sums"
[[ $(wc -l < "$sums/SHA256SUMS") -eq 4 ]] && ok "SHA256SUMS has one line per APK" || bad "SHA256SUMS has one line per APK"
fill; rm "$sums/cablegram-tv-arm64-v8a-v1.0.0.apk"
expect_fail "checksums fail on a missing artifact" "missing expected artifact: cablegram-tv-arm64-v8a-v1.0.0.apk" "$here/checksums.sh" v1.0.0 "$sums"
fill; echo x > "$sums/cablegram-phone-v1.0.0.apk"
expect_fail "checksums fail on an unexpected artifact" "unexpected artifact" "$here/checksums.sh" v1.0.0 "$sums"
fill
expect_fail "checksums fail for another tag" "missing expected artifact" "$here/checksums.sh" v2.0.0 "$sums"

# The scripts and the Gradle split configuration must agree on the ABIs.
default_abis=$(sed -n 's/^ABIS=\${ABIS:-"\(.*\)"}$/\1/p' "$here/package-artifacts.sh")
checks_abis=$(sed -n 's/^ABIS=\${ABIS:-"\(.*\)"}$/\1/p' "$here/checksums.sh")
[[ -n "$default_abis" && "$default_abis" == "$checks_abis" ]] && ok "both scripts expect the same ABIs ($default_abis)" || bad "both scripts expect the same ABIs"
for app in android-phone android-tv; do
  gradle=$(grep -E '^\s*include\("' "$root/apps/$app/build.gradle.kts" | sed -E 's/.*include\((.*)\).*/\1/' | tr -d '", ' | tr -s '\n' | sed 's/armeabi-v7aarm64-v8a/armeabi-v7a arm64-v8a/')
  [[ "$gradle" == "$default_abis" ]] && ok "$app splits match the scripts" || bad "$app splits ($gradle) differ from the scripts ($default_abis)"
  grep -q 'isUniversalApk = false' "$root/apps/$app/build.gradle.kts" && ok "$app declares no universal APK" || bad "$app declares no universal APK"
done

if [[ "${REAL_SIGNING:-}" == 1 ]]; then
  : "${ANDROID_HOME:?ANDROID_HOME is needed for REAL_SIGNING=1}"
  keytool -genkeypair -keystore "$work/throwaway.jks" -storepass throwaway -keypass throwaway -alias throwaway \
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=throwaway test key" >/dev/null 2>&1 || bad "keytool could not make the throwaway key"
  apk=$(ls "$root"/apps/android-phone/build/outputs/apk/release/*arm64-v8a*.apk 2>/dev/null | head -n 1 || true)
  if [[ -n "$apk" ]]; then
    module "$m" phone armeabi-v7a arm64-v8a
    cp "$apk" "$m/build/outputs/apk/release/cablegram-phone-arm64-v8a-release-unsigned.apk"
    cp "$apk" "$m/build/outputs/apk/release/cablegram-phone-armeabi-v7a-release-unsigned.apk"
    echo "PK" > "$m/build/outputs/bundle/release/cablegram-phone-release.aab"
    rm -rf "$work/dist" "$work/dist-play"
    # The bundle fixture is not a real zip, so only the APK half is signed for real.
    ( cd "$work" && JARSIGNER="$work/bin/jarsigner" KEYSTORE="$work/throwaway.jks" KEYSTORE_PASSWORD=throwaway KEY_ALIAS=throwaway \
        "$here/package-artifacts.sh" phone v0.0.0-test "$m" ) >"$work/out" 2>&1 && ok "real apksigner signs and verifies fixture APKs" || { bad "real apksigner signs and verifies fixture APKs"; sed 's/^/       /' "$work/out"; }
  else
    echo "skip - no built release APK to sign for real"
  fi
fi

echo; echo "$pass passed, $failed failed"
[[ $failed -eq 0 ]]
