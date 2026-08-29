from pathlib import Path
import hashlib
import struct
import zlib

# Production overlay notes:
# - Base DEX must be the v742 classes3.dex.
# - This script intentionally does NOT contain any signing material.


def fix_dex_header(buf: bytearray) -> None:
    buf[12:32] = hashlib.sha1(buf[32:]).digest()
    struct.pack_into('<I', buf, 8, zlib.adler32(buf[12:]) & 0xFFFFFFFF)


def patch_const_string_jumbo(buf: bytearray, off: int, reg: int, old_idx: int, new_idx: int) -> None:
    old = struct.pack('<HHH', 0x001B | (reg << 8), old_idx & 0xFFFF, (old_idx >> 16) & 0xFFFF)
    new = struct.pack('<HHH', 0x001B | (reg << 8), new_idx & 0xFFFF, (new_idx >> 16) & 0xFFFF)
    assert bytes(buf[off:off + 6]) == old
    buf[off:off + 6] = new


def patch_background_preserve_v5(buf: bytearray, start: int, end: int, resource_id: int = 0x7F08009C) -> None:
    # const v0, resource_id
    ins = struct.pack('<HHH', 0x0014, resource_id & 0xFFFF, (resource_id >> 16) & 0xFFFF)
    # invoke-virtual {v1, v0}, Landroid/view/View;.setBackgroundResource:(I)V // method@020c
    ins += struct.pack('<HHH', 0x206E, 0x020C, 0x0001)
    # CRITICAL v744 fix. The original method later calls dp(v5); v743 accidentally removed this definition.
    ins += struct.pack('<HH', 0x0513, 0x0028)  # const/16 v5, #40
    region_len = end - start
    assert len(ins) <= region_len and region_len % 2 == 0
    ins += b'\x00\x00' * ((region_len - len(ins)) // 2)
    buf[start:end] = ins


def patch_classes3(source: Path, target: Path) -> None:
    data = bytearray(source.read_bytes())

    # Shelf selection mode: 取消 -> × (two exact sites only).
    patch_const_string_jumbo(data, 0x1FAC3A, 0, 0x10347, 0x102F0)
    patch_const_string_jumbo(data, 0x1FF79A, 0, 0x10347, 0x102F0)

    # TXT reader bookmark action.
    patch_const_string_jumbo(data, 0x206E18, 2, 0x103E5, 0x00000)  # 添 -> empty
    assert bytes(data[0x206E4E:0x206E54]) == bytes.fromhex('22005b007010')
    patch_background_preserve_v5(data, 0x206E4E, 0x206E84)

    # EPUB reader bookmark action.
    patch_const_string_jumbo(data, 0x214C0A, 2, 0x10400, 0x00000)  # 签 -> empty
    assert bytes(data[0x214C40:0x214C46]) == bytes.fromhex('22005b007010')
    patch_background_preserve_v5(data, 0x214C40, 0x214C74)

    fix_dex_header(data)
    target.write_bytes(data)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('input_dex', type=Path)
    parser.add_argument('output_dex', type=Path)
    args = parser.parse_args()
    patch_classes3(args.input_dex, args.output_dex)
