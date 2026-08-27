from pathlib import Path
import struct, hashlib, zlib

# Input: extracted v722 classes3.dex. Output: v726 classes3.dex.
# v722 remains the binary/UI baseline. The only reader-cache change below is the
# explicitly requested pagination reuse fix: ShelfCacheWorker's default vertical
# content padding must match the real v722 reader viewport (0/0 inside the viewport).

src = Path('classes3_v722.dex')
out = Path('classes3_v726.dex')
b = bytearray(src.read_bytes())

def w16(off, val): struct.pack_into('<H', b, off, val & 0xffff)
def patch_words(code_off, start_pc, words):
    base = code_off + 16 + start_pc * 2
    for i, word in enumerate(words): w16(base + 2 * i, word)
def code_size(code_off): return struct.unpack_from('<I', b, code_off + 12)[0]
def cstr(v, idx): return [(v << 8) | 0x1a, idx]
def cclass(v, idx): return [(v << 8) | 0x1c, idx]
def move_res_obj(v): return [(v << 8) | 0x0c]
def const4(v, lit): return [((lit & 0xf) << 12) | (v << 8) | 0x12]
def invoke35(op, mid, regs):
    rr = list(regs) + [0] * (5 - len(regs))
    C, D, E, F, G = rr[:5]
    return [op | (G << 8) | (len(regs) << 12), mid, C | (D << 4) | (E << 8) | (F << 12)]
def filled(typeidx, regs): return invoke35(0x24, typeidx, regs)

# Existing refs in the shipped v722 classes3.dex.
STR_CLASS = 31569
STR_RECORD = 59555
STR_SHOW = 62132
TYPE_CONTEXT = 42
TYPE_ACTIVITY = 15
TYPE_STRING = 2339
TYPE_CLASS_ARRAY = 9296
TYPE_OBJECT_ARRAY = 9303
M_CLASS_FORNAME = 12911
M_CLASS_GETMETHOD = 12937
M_METHOD_INVOKE = 13445

# 1) Context,String logger -> bounded v726 history helper.
co = 0x1bf028
assert code_size(co) == 29
struct.pack_into('<H', b, co, 8)
w = []
w += cstr(0, STR_CLASS)
w += invoke35(0x71, M_CLASS_FORNAME, [0])
w += move_res_obj(0)
w += cstr(1, STR_RECORD)
w += cclass(3, TYPE_CONTEXT)
w += cclass(4, TYPE_STRING)
w += filled(TYPE_CLASS_ARRAY, [3, 4])
w += move_res_obj(2)
w += invoke35(0x6e, M_CLASS_GETMETHOD, [0, 1, 2])
w += move_res_obj(0)
w += const4(1, 0)
w += filled(TYPE_OBJECT_ARRAY, [6, 7])
w += move_res_obj(2)
w += invoke35(0x6e, M_METHOD_INVOKE, [0, 1, 2])
w += [0x000e]
assert len(w) == 29
patch_words(co, 0, w)

# 2) Disable v722's unbounded operation/log append sink.
co = 0x1bf2f4
assert code_size(co) == 30
patch_words(co, 0, [0x000e] + [0] * 29)

# 3) Fix WorkInfo handling and preserve the real current-book title.
co = 0x1fb46c
assert code_size(co) == 182
w16(co + 16 + 0x000b * 2, 0x0038)
w16(co + 16 + 0x005a * 2, 0x7607)
w16(co + 16 + 0x009e * 2, 0x0065)

# 4) Pagination reuse fix.
# Real v722 ReaderActivity puts the vertical reading guards on readerViewport:
#   top = status bar + 1 character, bottom = navigation bar + 3 characters,
# while contentView itself has top/bottom padding 0. ShelfCacheWorker calls
# ReaderCacheProfile.createSettings() with defaults; v722 defaulted the internal
# content top/bottom padding to 24dp, so its settingsHash could not match the
# visible reader. The same const/16 literal feeds both default top and bottom.
assert b[0x1e594c:0x1e5950] == bytes.fromhex('13051800')
b[0x1e594e:0x1e5950] = b'\x00\x00'

# 5) Existing Operation-log menu entry -> bounded list/detail helper.
co = 0x1fd44c
assert code_size(co) == 61
w = []
w += cstr(0, STR_CLASS)
w += invoke35(0x71, M_CLASS_FORNAME, [0])
w += move_res_obj(0)
w += cstr(1, STR_SHOW)
w += cclass(3, TYPE_ACTIVITY)
w += filled(TYPE_CLASS_ARRAY, [3])
w += move_res_obj(2)
w += invoke35(0x6e, M_CLASS_GETMETHOD, [0, 1, 2])
w += move_res_obj(0)
w += const4(1, 0)
w += filled(TYPE_OBJECT_ARRAY, [5])
w += move_res_obj(2)
w += invoke35(0x6e, M_METHOD_INVOKE, [0, 1, 2])
w += [0x000e]
patch_words(co, 0, w + [0] * (61 - len(w)))

# DEX header integrity.
b[12:32] = hashlib.sha1(b[32:]).digest()
struct.pack_into('<I', b, 8, zlib.adler32(b[12:]) & 0xffffffff)
out.write_bytes(b)
print(out, hashlib.sha256(b).hexdigest())
