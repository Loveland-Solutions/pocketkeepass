# Pass The Key: project context

A free, open-source Android password manager that reads and writes KeePass (`.kdbx`) databases.

## Why this exists

Today's Android KeePass apps are either clunky or paid. Pass The Key aims to be:

- **Free, always.** No ads, no paid tier, no tracking, no analytics.
- **Not terrible.** It should feel like a modern Android app: fast unlock, clean Material 3 UI, autofill that works.
- **Trustworthy.** Security is the product. When security and convenience conflict, security wins, and the tradeoff gets explained to the user in plain language.

## Status

Update this section as milestones land.

- **Done:** Android app shell: `FLAG_SECURE`, backups disabled, and no `INTERNET` permission (verified in the built APK).
- **Done:** our own KDBX **reader** in Rust (`core/`), covering:
  - KDBX 3.1 and 4.x;
  - password, key file, or both (key files in XML v1/v2, raw, hex, or any other file);
  - AES-KDF, Argon2d, and Argon2id;
  - AES-256, ChaCha20, and Twofish;
  - Salsa20 and ChaCha20 protected values;
  - a lossless XML tree.

  Verified field-for-field against KeePassXC's own exports:
  - KDBX 3.1, written by KeePassXC;
  - KDBX 4.0, written by pykeepass, in all three cipher and KDF combinations;
  - KDBX 4.1, written by KeePassXC.

  See `core/tests/fixtures/README.md`.
- **Done:** our own KDBX **writer** in Rust (`Database::save`), covering:
  - KDBX 4.x only, in the file's own version, cipher, compression, and key derivation settings;
  - fresh key derivation salt, master seed, IV, and protected-value key on every save;
  - a built-in safety check: the new file is read back and compared with the database in memory before it's returned;
  - an opt-in KDBX 3.1 → 4.0 upgrade (`Database::upgrade_to_kdbx4`). Without it, saving a 3.1 database is refused.

  KeePassXC opens every file we save and exports the same content it exported from the original, attachments included. It can also edit our files, and we read its result. pykeepass agrees (checked by hand, not in the test suite).
- **Done:** the UniFFI bridge (`core/src/ffi.rs`). Gradle builds the engine for arm64-v8a, armeabi-v7a, and x86_64, generates the Kotlin bindings, and packages both. The bridge exposes open, summary, group listings, search, upgrade, save, and lock. Kotlin tests call the engine through it on the dev Mac. The debug and minified release APKs both unlock databases on the API 37 emulator.
- **Done:** MVP feature 1, opening and unlocking a database (`DatabaseViewModel`, `ui/UnlockScreen.kt`):
  - the system file picker (`ACTION_OPEN_DOCUMENT`) with persisted access (read/write for the database, read for the key file). The last database and key file are remembered (`files/RecentDatabase.kt`, locations and names only), and grants for files no longer in use are released;
  - password (Material 3 `OutlinedSecureTextField`), key file, or both. An empty password with a key file means key file only. An empty password alone is a real (empty) password;
  - the password lives in a `TextFieldState` held by the ViewModel, never in saved state (that goes to disk). It's encoded to UTF-8 straight into a `ByteArray` without a `String` (`security/Secrets.kt`), wiped after the call, and the field and its undo history are cleared after unlocking or a wrong key. Key file bytes are read without stray buffer copies and wiped too;
  - unlocking runs off the main thread and can't be abandoned half-way: if the ViewModel is cleared meanwhile, the result is locked at once;
  - the database is locked by the Lock button, by auto-lock (below), and when the ViewModel is cleared (activity finished);
  - after unlocking, the browse screen opens at the root group.

  Checked by hand on the emulator: wrong password, password only (KDBX 4.0 and 4.1), password + key file, key file only, a deleted database file, rotation, and process death (comes back locked, with the choice remembered). The app logs nothing about secrets.
- **Done:** MVP feature 2, browsing groups and entries, plus search (`core/src/browse.rs`, `ui/BrowseScreen.kt`):
  - groups and entries are addressed by UUID (32 hex digits across the bridge), never by name, since KeePass allows duplicate names;
  - groups and entries are listed in file order, except that the recycle bin always comes last. The top bar shows the way back up, and the system back button goes up a level;
  - **lists and search never carry or read a protected value**: `Entry::unprotected_field` returns "" for protected fields, so a username the user chose to protect is hidden too. Entry passwords come with MVP feature 3, as bytes;
  - search runs in Rust over the whole database. The query is split into words, and every word must appear, ignoring case, in the title, username, URL, notes, or tags. It skips history, the recycle bin, groups with `EnableSearching` false (inherited by subgroups), and custom fields. Results show their group path;
  - the search query lives in a `TextFieldState` in the ViewModel, out of saved state, and is cleared on lock. Engine reads run off the main thread, and a cancelled read never applies its result;
  - icons are hand-copied Material icon vector drawables. `material-icons-extended` is deprecated, so KeePass's own entry and group icons (`IconID`) aren't shown yet.

  Checked by hand on the emulator with `browse.kdbx`: nested groups and back, the recycle bin, a protected username, several searches (none match passwords), rotation, and locking. A text field in `TopAppBar`'s title slot never received typing on the emulator, so the search bar is its own row.
- **Done:** auto-lock (`security/AutoLock.kt`, `ui/SettingsDialog.kt`), set from the gear icon on the browse screen:
  - locks after a timeout without taps or key presses (`Activity.onUserInteraction`), on screen or in the background. Choices run from 30 seconds to 1 hour, with 5 minutes as the default. There's deliberately no "never";
  - locks when the screen turns off (on by default, can be turned off);
  - in the foreground, a main-thread timer does it. Android freezes cached apps, and frozen apps run no timers, so leaving the screen also sets an inexact `AlarmManager` alarm (no permission needed) for the deadline. Its delivery unfreezes the app, and the app locks. Coming back to the app checks the time first, in case the alarm is late;
  - taps inside dialogs don't reach the activity, so changing an auto-lock setting counts as activity instead;
  - settings live in SharedPreferences (`auto_lock`). Nothing secret.

  Checked by hand on the emulator: foreground timeout, screen off, and background with the app frozen by `am freeze`, where the alarm arrived about 44 s after leaving (30 s timeout) and unfroze the app to lock. Freezing too soon after leaving the app makes Android kill it instead, which also wipes the database.
- **Done:** keeping text away from accessibility apps (`security/Accessibility.kt`). Accessibility apps can read the screen, and abusing that is the usual way Android spyware steals passwords:
  - since Android 8.0, a password field gives every accessibility app its real text, marked as a password, and only TalkBack chooses not to read it. So the master password field gives one dot per character instead, unless the user taps "show password". Screen readers still hear the length;
  - on Android 14+, every window (the activity and each dialog, which is a window of its own: call `AccessibleToToolsOnly()` in it) is hidden from accessibility apps not registered as assistive tools (`isAccessibilityTool`). TalkBack, Switch Access, and Voice Access still work. Compose's events skip that rule, so the window's root marks every event sensitive on its way out. Apps declare themselves tools and only Google Play checks, so this is a second layer.

  Checked on the emulator (API 37) with `tools/a11y-probe`, a test-only accessibility app with one service registered as a tool and one not. Before: the non-tool service read the master password as it was typed, and entry names. After: it gets no window content and no events with text; the tool one gets dots for the password and everything else.
- **Done:** MVP feature 3, viewing an entry and copying its fields (`ui/EntryScreen.kt`, `security/Clipboard.kt`):
  - `entry(id)` returns the entry's fields **without protected values** (they come back as "" with `is_protected`, plus `is_empty`). Username, password, and URL come first, then custom fields in file order, then notes. `reveal_field(entry_id, key)` returns one value as bytes, only when the user taps show or copy;
  - protected fields show a fixed `••••••••` (it gives nothing away, not even the length) until "show" is tapped. A shown value has to become a `String` for Compose to draw it, so it's kept only while that entry is on screen. Shown passwords use a monospace font;
  - copying decodes the bytes into a `CharArray` (`decodeUtf8`, no `String`) and wipes it afterwards. Android turns it into a `String` itself to hand it to the clipboard service, and we can't wipe that one. Protected fields are marked sensitive (`EXTRA_IS_SENSITIVE`), so Android 13+ shows dots in its "copied" preview. Below 13, a snackbar confirms the copy;
  - the clipboard clears itself after 15 s to 5 min (30 s by default, no "never"), set in the settings dialog (formerly the auto-lock dialog). It works like auto-lock: a main-thread timer plus an inexact alarm. The pending clear is remembered in SharedPreferences (`clipboard`, only "a clear is pending" and the clip's timestamp, never the content), so the alarm also works in a fresh process. In the foreground it skips clearing if the user copied something else since (`ClipDescription.timestamp`). In the background Android 10+ hides the clipboard, so it clears anyway;
  - the entry screen sits on top of the browse screen. Back returns to the same group or search, at the same scroll position (`SaveableStateHolder`; only list positions are saved).

  Checked by hand on the emulator with `browse.kdbx`: protected username and password hidden, show/hide, a custom field, tags, the system's "copied" preview shows dots, the clipboard is empty 30 s after a copy, and after copying, leaving the app, and `am kill`, the alarm started a fresh process about 37 s later and the clipboard was empty. Also back to the list, and Lock from the entry screen.
- **Tests:** 73 in Rust (`cargo test --release`), 17 in Kotlin (`./gradlew :app:testDebugUnitTest`).
- **Not built yet:** any way to change a database (edit entries or groups, change the master key). `save` writes back what was opened. Editing is MVP feature 6. Entry dates and attachments aren't shown yet.
- **Next:** MVP feature 4 (biometric quick-unlock).
- **History:** the first skeleton used the kotpass library and was replaced the same day (2026-11-17). See "Project decisions".

## Building

```sh
cd core && cargo test --release           # the KeePass engine (Rust); --release because key derivation is slow unoptimized
                                          # tests/keepassxc_oracle.rs drives keepassxc-cli and skips itself if it isn't installed
cargo clippy --all-targets && cargo fmt   # keep both clean
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug              # APK lands in app/build/outputs/apk/debug/; builds the Rust engine too
./gradlew :app:testDebugUnitTest          # Kotlin tests that call the engine through the bridge, on this computer
```

Gradle needs a JDK. The only one on the dev Mac is Android Studio's bundled copy. `local.properties` (gitignored) holds `sdk.dir`.

Gradle builds the Rust engine itself (`buildRust<Variant>` and `generateRustBindings<Variant>` in `app/build.gradle.kts`). That needs:

- Rust with the Android targets: `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android` (installed on the dev Mac);
- NDK 28.2.13676358 (installed; pinned as `ndkVersion`).

To try the app without a phone, there is an emulator: `~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_37.0`. `FLAG_SECURE` makes screenshots black, so read the screen with `adb shell uiautomator dump --windows` instead (uiautomator isn't an accessibility tool, so on Android 14+ a plain dump no longer sees Pass The Key's window). `adb shell am kill` only works once the app has been in the background for a few seconds. If `adb shell input text` stops reaching a text field, check whether Gboard is showing its full-screen "Try out your stylus" tutorial (`uiautomator dump --windows` lists its window) and tap Cancel.

`tools/a11y-probe` (Gradle module `:a11y-probe`, never shipped) logs what accessibility apps can read from Pass The Key. See its README.

## Module layout

- `core/` (Rust crate `passthekey-core`): **all** KeePass and crypto logic: file format, key derivation, encryption, the data model. No Android dependencies, so it's tested with plain `cargo test`.
- `app/` (Android, Kotlin, package `mn.lls.passthekey`): screens plus Android-only integration (file picker, biometrics, autofill, clipboard). It holds no password logic of its own and calls into `core` through UniFFI.
- The bridge between them:
  - `core/src/ffi.rs` is the only Rust the app can see. It wraps the engine in types that can cross the bridge and holds no logic of its own.
  - UniFFI generates the Kotlin side (package `mn.lls.passthekey.core`) into `app/build/generated/` on every build. It is never checked in or edited.
  - Secrets cross the bridge as bytes (`ByteArray`), never as `String`, so Kotlin can wipe them.
  - `UnlockedDatabase.lock()` wipes the decrypted database immediately. Don't rely on garbage collection for that.
  - Kotlin sees engine errors as `KeePassException` subclasses, with the engine's own user-facing messages.

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
| Rust ↔ Kotlin bridge | UniFFI 0.32 (Mozilla) | Generates the Kotlin bindings. They need JNA (`net.java.dev.jna:jna` 5.19.1, as an AAR), the one Java library we ship because of it |
| Android ABIs | arm64-v8a, armeabi-v7a, x86_64 | 64-bit libraries are 16 KB page aligned (checked) |
| Kotlin tests | JUnit 6 (Jupiter) | Run on the dev computer against the engine built for it |
| Crypto primitives | RustCrypto: `aes`, `cbc`, `chacha20`, `salsa20`, `twofish`, `argon2`, `sha2`, `hmac` | The only things we don't write ourselves |
| Other Rust deps | `flate2` (gzip), `quick-xml`, `zeroize`, `base64`, `getrandom`, `thiserror`, `uniffi` | All released in 2026 when chosen |
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
- **androidx.biometric 1.1.0** and **JUnit 4**: both from 2021. Use androidx.biometric 1.4.x and current test tooling. Android's instrumented tests (`androidx.test`) are built on JUnit 4, so we don't have any: Kotlin tests run on the dev computer, and on-device behavior is checked by hand on the emulator.
- **cargo-ndk**: last release August 2025. Gradle calls cargo and the NDK's clang directly instead (`app/build.gradle.kts`).

### KDBX engine design

- **Lossless by construction.** The decrypted XML is kept as a generic element tree, and the typed views (groups, entries, fields) read and write through that tree. Elements we don't understand are never dropped, because we never parse them into something narrower.
- **Test oracle:** KeePassXC is installed at `/Applications/KeePassXC.app`. `Contents/MacOS/keepassxc-cli` can create databases (`db-create`, `add`) and export the decrypted XML (`export --format xml`). Generate fixtures with it, and compare our decrypted output with its export.
- **Saving** (`core/src/kdbx/write.rs`) is the reading pipeline run backwards. Design rules:
  - Header fields and inner-header fields we don't understand are written back unchanged.
  - Every secret that protects the file is regenerated on every save, as KeePass and KeePassXC do. So every save runs the key derivation again and is as slow as unlocking.
  - `save` never returns a file it hasn't read back and compared with memory (`Error::SaveCheckFailed`). The read-back reuses the derived key instead of deriving it twice.
  - `Database` keeps the composite key hash in memory while it's open, so saving can't lock the file with a different key by accident.
  - The one deliberate exception to losslessness: characters XML 1.0 can't represent (most control characters) are left out of unprotected text, as KeePass 2.x does. Writing them makes a file KeePass and KeePassXC refuse to open. The editing API should reject them at input.
  - KDBX 3 → 4 (`upgrade.rs`) converts dates, moves attachments from `Meta/Binaries` to the inner header, and rebuilds the header. It keeps AES-KDF and its round count.
- KeePassXC's XML export isn't byte-stable between runs: it stamps `_LAST_MODIFIED`, reorders custom data items, and renumbers attachment `Ref`s. The oracle tests even these out. `keepassxc-cli attachment-export --stdout` re-encodes binary data as text, so export attachments to a file.
- Keep secrets in `zeroize`-on-drop buffers. Never `Debug`-print or log secret values. Build plaintext in `SecretBuf` (`util.rs`), which wipes its old allocation when it grows.

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
- [ ] Open a file we saved in KeePass 2.x itself (needs Windows or Mono). Only KeePassXC and pykeepass have been checked
- [ ] KDBX 3 attachments marked `Protected="True"` in `Meta/Binaries` fail to open unless they happen to be text (the reader treats every protected value as UTF-8). Rare: KeePass and KeePassXC don't protect attachments by default
- [ ] `save` doesn't set `Meta/Generator`. Decide whether to write "Pass The Key" once editing exists
- [ ] Benchmark Argon2 unlock time on a low-end phone
- [ ] `browse.kdbx` (AES-KDF, 14.3 million rounds, which KeePassXC tuned for 100 ms on the dev Mac) takes about 30–40 s to unlock on the arm64 emulator. Either AES hardware isn't used there or the emulator is just slow; measure on a phone
- [ ] Show KeePass icons (`IconID`, custom icons) for groups and entries
- [ ] AES hardware on Android: from the source, `aes` 0.9 detects ARMv8 AES at runtime on arm64 Android (through `cpufeatures`), so no `RUSTFLAGS` are needed. Not measured on a device yet. 32-bit ARM (armeabi-v7a) always uses the software implementation, so AES-KDF unlocks are slower there
- [ ] Unlock on a physical phone (only the emulator so far)
- [ ] Compose's text field keeps its own immutable copies of what's typed (and the keyboard sees it). We clear the field and its undo history, but can't wipe those copies. Every Android password manager has the same limit
- [ ] Reproducible Rust builds for F-Droid (pinned toolchain, path remapping)
- [ ] KDBX 3: wrong-key detection relies on padding plus the stream start bytes, which is standard. KDBX 4 uses the header HMAC
- [ ] Android 8–13 have no way to hide a window from non-tool accessibility apps. There, the master password and hidden protected fields are masked; entry names, usernames, and any protected value the user taps "show" on are readable by any accessibility app the user enabled
- [ ] `reveal_field`'s bytes pass through a UniFFI `RustBuffer`, which is freed without being wiped. The decrypted database is in the same memory anyway while it's open, so this adds little, but it's a copy we don't control
- [ ] Copying a field opens its value to everything that can read the clipboard until it's cleared (keyboards, and on Android 9 and older, any app). The sensitive flag asks keyboards not to keep it in their clipboard history; we can't enforce that
- [ ] App icon
