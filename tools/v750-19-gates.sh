#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v749-18-gates.sh
M=app/src/main/java/com/simplereader/app/ui/MainActivity.kt
G=app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
python3 - <<'PY'
from pathlib import Path
m=Path('app/src/main/java/com/simplereader/app/ui/MainActivity.kt').read_text()
g=Path('app/src/main/java/com/simplereader/app/ui/GroupBooksActivity.kt').read_text()
# Main shelf: title belongs inside createBookCover fallback only. After the cover, card may show progress/status but not book.title.
a=m.index('private fun addBookCard(')
b=m.index('private fun createBookCover(', a)
card=m[a:b]
if 'book.title' in card:
    raise SystemExit('FAIL 19 main shelf repeats book.title outside cover')
# Group shelf: BookCardView must not own/bind/add a title TextView below cover.
a=g.index('private class BookCardView(')
b=g.index('private fun updateSelection(', a)
card=g[a:b]
for bad in ['private val title = TextView(context)', 'content.addView(title)', 'title.text = book.title', 'title.setTextColor']:
    if bad in card:
        raise SystemExit('FAIL 19 group shelf title remains below cover: '+bad)
# Both shelf fallback covers must still render the title on the cover itself.
if 'fallback = TextView(this).apply' not in m or 'book.title.take(22)' not in m:
    raise SystemExit('FAIL 19 main cover title fallback missing')
if 'coverFallback.text = book.title.take(22)' not in g:
    raise SystemExit('FAIL 19 group cover title fallback missing')
PY
pass 19 'all shelf render paths show book title on cover only; no duplicate title below cover'
printf 'ALL_19_GATES_PASS\n'
