# App Store screenshots

iPhone screenshots for the App Store's 6.9-inch size, 1320 × 2868, taken in an iPhone 17 Pro Max simulator
(iOS 27.2) with `art/demo/Demo.kdbx`, renamed `Personal.kdbx` in the simulator's "On My iPhone" folder, as
for Google Play.

They're taken by a UI test, `StoreScreenshots` (`apple/iOS/PocketKeePassUITests`), which runs only when
asked. To take them again:

```sh
id=$(xcrun simctl create "PKP Shots iPhone" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max com.apple.CoreSimulator.SimRuntime.iOS-27-2)
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
