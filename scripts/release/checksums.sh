#!/usr/bin/env bash
# Writes and checks SHA256SUMS for the public release (see .github/workflows/release.yml).
#
#   checksums.sh <tag> <dir holding the signed APKs of both apps>
#
# The directory must hold exactly the intended APKs (phone and tv, one per ABI in ABIS) and nothing else; each
# gets one line in SHA256SUMS, and the file is verified before the script succeeds.
set -euo pipefail

tag=${1:?usage: checksums.sh <tag> <dir>}
dir=${2:?missing dir}
ABIS=${ABIS:-"armeabi-v7a arm64-v8a"}

fail() { echo "error: $*" >&2; exit 1; }
if command -v sha256sum >/dev/null; then sum() { sha256sum "$@"; }; else sum() { shasum -a 256 "$@"; }; fi
shopt -s nullglob

cd "$dir"
expected=()
for app in phone tv; do
  for abi in $ABIS; do expected+=("cablegram-$app-$abi-$tag.apk"); done
done
for name in "${expected[@]}"; do [[ -f "$name" ]] || fail "missing expected artifact: $name"; done
for apk in *.apk; do
  known=0
  for name in "${expected[@]}"; do [[ "$apk" == "$name" ]] && known=1; done
  [[ $known -eq 1 ]] || fail "unexpected artifact: $apk"
done

sum "${expected[@]}" > SHA256SUMS
[[ $(wc -l < SHA256SUMS) -eq ${#expected[@]} ]] || fail "SHA256SUMS does not list every artifact exactly once"
for name in "${expected[@]}"; do
  [[ $(grep -c -F " $name" SHA256SUMS) -eq 1 ]] || fail "SHA256SUMS has no single entry for $name"
done
sum -c SHA256SUMS
