from pathlib import Path
import struct

# Lightweight binary guard for the canonical v722 -> v727 classes3 overlay.
# Run against the patched classes3.dex before packaging.
p = Path('classes3_v727.dex')
b = p.read_bytes()

# ReaderActivity already uses 111; v727 worker must match it.
assert b[0x203f82:0x203f86] == bytes.fromhex('13176f00'), 'ReaderActivity catalog rule != 111'
assert b[0x21b220:0x21b224] == bytes.fromhex('13216f00'), 'ShelfCacheWorker catalog rule != 111'

# Shared default content top/bottom padding literal is 0 after patch.
assert b[0x1e594c:0x1e5950] == bytes.fromhex('13050000'), 'content padding fallback not 0'

# DEX header file size sanity.
assert struct.unpack_from('<I', b, 32)[0] == len(b), 'DEX file size header mismatch'
print('v727 cache identity guard: OK')
