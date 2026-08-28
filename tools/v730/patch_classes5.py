from pathlib import Path
import hashlib
import struct
import zlib

# Input: classes5.dex from the signed v729 overlay.
# Output: v730 classes5.dex with only the operation-log detail addView crash fixed.
src = Path("classes5_v729.dex")
out = Path("classes5_v730.dex")
b = bytearray(src.read_bytes())

string_ids_size, string_ids_off = struct.unpack_from("<II", b, 56)
type_ids_size, type_ids_off = struct.unpack_from("<II", b, 64)
proto_ids_size, proto_ids_off = struct.unpack_from("<II", b, 72)
method_ids_size, method_ids_off = struct.unpack_from("<II", b, 88)

def uleb(off):
    value = 0
    shift = 0
    cursor = off
    while True:
        part = b[cursor]
        cursor += 1
        value |= (part & 0x7f) << shift
        if not part & 0x80:
            return value, cursor
        shift += 7

def get_string(index):
    off = struct.unpack_from("<I", b, string_ids_off + 4 * index)[0]
    _, cursor = uleb(off)
    end = b.index(0, cursor)
    return b[cursor:end].decode("utf-8", "replace")

strings = [get_string(i) for i in range(string_ids_size)]
types = [strings[struct.unpack_from("<I", b, type_ids_off + 4 * i)[0]] for i in range(type_ids_size)]

def proto_desc(index):
    _, return_type, params_off = struct.unpack_from("<III", b, proto_ids_off + 12 * index)
    params = []
    if params_off:
        count = struct.unpack_from("<I", b, params_off)[0]
        params = [types[struct.unpack_from("<H", b, params_off + 4 + 2 * i)[0]] for i in range(count)]
    return "(" + "".join(params) + ")" + types[return_type]

# v729 method ref 94 was incorrectly encoded as:
# LinearLayout.addView(View, LinearLayout.LayoutParams)
# Android exposes addView(View, ViewGroup.LayoutParams), not that virtual signature.
class94, proto94, name94 = struct.unpack_from("<HHI", b, method_ids_off + 8 * 94)
_, proto97, _ = struct.unpack_from("<HHI", b, method_ids_off + 8 * 97)
assert types[class94] == "Landroid/widget/LinearLayout;"
assert strings[name94] == "addView"
assert proto_desc(proto94) == "(Landroid/view/View;Landroid/widget/LinearLayout$LayoutParams;)V"
assert proto_desc(proto97) == "(Landroid/view/View;)V"

# Reuse the already-existing (View)V proto and drop the unused LayoutParams argument from
# the two invoke-virtual instructions in showDetail().
struct.pack_into("<H", b, method_ids_off + 8 * 94 + 2, proto97)
for insn_off, expected_regs, new_regs in [
    (0x208A, 0x0625, 0x0025),  # root(v5), scroll(v2), drop params(v6)
    (0x209C, 0x0635, 0x0035),  # root(v5), seek(v3), drop params(v6)
]:
    first = struct.unpack_from("<H", b, insn_off)[0]
    method = struct.unpack_from("<H", b, insn_off + 2)[0]
    regs = struct.unpack_from("<H", b, insn_off + 4)[0]
    assert (first, method, regs) == (0x306E, 94, expected_regs)
    struct.pack_into("<H", b, insn_off, 0x206E)
    struct.pack_into("<H", b, insn_off + 4, new_regs)

# DEX header integrity.
b[12:32] = hashlib.sha1(b[32:]).digest()
struct.pack_into("<I", b, 8, zlib.adler32(b[12:]) & 0xffffffff)
out.write_bytes(b)
print(out, hashlib.sha256(b).hexdigest())
