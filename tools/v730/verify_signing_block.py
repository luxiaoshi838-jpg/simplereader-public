#!/usr/bin/env python3
from pathlib import Path
import struct
import sys

APK_SIG_MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
V3_ID = 0xF05368C0

if len(sys.argv) != 2:
    raise SystemExit("usage: verify_signing_block.py <apk>")

apk = Path(sys.argv[1])
data = apk.read_bytes()
magic_pos = data.rfind(APK_SIG_MAGIC)
if magic_pos < 0:
    raise SystemExit("FAIL: APK Signing Block not found")

size = struct.unpack_from("<Q", data, magic_pos - 8)[0]
start = magic_pos + len(APK_SIG_MAGIC) - (size + 8)
if start < 0 or struct.unpack_from("<Q", data, start)[0] != size:
    raise SystemExit("FAIL: malformed APK Signing Block")

ids = []
off = start + 8
end = magic_pos - 8
while off < end:
    length = struct.unpack_from("<Q", data, off)[0]
    off += 8
    if length < 4 or off + length > end + 1:
        raise SystemExit("FAIL: malformed signing-block pair")
    pair_id = struct.unpack_from("<I", data, off)[0]
    ids.append(pair_id)
    off += length

missing = [name for pid, name in ((V2_ID, "v2"), (V3_ID, "v3")) if pid not in ids]
if missing:
    raise SystemExit("FAIL: missing APK signature scheme " + ", ".join(missing))

print("OK: APK Signing Block contains v2 and v3")
