#!/usr/bin/env bash
# Collects, signs and verifies the release artifacts of one Android app (see .github/workflows/release.yml).
#
#   package-artifacts.sh <phone|tv> <tag> <module dir, e.g. apps/android-phone>
#
# Intended output per app: one signed APK for each ABI in ABIS (no universal APK, see the `splits` block in the
# app's build.gradle.kts) in OUT_APK, and one signed Play bundle in OUT_AAB. Anything else under the release
# build outputs (a missing ABI, a duplicate, a universal APK) stops the build instead of being picked "first".
#
# Environment: KEYSTORE (path), KEYSTORE_PASSWORD, KEY_ALIAS. Optional: ABIS, OUT_APK, OUT_AAB, APKSIGNER, JARSIGNER.
# The signing tools can be replaced (tests use stubs and a throwaway key); the key itself never appears in output.
set -euo pipefail

app=${1:?usage: package-artifacts.sh <phone|tv> <tag> <module dir>}
tag=${2:?missing tag}
module=${3:?missing module dir}
case "$app" in phone | tv) ;; *) echo "app must be phone or tv, got: $app" >&2; exit 2 ;; esac

ABIS=${ABIS:-"armeabi-v7a arm64-v8a"}
OUT_APK=${OUT_APK:-dist}
OUT_AAB=${OUT_AAB:-dist-play}
JARSIGNER=${JARSIGNER:-jarsigner}
: "${KEYSTORE:?KEYSTORE is not set; a release must be signed.}"
: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is not set}"
: "${KEY_ALIAS:?KEY_ALIAS is not set}"
export KEYSTORE_PASSWORD

fail() { echo "error: $*" >&2; exit 1; }

[[ -f "$KEYSTORE" ]] || fail "keystore not found: $KEYSTORE"
if [[ -z "${APKSIGNER:-}" ]]; then
  [[ -n "${ANDROID_HOME:-}" ]] || fail "APKSIGNER or ANDROID_HOME must be set"
  APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)
  [[ -n "$APKSIGNER" ]] || fail "no apksigner under $ANDROID_HOME/build-tools"
fi

apk_dir="$module/build/outputs/apk/release"
aab_dir="$module/build/outputs/bundle/release"
shopt -s nullglob

# 1. Enumerate: exactly one unsigned APK per intended ABI, and nothing else.
expected=()
for abi in $ABIS; do
  found=("$apk_dir"/*"-$abi-"*.apk)
  [[ ${#found[@]} -ge 1 ]] || fail "no release APK for $abi in $apk_dir"
  [[ ${#found[@]} -eq 1 ]] || fail "more than one release APK for $abi: ${found[*]}"
  expected+=("${found[0]}")
done
all=("$apk_dir"/*.apk)
for apk in "${all[@]}"; do
  known=0
  for e in "${expected[@]}"; do [[ "$apk" == "$e" ]] && known=1; done
  [[ $known -eq 1 ]] || fail "unexpected APK (a universal build?): $apk. Intended ABIs: $ABIS"
done
bundles=("$aab_dir"/*.aab)
[[ ${#bundles[@]} -ge 1 ]] || fail "no app bundle in $aab_dir"
[[ ${#bundles[@]} -eq 1 ]] || fail "more than one app bundle: ${bundles[*]}"

# 2. Sign and verify every APK; a file that does not verify is never left in the output.
mkdir -p "$OUT_APK" "$OUT_AAB"
i=0
for abi in $ABIS; do
  out="$OUT_APK/cablegram-$app-$abi-$tag.apk"
  [[ ! -e "$out" ]] || fail "refusing to overwrite $out"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASSWORD \
    --out "$out" "${expected[$i]}" || { rm -f "$out"; fail "signing failed for $abi"; }
  "$APKSIGNER" verify --print-certs "$out" || { rm -f "$out"; fail "signature of $out does not verify"; }
  i=$((i + 1))
done

# 3. The Play bundle is signed with the same key (the upload key). It is not attached to the public release:
# users cannot install a bundle. It is kept as a run artifact.
bundle_out="$OUT_AAB/cablegram-$app-$tag.aab"
[[ ! -e "$bundle_out" ]] || fail "refusing to overwrite $bundle_out"
cp "${bundles[0]}" "$bundle_out"
"$JARSIGNER" -keystore "$KEYSTORE" -storepass:env KEYSTORE_PASSWORD "$bundle_out" "$KEY_ALIAS" \
  || { rm -f "$bundle_out"; fail "bundle signing failed"; }
"$JARSIGNER" -verify "$bundle_out" || { rm -f "$bundle_out"; fail "bundle signature does not verify"; }

echo "Signed $app $tag: $(for abi in $ABIS; do printf '%s ' "cablegram-$app-$abi-$tag.apk"; done)and $(basename "$bundle_out")"
