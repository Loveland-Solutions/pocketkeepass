#!/bin/sh
# Builds the Mac app and sends it to App Store Connect, where it shows under TestFlight (macOS)
# and can be chosen for a Mac App Store version:
#
#   tools/release_mac.sh            builds, checks, and uploads
#   tools/release_mac.sh --check    builds and checks, uploads nothing
#
#   1. builds the Rust engine and archives the app with its AutoFill extension (the build
#      number is the commit count, as on Android and iPhone; the version is MARKETING_VERSION);
#   2. has Apple sign a copy for the Mac App Store and checks it: version and build number, the
#      privacy manifest, the sandbox with no network entitlement, the App Group, Keychain
#      group, and AutoFill entitlements, and no paths from this Mac in the binaries;
#   3. uploads it, signed the same way.
#
# Giving a build to testers, and submitting it for review, stay the owner's clicks in App Store
# Connect. Run from a clean commit. Needs, once: a macOS version in the PocketKeePass app record
# (the iPhone's: one listing for both, Apple's "universal purchase", with the same bundle IDs
# mn.lls.pocketkeepass and mn.lls.pocketkeepass.autofill), and the team's App Store Connect API key (see
# tools/release_ios.sh). Unlike the iPhone's, the archive is signed properly: a Mac needs no
# registered device for that.
set -eu

cd "$(dirname "$0")/.."

check=
if [ "${1:-}" = --check ]; then
  check=1
fi

if [ -n "$(git status --porcelain -- apple core)" ]; then
  echo "Commit or set aside the changes first: a release is built from a commit." >&2
  exit 1
fi

project=apple/macOS/PocketKeePass.xcodeproj
version=$(sed -n 's/.*MARKETING_VERSION = \([0-9.]*\);/\1/p' "$project/project.pbxproj" | sort -u)
team=$(sed -n 's/.*DEVELOPMENT_TEAM = \([A-Z0-9]*\);/\1/p' "$project/project.pbxproj" | sort -u)
build=$(git rev-list --count HEAD)
if [ "$(echo "$version" | wc -l)" -ne 1 ] || [ "$(echo "$team" | wc -l)" -ne 1 ]; then
  echo "The targets disagree on MARKETING_VERSION or DEVELOPMENT_TEAM." >&2
  exit 1
fi

keys=${PKP_ASC_KEYS:-$HOME/.config/betterbudget}
key=$(ls "$keys"/AuthKey_*.p8 2>/dev/null | head -n 1)
issuer=$(cat "$keys/app-store-connect-issuer" 2>/dev/null || true)
if [ -z "$key" ] || [ -z "$issuer" ]; then
  echo "No App Store Connect API key: put AuthKey_<Key ID>.p8 and a file" >&2
  echo "app-store-connect-issuer holding the Issuer ID in $keys." >&2
  exit 1
fi
key_id=$(basename "$key" .p8)
key_id=${key_id#AuthKey_}
signed() {
  xcodebuild -quiet "$@" -allowProvisioningUpdates \
    -authenticationKeyPath "$key" -authenticationKeyID "$key_id" \
    -authenticationKeyIssuerID "$issuer"
}

work=apple/build/release-mac
rm -rf "$work"
mkdir -p "$work"

options() {
  cat >"$work/$1.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>$1</string>
	<key>teamID</key>
	<string>$team</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<false/>
</dict>
</plist>
EOF
}

# 1. The engine, then the archive.
apple/build-engine.sh >"$work/engine.log" 2>&1 || { tail -20 "$work/engine.log" >&2; exit 1; }
signed -project "$project" -scheme PocketKeePass -configuration Release \
  -destination generic/platform=macOS -archivePath "$work/PocketKeePass.xcarchive" \
  archive CURRENT_PROJECT_VERSION="$build"

# 2. Sign a copy for the store and check it.
options export
signed -exportArchive -archivePath "$work/PocketKeePass.xcarchive" \
  -exportPath "$work/export" -exportOptionsPlist "$work/export.plist"
pkgutil --expand-full "$work/export/"*.pkg "$work/expanded"
app=$(find "$work/expanded" -maxdepth 3 -name PocketKeePass.app -type d | head -n 1)
if [ -z "$app" ]; then
  echo "The exported package holds no PocketKeePass.app." >&2
  exit 1
fi
extension=$app/Contents/PlugIns/PocketKeePassAutoFill.appex
for bundle in "$app" "$extension"; do
  name=$(basename "$bundle")
  plist=$bundle/Contents/Info.plist
  built=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")
  number=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$plist")
  if [ "$built" != "$version" ] || [ "$number" != "$build" ]; then
    echo "$name says $built ($number), not $version ($build). Not uploading it." >&2
    exit 1
  fi
  if [ ! -f "$bundle/Contents/Resources/PrivacyInfo.xcprivacy" ]; then
    echo "$name has no privacy manifest. Not uploading it." >&2
    exit 1
  fi
  entitlements=$(codesign -d --entitlements - --xml "$bundle" 2>/dev/null)
  for needed in com.apple.security.app-sandbox "$team.mn.lls.pocketkeepass.mac" keychain-access-groups; do
    if ! echo "$entitlements" | grep -q "$needed"; then
      echo "$name isn't signed with $needed. Not uploading it." >&2
      exit 1
    fi
  done
  # No network, like the Android app without the INTERNET permission.
  if echo "$entitlements" | grep -q -e network.client -e network.server; then
    echo "$name may use the network. Not uploading it." >&2
    exit 1
  fi
  binary=$bundle/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$plist")
  if strings "$binary" | grep -q -e "$HOME"; then
    echo "$name contains paths from this computer. Not uploading it." >&2
    exit 1
  fi
done
if ! codesign -d --entitlements - --xml "$extension" 2>/dev/null |
  grep -q com.apple.developer.authentication-services.autofill-credential-provider; then
  echo "The AutoFill extension isn't signed as a credential provider. Not uploading it." >&2
  exit 1
fi
codesign --verify --strict --deep "$app"

if [ -n "$check" ]; then
  echo "PocketKeePass $version ($build) for Mac is built and checked; nothing uploaded."
  exit 0
fi

# 3. Upload, signed the same way as the copy checked above.
options upload
signed -exportArchive -archivePath "$work/PocketKeePass.xcarchive" \
  -exportPath "$work/upload" -exportOptionsPlist "$work/upload.plist"

echo "PocketKeePass $version ($build) for Mac is uploaded. It shows in App Store Connect under"
echo "TestFlight (macOS) once Apple has processed it (usually 10 to 30 minutes)."
