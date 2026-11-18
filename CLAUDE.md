# PocketKeePass: project context

A free, open-source Android password manager that reads and writes KeePass (`.kdbx`) databases.

## Why this exists

Today's Android KeePass apps are either clunky or paid. PocketKeePass aims to be:

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
  - the open database lives in the app-wide `Session` (see feature 5), not in a ViewModel. It's locked by the Lock button, by auto-lock (below), and when the app's own screen is closed for good (`MainActivity.onDestroy` with `isFinishing`);
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
- **Done:** MVP feature 4, biometric quick unlock (`security/QuickUnlock.kt`, the switch in the settings dialog):
  - what's stored is the **composite key hash** (`Database::key_hash`, `open_with_key_hash`), not the derived key. Every save makes a fresh key derivation salt, so a stored derived key would stop working after any save, ours or another app's. The hash keeps working until the master key changes. The cost: key derivation still runs, so it's no faster than typing the password;
  - the hash is encrypted with an AES-256-GCM Android Keystore key that needs a strong biometric for every use (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on 11+), is deleted by Android when biometrics or the screen lock change, and lives in StrongBox when the phone has it. The device PIN can't use it. Only the ciphertext, its IV, and the database's location are stored (SharedPreferences `quick_unlock`);
  - it's opt-in from settings (turning it on takes a biometric check too), one database at a time. Picking another database, or adding or removing the key file, turns it off;
  - the prompt (androidx.biometric 1.4's `registerForAuthenticationResult`) opens by itself once after each lock, and the unlock screen has a button for it. "Use master password" closes it quietly. If Android deleted the key, or the hash no longer opens the database, quick unlock turns itself off and the unlock screen says why;
  - the ViewModel sends prompt requests through a channel. The activity shows them, and the result goes back to the ViewModel with its authenticated cipher, so a rotation mid-prompt still unlocks.

  Checked by hand on the emulator (API 37): unavailable without a fingerprint, turning it on, the auto prompt after locking, unlocking, "Use master password", failed attempts until lockout, rotation mid-prompt, process death (comes back locked and prompts), the file replaced by one with another password (turned off, explained), and a second fingerprint enrolled (turned off, explained).
- **Done:** MVP feature 5, the autofill service (`core/src/autofill.rs`, `autofill/`, `ui/AutofillScreen.kt`, `Session.kt`), turned on with a switch in the settings dialog that opens Android's own "preferred service" screen:
  - **matching is in Rust** (`Database::autofill_matches`, `FillTarget`). A web page matches entries saved for its host or a parent domain (an entry for `example.com` is offered on `login.example.com`, never the other way round), with `www.`, ports, and paths ignored. Exact hosts come first. An entry saved as `https` isn't offered on a page loaded over plain `http`. An app matches **only** entries that name its package: `androidapp://<package>` in the URL or a `KP2A_URL*` field (Keepass2Android's convention), or an `AndroidApp*` field (KeePassDX's). Apps are never matched to websites by guessing from the package name. Protected URLs, history, the recycle bin, and groups hidden from search never match. The URL parser is our own (`autofill.rs`);
  - **a website address only counts from a verified browser** (`autofill/TrustedBrowsers.kt`): its package name is in `res/raw/trusted_browsers.txt` and the installed app is signed with the certificate listed there. The list (62 browsers) is generated by `tools/trusted-browsers/update.py` from the list Google publishes for Credential Manager (`gstatic.com/gpm-passkeys-privileged-apps/apps.json`, fetched 2026-11-18), without debug builds and non-browsers. Any other app that reports a website is matched by package name only, and the chooser says the browser couldn't be verified;
  - **no suggestion carries a secret.** Every suggestion is a dataset with authentication: tapping it opens `AutofillActivity`, which fetches that one entry's username and password (`reveal_field`, as `CharArray`s, wiped after) and returns them to Android. Suggestions show the title and the unprotected username only. The pending intents are immutable and the activity isn't exported;
  - while locked there is one suggestion, "Unlock PocketKeePass", which opens the unlock screen (password or biometrics) on top of the other app. After unlocking, a single matching entry is filled in directly; otherwise a chooser lists the matches. "Search PocketKeePass" always opens the chooser, which can fill any entry and always shows where it will go;
  - finding the fields (`autofill/LoginFields.kt`, plain Kotlin so it's unit-tested): autofill hints, HTML attributes, input types, then field names. The username is the field before the password. A lone username field is offered (two-step logins). "New password" fields are never filled. Only fields of the focused field's own web page are filled together, so an embedded frame from another site is kept apart;
  - the open database moved from the ViewModel to the app-wide `Session` (`PocketKeePassApp.session`), because the app, the service, and `AutofillActivity` all need it. Each screen's `DatabaseViewModel` follows it. A database unlocked from autofill stays open until auto-lock, the same as one left open in the background;
  - PocketKeePass's own windows are hidden from autofill services (`importantForAutofill = NO_EXCLUDE_DESCENDANTS` on the window). Before that, another service was shown the master password field, marked as a password, so it could have offered to save it;
  - no "compatibility mode" (reading a browser's address bar through accessibility, which can be spoofed), no saving of new logins yet (needs feature 6), no keyboard inline suggestions yet.

  Checked by hand on the emulator (API 37) with `tools/autofill-probe` and Chrome 145: the labelled, unlabelled, and username-only forms; "Unlock PocketKeePass" then the chooser (two matches) or a direct fill (one match); a direct fill with nothing shown; search and a manual pick; an entry with nothing to fill (a toast, nothing filled); Chrome on `http://localhost` (offers the `http` entry, not the `https`-only one) and on `https://github.com/login`; the same page inside a non-browser app (gets that app's entries, not the site's, and the warning); locking in the app (the next tap offers "Unlock"); auto-lock 30 s after an autofill-only unlock in a fresh process; rotation in the chooser; the settings switch on and off; the minified release build; and that the probe's autofill service gets one empty container from the unlock screen (it got the password field without the window flag).
- **Done:** MVP feature 6, creating, editing, moving, and deleting entries and groups, saved straight away (`core/src/edit.rs`, `edit/`, `ui/EntryEditScreen.kt`, `ui/EditDialogs.kt`, `Session.kt`):
  - **edits are in place on the XML tree**, so whatever an entry or group holds that we don't understand is untouched. Editing an entry copies it into its history first (trimmed to `Meta/HistoryMaxItems`, default 10) and updates its times. An edit that changes nothing touches nothing. Deleting goes to the recycle bin (created when needed, with search and auto-type off), or for good from inside the bin or with the bin turned off, recorded in `DeletedObjects`. Moves set `LocationChanged`, plus `PreviousParentGroup` in KDBX 4.1. Text a KeePass file can't hold is refused (`Error::Invalid`, a user-facing sentence);
  - **protected values the user doesn't change never leave the engine.** The editor shows them as fixed dots, and `FieldEdit.value` null means "keep" (by the field's previous name, so renaming keeps the value). Tapping "Change" fetches it into a masked field. Standard fields keep their own protection (or get `Meta/MemoryProtection`'s default); custom fields have a "protect" switch;
  - the form's text lives in `TextFieldState`s in `EditViewModel`, out of saved state, wiped (with undo history) when it closes or the database locks. Values cross the bridge as bytes and are wiped on both sides;
  - **every change is saved at once** by `Session` in the app's own scope, never cancelled halfway. Before writing, the file is read and compared with the fingerprint (SHA-256) taken when it was opened or last saved. If another app or device changed it, nothing is written and the user chooses: replace it, discard ours and reload (with the stored key hash), or save a copy (`CreateDocument`). The write truncates (`"wt"`), syncs, then reads the file back and compares. While saving, a bar under the top bar says "Saving…", then "Saved" with a check for 2 seconds (`rememberJustSaved`, kept in `App` so a save that ends while the editor closes still shows; announced politely to screen readers). Failures leave the changes in memory with a "not saved" bar, retry, and save-a-copy. Locking with unsaved changes asks first;
  - a KDBX 3 database asks to upgrade to KDBX 4 before its first change;
  - a changed database says "PocketKeePass" in `Meta/Generator`, as every KeePass app does with its own name.

  KeePassXC reads our edited KDBX 4.0, 4.1, and upgraded 3.1 files and exports exactly our entries, history included (`keepassxc_oracle.rs`). Checked by hand on the emulator (API 37): a new entry with a protected custom field and tags (KeePassXC shows it all), renaming without touching the password (kept, old version in history), a conflict after the file was replaced behind the app (dialog, "Not now" banner, reload), a new group deleted into a newly created recycle bin, a move, and a KDBX 3.1 file upgraded on its first new entry.
- **Done:** MVP feature 7, the password generator (`core/src/generator.rs`, `ui/GeneratorDialog.kt`, `edit/GeneratorPreferences.kt`), opened from "Generate password" under the password field (and any protected custom field) in the entry editor:
  - **generation is in Rust** (`PasswordOptions::generate`): 4 to 256 characters (the slider offers 4 to 64) from lowercase, uppercase, digits, and symbols. Symbols are **None, Common, or All**. Common is `!@#$%^*-`, the short set sites commonly accept: it leaves out quotes, `<>&`, `\`, `;`, spaces, brackets, and `_` (which a `\W` check doesn't count as a symbol). All is every printable ASCII symbol. "Avoid look-alike characters" leaves out `0Oo1Il|`;
  - every chosen kind appears at least once. Passwords are drawn **uniformly** from just those passwords (draw from the whole pool, reject any missing a kind), not by placing one of each and shuffling, which favors some passwords. So the strength shown is exact: log2 of how many passwords are possible, by inclusion-exclusion (`strength_bits`). Each character is an unbiased pick (rejection sampling on `getrandom` words);
  - the password crosses the bridge as bytes and goes into the field as a `CharArray`, wiped after. The dialog shows it as a `String` (Compose needs one), held only while the dialog is open. "Use" replaces the field's value, so an existing password is never fetched to replace it;
  - the options (never a password) are remembered in SharedPreferences (`password_generator`). The default is 20 characters, every kind, Common symbols (about 122 bits).

  Checked by hand on the emulator (API 37): generating for an existing entry without fetching its password, then saving and revealing it (they match); Common, All, and None; look-alikes; every kind turned off (the engine's message, "Use" does nothing); the strength in bits against an independent calculation; the options remembered for the next entry; rotation with the dialog open; and that an accessibility dump of the filled field shows only dots.
- **Done:** MVP feature 8, TOTP codes (`core/src/totp.rs`, the code row in `ui/EntryScreen.kt`):
  - **reading the settings and making codes is in Rust** (`Totp::from_entry`, `code_at`). Three ways of storing them are read, in KeePassXC's order: the `otp` field (an `otpauth://totp/` URI as KeePassXC, KeePassDX, and Keepass2Android write it, the KeeOTP plugin's `key=…&size=…&step=…`, or leniently a bare Base32 secret), KeePass 2's `TimeOtp-Secret` / `-Hex` / `-Base32` / `-Base64` with `TimeOtp-Length`, `-Period`, and `-Algorithm`, then the older `TOTP Seed` with `TOTP Settings` (`30;6`, `30;S`). SHA-1, SHA-256, SHA-512, 1 to 10 digits, Steam Guard's 5-letter codes. The HMAC truncation is RFC 4226's;
  - **settings that can't be used are explained, not guessed at**: a secret that isn't valid Base32, an unknown algorithm, an `otpauth://hotp/` URI (HOTP isn't supported). KeePassXC shows a made-up code for the first and treats HOTP as TOTP;
  - `entry(id)` carries `totp: TotpStatus` (`None`, `Ready` with the period, length, and the fields the settings came from, or `Invalid` with the reason), nothing secret. `totp_code(entry_id, unix_seconds)` returns one code as bytes. The app passes the start of the current period, so the code and the countdown always agree;
  - the entry screen shows "One-time code" right after the password, **hidden until "show"** like a password (it's the second factor, and Android 8–13 can't keep it from accessibility apps), with a countdown that's always visible, so the user can see whether there's time to type it. Shown, it's fetched again as each period starts. Copying fetches the current code and marks it sensitive, with the same auto-clear as other fields. When the settings work, the fields holding them are left out of the entry screen (they're still in `fields`, so the editor keeps them); when they don't, they stay, under the reason;
  - the code is based on the phone's clock. Android keeps it in sync with the network by default.

  KeePassXC agrees on every code (`tests/totp.rs` runs `keepassxc-cli show --totp`), and the RFC 6238 and 4226 test vectors pass. Checked by hand on the emulator (API 37) with `totp.kdbx`: codes for the legacy, Steam, KeePass Base32, and default entries matched KeePassXC's at the same moment, the shown code rolled over by itself, a copy pasted as the plain code and Android's preview showed dots, the HOTP entry explained itself and listed its `otp` field, rotation hides the code again, and the editor still lists every `TimeOtp-*` field.
- **Done:** backups around every save (`files/Backups.kt`, `Session.write`, the unlock screen):
  - before the database's file is overwritten, two copies (both still encrypted) go to `noBackupFilesDir/backups/`, named by the SHA-256 of the file's location: the **previous version** (`<hash>.kdbx`, the file as it was) and the **unfinished save** (`<hash>.unfinished.kdbx`, the new bytes). The unfinished save is deleted once the write is read back and confirmed, or when the file next opens fine (`Session.start`), so it only outlives a save that was cut short, and then it has the latest changes. Each copy is written to a temporary file, synced, and renamed into place. If a copy can't be written (storage full), the save goes ahead anyway. The 3 most recently saved databases keep their copies; older ones are deleted;
  - after a conflict, "replace it" backs up the other app's version only if our master key opens it (that costs one more key derivation), so a damaged or foreign file never pushes out a previous version that works;
  - when the file is missing or won't open as a database (`NotKeePass`, `Corrupt`, `UnsupportedVersion`, or can't be read), the unlock screen offers the unfinished save if there is one, or else the previous version, with its time. "Restore backup" asks first, then writes it over the file (read back and compared, like a save). "Save as new file" (`CreateDocument`) writes it elsewhere instead, leaves the old file alone, and makes the new file the database with the same key file (the copies move with it). A wrong password never offers either.

  Checked by hand on the emulator (API 37): the previous version matched the file byte for byte after a save, and no unfinished copy was left; a file truncated to 600 bytes was offered the previous version, restored, and unlocked; a deleted file was saved as a new file, which unlocked; a wrong password showed no backup; an unfinished save (put in place by hand, then the file truncated) was offered as "cut short", restored with its latest change, and then deleted; and "replace it" over random bytes kept the old previous version, while over a valid KeePassXC file with the same password it backed that file up.
- **Done:** the app icon (`art/icon`): an adaptive icon with a themed (monochrome) layer.
- **Tests:** 135 in Rust (`cargo test --release`), 42 in Kotlin (`./gradlew :app:testDebugUnitTest`).
- **Not built yet:** changing the master key or database settings, restoring from history, entry dates and attachments (not shown, but kept), setting up TOTP from the app (today it means adding an `otp` field holding an `otpauth://` URI by hand).
- **Next:** the MVP features are all in. Candidates: setting up TOTP in the editor, saving new logins from autofill, testing on a physical phone.
- **History:** the first skeleton used the kotpass library and was replaced the same day (2026-11-17). See "Project decisions".
- **Renamed** from Pass The Key to PocketKeePass on 2026-11-18, application ID included (`mn.lls.passthekey` → `mn.lls.pocketkeepass`). Nothing had been published. The test fixtures `browse.*.xml` keep their old `Generator` text, because `browse.kdbx` contains it.

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

To try the app without a phone, there is an emulator: `~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_37.0`. `FLAG_SECURE` makes screenshots black, so read the screen with `adb shell uiautomator dump --windows` instead (uiautomator isn't an accessibility tool, so on Android 14+ a plain dump no longer sees PocketKeePass's window). `adb shell am kill` only works once the app has been in the background for a few seconds. If `adb shell input text` stops reaching a text field, check whether Gboard is showing its full-screen "Try out your stylus" tutorial (`uiautomator dump --windows` lists its window) and tap Cancel.

`tools/a11y-probe` (Gradle module `:a11y-probe`, never shipped) logs what accessibility apps can read from PocketKeePass. See its README.

`tools/autofill-probe` (Gradle module `:autofill-probe`, never shipped) has login forms for autofill to fill in, and a stand-in autofill service that logs what another password manager would be shown. See its README. Things that cost time when testing autofill on the emulator:

- Chrome and Android's WebView only allow autofill on pages loaded securely or from `localhost`. Serve the probe's `login.html` on the dev Mac and reach it with `adb reverse tcp:8765 tcp:8765`, not through `10.0.2.2`.
- Chrome needs Settings › Autofill services › Autofill using another service, once per emulator.
- Android doesn't focus a field when a screen opens, so nothing is offered until a field is tapped. Give the list about 3 seconds to appear.
- `adb shell dumpsys autofill` shows the sessions and whether the list is showing. `adb shell cmd autofill set log_level verbose` logs the rest.

## Module layout

- `core/` (Rust crate `pocketkeepass-core`): **all** KeePass and crypto logic: file format, key derivation, encryption, the data model, and editing it (`edit.rs`). No Android dependencies, so it's tested with plain `cargo test`.
- `app/` (Android, Kotlin, package `mn.lls.pocketkeepass`): screens plus Android-only integration (file picker, biometrics, autofill, clipboard). It holds no password logic of its own and calls into `core` through UniFFI.
- The bridge between them:
  - `core/src/ffi.rs` is the only Rust the app can see. It wraps the engine in types that can cross the bridge and holds no logic of its own.
  - UniFFI generates the Kotlin side (package `mn.lls.pocketkeepass.core`) into `app/build/generated/` on every build. It is never checked in or edited.
  - Secrets cross the bridge as bytes (`ByteArray`), never as `String`, so Kotlin can wipe them.
  - `UnlockedDatabase.lock()` wipes the decrypted database immediately. Don't rely on garbage collection for that.
  - The one open `UnlockedDatabase` is held by `Session` (`PocketKeePassApp.session`). Screens and the autofill service read it from there and never keep their own. After changing it, call `Session.changed()`: that saves, and bumps `Session.revision` so screens re-read.
  - Kotlin sees engine errors as `KeePassException` subclasses, with the engine's own user-facing messages.

## Project decisions

- **Name:** "PocketKeePass" (one word, capitalized like that). Publisher: Loveland Solutions. Chosen 2026-11-18 (it was "Pass The Key" before) so the app is recognizable and searchable as a KeePass app.
  - KeePass's author asks that other apps never use "KeePass" as a separate word in their name (keepass.info Downloads page). It must be joined to a prefix or suffix, as in KeePassDX or MiniKeePass. So never "Pocket KeePass", and never a store title like "PocketKeePass – KeePass Manager". Say "KeePass" freely in descriptions ("opens and saves KeePass (.kdbx) databases").
  - Don't use KeePass's own logo or icon anywhere.
  - Public repository: github.com/Loveland-Solutions/pocketkeepass, created 2026-11-18 with the history rewritten to the owner's GitHub no-reply address. The earlier private repository is `Loveland-Solutions/passthekey-old` and keeps PRs #1–7. The local folder is still named `passthekey`.
- **Application ID / package:** `mn.lls.pocketkeepass`. This is the Loveland Solutions domain (lls.mn) written backwards, and it matches the Tile Timer Android app (`mn.lls.tiletimer`). It can never change once the app is published on Google Play.
- **License:** GPLv3. Most KeePass apps use it, F-Droid accepts it, it stops closed-source forks, and it lets us reuse KeePassDX code if needed. MIT and Apache-2.0 libraries can be used inside a GPLv3 app.
- **Distribution:** F-Droid, Google Play, and GitHub releases. F-Droid means no proprietary dependencies (no Google Play Services or Firebase) and reproducible builds where possible.
- **Dependency standard (the owner's words: "I would rather reinvent the wheel than make junk pieced together from old scrap"):**
  - Every dependency must be actively maintained and on a current release. Check the release date before adding anything. Stale, abandoned, or years-old libraries are not acceptable, even when they "work".
  - When no current, high-quality library exists, **we write it ourselves**. That includes the whole KDBX format.
  - **The one exception:** cryptographic primitives (AES, ChaCha20, Salsa20, Twofish, Argon2, SHA-1, SHA-2, HMAC) come from the RustCrypto crates. Hand-written crypto fails silently and dangerously, while RustCrypto is current, audited, and widely used. Everything above the primitives is ours.
- **Minimize Java.** Password logic lives in Rust, not on the JVM. Kotlin is only the thin Android layer, because Android's APIs (autofill, biometrics, file picker) are only reachable from Kotlin or Java. A JDK exists only because Gradle requires one.

## Tech stack (confirmed)

| Area | Choice | Notes |
|---|---|---|
| Engine language | Rust (edition 2024) | `core/` crate |
| Android layer language | Kotlin | |
| Rust ↔ Kotlin bridge | UniFFI 0.32 (Mozilla) | Generates the Kotlin bindings. They need JNA (`net.java.dev.jna:jna` 5.19.1, as an AAR), the one Java library we ship because of it |
| Android ABIs | arm64-v8a, armeabi-v7a, x86_64 | 64-bit libraries are 16 KB page aligned (checked) |
| Kotlin tests | JUnit 6 (Jupiter) | Run on the dev computer against the engine built for it |
| Crypto primitives | RustCrypto: `aes`, `cbc`, `chacha20`, `salsa20`, `twofish`, `argon2`, `sha1` (TOTP only), `sha2`, `hmac` | The only things we don't write ourselves |
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
- Files saved by PocketKeePass must open in KeePassXC and KeePass 2.x. That's the compatibility bar.

### Files and sync
- Use the **Storage Access Framework** (`ACTION_OPEN_DOCUMENT`) with persisted URI permissions. Cloud sync then works through any provider (Google Drive, Dropbox, Nextcloud, Syncthing folders) without our own cloud code.
- **Safe save:** build the full database in memory, verify it decrypts, back up the current file in app storage, then overwrite. A half-written file can always be replaced by the backup.
- Detect when the file changed underneath us since it was opened, then offer to reload, overwrite, or merge (merge can come later).

### Security
- **No `INTERNET` permission.** The app never talks to the network. This is a headline feature.
- Biometric quick-unlock: the composite key hash (not the derived key, which changes on every save) is encrypted with an Android Keystore key that requires biometric auth and is invalidated when biometrics change. Master password is always the fallback.
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
7. Password generator. It must have an option for **special characters that sites commonly accept** (the owner's request, 2026-11-18: other KeePass apps generate symbols that sites then reject). So symbols are a choice between that short, widely accepted set and the full set, not just on or off
8. TOTP codes (KeePassXC `otp` field format and the KeePass 2 `TimeOtp-*` fields)

### Later
- Create a new database
- Attachments (view/add)
- Database merge on sync conflicts
- Inline autofill suggestions in the keyboard (Android 11+)
- Passkeys via Credential Manager
- Multiple databases, entry history browsing, recycle bin management
- Passphrases (random words) in the generator. Needs a word list bundled with the app (the EFF's long list is the usual choice; check its license)

### Non-goals
- Our own sync server or cloud accounts
- Any monetization
- Inventing a non-KeePass format

## Conventions

- All KeePass and crypto logic lives in the Rust `core/` crate, never in Kotlin.
- Crypto and parsing code needs tests, including round-trip tests against real sample databases created by KeePassXC and KeePass 2.x. Store them in `core/tests/fixtures/`, with their passwords documented next to them.
- Never write crypto primitives ourselves; use RustCrypto. Everything else is fair game to write ourselves.
- Small, focused commits.
- **Credits stay visible:** the README's Credits section and the app's About section (end of the settings dialog, plus one line on the welcome screen) say that the app is written with Claude, credit KeePass and Dominik Reichl for the format, and say we're independent of the KeePass project. Keep them when changing those screens, and put the same in store listings.

## Open items

- [ ] More sample databases: files written by KeePass 2.x itself (Windows/.NET). KDBX 3.1, 4.0, and 4.1 are covered
- [ ] Open a file we saved in KeePass 2.x itself (needs Windows or Mono). Only KeePassXC and pykeepass have been checked
- [ ] KDBX 3 attachments marked `Protected="True"` in `Meta/Binaries` fail to open unless they happen to be text (the reader treats every protected value as UTF-8). Rare: KeePass and KeePassXC don't protect attachments by default
- [ ] `Meta/HistoryMaxSize` isn't enforced, only `HistoryMaxItems`. KeePass also drops old history once an entry's history passes the size (6 MiB by default), which matters for entries with attachments
- [ ] No merge on conflict yet: the user picks one version or saves a copy. Merging (by UUID and modification time, using `DeletedObjects`) is on the "Later" list
- [ ] Auto-lock throws away changes that couldn't be saved (security wins). Only the Lock button asks first. A save that's running when the database locks still finishes
- [ ] The file picker gives access to one file, not its folder, so saving can't write a temporary file beside it and swap. A crash halfway through a write can still damage the file; the unlock screen then offers the save that was cut short (or the version before it) from app storage. Not covered: a cut short write to a cloud provider that then syncs the damaged file to other devices before PocketKeePass is opened again. They'll see it damaged until this phone restores it
- [ ] An unfinished save is only offered when the file is damaged. If a save failed without damaging the file (a write error, not a crash) and the database was then locked, those changes are lost as before, and the copy is deleted at the next unlock. Recovering them would need a way to open the copy and merge it
- [ ] Saving reads the whole file first to spot changes made elsewhere. Fine for local files; slow over a cloud provider that downloads on every read
- [ ] Group icons and entry icons for new items are KeePass's defaults (key, folder); there's no way to choose one yet
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
- [ ] Quick unlock's button shows until it's tapped even if Android has already deleted the key; the app only finds out when it prepares the cipher. Tapping it then explains and turns quick unlock off
- [ ] Android 16+ offers the device PIN after too many failed fingerprints, but only to turn the sensor back on. Quick unlock's key accepts strong biometrics only, so the PIN never opens the database. Check how this reads on a real phone
- [ ] Autofill treats any parent domain as the same site, without the Public Suffix List. An entry saved for a shared hosting domain itself (`github.io`, `blogspot.com`) would be offered on every site under it. Bundling the list (it changes often, and we have no network) is the fix
- [ ] Autofill compares host names as text, so an entry saved with a non-ASCII host (`bücher.example`) doesn't match the punycode form browsers report (`xn--bcher-kva.example`). Needs IDNA conversion, which we'd write ourselves or take from a current crate
- [ ] Trusted browsers: the list is a snapshot from 2026-11-18. Regenerate it before each release. Browsers Google doesn't list are missing, among them Fennec F-Droid, Tor Browser, Cromite, and IronFox, which F-Droid users are likely to have. Add them with certificates taken from their real APKs. Until then they get the chooser with a warning, not automatic matches
- [ ] Autofill has only been tried on the API 37 emulator, with Chrome. Not yet: Firefox, a physical phone, Android 8 to 12 (which use the same builders, deprecated since 13), a page with a frame from another site (unit-tested only), and the biometric prompt on the autofill unlock screen (the emulator had no fingerprint enrolled)
- [ ] Autofill can't link an app to an entry yet: an app only gets automatic matches if the entry has an `androidapp://` URL (the user can now add one by editing). After a manual pick, offer to remember the app or site in the entry. Editing exists now, so this is unblocked
- [ ] Offer to save new logins from autofill (`SaveInfo`, `onSaveRequest`). Unblocked by editing
- [ ] If the screen is rotated while the unlock screen or chooser is open over an app whose fields have no view IDs, Android can't find the fields again and nothing is filled in. Apps with ordinary layouts are fine (checked)
- [ ] A filled-in username and password pass through Android's autofill framework as `String`s we can't wipe, like the clipboard copy
- [ ] TOTP: no setup in the editor yet (paste a secret or an `otpauth://` URI, building the URI in Rust), and no QR scanning (needs the camera and a QR decoder, which we'd write ourselves). No HOTP (KeePass's `HmacOtp-*` fields and `otpauth://hotp/`), which has to save the counter after every code. Autofill doesn't offer the code yet
- [ ] A shown TOTP code is a `String` (Compose needs one) while it's on screen, like a shown password
