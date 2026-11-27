#!/bin/sh
# Builds the release bundle and puts it on Google Play's Internal testing track:
#
#   tools/release_play.sh "What changed, in a sentence or two."
#   tools/release_play.sh --draft "…"   leaves the release as a draft, to roll out from Play Console
#                                        (Play only takes drafts until the app's first review passes)
#   PKP_PLAY_TRACK=production tools/release_play.sh "…"   another track (internal by default)
#
#   1. builds the signed .aab (the version code is the commit count, see app/build.gradle.kts)
#   2. checks it doesn't ask for the internet
#   3. uploads it and makes it the only build in Internal testing, with the notes above in English
#
# Moving a build on to a wider track stays the owner's click in Play Console.
# Run from a clean commit. Needs, once:
#   - the upload key in keystore.properties (git-ignored; see app/build.gradle.kts)
#   - a Play service-account key at the path below (or in $PKP_PLAY_KEY), for an account Play
#     Console lets release PocketKeePass to testing tracks. Never in the repo.
set -eu

package=mn.lls.pocketkeepass
track=${PKP_PLAY_TRACK:-internal}
key=${PKP_PLAY_KEY:-$HOME/.config/betterbudget/play-service-account.json}
api=https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package
upload_api=https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$package

cd "$(dirname "$0")/.."

status=completed
if [ "${1:-}" = --draft ]; then
  status=draft
  shift
fi
notes=${1:-}
if [ -z "$notes" ]; then
  echo "Give the release notes: tools/release_play.sh \"What changed.\"" >&2
  exit 1
fi
if [ "$(printf %s "$notes" | wc -m)" -gt 500 ]; then
  echo "Play takes at most 500 characters of notes." >&2
  exit 1
fi
if [ ! -r "$key" ]; then
  echo "No Play key at $key." >&2
  exit 1
fi
if [ ! -r keystore.properties ]; then
  echo "No keystore.properties: release builds would be unsigned." >&2
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "Commit or set aside the changes first: a release is built from a commit." >&2
  exit 1
fi

# 1. Build and sign with the upload key.
JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"} ./gradlew -q :app:bundleRelease
aab=app/build/outputs/bundle/release/app-release.aab
version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)

# 2. Checks on what's inside. Each one fails closed: a check that can't read what it checks
# stops the release instead of passing it.
check=$(mktemp -d)
unzip -q "$aab" 'base/manifest/AndroidManifest.xml' 'base/lib/*' -d "$check"
manifest=$check/base/manifest/AndroidManifest.xml
if [ ! -s "$manifest" ] || ! strings "$manifest" | grep -q "$package"; then
  echo "Couldn't read the bundle's manifest. Not releasing it." >&2
  exit 1
fi
# The app never goes online: the bundle must not ask for the internet.
if strings "$manifest" | grep -q 'android.permission.INTERNET'; then
  echo "The bundle asks for internet access. Not releasing it." >&2
  exit 1
fi
# The engine must not carry paths from this computer (they name the developer's account).
# Our engine, built here, is checked for any home folder. Prebuilt libraries from others carry
# their own builders' paths (JNA's names /home/matthias/…), so those are checked for ours only.
engines=$(find "$check/base/lib" -name 'libpocketkeepass_core.so' | wc -l)
if [ "$engines" -ne 3 ]; then
  echo "Expected the engine for 3 ABIs in the bundle, found $engines. Not releasing it." >&2
  exit 1
fi
if find "$check/base/lib" -name 'libpocketkeepass_core.so' -exec strings {} + | grep -q -e "$HOME" -e '/Users/' -e '/home/'; then
  echo "The engine contains local paths. Not releasing it." >&2
  exit 1
fi
if find "$check/base/lib" -name '*.so' -exec strings {} + | grep -q -e "$HOME"; then
  echo "A native library contains paths from this computer. Not releasing it." >&2
  exit 1
fi
rm -rf "$check"

# 3. Sign in as the service account: a JWT signed with its key, traded for a token that can
# only reach the Play Developer API.
json() { python3 -I -c "import json,sys;print(json.load(sys.stdin)$1)"; }
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
pem=$(mktemp)
out=$(mktemp)
edit=
token=
cleanup() {
  rm -f "$pem" "$out"
  # An edit left open would hold back the next run's; throw it away.
  if [ -n "$edit" ]; then
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $token" "$api/edits/$edit"
  fi
}
trap cleanup EXIT
chmod 600 "$pem"
json "['private_key']" <"$key" >"$pem"
email=$(json "['client_email']" <"$key")
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
claims=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","iat":%d,"exp":%d}' \
  "$email" "$now" $((now + 3600)) | b64)
signature=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$pem" | b64)
token=$(curl -sf https://oauth2.googleapis.com/token \
  -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
  -d assertion="$header.$claims.$signature" | json "['access_token']")

# Stops the script with Google's own message if a call fails. The response lands in $out.
call() {
  if ! curl -sS --fail-with-body -o "$out" -H "Authorization: Bearer $token" "$@"; then
    cat "$out" >&2
    exit 1
  fi
}

call -X POST -H 'Content-Length: 0' "$api/edits"
edit=$(json "['id']" <"$out")
call -X POST -H 'Content-Type: application/octet-stream' --data-binary "@$aab" \
  "$upload_api/edits/$edit/bundles?uploadType=media"
code=$(json "['versionCode']" <"$out")

# The track's release lists only this build, so a tester never gets an older one.
body=$(VERSION=$version CODE=$code NOTES=$notes STATUS=$status TRACK=$track python3 -I -c '
import json, os
print(json.dumps({"track": os.environ["TRACK"], "releases": [{
    "name": "%s (%s)" % (os.environ["VERSION"], os.environ["CODE"]),
    "versionCodes": [os.environ["CODE"]],
    "status": os.environ["STATUS"],
    "releaseNotes": [{"language": "en-US", "text": os.environ["NOTES"]}]}]}))')
call -X PUT -H 'Content-Type: application/json' -d "$body" "$api/edits/$edit/tracks/$track"
call -X POST -H 'Content-Length: 0' "$api/edits/$edit:commit"
edit=

echo "PocketKeePass $version ($code) is on Play's $track track ($status)."
