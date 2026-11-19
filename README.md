# PocketKeePass

A free, open-source KeePass password manager for Android, from Loveland Solutions.

- Opens and saves your existing `.kdbx` databases (KeePass 2.x / KeePassXC compatible)
- Works with any cloud folder through Android's file picker: Google Drive, Dropbox, Syncthing, and more
- Keeps phones in step: while it's open, it picks up changes made on other devices and merges them with yours, so nothing is lost when two people edit the same database
- Passkeys on Android 14 and later, kept in the database the way KeePassXC and KeePassDX keep them, so they work there too
- No internet permission, no ads, no tracking, no paid tier

**Status:** early development. Not ready for real passwords yet.

## Building

The KeePass engine is a Rust crate in `core/`. The Android app is in `app/`.

```sh
(cd core && cargo test --release)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

Gradle builds the Rust engine for Android as part of the app build. That needs Rust with the Android targets and the Android NDK:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

## Credits

**KeePass.** PocketKeePass reads and writes the KeePass database format (KDBX), created by Dominik Reichl for [KeePass Password Safe](https://keepass.info). Thank you for an open, documented format that lets apps like this one exist. PocketKeePass is an independent app: it isn't made, endorsed by, or affiliated with the KeePass project. It uses no KeePass code. The format is implemented from scratch in Rust, from the [KDBX specification](https://keepass.info/help/kb/kdbx.html).

**KeePassXC.** [KeePassXC](https://keepassxc.org) is our test reference. Our tests check what we read and write against what it exports. Passkeys are stored in the format KeePassXC introduced and [KeePassDX](https://www.keepassdx.com) shares, including KeePassDX's way of linking Android apps.

**Claude.** PocketKeePass is written with [Claude](https://www.anthropic.com/claude), Anthropic's AI model, through Claude Code. Loveland Solutions directs the work and decides what ships. Commits Claude helped write carry a `Co-Authored-By: Claude` line, so the history shows which ones.

**RustCrypto.** The cryptographic primitives (AES, ChaCha20, Salsa20, Twofish, Argon2, SHA-1, SHA-2, HMAC, and P-256 ECDSA and RSA for passkeys) come from the [RustCrypto](https://github.com/RustCrypto) project. Ed25519 passkeys are signed with [ed25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek).

## License

GPLv3. See [LICENSE](LICENSE).
