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
- `iOS/` is the iPhone and iPad app (`PocketKeePass.xcodeproj`, iOS 18+, bundle ID
  `mn.lls.pocketkeepass`). The project uses folder-synchronized groups, so new Swift files are
  picked up without editing the project file.
- `macOS/`: not started.

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
```

`iOS/PocketKeePassUnitTests` (Swift Testing, hosted in the app) covers app logic that needs no
screen: quick unlock's expiry rule and the checks made before a Face ID prompt.

The UI test (`iOS/PocketKeePassUITests`) drives the app through the system file picker: open,
wrong password, unlock, browse, search, show and copy a protected field, and lock. Setup, once per
simulator: open the Files app once (that creates its "On My iPhone" storage), then copy
`core/tests/fixtures/browse.kdbx` into
`~/Library/Developer/CoreSimulator/Devices/<id>/data/Containers/Shared/AppGroup/<group>/File Provider Storage/`,
where `<group>` is the one whose `.com.apple.mobile_container_manager.metadata.plist` says
`group.com.apple.FileProvider.LocalStorage`. Keep it alone in that folder: the picker runs in
another process, taps on its elements don't always arrive, so the test taps the first icon of
the grid by position.

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

Screenshots: `xcrun simctl io booted screenshot shot.png`. iOS can't block screenshots, unlike
Android's `FLAG_SECURE`; the app covers itself in the app switcher and while the screen is being
recorded or mirrored.
