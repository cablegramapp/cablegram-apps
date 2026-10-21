#!/usr/bin/env bash
# Release signing on a CI runner: imports the Apple Distribution certificate into a temporary keychain, installs the App Store
# profiles of the app and its two extensions, and writes Config/Local.xcconfig (manual signing, version, build number) and the
# export options for `xcodebuild -exportArchive`. Local builds keep automatic signing; Local.xcconfig is gitignored.
#
# Env: IOS_DISTRIBUTION_CERTIFICATE_BASE64, IOS_DISTRIBUTION_CERTIFICATE_PASSWORD, IOS_PROFILE_APP_BASE64,
#      IOS_PROFILE_SHARE_BASE64, IOS_PROFILE_SERVING_BASE64, MARKETING_VERSION, BUILD_NUMBER.
#      Optional: CABLEGRAM_CAST_APP_ID (Cast console receiver ID; empty keeps Cast off, as in Base.xcconfig).
# Writes IOS_EXPORT_OPTIONS_PLIST to $GITHUB_ENV.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEAM_ID=3S2K758XUS
BUNDLE_ID=app.cablegram.phone
TMP="${RUNNER_TEMP:-$(mktemp -d)}"

for var in IOS_DISTRIBUTION_CERTIFICATE_BASE64 IOS_DISTRIBUTION_CERTIFICATE_PASSWORD IOS_PROFILE_APP_BASE64 \
  IOS_PROFILE_SHARE_BASE64 IOS_PROFILE_SERVING_BASE64 MARKETING_VERSION BUILD_NUMBER; do
  if [[ -z "${!var:-}" ]]; then
    echo "ERROR: ${var} is not set" >&2
    exit 1
  fi
done

KEYCHAIN="${TMP}/cablegram-build.keychain-db"
KEYCHAIN_PASSWORD="$(uuidgen)"
security create-keychain -p "${KEYCHAIN_PASSWORD}" "${KEYCHAIN}"
security set-keychain-settings -lut 21600 "${KEYCHAIN}"
security unlock-keychain -p "${KEYCHAIN_PASSWORD}" "${KEYCHAIN}"
security default-keychain -s "${KEYCHAIN}"

CERT_PATH="${TMP}/distribution.p12"
echo "${IOS_DISTRIBUTION_CERTIFICATE_BASE64}" | base64 -d > "${CERT_PATH}"
security import "${CERT_PATH}" -k "${KEYCHAIN}" -P "${IOS_DISTRIBUTION_CERTIFICATE_PASSWORD}" \
  -T /usr/bin/codesign -T /usr/bin/security
rm -f "${CERT_PATH}"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${KEYCHAIN_PASSWORD}" "${KEYCHAIN}" > /dev/null
security list-keychains -d user -s "${KEYCHAIN}" $(security list-keychains -d user | tr -d '"')

PROFILES_DIR="${HOME}/Library/MobileDevice/Provisioning Profiles"
mkdir -p "${PROFILES_DIR}"

# Installs one profile, checks that it is an App Store profile for the expected bundle id, and prints its name.
install_profile() {
  local base64="$1" bundle_id="$2" path plist name app_id
  path="${TMP}/${bundle_id}.mobileprovision"
  plist="${path}.plist"
  echo "${base64}" | base64 -d > "${path}"
  security cms -D -i "${path}" > "${plist}"
  app_id="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "${plist}")"
  if [[ "${app_id}" != "${TEAM_ID}.${bundle_id}" ]]; then
    echo "ERROR: the profile for ${bundle_id} is for ${app_id}" >&2
    exit 1
  fi
  if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "${plist}" > /dev/null 2>&1; then
    echo "ERROR: the profile for ${bundle_id} lists devices: it is a development or ad hoc profile, not App Store" >&2
    exit 1
  fi
  cp "${path}" "${PROFILES_DIR}/$(/usr/libexec/PlistBuddy -c 'Print UUID' "${plist}").mobileprovision"
  name="$(/usr/libexec/PlistBuddy -c 'Print Name' "${plist}")"
  echo "Installed profile \"${name}\" for ${bundle_id}" >&2
  echo "${name}"
}

PROFILE_APP="$(install_profile "${IOS_PROFILE_APP_BASE64}" "${BUNDLE_ID}")"
PROFILE_SHARE="$(install_profile "${IOS_PROFILE_SHARE_BASE64}" "${BUNDLE_ID}.share")"
PROFILE_SERVING="$(install_profile "${IOS_PROFILE_SERVING_BASE64}" "${BUNDLE_ID}.serving")"

# A .p12 exported from the key row holds only the private key. The certificate is public and every profile carries it: import the
# app profile's copy, so the key and the certificate make an identity either way (a duplicate import is refused, which is fine).
CERT_DER="${TMP}/profile-certificate.cer"
python3 -I -c 'import plistlib, sys; sys.stdout.buffer.write(plistlib.load(open(sys.argv[1], "rb"))["DeveloperCertificates"][0])' \
  "${TMP}/${BUNDLE_ID}.mobileprovision.plist" > "${CERT_DER}"
security import "${CERT_DER}" -k "${KEYCHAIN}" -T /usr/bin/codesign -T /usr/bin/security 2>/dev/null || true
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "${KEYCHAIN_PASSWORD}" "${KEYCHAIN}" > /dev/null

# Fail here, not after a ten-minute archive, when the key does not belong to the profiles' certificate or the certificate is not trusted.
echo "Certificates: $(security find-certificate -a "${KEYCHAIN}" | grep -c '"labl"'), private keys: $(security dump-keychain "${KEYCHAIN}" | grep -c 'class: 0x00000010')"
security find-identity -p codesigning "${KEYCHAIN}"
if ! security find-identity -v -p codesigning "${KEYCHAIN}" | grep -q "Apple Distribution: .*(${TEAM_ID})"; then
  echo "ERROR: no valid \"Apple Distribution\" identity for ${TEAM_ID}: the .p12 key does not match the certificate in the profiles," \
    "or the certificate is not trusted (see the identities above)." >&2
  exit 1
fi

cat >> "${ROOT}/Config/Local.xcconfig" <<EOF
// Written by scripts/setup-ios-signing.sh for the release build.
MARKETING_VERSION = ${MARKETING_VERSION}
CURRENT_PROJECT_VERSION = ${BUILD_NUMBER}
CODE_SIGN_STYLE = Manual
CABLEGRAM_CODE_SIGN_IDENTITY = Apple Distribution
OTHER_CODE_SIGN_FLAGS = --keychain ${KEYCHAIN}
CABLEGRAM_PROFILE_APP = ${PROFILE_APP}
CABLEGRAM_PROFILE_SHARE = ${PROFILE_SHARE}
CABLEGRAM_PROFILE_SERVING = ${PROFILE_SERVING}
EOF

# The Cast receiver ID also names the Bonjour service in Info.plist (_<id>._googlecast._tcp), so only a real ID goes in.
CAST_APP_ID="${CABLEGRAM_CAST_APP_ID:-}"
if [[ -n "${CAST_APP_ID}" ]]; then
  if [[ ! "${CAST_APP_ID}" =~ ^[0-9A-Fa-f]{8}$ ]]; then
    echo "ERROR: CABLEGRAM_CAST_APP_ID is not an 8-character Cast application ID" >&2
    exit 1
  fi
  echo "CABLEGRAM_CAST_APP_ID = ${CAST_APP_ID}" >> "${ROOT}/Config/Local.xcconfig"
  echo "Cast receiver ID set"
else
  echo "No Cast receiver ID: Cast stays off in this build"
fi

EXPORT_OPTIONS="${TMP}/ExportOptions.plist"
cat > "${EXPORT_OPTIONS}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>export</string>
	<key>teamID</key>
	<string>${TEAM_ID}</string>
	<key>signingStyle</key>
	<string>manual</string>
	<key>signingCertificate</key>
	<string>Apple Distribution</string>
	<key>uploadSymbols</key>
	<true/>
	<key>provisioningProfiles</key>
	<dict>
		<key>${BUNDLE_ID}</key>
		<string>${PROFILE_APP}</string>
		<key>${BUNDLE_ID}.share</key>
		<string>${PROFILE_SHARE}</string>
		<key>${BUNDLE_ID}.serving</key>
		<string>${PROFILE_SERVING}</string>
	</dict>
</dict>
</plist>
EOF

echo "IOS_EXPORT_OPTIONS_PLIST=${EXPORT_OPTIONS}" >> "${GITHUB_ENV:-/dev/null}"
echo "iOS signing configured: ${MARKETING_VERSION} (${BUILD_NUMBER})"
