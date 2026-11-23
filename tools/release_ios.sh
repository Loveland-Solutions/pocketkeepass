#!/bin/sh
# Builds the iPhone app and sends it to App Store Connect, where it shows under TestFlight and
# can be chosen for an App Store version:
#
#   tools/release_ios.sh            builds, checks, and uploads
#   tools/release_ios.sh --check    builds and checks, uploads nothing
#
#   1. builds the Rust engine and archives the app with its AutoFill extension, then signs it
#      ad hoc with its entitlements (the build number is the commit count, as on Android; the version is MARKETING_VERSION);
#   2. has Apple sign a copy for the App Store and checks it: version and build number, the
#      privacy manifest, the App Group and AutoFill entitlements, and no paths from this Mac in
#      the binaries;
#   3. uploads it, signed the same way.
#
# Giving a build to testers, and submitting it for review, stay the owner's clicks in App Store
# Connect. Run from a clean commit. Needs, once: the app made in App Store Connect with the
# bundle ID mn.lls.pocketkeepass, and the Loveland Solutions team's App Store Connect API key
# (below). The archive is built unsigned, because a properly signed one needs a registered
# iPhone (Apple keeps the certificate that signs the export), then signed ad hoc with each
# bundle's entitlements file: the export takes the entitlements from the archive's signature,
# and without one the App Group and AutoFill entitlements were lost. The first export registers the bundle IDs, the App
# Group, and the AutoFill capability with Apple.
#
# The same approach as BetterBudget's tool/release_ios.sh.
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

project=apple/iOS/PocketKeePass.xcodeproj
version=$(sed -n 's/.*MARKETING_VERSION = \([0-9.]*\);/\1/p' "$project/project.pbxproj" | sort -u)
team=$(sed -n 's/.*DEVELOPMENT_TEAM = \([A-Z0-9]*\);/\1/p' "$project/project.pbxproj" | sort -u)
build=$(git rev-list --count HEAD)
if [ "$(echo "$version" | wc -l)" -ne 1 ] || [ "$(echo "$team" | wc -l)" -ne 1 ]; then
  echo "The targets disagree on MARKETING_VERSION or DEVELOPMENT_TEAM." >&2
  exit 1
fi

# Xcode's command line can't use the account signed in to Xcode, so every step that signs or
# uploads goes by an App Store Connect API key: its AuthKey_<Key ID>.p8, and the Issuer ID in a
# file app-store-connect-issuer beside it. Neither goes in the repo.
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

work=apple/build/release-ios
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
xcodebuild -quiet -project "$project" -scheme PocketKeePass -configuration Release \
  -destination generic/platform=iOS -archivePath "$work/PocketKeePass.xcarchive" \
  archive CODE_SIGNING_ALLOWED=NO CURRENT_PROJECT_VERSION="$build"
# The export takes each bundle's entitlements from its signature, so sign them ad hoc with
# theirs (the extension first: it's inside the app).
archived=$work/PocketKeePass.xcarchive/Products/Applications/PocketKeePass.app
codesign --force --sign - --entitlements apple/iOS/Config/PocketKeePassAutoFill.entitlements \
  "$archived/PlugIns/PocketKeePassAutoFill.appex"
codesign --force --sign - --entitlements apple/iOS/Config/PocketKeePass.entitlements "$archived"

# 2. Sign a copy and check it.
options export
signed -exportArchive -archivePath "$work/PocketKeePass.xcarchive" \
  -exportPath "$work/export" -exportOptionsPlist "$work/export.plist"
ditto -x -k "$work/export/"*.ipa "$work/expanded"
app=$work/expanded/Payload/PocketKeePass.app
extension=$app/PlugIns/PocketKeePassAutoFill.appex
for bundle in "$app" "$extension"; do
  name=$(basename "$bundle")
  built=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$bundle/Info.plist")
  number=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$bundle/Info.plist")
  if [ "$built" != "$version" ] || [ "$number" != "$build" ]; then
    echo "$name says $built ($number), not $version ($build). Not uploading it." >&2
    exit 1
  fi
  if [ ! -f "$bundle/PrivacyInfo.xcprivacy" ]; then
    echo "$name has no privacy manifest. Not uploading it." >&2
    exit 1
  fi
  entitlements=$(codesign -d --entitlements - --xml "$bundle" 2>/dev/null)
  for needed in group.mn.lls.pocketkeepass com.apple.developer.authentication-services.autofill-credential-provider; do
    if ! echo "$entitlements" | grep -q "$needed"; then
      echo "$name isn't signed with $needed. Not uploading it." >&2
      exit 1
    fi
  done
  binary=$bundle/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$bundle/Info.plist")
  if strings "$binary" | grep -q -e "$HOME"; then
    echo "$name contains paths from this computer. Not uploading it." >&2
    exit 1
  fi
done
codesign --verify --strict --deep "$app"

if [ -n "$check" ]; then
  echo "PocketKeePass $version ($build) for iPhone is built and checked; nothing uploaded."
  exit 0
fi

# 3. Upload, signed the same way as the copy checked above.
options upload
signed -exportArchive -archivePath "$work/PocketKeePass.xcarchive" \
  -exportPath "$work/upload" -exportOptionsPlist "$work/upload.plist"

echo "PocketKeePass $version ($build) for iPhone is uploaded. It shows in App Store Connect"
echo "under TestFlight once Apple has processed it (usually 10 to 30 minutes)."
