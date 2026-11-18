# Test fixtures

These are throwaway test databases. None of them hold real secrets.

Generated with KeePassXC 2.8.0's `keepassxc-cli` on 2026-11-17. The CLI always writes **KDBX 3.1** with AES-KDF and AES-256.

| File | Unlock with | Contents |
|---|---|---|
| `pw-only.kdbx` | password `test-password` | "Email" (edited once, so it has 1 history item), "Banking/Credit Union" (Unicode password) |
| `keyfile-only.kdbx` | `keyfile.keyx` (no password) | "Keyfile Entry" |
| `pw-and-keyfile.kdbx` | password `test-password` + `pw-and-keyfile.keyx` | "Both Entry" |

A fourth KDBX 3.1 file covers attachments, which KDBX 3 stores inside the XML. It was made with `db-create`, `add`, and `attachment-import`:

| File | Unlock with | Contents |
|---|---|---|
| `kdbx3-attachment.kdbx` | password `test-password` | "With Files" (attachments `photo.bin` and `notes.txt`, plus history items that reference them), "No Files" |

`attachment-photo.bin` and `attachment-notes.txt` are the original attached files. The tests upgrade this database to KDBX 4, save it, and check that KeePassXC gets the same two files back out.

Each `*.export.xml` is KeePassXC's own decrypted export of the matching database (`keepassxc-cli export --format xml`). The tests use them as an independent answer key: every field we decrypt must match what KeePassXC decrypts.

## KDBX 4

The KeePassXC CLI can't write KDBX 4, so these were written by **pykeepass 4.2.0**, an independent Python implementation, using `make_kdbx4_fixtures.py`. KeePassXC then opened them and produced the `*.export.xml` answer keys. That means three implementations agree: pykeepass wrote them, KeePassXC read them, and our code must match.

| File | Unlock with | Settings |
|---|---|---|
| `kdbx4-argon2id-chacha20.kdbx` | password `test-password` | KDBX 4.0, Argon2id (8 MiB, 2 passes), ChaCha20 |
| `kdbx4-argon2d-aes256-keyfile.kdbx` | password `test-password` + `kdbx4-hex.key` (64-char hex key file) | KDBX 4.0, Argon2d, AES-256 |
| `kdbx4-aeskdf-twofish.kdbx` | password `test-password` | KDBX 4.0, AES-KDF (60,000 rounds), Twofish |

Each holds "Test" (with a protected custom field, a plain custom field, and one attachment) and "Sub Group/Unicode" (emoji password).

To regenerate them (pykeepass is test tooling only and never ships in the app):

```sh
python3 -m venv /tmp/pkvenv && /tmp/pkvenv/bin/pip install pykeepass==4.2.0
/tmp/pkvenv/bin/python -I make_kdbx4_fixtures.py "$PWD" "$PWD/kdbx4-hex.key"
```

Then re-export each file with `keepassxc-cli export --format xml`.

## KDBX 4.1

`kdbx41-keepassxc.kdbx` unlocks with password `test-password`. Its export is in `kdbx41-keepassxc.export.xml`. The file itself was **written by KeePassXC** (KDBX 4.1, Argon2d, ChaCha20). It was made like this:

1. pykeepass created a seed database (Argon2d, ChaCha20) containing "Tagged", an entry with tags.
2. `keepassxc-cli add` and `mkdir` added "Added By KeePassXC", "KXC Group", and "KXC Group/Nested". KeePassXC rewrites the whole file with its own writer on every edit.
3. `keepassxc-cli mv` moved "Nested" to the root. That records `PreviousParentGroup`, a 4.1-only field, so KeePassXC upgrades the file to 4.1.

## Browsing and search

`browse.kdbx` unlocks with password `test-password`. It's KDBX 3.1, **written by KeePassXC** from the hand-written `browse.source.xml`:

```sh
printf 'test-password\ntest-password\n' | keepassxc-cli import -q -p -t 100 browse.source.xml browse.kdbx
echo test-password | keepassxc-cli export -q --format xml browse.kdbx > browse.export.xml
```

Hand-written XML gives every group and entry a fixed UUID (all 16 bytes the same: `01` for the root, up to `0c`), plus the cases the browse and search tests need:

| Group | Entries | What it tests |
|---|---|---|
| Root (`01`) | "Email" (`02`): tags, notes, and a history item titled "Old Mail Title" | History is never searched |
| Work (`03`) | "VPN" (`04`): a plain custom field "Account ID" | Custom fields aren't searched |
| Work/Servers (`05`) | "Database Server" (`06`): notes "Rack 4, row B" | Nested paths, notes |
| Recycle Bin (`0b`), second in the file | "Deleted Email" (`0c`) | Listed last, never searched |
| Banking (`07`) | "Crédit Union" (`08`): tags `money;bank`, and a **protected username** | Protected fields stay out of lists and search |
| Archive (`09`), `EnableSearching` false | "Archived Email" (`0a`) | Unsearchable groups |

## Autofill

`autofill.kdbx` unlocks with password `test-password`. Like `browse.kdbx`, it's KDBX 3.1, **written by KeePassXC** from the hand-written `autofill.source.xml`, with the same two commands (`-t 100` is the lowest key derivation time KeePassXC accepts). Its UUIDs are fixed the same way: `01` to `04` for groups, `11` to `1d` for entries.

| Group | Entries | What it tests |
|---|---|---|
| Root (`01`) | "Example" (`11`): `https://example.com/account`, a **protected username**, and a history item for `old.example.com` | Paths are ignored, history is never matched, suggestions never carry a protected username |
| | "Shop" (`12`): `http://www.shop.example.org:8443/cart` | `www.` and ports are ignored. Saved as `http`, so it's offered on plain-http pages too |
| | "No Scheme" (`13`): `plain.example.net/path` | URLs without a scheme |
| | "App Link" (`14`): `androidapp://com.example.app` | Apps linked by package name (Keepass2Android's convention) |
| | "Site And App" (`15`): `https://both.example.com`, plus `KP2A_URL` (`androidapp://com.example.both`) and `KP2A_URL_1` (`https://other.example.com`) | Extra URLs in custom fields |
| | "KeePassDX Style" (`16`): custom fields `AndroidApp` and `AndroidApp_1` | Apps linked the way KeePassDX does it |
| | "Protected URL" (`17`): a **protected** `https://secret.example.com` | Protected URLs match nothing |
| | "Command" (`18`): `cmd://ssh example.com` | Other schemes aren't websites |
| | "No URL" (`19`) | |
| Sites (`02`) | "Example Login" (`1a`): `https://login.example.com`, "Example Admin" (`1b`): `https://admin.example.com` | Exact matches come before parent domains. Subdomain entries aren't offered on the parent site |
| Hidden (`03`), `EnableSearching` false | "Hidden Example" (`1c`): `https://example.com` | Groups hidden from search are hidden from autofill |
| Recycle Bin (`04`) | "Deleted Example" (`1d`): `https://example.com` | Never offered |

## TOTP

`totp.kdbx` unlocks with password `test-password`. Like `browse.kdbx`, it's KDBX 3.1, **written by KeePassXC** from the hand-written `totp.source.xml` with the same two commands. Entries are `21` to `2e`, all in the root group. Every secret is one of RFC 6238's (`12345678901234567890`, or its 32- and 64-byte versions for SHA-256 and SHA-512), so the expected codes come from the RFC's tables.

| Entry | Settings | What it tests |
|---|---|---|
| "Defaults" (`21`) | `otp`: `otpauth://totp/…?secret=…&issuer=…` | SHA-1, 6 digits, 30 s when the URI doesn't say |
| "SHA-256" (`22`), "SHA-512" (`23`) | `otp` with `algorithm`, `digits=8`, and (SHA-256) `period=60` | The other algorithms, 8 digits, another period |
| "Steam" (`24`) | `otp` with `encoder=steam` | Steam Guard's 5-letter codes |
| "KeeOTP" (`25`) | `otp`: `key=…&size=7&step=45` | The KeeOTP plugin's form, which KeePassXC also reads |
| "Legacy" (`26`), "Legacy Steam" (`27`) | `TOTP Seed` with `TOTP Settings` `30;8` or `30;S` | What KeePassXC wrote before 2.6, and TrayTOTP |
| "KeePass Base32" (`28`) | `TimeOtp-Secret-Base32`, `-Length` 8, `-Period` 60, `-Algorithm` `HMAC-SHA-256` | KeePass 2's own fields |
| "KeePass UTF-8" (`29`), "KeePass Hex" (`2a`), "KeePass Base64" (`2b`) | `TimeOtp-Secret`, `-Secret-Hex` (with SHA-512), `-Secret-Base64` (with length 7) | KeePass 2's other secret encodings. KeePassXC 2.8 doesn't read these |
| "Broken" (`2c`) | `otp` with a secret that isn't Base32 | Refused with a reason. KeePassXC makes up a code |
| "Counter" (`2d`) | `otp`: `otpauth://hotp/…` | HOTP is refused with a reason. KeePassXC treats it as TOTP |
| "No TOTP" (`2e`) | none | |

`tests/totp.rs` also compares our codes with what `keepassxc-cli show --totp` prints at the same moment, for every entry KeePassXC reads.

**Not yet covered:** files written by the original KeePass 2.x (Windows/.NET).

## Passkeys

`passkey.kdbx` unlocks with password `test-password`. Like `autofill.kdbx`, it's KDBX 3.1, **written by KeePassXC** from the hand-written `passkey.source.xml` with the same two commands. Every private key was made by OpenSSL (`openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256`, and `-algorithm ed25519` for the Ed25519 one), so the keys come from an implementation independent of ours. Passkeys use KeePassXC's `KPEX_PASSKEY_*` fields. Credential IDs are 32 bytes of the entry's own UUID byte (`31` to `39`). KeePassXC's `db-info` counts 7 passkeys: it leaves out the recycle bin.

| Group | Entries | What it tests |
|---|---|---|
| Root (`01`) | "Example (Passkey)" (`31`): `example.com`, user `alice@example.com`, handle `alice`, BE and BS `1` | A plain KeePassXC passkey |
| | "Example (Bob)" (`32`): rpId written `Example.com`, BE and BS `0` | rpIds compare ignoring case; the flags are read |
| | "StrongBox Import" (`33`): `strongbox.example`, with `KPEX_PASSKEY_GENERATED_USER_ID` and `KPXC_PASSKEY_USERNAME` | KeePassXC's StrongBox fields take precedence. No flag fields, so both default to on |
| | "Unwrapped PEM" (`34`): `unwrapped.example`, the PEM without line breaks, the credential ID padded | Other apps' lax encodings |
| | "Ed25519" (`35`): `ed.example`, an Ed25519 key | Listed, but explained as unusable |
| | "Linked App" (`36`): `app.example`, `AndroidApp` `com.example.app` with `AndroidApp Signature` `AA:AA:…` (32 times), and `AndroidApp_1` `com.example.other` without a signature | KeePassDX's app links |
| | "Example Password" (`37`): `https://example.com/login`, no passkey | A place to add a passkey to |
| Hidden (`02`), `EnableSearching` false | "Hidden Passkey" (`38`): `example.com` | Never offered |
| Recycle Bin (`04`) | "Deleted Passkey" (`39`): `example.com` | Never offered, but still counts as one the site has (`excludeCredentials`) |
