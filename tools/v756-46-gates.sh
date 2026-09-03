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
A=app/src/main/java/com/simplereader/app/ui/VerticalPageAdapter.kt
TEST=app/src/test/java/com/simplereader/app/parser/TxtCatalogRule112Test.kt

# 35-42: carry forward v754/v755 behavior without hard-coding an older app version.
grep -Fq '2098000756' "$B" && grep -Fq '"756"' "$B" && grep -Fq 'CATALOG_RULE_VERSION = 113' "$T" && grep -Fq 'RULE_VERSION = 113' "$D" || fail 35 'v756/rule113 versions missing'
pass 35 'v756 version + catalog rule113'

for token in 'explicitNumberedTitle' 'numberLeadingUnit' 'hasValidStructuralTail' 'numericOnly.matches(s)'; do
  grep -Fq "$token" "$D" || fail 36 "rule113 direct structural-boundary guard missing: $token"
done
for token in 'hasGluedPrefixedStructuralText' 'if (hasGluedPrefixedStructuralText(normalized)) return null'; do
  grep -Fq "$token" "$T" || fail 36 "rule113 fallback structural-boundary guard missing: $token"
done
for token in '3节课' '3节 课' '3节：课' '第3节课' '第3节 课' '12章鱼' '12章 鱼' '一条' '1天'; do
  grep -Fq "$token" "$TEST" || fail 36 "rule113 boundary regression test missing: $token"
done
pass 36 'rule113 rejects glued numeral+noun phrases in direct+fallback; accepts independent chapter units'

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

# Gate 43: user drag must clear the active search state before any visual rebind.
python3 - "$A" <<'PY'
from pathlib import Path
import re, sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'override fun onScrollStateChanged\(.*?\n    \}',s,re.S)
if not m: raise SystemExit('onScrollStateChanged missing')
b=m.group(0)
for token in ['RecyclerView.SCROLL_STATE_DRAGGING','activity.verticalOnUserDrag()','recyclerView.post','clearTransientSearchHighlight(recyclerView)']:
    if token not in b: raise SystemExit(f'missing drag highlight contract: {token}')
if b.index('activity.verticalOnUserDrag()') > b.index('clearTransientSearchHighlight(recyclerView)'):
    raise SystemExit('search state must clear before adapter rebind')
PY
pass 43 'drag clears active search state before visual rebind'

# Gate 44: stale highlighted CharSequences must be discarded and only visible rows rebound.
python3 - "$A" <<'PY'
from pathlib import Path
import re, sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView\) \{(.*?)\n    \}',s,re.S)
if not m: raise SystemExit('clearTransientSearchHighlight missing')
b=m.group(1)
for token in ['rendered.evictAll()','findFirstVisibleItemPosition()','findLastVisibleItemPosition()','notifyItemRangeChanged']:
    if token not in b: raise SystemExit(f'missing visible-only rebind contract: {token}')
PY
pass 44 'search highlight cache discarded; visible rows rebound'

# Gate 45: highlight removal is presentation-only and cannot mutate reading position.
python3 - "$A" <<'PY'
from pathlib import Path
import re, sys
s=Path(sys.argv[1]).read_text(encoding='utf-8')
m=re.search(r'fun clearTransientSearchHighlight\(recyclerView: RecyclerView\) \{(.*?)\n    \}',s,re.S)
if not m: raise SystemExit('clearTransientSearchHighlight missing')
b=m.group(1)
for forbidden in ['scrollToPosition','scrollToPositionWithOffset','smoothScroll','scrollBy(','jumpToPage','currentPageIndex =','suspendedAnchorOffset','lastStableSourceOffset']:
    if forbidden in b: raise SystemExit(f'highlight clear must not mutate reader position: {forbidden}')
PY
pass 45 'highlight clear cannot move/restore reader position'

# Gate 46: use the WorkManager 2.10 stable line containing the upstream foreground
# dataSync/shortService stopSelf timeout fix without forcing this AGP 8.1.x project to AGP 8.6.
grep -Fq 'androidx.work:work-runtime-ktx:2.10.5' "$B" || fail 46 'WorkManager 2.10.5 required'
pass 46 'Android foreground SystemForegroundService timeout fix on AGP-compatible WorkManager line'

printf 'ALL_46_GATES_PASS\n'
