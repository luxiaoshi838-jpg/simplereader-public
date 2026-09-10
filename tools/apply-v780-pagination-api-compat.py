#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
engine = root / 'app/src/main/java/com/simplereader/app/reader/page/PageEngine.kt'
s = engine.read_text(encoding='utf-8')

# Keep ImageSpanProvider as the final parameter so all historical trailing-lambda call sites
# (ShelfCacheWorker and others) still bind to the image provider. The new cancellation probe is
# inserted before it and is used by name from ReaderActivity.
old = '''        typeface: Typeface = Typeface.DEFAULT,
        imageSpanProvider: ImageSpanProvider? = null,
        shouldCancel: (() -> Boolean)? = null
    ): ReaderBook {
'''
new = '''        typeface: Typeface = Typeface.DEFAULT,
        shouldCancel: (() -> Boolean)? = null,
        imageSpanProvider: ImageSpanProvider? = null
    ): ReaderBook {
'''
if new not in s:
    if old not in s:
        raise SystemExit('v780 PageEngine compatibility signature anchor missing')
    s = s.replace(old, new, 1)
engine.write_text(s, encoding='utf-8')
print('v780 applied: PageEngine keeps historical trailing-lambda ImageSpanProvider compatibility')
