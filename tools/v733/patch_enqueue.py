from pathlib import Path
import argparse
import hashlib
import struct
import zlib

# Patch ONLY ShelfCacheWorker.Companion.enqueue(Context,String) in the v731-based classes3.dex.
# ShelfCacheWorker.doWork() is intentionally untouched: v732 demonstrated that inserting code
# into the Kotlin coroutine state machine can create invalid ART register type merges.

CODE = 0x21976C
STR_CLASS = 0x7B51              # SimpleReaderBackupDecoder.kt
STR_ENQUEUE = 0xACC2            # enqueue
TYPE_CONTEXT = 0x002A
TYPE_STRING = 0x0923
TYPE_CLASS_ARRAY = 0x2450
TYPE_OBJECT_ARRAY = 0x2457
M_CLASS_FORNAME = 0x326F
M_CLASS_GETMETHOD = 0x3289
M_METHOD_INVOKE = 0x3485


def cstr(v, idx): return [(v << 8) | 0x1A, idx]
def cclass(v, idx): return [(v << 8) | 0x1C, idx]
def move_res_obj(v): return [(v << 8) | 0x0C]
def const4(v, lit): return [((lit & 0xF) << 12) | (v << 8) | 0x12]
def invoke35(op, mid, regs):
    rr = list(regs) + [0] * (5 - len(regs))
    c, d, e, f, g = rr[:5]
    return [op | (g << 8) | (len(regs) << 12), mid, c | (d << 4) | (e << 8) | (f << 12)]
def filled(t, regs): return invoke35(0x24, t, regs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('input_dex', type=Path)
    ap.add_argument('output_dex', type=Path)
    args = ap.parse_args()
    b = bytearray(args.input_dex.read_bytes())
    size = struct.unpack_from('<I', b, CODE + 12)[0]
    if size != 113:
        raise SystemExit(f'unexpected enqueue code size: {size}')
    insn = CODE + 16
    def w16(off, value): struct.pack_into('<H', b, off, value & 0xFFFF)
    def patch(pc, words):
        for i, word in enumerate(words): w16(insn + 2 * (pc + i), word)

    # public static kt.enqueue(Context,String), invoked reflectively so classes3 does not need a
    # new cross-dex method/type reference.
    w = []
    w += cstr(0, STR_CLASS)
    w += invoke35(0x71, M_CLASS_FORNAME, [0])
    w += move_res_obj(0)
    w += cstr(1, STR_ENQUEUE)
    w += cclass(2, TYPE_CONTEXT)
    w += cclass(3, TYPE_STRING)
    w += filled(TYPE_CLASS_ARRAY, [2, 3])
    w += move_res_obj(2)
    w += invoke35(0x6E, M_CLASS_GETMETHOD, [0, 1, 2])
    w += move_res_obj(0)
    w += const4(1, 0)
    w += filled(TYPE_OBJECT_ARRAY, [4, 5])
    w += move_res_obj(2)
    w += invoke35(0x6E, M_METHOD_INVOKE, [0, 1, 2])
    w += [0x000E]
    if len(w) > size:
        raise SystemExit('trampoline does not fit')
    patch(0, w + [0] * (size - len(w)))

    # DEX header integrity.
    b[12:32] = hashlib.sha1(b[32:]).digest()
    struct.pack_into('<I', b, 8, zlib.adler32(b[12:]) & 0xFFFFFFFF)
    args.output_dex.write_bytes(b)
    print(hashlib.sha256(b).hexdigest())


if __name__ == '__main__':
    main()
