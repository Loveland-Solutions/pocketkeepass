# Demo database

`Demo.kdbx` holds only made-up entries (a "Sam Rivera" on `.example` domains). Its master password is `demo`.

Google Play's reviewers use it: the app's sign-in details in Play Console tell them to download it from
`https://github.com/Loveland-Solutions/pocketkeepass/raw/main/art/demo/Demo.kdbx`, so keep it at this path.
The store screenshots in `art/screenshots` were taken with it.

Made with `keepassxc-cli db-create -p -t 100` (KDBX 3.1, AES-KDF, the shortest key derivation it allows), then
`mkdir` and `add -g` for each group and entry. Opening it for editing offers the usual upgrade to KDBX 4.
