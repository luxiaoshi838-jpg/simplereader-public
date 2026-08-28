from pathlib import Path
import hashlib
import struct
import zlib

# Apply to classes3.dex extracted from the shipped v741 APK.
DEX = Path("classes3.dex")
b = bytearray(DEX.read_bytes())

# MainActivity.showDataExportOptions$lambda$172 code_item in v741.
CODE_ITEM = 0x1FCA3C
INSN = CODE_ITEM + 16

# v741 case 2 ("日志"):
#   const/4 v1, 0
#   invoke-direct {v0, v1}, showBookActionsLegacy(...)
#   goto return
expected = [0x0112, 0x2070, 0x287D, 0x0010, 0x0828]
actual = [struct.unpack_from("<H", b, INSN + 2 * i)[0] for i in range(8, 13)]
if actual != expected:
    raise RuntimeError(f"unexpected v741 instructions: {actual!r}")

# Replace with direct call to the existing private MainActivity.showCrashLogList() (method@2883).
replacement = [0x1070, 0x2883, 0x0000, 0x0928, 0x0000]
for i, word in enumerate(replacement, start=8):
    struct.pack_into("<H", b, INSN + 2 * i, word)

# Restore DEX header integrity after instruction patching.
b[12:32] = hashlib.sha1(b[32:]).digest()
struct.pack_into("<I", b, 8, zlib.adler32(b[12:]) & 0xFFFFFFFF)
DEX.write_bytes(b)
print(hashlib.sha256(b).hexdigest())
