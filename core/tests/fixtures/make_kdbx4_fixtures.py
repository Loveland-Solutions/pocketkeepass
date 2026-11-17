"""Generates KDBX 4 test fixtures for Pass The Key with pykeepass (test tooling only)."""
import sys

from construct import Container

from pykeepass import create_database
from pykeepass.kdbx_parsing.kdbx4 import kdf_uuids

out_dir, keyfile = sys.argv[1], sys.argv[2]

FIXTURES = [
    # (file name, cipher, kdf, uses key file)
    ("kdbx4-argon2id-chacha20.kdbx", "chacha20", "argon2id", False),
    ("kdbx4-argon2d-aes256-keyfile.kdbx", "aes256", "argon2", True),
    ("kdbx4-aeskdf-twofish.kdbx", "twofish", "aeskdf", False),
]

for name, cipher, kdf, use_keyfile in FIXTURES:
    path = f"{out_dir}/{name}"
    kp = create_database(path, password="test-password", keyfile=keyfile if use_keyfile else None)
    header = kp.kdbx.header.value.dynamic_header
    header.cipher_id.data = cipher
    params = header.kdf_parameters.data.dict
    params["$UUID"].value = kdf_uuids[kdf]
    if kdf == "aeskdf":
        # AES-KDF in KDBX 4 uses S (seed) and R (rounds) instead of Argon2's fields.
        for k in ["I", "M", "P", "V"]:
            params.pop(k)
        params["R"] = Container(type=0x05, key="R", value=60000, next_byte=0)
    else:
        params["M"].value = 8 * 1024 * 1024  # 8 MiB keeps tests fast
        params["I"].value = 2
    # pykeepass's builder stops at the item whose (peeked) next byte is 0, so only the last may be 0.
    items = list(params.values())
    for i, item in enumerate(items):
        item.next_byte = 0 if i == len(items) - 1 else 0x42

    kp.root_group.name = "Root"
    entry = kp.add_entry(kp.root_group, "Test", "kdbx4-user", "kdbx4-works", url="https://kdbx4.example", notes="multi\nline")
    entry.set_custom_property("TimeOtp-Secret-Base32", "JBSWY3DPEHPK3PXP", protect=True)
    entry.set_custom_property("Plain Custom", "not secret")
    group = kp.add_group(kp.root_group, "Sub Group")
    kp.add_entry(group, "Unicode", "jo", "üñíçødé ✓ 🔑")
    binary_id = kp.add_binary(b"attachment bytes \x00\x01\x02")
    entry.add_attachment(binary_id, "note.bin")
    kp.save()
    print(name, kp.version, kp.encryption_algorithm, kp.kdf_algorithm)
