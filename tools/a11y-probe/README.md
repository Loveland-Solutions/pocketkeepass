# Accessibility probe (test tool)

An accessibility service that logs everything it can read from PocketKeePass. It is **not** marked
as an assistive tool (`android:isAccessibilityTool`), like spyware that abuses accessibility.
Use it to check what such an app can see. It's a separate app and never ships inside PocketKeePass.

```sh
./gradlew :a11y-probe:assembleDebug
adb install -r tools/a11y-probe/build/outputs/apk/debug/a11y-probe-debug.apk
adb shell settings put secure enabled_accessibility_services mn.lls.pocketkeepass.a11yprobe/.ProbeService
adb shell settings put secure accessibility_enabled 1
adb logcat -s A11yProbe            # then use PocketKeePass
adb shell settings delete secure enabled_accessibility_services   # turn it off afterwards
```

It logs whatever it can read, passwords included, so only use it with test databases.

The second service, `.ToolProbeService` (log tag `A11yProbeTool`), *is* registered as an assistive tool, so it sees what TalkBack sees. Each node it logs includes its on-screen bounds, which makes it the way to read and tap PocketKeePass's screen from a test script now that `uiautomator` can't see it.
