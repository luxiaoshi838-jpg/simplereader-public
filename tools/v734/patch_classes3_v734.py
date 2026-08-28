from pathlib import Path
import zipfile, struct, hashlib, zlib

# Input: the installable v731 APK. Output: v734 classes3.dex.
# Preserve the v731 full-shelf path byte-for-byte. Only the valid no-catalog branch is diverted
# through the existing LogKt.logd(Context,String) bridge to the D8-compiled helper worker.
src = Path('v731.apk')
out = Path('classes3_v734.dex')
with zipfile.ZipFile(src) as z:
    b = bytearray(z.read('classes3.dex'))

CODE = 0x21976c
INSN = CODE + 16
size = struct.unpack_from('<I', b, CODE + 12)[0]
assert size == 113

def w16(pc, value):
    struct.pack_into('<H', b, INSN + 2 * pc, value & 0xffff)

# Original no-catalog valid branch: pc 0x1a = goto +3 -> common ShelfCacheWorker path.
# v734: goto pc 0x65 instead. all_books jumps to pc 0x1d and never executes this instruction.
assert struct.unpack_from('<H', b, INSN + 2 * 0x1a)[0] == 0x0328
w16(0x1a, 0x4b28)

# Reuse the old invalid-mode throw block as a tiny no-catalog bridge:
#   LogKt.logd(context, mode)
#   return
# method@1dba already exists in shipped classes3.dex.
words = [0x2071, 0x1dba, 0x0054, 0x000e] + [0x0000] * 8
for i, word in enumerate(words):
    w16(0x65 + i, word)

b[12:32] = hashlib.sha1(b[32:]).digest()
struct.pack_into('<I', b, 8, zlib.adler32(b[12:]) & 0xffffffff)
out.write_bytes(b)
print(hashlib.sha256(b).hexdigest())
