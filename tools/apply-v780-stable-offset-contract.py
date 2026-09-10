#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt'
s = path.read_text(encoding='utf-8')
old = '''                val stableOffset = liveFontOffset
                    ?: preserveOffset
                    ?: lastStableSourceOffset
'''
new = '''                val stableOffset = preserveOffset
                    ?.takeIf { fontRequestId == null }
                    ?: liveFontOffset
                    ?: lastStableSourceOffset
'''
if new in s:
    print('v780 stable-offset compatibility already applied')
elif old in s:
    path.write_text(s.replace(old, new, 1), encoding='utf-8')
    print('v780 applied: historical stable-offset contract + live font commit position')
else:
    raise SystemExit('v780 stable-offset anchor missing')
