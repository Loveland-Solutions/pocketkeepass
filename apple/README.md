# PocketKeePass for iOS and macOS

Two separate SwiftUI apps over the same Rust engine as the Android app (`../core`). No KeePass or
crypto logic lives in Swift.

- `build-engine.sh` builds the engine for iOS devices, the iOS simulator, and Macs (Apple
  silicon and Intel), generates the Swift bindings with UniFFI, and packages both into
  `PocketKeePassCore/`. Its output is git-ignored. The Xcode scheme runs it before every build
  (log: `PocketKeePassCore/.build-engine.log`); run it by hand once after cloning, because Xcode
  resolves the package before the scheme's pre-action runs.
- `PocketKeePassCore/` is the Swift package both apps use: the XCFramework, the generated
  `PocketKeePassCore.swift`, and our own `KeePassError+Message.swift`. `swift test` there runs
  `BridgeTests`, the Swift version of the Android `BridgeTest.kt`, against
  `core/tests/fixtures`.
- `Shared/` is built into both apps and the iPhone's AutoFill extension (the remembered
  database, file access, backups, quick unlock, fill targets, icons). `SharedApp/` is built into
  both apps but not the extension (the session, saving and sync, editing, unlocking, and the
  screens the two apps share, in `SharedApp/Views/`). `AutoFill/` is the extension's code.
  Platform differences are `#if os(iOS)` / `#if os(macOS)` in place.
- `iOS/` is the iPhone and iPad app (`PocketKeePass.xcodeproj`, iOS 18+, bundle ID
  `mn.lls.pocketkeepass`) and its AutoFill extension (`PocketKeePassAutoFill`,
  `mn.lls.pocketkeepass.autofill`). The project uses folder-synchronized groups that point at
  `PocketKeePass/` and the shared folders above, so new Swift files are picked up without
  editing the project file. `Config/` holds the extension's Info.plist and both targets'
  entitlements: the App Group `group.mn.lls.pocketkeepass`, which the two share (settings,
  backups, and quick unlock's Keychain item), and the AutoFill credential provider. Each target
  has its own string catalog; strings in `Shared/` need their Mongolian in both.
- `macOS/` is the Mac app (`PocketKeePass.xcodeproj`, macOS 15+, bundle ID
  `mn.lls.pocketkeepass.mac`), sandboxed with no network entitlement. Its App Group is
  `44H5SLX8KR.mn.lls.pocketkeepass.mac` (Mac groups start with the team ID), which is also the
  Keychain access group. Quick unlock's item is in the data protection keychain, which needs a
  provisioning profile: an unsigned or "sign to run locally" build gets
  `errSecMissingEntitlement` (-34018), so build with Apple's profile (below). Its AutoFill
  extension (`mn.lls.pocketkeepass.mac.autofill`) builds the same `AutoFill/` code as the
  iPhone's. macOS ties a file bookmark to the app that made it, so the extension can't open the
  database the user picked: the app keeps the file as it last read or wrote it in the App Group
  (`Backups.latest`), the extension unlocks that, and a passkey made there waits as an
  unfinished save that the app writes to the file at its next unlock. A key file can't be read
  there either, so a database with one fills only with Touch ID.

Rust targets needed:

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin x86_64-apple-darwin
```

## Building and testing

```sh
apple/build-engine.sh
cd apple/PocketKeePassCore && swift test           # the bridge, on this Mac
cd apple/iOS && xcodebuild -project PocketKeePass.xcodeproj -scheme PocketKeePass \
    -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath ../build/DerivedData test

# The Mac app, signed with a development profile Xcode fetches through the App Store Connect
# API key (see tools/release_ios.sh for where the key lives):
cd apple/macOS && xcodebuild -project PocketKeePass.xcodeproj -scheme PocketKeePass \
    -destination 'platform=macOS,arch=arm64' -derivedDataPath ../build/mac-signed \
    -allowProvisioningUpdates -authenticationKeyPath <AuthKey_….p8> \
    -authenticationKeyID <key ID> -authenticationKeyIssuerID <issuer ID> build
```

`iOS/PocketKeePassUnitTests` (Swift Testing, hosted in the app) covers app logic that needs no
screen: quick unlock's expiry rule and the checks made before a Face ID prompt, backups, and
saving through `Session` into copies of `core/tests/fixtures` (merging, restoring, upgrading).

The UI tests (`iOS/PocketKeePassUITests`) drive the app through the system file picker: open,
wrong password, unlock, browse, search, show and copy a protected field, lock, a new entry, the
password generator, and
editing, moving, and deleting an entry. If two simulators share a name (`xcrun simctl list
devices`), `name=` may pick the one without the fixture: pass `-destination 'id=<id>'` instead. Setup, once per
simulator: open the Files app once (that creates its "On My iPhone" storage), then copy
`core/tests/fixtures/browse.kdbx` into
`~/Library/Developer/CoreSimulator/Devices/<id>/data/Containers/Shared/AppGroup/<group>/File Provider Storage/`,
where `<group>` is the one whose `.com.apple.mobile_container_manager.metadata.plist` says
`group.com.apple.FileProvider.LocalStorage`. Keep it alone in that folder: the picker runs in
another process, taps on its elements don't always arrive, so the test taps the first icon of
the grid by position.

`testNewEntryIsSaved` writes to that copy of `browse.kdbx`: the first run upgrades it to KDBX 4
(after asking), and every run adds an entry. Copy the fixture in again to start over. If the
picker says "On My iPhone is Empty" with the file in place, the simulator's Files index is stale:
use a fresh simulator (`xcrun simctl create`), open Files once, and copy the file in.

`testFaceIDQuickUnlock` (the offer after the master password, turning it on, unlocking with Face
ID after a lock, turning it off) needs Face ID enrolled in that simulator and the Mac answering
its prompts, so it skips itself unless asked for:

```sh
xcrun simctl spawn <id> notifyutil -s com.apple.BiometricKit.enrollmentChanged 1
xcrun simctl spawn <id> notifyutil -p com.apple.BiometricKit.enrollmentChanged   # enrolls a face, once
apple/iOS/face-id-matcher.sh <id> &        # sends a matching face whenever the test asks
TEST_RUNNER_FACE_ID_MATCHER=1 xcodebuild … -destination 'id=<id>' test
```

The simulator doesn't enforce `.biometryCurrentSet` (a re-enrolled face still reads the Keychain
item), so "the faces changed, quick unlock turned off" can only be tried on a device; the unit
tests cover the app's own check. Use a simulator of your own for these tests: one shared with
other work gets its apps in the way of the file picker.

`testRestoreBackupOfDamagedFile` and `testSaveBackupAsNewFile` need browse.kdbx damaged, which a
UI test can't do from inside the simulator, and a save made to it before (any run of
`testNewEntryIsSaved`), so the app holds a backup. Keep a good copy, cut the file short from the
Mac, then run one of them:

```sh
cp "<File Provider Storage>/browse.kdbx" /tmp/browse-good.kdbx
truncate -s 600 "<File Provider Storage>/browse.kdbx"
TEST_RUNNER_DAMAGED_FILE=1 xcodebuild … -only-testing:PocketKeePassUITests/UnlockFlowTests/testRestoreBackupOfDamagedFile test
```

The restore test leaves browse.kdbx whole again. The save-as test leaves the damaged file and a
new `browse (restored).kdbx` beside it: delete that, and copy the good one back.

`testAutoFillInSafari` (turning AutoFill on, then filling the autofill probe's login page in
Safari through the extension: unlocked with the master password, an entry found by search) and
`testAutoFillWithFaceID` (the same, unlocked with Face ID turned on in the app) need the page
served on the Mac, and the extension turned on in the simulator: iOS's own "turn on" question
doesn't come up there (its helper fails to start, and the app then shows "Turn on AutoFill"
after 3 seconds anyway), so `pluginkit` does it:

```sh
python3 -m http.server 8765 -d tools/autofill-probe &
xcrun simctl spawn <id> pluginkit -e use -i mn.lls.pocketkeepass.autofill   # after installing the app once
TEST_RUNNER_AUTOFILL_PAGE=1 xcodebuild … -only-testing:PocketKeePassUITests/UnlockFlowTests/testAutoFillInSafari test
```

The Face ID one also needs `TEST_RUNNER_FACE_ID_MATCHER=1` and the matcher, as above. Without
the on-screen keyboard (a simulator using the Mac's keyboard) there's no key button above it;
the test then goes through the field's edit menu (AutoFill › Passwords). One simulator stopped
taking typed text in PocketKeePass's password field after a run of these (even `main`'s build,
even after a reboot); another was fine. If that happens, use a fresh simulator.

`testCreateDatabase` (a new database: named, saved with the system's save dialog into "On My
iPhone", opened, an entry added, locked, and unlocked) leaves the new file in "On My iPhone",
which the file picker tests want to themselves, so it only runs with
`TEST_RUNNER_CREATE_DATABASE=1`, on a simulator of its own.

`apple/build-engine.sh` builds the Mac copy of the engine (`core/target/release`, which the
Android app's Kotlin tests also load) with none of Xcode's environment: linked inside Xcode's
build, it came out malformed and wouldn't load.

`PasskeyTests.testPasskeyOnWebAuthnIO` registers a passkey with PocketKeePass on webauthn.io in
Safari, then signs in with it, so it needs the internet, browse.kdbx remembered, and the extension
turned on (`pluginkit`, as above). Run it with `TEST_RUNNER_PASSKEY_SITE=1`. Every run adds an
entry. If iOS's passkey sheet doesn't offer PocketKeePass, the system log says "no enabled
credential provider extensions supports passkeys": the simulator kept an older registration of
the extension. Install the built app with `xcrun simctl install`, turn it on again with
`pluginkit`, and retry.

## Releasing (iPhone and iPad)

`tools/release_ios.sh` builds, signs, checks, and uploads a build to App Store Connect, where it
shows under TestFlight; `--check` stops before the upload. It signs with the Loveland Solutions
team (`44H5SLX8KR`) through the team's App Store Connect API key, the same one BetterBudget's
release uses (`~/.config/betterbudget/`, see the script). No iPhone is registered on the team, so
a device gets the app only through TestFlight. Giving a build to testers and submitting it for
review are clicks in App Store Connect. The listing and the answers App Store Connect asks for
are in `art/app-store/listing.md`.

Screenshots: `xcrun simctl io booted screenshot shot.png`. iOS can't block screenshots, unlike
Android's `FLAG_SECURE`; the app covers itself in the app switcher and while the screen is being
recorded or mirrored.
