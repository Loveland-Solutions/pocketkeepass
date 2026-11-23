# App Store listing (Mac)

The Mac app is the macOS version of the PocketKeePass record (universal purchase, same bundle
ID as the iPhone app), so its name, subtitle, category, privacy answers, age rating, price, and
availability are the iPhone's (`listing.md`). What's below is the Mac version's own: its text,
review notes, and screenshots. Entered through the App Store Connect API on 2026-11-23.

## Promotional text (170)

> Your KeePass passwords on the Mac: free, open source, and offline. Fills in passwords and passkeys in Safari, with Touch ID.

## Keywords (100)

> keepass,kdbx,password manager,autofill,passkeys,totp,2fa,offline,open source,touch id,key file

## Description

> PocketKeePass opens and saves KeePass (.kdbx) password databases. It's free, it's open source, and it has no ads, no tracking, and no paid tier.
>
> It also never goes online. The app has no permission to use the network, so your passwords stay in your database file.
>
> YOUR FILE, YOUR CHOICE OF SYNC
> PocketKeePass doesn't have its own cloud. You open a database file from your Mac or from any folder another app keeps in sync: iCloud Drive, Dropbox, OneDrive, Nextcloud. Changes are saved straight back to that file, and changes made on your other devices are picked up while the app is open. The same file works in KeePassXC, KeePass, and other KeePass apps, and in PocketKeePass on iPhone and Android.
>
> WHAT IT DOES
> • Unlock with your master password, a key file, or both
> • Unlock with Touch ID after the first time
> • Browse and search your groups and entries, side by side in one window
> • Create, edit, duplicate, move, and delete entries and groups
> • Fill in passwords in Safari and other apps with AutoFill
> • Create passkeys and sign in with them, stored in your KeePass file the way KeePassXC stores them
> • Show one-time codes (TOTP) for two-factor sign-in
> • Generate passwords, including a symbols option that leaves out the characters many sites reject
> • Keyboard shortcuts for new entries and groups, editing, and locking
>
> BUILT TO BE CAREFUL
> • Locks itself after a set time, and when the Mac locks, sleeps, or starts its screen saver
> • Its window is left out of screenshots and screen sharing
> • Copied passwords stay on this Mac, are marked so clipboard managers skip them, and clear themselves after a set time
> • Every save is read back and checked before it counts, and a copy of the previous version is kept in case something goes wrong
> • If another device changed the file since you opened it, its changes are merged with yours, entry by entry, instead of one side overwriting the other
> • Fields it doesn't understand are left exactly as they were, so it won't damage a database made by another app
>
> SUPPORTED FORMATS
> KDBX 4.0 and 4.1, plus KDBX 3.1 (upgraded to 4 only if you agree). AES-256, ChaCha20, and Twofish. Argon2d, Argon2id, and AES-KDF.
>
> OPEN SOURCE
> PocketKeePass is licensed under GPLv3. You can read the code, check how it handles your passwords, and build it yourself: github.com/Loveland-Solutions/pocketkeepass
>
> CREDITS
> PocketKeePass was written with Claude, an AI model made by Anthropic. The KeePass format was created by Dominik Reichl for KeePass. PocketKeePass is an independent app and isn't affiliated with or endorsed by the KeePass project.

## URLs

- Support: https://lls.mn/en/contact/
- Marketing: https://github.com/Loveland-Solutions/pocketkeepass

## App Review information

- Sign-in required: no (there are no accounts). The app needs a KeePass database to show anything.
- Notes for the reviewer:

  > PocketKeePass opens KeePass password databases (.kdbx files) the user already has. To try it, download this demo database in Safari: https://github.com/Loveland-Solutions/pocketkeepass/raw/main/art/demo/Demo.kdbx — then in PocketKeePass click "Open database", choose Demo.kdbx in Downloads, and unlock it with the password: demo
  >
  > The app has no network entitlement and makes no connections. Its window is deliberately excluded from screenshots and screen sharing, so it appears blank in a screen recording.
  >
  > AutoFill and passkeys: PocketKeePass › Settings › AutoFill, or System Settings › General › AutoFill & Passwords, then turn on PocketKeePass. In Safari, click a password field and choose PocketKeePass. On webauthn.io, "Register" offers to save the passkey in PocketKeePass.

- Contact: as for the iPhone.

## Screenshots

`art/app-store/screenshots/mac-*.png` (2880 × 1800), taken from a debug build with
`PKP_ALLOW_SCREEN_CAPTURE=1` and the demo database. See that folder's README.
