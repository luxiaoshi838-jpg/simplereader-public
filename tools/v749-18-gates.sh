#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v749-17-gates.sh
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
P=app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt
V=app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt
N=app/src/main/java/com/simplereader/app/reader/ReaderBodyTitleNormalizerV104.kt
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
# 18: shipped v745 placement and V634/V635 vertical inter-item spacing contract.
python3 - <<'PY' || exit 1
from pathlib import Path
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
p=Path('app/src/main/java/com/simplereader/app/ui/PagedReaderView.kt').read_text()
v=Path('app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt').read_text()
n=Path('app/src/main/java/com/simplereader/app/reader/ReaderBodyTitleNormalizerV104.kt').read_text()
# v745 renderPage returns PageEngine.styledText output directly (except search highlighting): V104 must not touch vertical ReaderPage text.
start=r.index('private fun renderPage(')
end=r.index('private fun ensureVerticalReader()', start)
block=r[start:end]
if 'ReaderBodyTitleNormalizerV104' in block or 'normalizedRendered' in block:
    raise SystemExit('FAIL 18 V104 still mutates renderPage/vertical text')
if 'val hit = activeSearchHit ?: return rendered' not in block:
    raise SystemExit('FAIL 18 renderPage does not return raw styled text like v745')
# v745 applies V104 only at horizontal PagedReaderView snapshot bind/updateAdjacent.
if p.count('normalizeSnapshot') < 5:
    raise SystemExit('FAIL 18 horizontal snapshot V104 placement incomplete')
if 'fun normalizeSnapshot(snapshot: ReaderPageSnapshot)' not in n:
    raise SystemExit('FAIL 18 normalizeSnapshot entry missing')
# v634/v635 exact vertical gap contract: every non-final item gets the inter-item font-metrics gap; final item gets zero; terminal newline removed on non-final items.
required=[
 'includeFontPadding = false',
 'view.setLineSpacing(0f, activity.verticalLineSpacingMultiplier())',
 'view.paint.getFontMetricsInt(null)',
 '(view.lineHeight - fontHeight).coerceAtLeast(0)',
 'if (position == pages.lastIndex) 0 else finalGap',
 "if (position != pages.lastIndex && text.isNotEmpty() && text.last() == '\\n')",
 'text.subSequence(0, text.length - 1)',
 'view.setText(text, TextView.BufferType.SPANNABLE)'
]
for token in required:
    if token not in v:
        raise SystemExit('FAIL 18 vertical spacing token missing: '+token)
PY
pass 18 'v745 horizontal-only V104 placement + v634/v635 equal vertical line-gap contract'
printf 'ALL_18_GATES_PASS\n'
