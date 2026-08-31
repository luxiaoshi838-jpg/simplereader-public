#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v750-19-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }

# 20: exact shipped-v745 / v634 RecyclerView inter-item line-gap direction.
V=app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt
grep -Fq 'if (position == pages.lastIndex) 0 else finalGap' "$V" || fail 20 'non-final v634 line-gap condition missing'
! grep -Fq 'if (position == pages.lastIndex) finalGap else 0' "$V" || fail 20 'reversed line-gap regression returned'
grep -Fq "if (position != pages.lastIndex && text.isNotEmpty() && text.last() == '\\n')" "$V" || fail 20 'v634 terminal-newline normalization missing'
pass 20 'v745/v634 vertical item-boundary line spacing: gap on non-final items only'

# 21: add-bookmark action is nudged left but retains explicit spacing from search, TXT + EPUB.
for R in app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt app/src/main/java/com/simplereader/app/ui/ReadiumEpubActivity.kt; do
  grep -Fq 'marginStart = dp(8)' "$R" || fail 21 "bookmark/search spacing missing in $R"
  grep -Fq 'marginEnd = dp(16)' "$R" || fail 21 "bookmark left offset missing in $R"
done
pass 21 'TXT/EPUB add-bookmark action shifted left with explicit search gap'

# 22: only the two boundary haze overlays are narrowed to 28dp.
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/res/layout/activity_reader.xml').read_text()
for vid in ('readerTopHaze','readerBottomHaze'):
    i=s.index(f'android:id="@+id/{vid}"')
    j=s.index('/>', i)
    b=s[i:j]
    if 'android:layout_height="28dp"' not in b:
        raise SystemExit('FAIL 22 '+vid+' is not 28dp')
    if 'android:layout_height="36dp"' in b:
        raise SystemExit('FAIL 22 '+vid+' still uses old 36dp height')
PY
pass 22 'top/bottom boundary haze overlays narrowed to 28dp'
printf 'ALL_22_GATES_PASS\n'
