# Pass The Key

A free, open-source KeePass password manager for Android, from Loveland Solutions.

- Opens and saves your existing `.kdbx` databases (KeePass 2.x / KeePassXC compatible)
- Works with any cloud folder through Android's file picker: Google Drive, Dropbox, Syncthing, and more
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

## License

GPLv3. See [LICENSE](LICENSE).
