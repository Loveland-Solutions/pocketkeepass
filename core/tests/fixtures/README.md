# Test fixtures

These are throwaway test databases. None of them hold real secrets.

Generated with KeePassXC 2.8.0's `keepassxc-cli` on 2026-11-17. The CLI always writes **KDBX 3.1** with AES-KDF and AES-256.

| File | Unlock with | Contents |
|---|---|---|
| `pw-only.kdbx` | password `test-password` | "Email" (edited once, so it has 1 history item), "Banking/Credit Union" (Unicode password) |
| `keyfile-only.kdbx` | `keyfile.keyx` (no password) | "Keyfile Entry" |
| `pw-and-keyfile.kdbx` | password `test-password` + `pw-and-keyfile.keyx` | "Both Entry" |

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

**Not yet covered:** files written by the original KeePass 2.x (Windows/.NET).
