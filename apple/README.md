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

The UI test (`iOS/PocketKeePassUITests`) drives the app through the system file picker: open,
wrong password, unlock, browse, search, show and copy a protected field, and lock. Setup, once per
simulator: open the Files app once (that creates its "On My iPhone" storage), then copy
`core/tests/fixtures/browse.kdbx` into
`~/Library/Developer/CoreSimulator/Devices/<id>/data/Containers/Shared/AppGroup/<group>/File Provider Storage/`,
where `<group>` is the one whose `.com.apple.mobile_container_manager.metadata.plist` says
`group.com.apple.FileProvider.LocalStorage`. Keep it alone in that folder: the picker runs in
another process, taps on its elements don't always arrive, so the test taps the first icon of
the grid by position.

Screenshots: `xcrun simctl io booted screenshot shot.png`. iOS can't block screenshots, unlike
Android's `FLAG_SECURE`; the app covers itself in the app switcher and while the screen is being
recorded or mirrored.
