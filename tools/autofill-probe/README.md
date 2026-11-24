# Autofill probe (test tool)

A separate app for trying PocketKeePass's autofill on the emulator. It never ships inside
PocketKeePass. It has two halves:

- **Login forms to fill in** (`ProbeActivity`), chosen with the `form` extra:
  - `hinted` (default): a username and a password field with autofill hints, as Android asks apps to have;
  - `plain`: no hints or helpful names, only a text field followed by a password field;
  - `username`: an email field alone, like the first page of a two-step login;
  - `web`: a web page (the `url` extra) inside this app, which is not a browser. PocketKeePass must
    not believe the website it reports.

  "Show" writes what the fields hold into plain text, so `uiautomator dump` can read what was filled in.

- **A stand-in for someone else's autofill service** (`ProbeAutofillService`). It logs everything
  Android shows it of the screen in front, and offers nothing. Use it to check what another
  password manager could read from PocketKeePass's own screens (expected: one empty container, no
  fields, no text).

```sh
./gradlew :autofill-probe:assembleDebug
adb install -r tools/autofill-probe/build/outputs/apk/debug/autofill-probe-debug.apk

# Fill in its forms with PocketKeePass (or use the switch in PocketKeePass's settings):
adb shell settings put secure autofill_service mn.lls.pocketkeepass/.autofill.PocketAutofillService
adb shell am start -n mn.lls.pocketkeepass.autofillprobe/.ProbeActivity --es form plain

# See what another autofill service gets from PocketKeePass:
adb shell settings put secure autofill_service mn.lls.pocketkeepass.autofillprobe/.ProbeAutofillService
adb logcat -s AutofillProbe        # then tap PocketKeePass's master password field
```

An entry is offered in the probe's forms when its URL is `androidapp://mn.lls.pocketkeepass.autofillprobe`.

## A web page for browsers

`login.html` is a small login page. Chrome (and Android's WebView) only allow autofill on pages
loaded securely, or from `localhost`, so serve it from this computer and forward the port:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory tools/autofill-probe
adb reverse tcp:8765 tcp:8765
adb shell am start -a android.intent.action.VIEW -d http://localhost:8765/login.html com.android.chrome
adb shell am start -n mn.lls.pocketkeepass.autofillprobe/.ProbeActivity --es form web --es url http://localhost:8765/login.html
```

An entry with the URL `http://localhost:8765` matches it. `signup.html` is a sign-up form (an email and two new-password fields) whose form disappears on submit, which Chrome takes as a finished sign-up: use it to try saving a login from a browser. Chrome also needs Settings › Autofill
services › Autofill using another service, once.

`adb shell cmd autofill set log_level verbose` and `adb shell dumpsys autofill` show what Android's
autofill framework itself is doing.
