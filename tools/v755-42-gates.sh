#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash tools/v753-34-gates.sh
pass(){ printf 'PASS %02d %s\n' "$1" "$2"; }
fail(){ printf 'FAIL %02d %s\n' "$1" "$2" >&2; exit 1; }
R=app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt
D=app/src/main/java/com/simplereader/app/reader/DirectTxtCatalogV100.kt
T=app/src/main/java/com/simplereader/app/parser/TxtParser.kt
X=app/src/main/res/layout/activity_reader.xml
B=app/build.gradle.kts

grep -Fq '2098000755' "$B" && grep -Fq '"755"' "$B" && grep -Fq 'CATALOG_RULE_VERSION = 112' "$T" && grep -Fq 'RULE_VERSION = 112' "$D" || fail 35 'v754/rule112 versions missing'
pass 35 'v754 version + catalog rule112'

grep -Fq 'wrappedChineseSuffix' "$D" && grep -Fq 'wrappedLeadingUnit' "$D" && grep -Fq 'numericOnly.matches(s)' "$D" || fail 36 'rule112 false-positive guards missing'
! grep -Fq "unit == '回' && m.range.first != 0" "$D" || fail 36 'embedded 回 terminator bypass returned'
pass 36 'rule112 parenthetical/numeric/terminator guards'

grep -Fq 'isFastScrollEnabled = true' "$R" && grep -Fq 'isFastScrollAlwaysVisible = true' "$R" || fail 37 'catalog-only fast scroll missing'
! grep -Fq '${rowIndex + 1}.${chapter.title}' "$R" || fail 37 'artificial catalog row numbering returned'
grep -Fq 'jumpToPage(paged.firstPageOfChapter(chapterIndex), false)' "$R" || fail 37 'catalog click navigation missing'
pass 37 'catalog scroll only browses catalog; click navigates body; no artificial numbering'

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root=ET.parse('app/src/main/res/layout/activity_reader.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
parent={c:p for p in root.iter() for c in p}
stop=next(e for e in root.iter() if e.get(ns+'id')=='@+id/autoReadStopButton')
viewport=next(e for e in root.iter() if e.get(ns+'id')=='@+id/readerViewport')
assert parent[stop] is viewport
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun startAutoReading()')
e=r.index('    private fun scheduleAutomaticPageTurn()',s)
b=r[s:e]
assert 'setReaderChromeVisible(false)' in b
assert b.index('setReaderChromeVisible(false)') < b.index('showAutoReadStopButton()')
PY
pass 38 'auto-reading hides chrome; stop control is inside guarded viewport'

for token in 'suspendedAnchorOffset = pages[index].startOffset' 'suspendedAnchorViewportPx = verticalLayoutManager?.findViewByPosition(index)?.top ?: 0' 'scrollToPositionWithOffset(pageIndex, viewportOffset)' 'lastStableSourceOffset = anchorOffset'; do
  grep -Fq "$token" "$R" || fail 39 "focus anchor behavior missing: $token"
done
pass 39 'focus loss captures/restores source + pixel anchor'

grep -Fq '?: lastStableSourceOffset' "$R" || fail 40 'stable source fallback missing'
grep -Fq 'val anchor = (lastStableSourceOffset ?: currentVisibleSourceOffset())' "$R" || fail 40 'fallback not anchored'
python3 - <<'PY'
from pathlib import Path
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun showContinuousFallback(')
e=r.index('    private fun sourceOffsetAtScroll(',s)
b=r[s:e]
assert 'continuousWindowStartOffset = 0' not in b
assert 'loaded.text.substring(start, end)' in b
PY
pass 40 'pagination/fallback cannot hard-reset active reading to page 1'

grep -Fq 'onHit = { hit -> jumpToPage(hit.globalPageIndex, false, hit) }' "$R" || fail 41 'search hit navigation missing'
python3 - <<'P2'
from pathlib import Path
r=Path('app/src/main/java/com/simplereader/app/ui/ReaderActivity.kt').read_text()
s=r.index('    private fun jumpToPage(')
e=r.index('    private fun jumpChapter(', s)
b=r[s:e]
assert 'val targetPage = pages[currentPageIndex]' in b
assert 'if (pageTurnMode == TURN_MODE_VERTICAL && verticalWindowSuspended)' in b
assert 'suspendedAnchorOffset = targetPage.startOffset' in b
assert 'suspendedAnchorViewportPx = 0' in b
P2
pass 41 'explicit search/navigation replaces stale dialog restore anchor'

for token in 'RecyclerView(this)' 'LinearLayoutManager(this, RecyclerView.VERTICAL, false)' 'verticalAdapter?.setPages(paged.pages)' 'suspendedAnchorOffset = pages[index].startOffset' 'scrollToPositionWithOffset(pageIndex, viewportOffset)'; do
  grep -Fq "$token" "$R" || fail 42 "anti-rollback architecture missing: $token"
done
pass 42 'RecyclerView virtualized reader and focus anti-rollback preserved'
printf 'ALL_42_GATES_PASS\n'
