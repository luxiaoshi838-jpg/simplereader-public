from pathlib import Path
import struct, hashlib, zlib

# Input: extracted canonical v722 classes3.dex. Output: v727 classes3.dex.
# v722 remains the binary/UI baseline. The only reader-cache changes below are the
# explicitly requested shelf-pagination reuse fixes and v725 status/log overlay.

src = Path('classes3_v722.dex')
out = Path('classes3_v727.dex')
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
def sget_obj(v, field): return [(v << 8) | 0x62, field]
def iget_obj(a, breg, field): return [0x54 | (a << 8) | (breg << 12), field]
def move_res(v): return [(v << 8) | 0x0a]
def iget(a, breg, field): return [0x52 | (a << 8) | (breg << 12), field]
def bin2addr(op, a, breg): return [op | (a << 8) | (breg << 12)]
def const_high16(v, hi): return [0x15 | (v << 8), hi]
def float_to_int(a, breg): return [0x87 | (a << 8) | (breg << 12)]
def mul_int_lit8(a, breg, lit): return [0xda | (a << 8), (breg & 0xff) | ((lit & 0xff) << 8)]
def div_int_lit8(a, breg, lit): return [0xdb | (a << 8), (breg & 0xff) | ((lit & 0xff) << 8)]

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

# 1) Context,String logger -> bounded v726/v727 history helper.
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

# 4) Pagination identity fixes.
# 4a. Visible v722 reader uses content top/bottom padding 0.
assert b[0x1e594c:0x1e5950] == bytes.fromhex('13051800')
b[0x1e594e:0x1e5950] = b'\x00\x00'

# 4b. When no exact viewport has been remembered yet, use the same readerViewport
# height formula as ReaderActivity: screen - status - nav - 4 current-font pixels
# (1 character top guard + 3 character bottom guard).
co = 0x1e5a48
assert code_size(co) == 55
M_GET_RESOURCES = 0x0035
M_GET_DISPLAY_METRICS = 0x006b
M_SYSTEM_DIMENSION = 0x2412
M_CURRENT_TEXT_SIZE = 0x240e
M_COERCE_AT_LEAST_II = 0x5a0d
F_HEIGHT_PIXELS = 0x0029
F_SCALED_DENSITY = 0x002a
STR_STATUS_BAR_HEIGHT = 0xf59b
STR_NAV_BAR_HEIGHT = 0xdbf5
w = []
w += invoke35(0x6e, M_GET_RESOURCES, [5]); w += move_res_obj(1)
w += invoke35(0x6e, M_GET_DISPLAY_METRICS, [1]); w += move_res_obj(0)
w += cstr(3, STR_STATUS_BAR_HEIGHT)
w += invoke35(0x70, M_SYSTEM_DIMENSION, [4, 1, 3]); w += move_res(2)
w += cstr(3, STR_NAV_BAR_HEIGHT)
w += invoke35(0x70, M_SYSTEM_DIMENSION, [4, 1, 3]); w += move_res(3)
w += bin2addr(0xb0, 2, 3)
w += iget(1, 0, F_HEIGHT_PIXELS)
w += bin2addr(0xb1, 1, 2)
w += invoke35(0x6e, M_CURRENT_TEXT_SIZE, [4, 5]); w += move_res(2)
w += iget(3, 0, F_SCALED_DENSITY)
w += bin2addr(0xc8, 2, 3)
w += const_high16(3, 0x3f00)
w += bin2addr(0xc6, 2, 3)
w += float_to_int(2, 2)
w += mul_int_lit8(2, 2, 4)
w += bin2addr(0xb1, 1, 2)
w += iget(2, 0, F_HEIGHT_PIXELS)
w += div_int_lit8(2, 2, 2)
w += invoke35(0x71, M_COERCE_AT_LEAST_II, [1, 2]); w += move_res(5)
w += [0x050f]
assert len(w) <= 55
patch_words(co, 0, w + [0] * (55 - len(w)))

# 4c. Critical stale rule-version mismatch. ReaderActivity uses 111 but the
# worker used 15 when constructing CacheIdentity. That guarantees a cache miss.
assert b[0x21b220:0x21b224] == bytes.fromhex('13210f00')
b[0x21b222:0x21b224] = bytes.fromhex('6f00')

# 5) A worker success is valid only when the just-written page cache can be
# immediately loaded with the same CacheIdentity.
co = 0x219b2c
assert code_size(co) == 41
M_THROW = 0x3b17
F_PAGECACHE_INSTANCE = 0x0aa7
F_THIS = 0x10ed
F_IDENTITY = 0x10eb
F_PAGED = 0x10ec
M_GET_APP_CONTEXT = 0x2e59
M_SAVE_PAGES = 0x23d5
M_GET_TEXT = 0x2405
M_LOAD_PAGES = 0x23cb
STR_NONNULL = 0xde1c
M_CHECK_NOT_NULL = 0x569e
F_UNIT = 0x13f5
w = []
w += invoke35(0x71, M_THROW, [4])
w += sget_obj(0, F_PAGECACHE_INSTANCE)
w += iget_obj(1, 3, F_THIS)
w += invoke35(0x6e, M_GET_APP_CONTEXT, [1]); w += move_res_obj(1)
w += iget_obj(2, 3, F_IDENTITY)
w += iget_obj(4, 3, F_PAGED)
w += invoke35(0x6e, M_SAVE_PAGES, [0, 1, 2, 4])
w += invoke35(0x6e, M_GET_TEXT, [4]); w += move_res_obj(4)
w += invoke35(0x6e, M_LOAD_PAGES, [0, 1, 2, 4]); w += move_res_obj(0)
w += cstr(1, STR_NONNULL)
w += invoke35(0x71, M_CHECK_NOT_NULL, [0, 1])
w += sget_obj(0, F_UNIT)
w += [0x0011]
assert len(w) <= 41
patch_words(co, 0, w + [0] * (41 - len(w)))

# 6) Existing Operation-log menu entry -> bounded list/detail helper.
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
