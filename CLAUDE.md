# Pass The Key: project context

A free, open-source Android password manager that reads and writes KeePass (`.kdbx`) databases.

## Why this exists

Today's Android KeePass apps are either clunky or paid. Pass The Key aims to be:

- **Free, always.** No ads, no paid tier, no tracking, no analytics.
- **Not terrible.** It should feel like a modern Android app: fast unlock, clean Material 3 UI, autofill that works.
- **Trustworthy.** Security is the product. When security and convenience conflict, security wins, and the tradeoff gets explained to the user in plain language.

## Status

Update this section as milestones land.

- **Done:** Android app shell. `:app` is a placeholder Compose home screen with `FLAG_SECURE`, backups disabled, and no `INTERNET` permission (verified in the built APK).
- **Done:** our own KDBX **reader** in Rust (`core/`), covering:
  - KDBX 3.1 and 4.x;
  - password, key file, or both (key files in XML v1/v2, raw, hex, or any other file);
  - AES-KDF, Argon2d, and Argon2id;
  - AES-256, ChaCha20, and Twofish;
  - Salsa20 and ChaCha20 protected values;
  - a lossless XML tree.

  21 tests. Verified field-for-field against KeePassXC's own exports:
  - KDBX 3.1, written by KeePassXC;
  - KDBX 4.0, written by pykeepass, in all three cipher and KDF combinations;
  - KDBX 4.1, written by KeePassXC.

  See `core/tests/fixtures/README.md`.
- **Next:** the **writer** (save), then the UniFFI bridge into `app/`.
- **Then:** the UniFFI bridge into `:app`, then MVP feature 1 (open a database through the system file picker and unlock it).
- **History:** the first skeleton used the kotpass library and was replaced the same day (2026-11-17). See "Project decisions".

## Building

```sh
cd core && cargo test --release           # the KeePass engine (Rust); --release because key derivation is slow unoptimized
cargo clippy --all-targets && cargo fmt   # keep both clean
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug              # APK lands in app/build/outputs/apk/debug/
```

Gradle needs a JDK. The only one on the dev Mac is Android Studio's bundled copy. `local.properties` (gitignored) holds `sdk.dir`.

## Module layout

- `core/` (Rust crate `passthekey-core`): **all** KeePass and crypto logic: file format, key derivation, encryption, the data model. No Android dependencies, so it's tested with plain `cargo test`.
- `app/` (Android, Kotlin, package `mn.lls.passthekey`): screens plus Android-only integration (file picker, biometrics, autofill, clipboard). It holds no password logic of its own and calls into `core` through UniFFI.

## Project decisions

- **Name:** "Pass The Key" (display name, with spaces). Publisher: Loveland Solutions.
- **Application ID / package:** `mn.lls.passthekey`. This is the Loveland Solutions domain (lls.mn) written backwards, and it matches the Tile Timer Android app (`mn.lls.tiletimer`). It can never change once the app is published on Google Play.
- **License:** GPLv3. Most KeePass apps use it, F-Droid accepts it, it stops closed-source forks, and it lets us reuse KeePassDX code if needed. MIT and Apache-2.0 libraries can be used inside a GPLv3 app.
- **Distribution:** F-Droid, Google Play, and GitHub releases. F-Droid means no proprietary dependencies (no Google Play Services or Firebase) and reproducible builds where possible.
- **Dependency standard (the owner's words: "I would rather reinvent the wheel than make junk pieced together from old scrap"):**
  - Every dependency must be actively maintained and on a current release. Check the release date before adding anything. Stale, abandoned, or years-old libraries are not acceptable, even when they "work".
  - When no current, high-quality library exists, **we write it ourselves**. That includes the whole KDBX format.
  - **The one exception:** cryptographic primitives (AES, ChaCha20, Salsa20, Twofish, Argon2, SHA-2, HMAC) come from the RustCrypto crates. Hand-written crypto fails silently and dangerously, while RustCrypto is current, audited, and widely used. Everything above the primitives is ours.
- **Minimize Java.** Password logic lives in Rust, not on the JVM. Kotlin is only the thin Android layer, because Android's APIs (autofill, biometrics, file picker) are only reachable from Kotlin or Java. A JDK exists only because Gradle requires one.

## Tech stack (confirmed)

| Area | Choice | Notes |
|---|---|---|
| Engine language | Rust (edition 2024) | `core/` crate |
| Android layer language | Kotlin | |
| Rust ↔ Kotlin bridge | UniFFI (Mozilla) | Generates the Kotlin bindings |
| Crypto primitives | RustCrypto: `aes`, `cbc`, `chacha20`, `salsa20`, `twofish`, `argon2`, `sha2`, `hmac` | The only things we don't write ourselves |
| Other Rust deps | `flate2` (gzip), `quick-xml`, `zeroize`, `uuid`, `base64`, `getrandom`, `thiserror` | All released in 2026 when chosen |
| UI | Jetpack Compose + Material 3 | Dynamic color on Android 12+ |
| Architecture | Single-activity, MVVM, unidirectional data flow | ViewModel + StateFlow |
| DI | None for now (manual wiring) | Add Hilt only if wiring gets painful |
| Async | Coroutines | Crypto and IO run off the main thread |
| Build | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`) | |
| Min / target / compile SDK | 26 (Android 8.0) / 36 / 37 | Min 26 gives us the Autofill framework. Current AndroidX requires compile SDK 37 |
| Toolchain | AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1 | Versions live in `gradle/libs.versions.toml` |
| KDBX format | **Our own implementation** in `core/` | See "KDBX engine design" below |

### Rejected (don't bring these back)

- **kotpass** (Kotlin KDBX library): last release Sept 2025 and barely maintained. It also silently drops XML it doesn't recognize.
- **keepass-rs**: current and fine, but the owner wants the format implemented in-house.
- **androidx.biometric 1.1.0** and **JUnit 4**: both from 2021. Use androidx.biometric 1.4.x and current test tooling.

### KDBX engine design

- **Lossless by construction.** The decrypted XML is kept as a generic element tree, and the typed views (groups, entries, fields) read and write through that tree. Elements we don't understand are never dropped, because we never parse them into something narrower.
- **Test oracle:** KeePassXC is installed at `/Applications/KeePassXC.app`. `Contents/MacOS/keepassxc-cli` can create databases (`db-create`, `add`) and export the decrypted XML (`export --format xml`). Generate fixtures with it, and compare our decrypted output with its export.
- Keep secrets in `zeroize`-on-drop buffers. Never `Debug`-print or log secret values.

## Core requirements

### Format compatibility
- Open and save **KDBX 4.x**. Open **KDBX 3.1** (upgrading on save is acceptable but must be opt-in).
- KDFs: Argon2d, Argon2id, AES-KDF. Ciphers: AES-256, ChaCha20 (Twofish is optional).
- Composite keys: password, key file, or both.
- **Never lose data.** Preserve fields, attachments, custom data, history, and icons we don't understand, and round-trip them unchanged.
- Files saved by PassTheKey must open in KeePassXC and KeePass 2.x. That's the compatibility bar.

### Files and sync
- Use the **Storage Access Framework** (`ACTION_OPEN_DOCUMENT`) with persisted URI permissions. Cloud sync then works through any provider (Google Drive, Dropbox, Nextcloud, Syncthing folders) without our own cloud code.
- **Safe save:** write the full database to a temp location, verify it decrypts, then overwrite. Never leave a half-written file.
- Detect when the file changed underneath us since it was opened, then offer to reload, overwrite, or merge (merge can come later).

### Security
- **No `INTERNET` permission.** The app never talks to the network. This is a headline feature.
- Biometric quick-unlock: the derived key is encrypted with an Android Keystore key that requires biometric auth and is invalidated when biometrics change. Master password is always the fallback.
- `FLAG_SECURE` on all windows that show secrets (no screenshots, blank in recents).
- Clipboard: mark copied secrets as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE` on 13+) and auto-clear after a configurable timeout.
- Auto-lock on timeout and on screen off (configurable).
- Keep plaintext secrets in memory as briefly as practical. Prefer `CharArray`/`ByteArray` over `String` for passwords and zero them after use.
- Never log secrets, even in debug builds.
- `android:allowBackup="false"`. App data must not land in cloud backups.

### Features: MVP
1. Open an existing database (password and/or key file)
2. Browse groups and entries, plus search
3. View an entry and copy username/password/URL
4. Biometric quick-unlock
5. Autofill service (Android Autofill framework), matching by package name and web domain
6. Create and edit entries and groups, then save
7. Password generator
8. TOTP codes (KeePassXC `otp` field format and the KeePass 2 `TimeOtp-*` fields)

### Later
- Create a new database
- Attachments (view/add)
- Database merge on sync conflicts
- Inline autofill suggestions in the keyboard (Android 11+)
- Passkeys via Credential Manager
- Multiple databases, entry history browsing, recycle bin management

### Non-goals
- Our own sync server or cloud accounts
- Any monetization
- Inventing a non-KeePass format

## Conventions

- All KeePass and crypto logic lives in the Rust `core/` crate, never in Kotlin.
- Crypto and parsing code needs tests, including round-trip tests against real sample databases created by KeePassXC and KeePass 2.x. Store them in `core/tests/fixtures/`, with their passwords documented next to them.
- Never write crypto primitives ourselves; use RustCrypto. Everything else is fair game to write ourselves.
- Small, focused commits.

## Open items

- [ ] More sample databases: files written by KeePass 2.x itself (Windows/.NET). KDBX 3.1, 4.0, and 4.1 are covered
- [ ] Benchmark Argon2 unlock time on a low-end phone
- [ ] Cross-compiling Rust for Android: install `cargo-ndk` and the Android Rust targets (NDK 28 is already installed)
- [ ] The `aes` crate only auto-detects ARMv8 AES hardware on Linux and macOS. On Android, check whether `RUSTFLAGS=-Ctarget-feature=+aes` is needed, or AES-KDF unlocks will be slow
- [ ] KDBX 3: wrong-key detection relies on padding plus the stream start bytes, which is standard. KDBX 4 uses the header HMAC
- [ ] App icon
