from pathlib import Path
import struct, hashlib, zlib, zipfile

# Production v732 classes3 overlay. Input is the installable v731 APK.
# v722/v731 remains the behavioral/UI baseline. This patch only adds the approved
# no-catalog target-list bridge and per-book result bridge; the actual strict cache
# probe and detailed operation history live in tools/v732/helper/.../kt.java.
src_apk = Path('/mnt/data/v731.apk')
out = Path('/mnt/data/v732_build/classes3_v732.dex')
with zipfile.ZipFile(src_apk) as z:
    b = bytearray(z.read('classes3.dex'))

CODE = 0x21A3F4
INSN = CODE + 16

def w16(off, val): struct.pack_into('<H', b, off, val & 0xffff)
def words_at(pc, n): return [struct.unpack_from('<H', b, INSN + 2 * (pc + i))[0] for i in range(n)]
def patch(pc, words):
    for i, word in enumerate(words): w16(INSN + 2 * (pc + i), word)
def cstr(v, idx): return [(v << 8) | 0x1a, idx]
def cstr_jumbo(v, idx): return [(v << 8) | 0x1b, idx & 0xffff, (idx >> 16) & 0xffff]
def cclass(v, idx): return [(v << 8) | 0x1c, idx]
def move_res_obj(v): return [(v << 8) | 0x0c]
def const4(v, lit): return [((lit & 0xf) << 12) | (v << 8) | 0x12]
def newinst(v, t): return [(v << 8) | 0x22, t]
def checkcast(v, t): return [(v << 8) | 0x1f, t]
def invoke35(op, mid, regs):
    rr = list(regs) + [0] * (5 - len(regs))
    C, D, E, F, G = rr[:5]
    return [op | (G << 8) | (len(regs) << 12), mid, C | (D << 4) | (E << 8) | (F << 12)]
def invoke_range(op, mid, start, count): return [op | (count << 8), mid, start]
def filled(t, regs): return invoke35(0x24, t, regs)

# Stable refs from the shipped v731 classes3.dex (itself a minimal v722 overlay).
STR_CLASS = 0x7b51
STR_RECORD = 0xe8a3
STR_MODE_NO = 0x9ade
STR_FAIL_PREFIX = 0x10362
TYPE_OBJECT = 0x0916
TYPE_LIST = 0x09d3
TYPE_CLASS_ARRAY = 0x2450
TYPE_OBJECT_ARRAY = 0x2457
TYPE_STRINGBUILDER = 0x0925
M_CLASS_FORNAME = 0x326f
M_CLASS_GETMETHOD = 0x3289
M_METHOD_INVOKE = 0x3485
M_LOGD = 0x1dba
M_GET_APP_CONTEXT = 0x2e59
M_REPORT = 0x2e60
M_SB_INIT = 0x33e1
M_SB_APPEND_STRING = 0x33ed
M_SB_TOSTRING = 0x3400
M_OBJ_TOSTRING = 0x336e
FIELD_INTREF_ELEMENT = 0x165b

# 1) Immediately after mode is resolved, send exact mode to the existing LogKt bridge.
# This lets the helper distinguish all_books from books_without_catalog without inventing
# a second task/log entry.
orig = words_at(0x03ee, 3)
assert orig[0] & 0xff == 0x71, orig
patch(0x03ee, invoke35(0x71, M_LOGD, [11, 4]))

# 2) Replace the old worker-start/list-size/clear-all block (exactly 29 code units)
# with SimpleReaderBackupDecoder.kt.record(Object). The helper returns:
# - original all-books list while preserving the old clear-all behavior, or
# - a no-catalog target list from which only FULLY REUSABLE current caches have been removed.
# A reusable cache requires both current recognition and PageCacheStore.loadPages() success
# under the current ReaderCacheProfile identity; a mere recognition marker is insufficient.
assert 0x042e - 0x0411 == 29
w = []
w += cstr(11, STR_CLASS)
w += invoke35(0x71, M_CLASS_FORNAME, [11])
w += move_res_obj(11)
w += cstr(13, STR_RECORD)
w += cclass(14, TYPE_OBJECT)
w += filled(TYPE_CLASS_ARRAY, [14])
w += move_res_obj(12)
w += invoke35(0x6e, M_CLASS_GETMETHOD, [11, 13, 12])
w += move_res_obj(11)
w += const4(13, 0)
w += filled(TYPE_OBJECT_ARRAY, [0])
w += move_res_obj(12)
w += invoke35(0x6e, M_METHOD_INVOKE, [11, 13, 12])
w += move_res_obj(0)
w += checkcast(0, TYPE_LIST)
assert len(w) == 29, len(w)
patch(0x0411, w)

# 3) Race-safe no-catalog skip marker. If a filtered target becomes reusable after the
# pre-scan but before its turn, emit the no-catalog marker while that book is pending.
# The helper records that target as skipped with an explicit reason.
w = []
w += cstr(12, STR_MODE_NO)
w += invoke35(0x70, M_REPORT, [4, 7, 7, 12])
w += [0x0000]
assert len(w) == 6
patch(0x062f, w)

# 3b) Make the success path explicit before the shared region that v732 repurposes for
# failure logging: completed++ ; preserve the original register shuffle ; jump to 0a1f.
assert 0x094f - 0x0942 == 13
w = []
w += [0x1052, FIELD_INTREF_ELEMENT]   # iget v0,v1, IntRef.element
w += const4(5, 1)
w += [0x50b0]                          # add-int/2addr v0,v5
w += [0x1059, FIELD_INTREF_ELEMENT]   # iput v0,v1, IntRef.element
w += [0x2d08, 0x0008]                 # move-object/from16 v45,v8
w += [0x3807]                         # move-object v8,v3
w += [0x0308, 0x002d]                 # move-object/from16 v3,v45
w += [0x0029, 0x00d2]                 # goto/16 0a1f
assert len(w) == 13, len(w)
patch(0x0942, w)

# 4) Every caught processing failure now logs the pending book's raw Throwable before
# incrementing failed. The helper stores book title + inferred stage + original reason.
assert 0x0a1f - 0x09fc == 35
w = []
w += invoke_range(0x74, M_GET_APP_CONTEXT, 47, 1)
w += move_res_obj(6)
w += newinst(5, TYPE_STRINGBUILDER)
w += invoke35(0x70, M_SB_INIT, [5])
w += cstr_jumbo(3, STR_FAIL_PREFIX)
w += invoke35(0x6e, M_SB_APPEND_STRING, [5, 3])
w += invoke35(0x6e, M_OBJ_TOSTRING, [0])
w += move_res_obj(3)
w += invoke35(0x6e, M_SB_APPEND_STRING, [5, 3])
w += invoke35(0x6e, M_SB_TOSTRING, [5])
w += move_res_obj(5)
w += invoke35(0x71, M_LOGD, [6, 5])
w += [0xf052, FIELD_INTREF_ELEMENT]   # iget v0,v15, failed IntRef.element
w += const4(5, 1)
w += [0x50b0]
w += [0xf059, FIELD_INTREF_ELEMENT]   # iput v0,v15, failed IntRef.element
assert len(w) == 35, len(w)
patch(0x09fc, w)

# DEX header integrity.
b[12:32] = hashlib.sha1(b[32:]).digest()
struct.pack_into('<I', b, 8, zlib.adler32(b[12:]) & 0xffffffff)
out.write_bytes(b)
print(hashlib.sha256(b).hexdigest(), len(b))
