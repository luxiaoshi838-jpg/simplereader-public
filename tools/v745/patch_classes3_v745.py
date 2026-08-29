from pathlib import Path
import struct, hashlib, zlib

# Run against classes3.dex extracted from shipped v742.
DEX_IN = Path("classes3.dex")
DEX_OUT = Path("classes3_v745.dex")
BOOKMARK_DRAWABLE_ID = 0x7F08009C


def fix_dex_header(buf: bytearray) -> None:
    buf[12:32] = hashlib.sha1(buf[32:]).digest()
    struct.pack_into("<I", buf, 8, zlib.adler32(buf[12:]) & 0xFFFFFFFF)


def patch_const_string_jumbo(buf: bytearray, off: int, reg: int, old_idx: int, new_idx: int) -> None:
    old = struct.pack("<HHH", 0x001B | (reg << 8), old_idx & 0xFFFF, (old_idx >> 16) & 0xFFFF)
    new = struct.pack("<HHH", 0x001B | (reg << 8), new_idx & 0xFFFF, (new_idx >> 16) & 0xFFFF)
    assert bytes(buf[off:off + 6]) == old
    buf[off:off + 6] = new


def const32(reg: int, value: int) -> bytes:
    return struct.pack("<HHH", 0x0014 | (reg << 8), value & 0xFFFF, (value >> 16) & 0xFFFF)


def invoke_set_background_resource(view_reg: int, tmp_reg: int) -> bytes:
    # View.setBackgroundResource(I), method@020c in shipped v742 classes3.dex.
    return struct.pack("<HHH", 0x206E, 0x020C, (view_reg & 0xF) | ((tmp_reg & 0xF) << 4))


def safe_background_patch(buf: bytearray, start: int, keep_v5_off: int, end: int, tmp_reg: int) -> None:
    # Preserve the method return boolean register and preserve v5=40.
    prefix = const32(tmp_reg, BOOKMARK_DRAWABLE_ID) + invoke_set_background_resource(1, tmp_reg) + b"\x00\x00"
    assert len(prefix) == keep_v5_off - start
    buf[start:keep_v5_off] = prefix

    # Original const/16 v5, 40 must remain byte-for-byte.
    assert bytes(buf[keep_v5_off:keep_v5_off + 4]) == bytes.fromhex("13052800")

    tail_start = keep_v5_off + 4
    assert (end - tail_start) % 2 == 0
    buf[tail_start:end] = b"\x00\x00" * ((end - tail_start) // 2)


b = bytearray(DEX_IN.read_bytes())

# Shelf selection mode only: 取消 -> ×.
patch_const_string_jumbo(b, 0x1FAC3A, 0, 0x10347, 0x102F0)
patch_const_string_jumbo(b, 0x1FF79A, 0, 0x10347, 0x102F0)

# TXT ReaderActivity.
patch_const_string_jumbo(b, 0x206E18, 2, 0x103E5, 0x00000)  # 添 -> empty
# Keep const/4 v2,1 at 0x206e58; use v3 for drawable id; keep v5=40 at 0x206e68.
safe_background_patch(b, 0x206E5A, 0x206E68, 0x206E84, tmp_reg=3)

# EPUB ReadiumEpubActivity.
patch_const_string_jumbo(b, 0x214C0A, 2, 0x10400, 0x00000)  # 签 -> empty
# Keep return boolean v4 untouched; use v2 for drawable id; keep v5=40 at 0x214c58.
safe_background_patch(b, 0x214C4A, 0x214C58, 0x214C74, tmp_reg=2)

fix_dex_header(b)
DEX_OUT.write_bytes(b)
print(DEX_OUT)
