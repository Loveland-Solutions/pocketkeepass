# App Store screenshots

iPhone screenshots in two sizes: `6.3-inch-*` (1206 × 2622, an iPhone 17 Pro simulator), which App Store
Connect asks for first ("iPhone with Dynamic Island, medium display"; it refused the 6.9-inch ones there),
and `6.9-inch-*` (1320 × 2868, an iPhone 17 Pro Max simulator), both iOS 27.2, with `art/demo/Demo.kdbx`, renamed `Personal.kdbx` in the simulator's "On My iPhone" folder, as
for Google Play.

They're taken by a UI test, `StoreScreenshots` (`apple/iOS/PocketKeePassUITests`), which runs only when
asked. To take them again:

```sh
id=$(xcrun simctl create "PKP Shots iPhone" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro com.apple.CoreSimulator.SimRuntime.iOS-27-2)   # or iPhone-17-Pro-Max
xcrun simctl boot $id
xcrun simctl launch $id com.apple.DocumentsApp      # makes "On My iPhone"; then copy Demo.kdbx in as
                                                    # Personal.kdbx (where: apple/README.md, UI tests)
xcrun simctl spawn $id notifyutil -s com.apple.BiometricKit.enrollmentChanged 1
xcrun simctl spawn $id notifyutil -p com.apple.BiometricKit.enrollmentChanged   # Face ID, for settings
xcrun simctl status_bar $id override --time 9:41 --dataNetwork wifi --wifiBars 3 --cellularBars 4 \
    --operatorName "" --batteryState charged --batteryLevel 100
TEST_RUNNER_STORE_SCREENSHOTS=1 xcodebuild -project apple/iOS/PocketKeePass.xcodeproj -scheme PocketKeePass \
    -destination "id=$id" -resultBundlePath shots.xcresult -only-testing:PocketKeePassUITests/StoreScreenshots test
xcrun xcresulttool export attachments --path shots.xcresult --output-path shots   # named 1-unlock … 6-settings
```

Copy the demo database in again before each run: the generator screenshot opens the editor, which upgrades
the file to KDBX 4. The AutoFill row in settings says "Turn on AutoFill": iOS doesn't answer whether it's on
in the simulator.

No iPad screenshots yet: the app's iPad layout is the iPhone's, stretched, and some text says "iPhone".

## Mac

`mac-1-browse.png` … `mac-4-unlock.png` (2880 × 1800, light, in that order): the main window
with an entry, a search, the new-entry sheet, and the unlock screen, with the demo database as
`Personal.kdbx`. Each is a 1280 × 800 window captured with its shadow and centred on a soft
backdrop, from a debug build launched with environment settings only debug builds read:

- `PKP_ALLOW_SCREEN_CAPTURE=1` (the window is otherwise kept out of screenshots),
  `PKP_DEBUG_WINDOW=1280x800`, `PKP_DEBUG_APPEARANCE=light`;
- `PKP_OPEN_DATABASE=<path in the app's container>` and `PKP_TEST_PASSWORD=demo`;
- `PKP_DEBUG_GROUP=Finance`, `PKP_DEBUG_ENTRY=Credit union`, `PKP_DEBUG_QUERY=sam`, or
  `PKP_DEBUG_SHOW=new-entry`.

Run the app's binary with those, wait for it to unlock, then `screencapture -l <window id>`
(the window id from `CGWindowListCopyWindowInfo`), and composite onto 2880 × 1800. Copy the
demo database in again before each shot. The window comes to the front by itself, so don't type
meanwhile: keystrokes go to it.
