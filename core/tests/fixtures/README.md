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

**Not yet covered:** files written by the original KeePass 2.x (Windows/.NET).
