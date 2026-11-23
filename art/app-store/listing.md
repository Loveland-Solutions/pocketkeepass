# App Store listing (iPhone and iPad)

What to enter in App Store Connect for PocketKeePass (`mn.lls.pocketkeepass`). Adapted from the
Google Play listing, with only what the iOS app does today: no passkeys yet (they're shown, not
used), and no screenshot blocking (iOS doesn't allow it). Keep it in step with the app.

KeePass's author asks that "KeePass" never be a separate word in an app's name, so the name is
only "PocketKeePass", and the subtitle doesn't start with "KeePass". Apple doesn't allow other
apps' names in keywords (guideline 2.3.7), so KeePassXC and the rest stay out of them.

## App information

| Field | Value |
|---|---|
| Name | PocketKeePass |
| Subtitle (30) | Free password manager, offline |
| Bundle ID | mn.lls.pocketkeepass |
| SKU | pocketkeepass-ios |
| Primary language | English (U.S.) |
| Category | Utilities (secondary: Productivity) |
| Content rights | Contains no third-party content |
| Age rating | Every question "None" / "No": 4+ |
| Price | Free, all territories |
| Copyright | 2026 Loveland Solutions, LLC |
| Support URL | https://lls.mn/en/contact/ |
| Marketing URL | https://github.com/Loveland-Solutions/pocketkeepass |
| Privacy policy URL | https://lls.mn/en/pocketkeepass/privacy/ |

## App privacy

"Data Not Collected". The app has no network code, no analytics, and no third-party SDKs.
The privacy manifest (`apple/iOS/Shared/PrivacyInfo.xcprivacy`) says the same.

## Promotional text (170)

Your KeePass passwords on iPhone and iPad: free, open source, and offline. Fills in passwords
in Safari and apps, with Face ID.

## Keywords (100)

keepass,kdbx,password manager,autofill,totp,2fa,offline,open source,vault,face id,key file

## Description

PocketKeePass opens and saves KeePass (.kdbx) password databases. It's free, it's open source, and it has no ads, no tracking, and no paid tier.

It also never goes online. Your passwords stay in your database file, and the app contains no code that sends anything anywhere.

YOUR FILE, YOUR CHOICE OF SYNC
PocketKeePass doesn't have its own cloud. You pick a database file from your iPhone or from any storage app you already use: iCloud Drive, Dropbox, OneDrive, Nextcloud. Changes are saved straight back to that file, and changes made on your other devices are picked up while the app is open. The same file works in KeePassXC, KeePass, and other KeePass apps on your computer and on Android.

WHAT IT DOES
• Unlock with your master password, a key file, or both
• Unlock with Face ID or Touch ID after the first time
• Browse and search your groups and entries
• Create, edit, duplicate, move, and delete entries and groups
• Fill in passwords in Safari and other apps with AutoFill
• Show one-time codes (TOTP) for two-factor sign-in
• Generate passwords, including a symbols option that leaves out the characters many sites reject

BUILT TO BE CAREFUL
• Locks itself after a set time, and when the iPhone locks
• Copied passwords stay on this device and clear themselves after a set time
• Hides its contents in the app switcher and while the screen is recorded or shared
• Every save is read back and checked before it counts, and a copy of the previous version is kept in case something goes wrong
• If another device changed the file since you opened it, its changes are merged with yours, entry by entry, instead of one side overwriting the other
• Offline, a save waits on the iPhone until it's back online, so a storage app can't upload it over changes made elsewhere
• Fields it doesn't understand are left exactly as they were, so it won't damage a database made by another app

SUPPORTED FORMATS
KDBX 4.0 and 4.1, plus KDBX 3.1 (upgraded to 4 only if you agree). AES-256, ChaCha20, and Twofish. Argon2d, Argon2id, and AES-KDF.

OPEN SOURCE
PocketKeePass is licensed under GPLv3. You can read the code, check how it handles your passwords, and build it yourself: github.com/Loveland-Solutions/pocketkeepass

CREDITS
PocketKeePass was written with Claude, an AI model made by Anthropic. The KeePass format was created by Dominik Reichl for KeePass. PocketKeePass is an independent app and isn't affiliated with or endorsed by the KeePass project.

## What to test (TestFlight)

Open a KeePass database (.kdbx) from Files, unlock it, and use it as you would on your computer. Then turn on AutoFill in PocketKeePass's settings, and try filling in a password in Safari: tap a password field, then the key above the keyboard, and choose PocketKeePass. Please report anything that looks wrong, especially with files in iCloud Drive or another storage app.

## App Review information

- Sign-in required: no (there are no accounts). The app needs a KeePass database to show anything.
- Notes for the reviewer:

  > PocketKeePass opens KeePass password databases (.kdbx files) the user already has. To try it, open this demo database in Safari on the device and save it to Files: https://github.com/Loveland-Solutions/pocketkeepass/raw/main/art/demo/Demo.kdbx — then in PocketKeePass tap "Open database", choose Demo.kdbx, and unlock it with the password **demo**. The app makes no network connections. AutoFill: Settings in the app › Turn on AutoFill; then in Safari, on any login page, tap the password field, the key above the keyboard, and PocketKeePass.

- Contact: the owner's name, phone, and contact@lls.mn.

## Export compliance

The app leaves `ITSAppUsesNonExemptEncryption` out, so App Store Connect asks about each build.
The engine uses standard encryption (AES, ChaCha20, Twofish) itself, not only Apple's, which is
App Store Connect's own test for needing documentation, so the honest answers are: yes, it uses
encryption; the algorithms are standard, not proprietary; and it uses them instead of Apple's.
What's still to file (the US self-classification report, the French declaration or leaving
France out) is the open item in CLAUDE.md. Once documentation is approved, set the key to YES
with its `ITSEncryptionExportComplianceCode`; YES without a code fails the upload.

## Screenshots

`art/app-store/screenshots/6.3-inch-1-unlock.png` … `6.3-inch-6-settings.png` (1206 × 2622, for "iPhone with
Dynamic Island, medium display"), and the same as `6.9-inch-*` (1320 × 2868), in that order: unlock, browse, an entry, the password generator, search, settings. How they're taken is in
that folder's README. No iPad screenshots: see the open item on iPad in CLAUDE.md.
