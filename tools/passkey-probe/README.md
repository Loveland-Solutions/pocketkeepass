# Passkey probe (test tool)

A separate app for trying PocketKeePass's passkeys from a **native app** on the emulator. It
never ships inside PocketKeePass. Browsers are tried with Chrome and `webauthn.io` instead.

- **Create** asks Credential Manager for a new passkey for `probe.example`, and remembers the
  public key and credential ID the provider returned, as a site's server would.
- **Sign in** asks for a passkey for `probe.example` and checks the answer the way a server
  would: the ECDSA signature against the stored public key, the challenge, the type, the rpId
  hash, the user-verified flag, and that it's the same credential.

The outcome is written under the buttons (`verified origin=android:apk-key-hash:…`, or what
failed), for `uiautomator dump` to read, and logged with the full JSON (`adb logcat -s PasskeyProbe`).

`probe.example` belongs to nobody, so nothing checks the probe against the site. PocketKeePass
can't check that either (it would need the internet): it asks the user the first time an app
wants a passkey, and from then on only allows that app with the same signing certificate.

```sh
./gradlew :passkey-probe:assembleDebug
adb install -r tools/passkey-probe/build/outputs/apk/debug/passkey-probe-debug.apk

# PocketKeePass as the passkey provider (or use the switch in PocketKeePass's settings), and a
# screen lock, which passkeys need:
adb shell locksettings set-pin 1234
adb shell settings put secure credential_service_primary mn.lls.pocketkeepass/mn.lls.pocketkeepass.passkey.PocketPasskeyService
```

To try the other cases:

- **An app that isn't linked yet:** build a second copy under another package name, which
  PocketKeePass asks about once:
  `./gradlew :passkey-probe:assembleDebug -PprobeId=mn.lls.pocketkeepass.passkeyprobe.other`.
  It can't check the signature (it didn't register the passkey), so it reports "signed, not checked".
- **An impostor:** sign the APK with another key (`keytool -genkeypair …`, then
  `apksigner sign --ks …`), uninstall the probe, and install that copy. Its package name is
  linked, but its certificate isn't, so PocketKeePass doesn't offer the passkey at all.
